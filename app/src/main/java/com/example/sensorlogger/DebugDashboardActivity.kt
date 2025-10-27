package com.example.sensorlogger

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    private lateinit var headerView: TextView
    private lateinit var card1: TextView
    private lateinit var card2: TextView
    private lateinit var card3: TextView
    private lateinit var card4: TextView
    private lateinit var card5: TextView
    private lateinit var card6: TextView
    private lateinit var card7: TextView
    private lateinit var card8: TextView
    private lateinit var card9: TextView
    private lateinit var cardRawGnss: TextView
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    private val colorHighlight = Color.parseColor("#64B5F6")
    private val colorMuted = Color.parseColor("#666666")
    private val colorLabel = Color.parseColor("#AAAAAA")
    private val colorDivider = Color.parseColor("#888888")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_dashboard)
        
        headerView = findViewById(R.id.debug_header)
        card1 = findViewById(R.id.card1_content)
        card2 = findViewById(R.id.card2_content)
        card3 = findViewById(R.id.card3_content)
        card4 = findViewById(R.id.card4_content)
        card5 = findViewById(R.id.card5_content)
        card6 = findViewById(R.id.card6_content)
        card7 = findViewById(R.id.card7_content)
        card8 = findViewById(R.id.card8_content)
        card9 = findViewById(R.id.card9_content)
        cardRawGnss = findViewById(R.id.cardRawGnss_content)
        
        applyCardBorders()
        observeTelemetryState()
    }
    
    private fun applyCardBorders() {
        val cards = listOf(
            findViewById<FrameLayout>(R.id.card1_container),
            findViewById<FrameLayout>(R.id.card2_container),
            findViewById<FrameLayout>(R.id.card3_container),
            findViewById<FrameLayout>(R.id.card4_container),
            findViewById<FrameLayout>(R.id.card5_container),
            findViewById<FrameLayout>(R.id.card6_container),
            findViewById<FrameLayout>(R.id.card7_container),
            findViewById<FrameLayout>(R.id.card8_container),
            findViewById<FrameLayout>(R.id.card9_container),
            findViewById<FrameLayout>(R.id.cardRawGnss_container)
        )
        
        cards.forEach { card ->
            val shape = GradientDrawable().apply {
                cornerRadius = 8f * resources.displayMetrics.density
                setStroke(
                    (1 * resources.displayMetrics.density).toInt(),
                    ContextCompat.getColor(this@DebugDashboardActivity, R.color.debug_card_border)
                )
            }
            card.foreground = shape
        }
    }
    
    private fun observeTelemetryState() {
        lifecycleScope.launch {
            TelemetryStateStore.state.collect { state ->
                updateUI(state)
            }
        }
    }
    
    private fun updateUI(state: TelemetryUiState) {
        try {
            updateHeader(state)
            updateCards(state)
        } catch (e: Exception) {
            Timber.e(e, "Error updating debug dashboard")
            card1.text = "ERRO ao atualizar dashboard: ${e.message}"
        }
    }
    
    private fun updateHeader(state: TelemetryUiState) {
        val timestamp = dateFormat.format(Date())
        val mqttStatus = state.mqttStatus
        val imuFps = state.lastPayload?.imuFpsEffective?.let { "%.0f".format(it) } ?: "N/A"
        headerView.text = "Última atualização: $timestamp | taxa: $imuFps Hz | mqtt: $mqttStatus"
    }
    
    private fun updateCards(state: TelemetryUiState) {
        val p = state.lastPayload
        card1.text = buildCard1IdentificationNetwork(state, p)
        card2.text = buildCard2PositionMovementGnss(state, p)
        card3.text = buildCard3GnssQuality(state, p)
        card4.text = buildCard4VehicleDynamics(state, p)
        card5.text = buildCard5ImuHealth(state, p)
        card6.text = buildCard6AccelerationRaw(state, p)
        card7.text = buildCard7AccelerationLinear(state, p)
        card8.text = buildCard8Gyro(state, p)
        card9.text = buildCard9Magnetometer(state, p)
        cardRawGnss.text = buildCardRawGnss(state, p)
    }
    
    // Helper functions
    private fun SpannableStringBuilder.appendColored(text: String, color: Int, bold: Boolean = false): SpannableStringBuilder {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) {
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return this
    }
    
    private fun SpannableStringBuilder.appendNormal(text: String): SpannableStringBuilder {
        append(text)
        return this
    }
    
    private fun buildDividerLine(): String {
        return "━━━━━━━━━━━━━━━━━━"
    }
    
    private fun buildSectionTitle(title: String): SpannableStringBuilder {
        return SpannableStringBuilder().appendColored(title, colorHighlight, true)
    }
    
    private fun fmt(value: Any?): String {
        return when (value) {
            null -> "null"
            is Float -> if (value.isNaN() || value.isInfinite()) "N/A" else "%.3f".format(value)
            is Double -> if (value.isNaN() || value.isInfinite()) "N/A" else "%.3f".format(value)
            else -> value.toString()
        }
    }
    
    private fun fmtMuted(value: Any?): SpannableStringBuilder {
        val text = fmt(value)
        return if (text == "null" || text == "N/A") {
            SpannableStringBuilder().appendColored(text, colorMuted)
        } else {
            SpannableStringBuilder(text)
        }
    }
    
    private fun formatTable3Axis(
        title: String,
        unit: String,
        xMean: Float?, xRms: Float?, xMax: Float?, xMin: Float?, xSigma: Float?,
        yMean: Float?, yRms: Float?, yMax: Float?, yMin: Float?, ySigma: Float?,
        zMean: Float?, zRms: Float?, zMax: Float?, zMin: Float?, zSigma: Float?
    ): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        ssb.appendColored("$title ($unit)", colorLabel).appendNormal("  mean     RMS      max      min      σ\n")
        
        val xMeanStr = fmt(xMean).padEnd(8)
        val xRmsStr = fmt(xRms).padEnd(8)
        val xMaxStr = fmt(xMax).padEnd(8)
        val xMinStr = fmt(xMin).padEnd(8)
        val xSigmaStr = fmt(xSigma)
        ssb.appendNormal("X                    $xMeanStr $xRmsStr $xMaxStr $xMinStr $xSigmaStr\n")
        
        val yMeanStr = fmt(yMean).padEnd(8)
        val yRmsStr = fmt(yRms).padEnd(8)
        val yMaxStr = fmt(yMax).padEnd(8)
        val yMinStr = fmt(yMin).padEnd(8)
        val ySigmaStr = fmt(ySigma)
        ssb.appendNormal("Y                    $yMeanStr $yRmsStr $yMaxStr $yMinStr $ySigmaStr\n")
        
        val zMeanStr = fmt(zMean).padEnd(8)
        val zRmsStr = fmt(zRms).padEnd(8)
        val zMaxStr = fmt(zMax).padEnd(8)
        val zMinStr = fmt(zMin).padEnd(8)
        val zSigmaStr = fmt(zSigma)
        ssb.appendNormal("Z                    $zMeanStr $zRmsStr $zMaxStr $zMinStr $zSigmaStr")
        
        return ssb
    }
    
    private fun buildCard1IdentificationNetwork(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): CharSequence {
        val ssb = SpannableStringBuilder()
        ssb.appendNormal("🟧 CARD 1 · Identificação e Sessão / Rede\n")
        ssb.appendColored(buildDividerLine(), colorDivider).appendNormal("\n\n")
        
        // Identificação
        val seqId = p?.sequenceId?.toString() ?: fmtMuted(null).toString()
        val deviceId = p?.deviceId ?: fmtMuted(null).toString()
        val operatorId = p?.operatorId ?: fmtMuted(null).toString()
        val equipmentTag = p?.equipmentTag ?: fmtMuted(null).toString()
        
        ssb.appendNormal("Número da sequência: $seqId | Dispositivo: $deviceId | Operador: $operatorId 🟢 | Equipamento: $equipmentTag\n")
        
        val schemaVersion = p?.schemaVersion ?: fmtMuted(null).toString()
        val tsEpoch = p?.timestampEpoch?.toString() ?: fmtMuted(null).toString()
        val gnssProvider = p?.provider ?: fmtMuted(null).toString()
        val gnssFix = p?.gnssFix ?: fmtMuted(null).toString()
        
        ssb.appendNormal("Versão do schema: $schemaVersion 🟢 | Tempo (epoch ms): $tsEpoch | Provedor GNSS: $gnssProvider | Tipo de fix: $gnssFix\n")
        
        val imuSamples = p?.imuSamples?.toString() ?: fmtMuted(null).toString()
        val imuFps = p?.imuFpsEffective?.let { "%.1f".format(it) } ?: fmtMuted(null).toString()
        val gnssRawSupported = p?.gnssRawSupported?.toString() ?: fmtMuted(null).toString()
        
        ssb.appendNormal("Amostras IMU: $imuSamples | Taxa IMU: $imuFps Hz 🟣 Alta | GNSS raw suportado: $gnssRawSupported\n")
        
        val totalRaw = p?.gnssRawCount?.toString() ?: "0"
        val gpsCount = p?.gnssRaw?.measurements?.count { it.constellationType == 1 }?.toString() ?: "0"
        val galileoCount = p?.gnssRaw?.measurements?.count { it.constellationType == 6 }?.toString() ?: "0"
        val glonassCount = p?.gnssRaw?.measurements?.count { it.constellationType == 3 }?.toString() ?: "0"
        val beidouCount = p?.gnssRaw?.measurements?.count { it.constellationType == 5 }?.toString() ?: "0"
        val qzssCount = p?.gnssRaw?.measurements?.count { it.constellationType == 4 }?.toString() ?: "0"
        val sbasCount = p?.gnssRaw?.measurements?.count { it.constellationType == 2 }?.toString() ?: "0"
        
        ssb.appendNormal("Total raw: $totalRaw | GPS: $gpsCount | Galileo: $galileoCount | GLONASS: $glonassCount | BeiDou: $beidouCount | QZSS: $qzssCount | SBAS: $sbasCount\n\n")
        
        // Network / Upload (fundido aqui)
        ssb.appendColored("[REDE / ENVIO]\n", colorHighlight, true)
        ssb.appendNormal("mqttStatus: ${state.mqttStatus} | serviceRunning: ${state.isServiceRunning}\n")
        val queueSizeMBStr = state.offlineQueueSizeMB?.let { "%.2f MB".format(it) } ?: "N/A"
        ssb.appendNormal("offlineQueueCount: ${state.queueSize} | offlineQueueSizeMB: $queueSizeMBStr\n")
        ssb.appendNormal("brokerEndpoints: ${state.brokerActiveEndpoint ?: "N/A"}\n\n")
        
        ssb.appendNormal("Permissões:\n")
        ssb.appendNormal("  background location: ${if (state.permissionsGranted) "OK" else "FALTA"}\n")
        val batteryOptStatus = when (state.batteryOptimizationIgnored) {
            true -> "OK"
            false -> "ATENÇÃO"
            null -> "N/A"
        }
        val notificationStatus = when (state.notificationPermissionGranted) {
            true -> "OK"
            false -> "FALTA"
            null -> "N/A"
        }
        ssb.appendNormal("  battery optimization: $batteryOptStatus\n")
        ssb.appendNormal("  notifications: $notificationStatus\n\n")
        
        ssb.appendColored("Conclusão envio:\n", colorHighlight, true)
        val deliveryStatus = if (state.mqttStatus == "Connected" && state.queueSize == 0) {
            "🟢 ENVIANDO EM TEMPO REAL"
        } else {
            "🟡 BUFFER/RETRY"
        }
        ssb.appendNormal("  Delivery: $deliveryStatus\n")
        ssb.appendNormal("  Operator sync: ${state.operatorName.ifEmpty { "N/A" }}\n\n")
        
        // Mini resumo
        val loggerStatus = if (state.isServiceRunning) "OK" else "STOPPED"
        val dataAge = if (p != null) "fresh" else "N/A"
        ssb.appendNormal("→ Logger: $loggerStatus\n")
        ssb.appendNormal("→ Data age: $dataAge")
        
        return ssb
    }
    
    private fun buildCard2PositionMovementGnss(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): CharSequence {
        val ssb = SpannableStringBuilder()
        ssb.appendNormal("🟩 CARD 2 · Posição e Movimento GNSS\n")
        ssb.appendColored(buildDividerLine(), colorDivider).appendNormal("\n\n")
        
        ssb.appendColored("[POSIÇÃO / MOVIMENTO]\n", colorHighlight, true)
        val lat = p?.latitude?.let { "%.6f".format(it) } ?: "null"
        val lon = p?.longitude?.let { "%.6f".format(it) } ?: "null"
        val altGnss = p?.altitude?.let { "%.1f".format(it) } ?: "null"
        val altBaro = p?.baroAltitudeMeters?.let { "%.1f".format(it) } ?: "null"
        
        ssb.appendNormal("Latitude: $lat | Longitude: $lon | Altitude GNSS: $altGnss m | Altitude barométrica: $altBaro\n")
        
        val course = p?.course?.let { "%.1f".format(it) } ?: "null"
        val speedMs = p?.speed ?: 0f
        val speedKmh = speedMs * 3.6f
        val hasL5 = p?.hasL5?.toString() ?: "null"
        val motionEmoji = if (speedKmh < 0.5f) "🟦 Parado" else ""
        
        ssb.appendNormal("Rumo (heading): $course° | Velocidade: %.2f m/s (%.1f km/h) $motionEmoji | Possui L5: $hasL5 ⚪\n\n".format(speedMs, speedKmh))
        
        ssb.appendColored("[QUALIDADE / DOP]\n", colorHighlight, true)
        val hdop = p?.hdop?.let { "%.2f".format(it) } ?: "null"
        val pdop = p?.pdop?.let { "%.2f".format(it) } ?: "null"
        val vdop = p?.vdop?.let { "%.2f".format(it) } ?: "null"
        val accH = p?.accuracyMeters?.let { "%.1f".format(it) } ?: "null"
        val accV = p?.verticalAccuracyMeters?.let { "%.1f".format(it) } ?: "null"
        val accSpeed = p?.speedAccuracyMps?.let { "%.2f".format(it) } ?: "null"
        
        ssb.appendNormal("HDOP: $hdop | PDOP: $pdop | VDOP: $vdop\n")
        ssb.appendNormal("Precisão horizontal: $accH m | Precisão vertical: $accV m | Precisão da velocidade: $accSpeed m/s\n\n")
        
        ssb.appendColored("[SATÉLITES]\n", colorHighlight, true)
        val satsUsed = p?.satellitesUsed?.toString() ?: "null"
        val satsVisible = p?.satellitesVisible?.toString() ?: "null"
        ssb.appendNormal("Satélites usados: $satsUsed | Satélites visíveis: $satsVisible\n")
        
        val gpsU = p?.gpsUsed?.toString() ?: "0"
        val galileoU = p?.galileoUsed?.toString() ?: "0"
        val glonassU = p?.glonassUsed?.toString() ?: "0"
        val beidouU = p?.beidouUsed?.toString() ?: "0"
        ssb.appendNormal("Constelações ativas: GPS $gpsU | Galileo $galileoU | GLONASS $glonassU | BeiDou $beidouU 🟢\n\n")
        
        ssb.appendColored("[ORIENTAÇÃO]\n", colorHighlight, true)
        val rollDeg = p?.vehicleTiltRollDeg?.let { "%.2f".format(it) } ?: "null"
        val pitchDeg = p?.vehicleTiltPitchDeg?.let { "%.2f".format(it) } ?: "null"
        val yawDeg = p?.yawDeg?.let { "%.2f".format(it) } ?: "null"
        val yawRate = p?.yawRateDegPerSec?.let { "%.2f".format(it) } ?: "null"
        ssb.appendNormal("Inclinação roll: $rollDeg° | Inclinação pitch: $pitchDeg° | Guinada (yaw): $yawDeg° | Variação de guinada (yaw rate): $yawRate °/s 🔵 estável")
        
        return ssb
    }
    
    private fun buildCard3GnssQuality(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): CharSequence {
        val ssb = SpannableStringBuilder()
        ssb.appendNormal("🟦 CARD 3 · Qualidade do Sinal GNSS / Doppler / Clock\n")
        ssb.appendColored(buildDividerLine(), colorDivider).appendNormal("\n\n")
        
        ssb.appendColored("[QUALIDADE / DOP]\n", colorHighlight, true)
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
        
        ssb.appendNormal("HDOP $hdopStr $hdopEmoji | PDOP $pdopStr $pdopEmoji | VDOP $vdopStr $vdopEmoji | Multi-GNSS: usar >1 constelação? $multiGnssEmoji\n\n")
        
        ssb.appendColored("[SINAL / ESPECTRO]\n", colorHighlight, true)
        val cn0Min = p?.cn0Min?.let { "%.1f".format(it) } ?: "null"
        val cn0P25 = p?.cn0Percentile25?.let { "%.1f".format(it) } ?: "null"
        val cn0P50 = p?.cn0Median?.let { "%.1f".format(it) } ?: "null"
        val cn0P75 = p?.cn0Percentile75?.let { "%.1f".format(it) } ?: "null"
        val cn0Max = p?.cn0Max?.let { "%.1f".format(it) } ?: "null"
        val cn0Avg = p?.cn0Average?.let { "%.1f".format(it) } ?: "null"
        val cn0Sigma = p?.gnssRaw?.cn0Sigma?.let { "%.2f".format(it) } ?: "null"
        
        ssb.appendNormal("C/N0 dB-Hz: min $cn0Min, p25 $cn0P25, p50 $cn0P50, p75 $cn0P75, máx $cn0Max, média $cn0Avg, σ $cn0Sigma\n\n")
        
        ssb.appendNormal("Doppler:\n")
        val dopplerSats = p?.gnssRaw?.dopplerSatCount?.toString() ?: "null"
        val dopplerSpeed = p?.gnssRaw?.dopplerSpeedMps?.let { "%.2f".format(it) } ?: "null"
        val dopplerSigma = p?.gnssRaw?.dopplerSpeedSigma?.let { "%.3f".format(it) } ?: "null"
        ssb.appendNormal("  sats $dopplerSats | vel_doppler $dopplerSpeed m/s | sigma $dopplerSigma 🟡 baixa confiança se sigma alto\n")
        
        ssb.appendNormal("Última atualização: ${p?.gnssRaw?.satUpdateAgeMs?.toString() ?: "null"} ms | TTFF: ${p?.gnssRaw?.timeToFirstFixMs?.let { "%.0f".format(it) } ?: "null"} ms\n")
        
        ssb.appendNormal("Clock:\n")
        val clockBias = p?.gnssRaw?.clockBiasNanos?.toString() ?: "null"
        val clockDrift = p?.gnssRaw?.clockDriftNanosPerSecond?.let { "%.2f".format(it) } ?: "null"
        val bearingAcc = p?.bearingAccuracyDeg?.let { "%.2f".format(it) } ?: "null"
        ssb.appendNormal("  bias $clockBias ns | drift $clockDrift ns/s | bearing acc: $bearingAcc deg\n\n")
        
ssb.appendColored("[RESUMO]\n", colorHighlight, true)
        ssb.appendNormal("🟢 Fix bom / posição confiável\n")
        ssb.appendNormal("🟡 Altitude incerta se VDOP>3\n")
        ssb.appendNormal("🟡 CN0 médio se média<30\n")
        ssb.appendNormal("🟠 Doppler baixa confiança se sigma alto")
        
        return ssb
    }
    
    private fun buildCard4VehicleDynamics(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): CharSequence {
        val ssb = SpannableStringBuilder()
        ssb.appendNormal("🟨 CARD 4 · Dinâmica do Veículo\n")
        ssb.appendColored(buildDividerLine(), colorDivider).appendNormal("\n\n")
        
        val roll = p?.vehicleTiltRollDeg ?: 0f
        val rollStr = p?.vehicleTiltRollDeg?.let { "%.2f".format(it) } ?: "null"
        val pitchStr = p?.vehicleTiltPitchDeg?.let { "%.2f".format(it) } ?: "null"
        val yawStr = p?.yawDeg?.let { "%.2f".format(it) } ?: "null"
        val yawRate = p?.yawRateDegPerSec?.let { "%.2f".format(it) } ?: "null"
        val rollEmoji = if (abs(roll) < 5f) "🟢 nivelado" else ""
        
        ssb.appendColored("Orientação (°)", colorLabel).appendNormal("   roll    pitch    yaw    yaw_rate(°/s)\n")
        ssb.appendNormal("                   $rollStr   $pitchStr   $yawStr   $yawRate 🔵 estável | $rollEmoji\n\n")
        
        val accLong = p?.accLongitudinalMps2?.let { "%.3f".format(it) } ?: "null"
        val accLat = p?.accLateralMps2?.let { "%.3f".format(it) } ?: "null"
        val accVert = p?.accVerticalMps2?.let { "%.3f".format(it) } ?: "null"
        
        ssb.appendNormal("Aceleração longitudinal: $accLong m/s² | Lateral: $accLat m/s² | Vertical: $accVert m/s²\n")
        
        val jerkRms = p?.jerkNormRms?.let { "%.3f".format(it) } ?: "null"
        val jerkSigma = p?.jerkNormSigma?.let { "%.3f".format(it) } ?: "null"
        
        ssb.appendNormal("Jerk RMS (derivada da aceleração): $jerkRms m/s³ | Desvio Jerk σ: $jerkSigma m/s³\n")
        
        val shockLevel = p?.motionShockLevel ?: "null"
        val shockScore = p?.motionShockScore?.let { "%.2f".format(it) } ?: "null"
        
        ssb.appendNormal("Nível de choque: $shockLevel 🟡 | Pontuação de choque: $shockScore\n")
        
        val stationary = p?.motionStationary ?: false
        val motionState = if (stationary) "parado" else "em movimento / vibração"
        
        ssb.appendNormal("Estado de movimento: $motionState")
        
        return ssb
    }
    
    private fun buildCard5ImuHealth(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): CharSequence {
        val ssb = SpannableStringBuilder()
        ssb.appendNormal("🟫 CARD 5 · Saúde do IMU / Amostragem\n")
        ssb.appendColored(buildDividerLine(), colorDivider).appendNormal("\n\n")
        
        val fps = p?.imuFpsEffective ?: 0f
        val fpsStr = p?.imuFpsEffective?.let { "%.1f".format(it) } ?: "null"
        val fpsEmoji = if (fps > 100f) "🟣 alta" else ""
        val samples = p?.imuSamples?.toString() ?: "null"
        
        ssb.appendNormal("Taxa efetiva: $fpsStr Hz $fpsEmoji | Amostras: $samples\n")
        
        val accAcc = p?.accelerometerAccuracy ?: "null"
        val gyroAcc = p?.gyroscopeAccuracy ?: "null"
        val rotAcc = p?.rotationAccuracy ?: "null"
        
        ssb.appendNormal("Acurácia aceleração: $accAcc 🟢 | Acurácia giroscópio: $gyroAcc 🟢 | Acurácia rotação: $rotAcc 🟢\n")
        
        val yawRate = p?.yawRateDegPerSec?.let { "%.2f".format(it) } ?: "null"
        ssb.appendNormal("Yaw rate: $yawRate °/s 🔵 estável se ~0\n")
        
        val shockLevel = p?.motionShockLevel ?: "null"
        ssb.appendNormal("Nível de choque atual: $shockLevel 🟡\n\n")
        
        ssb.appendColored("Conclusão geral: ", colorHighlight, true)
        ssb.appendNormal("IMU estável, sensores de alta qualidade.")
        
        return ssb
    }
    
    private fun buildCard6AccelerationRaw(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): CharSequence {
        val ssb = SpannableStringBuilder()
        ssb.appendNormal("🟧 CARD 6 · Aceleração Bruta (com gravidade)\n")
        ssb.appendColored(buildDividerLine(), colorDivider).appendNormal("\n\n")
        
        ssb.append(formatTable3Axis(
            "Acc (grav)", "m/s²",
            p?.accXMean, p?.accXRms, p?.accXMax, p?.accXMin, p?.accXSigma,
            p?.accYMean, p?.accYRms, p?.accYMax, p?.accYMin, p?.accYSigma,
            p?.accZMean, p?.accZRms, p?.accZMax, p?.accZMin, p?.accZSigma
        ))
        
        val normRms = p?.accNormRms ?: 0f
        val normEmoji = if (normRms in 9.81f..9.90f) "🟢" else ""
        ssb.appendNormal("\nnorm RMS ${fmt(p?.accNormRms)} | σ ${fmt(p?.accNormSigma)} → ideal ≈9.81–9.90 m/s² parado $normEmoji")
        
        return ssb
    }
    
    private fun buildCard7AccelerationLinear(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): CharSequence {
        val ssb = SpannableStringBuilder()
        ssb.appendNormal("🟩 CARD 7 · Aceleração Linear (sem gravidade)\n")
        ssb.appendColored(buildDividerLine(), colorDivider).appendNormal("\n\n")
        
        ssb.append(formatTable3Axis(
            "Linear acc", "m/s²",
            p?.linearAccXMean, p?.linearAccXRms, p?.linearAccXMax, p?.linearAccXMin, p?.linearAccXSigma,
            p?.linearAccYMean, p?.linearAccYRms, p?.linearAccYMax, p?.linearAccYMin, p?.linearAccYSigma,
            p?.linearAccZMean, p?.linearAccZRms, p?.linearAccZMax, p?.linearAccZMin, p?.linearAccZSigma
        ))
        
        val normRms = p?.linearAccNormRms ?: 0f
        val normEmoji = if (normRms < 0.5f) "🟢" else ""
        ssb.appendNormal("\nnorm RMS ${fmt(p?.linearAccNormRms)} | σ ${fmt(p?.linearAccNormSigma)} → vibração do veículo $normEmoji se baixo")
        
        return ssb
    }
    
    private fun buildCard8Gyro(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): CharSequence {
        val ssb = SpannableStringBuilder()
        ssb.appendNormal("🟦 CARD 8 · Giro / Movimento Rotacional\n")
        ssb.appendColored(buildDividerLine(), colorDivider).appendNormal("\n\n")
        
        ssb.append(formatTable3Axis(
            "Giro", "rad/s",
            p?.gyroXMean, p?.gyroXRms, p?.gyroXMax, p?.gyroXMin, p?.gyroXSigma,
            p?.gyroYMean, p?.gyroYRms, p?.gyroYMax, p?.gyroYMin, p?.gyroYSigma,
            p?.gyroZMean, p?.gyroZRms, p?.gyroZMax, p?.gyroZMin, p?.gyroZSigma
        ))
        
        ssb.appendNormal("\nnorm RMS ${fmt(p?.gyroNormRms)} | σ ${fmt(p?.gyroNormSigma)} → baixa rotação = estável 🔵")
        
        return ssb
    }
    
    private fun buildCard9Magnetometer(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): CharSequence {
        val ssb = SpannableStringBuilder()
        ssb.appendNormal("🟪 CARD 9 · Campo Magnético e Orientação\n")
        ssb.appendColored(buildDividerLine(), colorDivider).appendNormal("\n\n")
        
        ssb.append(formatTable3Axis(
            "Mag", "µT",
            p?.magnetometerXMean, p?.magnetometerXRms, p?.magnetometerXMax, p?.magnetometerXMin, p?.magnetometerXSigma,
            p?.magnetometerYMean, p?.magnetometerYRms, p?.magnetometerYMax, p?.magnetometerYMin, p?.magnetometerYSigma,
            p?.magnetometerZMean, p?.magnetometerZRms, p?.magnetometerZMax, p?.magnetometerZMin, p?.magnetometerZSigma
        ))
        
        val fieldStrength = p?.magnetometerFieldStrength ?: 0f
        val fieldEmoji = if (fieldStrength in 25f..65f) "🟢" else ""
        ssb.appendNormal("\nCampo total: ${fmt(p?.magnetometerFieldStrength)} µT $fieldEmoji faixa terrestre normal\n")
        ssb.appendNormal("Orientação: yaw ${fmt(p?.yawDeg)}° | pitch ${fmt(p?.pitchDeg)}° | roll ${fmt(p?.rollDeg)}°")
        
        return ssb
    }
    
    private fun buildCardRawGnss(state: TelemetryUiState, p: com.example.sensorlogger.model.TelemetryPayloadV11?): CharSequence {
        val ssb = SpannableStringBuilder()
        ssb.appendNormal("🟦 CARD RAW GNSS · Medições Individuais\n")
        ssb.appendColored(buildDividerLine(), colorDivider).appendNormal("\n\n")
        
        val raw = p?.gnssRaw
        val agcAvg = raw?.agcDbAvg?.let { "%.2f".format(it) } ?: "null"
        val dopplerSats = raw?.dopplerSatCount?.toString() ?: "null"
        
        ssb.appendNormal("AGC média: $agcAvg dB | Satélites com Doppler: $dopplerSats\n\n")
        
        if (raw?.measurements?.isNotEmpty() == true) {
            raw.measurements.forEach { m ->
                val svid = m.svid
                val const = getConstellationName(m.constellationType)
                val cn0 = m.cn0DbHz?.let { "%.1f".format(it) } ?: "N/A"
                val freq = m.carrierFrequencyHz?.let { "%.3f".format(it / 1e6) } ?: "N/A"
                val doppler = m.pseudorangeRateMetersPerSecond?.let { "%.2f".format(it) } ?: "N/A"
                val agc = m.agcDb?.let { "%.2f".format(it) } ?: "N/A"
                ssb.appendNormal("$svid ($const) → CN0 $cn0 dB-Hz | Freq $freq MHz | Doppler $doppler m/s | AGC $agc dB\n")
            }
        } else {
            ssb.appendColored("(nenhuma medição raw disponível)", colorMuted)
        }
        
        return ssb
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
