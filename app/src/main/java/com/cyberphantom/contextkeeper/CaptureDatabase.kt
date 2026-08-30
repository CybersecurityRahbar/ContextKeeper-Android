package com.cyberphantom.contextkeeper

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Persistent store with per-session ordering and conservative duplicate/stream merging. */
class CaptureDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) = createSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateToV2(db)
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                PRIMARY KEY(session_id, id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_session_seq ON messages(session_id, seq)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_session_recent ON messages(session_id, role, last_seen_at)")
    }

    private fun migrateToV2(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages_v2 (
                id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                PRIMARY KEY(session_id, id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO messages_v2
            (id, session_id, seq, role, text, first_seen_at, last_seen_at, source)
            SELECT id, session_id, seq, role, text, first_seen_at, last_seen_at, source
            FROM messages
            """.trimIndent()
        )
        db.execSQL("DROP TABLE messages")
        db.execSQL("ALTER TABLE messages_v2 RENAME TO messages")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_session_seq ON messages(session_id, seq)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_session_recent ON messages(session_id, role, last_seen_at)")
    }

    fun insertOrMerge(
        sessionId: String,
        id: String,
        role: String,
        text: String,
        now: Long
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val exactRecent = findRecentExact(db, sessionId, role, text, now)
            if (exactRecent != null) {
                db.execSQL(
                    "UPDATE messages SET last_seen_at = ? WHERE session_id = ? AND id = ?",
                    arrayOf(now, sessionId, exactRecent.id)
                )
                db.setTransactionSuccessful()
                false
            } else {
                val merge = findRecentPrefixMatch(db, sessionId, role, text, now)
                if (merge != null) {
                    val incoming = normalizeForCompare(text)
                    val existing = normalizeForCompare(merge.text)
                    val mergedText = when {
                        incoming.startsWith(existing) && incoming.length > existing.length -> text
                        existing.startsWith(incoming) && existing.length > incoming.length -> merge.text
                        else -> null
                    }
                    if (mergedText != null) {
                        db.execSQL(
                            "UPDATE messages SET text = ?, last_seen_at = ? WHERE session_id = ? AND id = ?",
                            arrayOf(mergedText, now, sessionId, merge.id)
                        )
                        db.setTransactionSuccessful()
                        true
                    } else {
                        insertNew(db, sessionId, id, role, text, now)
                        db.setTransactionSuccessful()
                        true
                    }
                } else {
                    insertNew(db, sessionId, id, role, text, now)
                    db.setTransactionSuccessful()
                    true
                }
            }
        } finally {
            db.endTransaction()
        }
    }

    private fun findRecentExact(
        db: SQLiteDatabase,
        sessionId: String,
        role: String,
        text: String,
        now: Long
    ): CapturedMessage? {
        return db.rawQuery(
            "SELECT id, role, text, first_seen_at, last_seen_at, source FROM messages " +
                "WHERE session_id = ? AND role = ? AND last_seen_at >= ? ORDER BY seq DESC LIMIT 30",
            arrayOf(sessionId, role, (now - EXACT_WINDOW_MS).toString())
        ).use { cursor ->
            val incoming = normalizeForCompare(text)
            while (cursor.moveToNext()) {
                val candidate = CapturedMessage(
                    id = cursor.getString(0), role = cursor.getString(1), text = cursor.getString(2),
                    firstSeenAt = cursor.getLong(3), lastSeenAt = cursor.getLong(4), source = cursor.getString(5)
                )
                if (normalizeForCompare(candidate.text) == incoming) return@use candidate
            }
            null
        }
    }

    private fun findRecentPrefixMatch(
        db: SQLiteDatabase,
        sessionId: String,
        role: String,
        text: String,
        now: Long
    ): CapturedMessage? {
        val incoming = normalizeForCompare(text)
        if (incoming.length < MIN_MERGE_TEXT) return null
        return db.rawQuery(
            "SELECT id, role, text, first_seen_at, last_seen_at, source FROM messages " +
                "WHERE session_id = ? AND role = ? AND last_seen_at >= ? ORDER BY seq DESC LIMIT 20",
            arrayOf(sessionId, role, (now - MERGE_WINDOW_MS).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val candidate = CapturedMessage(
                    id = cursor.getString(0), role = cursor.getString(1), text = cursor.getString(2),
                    firstSeenAt = cursor.getLong(3), lastSeenAt = cursor.getLong(4), source = cursor.getString(5)
                )
                val existing = normalizeForCompare(candidate.text)
                if (existing.length >= MIN_MERGE_TEXT &&
                    (incoming.startsWith(existing) || existing.startsWith(incoming)) &&
                    kotlin.math.abs(incoming.length - existing.length) >= MIN_GROWTH_CHARS
                ) return@use candidate
            }
            null
        }
    }

    private fun insertNew(db: SQLiteDatabase, sessionId: String, id: String, role: String, text: String, now: Long) {
        db.execSQL(
            "INSERT INTO messages(id, session_id, seq, role, text, first_seen_at, last_seen_at, source) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(id, sessionId, nextSequence(db, sessionId), role, text, now, now, "android-accessibility")
        )
    }

    private fun normalizeForCompare(value: String): String =
        value.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim().lowercase()

    fun count(sessionId: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE session_id = ?", arrayOf(sessionId))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun readSession(sessionId: String, consumer: (CapturedMessage) -> Unit) {
        readableDatabase.rawQuery(
            "SELECT id, role, text, first_seen_at, last_seen_at, source FROM messages WHERE session_id = ? ORDER BY seq ASC",
            arrayOf(sessionId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                consumer(CapturedMessage(
                    id = cursor.getString(0), role = cursor.getString(1), text = cursor.getString(2),
                    firstSeenAt = cursor.getLong(3), lastSeenAt = cursor.getLong(4), source = cursor.getString(5)
                ))
            }
        }
    }

    private fun nextSequence(db: SQLiteDatabase, sessionId: String): Long =
        db.rawQuery("SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE session_id = ?", arrayOf(sessionId))
            .use { if (it.moveToFirst()) it.getLong(0) else 1L }

    companion object {
        private const val DB_NAME = "capture.db"
        private const val DB_VERSION = 2
        private const val EXACT_WINDOW_MS = 5 * 60 * 1000L
        private const val MERGE_WINDOW_MS = 15_000L
        private const val MIN_MERGE_TEXT = 64
        private const val MIN_GROWTH_CHARS = 24
        @Volatile private var instance: CaptureDatabase? = null

        fun get(context: Context): CaptureDatabase =
            instance ?: synchronized(this) {
                instance ?: CaptureDatabase(context).also { instance = it }
            }
    }
}
