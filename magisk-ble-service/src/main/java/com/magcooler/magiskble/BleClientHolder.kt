package com.magcooler.magiskble

import android.content.Context
import android.os.Handler
import android.os.Looper

object BleClientHolder {
    private var client: BleCoolerClient? = null

    fun get(context: Context): BleCoolerClient {
        val existing = client
        if (existing != null) return existing
        val appContext = context.applicationContext
        val config = ModuleConfig.load()
        val logger = ModuleLogger(config)
        return BleCoolerClient(
            context = appContext,
            handler = Handler(Looper.getMainLooper()),
            config = config,
            logger = logger,
            onStatus = { status ->
                CoolerStateStore.update { it.copy(status = status) }
            }
        ).also { client = it }
    }
}
