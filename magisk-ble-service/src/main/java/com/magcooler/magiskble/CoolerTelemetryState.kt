package com.magcooler.magiskble

import android.os.Handler
import android.os.Looper

data class CoolerTelemetryState(
    val connected: Boolean = false,
    val status: String = "未连接",
    val mode: String = "待机",
    val level: Int? = null,
    val fanRpm: Int? = null,
    val coolerTempC: Float? = null,
    val powerW: Int? = null,
    val coolingOn: Boolean = false,
    val boostOn: Boolean = false,
    val smartOn: Boolean = false,
    val levelConfirmed: Boolean = false,
    val manualWritePending: Boolean = false,
    val lastWriteResult: String = "--"
) {
    fun rpmText(): String = fanRpm?.let { "$it RPM" } ?: "--"

    fun tempText(): String = coolerTempC?.let {
        if (kotlin.math.abs(it - it.toInt()) < 0.05f) "${it.toInt()}°C" else String.format(java.util.Locale.US, "%.1f°C", it)
    } ?: "--"

    fun powerText(): String = powerW?.let { "${it}W" } ?: "--"
}

object CoolerStateStore {
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(CoolerTelemetryState) -> Unit>()
    var state: CoolerTelemetryState = CoolerTelemetryState()
        private set

    fun update(transform: (CoolerTelemetryState) -> CoolerTelemetryState) {
        state = transform(state)
        notifyListeners()
    }

    fun addListener(listener: (CoolerTelemetryState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (CoolerTelemetryState) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val snapshot = state
        handler.post {
            listeners.toList().forEach { it(snapshot) }
        }
    }
}
