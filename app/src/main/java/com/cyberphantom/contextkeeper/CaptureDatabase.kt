package com.cyberphantom.contextkeeper

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Persistent append-oriented store with per-session indexed deduplication. */
class CaptureDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateToV2(db)
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY NOT NULL,
                session_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                source TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_session_seq ON messages(session_id, seq)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages(session_id, id)")
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
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages(session_id, id)")
    }

    fun insertOrTouch(sessionId: String, id: String, role: String, text: String, now: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val cursor = db.rawQuery(
                "SELECT id FROM messages WHERE session_id = ? AND id = ? LIMIT 1",
                arrayOf(sessionId, id)
            )
            val exists = cursor.use { it.moveToFirst() }
            if (exists) {
                db.execSQL(
                    "UPDATE messages SET last_seen_at = ? WHERE session_id = ? AND id = ?",
                    arrayOf(now, sessionId, id)
                )
            } else {
                val nextSeq = nextSequence(db, sessionId)
                db.execSQL(
                    "INSERT INTO messages(id, session_id, seq, role, text, first_seen_at, last_seen_at, source) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf(id, sessionId, nextSeq, role, text, now, now, "android-accessibility")
                )
            }
            db.setTransactionSuccessful()
            !exists
        } finally {
            db.endTransaction()
        }
    }

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

    companion object {
        private const val DB_NAME = "capture.db"
        private const val DB_VERSION = 2
        @Volatile private var instance: CaptureDatabase? = null

        fun get(context: Context): CaptureDatabase =
            instance ?: synchronized(this) {
                instance ?: CaptureDatabase(context).also { instance = it }
            }
    }
}
