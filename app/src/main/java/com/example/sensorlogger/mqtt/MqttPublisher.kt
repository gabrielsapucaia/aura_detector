package com.example.sensorlogger.mqtt

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.sensorlogger.BuildConfig
import com.example.sensorlogger.model.TelemetryPayloadV11
import com.example.sensorlogger.net.BrokerGuard
import com.example.sensorlogger.net.WifiBoundSocketFactory
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MqttPublisher(
    private val context: Context,
    private val json: Json = Json { encodeDefaults = true },
    private val deviceIdProvider: () -> String = { BuildConfig.DEVICE_ID }
) {

    private val configuredEndpoints: List<String> = run {
        val configured = BuildConfig.MQTT_SERVER_URIS
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val primaryFallback = BuildConfig.MQTT_HOST.takeIf { it.isNotBlank() }?.let {
            "${BuildConfig.MQTT_SCHEME}://$it:${BuildConfig.MQTT_PORT}"
        }
        (configured + listOfNotNull(primaryFallback)).distinct()
    }
    @Volatile
    private var endpoints: List<String> = configuredEndpoints
    private val discoveryPrefix = BuildConfig.MQTT_DISCOVERY_PREFIX
    private val discoveryRange = BuildConfig.MQTT_DISCOVERY_RANGE
    private val discoveryTimeoutMs = BuildConfig.MQTT_DISCOVERY_TIMEOUT_MS
    private val discoveryEnabled = discoveryPrefix.isNotBlank() && discoveryRange.isNotBlank()
    private var discoveryAttempted = false
    private val mutex = Mutex()
    private val _statuses = MutableStateFlow(
        mapOf(
            NAME_PRIMARY to BrokerStatus(
                enabled = endpoints.isNotEmpty(),
                state = if (endpoints.isNotEmpty()) BrokerStatus.State.Disconnected else BrokerStatus.State.Disabled,
                activeEndpoint = null
            )
        )
    )
    val statuses: StateFlow<Map<String, BrokerStatus>> = _statuses.asStateFlow()

    private var keepAliveJob: Job? = null
    @Volatile
    private var consecutivePingFailures = 0
    @Volatile
    private var brokerVerified = false
    private var client: MqttAsyncClient? = null
    private var statusAnnounced: Boolean = false
    private var activeEndpoint: String? = null
    private var connectOptions: MqttConnectOptions? = null
    private var connectAwaiter: CompletableDeferred<Unit>? = null
    private val expectedBrokerId = BuildConfig.BROKER_ID_EXPECTED
    private val brokerSpkiPin = BuildConfig.BROKER_SPKI_PIN

    suspend fun publishTelemetry(
        deviceId: String,
        payload: TelemetryPayloadV11,
        lastSnapshot: ByteArray? = null,
        targetFilter: Set<String>? = null
    ): Map<String, Boolean> = mutex.withLock {
        if (targetFilter != null && NAME_PRIMARY !in targetFilter) {
            return@withLock emptyMap()
        }

        if (!hasEndpoints()) {
            updateStatus(BrokerStatus.State.Disabled)
            return@withLock mapOf(NAME_PRIMARY to false)
        }

        if (!brokerVerified) {
            brokerVerified = BrokerGuard.isVerified()
        }

        if (!isOnWifi()) {
            Log.i(TAG, "publishTelemetry gated for device=$deviceId: active network is not Wi-Fi; enqueue offline")
            updateStatus(BrokerStatus.State.Disconnected)
            return@withLock mapOf(NAME_PRIMARY to false)
        }

        if (!brokerVerified) {
            if (expectedBrokerId.isBlank()) {
                Timber.w("Broker verification skipped: expected broker id is blank")
                brokerVerified = true
            } else {
                val endpoint = activeEndpoint ?: endpoints.firstOrNull()
                if (endpoint.isNullOrBlank()) {
                    Timber.w("Broker verification aborted: no endpoint available")
                    return@withLock mapOf(NAME_PRIMARY to false)
                }
                Timber.i("Verifying broker identity for endpoint %s", endpoint)
                val verified = BrokerGuard.verify(
                    context = context,
                    brokerUrl = endpoint,
                    clientIdProbe = "${clientId()}-probe",
                    identityTopic = BROKER_IDENTITY_TOPIC,
                    expectedBrokerId = expectedBrokerId,
                    tlsSpkiPin = brokerSpkiPin.takeIf { it.isNotBlank() },
                    username = BuildConfig.MQTT_USERNAME.takeIf { it.isNotBlank() },
                    passwordChars = BuildConfig.MQTT_PASSWORD.takeIf { it.isNotBlank() }?.toCharArray()
                )
                if (!verified) {
                    Timber.w("Broker verification failed for endpoint %s; enqueuing offline", endpoint)
                    return@withLock mapOf(NAME_PRIMARY to false)
                }
                brokerVerified = true
                Timber.i("Broker identity verified for endpoint %s", endpoint)
            }
        }

        val payloadBytes = json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val mqttClient = ensureConnected()
                publishTelemetry(mqttClient, payloadBytes)
                lastSnapshot?.let { publishLastSnapshot(mqttClient, it) }
                true
            }.onFailure { ex ->
                Timber.w(ex, "MQTT publish failed")
                updateStatus(BrokerStatus.State.Failed)
            }.getOrElse { false }
        }
        if (ok) {
            consecutivePingFailures = 0
            updateStatus(BrokerStatus.State.Connected)
        }
        mapOf(NAME_PRIMARY to ok)
    }

    fun resetVerification() {
        brokerVerified = false
        BrokerGuard.reset()
    }

    suspend fun disconnectAll(@Suppress("UNUSED_PARAMETER") deviceId: String?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            closeClient(publishOffline = true)
            updateStatus(BrokerStatus.State.Disconnected)
            consecutivePingFailures = 0
        }
    }

    suspend fun reconnect(resetBackoff: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (resetBackoff) {
                consecutivePingFailures = 0
            }
            closeClient(publishOffline = true)
            runCatching { ensureConnected() }
                .onSuccess {
                    consecutivePingFailures = 0
                    updateStatus(BrokerStatus.State.Connected)
                }
                .onFailure {
                    Timber.w(it, "MQTT reconnection failed")
                    updateStatus(BrokerStatus.State.Failed)
                }
        }
    }

    fun startKeepAlive(scope: CoroutineScope) {
        if (keepAliveJob?.isActive == true) return
        keepAliveJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    delay(KEEP_ALIVE_INTERVAL_MS)
                    when (performPing()) {
                        PingResult.OK -> consecutivePingFailures = 0
                        PingResult.DISCONNECTED -> consecutivePingFailures = 0
                        PingResult.FAILED -> {
                            consecutivePingFailures++
                            if (consecutivePingFailures >= KEEP_ALIVE_FAILURE_THRESHOLD) {
                                Timber.w(
                                    "MQTT keep-alive ping failed %d times; triggering reconnect",
                                    KEEP_ALIVE_FAILURE_THRESHOLD
                                )
                                consecutivePingFailures = 0
                                runCatching { reconnect(resetBackoff = true) }
                                    .onFailure { Timber.w(it, "MQTT reconnect after ping failure failed") }
                            }
                        }
                    }
                }
            } finally {
                keepAliveJob = null
            }
        }
    }

    fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    fun enabledLabels(): Set<String> =
        if (hasEndpoints() || discoveryEnabled) setOf(NAME_PRIMARY) else emptySet()

    private suspend fun ensureConnected(): MqttAsyncClient {
        if (!hasEndpoints()) {
            if (!attemptDiscovery()) {
                throw IllegalStateException("No MQTT endpoints configured")
            }
        }

        var mqttClient = client
        if (mqttClient == null) {
            mqttClient = createClient()
        }
        if (mqttClient.isConnected) {
            if (!statusAnnounced) {
                publishStatus(mqttClient, STATUS_ONLINE)
                statusAnnounced = true
            }
            return mqttClient
        }

        val existingAwaiter = connectAwaiter
        if (existingAwaiter != null) {
            if (!mqttClient.isConnected) {
                throw MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED.toInt())
            }
            existingAwaiter.await()
            return mqttClient
        }

        activeEndpoint = null
        updateStatus(BrokerStatus.State.Connecting)
        val waiter = CompletableDeferred<Unit>()
        connectAwaiter = waiter
        val options = connectOptions ?: buildConnectOptions().also { connectOptions = it }
        try {
            suspendToken { mqttClient.connect(options, null, it) }
            waiter.complete(Unit)
        } catch (ex: Exception) {
            if (!waiter.isCompleted) {
                waiter.completeExceptionally(ex)
            }
            if (attemptDiscovery()) {
                return ensureConnected()
            }
            throw ex
        } finally {
            connectAwaiter = null
        }

        activeEndpoint = runCatching { mqttClient.serverURI }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: endpoints.firstOrNull()


        publishStatus(mqttClient, STATUS_ONLINE)
        statusAnnounced = true
        Timber.i("MQTT connected")
        updateStatus(BrokerStatus.State.Connected)
        return mqttClient
    }

    private suspend fun publishTelemetry(mqttClient: MqttAsyncClient, payload: ByteArray) {
        val message = MqttMessage(payload).apply {
            qos = 1
            isRetained = false
        }
        if (!mqttClient.isConnected) {
            throw MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED.toInt())
        }
        suspendToken { mqttClient.publish(telemetryTopic(), message, null, it) }
    }

    private suspend fun publishLastSnapshot(mqttClient: MqttAsyncClient, payload: ByteArray) {
        val message = MqttMessage(payload).apply {
            qos = 1
            isRetained = true
        }
        if (!mqttClient.isConnected) {
            throw MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED.toInt())
        }
        suspendToken { mqttClient.publish(lastTopic(), message, null, it) }
    }

    private suspend fun publishStatus(mqttClient: MqttAsyncClient, value: String) {
        val message = MqttMessage(value.toByteArray(StandardCharsets.UTF_8)).apply {
            qos = 1
            isRetained = true
        }
        suspendToken { mqttClient.publish(statusTopic(), message, null, it) }
    }

    private suspend fun closeClient(publishOffline: Boolean) {
        val mqttClient = client ?: return
        resetVerification()
        connectAwaiter?.cancel()
        connectAwaiter = null
        connectOptions = null
        discoveryAttempted = false
        runCatching {
            if (publishOffline && mqttClient.isConnected) {
                publishStatus(mqttClient, STATUS_OFFLINE)
            }
        }
        runCatching {
            if (mqttClient.isConnected) {
                suspendToken { mqttClient.disconnect(null, it) }
            }
        }
        mqttClient.close()
        client = null
        statusAnnounced = false
        activeEndpoint = null
    }

    private fun telemetryTopic(): String = "${BuildConfig.MQTT_TOPIC_BASE}/${deviceIdForTopics()}"
    private fun statusTopic(): String = "${BuildConfig.MQTT_TOPIC_STATUS}/${deviceIdForTopics()}"
    private fun lastTopic(): String = "${BuildConfig.MQTT_TOPIC_LAST}/${deviceIdForTopics()}"

    private fun hasEndpoints(): Boolean = endpoints.isNotEmpty()

    private fun deviceIdForTopics(): String = topicDeviceId

    private fun clientId(): String = resolvedClientId

    private val providerDeviceId: String by lazy {
        deviceIdProvider().trim().ifEmpty { DEFAULT_DEVICE_ID }
    }

    private val topicDeviceId: String by lazy {
        val configured = BuildConfig.DEVICE_ID.trim()
        val useConfigured = configured.isNotEmpty() && configured != DEFAULT_DEVICE_ID
        if (useConfigured) configured else providerDeviceId
    }

    private val resolvedClientId: String by lazy {
        val sanitized = providerDeviceId.replace(CLIENT_ID_SANITIZE_REGEX, "_")
        val withPrefix = CLIENT_ID_PREFIX + sanitized
        if (withPrefix.length <= MAX_CLIENT_ID_LENGTH) {
            withPrefix
        } else {
            withPrefix.take(MAX_CLIENT_ID_LENGTH)
        }
    }

    private fun updateStatus(state: BrokerStatus.State, endpoint: String? = activeEndpoint) {
        val enabled = hasEndpoints()
        val effectiveState = if (enabled) state else BrokerStatus.State.Disabled
        val active = if (enabled) endpoint else null
        _statuses.value = mapOf(
            NAME_PRIMARY to BrokerStatus(
                enabled = enabled,
                state = effectiveState,
                activeEndpoint = active
            )
        )
    }

    private fun createClient(): MqttAsyncClient {
        val mqttClient = MqttAsyncClient(
            endpoints.first(),
            clientId(),
            null
        )
        val bufferOptions = DisconnectedBufferOptions().apply {
            isBufferEnabled = false  // Disable internal buffer - use OfflineQueue instead
            isPersistBuffer = false
        }
        mqttClient.setBufferOpts(bufferOptions)
        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                statusAnnounced = false
                activeEndpoint = serverURI ?: endpoints.firstOrNull()
                discoveryAttempted = false
                connectAwaiter?.let { waiter ->
                    if (!waiter.isCompleted) {
                        waiter.complete(Unit)
                    }
                }
                Timber.i("MQTT connection complete (reconnect=%s)", reconnect)
                updateStatus(BrokerStatus.State.Connected)
            }

            override fun connectionLost(cause: Throwable?) {
                statusAnnounced = false
                discoveryAttempted = false
                Timber.w(cause, "MQTT connection lost")
                updateStatus(BrokerStatus.State.Reconnecting)
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) = Unit
            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        client = mqttClient
        connectOptions = buildConnectOptions()
        return mqttClient
    }

    private fun buildConnectOptions(): MqttConnectOptions =
        MqttConnectOptions().apply {
            isAutomaticReconnect = false  // Disable Paho auto-reconnect - we handle it manually
            isCleanSession = true
            keepAliveInterval = 30
            connectionTimeout = 10
            if (BuildConfig.MQTT_USERNAME.isNotBlank()) {
                userName = BuildConfig.MQTT_USERNAME
            }
            if (BuildConfig.MQTT_PASSWORD.isNotBlank()) {
                password = BuildConfig.MQTT_PASSWORD.toCharArray()
            }
            socketFactory = WifiBoundSocketFactory.get(context)
            setWill(statusTopic(), STATUS_OFFLINE.toByteArray(StandardCharsets.UTF_8), 1, true)
            if (endpoints.size > 1) {
                serverURIs = endpoints.toTypedArray()
            }
        }

    private suspend fun attemptDiscovery(): Boolean {
        if (!discoveryEnabled || discoveryAttempted) return false
        discoveryAttempted = true
        val prefixes = discoveryPrefix.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        Timber.i("Attempting MQTT broker discovery on prefixes: %s", prefixes.joinToString(", "))
        val allHosts = mutableListOf<String>()
        for (prefix in prefixes) {
            val hosts = BrokerDiscovery.scan(
                prefix = prefix,
                rangeSpec = discoveryRange,
                port = BuildConfig.MQTT_PORT,
                timeoutMs = discoveryTimeoutMs
            )
            allHosts.addAll(hosts)
            if (allHosts.size >= 3) break
        }
        if (allHosts.isEmpty()) {
            Timber.i("Broker discovery did not find any hosts")
            return false
        }
        val uris = allHosts.map { "${BuildConfig.MQTT_SCHEME}://$it:${BuildConfig.MQTT_PORT}" }
        val combined = (uris + configuredEndpoints).distinct()
        setEndpoints(combined)
        Timber.i("Discovered MQTT brokers: %s", allHosts.joinToString(", "))
        return true
    }

    private fun setEndpoints(candidates: List<String>) {
        val sanitized = candidates.map { it.trim() }.filter { it.isNotEmpty() }
        if (sanitized.isEmpty()) return
        if (sanitized == endpoints) return
        endpoints = sanitized
        resetVerification()
        resetClient()
        updateStatus(BrokerStatus.State.Disconnected)
    }

    private fun resetClient() {
        resetVerification()
        connectAwaiter?.cancel()
        connectAwaiter = null
        discoveryAttempted = false
        val current = client ?: return
        runCatching { current.setCallback(null) }
        runCatching {
            if (current.isConnected) {
                current.disconnectForcibly(0L, 0L)
            }
        }
        runCatching { current.close() }
        client = null
        statusAnnounced = false
        activeEndpoint = null
    }

    private suspend fun performPing(): PingResult = mutex.withLock {
        val mqttClient = client ?: return PingResult.DISCONNECTED
        if (!mqttClient.isConnected) {
            return PingResult.DISCONNECTED
        }
        return runCatching {
            suspendToken { listener ->
                mqttClient.checkPing(null, listener)
            }
            PingResult.OK
        }.getOrElse { throwable ->
            Timber.w(throwable, "MQTT keep-alive ping failed")
            PingResult.FAILED
        }
    }

    private suspend fun suspendToken(block: (IMqttActionListener) -> IMqttToken?) {
        suspendCancellableCoroutine { continuation ->
            var completedSynchronously = false
            val listener = object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    if (continuation.isActive) {
                        val cause = exception ?: MqttException(RuntimeException("MQTT action failed"))
                        continuation.resumeWithException(cause)
                    }
                }
            }
            val token = try {
                block(listener)
            } catch (ex: Exception) {
                continuation.resumeWithException(ex)
                return@suspendCancellableCoroutine
            }
            if (token == null) {
                // Some synchronous operations (e.g. checkPing on MQTT v5) complete without a token.
                completedSynchronously = true
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
            if (!completedSynchronously) {
                continuation.invokeOnCancellation {
                    runCatching { token?.waitForCompletion(500) }
                }
            }
        }
    }

    data class BrokerStatus(
        val enabled: Boolean,
        val state: State,
        val activeEndpoint: String?
    ) {
        enum class State {
            Disabled,
            Connecting,
            Disconnected,
            Connected,
            Reconnecting,
            Failed
        }
    }

    private enum class PingResult {
        OK,
        FAILED,
        DISCONNECTED
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    companion object {
        const val NAME_PRIMARY = "primary"
        private const val TAG = "MqttPublisher"
        private const val STATUS_ONLINE = "online"
        private const val STATUS_OFFLINE = "offline"
        private const val BROKER_IDENTITY_TOPIC = "aurabrokeridentity"
        private const val CLIENT_ID_PREFIX = "sensorlogger-"
        private const val MAX_CLIENT_ID_LENGTH = 64
        private const val DEFAULT_DEVICE_ID = "sensorlogger-device"
        private val CLIENT_ID_SANITIZE_REGEX = "[^A-Za-z0-9_-]".toRegex()
        private const val DISCONNECTED_BUFFER_SIZE = 2048
        private const val KEEP_ALIVE_INTERVAL_MS = 45_000L
        private const val KEEP_ALIVE_FAILURE_THRESHOLD = 2
    }
}

