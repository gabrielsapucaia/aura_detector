package com.example.sensorlogger.repository

import com.example.sensorlogger.model.TelemetryUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

object TelemetryStateStore {
    private val _state = MutableStateFlow(TelemetryUiState())
    val state: StateFlow<TelemetryUiState> = _state.asStateFlow()

    fun update(transform: (TelemetryUiState) -> TelemetryUiState) {
        val oldState = _state.value
        val newState = transform(oldState)
        _state.value = newState
        
        if (oldState.queueSize != newState.queueSize || oldState.offlineQueueSizeMB != newState.offlineQueueSizeMB) {
            Timber.d("StateStore queue updated: ${oldState.queueSize}->${newState.queueSize}, ${oldState.offlineQueueSizeMB}->${newState.offlineQueueSizeMB}")
        }
    }
}
