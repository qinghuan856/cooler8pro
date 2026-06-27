package com.magcooler.magiskble

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object LocalWebServer {
    private const val PORT = 27991
    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    private var appContext: Context? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        thread(name = "MagcoolerLocalWebServer", isDaemon = true) {
            runCatching {
                serverSocket = ServerSocket(PORT, 8, java.net.InetAddress.getByName("127.0.0.1"))
                while (running.get()) {
                    val socket = serverSocket?.accept() ?: break
                    thread(name = "MagcoolerLocalWebRequest", isDaemon = true) {
                        socket.use { s ->
                            runCatching {
                                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                                val requestLine = reader.readLine().orEmpty()
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                }
                                val response = handleRequest(requestLine)
                                writeResponse(s.getOutputStream(), response)
                            }
                        }
                    }
                }
            }
            running.set(false)
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleRequest(requestLine: String): String {
        val path = requestLine.split(' ').getOrNull(1) ?: "/status"
        val route = path.substringBefore('?')
        val query = path.substringAfter('?', "")
        val params = parseQuery(query)
        return when (route) {
            "/status" -> CoolerStateStore.state.toJson().toString()
            "/cmd" -> {
                dispatchCommand(params)
                JSONObject().put("ok", true).put("state", CoolerStateStore.state.toJson()).toString()
            }
            else -> JSONObject().put("ok", false).put("error", "not_found").toString()
        }
    }

    private fun dispatchCommand(params: Map<String, String>) {
        val context = appContext ?: return
        val client = BleClientHolder.get(context)
        val action = params["action"].orEmpty().lowercase(Locale.US)
        mainHandler.post {
            when (action) {
                "set-level" -> client.setManualLevel(params["level"]?.toIntOrNull()?.coerceIn(1, 10) ?: 6) {}
                "boost" -> client.setBoost(params["on"] != "false" && params["on"] != "off") {}
                "smart" -> client.setSmart(params["on"] != "false" && params["on"] != "off") {}
                "turn-on" -> client.turnOn {}
                "turn-off" -> client.turnOff {}
                "refresh" -> client.refreshTelemetry()
                "reconnect" -> {
                    client.disconnect()
                    client.connectIfNeeded()
                }
            }
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "")
            if (key.isBlank()) return@mapNotNull null
            val value = part.substringAfter('=', "")
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }.toMap()
    }

    private fun writeResponse(output: OutputStream, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Cache-Control: no-store\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
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
}
