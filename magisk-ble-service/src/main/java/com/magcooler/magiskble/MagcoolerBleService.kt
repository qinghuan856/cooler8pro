package com.magcooler.magiskble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class MagcoolerBleService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var config = ModuleConfig.load()
    private lateinit var logger: ModuleLogger
    private lateinit var bleClient: BleCoolerClient
    private var statusText = "等待充电状态"
    private var lastCharging: Boolean? = null
    private var evaluating = false

    override fun onCreate() {
        super.onCreate()
        logger = ModuleLogger(config)
        bleClient = BleClientHolder.get(this)
        CoolerStateStore.addListener { state -> updateStatus(state.status) }
        createChannel()
        startForegroundCompat()
        StateFileWriter.start()
        logger.info("service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        config = ModuleConfig.load()
        bleClient.updateConfig(config)
        logger.info("service start action=${intent?.action ?: "none"} enabled=${config.enabled} level=${config.chargeLevel}")
        startForegroundCompat()
        evaluateChargingState(intent?.action ?: ACTION_EVALUATE)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        logger.info("service destroyed")
        bleClient.disconnect()
        super.onDestroy()
    }

    private fun evaluateChargingState(reason: String) {
        if (evaluating) return
        evaluating = true
        handler.postDelayed({
            evaluating = false
            if (reason == ACTION_UI_OPEN) {
                updateStatus("控制面板已打开")
                bleClient.connectIfNeeded()
                return@postDelayed
            }
            if (!config.enabled) {
                updateStatus("模块已禁用")
                logger.info("skip because module disabled")
                return@postDelayed
            }
            val charging = isPhoneCharging()
            logger.info("evaluate reason=$reason charging=$charging last=$lastCharging")
            lastCharging = charging
            if (charging) {
                updateStatus("充电中，准备切到 ${config.chargeLevel} 档")
                bleClient.setManualLevel(config.chargeLevel) { ok ->
                    if (ok) {
                        markTookOver(true)
                        updateStatus("充电中：已切到 ${config.chargeLevel} 档")
                    } else {
                        updateStatus("充电切档失败")
                    }
                }
            } else if (config.closeOnUnplug && didTakeOver()) {
                updateStatus("已断电，准备关闭散热")
                bleClient.turnOff { ok ->
                    if (ok) {
                        markTookOver(false)
                        updateStatus("已断电：散热已关闭")
                    } else {
                        updateStatus("断电关闭失败")
                    }
                }
            } else {
                updateStatus("未充电，保持待机")
            }
        }, if (reason == Intent.ACTION_POWER_DISCONNECTED) 1200L else 0L)
    }

    private fun isPhoneCharging(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
    }

    private fun updateStatus(status: String) {
        statusText = status
        logger.info(status)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun markTookOver(value: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TOOK_OVER, value)
            .apply()
    }

    private fun didTakeOver(): Boolean {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TOOK_OVER, false)
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("冰环充电散热模块")
            .setContentText(statusText)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "冰环充电散热", NotificationManager.IMPORTANCE_LOW).apply {
            description = "充电时自动控制冰环散热器"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_EVALUATE = "com.magcooler.magiskble.action.EVALUATE"
        const val ACTION_UI_OPEN = "com.magcooler.magiskble.action.UI_OPEN"
        private const val CHANNEL_ID = "magcooler_charge_cooler"
        private const val NOTIFICATION_ID = 4608
        private const val PREFS = "magcooler_magisk_ble"
        private const val KEY_TOOK_OVER = "took_over"

        fun start(context: Context, action: String = ACTION_EVALUATE) {
            val intent = Intent(context, MagcoolerBleService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
