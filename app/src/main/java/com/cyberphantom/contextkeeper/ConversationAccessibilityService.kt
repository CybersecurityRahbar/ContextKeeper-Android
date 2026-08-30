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
import androidx.core.content.ContextCompat

/** Responsive background capture service. Heavy tree processing never runs on the service main thread. */
class ConversationAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var processing: CaptureProcessingQueue
    private var polling = false
    private var overlayView: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private var overlayReceiverRegistered = false
    private var lastEventAt = 0L

    private val overlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_OVERLAY_CHANGED) {
                if (CaptureStore.isOverlayEnabled(this@ConversationAccessibilityService)) showOverlay()
                else hideOverlay()
                updateOverlayText()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        processing = CaptureProcessingQueue(this)
        ContextCompat.registerReceiver(
            this,
            overlayReceiver,
            IntentFilter(ACTION_OVERLAY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        overlayReceiverRegistered = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            notificationTimeout = 10
            eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (CaptureStore.isOverlayEnabled(this)) showOverlay()
        updateOverlayText()
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
        val isContent = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (isContent && now - lastEventAt < 12L) return
        lastEventAt = now

        submitSnapshot(0L)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED || isContent) {
            submitSnapshot(25L)
            submitSnapshot(60L)
            submitSnapshot(120L)
            submitSnapshot(200L)
        }
        startPollingIfNeeded()
    }

    private fun submitSnapshot(delayMs: Long) {
        handler.postDelayed({
            if (!CaptureStore.isRecording(this)) return@postDelayed
            captureSnapshot()
        }, delayMs)
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
            captureSnapshot()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
        updateOverlayText()
    }

    /** Root acquisition is kept brief; traversal/extraction/database work is queued off-thread. */
    private fun captureSnapshot() {
        val root = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
            } else {
                rootInActiveWindow
            }
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            if (root.packageName?.toString() != CHATGPT_PACKAGE) {
                root.recycle()
                return
            }
            val sessionId = CaptureStore.currentSessionId(this)
            processing.submit(root, sessionId)
        } catch (_: Throwable) {
            runCatching { root.recycle() }
        }
    }

    private fun toggleRecordingFromOverlay() {
        val wasRecording = CaptureStore.isRecording(this)
        CaptureStore.setRecording(this, !wasRecording)
        if (wasRecording) {
            stopPolling()
            Toast.makeText(this, "توقف الالتقاط — البيانات محفوظة", Toast.LENGTH_SHORT).show()
        } else {
            startPollingIfNeeded()
            captureSnapshot()
            Toast.makeText(this, "استئناف الالتقاط", Toast.LENGTH_SHORT).show()
        }
        updateOverlayText()
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val wm = windowManager ?: return
        val size = (52 * resources.displayMetrics.density).toInt()
        val bubble = TextView(this).apply {
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.rgb(32, 33, 36))
            contentDescription = "Context Keeper: بدء أو إيقاف الالتقاط"
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleRecordingFromOverlay() }
            setOnLongClickListener {
                CaptureStore.newSession(this@ConversationAccessibilityService)
                if (CaptureStore.isRecording(this@ConversationAccessibilityService)) captureSnapshot()
                Toast.makeText(this@ConversationAccessibilityService, "جلسة جديدة — البيانات السابقة محفوظة", Toast.LENGTH_SHORT).show()
                true
            }

            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moving = false
            setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = overlayParams?.x ?: 0; startY = overlayParams?.y ?: 0
                        moving = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                            moving = true
                            overlayParams?.let {
                                it.x = startX + dx
                                it.y = startY + dy
                                runCatching { wm.updateViewLayout(view, it) }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> if (!moving) view.performClick() else true
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
        runCatching { windowManager?.removeView(view) }
        overlayView = null
        overlayParams = null
    }

    private fun updateOverlayText() {
        overlayView?.text = if (CaptureStore.isRecording(this)) "●" else "▶"
    }

    override fun onInterrupt() {
        // Preserve the recording/session state. System interruption must not erase data.
        stopPolling()
    }

    override fun onDestroy() {
        stopPolling()
        hideOverlay()
        processing.shutdown()
        if (overlayReceiverRegistered) runCatching { unregisterReceiver(overlayReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        private const val POLL_INTERVAL_MS = 75L
        private const val ACTION_OVERLAY_CHANGED = "com.cyberphantom.contextkeeper.ACTION_OVERLAY_CHANGED"
    }
}
