package com.magcooler.magiskble

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File

object StateFileWriter {
    private val handler = Handler(Looper.getMainLooper())
    private var lastWritten = ""
    private var stateFile: File? = null
    private var seq = 0

    fun start(context: Context) {
        stateFile = File(context.filesDir, "state.json")
        val currentSeq = ++seq
        fun write() {
            if (currentSeq != seq) return
            val text = CoolerStateStore.state.toJson().toString()
            if (text != lastWritten) {
                lastWritten = text
                runCatching {
                    stateFile?.writeText(text)
                }
                runCatching {
                    File(STATE_DIR).mkdirs()
                    File(STATE_FILE).writeText(text)
                }
            }
            handler.postDelayed({ write() }, 1000L)
        }
        write()
    }

    fun stop() {
        seq++
    }

    private fun CoolerTelemetryState.toJson(): JSONObject {
        return JSONObject().apply {
            put("connected", connected)
            put("status", status)
            put("mode", mode)
            put("level", level ?: -1)
            put("fanRpm", fanRpm ?: -1)
            put("coolerTempC", coolerTempC?.toDouble() ?: -99.0)
            put("powerW", powerW ?: -1)
            put("coolingOn", coolingOn)
            put("boostOn", boostOn)
            put("smartOn", smartOn)
            put("levelConfirmed", levelConfirmed)
            put("manualWritePending", manualWritePending)
            put("lastWriteResult", lastWriteResult)
        }
    }

    private const val STATE_DIR = "/data/adb/binghuan_charge_cooler"
    private const val STATE_FILE = "$STATE_DIR/state.json"
}
