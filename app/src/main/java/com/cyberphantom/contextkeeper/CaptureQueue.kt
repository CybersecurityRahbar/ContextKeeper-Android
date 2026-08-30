package com.cyberphantom.contextkeeper

import android.content.Context
import java.util.concurrent.Executors

/**
 * Serializes disk writes away from AccessibilityService callbacks.
 * A single writer preserves message order while preventing slow storage I/O
 * from delaying accessibility event processing.
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

    fun shutdown() {
        executor.shutdown()
    }
}
