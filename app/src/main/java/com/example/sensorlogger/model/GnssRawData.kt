package com.example.sensorlogger.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GnssRawMeasurement(
    val svid: Int,
    @SerialName("constellation_type")
    val constellationType: Int,
    @SerialName("carrier_frequency_hz")
    val carrierFrequencyHz: Double? = null,
    @SerialName("cn0_dbhz")
    val cn0DbHz: Double? = null,
    @SerialName("pseudorange_rate_mps")
    val pseudorangeRateMetersPerSecond: Double? = null,
    @SerialName("accumulated_delta_range_m")
    val accumulatedDeltaRangeMeters: Double? = null,
    @SerialName("accumulated_delta_range_state")
    val accumulatedDeltaRangeState: Int? = null,
    @SerialName("multipath_indicator")
    val multipathIndicator: Int? = null,
    @SerialName("agc_db")
    val agcDb: Double? = null,
    @SerialName("snr_db")
    val snrDb: Double? = null
)

@Serializable
data class GnssRawSnapshot(
    @SerialName("timestamp_nanos")
    val timestampNanos: Long,
    val measurements: List<GnssRawMeasurement>,
    @SerialName("doppler_speed_mps")
    val dopplerSpeedMps: Double? = null,
    @SerialName("doppler_speed_sigma")
    val dopplerSpeedSigma: Double? = null,
    @SerialName("agc_db_avg")
    val agcDbAvg: Double? = null,
    @SerialName("agc_db_min")
    val agcDbMin: Double? = null,
    @SerialName("agc_db_max")
    val agcDbMax: Double? = null,
    @SerialName("cn0_avg")
    val cn0Avg: Double? = null,
    @SerialName("cn0_min")
    val cn0Min: Double? = null,
    @SerialName("cn0_max")
    val cn0Max: Double? = null,
    @SerialName("cn0_sigma")
    val cn0Sigma: Double? = null,
    @SerialName("doppler_sat_count")
    val dopplerSatCount: Int? = null,
    @SerialName("clock_bias_nanos")
    val clockBiasNanos: Long? = null,
    @SerialName("clock_drift_nanos_per_s")
    val clockDriftNanosPerSecond: Double? = null,
    @SerialName("time_to_first_fix_ms")
    val timeToFirstFixMs: Double? = null,
    @SerialName("sat_update_age_ms")
    val satUpdateAgeMs: Long? = null
)
