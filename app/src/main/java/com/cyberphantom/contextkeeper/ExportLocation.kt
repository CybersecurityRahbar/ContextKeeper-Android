package com.cyberphantom.contextkeeper

import android.content.Context
import android.content.Intent
import android.net.Uri

object ExportLocation {
    private const val PREFS = "context_keeper"
    private const val KEY_TREE_URI = "export_tree_uri"

    fun get(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun save(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun clear(context: Context) {
        val old = get(context)
        if (old != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    old,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_TREE_URI).apply()
    }

    fun displayPath(context: Context): String {
        val uri = get(context) ?: return "غير محدد"
        return uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()
    }
}
