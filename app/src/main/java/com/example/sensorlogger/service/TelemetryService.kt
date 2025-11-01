package com.example.sensorlogger.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.sensorlogger.MainActivity
import com.example.sensorlogger.R
import com.example.sensorlogger.gnss.GnssManager
import com.example.sensorlogger.model.GnssSnapshot
import com.example.sensorlogger.model.ImuSnapshot
import com.example.sensorlogger.model.TelemetryMappers
import com.example.sensorlogger.model.TelemetryPayload
import com.example.sensorlogger.model.TelemetryPayloadV11
import com.example.sensorlogger.model.TelemetryUiState
import com.example.sensorlogger.mqtt.MqttPublisher
import com.example.sensorlogger.repository.TelemetryStateStore
import com.example.sensorlogger.sensors.ImuAggregator
import com.example.sensorlogger.storage.CsvWriter
import com.example.sensorlogger.storage.MinioUploader
import com.example.sensorlogger.storage.OfflineQueue
import com.example.sensorlogger.storage.OfflineQueue.DrainOutcome
import com.example.sensorlogger.work.EnsureTelemetryRunningWorker
import com.example.sensorlogger.util.IdProvider
import com.example.sensorlogger.util.Time
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import com.example.sensorlogger.BuildConfig
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.min
import kotlin.random.Random

class TelemetryService : LifecycleService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var imuAggregator: ImuAggregator
    private lateinit var gnssManager: GnssManager
    private lateinit var csvWriter: CsvWriter
    private lateinit var offlineQueue: OfflineQueue
    private lateinit var idProvider: IdProvider
    private lateinit var mqttPublisher: MqttPublisher
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager

    private val json = Json { encodeDefaults = true }
    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private val drainTrigger = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoReconnectJob: Job? = null
    @Volatile
    private var autoReconnectActive = false
    private var autoReconnectDelayMs = AUTO_RECONNECT_INITIAL_DELAY_MS
    private var awaitingOperatorConfig = false
    private var lastNetworkSnapshot: NetworkSnapshot? = null

    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            handleNetworkChange("available", network)
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            handleNetworkChange("lost", network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            handleNetworkChange("capabilities", network, networkCapabilities)
        }
    }

    private var operatorId: String = ""
    private var operatorName: String = ""
    private var equipmentTag: String = ""
    private var nmeaEnabled: Boolean = true

    private var isRunning = false
    private var queueFlushJob: Job? = null
    private var disconnectJob: Job? = null
    private var queueMonitorJob: Job? = null
    private var permissionsPrompted = false
    private var restartOnDestroy = true
    private val batteryOptimizationPrefs by lazy {
        getSharedPreferences(BATTERY_PREFS, Context.MODE_PRIVATE)
    }
    private var lastTelemetryPayload: TelemetryPayload? = null
    private var lastTelemetryPayloadV11: TelemetryPayloadV11? = null
    private var lastGnssSnapshot: GnssSnapshot? = null
    private var lastImuSnapshot: ImuSnapshot? = null
    private var minioUploader: MinioUploader? = null

    override fun onCreate() {
        super.onCreate()
        imuAggregator = ImuAggregator(this)
        gnssManager = GnssManager(this)
        minioUploader = createMinioUploader()
        csvWriter = CsvWriter(
            context = this,
            header = TelemetryPayload.HEADER,
            rotateBytes = BuildConfig.CSV_ROTATE_BYTES,
            rotateMinutes = BuildConfig.CSV_ROTATE_MINUTES,
            archiveListener = CsvWriter.ArchiveListener { file -> handleCsvArchive(file) }
        )
        offlineQueue = OfflineQueue(this)
        idProvider = IdProvider(this)
        mqttPublisher = MqttPublisher(
            context = this,
            deviceIdProvider = { idProvider.deviceId }
        )
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        createNotificationChannel()
        registerNetworkCallback()
        EnsureTelemetryRunningWorker.schedulePeriodic(applicationContext)

        serviceScope.launch {
            offlineQueue.initialize()
            val queueStats = offlineQueue.currentStats()
            TelemetryStateStore.update { state ->
                state.copy(
                    queueSize = queueStats.count,
                    offlineQueueSizeMB = queueStats.sizeMb
                )
            }
            if (queueStats.count > 0) {
                drainTrigger.tryEmit(Unit)
            }

            // Start queue monitor AFTER initialization
            queueMonitorJob = launch {
                Timber.i("Offline queue monitor started")
                var cycleCount = 0
                while (isActive) {
                    try {
                        val currentStats = offlineQueue.currentStats()

                        // Hybrid recalculation triggers:
                        // 1. Anomaly detection: non-zero count but zero size
                        val hasAnomaly = currentStats.count > 0 && currentStats.sizeMb < 0.01f
                        
                        // 2. Periodic safety check every 20 cycles (100 seconds)
                        val periodicCheck = cycleCount % 20 == 0
                        
                        val stats = if (hasAnomaly || periodicCheck) {
                            offlineQueue.recalculateStats().also {
                                Timber.i(
                                    "Offline queue stats recalculated | count=%d size=%.2fMB (anomaly=%s periodic=%s)",
                                    it.count,
                                    it.sizeMb,
                                    hasAnomaly,
                                    periodicCheck
                                )
                            }
                        } else {
                            currentStats
                        }

                        cycleCount++

                        Timber.v(
                            "Offline queue monitor snapshot | count=%d size=%.2fMB",
                            stats.count,
                            stats.sizeMb
                        )
                        TelemetryStateStore.update {
                            it.copy(queueSize = stats.count, offlineQueueSizeMB = stats.sizeMb)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Queue monitor update failed")
                    }
                    delay(5000L)
                }
                Timber.i("Offline queue monitor stopped")
            }
        }
        serviceScope.launch {
            mqttPublisher.statuses.collectLatest { statuses ->
                val primaryStatus = statuses[MqttPublisher.NAME_PRIMARY]
                val activeEndpoint = primaryStatus?.let { status ->
                    status.activeEndpoint?.takeIf { status.enabled }
                }
                TelemetryStateStore.update { state ->
                    state.copy(
                        localBrokerStatus = mapBrokerStatus(primaryStatus),
                        cloudBrokerStatus = TelemetryUiState.BrokerStatus.Disabled,
                        brokerActiveEndpoint = activeEndpoint
                    )
                }
                if (!isRunning || primaryStatus == null || !primaryStatus.enabled) {
                    stopAutoReconnectLoop()
                } else {
                    when (primaryStatus.state) {
                        MqttPublisher.BrokerStatus.State.Connected,
                        MqttPublisher.BrokerStatus.State.Disabled -> stopAutoReconnectLoop()
                        MqttPublisher.BrokerStatus.State.Disconnected,
                        MqttPublisher.BrokerStatus.State.Failed,
                        MqttPublisher.BrokerStatus.State.Connecting,
                        MqttPublisher.BrokerStatus.State.Reconnecting -> startAutoReconnectLoop()
                    }
                }
                updateNotification()
            }
        }
        queueFlushJob = serviceScope.launch { flushOfflineLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                operatorId = intent.getStringExtra(EXTRA_OPERATOR_ID) ?: operatorId
                operatorName = intent.getStringExtra(EXTRA_OPERATOR_NAME) ?: operatorName
                equipmentTag = intent.getStringExtra(EXTRA_EQUIPMENT_TAG) ?: equipmentTag
                nmeaEnabled = intent.getBooleanExtra(EXTRA_NMEA_ENABLED, true)
                restartOnDestroy = true
                startLogging()
            }
            ACTION_STOP -> {
                restartOnDestroy = false
                stopLogging()
            }
            ACTION_DRAIN_QUEUE -> {
                drainTrigger.tryEmit(Unit)
            }
            ACTION_RECONNECT -> {
                resetAutoReconnectBackoff()
                serviceScope.launch {
                    runCatching { mqttPublisher.reconnect(resetBackoff = true) }
                        .onFailure { Timber.w(it, "Manual MQTT reconnect failed") }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        if (networkCallbackRegistered && ::connectivityManager.isInitialized) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        queueMonitorJob?.cancel()
        stopLogging()
        runBlocking { disconnectJob?.join() }
        serviceScope.cancel()
        if (restartOnDestroy) {
            EnsureTelemetryRunningWorker.scheduleImmediate(applicationContext, "service_stopped")
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!isRunning || !restartOnDestroy) {
            Timber.i("TelemetryService task removed but restart disabled")
            return
        }
        Timber.w("TelemetryService task removed; scheduling immediate worker restart")
        EnsureTelemetryRunningWorker.scheduleImmediate(applicationContext, "service_stopped")
    }

    private fun startLogging() {
        if (isRunning) {
            updateNotification()
            return
        }
        restartOnDestroy = true
        val missingPermissions = missingRequiredPermissions()
        if (missingPermissions.isNotEmpty()) {
            Timber.w("TelemetryService pending: missing permissions %s", missingPermissions)
            notifyMissingPermissions(missingPermissions)
            return
        }
        if (operatorId.isBlank() || equipmentTag.isBlank()) {
            Timber.w("TelemetryService pending: operatorId or equipmentTag missing")
            awaitingOperatorConfig = true
            val batteryOptIgnored = checkBatteryOptimization()
            val notificationPerm = checkNotificationPermission()
            startForeground(NOTIFICATION_ID, buildNotification(initial = true))
            TelemetryStateStore.update { state ->
                state.copy(
                    serviceRunning = false,
                    operatorId = operatorId,
                    operatorName = operatorName,
                    equipmentTag = equipmentTag,
                    batteryOptimizationIgnored = batteryOptIgnored,
                    notificationPermissionGranted = notificationPerm,
                    permissionsGranted = true,
                    awaitingConfiguration = true
                )
            }
            EnsureTelemetryRunningWorker.schedulePeriodic(applicationContext)
            EnsureTelemetryRunningWorker.scheduleOneTimeCheck(applicationContext)
            return
        }
        awaitingOperatorConfig = false
        isRunning = true
        resetAutoReconnectBackoff()
        acquireWakeLock()
        acquireWifiLock()
        startForeground(NOTIFICATION_ID, buildNotification(initial = true))
        imuAggregator.start()
        gnssManager.start(nmeaEnabled)
        mqttPublisher.startKeepAlive(serviceScope)
        
        val batteryOptIgnored = checkBatteryOptimization()
        requestBatteryOptimizationIfNeeded(batteryOptIgnored)
        val notificationPerm = checkNotificationPermission()
        
        TelemetryStateStore.update { state ->
            state.copy(
                serviceRunning = true,
                operatorId = operatorId,
                operatorName = operatorName,
                equipmentTag = equipmentTag,
                batteryOptimizationIgnored = batteryOptIgnored,
                notificationPermissionGranted = notificationPerm,
                permissionsGranted = true,
                awaitingConfiguration = false
            )
        }
        serviceScope.launch { telemetryLoop() }
        drainTrigger.tryEmit(Unit)
    }

    private fun checkBatteryOptimization(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else true
    }

    private fun requestBatteryOptimizationIfNeeded(batteryOptIgnored: Boolean) {
        if (batteryOptIgnored) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (batteryOptimizationPrefs.getBoolean(KEY_BATTERY_OPT_PROMPTED, false)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onSuccess {
                batteryOptimizationPrefs.edit().putBoolean(KEY_BATTERY_OPT_PROMPTED, true).apply()
                Timber.i("Requested battery optimization exemption from service")
            }
            .onFailure { throwable ->
                Timber.w(throwable, "Failed to request battery optimization exemption")
                batteryOptimizationPrefs.edit().putBoolean(KEY_BATTERY_OPT_PROMPTED, true).apply()
                runCatching {
                    val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(settingsIntent)
                }.onFailure { Timber.w(it, "Failed to open battery optimization settings") }
            }
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun missingRequiredPermissions(): List<String> {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toMutableSet()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!backgroundGranted) {
                missing.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        return missing.toList()
    }

    private fun notifyMissingPermissions(missing: List<String>) {
        Timber.w("TelemetryService cannot start: missing permissions %s", missing.joinToString())
        TelemetryStateStore.update { state ->
            state.copy(
                serviceRunning = false,
                permissionsGranted = false,
                awaitingConfiguration = false
            )
        }
        restartOnDestroy = true
        if (!permissionsPrompted) {
            permissionsPrompted = true
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_FORCE_PERMISSIONS, true)
            }
            runCatching { startActivity(intent) }
                .onFailure { Timber.w(it, "Failed to launch MainActivity for missing permissions") }
        }
        stopSelf()
    }

    private fun stopLogging() {
        awaitingOperatorConfig = false
        if (!isRunning) {
            releaseWakeLock()
            releaseWifiLock()
            stopAutoReconnectLoop()
            stopForeground(STOP_FOREGROUND_DETACH)
            return
        }
        isRunning = false
        releaseWakeLock()
        releaseWifiLock()
        imuAggregator.stop()
        gnssManager.stop()
        mqttPublisher.stopKeepAlive()
        disconnectJob = serviceScope.launch { mqttPublisher.disconnectAll(idProvider.deviceId) }
        TelemetryStateStore.update { state ->
            state.copy(serviceRunning = false, awaitingConfiguration = false)
        }
        stopAutoReconnectLoop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun mapBrokerStatus(status: MqttPublisher.BrokerStatus?): TelemetryUiState.BrokerStatus {
        if (status == null || !status.enabled) return TelemetryUiState.BrokerStatus.Disabled
        return when (status.state) {
            MqttPublisher.BrokerStatus.State.Disabled -> TelemetryUiState.BrokerStatus.Disabled
            MqttPublisher.BrokerStatus.State.Connecting -> TelemetryUiState.BrokerStatus.Connecting
            MqttPublisher.BrokerStatus.State.Disconnected -> TelemetryUiState.BrokerStatus.Disconnected
            MqttPublisher.BrokerStatus.State.Connected -> TelemetryUiState.BrokerStatus.Connected
            MqttPublisher.BrokerStatus.State.Reconnecting -> TelemetryUiState.BrokerStatus.Reconnecting
            MqttPublisher.BrokerStatus.State.Failed -> TelemetryUiState.BrokerStatus.Failed
        }
    }

    private fun acquireWifiLock() {
        if (!::wifiManager.isInitialized) return
        val currentLock = wifiLock
        if (currentLock?.isHeld == true) return
        wifiLock = runCatching {
            wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "SensorLogger:TelemetryWifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Timber.w(it, "Failed to acquire Wi-Fi lock") }
            .getOrNull() ?: currentLock
    }

    private fun acquireWakeLock() {
        val currentLock = wakeLock
        if (currentLock?.isHeld == true) return
        wakeLock = runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SensorLogger:TelemetryCPU"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Timber.w(it, "Failed to acquire CPU wake lock") }
            .getOrNull() ?: currentLock
    }

    private fun releaseWifiLock() {
        wifiLock?.let { lock ->
            runCatching {
                if (lock.isHeld) {
                    lock.release()
                }
            }.onFailure { Timber.w(it, "Failed to release Wi-Fi lock") }
        }
        wifiLock = null
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching {
                if (lock.isHeld) {
                    lock.release()
                }
            }.onFailure { Timber.w(it, "Failed to release CPU wake lock") }
        }
        wakeLock = null
    }

    private fun startAutoReconnectLoop() {
        if (autoReconnectActive || !isRunning) return
        autoReconnectActive = true
        autoReconnectJob = serviceScope.launch {
            while (isActive && autoReconnectActive && isRunning) {
                delay(autoReconnectDelayMs)
                if (!autoReconnectActive || !isRunning) break
                val success = runCatching { mqttPublisher.reconnect() }.isSuccess
                autoReconnectDelayMs = if (success) {
                    AUTO_RECONNECT_INITIAL_DELAY_MS
                } else {
                    (autoReconnectDelayMs * 2).coerceAtMost(AUTO_RECONNECT_MAX_DELAY_MS)
                }
            }
        }
    }

    private fun stopAutoReconnectLoop() {
        autoReconnectActive = false
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        resetAutoReconnectBackoff()
    }

    private fun resetAutoReconnectBackoff() {
        autoReconnectDelayMs = AUTO_RECONNECT_INITIAL_DELAY_MS
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (t: Throwable) {
            Timber.w(t, "Failed to register network callback")
        }
    }

    private fun handleNetworkChange(
        event: String,
        network: Network,
        providedCapabilities: NetworkCapabilities? = null
    ) {
        if (!::connectivityManager.isInitialized) return
        val capabilities = providedCapabilities ?: runCatching {
            connectivityManager.getNetworkCapabilities(network)
        }.getOrNull()
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val transport = describeNetworkTransport(capabilities)

        Timber.i(
            "Network %s | connected=%s transport=%s validated=%s",
            event,
            connected,
            transport,
            validated
        )

        TelemetryStateStore.update { state ->
            state.copy(
                networkConnected = connected,
                networkTransport = transport,
                networkValidated = validated
            )
        }

        val snapshot = NetworkSnapshot(
            connected = connected,
            transport = transport,
            validated = validated
        )
        val previous = lastNetworkSnapshot
        val connectedChanged = previous?.connected != connected
        val transportChanged = previous?.transport != transport
        val validatedChanged = previous?.validated != validated

        if (connectedChanged || transportChanged) {
            mqttPublisher.resetVerification()
        }

        val shouldReconnect = when (event) {
            "available" -> true
            "lost" -> previous?.connected == true
            else -> connected && (connectedChanged || transportChanged || validatedChanged)
        }
        if (shouldReconnect) {
            resetAutoReconnectBackoff()
            serviceScope.launch {
                runCatching { mqttPublisher.reconnect(resetBackoff = true) }
                    .onFailure { Timber.w(it, "MQTT reconnect failed after network %s", event) }
            }
        }
        if (connected && (shouldReconnect || connectedChanged || transportChanged)) {
            drainTrigger.tryEmit(Unit)
        }
        lastNetworkSnapshot = snapshot
    }

    private fun describeNetworkTransport(capabilities: NetworkCapabilities?): String? {
        capabilities ?: return null
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun nowMono(): Long = SystemClock.elapsedRealtime()

    private suspend fun telemetryLoop() {
        val baseMono = nowMono()
        val baseEpoch = Time.nowUtcMillis()
        var tickIndex = 0L
        while (isRunning && serviceScope.isActive) {
            val targetMono = baseMono + tickIndex * PERIOD_MS
            var currentMono = nowMono()
            val sleep = targetMono - currentMono
            if (sleep > 0) {
                delay(sleep)
                currentMono = nowMono()
            }
            val ticksBehind = ((currentMono - targetMono) / PERIOD_MS).coerceAtLeast(0L)
            if (ticksBehind > MAX_CATCH_UP_TICKS) {
                val overflow = ticksBehind - MAX_CATCH_UP_TICKS
                repeat(overflow.toInt()) {
                    val missedTargetMono = baseMono + tickIndex * PERIOD_MS
                    val missedTimestampUtc = baseEpoch + (missedTargetMono - baseMono)
                    emitMissedTick(missedTimestampUtc)
                    tickIndex += 1
                }
            }
            val catchUpTicks = if (ticksBehind > MAX_CATCH_UP_TICKS) MAX_CATCH_UP_TICKS else ticksBehind
            repeat(catchUpTicks.toInt()) {
                if (!isRunning || !serviceScope.isActive) return
                val catchUpTarget = baseMono + tickIndex * PERIOD_MS
                executeTelemetryTick(catchUpTarget)
                tickIndex += 1
            }
            if (!isRunning || !serviceScope.isActive) {
                break
            }
            val currentTarget = baseMono + tickIndex * PERIOD_MS
            executeTelemetryTick(currentTarget)
            tickIndex += 1
        }
    }

    private suspend fun executeTelemetryTick(targetMono: Long) {
        val frameStart = nowMono()
        try {
            val nowUtc = Time.nowUtcMillis()
            val seq = idProvider.nextSequence()
            val elapsedRealtime = Time.elapsedRealtimeNanos()
            val imuSnapshot = imuAggregator.snapshot(nowUtc)
            val gnssSnapshot = gnssManager.snapshot()
            val payload = buildPayload(
                tsUtc = nowUtc,
                elapsedRealtimeNanos = elapsedRealtime,
                seq = seq,
                imu = imuSnapshot,
                gnss = gnssSnapshot,
                status = STATUS_OK,
                origin = ORIGIN_LIVE
            )
            val extras = TelemetryMappers.Extras(
                equipmentTag = equipmentTag.takeIf { it.isNotBlank() },
                truckStatus = TelemetryStateStore.state.value.truckStatus,
                barometerPressureHpa = imuSnapshot.pressure.takeIf { it != 0f },
                barometerAltitudeMeters = imuSnapshot.altitudeBaro.takeIf { it != 0f },
                gnss = TelemetryMappers.GnssExtras(
                    elapsedRealtimeNanos = gnssSnapshot.elapsedRealtimeNanos.takeIf { it != 0L },
                    cn0Min = gnssSnapshot.cn0Min,
                    cn0Max = gnssSnapshot.cn0Max,
                    cn0Percentile25 = gnssSnapshot.cn0Percentile25,
                    cn0Median = gnssSnapshot.cn0Median,
                    cn0Percentile75 = gnssSnapshot.cn0Percentile75,
                    gpsVisible = gnssSnapshot.gpsVisible,
                    gpsUsed = gnssSnapshot.gpsUsed,
                    glonassVisible = gnssSnapshot.glonassVisible,
                    glonassUsed = gnssSnapshot.glonassUsed,
                    galileoVisible = gnssSnapshot.galileoVisible,
                    galileoUsed = gnssSnapshot.galileoUsed,
                    beidouVisible = gnssSnapshot.beidouVisible,
                    beidouUsed = gnssSnapshot.beidouUsed,
                    qzssVisible = gnssSnapshot.qzssVisible,
                    qzssUsed = gnssSnapshot.qzssUsed,
                    sbasVisible = gnssSnapshot.sbasVisible,
                    sbasUsed = gnssSnapshot.sbasUsed,
                    rawGpsCount = gnssSnapshot.rawGpsCount,
                    rawGlonassCount = gnssSnapshot.rawGlonassCount,
                    rawGalileoCount = gnssSnapshot.rawGalileoCount,
                    rawBeidouCount = gnssSnapshot.rawBeidouCount,
                    rawQzssCount = gnssSnapshot.rawQzssCount,
                    rawSbasCount = gnssSnapshot.rawSbasCount,
                    rawSnapshot = gnssSnapshot.raw
                ),
                imu = TelemetryMappers.ImuExtras(
                    quaternion = imuSnapshot.quaternion.takeIf { imuSnapshot.sampleCount > 0 },
                    accelerometerAccuracy = imuSnapshot.accelerometerAccuracy,
                    gyroscopeAccuracy = imuSnapshot.gyroscopeAccuracy,
                    rotationAccuracy = imuSnapshot.rotationAccuracy,
                    stationary = determineStationary(imuSnapshot, gnssSnapshot),
                    shockLevel = determineShockLevel(imuSnapshot),
                    shockScore = imuSnapshot.rmsJerk.takeIf { imuSnapshot.sampleCount > 0 && it.isFinite() },
                    linearAccelerationStats = imuSnapshot.linearAccelerationStats,
                    linearAccelerationNorm = imuSnapshot.linearAccelerationNormStats,
                    magnetometerStats = imuSnapshot.magnetometerStats,
                    magnetometerNorm = imuSnapshot.magnetometerNormStats,
                    magnetometerFieldStrength = imuSnapshot.magnetometerFieldStrength
                )
            )
            val payloadV11 = TelemetryMappers.fromLegacy(
                payload,
                imuSnapshot,
                extras = extras
            )
            dispatchTelemetry(
                payload = payload,
                payloadV11 = payloadV11,
                imuSnapshot = imuSnapshot,
                gnssSnapshot = gnssSnapshot,
                extras = extras,
                updateCaches = true,
                notify = true
            )
            val loopElapsed = nowMono() - frameStart
            if (loopElapsed > PERIOD_MS) {
                Timber.w(
                    "Telemetry tick exceeded period: elapsed=%dms target=%d",
                    loopElapsed,
                    targetMono
                )
            }
        } catch (t: Throwable) {
            Timber.e(t, "Telemetry tick failed")
        }
    }

    private suspend fun emitMissedTick(timestampUtc: Long) {
        val baseImu = lastImuSnapshot ?: ImuSnapshot.EMPTY
        val baseGnss = lastGnssSnapshot ?: GnssSnapshot.EMPTY
        val seq = idProvider.nextSequence()
        val payload = buildPayload(
            tsUtc = timestampUtc,
            elapsedRealtimeNanos = Time.elapsedRealtimeNanos(),
            seq = seq,
            imu = baseImu,
            gnss = baseGnss,
            status = STATUS_MISSED,
            origin = ORIGIN_SYNTH
        )
        val extras = TelemetryMappers.Extras(
            equipmentTag = equipmentTag.takeIf { it.isNotBlank() },
            truckStatus = TelemetryStateStore.state.value.truckStatus,
            barometerPressureHpa = baseImu.pressure.takeIf { it != 0f },
            barometerAltitudeMeters = baseImu.altitudeBaro.takeIf { it != 0f },
            gnss = TelemetryMappers.GnssExtras(
                elapsedRealtimeNanos = baseGnss.elapsedRealtimeNanos.takeIf { it != 0L },
                cn0Min = baseGnss.cn0Min,
                cn0Max = baseGnss.cn0Max,
                cn0Percentile25 = baseGnss.cn0Percentile25,
                cn0Median = baseGnss.cn0Median,
                cn0Percentile75 = baseGnss.cn0Percentile75,
                gpsVisible = baseGnss.gpsVisible,
                gpsUsed = baseGnss.gpsUsed,
                glonassVisible = baseGnss.glonassVisible,
                glonassUsed = baseGnss.glonassUsed,
                galileoVisible = baseGnss.galileoVisible,
                galileoUsed = baseGnss.galileoUsed,
                beidouVisible = baseGnss.beidouVisible,
                beidouUsed = baseGnss.beidouUsed,
                qzssVisible = baseGnss.qzssVisible,
                qzssUsed = baseGnss.qzssUsed,
                sbasVisible = baseGnss.sbasVisible,
                sbasUsed = baseGnss.sbasUsed,
                rawGpsCount = baseGnss.rawGpsCount,
                rawGlonassCount = baseGnss.rawGlonassCount,
                rawGalileoCount = baseGnss.rawGalileoCount,
                rawBeidouCount = baseGnss.rawBeidouCount,
                rawQzssCount = baseGnss.rawQzssCount,
                rawSbasCount = baseGnss.rawSbasCount,
                rawSnapshot = baseGnss.raw
            ),
            imu = TelemetryMappers.ImuExtras(
                quaternion = baseImu.quaternion.takeIf { baseImu.sampleCount > 0 },
                accelerometerAccuracy = baseImu.accelerometerAccuracy,
                gyroscopeAccuracy = baseImu.gyroscopeAccuracy,
                rotationAccuracy = baseImu.rotationAccuracy,
                stationary = determineStationary(baseImu, baseGnss),
                shockLevel = determineShockLevel(baseImu),
                shockScore = baseImu.rmsJerk.takeIf { baseImu.sampleCount > 0 && it.isFinite() },
                linearAccelerationStats = baseImu.linearAccelerationStats,
                linearAccelerationNorm = baseImu.linearAccelerationNormStats,
                magnetometerStats = baseImu.magnetometerStats,
                magnetometerNorm = baseImu.magnetometerNormStats,
                magnetometerFieldStrength = baseImu.magnetometerFieldStrength
            )
        )
        val payloadV11 = TelemetryMappers.fromLegacy(payload, baseImu, extras)
        dispatchTelemetry(
            payload = payload,
            payloadV11 = payloadV11,
            imuSnapshot = baseImu,
            gnssSnapshot = baseGnss,
            extras = extras,
            updateCaches = false,
            notify = false
        )
        Timber.w("Recorded missed telemetry tick at %d", timestampUtc)
    }

    private suspend fun dispatchTelemetry(
        payload: TelemetryPayload,
        payloadV11: TelemetryPayloadV11,
        imuSnapshot: ImuSnapshot?,
        gnssSnapshot: GnssSnapshot?,
        extras: TelemetryMappers.Extras,
        updateCaches: Boolean,
        notify: Boolean
    ) {
        lastTelemetryPayload = payload
        lastTelemetryPayloadV11 = payloadV11
        if (updateCaches) {
            imuSnapshot?.let { lastImuSnapshot = it }
            gnssSnapshot?.let { lastGnssSnapshot = it }
        }
        serviceScope.launch { writeCsv(payload) }
        val payloadJson = json.encodeToString(payloadV11)
        val lastSnapshotBytes = payloadJson.toByteArray(StandardCharsets.UTF_8)
        schedulePublish(payloadV11, lastSnapshotBytes)
        val queueStats = offlineQueue.currentStats()
        val location = gnssSnapshot?.location
        TelemetryStateStore.update { state ->
            state.copy(
                operatorId = operatorId,
                operatorName = operatorName,
                equipmentTag = equipmentTag,
                sequence = payload.sequence,
                queueSize = queueStats.count,
                offlineQueueSizeMB = queueStats.sizeMb,
                lastLatitude = location?.latitude?.toFloat() ?: state.lastLatitude,
                lastLongitude = location?.longitude?.toFloat() ?: state.lastLongitude,
                lastSpeed = location?.speed?.toFloat() ?: state.lastSpeed,
                lastArms = imuSnapshot?.rmsAcceleration ?: state.lastArms,
                lastAltitude = location?.altitude?.toFloat() ?: state.lastAltitude,
                lastAccuracy = location?.accuracy ?: state.lastAccuracy,
                lastVerticalAccuracy = location?.verticalAccuracyMeters ?: state.lastVerticalAccuracy,
                lastSpeedAccuracy = location?.speedAccuracyMetersPerSecond ?: state.lastSpeedAccuracy,
                lastBearing = location?.bearing?.toFloat() ?: state.lastBearing,
                lastBearingAccuracy = location?.bearingAccuracyDegrees ?: state.lastBearingAccuracy,
                lastProvider = location?.provider ?: gnssSnapshot?.provider ?: state.lastProvider,
                satellitesVisible = gnssSnapshot?.satellitesVisible ?: state.satellitesVisible,
                satellitesUsed = gnssSnapshot?.satellitesUsed ?: state.satellitesUsed,
                cn0Average = gnssSnapshot?.cn0Average ?: state.cn0Average,
                gnssElapsedRealtimeNanos = extras.gnss.elapsedRealtimeNanos ?: state.gnssElapsedRealtimeNanos,
                hasL5 = gnssSnapshot?.hasL5 ?: state.hasL5,
                hdop = gnssSnapshot?.hdop ?: state.hdop,
                vdop = gnssSnapshot?.vdop ?: state.vdop,
                pdop = gnssSnapshot?.pdop ?: state.pdop,
                gnssRawSupported = gnssSnapshot?.gnssRawSupported ?: state.gnssRawSupported,
                gnssRawCount = gnssSnapshot?.gnssRawCount ?: state.gnssRawCount,
                baroPressureHpa = extras.barometerPressureHpa ?: state.baroPressureHpa,
                baroAltitudeMeters = extras.barometerAltitudeMeters ?: state.baroAltitudeMeters,
                lastAx = imuSnapshot?.ax ?: state.lastAx,
                lastAy = imuSnapshot?.ay ?: state.lastAy,
                lastAz = imuSnapshot?.az ?: state.lastAz,
                lastGx = imuSnapshot?.gx ?: state.lastGx,
                lastGy = imuSnapshot?.gy ?: state.lastGy,
                lastGz = imuSnapshot?.gz ?: state.lastGz,
                lastPitch = imuSnapshot?.pitch ?: state.lastPitch,
                lastRoll = imuSnapshot?.roll ?: state.lastRoll,
                lastYaw = imuSnapshot?.yaw ?: state.lastYaw,
                lastJerk = imuSnapshot?.rmsJerk ?: state.lastJerk,
                lastYawRate = imuSnapshot?.yawRateMean ?: state.lastYawRate,
                imuSamples = imuSnapshot?.sampleCount ?: state.imuSamples,
                imuHz = imuSnapshot?.effectiveHz ?: state.imuHz,
                imuQuaternionW = extras.imu.quaternion?.w ?: state.imuQuaternionW,
                imuQuaternionX = extras.imu.quaternion?.x ?: state.imuQuaternionX,
                imuQuaternionY = extras.imu.quaternion?.y ?: state.imuQuaternionY,
                imuQuaternionZ = extras.imu.quaternion?.z ?: state.imuQuaternionZ,
                imuAccelerometerAccuracy = extras.imu.accelerometerAccuracy.takeIf { it >= 0 }
                    ?: state.imuAccelerometerAccuracy,
                imuGyroscopeAccuracy = extras.imu.gyroscopeAccuracy.takeIf { it >= 0 }
                    ?: state.imuGyroscopeAccuracy,
                imuRotationAccuracy = extras.imu.rotationAccuracy.takeIf { it >= 0 }
                    ?: state.imuRotationAccuracy,
                imuMotionStationary = extras.imu.stationary ?: state.imuMotionStationary,
                imuMotionShockLevel = extras.imu.shockLevel ?: state.imuMotionShockLevel,
                imuMotionShockScore = extras.imu.shockScore ?: state.imuMotionShockScore,
                linearAccXMean = extras.imu.linearAccelerationStats.x.mean ?: state.linearAccXMean,
                linearAccXRms = extras.imu.linearAccelerationStats.x.rms ?: state.linearAccXRms,
                linearAccXMin = extras.imu.linearAccelerationStats.x.min ?: state.linearAccXMin,
                linearAccXMax = extras.imu.linearAccelerationStats.x.max ?: state.linearAccXMax,
                linearAccXSigma = extras.imu.linearAccelerationStats.x.sigma ?: state.linearAccXSigma,
                linearAccYMean = extras.imu.linearAccelerationStats.y.mean ?: state.linearAccYMean,
                linearAccYRms = extras.imu.linearAccelerationStats.y.rms ?: state.linearAccYRms,
                linearAccYMin = extras.imu.linearAccelerationStats.y.min ?: state.linearAccYMin,
                linearAccYMax = extras.imu.linearAccelerationStats.y.max ?: state.linearAccYMax,
                linearAccYSigma = extras.imu.linearAccelerationStats.y.sigma ?: state.linearAccYSigma,
                linearAccZMean = extras.imu.linearAccelerationStats.z.mean ?: state.linearAccZMean,
                linearAccZRms = extras.imu.linearAccelerationStats.z.rms ?: state.linearAccZRms,
                linearAccZMin = extras.imu.linearAccelerationStats.z.min ?: state.linearAccZMin,
                linearAccZMax = extras.imu.linearAccelerationStats.z.max ?: state.linearAccZMax,
                linearAccZSigma = extras.imu.linearAccelerationStats.z.sigma ?: state.linearAccZSigma,
                linearAccNormRms = extras.imu.linearAccelerationNorm.rms ?: state.linearAccNormRms,
                linearAccNormSigma = extras.imu.linearAccelerationNorm.sigma ?: state.linearAccNormSigma,
                magnetometerXMean = extras.imu.magnetometerStats.x.mean ?: state.magnetometerXMean,
                magnetometerXRms = extras.imu.magnetometerStats.x.rms ?: state.magnetometerXRms,
                magnetometerXMin = extras.imu.magnetometerStats.x.min ?: state.magnetometerXMin,
                magnetometerXMax = extras.imu.magnetometerStats.x.max ?: state.magnetometerXMax,
                magnetometerXSigma = extras.imu.magnetometerStats.x.sigma ?: state.magnetometerXSigma,
                magnetometerYMean = extras.imu.magnetometerStats.y.mean ?: state.magnetometerYMean,
                magnetometerYRms = extras.imu.magnetometerStats.y.rms ?: state.magnetometerYRms,
                magnetometerYMin = extras.imu.magnetometerStats.y.min ?: state.magnetometerYMin,
                magnetometerYMax = extras.imu.magnetometerStats.y.max ?: state.magnetometerYMax,
                magnetometerYSigma = extras.imu.magnetometerStats.y.sigma ?: state.magnetometerYSigma,
                magnetometerZMean = extras.imu.magnetometerStats.z.mean ?: state.magnetometerZMean,
                magnetometerZRms = extras.imu.magnetometerStats.z.rms ?: state.magnetometerZRms,
                magnetometerZMin = extras.imu.magnetometerStats.z.min ?: state.magnetometerZMin,
                magnetometerZMax = extras.imu.magnetometerStats.z.max ?: state.magnetometerZMax,
                magnetometerZSigma = extras.imu.magnetometerStats.z.sigma ?: state.magnetometerZSigma,
                magnetometerFieldStrength = extras.imu.magnetometerFieldStrength ?: state.magnetometerFieldStrength,
                lastPayloadJson = payloadJson,
                lastPayload = payloadV11,
                lastStatus = payload.status ?: state.lastStatus,
                lastOrigin = payload.origin ?: state.lastOrigin,
                lastMessageTimestampUtc = payload.timestampUtc,
                lastUpdatedMillis = System.currentTimeMillis()
            )
        }
        if (notify && payload.status != STATUS_MISSED) {
            updateNotification(payload)
        }
    }

    private fun handleCsvArchive(file: File) {
        val now = System.currentTimeMillis()
        val sizeBytes = file.length()
        TelemetryStateStore.update { state ->
            state.copy(
                csvRotationCount = state.csvRotationCount + 1,
                lastCsvRotateAtMillis = now,
                lastCsvRotateFile = file.name,
                lastCsvRotateBytes = sizeBytes
            )
        }
        val uploader = minioUploader ?: return
        val objectKey = "csv/${BuildConfig.DEVICE_ID}/${file.name}"
        serviceScope.launch(Dispatchers.IO) {
            val result = uploader.upload(file, objectKey)
            val success = result.success
            val error = result.error
            if (success) {
                val deleted = file.delete()
                if (!deleted) {
                    Timber.w("Failed to delete archived CSV %s after upload", file.name)
                }
            }
            val uploadNow = System.currentTimeMillis()
            TelemetryStateStore.update { state ->
                state.copy(
                    csvUploadAttemptCount = state.csvUploadAttemptCount + 1,
                    csvUploadSuccessCount = state.csvUploadSuccessCount + if (success) 1 else 0,
                    csvUploadFailureCount = state.csvUploadFailureCount + if (success) 0 else 1,
                    lastCsvUploadAtMillis = uploadNow,
                    lastCsvUploadObjectKey = objectKey,
                    lastCsvUploadError = if (success) null else error
                )
            }
        }
    }

    private fun createMinioUploader(): MinioUploader? {
        val endpoint = BuildConfig.MINIO_ENDPOINT.trim()
        val accessKey = BuildConfig.MINIO_ACCESS_KEY.trim()
        val secretKey = BuildConfig.MINIO_SECRET_KEY.trim()
        val bucket = BuildConfig.MINIO_BUCKET.trim()
        val region = BuildConfig.MINIO_REGION.trim().ifEmpty { "us-east-1" }
        if (endpoint.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty() || bucket.isEmpty()) {
            Timber.i("MinIO uploader disabled: missing configuration")
            return null
        }
        return runCatching {
            MinioUploader(endpoint, accessKey, secretKey, bucket, region)
        }.onFailure { Timber.w(it, "Failed to initialize MinIO uploader") }
            .getOrNull()
    }

    private suspend fun writeCsv(payload: TelemetryPayload) {
        val ok = csvWriter.append(payload.toCsvRow())
        if (!ok) {
            Timber.w("CSV write failed for seq=%d", payload.sequence)
            val failureAt = System.currentTimeMillis()
            TelemetryStateStore.update { state ->
                state.copy(
                    csvFailures = state.csvFailures + 1,
                    lastCsvFailureSequence = payload.sequence,
                    lastCsvFailureAtMillis = failureAt
                )
            }
        }
    }

    private fun schedulePublish(payload: TelemetryPayloadV11, lastSnapshotBytes: ByteArray) {
        serviceScope.launch {
            val enabledTargets = mqttPublisher.enabledLabels()
            if (enabledTargets.isEmpty()) {
                val now = System.currentTimeMillis()
                TelemetryStateStore.update { state ->
                    state.copy(
                        enqueueFailures = state.enqueueFailures + 1,
                        lastEnqueueFailureSequence = payload.sequenceId,
                        lastEnqueueFailureReason = "no_targets",
                        lastEnqueueFailureAtMillis = now
                    )
                }
                Timber.w("Skipping telemetry publish | seq=%d reason=no_targets", payload.sequenceId)
                return@launch
            }

            val publishAttempt = if (!isOnWifi()) {
                Timber.i("Skipping live publish for seq=%d: active network is not Wi-Fi", payload.sequenceId)
                Result.failure<Map<String, Boolean>>(NotOnWifiException)
            } else {
                runCatching {
                    withTimeoutOrNull(MQTT_PUBLISH_TIMEOUT_MS) {
                        mqttPublisher.publishTelemetry(
                            payload.deviceId,
                            payload,
                            lastSnapshot = lastSnapshotBytes
                        )
                    } ?: throw CancellationException("MQTT publish timed out")
                }
            }

            val publishResults = publishAttempt.getOrNull()
            val failedTargets = if (publishResults == null) {
                enabledTargets
            } else {
                enabledTargets.filter { publishResults[it] != true }.toSet()
            }

            if (failedTargets.isEmpty()) {
                resetAutoReconnectBackoff()
                Timber.d("MQTT publish succeeded | seq=%d", payload.sequenceId)
                return@launch
            }

            val errorTag = when (publishAttempt.exceptionOrNull()) {
                is TimeoutCancellationException -> "publish_timeout"
                is NotOnWifiException -> "not_on_wifi"
                null -> "publish_failed"
                else -> "publish_exception"
            }
            publishAttempt.exceptionOrNull()?.let {
                Timber.w(it, "MQTT publish failed for seq=%d", payload.sequenceId)
            }

            val stored = offlineQueue.enqueue(payload, failedTargets, errorTag)
            val now = System.currentTimeMillis()
            if (stored) {
                drainTrigger.tryEmit(Unit)
                TelemetryStateStore.update { state ->
                    state.copy(
                        lastQueueEnqueuedSequence = payload.sequenceId,
                        lastQueueEnqueuedReason = errorTag,
                        lastQueueEnqueuedAtMillis = now
                    )
                }
            } else {
                TelemetryStateStore.update { state ->
                    state.copy(
                        enqueueFailures = state.enqueueFailures + 1,
                        lastEnqueueFailureSequence = payload.sequenceId,
                        lastEnqueueFailureReason = errorTag,
                        lastEnqueueFailureAtMillis = now
                    )
                }
            }
            Timber.w(
                "Telemetry enqueued offline | seq=%d targets=%s stored=%s reason=%s",
                payload.sequenceId,
                failedTargets,
                stored,
                errorTag
            )
        }
    }

    private suspend fun flushOfflineLoop() {
        var backoff = DRAIN_MIN_BACKOFF_MS
        while (serviceScope.isActive) {
            val outcome = drainOfflineBatch()

            if (outcome.processed > 0) {
                val stats = offlineQueue.recalculateStats()
                Timber.i(
                    "Offline queue drained batch | processed=%d remaining=%d size=%.2fMB",
                    outcome.processed,
                    stats.count,
                    stats.sizeMb
                )
                val now = System.currentTimeMillis()
                TelemetryStateStore.update { state ->
                    state.copy(
                        lastQueueDrainCount = outcome.processed,
                        lastQueueDrainAtMillis = now,
                        lastQueueDrainRemaining = stats.count,
                        queueSize = stats.count,
                        offlineQueueSizeMB = stats.sizeMb
                    )
                }
                backoff = DRAIN_MIN_BACKOFF_MS
                continue
            }

            if (outcome.remaining == 0) {
                backoff = DRAIN_MIN_BACKOFF_MS
                TelemetryStateStore.update { state ->
                    state.copy(
                        lastQueueDrainCount = 0,
                        lastQueueDrainRemaining = 0,
                        lastQueueDrainAtMillis = System.currentTimeMillis(),
                        queueSize = 0,
                        offlineQueueSizeMB = 0f
                    )
                }
                if (waitForDrainTrigger(DRAIN_IDLE_INTERVAL_MS)) {
                    return
                }
                continue
            }

            val delayMillis = jitterDelay(backoff)
            backoff = min(backoff * 2, DRAIN_MAX_BACKOFF_MS)

            if (outcome.blocked) {
                Timber.w(
                    "Offline queue drain blocked; waiting for network recovery | remaining=%d nextDelay=%dms",
                    outcome.remaining,
                    delayMillis
                )
            }

            if (waitForDrainTrigger(delayMillis)) {
                return
            }
        }
    }

    private suspend fun drainOfflineBatch(): DrainOutcome =
        offlineQueue.drainOnce(DRAIN_BATCH_SIZE) { message ->
            val targets = message.targets.toSet()
            if (targets.isEmpty()) {
                emptyMap()
            } else {
                mqttPublisher.publishTelemetry(
                    deviceId = message.payload.deviceId,
                    payload = message.payload,
                    targetFilter = targets
                )
            }
        }

    private suspend fun waitForDrainTrigger(timeoutMs: Long): Boolean {
        if (!serviceScope.isActive) return true
        if (timeoutMs <= 0L) {
            return !serviceScope.isActive
        }
        withTimeoutOrNull(timeoutMs) {
            drainTrigger.first()
        }
        return !serviceScope.isActive
    }

    private fun jitterDelay(base: Long): Long {
        if (base <= 1L) return 1L
        val spread = base / 2
        val offset = if (spread > 0) Random.nextLong(-spread, spread + 1) else 0L
        return (base + offset).coerceAtLeast(base / 2)
    }

    private fun buildPayload(
        tsUtc: Long,
        elapsedRealtimeNanos: Long,
        seq: Long,
        imu: ImuSnapshot,
        gnss: GnssSnapshot,
        status: String? = null,
        origin: String? = null
    ): TelemetryPayload {
        val deviceId = BuildConfig.DEVICE_ID.takeIf { it.isNotBlank() } ?: idProvider.deviceId
        val location = gnss.location
        return TelemetryPayload(
            deviceId = deviceId,
            timestampUtc = tsUtc,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            sequence = seq,
            operatorId = operatorId,
            operatorName = operatorName,
            equipmentTag = equipmentTag,
            latitude = location?.latitude?.toFloat() ?: 0f,
            longitude = location?.longitude?.toFloat() ?: 0f,
            altitude = location?.altitude?.toFloat() ?: 0f,
            speed = location?.speed?.toFloat() ?: 0f,
            bearing = location?.bearing?.toFloat() ?: 0f,
            accuracy = location?.accuracy ?: 0f,
            verticalAccuracyMeters = location?.verticalAccuracyMeters ?: 0f,
            speedAccuracyMps = location?.speedAccuracyMetersPerSecond ?: 0f,
            bearingAccuracyDeg = location?.bearingAccuracyDegrees ?: 0f,
            satellitesVisible = gnss.satellitesVisible,
            satellitesUsed = gnss.satellitesUsed,
            cn0Average = gnss.cn0Average,
            hasL5 = gnss.hasL5,
            hdop = gnss.hdop,
            vdop = gnss.vdop,
            pdop = gnss.pdop,
            provider = gnss.provider,
            ax = imu.ax,
            ay = imu.ay,
            az = imu.az,
            gx = imu.gx,
            gy = imu.gy,
            gz = imu.gz,
            pitch = imu.pitch,
            roll = imu.roll,
            yaw = imu.yaw,
            a_rms_total = imu.rmsAcceleration,
            jerk_rms = imu.rmsJerk,
            yaw_rate_mean = imu.yawRateMean,
            samples_imu = imu.sampleCount,
            imu_hz = imu.effectiveHz,
            pressure = imu.pressure,
            alt_baro = imu.altitudeBaro,
            gnss_raw_supported = gnss.gnssRawSupported,
            gnss_raw_count = gnss.gnssRawCount,
            status = status,
            origin = origin,
            timestamp = Time.formatUtc(tsUtc)
        )
    }

    private fun determineStationary(imu: ImuSnapshot, gnss: GnssSnapshot): Boolean? {
        if (imu.sampleCount == 0) return null
        val speed = gnss.location?.speed ?: 0f
        val jerk = imu.rmsJerk
        val sigma = imu.accelerationNormStats.sigma ?: 0f
        val imuStationary = jerk < 1f && sigma < 0.2f
        val gnssStationary = speed.isFinite() && speed < 0.3f
        return when {
            imuStationary && gnssStationary -> true
            !imuStationary && !gnssStationary -> false
            else -> null
        }
    }

    private fun determineShockLevel(imu: ImuSnapshot): String? {
        if (imu.sampleCount == 0) return null
        val jerk = imu.rmsJerk
        if (!jerk.isFinite()) return null
        return when {
            jerk < 1.5f -> "low"
            jerk < 4.5f -> "medium"
            else -> "high"
        }
    }

    private fun buildNotification(payload: TelemetryPayload? = null, initial: Boolean = false): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val awaitingConfig = awaitingOperatorConfig && !isRunning
        val title = when {
            isRunning -> getString(R.string.notification_title_active)
            awaitingConfig -> getString(R.string.notification_title_idle)
            else -> getString(R.string.notification_title_idle)
        }
        val text = when {
            awaitingConfig -> getString(R.string.text_operator_required)
            payload != null -> "seq=${payload.sequence} | lat=${"%.5f".format(payload.latitude)} | a_rms=${"%.3f".format(payload.a_rms_total)}"
            else -> getString(R.string.notification_text_idle)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_logger)
            .setContentIntent(pendingIntent)
            .setOngoing(isRunning || awaitingConfig)
            .setOnlyAlertOnce(!initial)
            .build()
    }

    private fun updateNotification(payload: TelemetryPayload? = null) {
        if (!isRunning && !awaitingOperatorConfig) return
        notificationManager.notify(NOTIFICATION_ID, buildNotification(payload))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private data class NetworkSnapshot(
        val connected: Boolean,
        val transport: String?,
        val validated: Boolean
    )

    private object NotOnWifiException : IllegalStateException("Active network is not Wi-Fi")

    companion object {
        const val ACTION_START = "com.example.sensorlogger.action.START"
        const val ACTION_STOP = "com.example.sensorlogger.action.STOP"
        const val ACTION_DRAIN_QUEUE = "com.example.sensorlogger.action.DRAIN_QUEUE"
        const val ACTION_RECONNECT = "com.example.sensorlogger.action.RECONNECT_MQTT"

        private const val EXTRA_OPERATOR_ID = "extra_operator_id"
        private const val EXTRA_OPERATOR_NAME = "extra_operator_name"
        private const val EXTRA_EQUIPMENT_TAG = "extra_equipment_tag"
        private const val EXTRA_NMEA_ENABLED = "extra_nmea_enabled"

        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "sensorlogger_channel"
        private const val PERIOD_MS = 1_000L
        private const val DRAIN_MIN_BACKOFF_MS = 2_000L
        private const val DRAIN_MAX_BACKOFF_MS = 300_000L
        private const val DRAIN_IDLE_INTERVAL_MS = 60_000L
        private const val DRAIN_BATCH_SIZE = 500
        private const val MQTT_PUBLISH_TIMEOUT_MS = 500L  // Quick timeout for faster offline detection
        private const val AUTO_RECONNECT_INITIAL_DELAY_MS = 5_000L
        private const val AUTO_RECONNECT_MAX_DELAY_MS = 60_000L
        private const val MAX_CATCH_UP_TICKS = 35L
        private const val BATTERY_PREFS = "telemetry_runtime"
        private const val KEY_BATTERY_OPT_PROMPTED = "battery_opt_prompted"
        private const val STATUS_OK = "ok"
        private const val STATUS_MISSED = "missed_tick"
        private const val ORIGIN_LIVE = "live"
        private const val ORIGIN_SYNTH = "synth"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )

        fun startIntent(
            context: Context,
            operatorId: String,
            operatorName: String,
            equipmentTag: String,
            nmeaEnabled: Boolean
        ): Intent =
            Intent(context, TelemetryService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_OPERATOR_ID, operatorId)
                putExtra(EXTRA_OPERATOR_NAME, operatorName)
                putExtra(EXTRA_EQUIPMENT_TAG, equipmentTag)
                putExtra(EXTRA_NMEA_ENABLED, nmeaEnabled)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, TelemetryService::class.java).apply { action = ACTION_STOP }
    }
}
