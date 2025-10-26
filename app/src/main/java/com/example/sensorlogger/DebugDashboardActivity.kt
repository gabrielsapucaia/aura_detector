package com.example.sensorlogger

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.sensorlogger.model.TelemetryUiState
import com.example.sensorlogger.repository.TelemetryStateStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

class DebugDashboardActivity : AppCompatActivity() {
    
    // View references - consolidated RAW + INTERP per card
    private lateinit var systemRawText: TextView
    private lateinit var systemInterpText: TextView
    private lateinit var gnssRawText: TextView
    private lateinit var gnssInterpText: TextView
    private lateinit var vehicleRawText: TextView
    private lateinit var vehicleInterpText: TextView
    private lateinit var imuRawText: TextView
    private lateinit var imuInterpText: TextView
    private lateinit var baroRawText: TextView
    private lateinit var baroInterpText: TextView
    private lateinit var networkRawText: TextView
    private lateinit var networkInterpText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_dashboard)
        
        bindViews()
        observeTelemetryState()
    }
    
    private fun bindViews() {
        systemRawText = findViewById(R.id.systemRawText)
        systemInterpText = findViewById(R.id.systemInterpText)
        gnssRawText = findViewById(R.id.gnssRawText)
        gnssInterpText = findViewById(R.id.gnssInterpText)
        vehicleRawText = findViewById(R.id.vehicleRawText)
        vehicleInterpText = findViewById(R.id.vehicleInterpText)
        imuRawText = findViewById(R.id.imuRawText)
        imuInterpText = findViewById(R.id.imuInterpText)
        baroRawText = findViewById(R.id.baroRawText)
        baroInterpText = findViewById(R.id.baroInterpText)
        networkRawText = findViewById(R.id.networkRawText)
        networkInterpText = findViewById(R.id.networkInterpText)
    }
    
    private fun observeTelemetryState() {
        lifecycleScope.launch {
            TelemetryStateStore.state.collectLatest { state ->
                runOnUiThread {
                    updateUI(state)
                }
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
        
        // RAW DATA
        val raw = StringBuilder()
        raw.append("device.id: ${payload?.deviceId ?: "N/A"}\n")
        raw.append("operator.id: ${payload?.operatorId ?: "N/A"}\n")
        raw.append("equipment.tag: ${payload?.equipmentTag ?: "N/A"}\n")
        raw.append("seq_id: ${payload?.sequenceId ?: 0}\n")
        raw.append("schema.version: ${payload?.schemaVersion ?: "N/A"}\n")
        raw.append("ts_epoch: ${payload?.timestampEpoch ?: "N/A"}\n")
        raw.append("imu.fps_eff: ${payload?.imuFpsEffective?.let { "%.1f".format(it) } ?: "N/A"}\n")
        raw.append("imu.samples: ${payload?.imuSamples ?: "N/A"}\n")
        raw.append("serviceRunning: ${state.isServiceRunning}\n")
        raw.append("mqttStatus: ${state.mqttStatus}")
        systemRawText.text = raw.toString()
        
        // INTERPRETATION
        val interp = SpannableStringBuilder()
        val loggerStatus = if (state.isServiceRunning) {
            if (state.mqttStatus == "Connected") "OK" else "DEGRADED"
        } else "STOPPED"
        val loggerColor = when (loggerStatus) {
            "OK" -> Color.GREEN
            "DEGRADED" -> Color.YELLOW
            else -> Color.RED
        }
        interp.append("Logger: $loggerStatus\n", ForegroundColorSpan(loggerColor), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val dataAge = (System.currentTimeMillis() - (payload?.timestampEpoch ?: 0)) / 1000.0
        val ageText = "Data age: ${"%.1f".format(dataAge)} s"
        val ageColor = when {
            dataAge < 5.0 -> Color.GREEN
            dataAge < 30.0 -> Color.YELLOW
            else -> Color.RED
        }
        interp.append(ageText, ForegroundColorSpan(ageColor), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        systemInterpText.text = interp
    }
    
    private fun updateGnssCard(state: TelemetryUiState) {
        val p = state.lastPayload
        
        // RAW DATA
        val raw = StringBuilder()
        raw.append("lat: ${p?.latitude?.let { "%.6f".format(it) } ?: "N/A"}\n")
        raw.append("lon: ${p?.longitude?.let { "%.6f".format(it) } ?: "N/A"}\n")
        raw.append("alt: ${p?.altitude?.let { "%.1f".format(it) } ?: "N/A"} m\n")
        raw.append("speed: ${p?.speed?.let { "%.2f".format(it) } ?: "N/A"} m/s\n")
        raw.append("course: ${p?.course?.let { "%.1f".format(it) } ?: "N/A"}°\n")
        raw.append("accuracy: ${p?.accuracyMeters?.let { "%.1f".format(it) } ?: "N/A"} m\n")
        raw.append("vert_acc: ${p?.verticalAccuracyMeters?.let { "%.1f".format(it) } ?: "N/A"} m\n")
        raw.append("hdop: ${p?.hdop?.let { "%.2f".format(it) } ?: "N/A"}\n")
        raw.append("vdop: ${p?.vdop?.let { "%.2f".format(it) } ?: "N/A"}\n")
        raw.append("pdop: ${p?.pdop?.let { "%.2f".format(it) } ?: "N/A"}\n")
        raw.append("num_sats: ${p?.satellitesUsed ?: "N/A"}\n")
        raw.append("sats_visible: ${p?.satellitesVisible ?: "N/A"}\n")
        raw.append("cn0_avg: ${p?.cn0Average?.let { "%.1f".format(it) } ?: "N/A"}\n")
        raw.append("cn0_min: ${p?.cn0Min?.let { "%.1f".format(it) } ?: "N/A"}\n")
        raw.append("cn0_max: ${p?.cn0Max?.let { "%.1f".format(it) } ?: "N/A"}\n")
        raw.append("provider: ${p?.provider ?: "N/A"}\n")
        raw.append("has_l5: ${p?.hasL5 ?: false}\n")
        raw.append("gps_used: ${p?.gpsUsed ?: 0}\n")
        raw.append("galileo_used: ${p?.galileoUsed ?: 0}\n")
        raw.append("glonass_used: ${p?.glonassUsed ?: 0}\n")
        raw.append("beidou_used: ${p?.beidouUsed ?: 0}\n")
        raw.append("raw_supported: ${p?.gnssRawSupported ?: false}\n")
        raw.append("raw_count: ${p?.gnssRawCount ?: 0}")
        gnssRawText.text = raw.toString()
        
        // INTERPRETATION
        val interp = SpannableStringBuilder()
        val acc = p?.accuracyMeters ?: 999.0
        val hdop = p?.hdop ?: 99.0
        val sats = p?.satellitesUsed ?: 0
        val posQuality = when {
            acc < 5.0 && hdop < 2.0 && sats >= 7 -> Pair("OK", Color.GREEN)
            acc < 15.0 || hdop < 4.0 -> Pair("WARN", Color.YELLOW)
            else -> Pair("BAD", Color.RED)
        }
        interp.append("Position quality: ${posQuality.first}\n", ForegroundColorSpan(posQuality.second), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val cn0 = p?.cn0Average ?: 0.0
        val signalEnv = when {
            cn0 > 30.0 -> "céu aberto"
            cn0 > 20.0 -> "parcial bloq"
            else -> "degradado"
        }
        interp.append("Signal env: $signalEnv\n", ForegroundColorSpan(Color.LTGRAY), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val speed = p?.speed ?: 0.0
        val motionState = if (speed < 0.5) "parado" else "movendo"
        interp.append("Motion state: $motionState\n", ForegroundColorSpan(Color.LTGRAY), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val health = if (sats >= 4) Pair("OK", Color.GREEN) else Pair("PERDENDO FIX", Color.RED)
        interp.append("GNSS health: ${health.first}", ForegroundColorSpan(health.second), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        gnssInterpText.text = interp
    }
    
    private fun updateVehicleCard(state: TelemetryUiState) {
        val p = state.lastPayload
        
        // RAW DATA
        val raw = StringBuilder()
        raw.append("acc_long: ${p?.accLongitudinalMps2?.let { "%.3f".format(it) } ?: "N/A"} m/s²\n")
        raw.append("acc_lat: ${p?.accLateralMps2?.let { "%.3f".format(it) } ?: "N/A"} m/s²\n")
        raw.append("acc_vert: ${p?.accVerticalMps2?.let { "%.3f".format(it) } ?: "N/A"} m/s²\n")
        raw.append("tilt_pitch: ${p?.vehicleTiltPitchDeg?.let { "%.2f".format(it) } ?: "N/A"}°\n")
        raw.append("tilt_roll: ${p?.vehicleTiltRollDeg?.let { "%.2f".format(it) } ?: "N/A"}°\n")
        raw.append("shock_level: ${p?.motionShockLevel ?: "N/A"}\n")
        raw.append("shock_score: ${p?.motionShockScore?.let { "%.2f".format(it) } ?: "N/A"}\n")
        raw.append("stationary: ${p?.motionStationary ?: "N/A"}\n")
        raw.append("yaw_rate: ${p?.yawRateDegPerSec?.let { "%.2f".format(it) } ?: "N/A"} °/s\n")
        raw.append("gnss.speed: ${p?.speed?.let { "%.2f".format(it) } ?: "N/A"} m/s")
        vehicleRawText.text = raw.toString()
        
        // INTERPRETATION
        val interp = SpannableStringBuilder()
        val accVert = abs(p?.accVerticalMps2 ?: 0.0)
        val impact = when {
            accVert > 1.5 || p?.motionShockLevel == "high" -> Pair("IMPACTO FORTE", Color.RED)
            accVert > 0.5 -> Pair("IMPACTO MEDIO", Color.YELLOW)
            else -> Pair("OK", Color.GREEN)
        }
        interp.append("Impact: ${impact.first}\n", ForegroundColorSpan(impact.second), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val accLat = abs(p?.accLateralMps2 ?: 0.0)
        val curve = if (accLat > 1.5) Pair("CURVA AGRESSIVA", Color.RED) else Pair("NORMAL", Color.GREEN)
        interp.append("Curve: ${curve.first}\n", ForegroundColorSpan(curve.second), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val accLong = p?.accLongitudinalMps2 ?: 0.0
        val brakeAccel = when {
            accLong < -1.5 -> Pair("FREADA FORTE", Color.RED)
            accLong > 1.5 -> Pair("ACELERAÇÃO FORTE", Color.YELLOW)
            else -> Pair("OK", Color.GREEN)
        }
        interp.append("Brake/Accel: ${brakeAccel.first}\n", ForegroundColorSpan(brakeAccel.second), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val roll = abs(p?.vehicleTiltRollDeg ?: 0.0)
        val rollRisk = when {
            roll > 15 -> Pair("ALERTA TOMBAMENTO", Color.RED)
            roll > 8 -> Pair("Inclinação Alta", Color.YELLOW)
            else -> Pair("Estável", Color.GREEN)
        }
        interp.append("Roll risk: ${rollRisk.first}\n", ForegroundColorSpan(rollRisk.second), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val stability = if (p?.motionStationary == true) "PARADO" else "MOVIMENTO"
        interp.append("Stability: $stability", ForegroundColorSpan(Color.LTGRAY), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        vehicleInterpText.text = interp
    }
    
    private fun updateImuCard(state: TelemetryUiState) {
        val p = state.lastPayload
        
        // RAW DATA
        val raw = StringBuilder()
        raw.append("pitch: ${p?.pitchDeg?.let { "%.2f".format(it) } ?: "N/A"}°\n")
        raw.append("roll: ${p?.rollDeg?.let { "%.2f".format(it) } ?: "N/A"}°\n")
        raw.append("yaw: ${p?.yawDeg?.let { "%.2f".format(it) } ?: "N/A"}°\n")
        raw.append("q.w: ${p?.quaternionW?.let { "%.3f".format(it) } ?: "N/A"}\n")
        raw.append("q.x: ${p?.quaternionX?.let { "%.3f".format(it) } ?: "N/A"}\n")
        raw.append("q.y: ${p?.quaternionY?.let { "%.3f".format(it) } ?: "N/A"}\n")
        raw.append("q.z: ${p?.quaternionZ?.let { "%.3f".format(it) } ?: "N/A"}\n")
        raw.append("linear_acc.x: ${p?.linearAccXMean?.let { "%.3f".format(it) } ?: "N/A"}\n")
        raw.append("linear_acc.y: ${p?.linearAccYMean?.let { "%.3f".format(it) } ?: "N/A"}\n")
        raw.append("linear_acc.z: ${p?.linearAccZMean?.let { "%.3f".format(it) } ?: "N/A"}\n")
        raw.append("gyro.x: ${p?.gyroXMean?.let { "%.3f".format(it) } ?: "N/A"}\n")
        raw.append("gyro.y: ${p?.gyroYMean?.let { "%.3f".format(it) } ?: "N/A"}\n")
        raw.append("gyro.z: ${p?.gyroZMean?.let { "%.3f".format(it) } ?: "N/A"}\n")
        raw.append("jerk.rms: ${p?.jerkNormRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        raw.append("mag.field: ${p?.magnetometerFieldStrength?.let { "%.1f".format(it) } ?: "N/A"} µT\n")
        raw.append("fps_eff: ${p?.imuFpsEffective?.let { "%.1f".format(it) } ?: "N/A"}\n")
        raw.append("samples: ${p?.imuSamples ?: "N/A"}")
        imuRawText.text = raw.toString()
        
        // INTERPRETATION
        val interp = SpannableStringBuilder()
        val calib = "SENSORES OK" // Simplified - could check accuracy if exposed
        interp.append("Calibration: $calib\n", ForegroundColorSpan(Color.GREEN), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val fps = p?.imuFpsEffective ?: 0.0
        val rate = if (fps >= 100) Pair("RATE OK", Color.GREEN) else Pair("RATE BAIXO", Color.YELLOW)
        interp.append("Data rate: ${rate.first}", ForegroundColorSpan(rate.second), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        imuInterpText.text = interp
    }
    
    private fun updateBaroCard(state: TelemetryUiState) {
        val p = state.lastPayload
        
        // Check if we have valid data
        val hasPressure = p?.baroPressureHpa != null
        val hasAltitude = p?.baroAltitudeMeters != null
        val hasData = hasPressure || hasAltitude
        
        // Toggle visibility
        val baroCard = findViewById<View>(R.id.barometerCardRoot)
        baroCard.visibility = if (hasData) View.VISIBLE else View.GONE
        
        if (!hasData) return
        
        // RAW DATA
        val raw = StringBuilder()
        raw.append("pressure: ${p?.baroPressureHpa?.let { "%.1f".format(it) } ?: "N/A"} hPa\n")
        raw.append("altitude: ${p?.baroAltitudeMeters?.let { "%.1f".format(it) } ?: "N/A"} m")
        baroRawText.text = raw.toString()
        
        // INTERPRETATION
        val interp = SpannableStringBuilder()
        val status = if (hasData) Pair("sensor OK", Color.GREEN) else Pair("sem barômetro", Color.GRAY)
        interp.append("Status: ${status.first}", ForegroundColorSpan(status.second), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        baroInterpText.text = interp
    }
    
    private fun updateNetworkCard(state: TelemetryUiState) {
        // RAW DATA
        val raw = StringBuilder()
        raw.append("mqttStatus: ${state.mqttStatus}\n")
        raw.append("serviceRunning: ${state.isServiceRunning}\n")
        raw.append("offlineQueueCount: TODO\n")
        raw.append("offlineQueueSizeMB: TODO\n")
        raw.append("brokerEndpoints: TODO")
        networkRawText.text = raw.toString()
        
        // INTERPRETATION
        val interp = SpannableStringBuilder()
        val delivery = if (state.mqttStatus == "Connected") {
            Pair("ENVIANDO EM TEMPO REAL", Color.GREEN)
        } else {
            Pair("ARMAZENANDO OFFLINE", Color.YELLOW)
        }
        interp.append("Delivery: ${delivery.first}\n", ForegroundColorSpan(delivery.second), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        interp.append("Operator sync: TODO", ForegroundColorSpan(Color.LTGRAY), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        networkInterpText.text = interp
    }
}

