package com.dmitry.obd2prolink

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var db: ObdLocalDb

    private lateinit var txtServiceStatus: TextView
    private lateinit var txtLiveStats: TextView
    private lateinit var txtDriveHealth: TextView
    private lateinit var txtSessions: TextView
    private lateinit var switchAutoStart: Switch
    private lateinit var edtDuration: EditText
    private lateinit var edtKeywords: EditText
    private lateinit var edtDriveUrl: EditText

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.count { it }
        Toast.makeText(this, "Granted $granted/${results.size} permissions", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = ObdLocalDb(this)

        txtServiceStatus = findViewById(R.id.txtServiceStatus)
        txtLiveStats = findViewById(R.id.txtLiveStats)
        txtDriveHealth = findViewById(R.id.txtDriveHealth)
        txtSessions = findViewById(R.id.txtSessions)
        switchAutoStart = findViewById(R.id.switchAutoStart)
        edtDuration = findViewById(R.id.edtDurationMinutes)
        edtKeywords = findViewById(R.id.edtCarKeywords)
        edtDriveUrl = findViewById(R.id.edtDriveUrl)

        // Load saved preferences
        switchAutoStart.isChecked = AppConfig.isAutoStartEnabled(this)
        edtDuration.setText(AppConfig.getDurationMinutes(this).toString())
        edtKeywords.setText(AppConfig.getCarKeywords(this).joinToString(","))
        edtDriveUrl.setText(AppConfig.getDriveUrl(this))

        findViewById<Button>(R.id.btnStartCapture).setOnClickListener {
            requestRequiredPermissions()
            val intent = Intent(this, ObdCaptureService::class.java).apply {
                action = ObdCaptureService.ACTION_START
                putExtra("trigger_device", "Manual")
            }
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Starting background capture...", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStopCapture).setOnClickListener {
            val intent = Intent(this, ObdCaptureService::class.java).apply {
                action = ObdCaptureService.ACTION_STOP
            }
            startService(intent)
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            AppConfig.setAutoStartEnabled(this, switchAutoStart.isChecked)
            AppConfig.setDurationMinutes(this, edtDuration.text.toString().toIntOrNull() ?: 20)
            AppConfig.setCarKeywords(this, edtKeywords.text.toString())
            AppConfig.setDriveUrl(this, edtDriveUrl.text.toString())
            Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show()
            checkDriveHealthAndFlush()
        }

        findViewById<Button>(R.id.btnFlushQueue).setOnClickListener {
            checkDriveHealthAndFlush()
        }

        findViewById<Button>(R.id.btnPermissions).setOnClickListener {
            requestRequiredPermissions()
        }

        findViewById<Button>(R.id.btnBatteryUnrestricted).setOnClickListener {
            openBatteryUnrestrictedSettings()
        }

        checkDriveHealthAndFlush()
        startUiRefreshTicker()
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
    }

    private fun openBatteryUnrestrictedSettings() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            runCatching { startActivity(intent) }.onFailure {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } else {
            Toast.makeText(this, "Battery is already Unrestricted for OBD2 Pro Link!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkDriveHealthAndFlush() {
        uiScope.launch {
            txtDriveHealth.text = "Drive Sync: Checking endpoint..."
            val health = DriveSyncManager.checkHealth(this@MainActivity)
            if (health.ok) {
                val (flushed, _) = DriveSyncManager.flushQueue(this@MainActivity, db)
                val (remBatches, remRows) = db.countPendingBatches()
                txtDriveHealth.text = "Drive Sync: ${health.detail} | Flushed: $flushed | Queued: $remBatches ($remRows rows)"
            } else {
                val (remBatches, remRows) = db.countPendingBatches()
                txtDriveHealth.text = "Drive Sync: ${health.detail} | Buffered locally: $remBatches ($remRows rows)"
            }
            refreshSessionsList()
        }
    }

    private fun startUiRefreshTicker() {
        uiScope.launch {
            while (isActive) {
                txtServiceStatus.text = "Status: ${ObdCaptureService.statusText}"
                txtLiveStats.text = ObdCaptureService.liveSummary
                refreshSessionsList()
                delay(1500)
            }
        }
    }

    private fun refreshSessionsList() {
        val sessions = db.getRecentSessions(8)
        val (qB, qR) = db.countPendingBatches()
        if (sessions.isEmpty()) {
            txtSessions.text = "No captured sessions yet. Local buffer: $qB batches ($qR rows)."
            return
        }
        val sb = StringBuilder()
        if (qB > 0) sb.append("Pending offline upload: $qB batch(es) ($qR rows)\n\n")
        sessions.forEach { s ->
            sb.append("${s.sessionId} • ${s.vehicleName}\n")
            sb.append("  VIN: ${s.vin.ifEmpty { "--" }} | Rows: ${s.rowCount} (Synced: ${s.syncedRows}) | DTCs: ${s.dtcCount}\n")
        }
        txtSessions.text = sb.toString().trim()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
