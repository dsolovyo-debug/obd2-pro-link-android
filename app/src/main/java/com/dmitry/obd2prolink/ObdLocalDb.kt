package com.dmitry.obd2prolink

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PendingBatch(
    val id: Long,
    val sessionId: String,
    val payloadJson: String,
    val rowCount: Int
)

data class SessionSummary(
    val sessionId: String,
    val vehicleName: String,
    val vin: String,
    val rowCount: Int,
    val dtcCount: Int,
    val syncedRows: Int
)

class ObdLocalDb(context: Context) : SQLiteOpenHelper(context, "obd2_pro_link.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                session_id TEXT PRIMARY KEY,
                started_at INTEGER NOT NULL,
                ended_at INTEGER DEFAULT 0,
                vehicle_name TEXT NOT NULL,
                vin TEXT DEFAULT '',
                row_count INTEGER DEFAULT 0,
                dtc_count INTEGER DEFAULT 0,
                synced_rows INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_batches (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                row_count INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun upsertSession(sessionId: String, vehicleName: String, vin: String, rowCount: Int, dtcCount: Int) {
        val cv = ContentValues().apply {
            put("session_id", sessionId)
            put("started_at", System.currentTimeMillis())
            put("vehicle_name", vehicleName)
            put("vin", vin)
            put("row_count", rowCount)
            put("dtc_count", dtcCount)
        }
        val updated = writableDatabase.update(
            "sessions",
            ContentValues().apply {
                put("vehicle_name", vehicleName)
                put("vin", vin)
                put("row_count", rowCount)
                put("dtc_count", dtcCount)
                put("ended_at", System.currentTimeMillis())
            },
            "session_id = ?",
            arrayOf(sessionId)
        )
        if (updated == 0) {
            writableDatabase.insert("sessions", null, cv)
        }
    }

    @Synchronized
    fun enqueueBatch(sessionId: String, payloadJson: String, rowCount: Int): Long {
        val cv = ContentValues().apply {
            put("session_id", sessionId)
            put("payload_json", payloadJson)
            put("row_count", rowCount)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("pending_batches", null, cv)
    }

    @Synchronized
    fun getPendingBatches(limit: Int = 25): List<PendingBatch> {
        val list = mutableListOf<PendingBatch>()
        readableDatabase.rawQuery(
            "SELECT id, session_id, payload_json, row_count FROM pending_batches ORDER BY id ASC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(PendingBatch(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3)))
            }
        }
        return list
    }

    @Synchronized
    fun markBatchSynced(batch: PendingBatch) {
        writableDatabase.delete("pending_batches", "id = ?", arrayOf(batch.id.toString()))
        writableDatabase.execSQL(
            "UPDATE sessions SET synced_rows = synced_rows + ? WHERE session_id = ?",
            arrayOf(batch.rowCount, batch.sessionId)
        )
    }

    @Synchronized
    fun countPendingBatches(): Pair<Int, Int> {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(row_count), 0) FROM pending_batches",
            null
        ).use { c ->
            return if (c.moveToFirst()) Pair(c.getInt(0), c.getInt(1)) else Pair(0, 0)
        }
    }

    @Synchronized
    fun getRecentSessions(limit: Int = 10): List<SessionSummary> {
        val list = mutableListOf<SessionSummary>()
        readableDatabase.rawQuery(
            "SELECT session_id, vehicle_name, vin, row_count, dtc_count, synced_rows FROM sessions ORDER BY started_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    SessionSummary(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getInt(3),
                        c.getInt(4),
                        c.getInt(5)
                    )
                )
            }
        }
        return list
    }
}
