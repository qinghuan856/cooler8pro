package com.magcooler.magiskble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MagcoolerBleService.start(context, MagcoolerBleService.ACTION_UI_OPEN)
        val client = BleClientHolder.get(context)
        when (intent.action) {
            ACTION_SET_LEVEL -> client.setManualLevel(intent.getIntExtra(EXTRA_LEVEL, 6).coerceIn(1, 10)) {}
            ACTION_BOOST -> client.setBoost(intent.getBooleanExtra(EXTRA_ON, true)) {}
            ACTION_SMART -> client.setSmart(intent.getBooleanExtra(EXTRA_ON, true)) {}
            ACTION_TURN_ON -> client.turnOn {}
            ACTION_TURN_OFF -> client.turnOff {}
            ACTION_REFRESH -> client.refreshTelemetry()
            ACTION_RECONNECT -> {
                client.disconnect()
                client.connectIfNeeded()
            }
        }
    }

    companion object {
        private const val PREFIX = "com.magcooler.magiskble.action"
        const val ACTION_SET_LEVEL = "$PREFIX.SET_LEVEL"
        const val ACTION_BOOST = "$PREFIX.BOOST"
        const val ACTION_SMART = "$PREFIX.SMART"
        const val ACTION_TURN_ON = "$PREFIX.TURN_ON"
        const val ACTION_TURN_OFF = "$PREFIX.TURN_OFF"
        const val ACTION_REFRESH = "$PREFIX.REFRESH"
        const val ACTION_RECONNECT = "$PREFIX.RECONNECT"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_ON = "on"
    }
}
