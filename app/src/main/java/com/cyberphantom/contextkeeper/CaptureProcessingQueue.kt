package com.cyberphantom.contextkeeper

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Background pipeline: tree traversal/extraction happens off the service main thread. */
class CaptureProcessingQueue(context: Context) {
    private val appContext = context.applicationContext
    private val workers = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "ContextKeeper-Snapshot-${System.nanoTime()}").apply { isDaemon = true }
    }
    private val slots = Semaphore(8)

    fun submit(root: AccessibilityNodeInfo, sessionId: String) {
        if (!slots.tryAcquire()) {
            runCatching { root.recycle() }
            return
        }
        workers.execute {
            try {
                val segments = ConversationExtractor.extract(root)
                for (segment in segments) {
                    CaptureQueue.enqueue(appContext, sessionId, segment.role, segment.text)
                }
            } catch (_: Throwable) {
                // Never allow a transient accessibility-tree failure to kill the service.
            } finally {
                runCatching { root.recycle() }
                slots.release()
            }
        }
    }

    fun shutdown() {
        workers.shutdownNow()
    }
}
