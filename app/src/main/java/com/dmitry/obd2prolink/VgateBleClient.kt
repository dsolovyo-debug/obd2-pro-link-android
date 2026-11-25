package com.dmitry.obd2prolink

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

@SuppressLint("MissingPermission")
class VgateBleClient(private val context: Context) {

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager.adapter

    private var gatt: BluetoothGatt? = null
    private var rwChar: BluetoothGattCharacteristic? = null
    private val cmdMutex = Mutex()
    private val rxBuffer = StringBuilder()
    private var pendingDeferred: CompletableDeferred<String>? = null
    private var readyDeferred: CompletableDeferred<Boolean>? = null

    val isConnected: Boolean get() = gatt != null && rwChar != null

    suspend fun scanAndConnect(timeoutMs: Long = 15000L): Boolean {
        val btAdapter = adapter ?: return false
        if (!btAdapter.isEnabled) return false

        // First check already-bonded devices for IOS-Vlink
        val bonded = btAdapter.bondedDevices?.firstOrNull {
            it.name?.contains(AppConfig.VGATE_DEVICE_HINT, ignoreCase = true) == true
        }
        val targetDevice = bonded ?: scanForVgate(btAdapter, timeoutMs) ?: return false
        return connectGatt(targetDevice, 12000L)
    }

    private suspend fun scanForVgate(btAdapter: BluetoothAdapter, timeoutMs: Long): BluetoothDevice? {
        val scanner = btAdapter.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<BluetoothDevice?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: ""
                if (name.contains(AppConfig.VGATE_DEVICE_HINT, ignoreCase = true) ||
                    name.contains("V-LINK", ignoreCase = true) ||
                    name.contains("Vgate", ignoreCase = true)
                ) {
                    if (!found.isCompleted) found.complete(result.device)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                if (!found.isCompleted) found.complete(null)
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, cb)
        return try {
            withTimeoutOrNull(timeoutMs) { found.await() }
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    private suspend fun connectGatt(device: BluetoothDevice, timeoutMs: Long): Boolean {
        disconnect()
        readyDeferred = CompletableDeferred()
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    rwChar = null
                    readyDeferred?.complete(false)
                    pendingDeferred?.complete("")
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    readyDeferred?.complete(false)
                    return
                }
                val svc = g.getService(UUID.fromString(AppConfig.VGATE_SERVICE_UUID))
                val ch = svc?.getCharacteristic(UUID.fromString(AppConfig.VGATE_CHAR_UUID))
                if (ch == null) {
                    readyDeferred?.complete(false)
                    return
                }
                rwChar = ch
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(UUID.fromString(AppConfig.CCCD_UUID))
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                } else {
                    readyDeferred?.complete(true)
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                readyDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handleIncoming(value)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                c.value?.let { handleIncoming(it) }
            }
        }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(timeoutMs) { readyDeferred?.await() } == true
        if (!ok) disconnect()
        return ok
    }

    private fun handleIncoming(bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8)
        synchronized(rxBuffer) {
            rxBuffer.append(text)
            if (rxBuffer.contains(">")) {
                val full = rxBuffer.toString().replace(">", "").trim()
                rxBuffer.clear()
                pendingDeferred?.complete(full)
            }
        }
    }

    suspend fun sendCommand(cmd: String, timeoutMs: Long = 2200L): String = cmdMutex.withLock {
        val g = gatt ?: return ""
        val ch = rwChar ?: return ""
        synchronized(rxBuffer) { rxBuffer.clear() }
        val def = CompletableDeferred<String>()
        pendingDeferred = def

        val payload = (cmd.trim() + "\r").toByteArray(Charsets.UTF_8)
        delay(25) // Proven BLE write pacing for Vgate iCar Pro
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            ch.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }

        val reply = withTimeoutOrNull(timeoutMs) { def.await() }
        pendingDeferred = null
        reply ?: synchronized(rxBuffer) {
            val partial = rxBuffer.toString().trim()
            rxBuffer.clear()
            partial
        }
    }

    suspend fun initElm327(): Boolean {
        sendCommand("ATZ", 1800)
        delay(1100) // Proven ATZ settle time
        sendCommand("ATE0", 1200)
        sendCommand("ATL0", 1200)
        sendCommand("ATS0", 1200)
        sendCommand("ATH0", 1200)
        sendCommand("ATSP6", 1500)
        val check = sendCommand("0100", 2500)
        if (!check.uppercase().contains("4100")) {
            sendCommand("ATSP0", 1500)
            val fallback = sendCommand("0100", 3500)
            return fallback.uppercase().contains("4100")
        }
        return true
    }

    fun disconnect() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        rwChar = null
        pendingDeferred?.complete("")
        pendingDeferred = null
    }
}
