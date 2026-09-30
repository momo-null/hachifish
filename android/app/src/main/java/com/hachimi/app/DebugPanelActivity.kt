package com.hachimi.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 八原语调试面板（原 M1' 调试形态，自 MainActivity 迁出）：每个原语一个按钮，
 * 屏上回显 JSON 结果。研究排障入口（P0 起主界面让位给任务形态）。
 */
class DebugPanelActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var debugBox: LinearLayout
    private lateinit var testInput: EditText
    private val logBuf = StringBuilder()
    private var lastTree: JSONArray? = null
    private val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi(): View {
        val pad = (14 * resources.displayMetrics.density).toInt()
        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, pad / 2) }
        box.addView(status)

        box.addView(button("打开无障碍设置") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        box.addView(button("启动内核前台服务") {
            KernelHostService.start(this@DebugPanelActivity); refresh()
        })
        box.addView(button("请求屏幕录制授权") {
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startActivityForResult(pm.createScreenCaptureIntent(), REQ_PROJECTION)
        })

        box.addView(sectionLabel("── 八原语调试面板 ──"))
        testInput = EditText(this).apply { hint = "④ type_text 的目标输入框" }
        box.addView(testInput)
        debugBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
        }
        box.addView(debugBox)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(0xFF1E2026.toInt())
        }
        box.addView(sectionLabel("── 执行日志 ──"))
        box.addView(logView)

        root.addView(box)
        rebuildDebugButtons()
        return root
    }

    private fun rebuildDebugButtons() {
        debugBox.removeAllViews()
        val svc = HachimiAccessibilityService.instance
        if (svc == null) {
            debugBox.addView(text("无障碍未连接：请先开启服务再回来。"))
            return
        }
        dbg("① observe（UI 树）") { primObserve() }
        dbg("② tap_by_id（缓存树首个可点击）") { primTapById() }
        dbg("③ tap_xy(0.5,0.9)") { svc.tapXY(0.5, 0.9) }
        dbg("④ type_text（替换上方输入框内容）") {
            testInput.requestFocus()
            svc.typeText("Hachifish M1' 测试 123")
        }
        dbg("⑤ gesture 上滑") {
            svc.gesture(JSONArray().put(JSONArray().put(0.5).put(0.8)).put(JSONArray().put(0.5).put(0.3)))
        }
        dbg("⑥ screenshot（异步）") {
            svc.screenshot { r -> runOnUiThread { log("⑥ screenshot → $r") } }
            JSONObject().put("ok", true).put("note", "已发起，结果见下一条日志")
        }
        dbg("⑦ launch_app(org.fossify.notes)") { svc.launchApp("org.fossify.notes") }
        dbg("⑧ press(back)") { svc.press("back") }
    }

    private fun primObserve(): JSONObject {
        val r = HachimiAccessibilityService.instance!!.observe()
        if (r.optBoolean("ok")) {
            lastTree = r.optJSONArray("tree")
            r.put("node_count", lastTree?.length() ?: 0)
            r.put("tree_preview", lastTree?.let { t ->
                (0 until minOf(2, t.length())).map { t.getJSONObject(it).toString() }
            })
            r.remove("tree") // 全树太大，回显只给统计；tap_by_id 用缓存树
        }
        return r
    }

    private fun primTapById(): JSONObject {
        val tree = lastTree ?: throw IllegalStateException("先点 ① observe 获取缓存树")
        val first = (0 until tree.length())
            .map { tree.getJSONObject(it) }
            .firstOrNull { it.optBoolean("clickable") && it.optString("view_id").isNotEmpty() }
            ?: throw IllegalStateException("缓存树中无可点击且有 id 的控件")
        val vid = first.getString("view_id")
        return HachimiAccessibilityService.instance!!.tapById(vid).put("target", vid)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            KernelHostService.startProjection(this, resultCode, data)
            refresh()
        }
    }

    private fun refresh() {
        val connected = HachimiAccessibilityService.instance != null
        status.text = "无障碍通道: ${if (connected) "已连接 ✓" else "未开启"}\n" +
                "设备: Android ${android.os.Build.VERSION.SDK_INT} · Hachifish v0.1"
        if (::debugBox.isInitialized) rebuildDebugButtons()
    }

    private fun dbg(label: String, block: () -> JSONObject) {
        debugBox.addView(Button(this).apply {
            text = label
            setOnClickListener {
                try {
                    val r = block()
                    log("$label → $r")
                } catch (e: Exception) {
                    log("$label → ERROR ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        })
    }

    private fun log(line: String) {
        logBuf.insert(0, "[${ts.format(Date())}] $line\n")
        if (logBuf.length > 6000) logBuf.setLength(6000)
        logView.text = logBuf.toString()
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply { text = label; setOnClickListener { onClick() } }

    private fun sectionLabel(s: String): TextView = TextView(this).apply {
        text = s; textSize = 12f; gravity = Gravity.CENTER; setPadding(0, 8, 0, 8)
    }

    private fun text(s: String): TextView = TextView(this).apply { text = s; textSize = 13f }

    companion object {
        private const val REQ_PROJECTION = 1001
    }
}
