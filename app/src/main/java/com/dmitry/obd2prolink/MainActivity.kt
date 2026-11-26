package com.dmitry.obd2prolink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.ContentValues
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Base64
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var db: ObdLocalDb
    private lateinit var webView: WebView

    @Volatile private var lastDriveHealthOk = false
    @Volatile private var lastDriveHealthDetail = "Checking..."

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.count { it }
        Toast.makeText(this, "Granted $granted/${results.size} permissions", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = ObdLocalDb(this)

        // Fix Android 15 / Galaxy S25 edge-to-edge status & navigation bar padding
        val root = findViewById<FrameLayout>(R.id.rootContainer)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        webView = findViewById(R.id.webView)
        webView.setBackgroundColor(Color.parseColor("#0A0F1D"))
        val liveUrl = "https://dsolovyo-debug.github.io/obd2-pro-link/?t=" + (System.currentTimeMillis() / 60000L)
        val offlineFallbackUrl = "file:///android_asset/index.html"
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true && request.url.toString().startsWith("https://")) {
                    view?.loadUrl(offlineFallbackUrl)
                }
            }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.addJavascriptInterface(AndroidObdBridge(), "AndroidObd")
        webView.loadUrl(liveUrl)

        checkDriveHealthAndFlush()
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
            Toast.makeText(this, "Battery is already Unrestricted!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkDriveHealthAndFlush() {
        uiScope.launch {
            val health = DriveSyncManager.checkHealth(this@MainActivity)
            lastDriveHealthOk = health.ok
            lastDriveHealthDetail = health.detail
            if (health.ok) {
                DriveSyncManager.flushQueue(this@MainActivity, db)
            }
        }
    }

    inner class AndroidObdBridge {
        @JavascriptInterface
        fun isNative(): Boolean = true

        @JavascriptInterface
        fun startCapture() {
            runOnUiThread {
                requestRequiredPermissions()
                val intent = Intent(this@MainActivity, ObdCaptureService::class.java).apply {
                    action = ObdCaptureService.ACTION_START
                    putExtra("trigger_device", "Manual")
                }
                ContextCompat.startForegroundService(this@MainActivity, intent)
            }
        }

        @JavascriptInterface
        fun stopCapture() {
            runOnUiThread {
                val intent = Intent(this@MainActivity, ObdCaptureService::class.java).apply {
                    action = ObdCaptureService.ACTION_STOP
                }
                startService(intent)
            }
        }

        @JavascriptInterface
        fun requestPermissions() {
            runOnUiThread { requestRequiredPermissions() }
        }

        @JavascriptInterface
        fun openBatteryUnrestricted() {
            runOnUiThread { openBatteryUnrestrictedSettings() }
        }

        @JavascriptInterface
        fun flushNow() {
            checkDriveHealthAndFlush()
        }

        @JavascriptInterface
        fun saveNativeSettings(autoStart: Boolean, durationMin: Int, keywords: String, driveUrl: String) {
            AppConfig.setAutoStartEnabled(this@MainActivity, autoStart)
            AppConfig.setDurationMinutes(this@MainActivity, durationMin)
            AppConfig.setCarKeywords(this@MainActivity, keywords)
            AppConfig.setDriveUrl(this@MainActivity, driveUrl)
            checkDriveHealthAndFlush()
        }

        @JavascriptInterface
        fun sendCommand(cmd: String, callbackId: String) {
            uiScope.launch(Dispatchers.IO) {
                val reply = ObdCaptureService.sendLiveCommand(cmd)
                val safeReply = JSONObject.quote(reply)
                val safeId = JSONObject.quote(callbackId)
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("window.__onAndroidCmdReply($safeId, $safeReply);", null)
                }
            }
        }


        @JavascriptInterface
        fun savePdfBase64(fileName: String, base64Data: String) {
            uiScope.launch(Dispatchers.IO) {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val resolver = contentResolver
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Saved PDF to Downloads: $fileName", Toast.LENGTH_LONG).show()
                            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/pdf")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { startActivity(openIntent) }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "PDF save failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }


        @JavascriptInterface
        fun emailPdfBase64(fileName: String, base64Data: String, subject: String, body: String) {
            uiScope.launch(Dispatchers.IO) {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val resolver = contentResolver
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        withContext(Dispatchers.Main) {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_SUBJECT, subject)
                                putExtra(Intent.EXTRA_TEXT, body)
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            val chooser = Intent.createChooser(sendIntent, "Email Health Report PDF")
                            runCatching { startActivity(chooser) }.onFailure {
                                Toast.makeText(this@MainActivity, "No email app found (PDF saved to Downloads)", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Email PDF failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun getStateJson(): String {
            val (qBatches, qRows) = db.countPendingBatches()
            val sessionsArr = JSONArray()
            db.getRecentSessions(8).forEach { s ->
                sessionsArr.put(JSONObject().apply {
                    put("sessionId", s.sessionId)
                    put("vehicleName", s.vehicleName)
                    put("vin", s.vin)
                    put("rowCount", s.rowCount)
                    put("syncedRows", s.syncedRows)
                    put("dtcCount", s.dtcCount)
                })
            }
            val liveObj = runCatching { JSONObject(ObdCaptureService.liveSnapshotJson) }.getOrElse { JSONObject() }
            return JSONObject().apply {
                put("running", ObdCaptureService.isRunning)
                put("connected", ObdCaptureService.isBleConnected)
                put("statusText", ObdCaptureService.statusText)
                put("vehicleName", ObdCaptureService.currentVehicle)
                put("vin", ObdCaptureService.currentVin)
                put("remainingSec", ObdCaptureService.remainingSeconds)
                put("totalRows", ObdCaptureService.currentTotalRows)
                put("live", liveObj)
                if (ObdCaptureService.hvBmsJson.isNotEmpty()) {
                    runCatching { put("hvBms", JSONObject(ObdCaptureService.hvBmsJson)) }
                }
                put("bufferedBatches", qBatches)
                put("bufferedRows", qRows)
                put("driveHealth", JSONObject().apply {
                    put("ok", lastDriveHealthOk)
                    put("detail", lastDriveHealthDetail)
                })
                put("sessions", sessionsArr)
                put("settings", JSONObject().apply {
                    put("autoStart", AppConfig.isAutoStartEnabled(this@MainActivity))
                    put("durationMin", AppConfig.getDurationMinutes(this@MainActivity))
                    put("keywords", AppConfig.getCarKeywords(this@MainActivity).joinToString(","))
                    put("driveUrl", AppConfig.getDriveUrl(this@MainActivity))
                })
            }.toString()
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
