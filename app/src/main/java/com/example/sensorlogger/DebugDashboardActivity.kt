package com.example.sensorlogger

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.sensorlogger.model.TelemetryUiState
import com.example.sensorlogger.repository.TelemetryStateStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class DebugDashboardActivity : AppCompatActivity() {

    private lateinit var debugFullContent: TextView
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_dashboard)
        
        debugFullContent = findViewById(R.id.debug_full_content)
        observeTelemetryState()
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
            val content = buildFullDiagnosticContent(state)
            debugFullContent.text = content
        } catch (e: Exception) {
            Timber.e(e, "Error updating debug dashboard")
            debugFullContent.text = "ERRO ao atualizar dashboard: ${e.message}"
        }
    }
    
    private fun buildFullDiagnosticContent(state: TelemetryUiState): String {
        val sb = StringBuilder()
        val p = state.lastPayload
        
        sb.append(buildCard1IdentificationSession(state, p))
        sb.append("\n")
        sb.append(buildCard2PositionMovementGnss(state, p))
        sb.append("\n")
        sb.append(buildCard3GnssQuality(state, p))
        sb.append("\n")
        sb.append(buildCard4VehicleDynamics(state, p))
        sb.append("\n")
        sb.append(buildCard5ImuHealth(state, p))
        sb.append("\n")
        sb.append(buildCard6AccelerationRaw(state, p))
        sb.append("\n")
        sb.append(buildCard7AccelerationLinear(state, p))
        sb.append("\n")
        sb.append(buildCard8Gyro(state, p))
        sb.append("\n")
        sb.append(buildCard9Magnetometer(state, p))
        sb.append("\n")
        sb.append(buildCard10Barometer(state, p))
        sb.append("\n")
        sb.append(buildCard11NetworkUpload(state, p))
        sb.append("\n")
        sb.append(buildCard12GnssRaw(state, p))
        sb.append("\n\n")
        
        // Timestamp de atualização
        val timestamp = dateFormat.format(Date())
        sb.append("────────────────────────────────────────────────────────────\n")
        sb.append("Atualizado: $timestamp")
        
        return sb.toString()
    }
    
    private fun fmt(value: Any?): String {
        return when (value) {
            null -> "null"
            is Float -> if (value.isNaN() || value.isInfinite()) "N/A" else "%.3f".format(value)
            is Double -> if (value.isNaN() || value.isInfinite()) "N/A" else "%.3f".format(value)
            else -> value.toString()
        }
    }
    
    private fun buildCard1IdentificationSession(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val sb = StringBuilder()
        sb.append("🟧 CARD 1 · Identificação e Sessão\n")
        sb.append("────────────────────────────────────────────────────────────\n")
        
        val seqId = p?.sequenceId?.toString() ?: "N/A"
        val deviceId = p?.deviceId ?: "N/A"
        val operatorId = p?.operatorId ?: "N/A"
        val equipmentTag = p?.equipmentTag ?: "null"
        
        sb.append("Número da sequência: $seqId | Dispositivo: $deviceId | Operador: $operatorId 🟢 | Equipamento: $equipmentTag\n")
        
        val schemaVersion = p?.schemaVersion ?: "N/A"
        val tsEpoch = p?.timestampEpoch?.toString() ?: "N/A"
        val gnssProvider = p?.provider ?: "null"
        val gnssFix = p?.gnssFix ?: "null"
        
        sb.append("Versão do schema: $schemaVersion 🟢 | Tempo (epoch ms): $tsEpoch | Provedor GNSS: $gnssProvider | Tipo de fix: $gnssFix\n")
        
        val imuSamples = p?.imuSamples?.toString() ?: "N/A"
        val imuFps = p?.imuFpsEffective?.let { "%.1f".format(it) } ?: "N/A"
        val gnssRealtimeNanos = p?.gnssElapsedRealtimeNanos?.toString() ?: "null"
        
        sb.append("Amostras IMU: $imuSamples | Taxa IMU: $imuFps Hz 🟣 Alta | Tempo GNSS (realtime nanos): $gnssRealtimeNanos\n")
        
        val gnssRawSupported = p?.gnssRawSupported?.toString() ?: "null"
        val totalRaw = p?.gnssRawCount?.toString() ?: "0"
        val gpsUsed = p?.gpsUsed?.toString() ?: "0"
        val galileoUsed = p?.galileoUsed?.toString() ?: "0"
        val glonassUsed = p?.glonassUsed?.toString() ?: "0"
        val beidouUsed = p?.beidouUsed?.toString() ?: "0"
        val qzssUsed = p?.qzssUsed?.toString() ?: "0"
        val sbasUsed = p?.sbasUsed?.toString() ?: "0"
        
        sb.append("GNSS raw suportado: $gnssRawSupported | Total raw: $totalRaw | ")
        sb.append("GPS: $gpsUsed | Galileo: $galileoUsed | GLONASS: $glonassUsed | BeiDou: $beidouUsed | QZSS: $qzssUsed | SBAS: $sbasUsed")
        
        return sb.toString()
    }
    
    private fun buildCard2PositionMovementGnss(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val sb = StringBuilder()
        sb.append("🟩 CARD 2 · Posição e Movimento GNSS\n")
        sb.append("────────────────────────────────────────────────────────────\n")
        
        val lat = p?.latitude?.let { "%.6f".format(it) } ?: "null"
        val lon = p?.longitude?.let { "%.6f".format(it) } ?: "null"
        val altGnss = p?.altitude?.let { "%.1f".format(it) } ?: "null"
        val altBaro = p?.baroAltitudeMeters?.let { "%.1f".format(it) } ?: "null"
        
        sb.append("Latitude: $lat | Longitude: $lon | Altitude GNSS: $altGnss m | Altitude barométrica: $altBaro\n")
        
        val course = p?.course?.let { "%.1f".format(it) } ?: "null"
        val speedMs = p?.speed ?: 0f
        val speedKmh = speedMs * 3.6f
        val hasL5 = p?.hasL5?.toString() ?: "null"
        val motionEmoji = if (speedKmh < 0.5f) "🟦 Parado" else ""
        
        sb.append("Rumo (heading): $course° | Velocidade: %.2f m/s (%.1f km/h) $motionEmoji | Possui L5: $hasL5 ⚪\n".format(speedMs, speedKmh))
        
        val accH = p?.accuracyMeters?.let { "%.1f".format(it) } ?: "null"
        val accV = p?.verticalAccuracyMeters?.let { "%.1f".format(it) } ?: "null"
        val accSpeed = p?.speedAccuracyMps?.let { "%.2f".format(it) } ?: "null"
        
        sb.append("Precisão horizontal: $accH m | Precisão vertical: $accV m | Precisão da velocidade: $accSpeed m/s\n")
        
        val hdop = p?.hdop?.let { "%.2f".format(it) } ?: "null"
        val pdop = p?.pdop?.let { "%.2f".format(it) } ?: "null"
        val vdop = p?.vdop?.let { "%.2f".format(it) } ?: "null"
        
        sb.append("HDOP: $hdop | PDOP: $pdop | VDOP: $vdop\n")
        
        val satsUsed = p?.satellitesUsed?.toString() ?: "null"
        val satsVisible = p?.satellitesVisible?.toString() ?: "null"
        
        sb.append("Satélites usados: $satsUsed | Satélites visíveis: $satsVisible\n")
        
        val gpsU = p?.gpsUsed?.toString() ?: "0"
        val galileoU = p?.galileoUsed?.toString() ?: "0"
        val glonassU = p?.glonassUsed?.toString() ?: "0"
        val beidouU = p?.beidouUsed?.toString() ?: "0"
        
        sb.append("Constelações ativas: GPS $gpsU | Galileo $galileoU | GLONASS $glonassU | BeiDou $beidouU 🟢\n")
        
        val rollDeg = p?.vehicleTiltRollDeg?.let { "%.2f".format(it) } ?: "null"
        val pitchDeg = p?.vehicleTiltPitchDeg?.let { "%.2f".format(it) } ?: "null"
        val yawDeg = p?.yawDeg?.let { "%.2f".format(it) } ?: "null"
        val yawRate = p?.yawRateDegPerSec?.let { "%.2f".format(it) } ?: "null"
        
        sb.append("Inclinação roll: $rollDeg° | Inclinação pitch: $pitchDeg° | Guinada (yaw): $yawDeg° | Variação de guinada (yaw rate): $yawRate °/s 🔵 estável")
        
        return sb.toString()
    }
    
    private fun buildCard3GnssQuality(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val sb = StringBuilder()
        sb.append("🟦 CARD 3 · Qualidade do Sinal GNSS / Doppler / Clock\n")
        sb.append("────────────────────────────────────────────────────────────\n")
        
        val hdop = p?.hdop ?: Float.MAX_VALUE
        val pdop = p?.pdop ?: Float.MAX_VALUE
        val vdop = p?.vdop ?: Float.MAX_VALUE
        val hdopStr = p?.hdop?.let { "%.2f".format(it) } ?: "null"
        val pdopStr = p?.pdop?.let { "%.2f".format(it) } ?: "null"
        val vdopStr = p?.vdop?.let { "%.2f".format(it) } ?: "null"
        val hdopEmoji = if (hdop < 1.5f) "🟢" else "🟡"
        val pdopEmoji = if (pdop < 2.0f) "🟢" else "🟡"
        val vdopEmoji = if (vdop > 3.0f) "🟡 altitude incerta" else ""
        
        val multiGnss = (if ((p?.gpsUsed ?: 0) > 0) 1 else 0) + 
                        (if ((p?.galileoUsed ?: 0) > 0) 1 else 0) + 
                        (if ((p?.glonassUsed ?: 0) > 0) 1 else 0) + 
                        (if ((p?.beidouUsed ?: 0) > 0) 1 else 0)
        val multiGnssEmoji = if (multiGnss > 1) "🟢" else "🟡"
        
        sb.append("HDOP $hdopStr $hdopEmoji | PDOP $pdopStr $pdopEmoji | VDOP $vdopStr $vdopEmoji | Multi-GNSS: usar >1 constelação? $multiGnssEmoji\n")
        
        sb.append("C/N0 dB-Hz:\n")
        val cn0Min = p?.cn0Min?.let { "%.1f".format(it) } ?: "null"
        val cn0P25 = p?.cn0Percentile25?.let { "%.1f".format(it) } ?: "null"
        val cn0P50 = p?.cn0Median?.let { "%.1f".format(it) } ?: "null"
        val cn0P75 = p?.cn0Percentile75?.let { "%.1f".format(it) } ?: "null"
        val cn0Max = p?.cn0Max?.let { "%.1f".format(it) } ?: "null"
        val cn0Avg = p?.cn0Average?.let { "%.1f".format(it) } ?: "null"
        val cn0Sigma = p?.gnssRaw?.cn0Sigma?.let { "%.2f".format(it) } ?: "null"
        
        sb.append("  mín $cn0Min | p25 $cn0P25 | p50 $cn0P50 | p75 $cn0P75 | máx $cn0Max | média $cn0Avg | σ $cn0Sigma\n")
        
        sb.append("Doppler:\n")
        val dopplerSats = p?.gnssRaw?.dopplerSatCount?.toString() ?: "null"
        val dopplerSpeed = p?.gnssRaw?.dopplerSpeedMps?.let { "%.2f".format(it) } ?: "null"
        val dopplerSigma = p?.gnssRaw?.dopplerSpeedSigma?.let { "%.3f".format(it) } ?: "null"
        sb.append("  sats $dopplerSats | vel_doppler $dopplerSpeed m/s | sigma $dopplerSigma 🟡 baixa confiança se sigma alto\n")
        
        sb.append("Tempo:\n")
        val satUpdateAge = p?.gnssRaw?.satUpdateAgeMs?.toString() ?: "null"
        val ttff = p?.gnssRaw?.timeToFirstFixMs?.let { "%.0f".format(it) } ?: "null"
        sb.append("  última atualização sat: $satUpdateAge ms | ttff (time to first fix): $ttff ms\n")
        
        sb.append("Clock:\n")
        val clockBias = p?.gnssRaw?.clockBiasNanos?.toString() ?: "null"
        val clockDrift = p?.gnssRaw?.clockDriftNanosPerSecond?.let { "%.2f".format(it) } ?: "null"
        sb.append("  bias $clockBias ns | drift $clockDrift ns/s\n")
        
        val bearingAcc = p?.bearingAccuracyDeg?.let { "%.2f".format(it) } ?: "null"
        sb.append("Precisão bearing: $bearingAcc deg\n")
        
        sb.append("Resumo:\n")
        sb.append("  🟢 Fix bom / posição confiável\n")
        sb.append("  🟡 Altitude incerta se VDOP>3\n")
        sb.append("  🟡 CN0 médio se média<30\n")
        sb.append("  🟡 Doppler baixa confiança se sigma alto")
        
        return sb.toString()
    }
    
    private fun buildCard4VehicleDynamics(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val sb = StringBuilder()
        sb.append("🟨 CARD 4 · Dinâmica do Veículo\n")
        sb.append("────────────────────────────────────────────────────────────\n")
        
        val roll = p?.vehicleTiltRollDeg ?: 0f
        val rollStr = p?.vehicleTiltRollDeg?.let { "%.2f".format(it) } ?: "null"
        val pitchStr = p?.vehicleTiltPitchDeg?.let { "%.2f".format(it) } ?: "null"
        val rollEmoji = if (abs(roll) < 5f) "🟢 nivelado" else ""
        
        sb.append("Inclinação lateral (roll): $rollStr° | Inclinação frontal (pitch): $pitchStr° $rollEmoji\n")
        
        val yawStr = p?.yawDeg?.let { "%.2f".format(it) } ?: "null"
        sb.append("Guinada (yaw): $yawStr°\n")
        
        val accLong = p?.accLongitudinalMps2?.let { "%.3f".format(it) } ?: "null"
        val accLat = p?.accLateralMps2?.let { "%.3f".format(it) } ?: "null"
        val accVert = p?.accVerticalMps2?.let { "%.3f".format(it) } ?: "null"
        
        sb.append("Aceleração longitudinal: $accLong m/s² | Lateral: $accLat m/s² | Vertical: $accVert m/s²\n")
        
        val jerkRms = p?.jerkNormRms?.let { "%.3f".format(it) } ?: "null"
        val jerkSigma = p?.jerkNormSigma?.let { "%.3f".format(it) } ?: "null"
        
        sb.append("Jerk RMS (derivada da aceleração): $jerkRms m/s³ | Desvio Jerk σ: $jerkSigma m/s³\n")
        
        val shockLevel = p?.motionShockLevel ?: "null"
        val shockScore = p?.motionShockScore?.let { "%.2f".format(it) } ?: "null"
        
        sb.append("Nível de choque: $shockLevel 🟡 | Pontuação de choque: $shockScore\n")
        
        val stationary = p?.motionStationary ?: false
        val motionState = if (stationary) "parado" else "em movimento / vibração"
        
        sb.append("Estado de movimento: $motionState")
        
        return sb.toString()
    }
    
    private fun buildCard5ImuHealth(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val sb = StringBuilder()
        sb.append("🟫 CARD 5 · Saúde do IMU / Amostragem\n")
        sb.append("────────────────────────────────────────────────────────────\n")
        
        val fps = p?.imuFpsEffective ?: 0f
        val fpsStr = p?.imuFpsEffective?.let { "%.1f".format(it) } ?: "null"
        val fpsEmoji = if (fps > 100f) "🟣 alta" else ""
        val samples = p?.imuSamples?.toString() ?: "null"
        
        sb.append("Taxa efetiva: $fpsStr Hz $fpsEmoji | Amostras: $samples\n")
        
        val accAcc = p?.accelerometerAccuracy ?: "null"
        val gyroAcc = p?.gyroscopeAccuracy ?: "null"
        val rotAcc = p?.rotationAccuracy ?: "null"
        
        sb.append("Acurácia aceleração: $accAcc 🟢 | Acurácia giroscópio: $gyroAcc 🟢 | Acurácia rotação: $rotAcc 🟢\n")
        
        val yawRate = p?.yawRateDegPerSec?.let { "%.2f".format(it) } ?: "null"
        sb.append("Yaw rate: $yawRate °/s 🔵 estável se ~0\n")
        
        val shockLevel = p?.motionShockLevel ?: "null"
        sb.append("Nível de choque atual: $shockLevel 🟡\n")
        
        sb.append("Conclusão geral: IMU estável, sensores de alta qualidade.")
        
        return sb.toString()
    }
    
    private fun buildCard6AccelerationRaw(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val sb = StringBuilder()
        sb.append("🟧 CARD 6 · Aceleração Bruta (com gravidade)\n")
        sb.append("────────────────────────────────────────────────────────────\n")
        
        sb.append("Aceleração X: mean ${fmt(p?.accXMean)} m/s² | RMS ${fmt(p?.accXRms)} | max ${fmt(p?.accXMax)} | min ${fmt(p?.accXMin)} | σ ${fmt(p?.accXSigma)}\n")
        sb.append("Aceleração Y: mean ${fmt(p?.accYMean)} m/s² | RMS ${fmt(p?.accYRms)} | max ${fmt(p?.accYMax)} | min ${fmt(p?.accYMin)} | σ ${fmt(p?.accYSigma)}\n")
        sb.append("Aceleração Z: mean ${fmt(p?.accZMean)} m/s² | RMS ${fmt(p?.accZRms)} | max ${fmt(p?.accZMax)} | min ${fmt(p?.accZMin)} | σ ${fmt(p?.accZSigma)}\n")
        
        val normRms = p?.accNormRms ?: 0f
        val normEmoji = if (normRms in 9.81f..9.90f) "🟢" else ""
        sb.append("Norma |acc|: RMS ${fmt(p?.accNormRms)} m/s² | σ ${fmt(p?.accNormSigma)} → ideal ≈9.81–9.90 m/s² parado $normEmoji")
        
        return sb.toString()
    }
    
    private fun buildCard7AccelerationLinear(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val sb = StringBuilder()
        sb.append("🟩 CARD 7 · Aceleração Linear (sem gravidade)\n")
        sb.append("────────────────────────────────────────────────────────────\n")
        
        sb.append("Linear X: mean ${fmt(p?.linearAccXMean)} m/s² | RMS ${fmt(p?.linearAccXRms)} | max ${fmt(p?.linearAccXMax)} | min ${fmt(p?.linearAccXMin)} | σ ${fmt(p?.linearAccXSigma)}\n")
        sb.append("Linear Y: mean ${fmt(p?.linearAccYMean)} m/s² | RMS ${fmt(p?.linearAccYRms)} | max ${fmt(p?.linearAccYMax)} | min ${fmt(p?.linearAccYMin)} | σ ${fmt(p?.linearAccYSigma)}\n")
        sb.append("Linear Z: mean ${fmt(p?.linearAccZMean)} m/s² | RMS ${fmt(p?.linearAccZRms)} | max ${fmt(p?.linearAccZMax)} | min ${fmt(p?.linearAccZMin)} | σ ${fmt(p?.linearAccZSigma)}\n")
        
        val normRms = p?.linearAccNormRms ?: 0f
        val normEmoji = if (normRms < 0.5f) "🟢" else ""
        sb.append("Norma |linear_acc|: RMS ${fmt(p?.linearAccNormRms)} m/s² | σ ${fmt(p?.linearAccNormSigma)} → vibração do veículo $normEmoji")
        
        return sb.toString()
    }
    
    private fun buildCard8Gyro(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val sb = StringBuilder()
        sb.append("🟦 CARD 8 · Giro / Movimento Rotacional\n")
        sb.append("────────────────────────────────────────────────────────────\n")
        
        sb.append("Giro X: mean ${fmt(p?.gyroXMean)} rad/s | RMS ${fmt(p?.gyroXRms)} | max ${fmt(p?.gyroXMax)} | min ${fmt(p?.gyroXMin)} | σ ${fmt(p?.gyroXSigma)}\n")
        sb.append("Giro Y: mean ${fmt(p?.gyroYMean)} rad/s | RMS ${fmt(p?.gyroYRms)} | max ${fmt(p?.gyroYMax)} | min ${fmt(p?.gyroYMin)} | σ ${fmt(p?.gyroYSigma)}\n")
        sb.append("Giro Z: mean ${fmt(p?.gyroZMean)} rad/s | RMS ${fmt(p?.gyroZRms)} | max ${fmt(p?.gyroZMax)} | min ${fmt(p?.gyroZMin)} | σ ${fmt(p?.gyroZSigma)}\n")
        
        sb.append("Norma |gyro|: RMS ${fmt(p?.gyroNormRms)} rad/s | σ ${fmt(p?.gyroNormSigma)} → baixa rotação = estável 🔵")
        
        return sb.toString()
    }
    
    private fun buildCard9Magnetometer(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val sb = StringBuilder()
        sb.append("🟪 CARD 9 · Campo Magnético e Orientação\n")
        sb.append("────────────────────────────────────────────────────────────\n")
        
        sb.append("Mag X: mean ${fmt(p?.magnetometerXMean)} µT | RMS ${fmt(p?.magnetometerXRms)} | max ${fmt(p?.magnetometerXMax)} | min ${fmt(p?.magnetometerXMin)} | σ ${fmt(p?.magnetometerXSigma)}\n")
        sb.append("Mag Y: mean ${fmt(p?.magnetometerYMean)} µT | RMS ${fmt(p?.magnetometerYRms)} | max ${fmt(p?.magnetometerYMax)} | min ${fmt(p?.magnetometerYMin)} | σ ${fmt(p?.magnetometerYSigma)}\n")
        sb.append("Mag Z: mean ${fmt(p?.magnetometerZMean)} µT | RMS ${fmt(p?.magnetometerZRms)} | max ${fmt(p?.magnetometerZMax)} | min ${fmt(p?.magnetometerZMin)} | σ ${fmt(p?.magnetometerZSigma)}\n")
        
        val fieldStrength = p?.magnetometerFieldStrength ?: 0f
        val fieldEmoji = if (fieldStrength in 25f..65f) "🟢" else ""
        sb.append("Campo total: ${fmt(p?.magnetometerFieldStrength)} µT $fieldEmoji faixa terrestre normal\n")
        
        sb.append("Orientação: yaw ${fmt(p?.yawDeg)}° | pitch ${fmt(p?.pitchDeg)}° | roll ${fmt(p?.rollDeg)}°")
        
        return sb.toString()
    }
    
    private fun buildCard10Barometer(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val sb = StringBuilder()
        sb.append("🟫 CARD 10 · Barômetro / Pressão Atmosférica\n")
        sb.append("────────────────────────────────────────────────────────────\n")
        
        val pressure = p?.baroPressureHpa?.let { "%.1f".format(it) } ?: "null"
        val altitude = p?.baroAltitudeMeters?.let { "%.1f".format(it) } ?: "null"
        
        sb.append("Pressão barométrica: $pressure hPa | Altitude barométrica: $altitude m\n")
        
        val hasData = (p?.baroPressureHpa != null || p?.baroAltitudeMeters != null)
        if (!hasData) {
            sb.append("→ se ambos null: \"⚪ sem barômetro no hardware\"")
        }
        
        return sb.toString()
    }
    
    private fun buildCard12GnssRaw(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val sb = StringBuilder()
        sb.append("🟦 CARD 12 · GNSS Raw – Satélites e Medições Avançadas\n")
        sb.append("────────────────────────────────────────────────────────────\n")
        
        val raw = p?.gnssRaw
        
        val tsNanos = raw?.timestampNanos?.toString() ?: "null"
        val clockBias = raw?.clockBiasNanos?.toString() ?: "null"
        val clockDrift = raw?.clockDriftNanosPerSecond?.let { "%.2f".format(it) } ?: "null"
        
        sb.append("Tempo GNSS (timestamp_nanos): $tsNanos | Bias clock: $clockBias ns | Drift: $clockDrift ns/s\n")
        
        val dopplerSats = raw?.dopplerSatCount?.toString() ?: "null"
        sb.append("Satélites Doppler: $dopplerSats\n")
        
        val dopplerSpeed = raw?.dopplerSpeedMps?.let { "%.2f".format(it) } ?: "null"
        val dopplerSigma = raw?.dopplerSpeedSigma?.let { "%.3f".format(it) } ?: "null"
        sb.append("Velocidade Doppler agregada: $dopplerSpeed m/s | Sigma: $dopplerSigma (quanto menor melhor)\n")
        
        val satUpdateAge = raw?.satUpdateAgeMs?.toString() ?: "null"
        val ttff = raw?.timeToFirstFixMs?.let { "%.0f".format(it) } ?: "null"
        sb.append("Última atualização de sat: $satUpdateAge ms | Tempo até primeiro fix: $ttff ms\n")
        
        val agcAvg = raw?.agcDbAvg?.let { "%.2f".format(it) } ?: "null"
        val cn0Avg = raw?.cn0Avg?.let { "%.2f".format(it) } ?: "null"
        val cn0Sigma = raw?.cn0Sigma?.let { "%.2f".format(it) } ?: "null"
        sb.append("AGC médio: $agcAvg dB | CN0 médio: $cn0Avg dB-Hz | CN0 σ: $cn0Sigma\n")
        
        sb.append("Listar TODAS as medições individuais atuais (não limitar a 10):\n")
        
        if (raw?.measurements?.isNotEmpty() == true) {
            raw.measurements.forEach { m ->
                val svid = m.svid
                val const = getConstellationName(m.constellationType)
                val cn0 = m.cn0DbHz?.let { "%.1f".format(it) } ?: "N/A"
                val freq = m.carrierFrequencyHz?.let { "%.3f".format(it / 1e6) } ?: "N/A"
                val doppler = m.pseudorangeRateMetersPerSecond?.let { "%.2f".format(it) } ?: "N/A"
                val agc = m.agcDb?.let { "%.2f".format(it) } ?: "N/A"
                
                sb.append("  $svid ($const) → CN0 $cn0 dB-Hz | Freq $freq MHz | Doppler $doppler m/s | AGC $agc dB\n")
            }
        } else {
            sb.append("  (nenhuma medição raw disponível)\n")
        }
        
        return sb.toString()
    }
    
    private fun buildCard11NetworkUpload(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): String {
        val sb = StringBuilder()
        sb.append("🟩 CARD 11 · Network / Upload / Serviço\n")
        sb.append("────────────────────────────────────────────────────────────\n")
        
        sb.append("mqttStatus: ${state.mqttStatus} | serviceRunning: ${state.isServiceRunning}\n")
        sb.append("offlineQueueCount: ${state.queueSize} | offlineQueueSizeMB: N/A\n")
        sb.append("brokerEndpoints: ${state.brokerActiveEndpoint ?: "N/A"} (string atual)\n")
        
        sb.append("Permissões:\n")
        sb.append("  background location: ${if (state.permissionsGranted) "OK" else "FALTA"}\n")
        sb.append("  battery optimization: N/A\n")
        sb.append("  notifications: N/A\n")
        
        sb.append("Conclusão envio:\n")
        val deliveryStatus = if (state.mqttStatus == "Connected" && state.queueSize == 0) {
            "🟢 ENVIANDO EM TEMPO REAL"
        } else {
            "🟡 BUFFER/RETRY"
        }
        sb.append("  Delivery: $deliveryStatus\n")
        sb.append("  Operator sync: ${state.operatorName.ifEmpty { "N/A" }}")
        
        return sb.toString()
    }
    
    private fun getConstellationName(type: Int): String {
        return when (type) {
            1 -> "GPS"
            2 -> "SBAS"
            3 -> "GLONASS"
            4 -> "QZSS"
            5 -> "BEIDOU"
            6 -> "GALILEO"
            else -> "UNKNOWN"
        }
    }
}
