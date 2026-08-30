package com.cyberphantom.contextkeeper

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

object CaptureStore {
    private const val PREFS = "context_keeper"
    private const val SESSION_ID = "session_id"
    private const val RECORDING = "recording"

    @Synchronized
    fun isRecording(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(RECORDING, false)

    @Synchronized
    fun setRecording(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(RECORDING, value).apply()
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

    @Synchronized
    fun addOrUpdate(context: Context, role: String, text: String) {
        if (!isRecording(context)) return
        val clean = normalize(text)
        if (clean.isBlank()) return

        val session = currentSessionId(context)
        val file = sessionFile(context, session)
        val all = readJson(file)
        val now = System.currentTimeMillis()
        val id = sha256(role + "\u0000" + clean)

        var found = false
        for (i in 0 until all.length()) {
            val obj = all.optJSONObject(i) ?: continue
            if (obj.optString("id") == id) {
                obj.put("lastSeenAt", now)
                found = true
                break
            }
        }
        if (!found) all.put(CapturedMessage(id, role, clean, now, now).toJson())
        atomicWrite(file, all.toString())
    }

    @Synchronized
    fun exportMarkdown(context: Context): File {
        val session = currentSessionId(context)
        val json = readJson(sessionFile(context, session))
        val outDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "exports")
        outDir.mkdirs()
        val out = File(outDir, "$session.md")
        val sb = StringBuilder()
        sb.append("# Chat Context\n\n")
        sb.append("Session: ").append(session).append("\n")
        sb.append("Generated: ").append(System.currentTimeMillis()).append("\n\n")
        for (i in 0 until json.length()) {
            val obj = json.optJSONObject(i) ?: continue
            sb.append("## ").append(obj.optString("role", "unknown")).append("\n\n")
            sb.append(obj.optString("text")).append("\n\n---\n\n")
        }
        out.writeText(sb.toString(), Charsets.UTF_8)
        return out
    }

    @Synchronized
    fun exportJson(context: Context): File {
        val session = currentSessionId(context)
        val outDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "exports")
        outDir.mkdirs()
        val out = File(outDir, "$session.json")
        out.writeText(readJson(sessionFile(context, session)).toString(2), Charsets.UTF_8)
        return out
    }

    @Synchronized
    fun messageCount(context: Context): Int =
        readJson(sessionFile(context, currentSessionId(context))).length()

    private fun sessionFile(context: Context, session: String): File {
        val dir = File(context.filesDir, "sessions")
        dir.mkdirs()
        return File(dir, "$session.json")
    }

    private fun readJson(file: File): JSONArray =
        if (!file.exists()) JSONArray() else try { JSONArray(file.readText(Charsets.UTF_8)) } catch (_: Exception) { JSONArray() }

    private fun atomicWrite(file: File, data: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(data, Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.writeText(data, Charsets.UTF_8); tmp.delete() }
    }

    private fun normalize(value: String): String = value
        .replace("\u00A0", " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\\n\\n")
        .trim()

    private fun sha256(value: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
