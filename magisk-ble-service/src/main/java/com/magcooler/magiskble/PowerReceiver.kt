package com.magcooler.magiskble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: MagcoolerBleService.ACTION_EVALUATE
        MagcoolerBleService.start(context, action)
    }
}
