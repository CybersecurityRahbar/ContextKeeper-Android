package com.cyberphantom.contextkeeper

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Persistent store with per-session indexed deduplication and safe streaming-response merging. */
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

    fun insertOrTouch(
        sessionId: String,
        id: String,
        role: String,
        text: String,
        now: Long
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val exact = db.rawQuery(
                "SELECT id FROM messages WHERE session_id = ? AND id = ? LIMIT 1",
                arrayOf(sessionId, id)
            ).use { it.moveToFirst() }

            if (exact) {
                db.execSQL(
                    "UPDATE messages SET last_seen_at = ? WHERE session_id = ? AND id = ?",
                    arrayOf(now, sessionId, id)
                )
                db.setTransactionSuccessful()
                false
            } else {
                val merge = findRecentPrefixMatch(db, sessionId, role, text, now)
                if (merge != null) {
                    val mergedText = when {
                        normalizeForCompare(text).startsWith(normalizeForCompare(merge.text)) -> text
                        normalizeForCompare(merge.text).startsWith(normalizeForCompare(text)) -> merge.text
                        else -> null
                    }

                    if (mergedText != null) {
                        val mergedId = sha256(role + "\u0000" + mergedText)
                        if (mergedId == merge.id) {
                            db.execSQL(
                                "UPDATE messages SET last_seen_at = ? WHERE session_id = ? AND id = ?",
                                arrayOf(now, sessionId, merge.id)
                            )
                        } else {
                            val conflicting = db.rawQuery(
                                "SELECT id FROM messages WHERE session_id = ? AND id = ? LIMIT 1",
                                arrayOf(sessionId, mergedId)
                            ).use { it.moveToFirst() }
                            if (conflicting) {
                                db.execSQL(
                                    "UPDATE messages SET last_seen_at = ? WHERE session_id = ? AND id = ?",
                                    arrayOf(now, sessionId, mergedId)
                                )
                            } else {
                                db.execSQL(
                                    "UPDATE messages SET id = ?, text = ?, last_seen_at = ? WHERE session_id = ? AND id = ?",
                                    arrayOf(mergedId, mergedText, now, sessionId, merge.id)
                                )
                            }
                        }
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

    private fun insertNew(
        db: SQLiteDatabase,
        sessionId: String,
        id: String,
        role: String,
        text: String,
        now: Long
    ) {
        db.execSQL(
            "INSERT INTO messages(id, session_id, seq, role, text, first_seen_at, last_seen_at, source) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(id, sessionId, nextSequence(db, sessionId), role, text, now, now, "android-accessibility")
        )
    }

    /**
     * Only merge with a recent message. This is critical: a similar message
     * typed later in the conversation must not be swallowed as a scroll duplicate.
     */
    private fun findRecentPrefixMatch(
        db: SQLiteDatabase,
        sessionId: String,
        role: String,
        text: String,
        now: Long
    ): CapturedMessage? {
        val normalizedIncoming = normalizeForCompare(text)
        if (normalizedIncoming.length < 64) return null

        return db.rawQuery(
            "SELECT id, role, text, first_seen_at, last_seen_at, source FROM messages " +
                "WHERE session_id = ? AND role = ? AND last_seen_at >= ? " +
                "ORDER BY seq DESC LIMIT 20",
            arrayOf(sessionId, role, (now - MERGE_WINDOW_MS).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val candidate = CapturedMessage(
                    id = cursor.getString(0),
                    role = cursor.getString(1),
                    text = cursor.getString(2),
                    firstSeenAt = cursor.getLong(3),
                    lastSeenAt = cursor.getLong(4),
                    source = cursor.getString(5)
                )
                val existing = normalizeForCompare(candidate.text)
                if (existing.length >= 64 &&
                    (normalizedIncoming.startsWith(existing) || existing.startsWith(normalizedIncoming)) &&
                    kotlin.math.abs(normalizedIncoming.length - existing.length) >= MIN_GROWTH_CHARS
                ) {
                    return@use candidate
                }
            }
            null
        }
    }

    private fun normalizeForCompare(value: String): String = value
        .replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()

    fun count(sessionId: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE session_id = ?",
            arrayOf(sessionId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun readSession(sessionId: String, consumer: (CapturedMessage) -> Unit) {
        readableDatabase.rawQuery(
            "SELECT id, role, text, first_seen_at, last_seen_at, source FROM messages WHERE session_id = ? ORDER BY seq ASC",
            arrayOf(sessionId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                consumer(
                    CapturedMessage(
                        id = cursor.getString(0),
                        role = cursor.getString(1),
                        text = cursor.getString(2),
                        firstSeenAt = cursor.getLong(3),
                        lastSeenAt = cursor.getLong(4),
                        source = cursor.getString(5)
                    )
                )
            }
        }
    }

    private fun nextSequence(db: SQLiteDatabase, sessionId: String): Long =
        db.rawQuery(
            "SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE session_id = ?",
            arrayOf(sessionId)
        ).use { if (it.moveToFirst()) it.getLong(0) else 1L }

    private fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val DB_NAME = "capture.db"
        private const val DB_VERSION = 2
        private const val MERGE_WINDOW_MS = 15_000L
        private const val MIN_GROWTH_CHARS = 24
        @Volatile private var instance: CaptureDatabase? = null

        fun get(context: Context): CaptureDatabase =
            instance ?: synchronized(this) {
                instance ?: CaptureDatabase(context).also { instance = it }
            }
    }
}
