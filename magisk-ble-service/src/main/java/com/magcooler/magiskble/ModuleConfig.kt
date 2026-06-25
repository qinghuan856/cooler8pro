package com.magcooler.magiskble

import java.io.File
import java.util.Locale
import java.util.Properties

data class ModuleConfig(
    val enabled: Boolean = true,
    val chargeLevel: Int = 6,
    val unplugAction: String = "off",
    val scanTimeoutMs: Long = 18_000L,
    val connectTimeoutMs: Long = 18_000L,
    val writeDelayMs: Long = 220L,
    val retryCount: Int = 2,
    val reconnectBackoffMs: Long = 5_000L,
    val logEnabled: Boolean = true,
    val deviceNameFilters: List<String> = BleProtocol.defaultNameFilters
) {
    val closeOnUnplug: Boolean
        get() = unplugAction.equals("off", ignoreCase = true)

    companion object {
        private val configPaths = listOf(
            "/system/etc/binghuan_charge_cooler/config.prop",
            "/data/adb/modules/binghuan_charge_cooler/config.prop",
            "/data/adb/binghuan_charge_cooler/config.prop"
        )

        fun load(): ModuleConfig {
            val properties = Properties()
            configPaths.asSequence()
                .map { File(it) }
                .firstOrNull { it.isFile }
                ?.inputStream()
                ?.use { properties.load(it) }

            return ModuleConfig(
                enabled = properties.boolean("enabled", true),
                chargeLevel = properties.int("charge_level", 6).coerceIn(1, BleProtocol.fanLevelValues.size),
                unplugAction = properties.getProperty("unplug_action", "off").trim().lowercase(Locale.US),
                scanTimeoutMs = properties.long("scan_timeout_ms", 18_000L),
                connectTimeoutMs = properties.long("connect_timeout_ms", 18_000L),
                writeDelayMs = properties.long("write_delay_ms", 220L),
                retryCount = properties.int("retry_count", 2).coerceAtLeast(0),
                reconnectBackoffMs = properties.long("reconnect_backoff_ms", 5_000L),
                logEnabled = properties.boolean("log_enabled", true),
                deviceNameFilters = properties.getProperty("device_name_filters")
                    ?.split(',')
                    ?.map { it.trim().lowercase(Locale.US) }
                    ?.filter { it.isNotBlank() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: BleProtocol.defaultNameFilters
            )
        }

        private fun Properties.boolean(key: String, defaultValue: Boolean): Boolean {
            return getProperty(key)?.trim()?.lowercase(Locale.US)?.let {
                it == "1" || it == "true" || it == "yes" || it == "on"
            } ?: defaultValue
        }

        private fun Properties.int(key: String, defaultValue: Int): Int {
            return getProperty(key)?.trim()?.toIntOrNull() ?: defaultValue
        }

        private fun Properties.long(key: String, defaultValue: Long): Long {
            return getProperty(key)?.trim()?.toLongOrNull() ?: defaultValue
        }
    }
}
