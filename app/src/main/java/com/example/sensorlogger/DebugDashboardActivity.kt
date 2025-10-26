package com.example.sensorlogger

import android.graphics.Color
import android.os.Bundle
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
import kotlin.math.sqrt

class DebugDashboardActivity : AppCompatActivity() {

    private lateinit var systemRawData: TextView
    private lateinit var gnssRawData: TextView
    private lateinit var vehicleRawData: TextView
    private lateinit var imuRawData: TextView
    private lateinit var networkRawData: TextView
    private lateinit var baroRawData: TextView
    private lateinit var qualitativeMetrics: TextView
    private lateinit var interpretationSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_dashboard)
        
        bindViews()
        observeTelemetryState()
    }
    
    private fun bindViews() {
        systemRawData = findViewById(R.id.system_rawData)
        gnssRawData = findViewById(R.id.gnss_rawData)
        vehicleRawData = findViewById(R.id.vehicle_rawData)
        imuRawData = findViewById(R.id.imu_rawData)
        networkRawData = findViewById(R.id.network_rawData)
        baroRawData = findViewById(R.id.baro_rawData)
        qualitativeMetrics = findViewById(R.id.qualitative_metrics)
        interpretationSummary = findViewById(R.id.interpretation_summary)
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
            updateNetworkCard(state)
            updateBaroCard(state)
            updateQualitativeMetrics(state)
            updateInterpretation(state)
        } catch (e: Exception) {
            Timber.e(e, "Error updating debug dashboard")
        }
    }
    
    private fun updateSystemCard(state: TelemetryUiState) {
        val payload = state.lastPayload
        val sb = StringBuilder()
        
        sb.append("device.id: ${payload?.deviceId ?: "N/A"}\n")
        sb.append("operator.id: ${payload?.operatorId ?: "N/A"}\n")
        sb.append("equipment.tag: ${payload?.equipmentTag ?: "N/A"}\n")
        sb.append("seq_id: ${payload?.sequenceId ?: "N/A"}\n")
        sb.append("schema.version: ${payload?.schemaVersion ?: "N/A"}\n")
        sb.append("ts_epoch: ${payload?.timestampEpoch ?: "N/A"}\n")
        
        val dataAgeS = payload?.timestampEpoch?.let { 
            (System.currentTimeMillis() - it) / 1000.0f
        }
        sb.append("data_age_s: ${dataAgeS?.let { "%.1f".format(it) } ?: "N/A"}\n")
        
        sb.append("serviceRunning: ${state.isServiceRunning}\n")
        sb.append("mqttStatus: ${state.mqttStatus}\n")
        sb.append("imu.fps_eff: ${payload?.imuFpsEffective?.let { "%.1f".format(it) } ?: "N/A"}\n")
        sb.append("imu.samples: ${payload?.imuSamples ?: "N/A"}\n\n")
        
        // Interpretação inline
        val loggerStatus = when {
            !state.isServiceRunning -> "STOPPED"
            state.mqttStatus.contains("connected", ignoreCase = true) -> "OK"
            else -> "DEGRADED"
        }
        val loggerColor = when (loggerStatus) {
            "OK" -> "#00FF66"
            "DEGRADED" -> "#FFD633"
            else -> "#FF4444"
        }
        
        val dataAgeStatus = when {
            dataAgeS == null -> "N/A"
            dataAgeS < 5 -> "fresh"
            dataAgeS < 30 -> "stale"
            else -> "very stale"
        }
        val dataAgeColor = when {
            dataAgeS == null -> "#888888"
            dataAgeS < 5 -> "#00FF66"
            dataAgeS < 30 -> "#FFD633"
            else -> "#FF4444"
        }
        
        sb.append("→ Logger: $loggerStatus\n")
        sb.append("→ Data age: $dataAgeStatus")
        
        systemRawData.text = sb.toString()
    }
    
    private fun updateGnssCard(state: TelemetryUiState) {
        val payload = state.lastPayload
        val sb = StringBuilder()
        
        // Fix Info
        sb.append("gnss.fix: ${payload?.gnssFix ?: "N/A"}\n")
        sb.append("gnss.provider: ${payload?.provider ?: "N/A"}\n")
        sb.append("gnss.lat: ${payload?.latitude?.let { "%.6f".format(it) } ?: "N/A"}\n")
        sb.append("gnss.lon: ${payload?.longitude?.let { "%.6f".format(it) } ?: "N/A"}\n")
        sb.append("gnss.alt: ${payload?.altitude?.let { "%.1f".format(it) } ?: "N/A"} m\n")
        sb.append("gnss.speed: ${payload?.speed?.let { val ms = it; "%.2f m/s (%.1f km/h)".format(ms, ms * 3.6f) } ?: "N/A"}\n")
        sb.append("gnss.course: ${payload?.course?.let { "%.1f".format(it) } ?: "N/A"}°\n")
        sb.append("gnss.speed_accuracy_mps: ${payload?.speedAccuracyMps?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("gnss.bearing_accuracy_deg: ${payload?.bearingAccuracyDeg?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("gnss.elapsedRealtimeNanos: ${payload?.gnssElapsedRealtimeNanos ?: "N/A"}\n\n")
        
        // Accuracy
        sb.append("gnss.accuracy_m: ${payload?.accuracyMeters?.let { "%.1f".format(it) } ?: "N/A"}\n")
        sb.append("gnss.vert_accuracy_m: ${payload?.verticalAccuracyMeters?.let { "%.1f".format(it) } ?: "N/A"}\n")
        sb.append("gnss.hdop: ${payload?.hdop?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("gnss.vdop: ${payload?.vdop?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("gnss.pdop: ${payload?.pdop?.let { "%.2f".format(it) } ?: "N/A"}\n\n")
        
        // Satellites
        sb.append("gnss.num_sats: ${payload?.satellitesUsed ?: "N/A"}\n")
        sb.append("gnss.sats_visible: ${payload?.satellitesVisible ?: "N/A"}\n")
        sb.append("gnss.has_l5: ${payload?.hasL5 ?: "N/A"}\n\n")
        
        // Constellations
        sb.append("GPS used/visible: ${payload?.gpsUsed ?: 0} / ${payload?.gpsVisible ?: 0}\n")
        sb.append("Galileo used/visible: ${payload?.galileoUsed ?: 0} / ${payload?.galileoVisible ?: 0}\n")
        sb.append("GLONASS used/visible: ${payload?.glonassUsed ?: 0} / ${payload?.glonassVisible ?: 0}\n")
        sb.append("BeiDou used/visible: ${payload?.beidouUsed ?: 0} / ${payload?.beidouVisible ?: 0}\n")
        sb.append("QZSS used/visible: ${payload?.qzssUsed ?: 0} / ${payload?.qzssVisible ?: 0}\n")
        sb.append("SBAS used/visible: ${payload?.sbasUsed ?: 0} / ${payload?.sbasVisible ?: 0}\n\n")
        
        // CN0 Signal Metrics
        sb.append("gnss.cn0.min: ${payload?.cn0Min?.let { "%.1f".format(it) } ?: "N/A"} dB-Hz\n")
        sb.append("gnss.cn0.max: ${payload?.cn0Max?.let { "%.1f".format(it) } ?: "N/A"} dB-Hz\n")
        sb.append("gnss.cn0_avg: ${payload?.cn0Average?.let { "%.1f".format(it) } ?: "N/A"} dB-Hz\n")
        sb.append("gnss.cn0.p25: ${payload?.cn0Percentile25?.let { "%.1f".format(it) } ?: "N/A"} dB-Hz\n")
        sb.append("gnss.cn0.p50: ${payload?.cn0Median?.let { "%.1f".format(it) } ?: "N/A"} dB-Hz\n")
        sb.append("gnss.cn0.p75: ${payload?.cn0Percentile75?.let { "%.1f".format(it) } ?: "N/A"} dB-Hz\n\n")
        
        // Raw GNSS
        sb.append("gnss.raw_supported: ${payload?.gnssRawSupported ?: false}\n")
        sb.append("gnss.raw_count: ${payload?.gnssRawCount ?: 0}\n")
        
        payload?.gnssRaw?.let { raw ->
            sb.append("\n--- GNSS RAW SNAPSHOT ---\n")
            sb.append("timestamp_nanos: ${raw.timestampNanos}\n")
            sb.append("doppler_speed_mps: ${raw.dopplerSpeedMps?.let { "%.2f".format(it) } ?: "N/A"}\n")
            sb.append("doppler_speed_sigma: ${raw.dopplerSpeedSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
            sb.append("doppler_sat_count: ${raw.dopplerSatCount ?: "N/A"}\n")
            sb.append("agc_db_avg: ${raw.agcDbAvg?.let { "%.2f".format(it) } ?: "N/A"}\n")
            sb.append("agc_db_min: ${raw.agcDbMin?.let { "%.2f".format(it) } ?: "N/A"}\n")
            sb.append("agc_db_max: ${raw.agcDbMax?.let { "%.2f".format(it) } ?: "N/A"}\n")
            sb.append("cn0_avg: ${raw.cn0Avg?.let { "%.2f".format(it) } ?: "N/A"}\n")
            sb.append("cn0_min: ${raw.cn0Min?.let { "%.2f".format(it) } ?: "N/A"}\n")
            sb.append("cn0_max: ${raw.cn0Max?.let { "%.2f".format(it) } ?: "N/A"}\n")
            sb.append("cn0_sigma: ${raw.cn0Sigma?.let { "%.2f".format(it) } ?: "N/A"}\n")
            sb.append("clock_bias_nanos: ${raw.clockBiasNanos ?: "N/A"}\n")
            sb.append("clock_drift_nanos_per_s: ${raw.clockDriftNanosPerSecond?.let { "%.2f".format(it) } ?: "N/A"}\n")
            sb.append("time_to_first_fix_ms: ${raw.timeToFirstFixMs?.let { "%.0f".format(it) } ?: "N/A"}\n")
            sb.append("sat_update_age_ms: ${raw.satUpdateAgeMs ?: "N/A"}\n")
            sb.append("measurements_count: ${raw.measurements.size}\n")
            
            if (raw.measurements.isNotEmpty()) {
                sb.append("\n--- RAW MEASUREMENTS (primeiros 10) ---\n")
                raw.measurements.take(10).forEach { m ->
                    sb.append("svid ${m.svid} const ${m.constellationType}: ")
                    sb.append("cn0=${m.cn0DbHz?.let { "%.1f".format(it) } ?: "N/A"} ")
                    sb.append("dopHz=${m.pseudorangeRateMetersPerSecond?.let { "%.1f".format(it) } ?: "N/A"} ")
                    sb.append("carrHz=${m.carrierFrequencyHz?.let { "%.0f".format(it) } ?: "N/A"} ")
                    sb.append("agc=${m.agcDb?.let { "%.2f".format(it) } ?: "N/A"} ")
                    sb.append("snr=${m.snrDb?.let { "%.1f".format(it) } ?: "N/A"}\n")
                }
                if (raw.measurements.size > 10) {
                    sb.append("... (${raw.measurements.size - 10} more)\n")
                }
            }
        }
        
        // RAW counts by constellation
        sb.append("\n--- RAW COUNTS BY CONSTELLATION ---\n")
        sb.append("GPS: ${payload?.rawGpsCount ?: 0}\n")
        sb.append("Galileo: ${payload?.rawGalileoCount ?: 0}\n")
        sb.append("GLONASS: ${payload?.rawGlonassCount ?: 0}\n")
        sb.append("BeiDou: ${payload?.rawBeidouCount ?: 0}\n")
        sb.append("QZSS: ${payload?.rawQzssCount ?: 0}\n")
        sb.append("SBAS: ${payload?.rawSbasCount ?: 0}\n\n")
        
        // Interpretation
        val acc = payload?.accuracyMeters ?: Float.MAX_VALUE
        val hdop = payload?.hdop ?: Float.MAX_VALUE
        val sats = payload?.satellitesUsed ?: 0
        val posQuality = when {
            acc < 5 && hdop < 2 && sats >= 7 -> "OK"
            acc < 15 || hdop < 4 -> "WARN"
            else -> "BAD"
        }
        
        val cn0Avg = payload?.cn0Average ?: 0f
        val signalEnv = when {
            cn0Avg > 30 -> "céu aberto bom"
            cn0Avg >= 20 -> "parcial bloqueado"
            else -> "sombra / degradado"
        }
        
        val speed = payload?.speed ?: 0f
        val motionState = if (speed < 0.5f) "parado" else "movendo"
        
        val satAge = payload?.gnssRaw?.satUpdateAgeMs ?: Long.MAX_VALUE
        val gnssHealth = if (satAge < 1500 && sats >= 4) "OK" else "PERDENDO FIX"
        
        sb.append("→ Position quality: $posQuality\n")
        sb.append("→ Signal env: $signalEnv\n")
        sb.append("→ Motion state: $motionState\n")
        sb.append("→ GNSS health: $gnssHealth")
        
        gnssRawData.text = sb.toString()
    }
    
    private fun updateVehicleCard(state: TelemetryUiState) {
        val payload = state.lastPayload
        val sb = StringBuilder()
        
        sb.append("imu.acc_longitudinal_mps2: ${payload?.accLongitudinalMps2?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("imu.acc_lateral_mps2: ${payload?.accLateralMps2?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("imu.acc_vertical_mps2: ${payload?.accVerticalMps2?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("imu.vehicle_tilt_pitch_deg: ${payload?.vehicleTiltPitchDeg?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("imu.vehicle_tilt_roll_deg: ${payload?.vehicleTiltRollDeg?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("imu.yaw_rate.deg_s: ${payload?.yawRateDegPerSec?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("imu.motion.shock_level: ${payload?.motionShockLevel ?: "N/A"}\n")
        sb.append("imu.motion.shock_score: ${payload?.motionShockScore?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("imu.motion.stationary: ${payload?.motionStationary ?: "N/A"}\n")
        sb.append("gnss.speed: ${payload?.speed?.let { "%.2f m/s".format(it) } ?: "N/A"}\n\n")
        
        // Interpretation
        val accVert = payload?.accVerticalMps2 ?: 0f
        val shockLevel = payload?.motionShockLevel ?: ""
        val impact = when {
            abs(accVert) > 1.5 || shockLevel == "high" -> "IMPACTO FORTE"
            abs(accVert) > 0.5 -> "IMPACTO MEDIO"
            else -> "Suave"
        }
        
        val accLat = payload?.accLateralMps2 ?: 0f
        val speed = payload?.speed ?: 0f
        val curve = if (abs(accLat) > 1.5 && speed > 5) "CURVA AGRESSIVA" else "Normal"
        
        val accLong = payload?.accLongitudinalMps2 ?: 0f
        val brakeAccel = when {
            accLong < -1.5 -> "FREADA FORTE"
            accLong > 1.5 -> "ACELERAÇÃO FORTE"
            else -> "Normal"
        }
        
        val roll = payload?.vehicleTiltRollDeg ?: 0f
        val rollRisk = when {
            abs(roll) > 15 -> "ALERTA TOMBAMENTO"
            abs(roll) > 8 -> "Inclinação Alta"
            else -> "Estável"
        }
        
        val stationary = payload?.motionStationary ?: false
        val stability = when {
            stationary -> "PARADO"
            speed < 0.5f && abs(accVert) > 0.5 -> "VIBRANDO PARADO"
            else -> "MOVIMENTO"
        }
        
        sb.append("→ Impact: $impact\n")
        sb.append("→ Curve: $curve\n")
        sb.append("→ Brake/Accel: $brakeAccel\n")
        sb.append("→ Roll risk: $rollRisk\n")
        sb.append("→ Stability: $stability")
        
        vehicleRawData.text = sb.toString()
    }
    
    private fun updateImuCard(state: TelemetryUiState) {
        val payload = state.lastPayload
        val sb = StringBuilder()
        
        // Sample rate
        sb.append("imu.samples: ${payload?.imuSamples ?: "N/A"}\n")
        sb.append("imu.fps_eff: ${payload?.imuFpsEffective?.let { "%.1f".format(it) } ?: "N/A"}\n\n")
        
        // Orientation
        sb.append("--- ORIENTATION ---\n")
        sb.append("pitch_deg: ${payload?.pitchDeg?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("roll_deg: ${payload?.rollDeg?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("yaw_deg: ${payload?.yawDeg?.let { "%.2f".format(it) } ?: "N/A"}\n")
        sb.append("quaternion.w: ${payload?.quaternionW?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("quaternion.x: ${payload?.quaternionX?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("quaternion.y: ${payload?.quaternionY?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("quaternion.z: ${payload?.quaternionZ?.let { "%.3f".format(it) } ?: "N/A"}\n\n")
        
        // Linear Acceleration (gravity removed)
        sb.append("--- LINEAR ACC (gravity removed) ---\n")
        sb.append("x.mean: ${payload?.linearAccXMean?.let { "%.3f".format(it) } ?: "N/A"} m/s²\n")
        sb.append("x.rms: ${payload?.linearAccXRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("x.min: ${payload?.linearAccXMin?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("x.max: ${payload?.linearAccXMax?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("x.sigma: ${payload?.linearAccXSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.mean: ${payload?.linearAccYMean?.let { "%.3f".format(it) } ?: "N/A"} m/s²\n")
        sb.append("y.rms: ${payload?.linearAccYRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.min: ${payload?.linearAccYMin?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.max: ${payload?.linearAccYMax?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.sigma: ${payload?.linearAccYSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.mean: ${payload?.linearAccZMean?.let { "%.3f".format(it) } ?: "N/A"} m/s²\n")
        sb.append("z.rms: ${payload?.linearAccZRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.min: ${payload?.linearAccZMin?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.max: ${payload?.linearAccZMax?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.sigma: ${payload?.linearAccZSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("norm.rms: ${payload?.linearAccNormRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("norm.sigma: ${payload?.linearAccNormSigma?.let { "%.3f".format(it) } ?: "N/A"}\n\n")
        
        // Body Acceleration (with gravity)
        sb.append("--- BODY ACC (with gravity) ---\n")
        sb.append("x.mean: ${payload?.accXMean?.let { "%.3f".format(it) } ?: "N/A"} m/s²\n")
        sb.append("x.rms: ${payload?.accXRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("x.min: ${payload?.accXMin?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("x.max: ${payload?.accXMax?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("x.sigma: ${payload?.accXSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.mean: ${payload?.accYMean?.let { "%.3f".format(it) } ?: "N/A"} m/s²\n")
        sb.append("y.rms: ${payload?.accYRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.min: ${payload?.accYMin?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.max: ${payload?.accYMax?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.sigma: ${payload?.accYSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.mean: ${payload?.accZMean?.let { "%.3f".format(it) } ?: "N/A"} m/s²\n")
        sb.append("z.rms: ${payload?.accZRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.min: ${payload?.accZMin?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.max: ${payload?.accZMax?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.sigma: ${payload?.accZSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("norm.rms: ${payload?.accNormRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("norm.sigma: ${payload?.accNormSigma?.let { "%.3f".format(it) } ?: "N/A"}\n\n")
        
        // Jerk
        sb.append("--- JERK ---\n")
        sb.append("x.rms: ${payload?.jerkXRms?.let { "%.3f".format(it) } ?: "N/A"} m/s³\n")
        sb.append("y.rms: ${payload?.jerkYRms?.let { "%.3f".format(it) } ?: "N/A"} m/s³\n")
        sb.append("z.rms: ${payload?.jerkZRms?.let { "%.3f".format(it) } ?: "N/A"} m/s³\n")
        sb.append("norm.rms: ${payload?.jerkNormRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("norm.sigma: ${payload?.jerkNormSigma?.let { "%.3f".format(it) } ?: "N/A"}\n\n")
        
        // Gyro
        sb.append("--- GYRO ---\n")
        sb.append("x.mean: ${payload?.gyroXMean?.let { "%.3f".format(it) } ?: "N/A"} rad/s\n")
        sb.append("x.rms: ${payload?.gyroXRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("x.min: ${payload?.gyroXMin?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("x.max: ${payload?.gyroXMax?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("x.sigma: ${payload?.gyroXSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.mean: ${payload?.gyroYMean?.let { "%.3f".format(it) } ?: "N/A"} rad/s\n")
        sb.append("y.rms: ${payload?.gyroYRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.min: ${payload?.gyroYMin?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.max: ${payload?.gyroYMax?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.sigma: ${payload?.gyroYSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.mean: ${payload?.gyroZMean?.let { "%.3f".format(it) } ?: "N/A"} rad/s\n")
        sb.append("z.rms: ${payload?.gyroZRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.min: ${payload?.gyroZMin?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.max: ${payload?.gyroZMax?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.sigma: ${payload?.gyroZSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("norm.rms: ${payload?.gyroNormRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("norm.sigma: ${payload?.gyroNormSigma?.let { "%.3f".format(it) } ?: "N/A"}\n\n")
        
        // Magnetometer
        sb.append("--- MAGNETOMETER ---\n")
        sb.append("x.mean: ${payload?.magnetometerXMean?.let { "%.3f".format(it) } ?: "N/A"} µT\n")
        sb.append("x.rms: ${payload?.magnetometerXRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("x.min: ${payload?.magnetometerXMin?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("x.max: ${payload?.magnetometerXMax?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("x.sigma: ${payload?.magnetometerXSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.mean: ${payload?.magnetometerYMean?.let { "%.3f".format(it) } ?: "N/A"} µT\n")
        sb.append("y.rms: ${payload?.magnetometerYRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.min: ${payload?.magnetometerYMin?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.max: ${payload?.magnetometerYMax?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("y.sigma: ${payload?.magnetometerYSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.mean: ${payload?.magnetometerZMean?.let { "%.3f".format(it) } ?: "N/A"} µT\n")
        sb.append("z.rms: ${payload?.magnetometerZRms?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.min: ${payload?.magnetometerZMin?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.max: ${payload?.magnetometerZMax?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("z.sigma: ${payload?.magnetometerZSigma?.let { "%.3f".format(it) } ?: "N/A"}\n")
        sb.append("field_strength_uT: ${payload?.magnetometerFieldStrength?.let { "%.1f".format(it) } ?: "N/A"}\n\n")
        
        // Sensor Health
        sb.append("--- SENSOR HEALTH ---\n")
        sb.append("acc.accuracy: ${payload?.accelerometerAccuracy ?: "N/A"}\n")
        sb.append("gyro.accuracy: ${payload?.gyroscopeAccuracy ?: "N/A"}\n")
        sb.append("rotation.accuracy: ${payload?.rotationAccuracy ?: "N/A"}\n\n")
        
        // Interpretation
        val accAcc = payload?.accelerometerAccuracy ?: ""
        val gyroAcc = payload?.gyroscopeAccuracy ?: ""
        val rotAcc = payload?.rotationAccuracy ?: ""
        val calibStatus = if (accAcc == "high" && gyroAcc == "high" && rotAcc == "high") "SENSORES OK" else "CALIBRAR"
        
        val fps = payload?.imuFpsEffective ?: 0f
        val samples = payload?.imuSamples ?: 0
        val dataRate = if (fps >= 100 && samples >= 100) "RATE OK" else "RATE BAIXO"
        
        sb.append("→ Calibration: $calibStatus\n")
        sb.append("→ Data rate: $dataRate")
        
        imuRawData.text = sb.toString()
    }
    
    private fun updateNetworkCard(state: TelemetryUiState) {
        val sb = StringBuilder()
        
        sb.append("mqttStatus: ${state.mqttStatus}\n")
        sb.append("serviceRunning: ${state.isServiceRunning}\n")
        sb.append("localBrokerStatus: ${state.localBrokerStatus}\n")
        sb.append("cloudBrokerStatus: ${state.cloudBrokerStatus}\n")
        sb.append("brokerActiveEndpoint: ${state.brokerActiveEndpoint ?: "N/A"}\n")
        sb.append("queueSize: ${state.queueSize}\n")
        sb.append("sequence: ${state.sequence}\n\n")
        
        // Interpretation
        val deliveryStatus = when {
            state.mqttStatus.contains("connected", ignoreCase = true) -> "ENVIANDO EM TEMPO REAL"
            state.isServiceRunning -> "ARMAZENANDO OFFLINE"
            else -> "SEM PERMISSÃO (LOCALIZAÇÃO BLOQUEADA)"
        }
        
        sb.append("→ Delivery: $deliveryStatus\n")
        sb.append("→ Operator: ${state.operatorName.ifEmpty { "N/A" }}")
        
        networkRawData.text = sb.toString()
    }
    
    private fun updateBaroCard(state: TelemetryUiState) {
        val payload = state.lastPayload
        val sb = StringBuilder()
        
        sb.append("baro.pressure_hpa: ${payload?.baroPressureHpa?.let { "%.1f".format(it) } ?: "N/A"}\n")
        sb.append("baro.altitude_m: ${payload?.baroAltitudeMeters?.let { "%.1f".format(it) } ?: "N/A"}\n\n")
        
        val baroValid = (payload?.baroPressureHpa != null || payload?.baroAltitudeMeters != null)
        val statusText = if (baroValid) "sensor OK" else "sem barômetro no hardware"
        sb.append("→ Status: $statusText")
        
        baroRawData.text = sb.toString()
        
        // Hide card if no valid data
        val baroCard = findViewById<View>(R.id.card_baro)
        baroCard?.visibility = if (baroValid) View.VISIBLE else View.GONE
    }
    
    private fun updateQualitativeMetrics(state: TelemetryUiState) {
        val payload = state.lastPayload
        val sb = StringBuilder()
        
        // Velocidade média GNSS
        val speedMs = payload?.speed ?: 0f
        val speedKmh = speedMs * 3.6f
        sb.append("Velocidade GNSS: %.2f m/s (%.1f km/h)\n".format(speedMs, speedKmh))
        
        // Precisão horizontal média
        val accH = payload?.accuracyMeters ?: 0f
        sb.append("Precisão horizontal: %.1f m\n".format(accH))
        
        // Amplitude jerk total
        val jerkNormRms = payload?.jerkNormRms ?: 0f
        sb.append("Amplitude jerk total: %.3f m/s³\n".format(jerkNormRms))
        
        // Magnitude média do vetor aceleração linear
        val linearAccNormRms = payload?.linearAccNormRms ?: 0f
        sb.append("Magnitude média acc linear: %.3f m/s²\n".format(linearAccNormRms))
        
        // Índice de vibração
        val accNormSigma = payload?.accNormSigma ?: 0.001f
        val vibrIndex = if (accNormSigma > 0) jerkNormRms / accNormSigma else 0f
        sb.append("Índice de vibração: %.2f\n".format(vibrIndex))
        
        // Inclinação total
        val pitch = payload?.vehicleTiltPitchDeg ?: 0f
        val roll = payload?.vehicleTiltRollDeg ?: 0f
        val tiltTotal = sqrt(pitch * pitch + roll * roll)
        sb.append("Inclinação total: %.2f°\n".format(tiltTotal))
        
        // Status de movimento
        val stationary = payload?.motionStationary ?: false
        val accVertical = payload?.accVerticalMps2 ?: 0f
        val movementStatus = when {
            stationary -> "parado"
            abs(accVertical) > 1.0 -> "aceleração brusca"
            speedMs > 2.0 -> "deslocamento suave"
            else -> "movimento leve"
        }
        sb.append("Status de movimento: $movementStatus\n")
        
        // Qualidade GNSS geral
        val hdop = payload?.hdop ?: Float.MAX_VALUE
        val pdop = payload?.pdop ?: Float.MAX_VALUE
        val cn0Avg = payload?.cn0Average ?: 0f
        val gnssQuality = when {
            hdop < 2 && pdop < 3 && cn0Avg > 30 -> "excelente"
            hdop < 4 && cn0Avg > 20 -> "regular"
            else -> "fraco"
        }
        sb.append("Qualidade GNSS geral: $gnssQuality")
        
        qualitativeMetrics.text = sb.toString()
    }
    
    private fun updateInterpretation(state: TelemetryUiState) {
        val payload = state.lastPayload
        val sb = StringBuilder()
        
        // Situação GNSS
        val cn0Avg = payload?.cn0Average ?: 0f
        val hdop = payload?.hdop ?: Float.MAX_VALUE
        val gnssStatus = when {
            cn0Avg > 30 && hdop < 2 -> "sinal excelente"
            cn0Avg > 20 && hdop < 4 -> "sinal regular"
            else -> "sinal fraco"
        }
        sb.append("Situação GNSS: $gnssStatus\n")
        
        // Situação IMU
        val jerkNormRms = payload?.jerkNormRms ?: 0f
        val linearAccNormRms = payload?.linearAccNormRms ?: 0f
        val imuStatus = when {
            jerkNormRms > 5.0 -> "vibração alta"
            linearAccNormRms > 2.0 -> "aceleração elevada"
            else -> "sensores estáveis"
        }
        sb.append("Situação IMU: $imuStatus\n")
        
        // Situação de movimento
        val stationary = payload?.motionStationary ?: false
        val speed = payload?.speed ?: 0f
        val accVertical = payload?.accVerticalMps2 ?: 0f
        val movementStatus = when {
            stationary -> "parado"
            abs(accVertical) > 1.5 -> "tranco"
            speed > 1.0 -> "deslocando"
            else -> "movimento leve"
        }
        sb.append("Situação de movimento: $movementStatus\n")
        
        // Situação geral
        val mqttConnected = state.mqttStatus.contains("connected", ignoreCase = true)
        val generalStatus = when {
            mqttConnected && cn0Avg > 20 -> "Telemetria operacional"
            state.isServiceRunning -> "Telemetria degradada (offline)"
            else -> "Telemetria offline"
        }
        sb.append("Situação geral: $generalStatus")
        
        interpretationSummary.text = sb.toString()
    }
}
