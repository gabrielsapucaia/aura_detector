package com.example.sensorlogger.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Realiza verificação defensiva do broker MQTT antes de permitir publicação.
 */
object BrokerGuard {

    private const val TAG = "BrokerGuard"
    private const val DEFAULT_IDENTITY_TOPIC = "aurabrokeridentity"
    private const val CONNECT_TIMEOUT_SECONDS = 8
    private const val IDENTITY_TIMEOUT_MILLIS = 5_000L

    private val verified = AtomicBoolean(false)
    private val lock = Mutex()

    fun reset() {
        if (verified.getAndSet(false)) {
            Timber.i("%s reset: broker will require re-verification", TAG)
        }
    }

    fun isVerified(): Boolean = verified.get()

    suspend fun verify(
        context: Context,
        brokerUrl: String,
        clientIdProbe: String,
        identityTopic: String = DEFAULT_IDENTITY_TOPIC,
        expectedBrokerId: String,
        tlsSpkiPin: String? = null,
        username: String? = null,
        passwordChars: CharArray? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (expectedBrokerId.isBlank()) {
            Timber.w("%s verification skipped: expected broker id is blank", TAG)
            return@withContext false
        }
        if (verified.get()) {
            return@withContext true
        }
        if (!isOnWifi(context)) {
            Timber.i("%s verification aborted: active network is not Wi-Fi", TAG)
            return@withContext false
        }

        lock.withLock {
            if (verified.get()) {
                return@withLock true
            }

            Timber.i(
                "%s starting broker verification | url=%s expectedId=%s pin=%s",
                TAG,
                brokerUrl,
                expectedBrokerId,
                tlsSpkiPin?.takeUnless { it.isBlank() } ?: "none"
            )

            val result = runCatching {
                performVerification(
                    context = context,
                    brokerUrl = brokerUrl,
                    clientIdProbe = sanitizeClientId(clientIdProbe),
                    identityTopic = identityTopic,
                    expectedBrokerId = expectedBrokerId,
                    tlsSpkiPin = tlsSpkiPin,
                    username = username,
                    passwordChars = passwordChars
                )
            }.onFailure { error ->
                Timber.w(error, "%s broker verification failed", TAG)
            }.getOrDefault(false)

            verified.set(result)
            Timber.i("%s verification result=%s", TAG, result)
            result
        }
    }

    private suspend fun performVerification(
        context: Context,
        brokerUrl: String,
        clientIdProbe: String,
        identityTopic: String,
        expectedBrokerId: String,
        tlsSpkiPin: String?,
        username: String?,
        passwordChars: CharArray?
    ): Boolean {
        val uri = parseBrokerUri(brokerUrl)
        val scheme = uri.scheme?.lowercase()
        val isTls = scheme == "ssl" || scheme == "tls" || scheme == "mqtts" || scheme == "mqtt+ssl"

        val connectOptions = MqttConnectOptions().apply {
            isAutomaticReconnect = false
            isCleanSession = true
            keepAliveInterval = 20
            connectionTimeout = CONNECT_TIMEOUT_SECONDS
            username?.takeIf { it.isNotBlank() }?.let { userName = it }
            passwordChars?.let { this.password = it }
            socketFactory = createSocketFactory(context, isTls, tlsSpkiPin)
        }

        val deferredMessage = CompletableDeferred<MqttMessage>()
        val persistence = MemoryPersistence()
        val client = MqttAsyncClient(brokerUrl, clientIdProbe, persistence)
        try {
            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) = Unit
                override fun connectionLost(cause: Throwable?) {
                    if (!deferredMessage.isCompleted) {
                        deferredMessage.completeExceptionally(cause ?: IOException("Connection lost"))
                    }
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == identityTopic && message != null && !deferredMessage.isCompleted) {
                        deferredMessage.complete(message)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })

            client.connect(connectOptions, null, logListener("connect"))
                .waitForCompletion(CONNECT_TIMEOUT_SECONDS * 1_000L)

            client.subscribe(identityTopic, 1, null as Any?, logListener("subscribe"))
                .waitForCompletion(2_000)

            val retainedMessage = withTimeoutOrNull(IDENTITY_TIMEOUT_MILLIS) {
                deferredMessage.await()
            } ?: return false

            val brokerId = parseBrokerId(retainedMessage)
            if (brokerId != expectedBrokerId) {
                Timber.w("%s identity mismatch: expected=%s actual=%s", TAG, expectedBrokerId, brokerId)
                return false
            }

            Timber.i("%s broker identity confirmed: %s", TAG, brokerId)
            return true
        } finally {
            runCatching {
                if (client.isConnected) {
                    client.disconnect().waitForCompletion(2_000)
                }
            }
            runCatching { client.close() }
            passwordChars?.fill('\u0000')
        }
    }

    private fun createSocketFactory(
        context: Context,
        isTls: Boolean,
        tlsSpkiPin: String?
    ): SocketFactory {
        return if (isTls) {
            val sslContext = SSLContext.getInstance("TLS")
            val trustManagers = createPinnedTrustManagers(tlsSpkiPin)
            sslContext.init(null, trustManagers, SecureRandom())
            WifiBoundPinnedSslSocketFactory(context, sslContext.socketFactory)
        } else {
            WifiBoundSocketFactory.get(context)
        }
    }

    private fun createPinnedTrustManagers(pin: String?): Array<TrustManager>? {
        if (pin.isNullOrBlank()) {
            return null
        }
        val normalized = pin.removePrefix("sha256/").removePrefix("sha256:").removePrefix("sha256")
        val expected = try {
            Base64.decode(normalized, Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid SPKI pin format", error)
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as java.security.KeyStore?)
        }
        val defaultTm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("No default X509TrustManager available")

        val pinningTm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                defaultTm.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                defaultTm.checkServerTrusted(chain, authType)
                val leaf = chain.firstOrNull()
                    ?: throw CertificateException("Empty certificate chain")
                val digest = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
                if (!digest.contentEquals(expected)) {
                    throw CertificateException("SPKI pin mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTm.acceptedIssuers
        }
        return arrayOf(pinningTm)
    }

    private fun parseBrokerId(message: MqttMessage): String {
        val payload = runCatching {
            String(message.payload, Charsets.UTF_8)
        }.getOrDefault("")

        return runCatching {
            JSONObject(payload).optString("broker_id", "")
        }.getOrElse {
            Timber.w(it, "%s failed to parse broker identity payload", TAG)
            ""
        }
    }

    private fun parseBrokerUri(brokerUrl: String): URI {
        return try {
            URI(brokerUrl)
        } catch (error: URISyntaxException) {
            throw IllegalArgumentException("Invalid broker URL: $brokerUrl", error)
        }
    }

    private fun sanitizeClientId(candidate: String): String {
        val cleaned = candidate.replace("[^A-Za-z0-9_-]".toRegex(), "_")
        return if (cleaned.length <= 64) cleaned else cleaned.take(64)
    }

    private fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun logListener(op: String) = object : IMqttActionListener {
        override fun onSuccess(asyncActionToken: IMqttToken?) {
            Timber.d("%s %s succeeded", TAG, op)
        }

        override fun onFailure(
            asyncActionToken: IMqttToken?,
            exception: Throwable?
        ) {
            Timber.w(exception ?: Exception("unknown failure"), "%s %s failed", TAG, op)
        }
    }

    private class WifiBoundPinnedSslSocketFactory(
        private val context: Context,
        private val delegate: SSLSocketFactory
    ) : SSLSocketFactory() {

        private val wifiFactory = WifiBoundSocketFactory.get(context)

        private fun bind(socket: Socket): Socket = wifiFactory.bindSocket(socket)

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(): Socket = bind(delegate.createSocket())

        override fun createSocket(
            s: Socket?,
            host: String?,
            port: Int,
            autoClose: Boolean
        ): Socket {
            val base = delegate.createSocket(s?.let { bind(it) }, host, port, autoClose)
            return bind(base)
        }

        override fun createSocket(host: String?, port: Int): Socket {
            val socket = createSocket()
            socket.connect(java.net.InetSocketAddress(host, port))
            return socket
        }

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int
        ): Socket {
            val socket = createSocket()
            localHost?.let { socket.bind(java.net.InetSocketAddress(it, localPort)) }
            socket.connect(java.net.InetSocketAddress(host, port))
            return socket
        }

        override fun createSocket(address: InetAddress?, port: Int): Socket {
            val socket = createSocket()
            socket.connect(java.net.InetSocketAddress(address, port))
            return socket
        }

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int
        ): Socket {
            val socket = createSocket()
            localAddress?.let { socket.bind(java.net.InetSocketAddress(it, localPort)) }
            socket.connect(java.net.InetSocketAddress(address, port))
            return socket
        }
    }
}
