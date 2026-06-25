package com.magcooler.magiskble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
    private var charStatus: BluetoothGattCharacteristic? = null
    private var charBoost: BluetoothGattCharacteristic? = null
    private var charSmart: BluetoothGattCharacteristic? = null
    private var charFanSpeed: BluetoothGattCharacteristic? = null
    private var charPower: BluetoothGattCharacteristic? = null

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
        onStatus("已断开")
    }

    fun setManualLevel(level: Int, done: (Boolean) -> Unit) {
        ensureReady("切档") {
            val fan = charFanMode
            if (fan == null) {
                logger.warn("fan characteristic 1012 is missing")
                done(false)
                return@ensureReady
            }
            val smart = charSmart
            val boost = charBoost
            val power = charPowerMode
            if (smart != null) enqueueWrite(smart, byteArrayOf(0x00), "关闭智能温控")
            if (boost != null) enqueueWrite(boost, byteArrayOf(0x00), "关闭 Boost")
            if (power != null) enqueueWrite(power, byteArrayOf(0x02), "开启散热")
            enqueueWrite(fan, byteArrayOf(BleProtocol.fanValueForLevel(level)), "切到 ${level} 档")
            waitForWrites(done)
        }
    }

    fun turnOff(done: (Boolean) -> Unit) {
        ensureReady("关闭散热") {
            val power = charPowerMode
            if (power == null) {
                logger.warn("power characteristic 1011 is missing")
                done(false)
                return@ensureReady
            }
            enqueueWrite(power, byteArrayOf(0x03), "关闭散热")
            waitForWrites(done)
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

    private var onReadyOnce: (() -> Unit)? = null

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasBlePermission()) {
            onStatus("缺少蓝牙权限")
            logger.warn("missing bluetooth permission")
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("蓝牙未开启")
            logger.warn("bluetooth adapter is unavailable or disabled")
            return
        }
        state = State.SCANNING
        scanning = true
        val seq = ++scanSeq
        onStatus("扫描散热器中")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        runCatching { adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback) }
            .onFailure {
                scanning = false
                state = State.IDLE
                onStatus("扫描启动失败")
                logger.error("failed to start BLE scan", it)
            }
        handler.postDelayed({
            if (seq == scanSeq && scanning) {
                stopScan()
                state = State.IDLE
                onStatus("未找到散热器")
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
            onStatus("发现散热器：$name")
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
                onStatus("连接失败")
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
                onStatus("发现服务中")
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
            onStatus("散热器已连接")
            logger.info("cooler GATT ready")
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
                    logger.warn("write failed status=$status")
                } else if (current != null) {
                    logger.info("write ok: ${current.label}")
                    onStatus(current.label)
                }
                scheduleNextWrite()
            }
        }
    }

    private var currentWrite: WriteJob? = null

    private fun findCharacteristics(gatt: BluetoothGatt) {
        val chars = gatt.services.flatMap { it.characteristics }
        fun find(uuid: java.util.UUID): BluetoothGattCharacteristic? = chars.firstOrNull { it.uuid == uuid }
        charPowerMode = find(BleProtocol.powerModeUuid)
        charFanMode = find(BleProtocol.fanModeUuid)
        charStatus = find(BleProtocol.statusUuid)
        charBoost = find(BleProtocol.boostUuid)
        charSmart = find(BleProtocol.smartUuid)
        charFanSpeed = find(BleProtocol.fanSpeedUuid)
        charPower = find(BleProtocol.powerUuid)
    }

    private fun enqueueWrite(characteristic: BluetoothGattCharacteristic, value: ByteArray, label: String) {
        writeQueue.add(WriteJob(characteristic, value, label, config.retryCount))
        scheduleNextWrite()
    }

    @SuppressLint("MissingPermission")
    private fun scheduleNextWrite() {
        if (writeInProgress) return
        val gatt = bluetoothGatt ?: return
        val job = writeQueue.removeFirstOrNull() ?: return
        if (!hasBlePermission()) return
        handler.postDelayed({
            currentWrite = job
            writeInProgress = true
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(job.characteristic, job.value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                job.characteristic.value = job.value
                job.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
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

    private fun releaseGatt(message: String) {
        stopScan()
        runCatching { bluetoothGatt?.disconnect() }
        runCatching { bluetoothGatt?.close() }
        bluetoothGatt = null
        clearCharacteristics()
        writeQueue.clear()
        writeInProgress = false
        state = State.IDLE
        onStatus(message)
    }

    private fun clearCharacteristics() {
        charPowerMode = null
        charFanMode = null
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
}
