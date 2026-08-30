package com.cyberphantom.contextkeeper

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * High-reliability capture loop for ChatGPT's accessibility tree.
 * Each scroll/content change gets an immediate read plus delayed reads so the
 * service can observe the UI after ChatGPT finishes laying out the new range.
 */
class ConversationAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastSnapshotFingerprint = ""
    private var lastEventAt = 0L
    private var captureGeneration = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            notificationTimeout = 40
            eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !CaptureStore.isRecording(this)) return
        if (event.packageName?.toString() != CHATGPT_PACKAGE) return

        val now = System.currentTimeMillis()
        val isScroll = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        val isContent = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (isContent && now - lastEventAt < 35L) return
        lastEventAt = now

        captureNow()
        if (isScroll || isContent) {
            scheduleCapture(75L)
            scheduleCapture(180L)
        }
    }

    private fun scheduleCapture(delayMs: Long) {
        val generation = ++captureGeneration
        handler.postDelayed({
            if (generation <= captureGeneration && CaptureStore.isRecording(this)) captureNow()
        }, delayMs)
    }

    private fun captureNow() {
        val root = try { getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID) }
        catch (_: Throwable) { rootInActiveWindow }
        root ?: return

        val segments = ConversationExtractor.extract(root)
        if (segments.isEmpty()) return

        val snapshot = segments.joinToString("\n") { "${it.role}:${it.text}" }
        val fingerprint = CaptureFingerprint.sha256(snapshot)
        if (fingerprint == lastSnapshotFingerprint) return
        lastSnapshotFingerprint = fingerprint

        val sessionId = CaptureStore.currentSessionId(this)
        for (segment in segments) {
            CaptureQueue.enqueue(this, sessionId, segment.role, segment.text)
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
    }
}

object CaptureFingerprint {
    fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
