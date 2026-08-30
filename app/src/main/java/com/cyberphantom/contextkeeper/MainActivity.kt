package com.cyberphantom.contextkeeper

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var exportPath: TextView
    private lateinit var recordingToggle: Switch
    private lateinit var overlayToggle: Switch
    private val exportExecutor = Executors.newSingleThreadExecutor()
    private var pendingExport: PendingExport? = null

    private enum class PendingExport { MARKDOWN, JSON }

    companion object {
        private const val REQUEST_EXPORT_FOLDER = 4101
        private const val ACTION_OVERLAY_CHANGED = "com.cyberphantom.contextkeeper.ACTION_OVERLAY_CHANGED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        exportExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }
        setContentView(root)

        root.addView(TextView(this).apply { text = "Context Keeper"; textSize = 28f }, lp())
        root.addView(TextView(this).apply {
            text = "يلتقط نص ChatGPT أثناء الظهور والتمرير، ويحفظه تدريجيًا دون تكرار."
            textSize = 16f
        }, lp())
        status = TextView(this).apply { textSize = 16f }
        root.addView(status, lp())
        count = TextView(this).apply { textSize = 16f }
        root.addView(count, lp())
        exportPath = TextView(this).apply { textSize = 14f }
        root.addView(exportPath, lp())

        recordingToggle = Switch(this).apply { text = "تشغيل التسجيل" }
        root.addView(recordingToggle, lp())
        recordingToggle.setOnCheckedChangeListener { _, checked ->
            CaptureStore.setRecording(this@MainActivity, checked)
            if (checked) CaptureStore.newSession(this@MainActivity)
            refresh()
        }

        overlayToggle = Switch(this).apply { text = "إظهار الزر العائم" }
        root.addView(overlayToggle, lp())
        overlayToggle.setOnCheckedChangeListener { _, checked ->
            CaptureStore.setOverlayEnabled(this@MainActivity, checked)
            sendOverlayChanged()
            refresh()
        }

        root.addView(Button(this).apply {
            text = "اختيار مجلد حفظ الملفات"
            setOnClickListener { chooseExportFolder() }
        }, lp())
        root.addView(Button(this).apply {
            text = "فتح إعدادات إمكانية الوصول"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, lp())
        root.addView(Button(this).apply {
            text = "تصدير Markdown + نسخ"
            setOnClickListener { exportMarkdown() }
        }, lp())
        root.addView(Button(this).apply {
            text = "تصدير JSON"
            setOnClickListener { exportJson() }
        }, lp())
        root.addView(Button(this).apply {
            text = "جلسة جديدة"
            setOnClickListener {
                CaptureStore.newSession(this@MainActivity)
                sendOverlayChanged()
                refresh()
            }
        }, lp())
    }

    private fun refresh() {
        status.text = if (isAccessibilityServiceEnabled()) "خدمة الالتقاط: مفعلة" else "خدمة الالتقاط: غير مفعلة"
        count.text = "الرسائل المحفوظة: ${CaptureStore.messageCount(this)}"
        exportPath.text = "مجلد التصدير: ${ExportLocation.displayPath(this)}"

        recordingToggle.setOnCheckedChangeListener(null)
        recordingToggle.isChecked = CaptureStore.isRecording(this)
        recordingToggle.setOnCheckedChangeListener { _, checked ->
            CaptureStore.setRecording(this@MainActivity, checked)
            if (checked) CaptureStore.newSession(this@MainActivity)
            refresh()
        }

        overlayToggle.setOnCheckedChangeListener(null)
        overlayToggle.isChecked = CaptureStore.isOverlayEnabled(this)
        overlayToggle.setOnCheckedChangeListener { _, checked ->
            CaptureStore.setOverlayEnabled(this@MainActivity, checked)
            sendOverlayChanged()
            refresh()
        }
    }

    private fun sendOverlayChanged() {
        sendBroadcast(Intent(ACTION_OVERLAY_CHANGED).setPackage(packageName))
    }

    private fun chooseExportFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_EXPORT_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            ExportLocation.save(this, uri)
            refresh()
            pendingExport?.let {
                pendingExport = null
                when (it) {
                    PendingExport.MARKDOWN -> exportMarkdown()
                    PendingExport.JSON -> exportJson()
                }
            }
        } catch (error: Exception) {
            Toast.makeText(this, "تعذر حفظ مجلد التصدير: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun ensureExportFolder(kind: PendingExport): Uri? {
        val uri = ExportLocation.get(this)
        if (uri != null) return uri
        pendingExport = kind
        Toast.makeText(this, "اختر مجلدًا لحفظ ملفات السياق، مثل Downloads", Toast.LENGTH_LONG).show()
        chooseExportFolder()
        return null
    }

    private fun exportMarkdown() {
        val treeUri = ensureExportFolder(PendingExport.MARKDOWN) ?: return
        runExport("Markdown") {
            val uri = CaptureStore.exportMarkdownTo(this@MainActivity, treeUri)
            val text = readDocument(uri)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("conversation", text))
            "تم حفظ Markdown ونسخه (${text.length} حرف)"
        }
    }

    private fun exportJson() {
        val treeUri = ensureExportFolder(PendingExport.JSON) ?: return
        runExport("JSON") {
            CaptureStore.exportJsonTo(this@MainActivity, treeUri)
            "تم حفظ JSON في مجلد التصدير"
        }
    }

    private fun readDocument(uri: Uri): String {
        contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { return it.readText() }
        }
        throw IllegalStateException("تعذر قراءة ملف Markdown")
    }

    private fun runExport(kind: String, task: () -> String) {
        Toast.makeText(this, "جاري تصدير $kind...", Toast.LENGTH_SHORT).show()
        exportExecutor.execute {
            CaptureQueue.awaitIdle()
            try {
                val message = task()
                runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "فشل التصدير: ${error.message ?: "خطأ غير معروف"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val expected = packageName + "/" + ConversationAccessibilityService::class.java.name
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id == expected }
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 20 }
}
