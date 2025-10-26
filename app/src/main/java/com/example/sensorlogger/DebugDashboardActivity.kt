package com.example.sensorlogger

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.sensorlogger.model.TelemetryUiState
import com.example.sensorlogger.repository.TelemetryStateStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlin.math.abs

class DebugDashboardActivity : AppCompatActivity() {

    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }
    
    // System views
    private lateinit var systemRawData: TextView
    private lateinit var systemLoggerStatus: TextView
    private lateinit var systemDataAge: TextView
    
    // GNSS views
    private lateinit var gnssRawData: TextView
    private lateinit var gnssPosQuality: TextView
    private lateinit var gnssSignalEnv: TextView
    private lateinit var gnssMotionState: TextView
    private lateinit var gnssHealth: TextView
    
    // Vehicle views
    private lateinit var vehicleRawData: TextView
    private lateinit var vehicleImpactSeverity: TextView
    private lateinit var vehicleCurveAggr: TextView
    private lateinit var vehicleBrakeAccel: TextView
    private lateinit var vehicleRollRisk: TextView
    private lateinit var vehicleStability: TextView
    
    // IMU views
    private lateinit var imuRawData: TextView
    private lateinit var imuCalibStatus: TextView
    private lateinit var imuDataRate: TextView
    
    // Baro views
    private lateinit var baroRawData: TextView
    private lateinit var baroStatus: TextView
    
    // Network views
    private lateinit var networkRawData: TextView
    private lateinit var networkDeliveryStatus: TextView
    private lateinit var networkOperatorSync: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_dashboard)
        
        bindViews()
        observeTelemetryState()
    }
    
    private fun bindViews() {
        // System
        systemRawData = findViewById(R.id.system_rawData)
        systemLoggerStatus = findViewById(R.id.system_loggerStatusText)
        systemDataAge = findViewById(R.id.system_dataAgeText)
        
        // GNSS
        gnssRawData = findViewById(R.id.gnss_rawData)
        gnssPosQuality = findViewById(R.id.gnss_posQualityText)
        gnssSignalEnv = findViewById(R.id.gnss_signalEnvText)
        gnssMotionState = findViewById(R.id.gnss_motionStateText)
        gnssHealth = findViewById(R.id.gnss_healthText)
        
        // Vehicle
        vehicleRawData = findViewById(R.id.vehicle_rawData)
        vehicleImpactSeverity = findViewById(R.id.vehicle_impactSeverityText)
        vehicleCurveAggr = findViewById(R.id.vehicle_curveAggressivenessText)
        vehicleBrakeAccel = findViewById(R.id.vehicle_brakeAccelText)
        vehicleRollRisk = findViewById(R.id.vehicle_rollRiskText)
        vehicleStability = findViewById(R.id.vehicle_stabilityText)
        
        // IMU
        imuRawData = findViewById(R.id.imu_rawData)
        imuCalibStatus = findViewById(R.id.imu_calibStatusText)
        imuDataRate = findViewById(R.id.imu_dataRateText)
        
        // Baro
        baroRawData = findViewById(R.id.baro_rawData)
        baroStatus = findViewById(R.id.baro_statusText)
        
        // Network
        networkRawData = findViewById(R.id.network_rawData)
        networkDeliveryStatus = findViewById(R.id.network_deliveryStatusText)
        networkOperatorSync = findViewById(R.id.network_operatorSyncText)
    }
    
    private fun observeTelemetryState() {
        lifecycleScope.launch {
            TelemetryStateStore.state.collectLatest { state ->
                updateUI(state)
            }
        }
    }
    
    private fun updateUI(state: TelemetryUiState) {
        try {
            updateSystemCard(state)
            updateGnssCard(state)
            updateVehicleCard(state)
            updateImuCard(state)
            updateBaroCard(state)
            updateNetworkCard(state)
        } catch (e: Exception) {
            Timber.e(e, "Error updating debug dashboard")
        }
    }
    
    private fun updateSystemCard(state: TelemetryUiState) {
        val payload = state.lastPayload
        val sb = SpannableStringBuilder()
        
        sb.append("device.id: ${payload?.deviceId ?: "N/A"}\n")
        sb.append("operator.id: ${payload?.operatorId ?: "N/A"}\n")
        sb.append("equipment.tag: ${payload?.equipmentTag ?: "N/A"}\n")
        sb.append("seq_id: ${payload?.sequenceId ?: "N/A"}\n")
        sb.append("schema.version: ${payload?.schemaVersion ?: "N/A"}\n")
        sb.append("ts_epoch: ${payload?.timestampEpoch ?: "N/A"}\n")
        sb.append("imu.fps_eff: ${payload?.imuFpsEffective?.let { "%.1f".format(it) } ?: "N/A"}\n")
        sb.append("imu.samples: ${payload?.imuSamples ?: "N/A"}\n")
        sb.append("serviceRunning: ${state.isServiceRunning}\n")
        sb.append("mqttStatus: ${state.mqttStatus}")
        
        systemRawData.text = sb.toString()
        
        // Interpretations
        val loggerStatus = calculateLoggerStatus(state)
        systemLoggerStatus.text = "Logger: $loggerStatus"
        systemLoggerStatus.setTextColor(getLoggerStatusColor(loggerStatus))
        
        val dataAge = calculateDataAge(payload?.timestampEpoch)
        systemDataAge.text = dataAge.first
        systemDataAge.setTextColor(dataAge.second)
    }
    
    private fun updateGnssCard(state: TelemetryUiState) {
        val payload = state.lastPayload
        val sb = SpannableStringBuilder()
        
        sb.append("lat: ${payload?.latitude?.let { "%.6f".format(it) } ?: "N/A"}\n")
        sb.append("lon: ${payload?.longitude?.let { "%.6f".format(it) } ?: "N/A"}\n")
        sb.append("alt: ${payload?.altitude?.let { "%.1f".format(it) } ?: "N/A"} m\n")
        sb.append("speed: ${payload?.speed?.let { "%.2f".format(it) } ?: "N/A"} m/s\n")
        sb.append("course: ${payload?.course?.let { "%.1f".format(it) } ?: "N/A"}°\n")
        sb.append("accuracy: ${payload?.accuracyMeters?.let { "%.1f".format(it) } ?: "N/A"} m\n")
        sb.append("vert_accuracy: ${payload?.verticalAccuracyMeters?.let { "%.1f".format(it) } ?: "N/A"} m\n")
        sb.append("hdop: ${payload?.hdop?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("vdop: ${payload?.vdop?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("pdop: ${payload?.pdop?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("num_sats: ${payload?.satellitesUsed ?: "N/A"}\n")
        sb.append("sats_visible: ${payload?.satellitesVisible ?: "N/A"}\n")
        sb.append("cn0_avg: ${payload?.cn0Average?.let { "%.1f".format(it) } ?: "N/A"} dB-Hz\n")
        sb.append("cn0_min: ${payload?.cn0Min?.let { "%.1f".format(it) } ?: "N/A"} dB-Hz\n")
        sb.append("cn0_max: ${payload?.cn0Max?.let { "%.1f".format(it) } ?: "N/A"} dB-Hz\n")
        sb.append("provider: ${payload?.provider ?: "N/A"}\n")
        sb.append("has_l5: ${payload?.hasL5 ?: false}\n")
        sb.append("gps_used: ${payload?.gpsUsed ?: 0}\n")
        sb.append("galileo_used: ${payload?.galileoUsed ?: 0}\n")
        sb.append("glonass_used: ${payload?.glonassUsed ?: 0}\n")
        sb.append("beidou_used: ${payload?.beidouUsed ?: 0}\n")
        sb.append("raw_supported: ${payload?.gnssRawSupported ?: false}\n")
        sb.append("raw_count: ${payload?.gnssRawCount ?: 0}\n")
        
        payload?.gnssRaw?.let { raw ->
            sb.append("doppler_speed: ${raw.dopplerSpeedMps?.let { "%.2f".format(it) } ?: "N/A"} m/s\n")
            sb.append("doppler_sigma: ${raw.dopplerSpeedSigma?.let { "%.2f".format(it) } ?: "N/A"}\n")
            sb.append("sat_update_age: ${raw.satUpdateAgeMs ?: "N/A"} ms\n")
            sb.append("time_to_fix: ${raw.timeToFirstFixMs?.let { "%.0f".format(it) } ?: "N/A"} ms")
        }
        
        gnssRawData.text = sb.toString()
        
        // Interpretations
        val posQuality = calculatePosQuality(payload)
        gnssPosQuality.text = "Position quality: ${posQuality.first}"
        gnssPosQuality.setTextColor(posQuality.second)
        
        val signalEnv = calculateSignalEnv(payload?.cn0Average)
        gnssSignalEnv.text = "Signal env: $signalEnv"
        
        val motionState = calculateMotionState(payload)
        gnssMotionState.text = "Motion state: $motionState"
        
        val gnssHealthResult = calculateGnssHealth(payload)
        gnssHealth.text = "GNSS health: ${gnssHealthResult.first}"
        gnssHealth.setTextColor(gnssHealthResult.second)
    }
    
    private fun updateVehicleCard(state: TelemetryUiState) {
        val payload = state.lastPayload
        val sb = SpannableStringBuilder()
        
        sb.append("acc_longitudinal: ${payload?.accLongitudinalMps2?.let { "%.3f".format(it) } ?: "N/A"} m/s²\n")
        sb.append("acc_lateral: ${payload?.accLateralMps2?.let { "%.3f".format(it) } ?: "N/A"} m/s²\n")
        sb.append("acc_vertical: ${payload?.accVerticalMps2?.let { "%.3f".format(it) } ?: "N/A"} m/s²\n")
        sb.append("tilt_pitch: ${payload?.vehicleTiltPitchDeg?.let { "%.2f".format(it) } ?: "N/A"}°\n")
        sb.append("tilt_roll: ${payload?.vehicleTiltRollDeg?.let { "%.2f".format(it) } ?: "N/A"}°\n")
        sb.append("shock_level: ${payload?.motionShockLevel ?: "N/A"}\n")
        sb.append("shock_score: ${payload?.motionShockScore?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("stationary: ${payload?.motionStationary ?: "N/A"}\n")
        sb.append("yaw_rate: ${payload?.yawRateDegPerSec?.let { "%.2f".format(it) } ?: "N/A"} °/s\n")
        sb.append("gnss.speed: ${payload?.speed?.let { "%.2f".format(it) } ?: "N/A"} m/s")
        
        vehicleRawData.text = sb.toString()
        
        // Interpretations
        val impactSeverity = calculateImpactSeverity(payload)
        vehicleImpactSeverity.text = "Impact: ${impactSeverity.first}"
        vehicleImpactSeverity.setTextColor(impactSeverity.second)
        
        val curveAggr = calculateCurveAggression(payload)
        vehicleCurveAggr.text = "Curve: ${curveAggr.first}"
        vehicleCurveAggr.setTextColor(curveAggr.second)
        
        val brakeAccel = calculateBrakeAccel(payload)
        vehicleBrakeAccel.text = "Brake/Accel: ${brakeAccel.first}"
        vehicleBrakeAccel.setTextColor(brakeAccel.second)
        
        val rollRisk = calculateRollRisk(payload)
        vehicleRollRisk.text = "Roll risk: ${rollRisk.first}"
        vehicleRollRisk.setTextColor(rollRisk.second)
        
        val stability = calculateStability(payload)
        vehicleStability.text = "Stability: $stability"
    }
    
    private fun updateImuCard(state: TelemetryUiState) {
        val payload = state.lastPayload
        val sb = SpannableStringBuilder()
        
        sb.append("pitch: ${payload?.pitchDeg?.let { "%.2f".format(it) } ?: "N/A"}°\n")
        sb.append("roll: ${payload?.rollDeg?.let { "%.2f".format(it) } ?: "N/A"}°\n")
        sb.append("yaw: ${payload?.yawDeg?.let { "%.2f".format(it) } ?: "N/A"}°\n")
        sb.append("quaternion w/x/y/z: ${payload?.quaternionW?.let { "%.3f".format(it) } ?: "N/A"} / ")
        sb.append("${payload?.quaternionX?.let { "%.3f".format(it) } ?: "N/A"} / ")
        sb.append("${payload?.quaternionY?.let { "%.3f".format(it) } ?: "N/A"} / ")
        sb.append("${payload?.quaternionZ?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("linear_acc x/y/z mean: ${payload?.linearAccXMean?.let { "%.3f".format(it) } ?: "N/A"} / ")
        sb.append("${payload?.linearAccYMean?.let { "%.3f".format(it) } ?: "N/A"} / ")
        sb.append("${payload?.linearAccZMean?.let { "%.3f".format(it) } ?: "N/A"} m/s²\n")
        sb.append("linear_acc x/y/z sigma: ${payload?.linearAccXSigma?.let { "%.3f".format(it) } ?: "N/A"} / ")
        sb.append("${payload?.linearAccYSigma?.let { "%.3f".format(it) } ?: "N/A"} / ")
        sb.append("${payload?.linearAccZSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("linear_acc norm rms: ${payload?.linearAccNormRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("gyro x/y/z mean: ${payload?.gyroXMean?.let { "%.3f".format(it) } ?: "N/A"} / ")
        sb.append("${payload?.gyroYMean?.let { "%.3f".format(it) } ?: "N/A"} / ")
        sb.append("${payload?.gyroZMean?.let { "%.3f".format(it) } ?: "N/A"} rad/s\n")
        sb.append("gyro x/y/z sigma: ${payload?.gyroXSigma?.let { "%.3f".format(it) } ?: "N/A"} / ")
        sb.append("${payload?.gyroYSigma?.let { "%.3f".format(it) } ?: "N/A"} / ")
        sb.append("${payload?.gyroZSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("jerk norm rms: ${payload?.jerkNormRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("jerk norm sigma: ${payload?.jerkNormSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("mag field strength: ${payload?.magnetometerFieldStrength?.let { "%.1f".format(it) } ?: "N/A"} µT\n")
        sb.append("fps_eff: ${payload?.imuFpsEffective?.let { "%.1f".format(it) } ?: "N/A"}\n")
        sb.append("samples: ${payload?.imuSamples ?: "N/A"}\n")
        sb.append("acc accuracy: ${payload?.accelerometerAccuracy ?: "N/A"}\n")
        sb.append("gyro accuracy: ${payload?.gyroscopeAccuracy ?: "N/A"}\n")
        sb.append("rotation accuracy: ${payload?.rotationAccuracy ?: "N/A"}")
        
        imuRawData.text = sb.toString()
        
        // Interpretations
        val calibStatus = calculateCalibStatus(payload)
        imuCalibStatus.text = "Calibration: ${calibStatus.first}"
        imuCalibStatus.setTextColor(calibStatus.second)
        
        val dataRate = calculateDataRate(payload)
        imuDataRate.text = "Data rate: ${dataRate.first}"
        imuDataRate.setTextColor(dataRate.second)
    }
    
    private fun updateBaroCard(state: TelemetryUiState) {
        val payload = state.lastPayload
        val sb = SpannableStringBuilder()
        
        sb.append("pressure: ${payload?.baroPressureHpa?.let { "%.1f".format(it) } ?: "N/A"} hPa\n")
        sb.append("altitude: ${payload?.baroAltitudeMeters?.let { "%.1f".format(it) } ?: "N/A"} m")
        
        baroRawData.text = sb.toString()
        
        // Hide card if no valid data
        val baroValid = (payload?.baroPressureHpa != null || payload?.baroAltitudeMeters != null)
        val baroCard = findViewById<View>(R.id.card_baro)
        baroCard?.visibility = if (baroValid) View.VISIBLE else View.GONE
        
        // Interpretation
        val statusText = if (baroValid) "sensor OK" else "sem barômetro no hardware"
        baroStatus.text = "Status: $statusText"
        baroStatus.setTextColor(if (baroValid) Color.GREEN else Color.GRAY)
    }
    
    private fun updateNetworkCard(state: TelemetryUiState) {
        val sb = SpannableStringBuilder()
        
        sb.append("mqttStatus: ${state.mqttStatus}\n")
        sb.append("serviceRunning: ${state.isServiceRunning}\n")
        sb.append("offlineQueueCount: TODO\n")
        sb.append("offlineQueueSizeMB: TODO\n")
        sb.append("brokerEndpoints: TODO\n")
        sb.append("permissions:\n")
        sb.append("  - background location: TODO\n")
        sb.append("  - battery optimization: TODO\n")
        sb.append("  - notifications: TODO")
        
        networkRawData.text = sb.toString()
        
        // Interpretations
        val deliveryStatus = calculateDeliveryStatus(state)
        networkDeliveryStatus.text = "Delivery: ${deliveryStatus.first}"
        networkDeliveryStatus.setTextColor(deliveryStatus.second)
        
        networkOperatorSync.text = "Operator sync: TODO"
    }
    
    // ===== INTERPRETATION CALCULATORS =====
    
    private fun calculateLoggerStatus(state: TelemetryUiState): String {
        if (!state.isServiceRunning) return "STOPPED"
        return when {
            state.mqttStatus.contains("connected", ignoreCase = true) -> "OK"
            else -> "DEGRADED"
        }
    }
    
    private fun getLoggerStatusColor(status: String): Int = when (status) {
        "OK" -> Color.GREEN
        "DEGRADED" -> Color.rgb(255, 165, 0) // Orange
        "STOPPED" -> Color.RED
        else -> Color.GRAY
    }
    
    private fun calculateDataAge(tsEpoch: Long?): Pair<String, Int> {
        if (tsEpoch == null || tsEpoch == 0L) {
            return Pair("Data age: N/A", Color.GRAY)
        }
        val ageSeconds = (System.currentTimeMillis() - tsEpoch) / 1000.0
        val label = when {
            ageSeconds < 5 -> "fresh"
            ageSeconds < 30 -> "stale"
            else -> "very stale"
        }
        val color = when {
            ageSeconds < 5 -> Color.GREEN
            ageSeconds < 30 -> Color.rgb(255, 165, 0) // Orange
            else -> Color.RED
        }
        return Pair("Data age: ${"%.1f".format(ageSeconds)} s ($label)", color)
    }
    
    private fun calculatePosQuality(payload: com.example.sensorlogger.model.TelemetryPayloadV11?): Pair<String, Int> {
        val acc = payload?.accuracyMeters ?: Float.MAX_VALUE
        val hdop = payload?.hdop ?: Float.MAX_VALUE
        val sats = payload?.satellitesUsed ?: 0
        
        return when {
            acc < 5 && hdop < 2 && sats >= 7 -> Pair("OK", Color.GREEN)
            acc < 15 || hdop < 4 -> Pair("WARN", Color.rgb(255, 165, 0))
            else -> Pair("BAD", Color.RED)
        }
    }
    
    private fun calculateSignalEnv(cn0Avg: Float?): String {
        return when {
            cn0Avg == null -> "N/A"
            cn0Avg > 30 -> "céu aberto bom"
            cn0Avg >= 20 -> "parcial bloqueado"
            else -> "sombra / degradado"
        }
    }
    
    private fun calculateMotionState(payload: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val speed = payload?.speed ?: 0f
        val dopplerSigma = payload?.gnssRaw?.dopplerSpeedSigma ?: 0.0
        
        return when {
            speed < 0.5f && dopplerSigma > 2.0 -> "ruído alto"
            speed < 0.5f -> "parado"
            else -> "movendo"
        }
    }
    
    private fun calculateGnssHealth(payload: com.example.sensorlogger.model.TelemetryPayloadV11?): Pair<String, Int> {
        val satAge = payload?.gnssRaw?.satUpdateAgeMs ?: Long.MAX_VALUE
        val sats = payload?.satellitesUsed ?: 0
        
        return when {
            satAge < 1500 && sats >= 4 -> Pair("OK", Color.GREEN)
            else -> Pair("PERDENDO FIX", Color.RED)
        }
    }
    
    private fun calculateImpactSeverity(payload: com.example.sensorlogger.model.TelemetryPayloadV11?): Pair<String, Int> {
        val accVert = payload?.accVerticalMps2 ?: 0f
        val shockLevel = payload?.motionShockLevel ?: ""
        
        return when {
            abs(accVert) > 1.5 || shockLevel == "high" -> Pair("IMPACTO FORTE", Color.RED)
            abs(accVert) > 0.5 -> Pair("IMPACTO MEDIO", Color.rgb(255, 165, 0))
            else -> Pair("OK", Color.GREEN)
        }
    }
    
    private fun calculateCurveAggression(payload: com.example.sensorlogger.model.TelemetryPayloadV11?): Pair<String, Int> {
        val accLat = payload?.accLateralMps2 ?: 0f
        val speed = payload?.speed ?: 0f
        
        return when {
            abs(accLat) > 1.5 && speed > 5 -> Pair("CURVA AGRESSIVA", Color.RED)
            else -> Pair("NORMAL", Color.GREEN)
        }
    }
    
    private fun calculateBrakeAccel(payload: com.example.sensorlogger.model.TelemetryPayloadV11?): Pair<String, Int> {
        val accLong = payload?.accLongitudinalMps2 ?: 0f
        
        return when {
            accLong < -1.5 -> Pair("FREADA FORTE", Color.RED)
            accLong > 1.5 -> Pair("ACELERAÇÃO FORTE", Color.rgb(255, 165, 0))
            else -> Pair("OK", Color.GREEN)
        }
    }
    
    private fun calculateRollRisk(payload: com.example.sensorlogger.model.TelemetryPayloadV11?): Pair<String, Int> {
        val roll = payload?.vehicleTiltRollDeg ?: 0f
        
        return when {
            abs(roll) > 15 -> Pair("ALERTA TOMBAMENTO", Color.RED)
            abs(roll) > 8 -> Pair("Inclinação Alta", Color.rgb(255, 165, 0))
            else -> Pair("Estável", Color.GREEN)
        }
    }
    
    private fun calculateStability(payload: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val stationary = payload?.motionStationary ?: false
        val speed = payload?.speed ?: 0f
        val accVert = payload?.accVerticalMps2 ?: 0f
        
        return when {
            stationary -> "PARADO"
            speed < 0.5f && abs(accVert) > 0.5 -> "VIBRANDO PARADO"
            else -> "MOVIMENTO"
        }
    }
    
    private fun calculateCalibStatus(payload: com.example.sensorlogger.model.TelemetryPayloadV11?): Pair<String, Int> {
        val accAcc = payload?.accelerometerAccuracy ?: ""
        val gyroAcc = payload?.gyroscopeAccuracy ?: ""
        val rotAcc = payload?.rotationAccuracy ?: ""
        
        val allHigh = accAcc == "high" && gyroAcc == "high" && rotAcc == "high"
        
        return if (allHigh) {
            Pair("SENSORES OK", Color.GREEN)
        } else {
            Pair("CALIBRAR", Color.rgb(255, 165, 0))
        }
    }
    
    private fun calculateDataRate(payload: com.example.sensorlogger.model.TelemetryPayloadV11?): Pair<String, Int> {
        val fps = payload?.imuFpsEffective ?: 0f
        val samples = payload?.imuSamples ?: 0
        
        return when {
            fps >= 100 && samples >= 100 -> Pair("RATE OK", Color.GREEN)
            else -> Pair("RATE BAIXO (ENERGY SAVE?)", Color.rgb(255, 165, 0))
        }
    }
    
    private fun calculateDeliveryStatus(state: TelemetryUiState): Pair<String, Int> {
        return when {
            state.mqttStatus.contains("connected", ignoreCase = true) -> 
                Pair("ENVIANDO EM TEMPO REAL", Color.GREEN)
            state.isServiceRunning -> 
                Pair("ARMAZENANDO OFFLINE", Color.rgb(255, 165, 0))
            else -> 
                Pair("SEM PERMISSÃO (LOCALIZAÇÃO BLOQUEADA)", Color.RED)
        }
    }
}
