package com.example.mediasessiontest.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

enum class SyncStatus {
    DISCONNECTED,
    CONNECTING,
    SYNCED,
    CORRECTING_DRIFT
}

data class EventLogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val direction: String, // "IN" or "OUT"
    val eventType: String,
    val payload: String
)

class TelemetryState {
    var uiMode by mutableStateOf(UiMode.USER)
    var syncStatus by mutableStateOf(SyncStatus.DISCONNECTED)
    var currentRttMs by mutableStateOf(0L)
    var currentDriftMs by mutableStateOf(0.0)
    var artificialLatencyMs by mutableStateOf(0L)

    val rttHistory = mutableStateListOf<Long>()
    val driftHistory = mutableStateListOf<Double>()
    val eventLogs = mutableStateListOf<EventLogEntry>()

    fun logEvent(direction: String, eventType: String, payload: String) {
        if (eventLogs.size >= 50) {
            eventLogs.removeAt(0)
        }
        eventLogs.add(EventLogEntry(direction = direction, eventType = eventType, payload = payload))
    }

    fun recordRtt(rtt: Long) {
        currentRttMs = rtt
        if (rttHistory.size >= 30) {
            rttHistory.removeAt(0)
        }
        rttHistory.add(rtt)
    }

    fun recordDrift(driftSeconds: Double) {
        val driftMs = driftSeconds * 1000.0
        currentDriftMs = driftMs
        if (driftHistory.size >= 30) {
            driftHistory.removeAt(0)
        }
        driftHistory.add(driftMs)
    }
}
