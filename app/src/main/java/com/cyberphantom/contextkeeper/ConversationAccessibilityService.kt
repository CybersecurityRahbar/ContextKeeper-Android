package com.cyberphantom.contextkeeper

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * High-reliability capture loop.
 *
 * Accessibility callbacks are event-driven, so fast scrolling can produce UI
 * states between callbacks. While recording, a polling loop also samples the
 * active window, while scroll/content events trigger immediate and delayed
 * snapshots.
 */
class ConversationAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastSnapshotFingerprint = ""
    private var lastEventAt = 0L
    private var polling = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            notificationTimeout = 20
            eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
        }
        startPollingIfNeeded()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !CaptureStore.isRecording(this)) {
            if (!CaptureStore.isRecording(this)) stopPolling()
            return
        }
        if (event.packageName?.toString() != CHATGPT_PACKAGE) return

        val now = System.currentTimeMillis()
        val isScroll = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        val isContent = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (isContent && now - lastEventAt < 20L) return
        lastEventAt = now

        captureNow()
        if (isScroll || isContent) {
            handler.postDelayed({ captureIfRecording() }, 45L)
            handler.postDelayed({ captureIfRecording() }, 110L)
            handler.postDelayed({ captureIfRecording() }, 220L)
        }
        startPollingIfNeeded()
    }

    private fun startPollingIfNeeded() {
        if (polling || !CaptureStore.isRecording(this)) return
        polling = true
        handler.post(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!CaptureStore.isRecording(this@ConversationAccessibilityService)) {
                polling = false
                return
            }
            captureNow()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun captureIfRecording() {
        if (CaptureStore.isRecording(this)) captureNow()
    }

    private fun captureNow() {
        val root = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
            } else {
                rootInActiveWindow
            }
        } catch (_: Throwable) {
            rootInActiveWindow
        } ?: return

        try {
            val segments = ConversationExtractor.extract(root)
            if (segments.isEmpty()) return

            val fingerprint = CaptureFingerprint.sha256(
                segments.joinToString("\n\u0000") { "${it.role}:${it.text}" }
            )
            if (fingerprint == lastSnapshotFingerprint) return
            lastSnapshotFingerprint = fingerprint

            val sessionId = CaptureStore.currentSessionId(this)
            for (segment in segments) {
                CaptureQueue.enqueue(this, sessionId, segment.role, segment.text)
            }
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() {
        stopPolling()
    }

    override fun onDestroy() {
        stopPolling()
        super.onDestroy()
    }

    companion object {
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        private const val POLL_INTERVAL_MS = 75L
    }
}

object CaptureFingerprint {
    fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
