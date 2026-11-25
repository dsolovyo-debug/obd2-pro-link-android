package com.dmitry.obd2prolink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class BluetoothTriggerReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (!AppConfig.isAutoStartEnabled(context)) return
        if (ObdCaptureService.isRunning) return

        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val name = runCatching { device?.name }.getOrNull()?.uppercase() ?: return
        val keywords = AppConfig.getCarKeywords(context)

        if (keywords.any { kw -> name.contains(kw) }) {
            val svcIntent = Intent(context, ObdCaptureService::class.java).apply {
                action = ObdCaptureService.ACTION_START
                putExtra("trigger_device", name)
            }
            runCatching { ContextCompat.startForegroundService(context, svcIntent) }
        }
    }
}
