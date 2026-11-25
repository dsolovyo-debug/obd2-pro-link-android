package com.dmitry.obd2prolink

import android.content.Context

object AppConfig {
    const val PREFS_NAME = "obd2_pro_link_prefs"
    const val DEFAULT_DRIVE_URL =
        "https://script.google.com/macros/s/AKfycbz1hGnF56n-N6ebow9QNXd0KwS22C2-o1OJPTUDHN9yHNXzlkL-B2PsaLobKa5nPT0k/exec"
    const val DEFAULT_CAR_KEYWORDS =
        "ELANTRA,HYUNDAI,SUBARU,LEGACY,MB BLUETOOTH,MERCEDES,HANDSFREELINK,HONDA,IOS-VLINK"
    const val DEFAULT_DURATION_MIN = 20

    // Verified Vgate iCar Pro BLE GATT UUIDs
    const val VGATE_DEVICE_HINT = "IOS-Vlink"
    const val VGATE_SERVICE_UUID = "e7810a71-73ae-499d-8c15-faa9aef0c3f2"
    const val VGATE_CHAR_UUID = "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    fun isAutoStartEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("auto_start", true)

    fun setAutoStartEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("auto_start", enabled).apply()
    }

    fun getDurationMinutes(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("duration_min", DEFAULT_DURATION_MIN).coerceIn(1, 180)

    fun setDurationMinutes(ctx: Context, min: Int) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("duration_min", min.coerceIn(1, 180)).apply()
    }

    fun getDriveUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("drive_url", DEFAULT_DRIVE_URL)?.trim().orEmpty()
            .ifEmpty { DEFAULT_DRIVE_URL }

    fun setDriveUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("drive_url", url.trim()).apply()
    }

    fun getCarKeywords(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("car_keywords", DEFAULT_CAR_KEYWORDS) ?: DEFAULT_CAR_KEYWORDS
        return raw.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
    }

    fun setCarKeywords(ctx: Context, raw: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("car_keywords", raw.trim()).apply()
    }

    fun identifyFleetVehicle(vin: String?): String {
        val v = vin?.uppercase().orEmpty()
        return when {
            v.startsWith("KMHLN4DJ") -> "2024 Hyundai Elantra Hybrid Limited"
            v.startsWith("4S3BNAN") -> "2015 Subaru Legacy 2.5i Limited"
            v.startsWith("WDDHF8JB") -> "2016 Mercedes-Benz E350 4Matic"
            v.startsWith("2HKRM") || v.startsWith("5J6RM") || v.startsWith("JHLRM") -> "2015 Honda CR-V"
            v.length >= 8 -> "Vehicle ($v)"
            else -> "Fleet Vehicle"
        }
    }
}
