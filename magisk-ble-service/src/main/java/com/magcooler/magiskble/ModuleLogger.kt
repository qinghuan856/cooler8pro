package com.magcooler.magiskble

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ModuleLogger(private val config: ModuleConfig = ModuleConfig.load()) {
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun info(message: String) = write(Log.INFO, message, null)

    fun warn(message: String, throwable: Throwable? = null) = write(Log.WARN, message, throwable)

    fun error(message: String, throwable: Throwable? = null) = write(Log.ERROR, message, throwable)

    private fun write(priority: Int, message: String, throwable: Throwable?) {
        if (throwable == null) {
            Log.println(priority, TAG, message)
        } else {
            Log.println(priority, TAG, "$message: ${throwable.message}\n${Log.getStackTraceString(throwable)}")
        }
        if (!config.logEnabled) return
        runCatching {
            val dir = File(LOG_DIR)
            dir.mkdirs()
            val line = buildString {
                append(timeFormat.format(Date()))
                append(' ')
                append(message)
                if (throwable != null) append(" (${throwable.message})")
                append('\n')
            }
            File(dir, "log.txt").appendText(line)
        }
    }

    companion object {
        const val TAG = "MagcoolerMagiskBle"
        private const val LOG_DIR = "/data/adb/binghuan_charge_cooler"
    }
}
