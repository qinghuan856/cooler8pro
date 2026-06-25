package com.magcooler.magiskble

import java.util.Locale
import java.util.UUID

object BleProtocol {
    val powerModeUuid: UUID = uuid16("1011")
    val fanModeUuid: UUID = uuid16("1012")
    val statusUuid: UUID = uuid16("1015")
    val boostUuid: UUID = uuid16("1017")
    val smartUuid: UUID = uuid16("1018")
    val fanSpeedUuid: UUID = uuid16("101C")
    val powerUuid: UUID = uuid16("101D")

    val fanLevelValues = intArrayOf(0x28, 0x2C, 0x2E, 0x34, 0x3A, 0x40, 0x44, 0x48, 0x4C, 0x50)

    val defaultNameFilters = listOf(
        "redmagic",
        "red magic",
        "nubia",
        "magcooler",
        "mag cooler",
        "jacket",
        "srq",
        "8pro"
    )

    fun uuid16(value: String): UUID {
        return UUID.fromString("0000${value.lowercase(Locale.US)}-0000-1000-8000-00805f9b34fb")
    }

    fun fanValueForLevel(level: Int): Byte {
        val index = level.coerceIn(1, fanLevelValues.size) - 1
        return fanLevelValues[index].toByte()
    }
}
