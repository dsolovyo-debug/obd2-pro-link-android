package com.dmitry.obd2prolink

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object DriveSyncManager {

    data class HealthResult(val ok: Boolean, val detail: String)

    suspend fun checkHealth(ctx: Context): HealthResult = withContext(Dispatchers.IO) {
        val endpoint = AppConfig.getDriveUrl(ctx)
        if (endpoint.isEmpty()) return@withContext HealthResult(false, "No Drive URL configured")
        try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 10000
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (code in 200..299 && json?.optString("status") == "ok") {
                val ver = json.optString("version", "1.0")
                HealthResult(true, "Verified OK (v$ver)")
            } else {
                val clean = body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(90)
                HealthResult(false, "Error HTTP $code: $clean")
            }
        } catch (e: Exception) {
            HealthResult(false, "Offline / Error: ${e.message}")
        }
    }

    suspend fun flushQueue(ctx: Context, db: ObdLocalDb): Pair<Int, String> = withContext(Dispatchers.IO) {
        val endpoint = AppConfig.getDriveUrl(ctx)
        if (endpoint.isEmpty()) return@withContext Pair(0, "No URL")

        var syncedBatches = 0
        val batches = db.getPendingBatches(50)
        for (batch in batches) {
            val ok = postJsonFollowingRedirects(endpoint, batch.payloadJson)
            if (ok) {
                db.markBatchSynced(batch)
                syncedBatches++
            } else {
                return@withContext Pair(syncedBatches, "Paused on network/Drive error (kept in SQLite)")
            }
        }
        Pair(syncedBatches, if (syncedBatches > 0) "Synced $syncedBatches batch(es)" else "Queue empty")
    }

    private fun postJsonFollowingRedirects(urlStr: String, bodyJson: String): Boolean {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Content-Type", "text/plain;charset=utf-8")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(bodyJson) }
            val code = conn.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) return false
                val follow = (URL(loc).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val fCode = follow.responseCode
                val resp = (if (fCode in 200..299) follow.inputStream else follow.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                follow.disconnect()
                val json = runCatching { JSONObject(resp) }.getOrNull()
                fCode in 200..299 && (json?.optString("status") == "ok" || json?.optString("status") == "empty")
            } else {
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                val json = runCatching { JSONObject(resp) }.getOrNull()
                code in 200..299 && (json?.optString("status") == "ok" || json?.optString("status") == "empty")
            }
        } catch (_: Exception) {
            false
        }
    }
}
