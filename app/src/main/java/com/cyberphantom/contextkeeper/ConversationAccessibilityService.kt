package com.cyberphantom.contextkeeper

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Continuous capture service for ChatGPT plus an optional floating control. */
class ConversationAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastSnapshotFingerprint = ""
    private var lastSessionId = ""
    private var lastEventAt = 0L
    private var polling = false
    private var overlayView: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private var overlayReceiverRegistered = false

    private val overlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_OVERLAY_CHANGED) updateOverlay()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_OVERLAY_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(overlayReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(overlayReceiver, filter)
        }
        overlayReceiverRegistered = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            notificationTimeout = 20
            eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateOverlay()
        if (CaptureStore.isRecording(this)) startPollingIfNeeded()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != CHATGPT_PACKAGE) return
        if (!CaptureStore.isRecording(this)) {
            stopPolling()
            updateOverlayText()
            return
        }

        val now = System.currentTimeMillis()
        val isScroll = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        val isContent = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (isContent && now - lastEventAt < 15L) return
        lastEventAt = now

        captureNow()
        if (isScroll || isContent) {
            scheduleCapture(30L)
            scheduleCapture(75L)
            scheduleCapture(140L)
            scheduleCapture(240L)
        }
        updateOverlayText()
        startPollingIfNeeded()
    }

    private fun scheduleCapture(delayMs: Long) {
        handler.postDelayed({ if (CaptureStore.isRecording(this)) captureNow() }, delayMs)
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
                updateOverlayText()
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

    private fun captureNow() {
        val root = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
            } else rootInActiveWindow
        } catch (_: Throwable) {
            rootInActiveWindow
        } ?: return

        try {
            if (root.packageName?.toString() != CHATGPT_PACKAGE) return
            val segments = ConversationExtractor.extract(root)
            if (segments.isEmpty()) return

            val sessionId = CaptureStore.currentSessionId(this)
            if (sessionId != lastSessionId) {
                lastSessionId = sessionId
                lastSnapshotFingerprint = ""
            }

            val fingerprint = CaptureFingerprint.sha256(
                segments.joinToString("\n\u0000") { "${it.role}:${it.text}" }
            )
            if (fingerprint == lastSnapshotFingerprint) return
            lastSnapshotFingerprint = fingerprint

            for (segment in segments) {
                CaptureQueue.enqueue(this, sessionId, segment.role, segment.text)
            }
        } catch (_: Throwable) {
            // A malformed/in-flight accessibility tree must not terminate the service.
        } finally {
            try { root.recycle() } catch (_: Throwable) { }
        }
    }

    private fun updateOverlay() {
        if (CaptureStore.isOverlayEnabled(this)) showOverlay() else hideOverlay()
        updateOverlayText()
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val wm = windowManager ?: return
        val size = (52 * resources.displayMetrics.density).toInt()
        val bubble = TextView(this).apply {
            text = if (CaptureStore.isRecording(this@ConversationAccessibilityService)) "●" else "▶"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.rgb(32, 33, 36))
            contentDescription = "Context Keeper: بدء أو إيقاف الالتقاط"
            isClickable = true
            isFocusable = true

            setOnClickListener {
                val newValue = !CaptureStore.isRecording(this@ConversationAccessibilityService)
                CaptureStore.setRecording(this@ConversationAccessibilityService, newValue)
                if (newValue) {
                    CaptureStore.newSession(this@ConversationAccessibilityService)
                    lastSessionId = CaptureStore.currentSessionId(this@ConversationAccessibilityService)
                    lastSnapshotFingerprint = ""
                    startPollingIfNeeded()
                    captureNow()
                    Toast.makeText(this@ConversationAccessibilityService, "بدأ الالتقاط", Toast.LENGTH_SHORT).show()
                } else {
                    stopPolling()
                    Toast.makeText(this@ConversationAccessibilityService, "توقف الالتقاط", Toast.LENGTH_SHORT).show()
                }
                updateOverlayText()
            }

            setOnLongClickListener {
                CaptureStore.newSession(this@ConversationAccessibilityService)
                lastSessionId = CaptureStore.currentSessionId(this@ConversationAccessibilityService)
                lastSnapshotFingerprint = ""
                if (CaptureStore.isRecording(this@ConversationAccessibilityService)) captureNow()
                Toast.makeText(this@ConversationAccessibilityService, "جلسة جديدة", Toast.LENGTH_SHORT).show()
                true
            }

            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moving = false
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = overlayParams?.x ?: 0
                        startY = overlayParams?.y ?: 0
                        moving = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                            moving = true
                            overlayParams?.let {
                                it.x = startX + dx
                                it.y = startY + dy
                                try { wm.updateViewLayout(view, it) } catch (_: Throwable) { }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> moving
                    else -> false
                }
            }
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * resources.displayMetrics.density).toInt()
            y = (140 * resources.displayMetrics.density).toInt()
        }

        try {
            wm.addView(bubble, params)
            overlayView = bubble
            overlayParams = params
        } catch (_: Throwable) {
            overlayView = null
            overlayParams = null
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        try { windowManager?.removeView(view) } catch (_: Throwable) { }
        overlayView = null
        overlayParams = null
    }

    private fun updateOverlayText() {
        overlayView?.text = if (CaptureStore.isRecording(this)) "●" else "▶"
    }

    override fun onInterrupt() {
        stopPolling()
        hideOverlay()
    }

    override fun onDestroy() {
        stopPolling()
        hideOverlay()
        if (overlayReceiverRegistered) {
            try { unregisterReceiver(overlayReceiver) } catch (_: Throwable) { }
            overlayReceiverRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        private const val POLL_INTERVAL_MS = 75L
        private const val ACTION_OVERLAY_CHANGED = "com.cyberphantom.contextkeeper.ACTION_OVERLAY_CHANGED"
    }
}

object CaptureFingerprint {
    fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
