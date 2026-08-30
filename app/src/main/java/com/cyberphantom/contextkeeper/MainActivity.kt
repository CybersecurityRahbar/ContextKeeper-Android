package com.cyberphantom.contextkeeper

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var toggle: Switch

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi() }
    override fun onResume() { super.onResume(); refresh() }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 48, 36, 36) }
        setContentView(root)
        root.addView(TextView(this).apply { text = "Context Keeper"; textSize = 28f }, lp())
        root.addView(TextView(this).apply { text = "يسجل نص محادثة ChatGPT أثناء ظهورها والتمرير، ويمنع إضافة نفس المقطع مرتين."; textSize = 16f }, lp())
        status = TextView(this).apply { textSize = 16f }; root.addView(status, lp())
        count = TextView(this).apply { textSize = 16f }; root.addView(count, lp())
        toggle = Switch(this).apply {
            text = "تشغيل التسجيل"
            setOnCheckedChangeListener { _, checked ->
                CaptureStore.setRecording(this@MainActivity, checked)
                if (checked) CaptureStore.newSession(this@MainActivity)
                refresh()
            }
        }
        root.addView(toggle, lp())
        root.addView(Button(this).apply { text = "فتح إعدادات إمكانية الوصول"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }, lp())
        root.addView(Button(this).apply { text = "تصدير Markdown + نسخ إلى الحافظة"; setOnClickListener { exportAndCopyMarkdown() } }, lp())
        root.addView(Button(this).apply { text = "تصدير JSON"; setOnClickListener { exportJson() } }, lp())
        root.addView(Button(this).apply { text = "جلسة جديدة"; setOnClickListener { CaptureStore.newSession(this@MainActivity); refresh() } }, lp())
    }

    private fun refresh() {
        status.text = if (isAccessibilityServiceEnabled()) "خدمة الالتقاط: مفعلة" else "خدمة الالتقاط: غير مفعلة"
        count.text = "العناصر المحفوظة: ${CaptureStore.messageCount(this)}"
        toggle.setOnCheckedChangeListener(null); toggle.isChecked = CaptureStore.isRecording(this)
        toggle.setOnCheckedChangeListener { _, checked -> CaptureStore.setRecording(this, checked); if (checked) CaptureStore.newSession(this); refresh() }
    }

    private fun exportAndCopyMarkdown() {
        val file = CaptureStore.exportMarkdown(this)
        val text = file.readText(Charsets.UTF_8)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("conversation", text))
        Toast.makeText(this, "تم حفظ ونسخ Markdown (${text.length} حرف)", Toast.LENGTH_LONG).show()
    }

    private fun exportJson() {
        val file = CaptureStore.exportJson(this)
        Toast.makeText(this, "تم حفظ JSON: ${file.name}", Toast.LENGTH_LONG).show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val expected = packageName + "/" + ConversationAccessibilityService::class.java.name
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.id == expected }
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20 }
}
