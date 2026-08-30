package com.cyberphantom.contextkeeper

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class ConversationAccessibilityService : AccessibilityService() {
    private var lastFingerprint = ""
    private var lastCaptureAt = 0L

    override fun onServiceConnected() { super.onServiceConnected() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !CaptureStore.isRecording(this)) return
        if (event.packageName?.toString() != "com.openai.chatgpt") return

        val now = System.currentTimeMillis()
        if (now - lastCaptureAt < 150L && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val root = rootInActiveWindow ?: return
        val segments = ConversationExtractor.extract(root)
        if (segments.isEmpty()) return

        val fingerprint = segments.takeLast(25).joinToString("\n") { it.role + ":" + it.text }
        if (fingerprint == lastFingerprint && event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        lastFingerprint = fingerprint
        lastCaptureAt = now

        for (segment in segments) CaptureStore.addOrUpdate(this, segment.role, segment.text)
    }

    override fun onInterrupt() = Unit
}
