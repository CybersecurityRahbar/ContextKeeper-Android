package com.cyberphantom.contextkeeper

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStreamWriter
import java.security.MessageDigest

/** Thread-safe facade over SQLite and user-selected document storage. */
object CaptureStore {
    private const val PREFS = "context_keeper"
    private const val SESSION_ID = "session_id"
    private const val RECORDING = "recording"
    private const val OVERLAY_ENABLED = "overlay_enabled"
    private const val MIN_TEXT_LENGTH = 2

    @Synchronized
    fun isRecording(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(RECORDING, false)

    @Synchronized
    fun setRecording(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(RECORDING, value).apply()
    }

    @Synchronized
    fun isOverlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(OVERLAY_ENABLED, false)

    @Synchronized
    fun setOverlayEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(OVERLAY_ENABLED, value).apply()
    }

    @Synchronized
    fun currentSessionId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(SESSION_ID, null) ?: newSession(context)
    }

    @Synchronized
    fun newSession(context: Context): String {
        val id = "session-" + System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(SESSION_ID, id).apply()
        return id
    }

    fun addOrUpdate(context: Context, role: String, text: String): Boolean =
        if (isRecording(context)) addCaptured(context, currentSessionId(context), role, text) else false

    @Synchronized
    internal fun addOrUpdate(context: Context, sessionId: String, role: String, text: String): Boolean =
        addCaptured(context, sessionId, role, text)

    @Synchronized
    private fun addCaptured(context: Context, sessionId: String, role: String, text: String): Boolean {
        val clean = normalize(text)
        if (clean.length < MIN_TEXT_LENGTH) return false
        val id = sha256(role + "\u0000" + clean)
        return CaptureDatabase.get(context).insertOrTouch(
            sessionId = sessionId,
            id = id,
            role = role,
            text = clean,
            now = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun messageCount(context: Context): Int =
        CaptureDatabase.get(context).count(currentSessionId(context))

    fun exportMarkdownTo(context: Context, treeUri: Uri): Uri {
        return exportToDocument(context, treeUri, "text/markdown", "md") { writer, session ->
            writer.appendLine("# Chat Context")
            writer.appendLine()
            writer.appendLine("Session: $session")
            writer.appendLine("Generated: ${System.currentTimeMillis()}")
            writer.appendLine()
            CaptureDatabase.get(context).readSession(session) { message ->
                writer.appendLine("## ${message.role}")
                writer.appendLine()
                writer.appendLine(message.text)
                writer.appendLine()
                writer.appendLine("---")
                writer.appendLine()
            }
        }
    }

    fun exportJsonTo(context: Context, treeUri: Uri): Uri {
        return exportToDocument(context, treeUri, "application/json", "json") { writer, session ->
            writer.append('[')
            var first = true
            CaptureDatabase.get(context).readSession(session) { message ->
                if (!first) writer.append(',')
                writer.append('\n')
                writer.append(message.toJson().toString())
                first = false
            }
            if (!first) writer.append('\n')
            writer.append(']')
        }
    }

    private fun exportToDocument(
        context: Context,
        treeUri: Uri,
        mimeType: String,
        extension: String,
        writeContent: (OutputStreamWriter, String) -> Unit
    ): Uri {
        val session = currentSessionId(context)
        val name = "$session.$extension"
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("المجلد المحدد غير صالح أو لم يعد متاحًا")
        if (!tree.isDirectory || !tree.canWrite()) {
            throw IllegalStateException("لا يمكن الكتابة إلى المجلد المحدد. اختر مجلدًا آخر.")
        }

        val existing = tree.findFile(name)
        val target = existing ?: tree.createFile(mimeType, name)
            ?: throw IllegalStateException("تعذر إنشاء الملف داخل المجلد المحدد")

        context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                writeContent(writer, session)
                writer.flush()
            }
        } ?: throw IllegalStateException("تعذر فتح الملف للكتابة")

        return target.uri
    }

    private fun normalize(value: String): String =
        value.replace("\u00A0", " ").trim()

    private fun sha256(value: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
