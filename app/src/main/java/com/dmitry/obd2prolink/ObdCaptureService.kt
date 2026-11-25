package com.dmitry.obd2prolink

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ObdCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.dmitry.obd2prolink.ACTION_START"
        const val ACTION_STOP = "com.dmitry.obd2prolink.ACTION_STOP"
        const val CHANNEL_ID = "obd2_capture_channel"
        const val NOTIF_ID = 4201

        @Volatile var activeInstance: ObdCaptureService? = null
        @Volatile var isRunning = false
        @Volatile var isBleConnected = false
        @Volatile var statusText = "Standby (Waiting for Car Bluetooth)"
        @Volatile var currentSessionId = ""
        @Volatile var currentVehicle = "Fleet Vehicle"
        @Volatile var currentVin = ""
        @Volatile var remainingSeconds = 0L
        @Volatile var currentTotalRows = 0
        @Volatile var liveSnapshotJson = "{}"
        @Volatile var hvBmsJson = ""

        suspend fun sendLiveCommand(cmd: String): String {
            val inst = activeInstance ?: return "NOT CONNECTED"
            if (!inst.ble.isConnected) return "NOT CONNECTED"
            return inst.ble.sendCommand(cmd, 2500L)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var db: ObdLocalDb
    internal lateinit var ble: VgateBleClient

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        db = ObdLocalDb(this)
        ble = VgateBleClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCaptureAndSelf("Stopped by user")
            return START_NOT_STICKY
        }
        if (isRunning) return START_STICKY

        isRunning = true
        isBleConnected = false
        currentTotalRows = 0
        val durationMin = AppConfig.getDurationMinutes(this)
        remainingSeconds = durationMin * 60L
        val triggerName = intent?.getStringExtra("trigger_device") ?: "Manual"
        startForegroundCompat("Connecting to IOS-Vlink ($triggerName)...")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBD2ProLink::CaptureLock").apply {
            acquire((durationMin + 2) * 60_000L)
        }

        captureJob = scope.launch {
            runCaptureLoop(durationMin, triggerName)
        }
        return START_STICKY
    }

    private suspend fun runCaptureLoop(durationMin: Int, triggerName: String) {
        val sessionId = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        currentSessionId = sessionId
        var vehicleName = "Fleet Vehicle"
        var detectedVin = ""
        var totalRows = 0
        val pendingRows = mutableListOf<JSONObject>()
        val dtcList = mutableListOf<String>()
        val rawReplies = JSONObject()
        var metaSent = false

        try {
            updateStatus("Scanning for IOS-Vlink ($triggerName)...")
            if (triggerName != "Manual") delay(4000)

            var connected = false
            for (attempt in 1..3) {
                if (!currentCoroutineContext().isActive) break
                updateStatus("Connecting to IOS-Vlink (attempt $attempt/3)...")
                if (ble.scanAndConnect(12000L)) {
                    connected = true
                    break
                }
                delay(3000)
            }

            if (!connected) {
                updateStatus("IOS-Vlink not found (car off or out of range)")
                return
            }
            isBleConnected = true

            updateStatus("Initializing ELM327 on CAN 500k...")
            if (!ble.initElm327()) {
                updateStatus("ELM327 init failed (no ECU reply)")
                return
            }

            // Auto-VIN
            val vinRaw = ble.sendCommand("0902", 3000)
            detectedVin = ObdDecoders.decodeVin(vinRaw).orEmpty()
            vehicleName = AppConfig.identifyFleetVehicle(detectedVin)
            currentVin = detectedVin
            currentVehicle = vehicleName
            rawReplies.put("0902", vinRaw)

            // Initial DTC sweep
            val d03 = ble.sendCommand("03", 2500)
            val d07 = ble.sendCommand("07", 2500)
            val d0a = ble.sendCommand("0A", 2500)
            rawReplies.put("03", d03)
            rawReplies.put("07", d07)
            rawReplies.put("0A", d0a)
            dtcList.addAll(ObdDecoders.decodeDtcs(d03, "43"))
            dtcList.addAll(ObdDecoders.decodeDtcs(d07, "47"))
            dtcList.addAll(ObdDecoders.decodeDtcs(d0a, "4A"))

            // Multi-ECU 0100 discovery snapshot
            ble.sendCommand("ATH1", 1000)
            rawReplies.put("0100_ATH1", ble.sendCommand("0100", 2500))
            rawReplies.put("0120_ATH1", ble.sendCommand("0120", 2500))
            ble.sendCommand("ATH0", 1000)

            db.upsertSession(sessionId, vehicleName, detectedVin, 0, dtcList.size)

            // Initial Hyundai Hybrid HV Battery BMS snapshot (7E4 -> 220101)
            if (detectedVin.startsWith("KMH")) {
                ble.sendCommand("ATSH7E4", 1000)
                ble.sendCommand("ATCRA7EC", 1000)
                val bmsRaw = ble.sendCommand("220101", 2500)
                ble.sendCommand("ATSH7DF", 1000)
                ble.sendCommand("ATCRA", 1000)
                rawReplies.put("7E4_220101", bmsRaw)
                ObdDecoders.decodeHyundaiHvBms(bmsRaw)?.let { hvBmsJson = it.toString() }
            }

            val endAtMs = System.currentTimeMillis() + durationMin * 60_000L
            var lastFlushMs = System.currentTimeMillis()
            var cycle = 0
            var lastCoolant: Double? = null
            var lastIntake: Double? = null
            var lastStft: Double? = null
            var lastLtft: Double? = null
            var lastVolt: Double? = null

            while (currentCoroutineContext().isActive && ble.isConnected && System.currentTimeMillis() < endAtMs) {
                val now = System.currentTimeMillis()
                val remSec = ((endAtMs - now) / 1000L).coerceAtLeast(0L)
                remainingSeconds = remSec
                val remMin = remSec / 60
                val remS = remSec % 60

                val rpm = ObdDecoders.decodeRpm(ble.sendCommand("010C", 1500))
                val spd = ObdDecoders.decodeSpeedMph(ble.sendCommand("010D", 1500))
                val load = ObdDecoders.decodeLoadPct(ble.sendCommand("0104", 1500))
                val thr = ObdDecoders.decodeThrottlePct(ble.sendCommand("0111", 1500))
                val maf = if ((rpm ?: 0.0) > 400.0) ObdDecoders.decodeMafGs(ble.sendCommand("0110", 1500)) else null

                if (cycle > 0 && cycle % 25 == 0 && detectedVin.startsWith("KMH")) {
                    ble.sendCommand("ATSH7E4", 800)
                    ble.sendCommand("ATCRA7EC", 800)
                    val bmsRaw = ble.sendCommand("220101", 2000)
                    ble.sendCommand("ATSH7DF", 800)
                    ble.sendCommand("ATCRA", 800)
                    ObdDecoders.decodeHyundaiHvBms(bmsRaw)?.let { hvBmsJson = it.toString() }
                }
                if (cycle % 5 == 0) {
                    lastCoolant = ObdDecoders.decodeCoolantF(ble.sendCommand("0105", 1500)) ?: lastCoolant
                    lastIntake = ObdDecoders.decodeIntakeF(ble.sendCommand("010F", 1500)) ?: lastIntake
                    lastStft = ObdDecoders.decodeFuelTrimPct(ble.sendCommand("0106", 1500), "06") ?: lastStft
                    lastLtft = ObdDecoders.decodeFuelTrimPct(ble.sendCommand("0107", 1500), "07") ?: lastLtft
                    lastVolt = ObdDecoders.decodeControlVoltage(ble.sendCommand("0142", 1500))
                        ?: ObdDecoders.decodeAtrv(ble.sendCommand("ATRV", 1500))
                        ?: lastVolt
                }

                val row = JSONObject().apply {
                    put("ts", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.format(Date()))
                    if (rpm != null) put("rpm", Math.round(rpm * 10.0) / 10.0)
                    if (spd != null) put("mph", Math.round(spd * 10.0) / 10.0)
                    if (load != null) put("load", Math.round(load * 10.0) / 10.0)
                    if (thr != null) put("throttle", Math.round(thr * 10.0) / 10.0)
                    if (maf != null) put("maf", Math.round(maf * 100.0) / 100.0)
                    lastCoolant?.let { put("coolantF", Math.round(it * 10.0) / 10.0) }
                    lastIntake?.let { put("intakeF", Math.round(it * 10.0) / 10.0) }
                    lastStft?.let { put("stft", Math.round(it * 10.0) / 10.0) }
                    lastLtft?.let { put("ltft", Math.round(it * 10.0) / 10.0) }
                    lastVolt?.let { put("volts", Math.round(it * 100.0) / 100.0) }
                }
                liveSnapshotJson = row.toString()
                pendingRows.add(row)
                totalRows++
                currentTotalRows = totalRows
                cycle++

                updateStatus(String.format(Locale.US, "Recording %s (%02d:%02d left) • %d rows", vehicleName, remMin, remS, totalRows))

                if (System.currentTimeMillis() - lastFlushMs >= 10_000L && pendingRows.isNotEmpty()) {
                    persistAndSyncBatch(
                        sessionId, vehicleName, detectedVin, triggerName,
                        pendingRows, !metaSent, if (!metaSent) dtcList else emptyList(),
                        if (!metaSent) rawReplies else null, totalRows, dtcList.size
                    )
                    metaSent = true
                    pendingRows.clear()
                    lastFlushMs = System.currentTimeMillis()
                }
            }

            if (pendingRows.isNotEmpty() || !metaSent) {
                persistAndSyncBatch(
                    sessionId, vehicleName, detectedVin, triggerName,
                    pendingRows, !metaSent, if (!metaSent) dtcList else emptyList(),
                    if (!metaSent) rawReplies else null, totalRows, dtcList.size
                )
                pendingRows.clear()
            }

            runCatching { ble.sendCommand("ATPC", 800) }
            updateStatus("Completed $vehicleName ($totalRows rows) — auto-disconnected")
        } finally {
            ble.disconnect()
            isBleConnected = false
            currentVin = ""
            currentVehicle = ""
            hvBmsJson = ""
            releaseWakeLock()
            isRunning = false
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private suspend fun persistAndSyncBatch(
        sessionId: String,
        vehicleName: String,
        vin: String,
        triggerName: String,
        rows: List<JSONObject>,
        includeMeta: Boolean,
        dtcs: List<String>,
        rawReplies: JSONObject?,
        totalRows: Int,
        totalDtcs: Int
    ) {
        val payload = JSONObject().apply {
            put("sessionId", sessionId)
            put("vehicle", vehicleName)
            put("rows", JSONArray(rows))
            if (includeMeta) {
                put("meta", JSONObject().apply {
                    put("device", "IOS-Vlink (Android APK)")
                    put("trigger", triggerName)
                    put("vin", vin)
                    put("vehicleName", vehicleName)
                })
            }
            if (dtcs.isNotEmpty()) put("dtcs", JSONArray(dtcs))
            if (rawReplies != null && rawReplies.length() > 0) put("rawReplies", rawReplies)
        }
        db.upsertSession(sessionId, vehicleName, vin, totalRows, totalDtcs)
        db.enqueueBatch(sessionId, payload.toString(), rows.size)
        DriveSyncManager.flushQueue(this, db)
    }

    private fun stopCaptureAndSelf(reason: String) {
        updateStatus(reason)
        captureJob?.cancel()
        ble.disconnect()
        isBleConnected = false
        releaseWakeLock()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun startForegroundCompat(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateStatus(text: String) {
        statusText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ObdCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OBD2 Pro Link")
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(isRunning)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "OBD2 Background Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Vgate iCar Pro BLE logging active while the screen is off"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(ch)
    }

    override fun onDestroy() {
        if (activeInstance == this) activeInstance = null
        captureJob?.cancel()
        scope.cancel()
        ble.disconnect()
        isBleConnected = false
        releaseWakeLock()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
