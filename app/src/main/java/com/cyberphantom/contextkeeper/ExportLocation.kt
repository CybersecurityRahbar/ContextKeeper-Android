package com.cyberphantom.contextkeeper

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object ExportLocation {
    private const val PREFS = "context_keeper"
    private const val KEY_TREE_URI = "export_tree_uri"

    fun get(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)?.let(Uri::parse)

    /** Backward-compatible save used by the current Activity. */
    fun save(context: Context, uri: Uri) {
        save(
            context,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    fun save(context: Context, uri: Uri, grantFlags: Int) {
        val persistable = grantFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (persistable == 0) {
            throw SecurityException("لم يمنح Android التطبيق صلاحية دائمة للمجلد المحدد")
        }
        context.contentResolver.takePersistableUriPermission(uri, persistable)
        val tree = DocumentFile.fromTreeUri(context, uri)
        if (tree == null || !tree.isDirectory || !tree.canWrite()) {
            throw SecurityException("المجلد المحدد لا يسمح بالكتابة")
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun isUsable(context: Context): Boolean {
        val uri = get(context) ?: return false
        return try {
            val tree = DocumentFile.fromTreeUri(context, uri)
            tree != null && tree.isDirectory && tree.canWrite()
        } catch (_: Exception) {
            false
        }
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TREE_URI).apply()
    }

    fun displayPath(context: Context): String {
        val uri = get(context) ?: return "غير محدد"
        return uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()
    }
}
