package com.magcooler.magiskble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

class BleCoolerClient(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private var config: ModuleConfig = ModuleConfig.load(),
    private val logger: ModuleLogger = ModuleLogger(config),
    private val onStatus: (String) -> Unit = {}
) {
    private enum class State { IDLE, SCANNING, CONNECTING, DISCOVERING, READY }

    private data class WriteJob(
        val characteristic: BluetoothGattCharacteristic,
        val value: ByteArray,
        val label: String,
        val attemptsLeft: Int
    )

    private var state = State.IDLE
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false
    private var scanSeq = 0
    private var connectSeq = 0
    private var writeInProgress = false
    private val writeQueue = ArrayDeque<WriteJob>()

    private var charPowerMode: BluetoothGattCharacteristic? = null
    private var charFanMode: BluetoothGattCharacteristic? = null
    private var charTemp: BluetoothGattCharacteristic? = null
    private var charStatus: BluetoothGattCharacteristic? = null
    private var charBoost: BluetoothGattCharacteristic? = null
    private var charSmart: BluetoothGattCharacteristic? = null
    private var charFanSpeed: BluetoothGattCharacteristic? = null
    private var charPower: BluetoothGattCharacteristic? = null
    private var currentWrite: WriteJob? = null
    private var onReadyOnce: (() -> Unit)? = null

    fun updateConfig(newConfig: ModuleConfig) {
        config = newConfig
    }

    fun connectIfNeeded() {
        if (state == State.READY) return
        if (state == State.SCANNING || state == State.CONNECTING || state == State.DISCOVERING) return
        startScan()
    }

    fun disconnect() {
        handler.removeCallbacksAndMessages(null)
        stopScan()
        writeQueue.clear()
        writeInProgress = false
        runCatching { bluetoothGatt?.disconnect() }
        runCatching { bluetoothGatt?.close() }
        bluetoothGatt = null
        clearCharacteristics()
        state = State.IDLE
        updateState { it.copy(connected = false, status = "已断开") }
    }

    fun setManualLevel(level: Int, done: (Boolean) -> Unit) {
        ensureReady("切档") {
            val fan = charFanMode
            if (fan == null) {
                finishWrite(false, "缺少档位特征 1012", done)
                return@ensureReady
            }
            charSmart?.let { enqueueWrite(it, byteArrayOf(0x00), "关闭智能温控") }
            charBoost?.let { enqueueWrite(it, byteArrayOf(0x00), "关闭破坏神") }
            charPowerMode?.let { enqueueWrite(it, byteArrayOf(0x02), "开启散热") }
            enqueueWrite(fan, byteArrayOf(BleProtocol.fanValueForLevel(level)), "切到 ${level} 档")
            waitForWrites { ok ->
                if (ok) updateState { it.copy(level = level, mode = "手动 ${level} 档", smartOn = false, boostOn = false, coolingOn = true) }
                finishWrite(ok, if (ok) "已切到 ${level} 档" else "切档失败", done)
                refreshTelemetry()
            }
        }
    }

    fun turnOff(done: (Boolean) -> Unit) {
        ensureReady("关闭散热") {
            val power = charPowerMode
            if (power == null) {
                finishWrite(false, "缺少开关特征 1011", done)
                return@ensureReady
            }
            enqueueWrite(power, byteArrayOf(0x03), "关闭散热")
            waitForWrites { ok ->
                if (ok) updateState { it.copy(coolingOn = false, mode = "已关闭") }
                finishWrite(ok, if (ok) "散热已关闭" else "关闭失败", done)
                refreshTelemetry()
            }
        }
    }

    fun turnOn(done: (Boolean) -> Unit) {
        ensureReady("开启散热") {
            val power = charPowerMode
            if (power == null) {
                finishWrite(false, "缺少开关特征 1011", done)
                return@ensureReady
            }
            enqueueWrite(power, byteArrayOf(0x02), "开启散热")
            waitForWrites { ok ->
                if (ok) updateState { it.copy(coolingOn = true, mode = "散热开启") }
                finishWrite(ok, if (ok) "散热已开启" else "开启失败", done)
                refreshTelemetry()
            }
        }
    }

    fun setBoost(on: Boolean, done: (Boolean) -> Unit) {
        ensureReady(if (on) "开启破坏神" else "关闭破坏神") {
            val boost = charBoost
            if (boost == null) {
                finishWrite(false, "缺少破坏神特征 1017", done)
                return@ensureReady
            }
            if (on) {
                charSmart?.let { enqueueWrite(it, byteArrayOf(0x00), "关闭智能温控") }
                charPowerMode?.let { enqueueWrite(it, byteArrayOf(0x02), "开启散热") }
            }
            enqueueWrite(boost, byteArrayOf((if (on) 0x01 else 0x00).toByte()), if (on) "开启破坏神" else "关闭破坏神")
            waitForWrites { ok ->
                if (ok) updateState { it.copy(boostOn = on, smartOn = if (on) false else it.smartOn, coolingOn = if (on) true else it.coolingOn, mode = if (on) "破坏神" else "破坏神已关闭") }
                finishWrite(ok, if (ok && on) "破坏神已开启" else if (ok) "破坏神已关闭" else "破坏神切换失败", done)
                refreshTelemetry()
            }
        }
    }

    fun setSmart(on: Boolean, done: (Boolean) -> Unit) {
        ensureReady(if (on) "开启智能温控" else "关闭智能温控") {
            val smart = charSmart
            if (smart == null) {
                finishWrite(false, "缺少智能温控特征 1018", done)
                return@ensureReady
            }
            if (on) {
                charBoost?.let { enqueueWrite(it, byteArrayOf(0x00), "关闭破坏神") }
                charPowerMode?.let { enqueueWrite(it, byteArrayOf(0x02), "开启散热") }
            }
            enqueueWrite(smart, byteArrayOf((if (on) 0x01 else 0x00).toByte()), if (on) "开启智能温控" else "关闭智能温控")
            waitForWrites { ok ->
                if (ok) updateState { it.copy(smartOn = on, boostOn = if (on) false else it.boostOn, coolingOn = if (on) true else it.coolingOn, mode = if (on) "智能温控" else "智能温控已关闭") }
                finishWrite(ok, if (ok && on) "智能温控已开启" else if (ok) "智能温控已关闭" else "智能温控切换失败", done)
                refreshTelemetry()
            }
        }
    }

    fun refreshTelemetry() {
        ensureReady("刷新状态") {
            val gatt = bluetoothGatt ?: return@ensureReady
            listOfNotNull(charStatus, charTemp, charFanSpeed, charPower, charPowerMode, charBoost, charSmart)
                .distinctBy { it.uuid }
                .forEachIndexed { index, characteristic ->
                    handler.postDelayed({
                        if (hasBlePermission()) runCatching { gatt.readCharacteristic(characteristic) }
                    }, index * 260L)
                }
        }
    }

    private fun ensureReady(reason: String, action: () -> Unit) {
        if (state == State.READY) {
            action()
            return
        }
        onReadyOnce = action
        logger.info("BLE not ready, start scan for $reason")
        connectIfNeeded()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasBlePermission()) {
            updateState { it.copy(status = "缺少蓝牙权限") }
            logger.warn("missing bluetooth permission")
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            updateState { it.copy(status = "蓝牙未开启") }
            logger.warn("bluetooth adapter is unavailable or disabled")
            return
        }
        state = State.SCANNING
        scanning = true
        val seq = ++scanSeq
        updateState { it.copy(status = "扫描散热器中") }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        runCatching { adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback) }
            .onFailure {
                scanning = false
                state = State.IDLE
                updateState { old -> old.copy(status = "扫描启动失败") }
                logger.error("failed to start BLE scan", it)
            }
        handler.postDelayed({
            if (seq == scanSeq && scanning) {
                stopScan()
                state = State.IDLE
                updateState { it.copy(status = "未找到散热器") }
                logger.warn("scan timeout")
            }
        }, config.scanTimeoutMs)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        if (!hasBlePermission()) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (!isLikelyMagcooler(result)) return
            stopScan()
            state = State.CONNECTING
            val seq = ++connectSeq
            val name = result.device?.name ?: result.scanRecord?.deviceName ?: "Magcooler"
            updateState { it.copy(status = "发现散热器：$name") }
            logger.info("found cooler $name ${device.address}")
            runCatching {
                bluetoothGatt?.close()
                bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                handler.postDelayed({
                    if (seq == connectSeq && state != State.READY) {
                        logger.warn("connect timeout")
                        releaseGatt("连接超时")
                    }
                }, config.connectTimeoutMs)
            }.onFailure {
                state = State.IDLE
                updateState { old -> old.copy(status = "连接失败") }
                logger.error("failed to connect GATT", it)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) {
                runCatching { gatt.close() }
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                logger.warn("GATT failed status=$status newState=$newState")
                releaseGatt("GATT 连接失败")
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                state = State.DISCOVERING
                updateState { it.copy(status = "发现服务中") }
                runCatching { gatt.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                releaseGatt("散热器已断开")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger.warn("service discovery failed status=$status")
                releaseGatt("服务发现失败")
                return
            }
            findCharacteristics(gatt)
            if (charPowerMode == null || charStatus == null || charFanMode == null) {
                logger.warn("missing required characteristics 1011/1012/1015")
                releaseGatt("未发现散热器协议")
                return
            }
            state = State.READY
            updateState { it.copy(connected = true, status = "散热器已连接") }
            logger.info("cooler GATT ready")
            enableNotifications(gatt)
            refreshTelemetry()
            onReadyOnce?.let {
                onReadyOnce = null
                handler.post(it)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                writeInProgress = false
                val current = currentWrite
                currentWrite = null
                if (status != BluetoothGatt.GATT_SUCCESS && current != null && current.attemptsLeft > 0) {
                    logger.warn("write ${current.label} failed status=$status, retrying")
                    writeQueue.addFirst(current.copy(attemptsLeft = current.attemptsLeft - 1))
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    updateState { it.copy(lastWriteResult = "写入失败：$status") }
                    logger.warn("write failed status=$status")
                } else if (current != null) {
                    updateState { it.copy(lastWriteResult = current.label) }
                    logger.info("write ok: ${current.label}")
                }
                scheduleNextWrite()
            }
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt !== bluetoothGatt) return
            handleCharacteristic(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (gatt !== bluetoothGatt) return
            handleCharacteristic(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) handleCharacteristic(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) handleCharacteristic(characteristic.uuid, value)
        }
    }

    private fun findCharacteristics(gatt: BluetoothGatt) {
        val chars = gatt.services.flatMap { it.characteristics }
        fun find(uuid: UUID): BluetoothGattCharacteristic? = chars.firstOrNull { it.uuid == uuid }
        charPowerMode = find(BleProtocol.powerModeUuid)
        charFanMode = find(BleProtocol.fanModeUuid)
        charTemp = find(BleProtocol.tempUuid)
        charStatus = find(BleProtocol.statusUuid)
        charBoost = find(BleProtocol.boostUuid)
        charSmart = find(BleProtocol.smartUuid)
        charFanSpeed = find(BleProtocol.fanSpeedUuid)
        charPower = find(BleProtocol.powerUuid)
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt) {
        if (!hasBlePermission()) return
        val notifyList = listOfNotNull(charStatus, charTemp, charFanSpeed, charPower, charPowerMode, charBoost, charSmart)
            .distinctBy { it.uuid }
            .filter { characteristic ->
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            }
        notifyList.forEachIndexed { index, characteristic ->
            handler.postDelayed({
                runCatching {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val cccd = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(cccd)
                        }
                    }
                }.onFailure { logger.warn("enable notify failed ${characteristic.uuid}", it) }
            }, index * 220L)
        }
    }

    private fun handleCharacteristic(uuid: UUID, value: ByteArray) {
        when (uuid) {
            BleProtocol.powerModeUuid -> {
                val raw = value.firstOrNull()?.toInt()?.and(0xFF)
                if (raw == 0x02 || raw == 0x03) updateState { it.copy(coolingOn = raw == 0x02, status = if (raw == 0x02) "散热已开启" else "散热已关闭") }
            }
            BleProtocol.boostUuid -> {
                val on = value.firstOrNull()?.toInt()?.and(0xFF) == 0x01
                updateState { it.copy(boostOn = on, mode = if (on) "破坏神" else if (it.mode == "破坏神") "待机" else it.mode) }
            }
            BleProtocol.smartUuid -> {
                val on = value.firstOrNull()?.toInt()?.and(0xFF) == 0x01
                updateState { it.copy(smartOn = on, mode = if (on) "智能温控" else if (it.mode == "智能温控") "待机" else it.mode) }
            }
            BleProtocol.tempUuid -> parseTemp(value)?.let { temp -> updateState { it.copy(coolerTempC = temp) } }
            BleProtocol.fanSpeedUuid -> parseRpm(value)?.let { rpm -> updateState { it.copy(fanRpm = rpm) } }
            BleProtocol.powerUuid -> parsePower(value)?.let { power -> updateState { it.copy(powerW = power) } }
            BleProtocol.statusUuid -> parseStatus1015(value)
        }
    }

    private fun parseStatus1015(value: ByteArray) {
        if (value.size < 2) return
        when (value[0].toInt() and 0xFF) {
            0x04 -> parseStatusTemp(value)?.let { temp -> updateState { it.copy(coolerTempC = temp) } }
            0x08 -> parseRpm(value)?.let { rpm -> updateState { it.copy(fanRpm = rpm) } }
            0x09 -> parsePower(value)?.let { power -> updateState { it.copy(powerW = power) } }
            0x05 -> {
                val raw = value[1].toInt() and 0xFF
                if (raw == 0x7F) updateState { it.copy(coolingOn = false, mode = "已关闭") }
            }
        }
    }

    private fun enqueueWrite(characteristic: BluetoothGattCharacteristic, value: ByteArray, label: String) {
        writeQueue.add(WriteJob(characteristic, value, label, config.retryCount))
        scheduleNextWrite()
    }

    @SuppressLint("MissingPermission")
    private fun scheduleNextWrite() {
        if (writeInProgress) return
        val gatt = bluetoothGatt ?: return
        val job = if (writeQueue.isEmpty()) return else writeQueue.removeFirst()
        if (!hasBlePermission()) return
        handler.postDelayed({
            currentWrite = job
            writeInProgress = true
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(job.characteristic, job.value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                job.characteristic.setValue(job.value)
                @Suppress("DEPRECATION")
                job.characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(job.characteristic)
            }
            if (!ok) {
                writeInProgress = false
                currentWrite = null
                if (job.attemptsLeft > 0) writeQueue.addFirst(job.copy(attemptsLeft = job.attemptsLeft - 1))
                scheduleNextWrite()
            }
        }, config.writeDelayMs)
    }

    private fun waitForWrites(done: (Boolean) -> Unit) {
        val startedAt = System.currentTimeMillis()
        fun poll() {
            if (writeQueue.isEmpty() && !writeInProgress) {
                done(true)
                return
            }
            if (System.currentTimeMillis() - startedAt > 12_000L) {
                done(false)
                return
            }
            handler.postDelayed({ poll() }, 250L)
        }
        poll()
    }

    private fun finishWrite(ok: Boolean, message: String, done: (Boolean) -> Unit) {
        updateState { it.copy(lastWriteResult = message, status = message) }
        done(ok)
    }

    private fun releaseGatt(message: String) {
        stopScan()
        runCatching { bluetoothGatt?.disconnect() }
        runCatching { bluetoothGatt?.close() }
        bluetoothGatt = null
        clearCharacteristics()
        writeQueue.clear()
        writeInProgress = false
        state = State.IDLE
        updateState { it.copy(connected = false, status = message) }
    }

    private fun clearCharacteristics() {
        charPowerMode = null
        charFanMode = null
        charTemp = null
        charStatus = null
        charBoost = null
        charSmart = null
        charFanSpeed = null
        charPower = null
    }

    private fun hasBlePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun isLikelyMagcooler(result: ScanResult): Boolean {
        val name = result.device?.name ?: result.scanRecord?.deviceName ?: ""
        if (isLikelyMagcoolerName(name)) return true
        val serviceText = result.scanRecord?.serviceUuids?.joinToString(",") { it.uuid.toString().lowercase(Locale.US) }.orEmpty()
        if (serviceText.contains("00001011") || serviceText.contains("00001015") || serviceText.contains("0000101c")) return true
        val recordText = result.scanRecord?.bytes?.let { bytes ->
            buildString {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 0xFF
                    append(if (value in 32..126) value.toChar() else ' ')
                }
            }.lowercase(Locale.US)
        } ?: return false
        return config.deviceNameFilters.any { recordText.contains(it) } ||
            recordText.contains("1011") || recordText.contains("1015") || recordText.contains("101c")
    }

    private fun isLikelyMagcoolerName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed.equals("Unknown", ignoreCase = true)) return false
        val printable = trimmed.count { !it.isISOControl() && (it.code in 32..126 || it in '一'..'鿿') }
        if (printable < 2 || printable.toFloat() / trimmed.length.coerceAtLeast(1) < 0.72f) return false
        val lower = trimmed.lowercase(Locale.US)
        if (config.deviceNameFilters.any { lower.contains(it) }) return true
        return Regex("(^|[^a-z0-9])rm[^a-z0-9]*8[^a-z0-9]*pro([^a-z0-9]|$)").containsMatchIn(lower)
    }

    private fun parseTemp(value: ByteArray): Float? {
        if (value.isEmpty()) return null
        if (value.size == 1) {
            val temp = value[0].toInt().toFloat()
            return if (temp in -40f..80f) temp else null
        }
        if ((value[0].toInt() and 0xFF) == 0x04) return parseStatusTemp(value)
        val be = ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
        val le = ((value[1].toInt() and 0xFF) shl 8) or (value[0].toInt() and 0xFF)
        return listOfNotNull(signed16ToC(be), signed16ToC(le), be.toFloat(), be / 10f, le.toFloat(), le / 10f)
            .firstOrNull { it in -40f..80f }
    }

    private fun parseStatusTemp(value: ByteArray): Float? {
        if (value.size < 2) return null
        val temp = value[1].toInt().toFloat()
        return if (temp in -40f..80f) temp else null
    }

    private fun signed16ToC(raw: Int): Float? {
        val signed = if (raw and 0x8000 != 0) raw - 0x10000 else raw
        return if (signed in -400..800) signed.toFloat() else null
    }

    private fun parseRpm(value: ByteArray): Int? {
        if (value.size >= 3 && (value[0].toInt() and 0xFF) == 0x08) {
            return ((value[1].toInt() and 0xFF) shl 8) or (value[2].toInt() and 0xFF)
        }
        if (value.size < 2) return null
        val rpm = ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
        return if (rpm in 0..12000) rpm else null
    }

    private fun parsePower(value: ByteArray): Int? {
        if (value.isEmpty()) return null
        val power = if (value.size >= 2 && (value[0].toInt() and 0xFF) == 0x09) {
            value[1].toInt() and 0xFF
        } else {
            value[0].toInt() and 0xFF
        }
        return if (power in 0..80) power else null
    }

    private fun updateState(transform: (CoolerTelemetryState) -> CoolerTelemetryState) {
        CoolerStateStore.update(transform)
        onStatus(CoolerStateStore.state.status)
    }
}
