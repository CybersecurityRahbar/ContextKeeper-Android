package com.cyberphantom.contextkeeper

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Serializes database writes away from AccessibilityService callbacks.
 * A single writer preserves capture order while preventing slow I/O from
 * blocking accessibility processing.
 */
object CaptureQueue {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ContextKeeper-CaptureWriter").apply { isDaemon = true }
    }

    fun enqueue(context: Context, sessionId: String, role: String, text: String) {
        val appContext = context.applicationContext
        executor.execute {
            CaptureStore.addOrUpdate(appContext, sessionId, role, text)
        }
    }

    /** Waits until all captures already queued have reached SQLite. */
    fun awaitIdle(timeoutMs: Long = 5_000L): Boolean {
        val barrier: Future<*> = executor.submit { }
        return try {
            barrier.get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (_: Exception) {
            false
        }
    }
}
