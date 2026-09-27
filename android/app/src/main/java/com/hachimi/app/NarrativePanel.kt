package com.hachimi.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * 悬浮叙事层（架构 §6 两种执行形态的 UI 组成）：
 * - 浮条（前台引导模式）：顶部窄条，步骤/当前动作 + 暂停/停止。
 * - 停靠（分屏模式）：下半屏 agent 控制台——感知描述（observe 摘要）/决策（当前工具）/
 *   时间线（最近步）。系统分屏无程序化公开 API，overlay 停靠视觉等价（v1 决策，帧预览后置）。
 * 数据源 = Python bridge 叙事事件（register_narrative）；TYPE_APPLICATION_OVERLAY +
 * FLAG_NOT_FOCUSABLE 不抢目标 App 输入。研究机经 adb appops 授予权限。
 */
object NarrativePanel {

    private const val TAG = "HachimiPanel"
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var context: Context? = null
    private var contentView: LinearLayout? = null
    private var barView: LinearLayout? = null
    private var docked = false
    private var paused = false

    // 浮条视图引用
    private var statusLine: TextView? = null
    private var logLine: TextView? = null

    // 停靠控制台视图引用
    private var consoleStatus: TextView? = null
    private var perception: TextView? = null
    private var decision: TextView? = null
    private var timeline: TextView? = null
    private val timelineLines = ArrayDeque<String>()

    /** Python 叙事事件入口（bridge.register_narrative 注入的 sink）。 */
    @JvmStatic
    fun push(json: String) {
        main.post {
            try {
                val e = JSONObject(json)
                when (e.optString("event")) {
                    "start" -> {
                        append("▶ 目标: " + e.optString("objective").take(40))
                        addTimeline("▶ " + e.optString("objective").take(36))
                        setAllStatus("run " + e.optString("run_id").take(14))
                    }
                    "mode" -> if (e.optBoolean("docked")) dock() else undock()
                    "step" -> {
                        val tool = e.optString("tool")
                        val ok = when {
                            !e.has("ok") || e.isNull("ok") -> "…"
                            e.optBoolean("ok") -> "✓"
                            else -> "✗"
                        }
                        val err = if (e.isNull("error")) "" else e.optString("error")
                        val screen = e.optJSONArray("screen") ?: org.json.JSONArray()
                        val perceptionText = (0 until screen.length())
                            .joinToString(" | ") { screen.optString(it) }
                        val line = "step ${e.optInt("step")} $tool $ok" +
                                (if (err.isNotEmpty()) " $err".take(50) else "")
                        append(line)
                        addTimeline(line)
                        if (perceptionText.isNotEmpty()) {
                            perception?.text = "感知: " + perceptionText.take(160)
                        }
                        decision?.text = "决策: $tool " +
                                e.optJSONObject("args")?.toString()?.take(90).orEmpty()
                    }
                    "paused" -> append("⏸ 已暂停（面板可恢复）")
                    "finish" -> {
                        append("■ 完成 ${e.optString("status")} · ${e.optInt("steps")}步")
                        addTimeline("■ ${e.optString("status")} · ${e.optInt("steps")}步")
                    }
                    "error" -> append("■ 错误: " + e.optString("error").take(80))
                }
            } catch (ex: Exception) {
                Log.w(TAG, "push parse: ${ex.message}")
            }
        }
    }

    @Synchronized
    fun show(context: Context) {
        this.context = context.applicationContext
        if (contentView != null) return
        if (!canOverlay(context)) {
            Log.w(TAG, "overlay permission missing (adb appops SYSTEM_ALERT_WINDOW)")
            return
        }
        this.context?.let { attach(it, docked) }
    }

    @Synchronized
    fun dock() {
        docked = true
        val c = context ?: return
        if (contentView != null) detach() else if (!canOverlay(c)) return
        attach(c, true)
    }

    @Synchronized
    fun undock() {
        docked = false
        val c = context ?: return
        if (contentView != null) detach() else if (!canOverlay(c)) return
        attach(c, false)
    }

    @Synchronized
    fun hide() {
        detach()
    }

    // ---------------- 视图构建 ----------------

    private fun canOverlay(c: Context): Boolean =
        android.provider.Settings.canDrawOverlays(c)

    /** 双窗结构：内容窗纯显示（NOT_TOUCHABLE，触摸穿透到目标 App——分屏并行语义），
     *  按钮条窗可触摸（仅按钮自身区域拦截）。 */
    private fun attach(c: Context, dock: Boolean) {
        if (!canOverlay(c)) {
            Log.w(TAG, "overlay permission missing")
            return
        }
        val pad = (12 * c.resources.displayMetrics.density).toInt()

        contentView = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2000000.toInt())
            setPadding(pad, pad / 2, pad, pad / 2)
        }.also { box ->
            if (dock) {
                consoleStatus = TextView(c).apply {
                    text = "Hachimi 控制台 · idle"
                    setTextColor(0xFF9FD8FF.toInt()); textSize = 10f
                }
                perception = TextView(c).apply {
                    text = "感知: -"
                    setTextColor(0xFFB0E0B0.toInt()); textSize = 11f; maxLines = 2
                }
                decision = TextView(c).apply {
                    text = "决策: -"
                    setTextColor(0xFFFFD9A0.toInt()); textSize = 11f; maxLines = 1
                }
                timeline = TextView(c).apply {
                    text = ""
                    setTextColor(Color.WHITE); textSize = 11f; maxLines = 6
                    typeface = Typeface.MONOSPACE
                }
                box.addView(consoleStatus)
                box.addView(perception)
                box.addView(decision)
                box.addView(timeline)
            } else {
                consoleStatus = null; perception = null; decision = null; timeline = null
                statusLine = TextView(c).apply {
                    text = "Hachimi · idle"
                    setTextColor(0xFF9FD8FF.toInt()); textSize = 10f
                }
                logLine = TextView(c).apply {
                    text = ""
                    setTextColor(Color.WHITE); textSize = 11f; maxLines = 4
                }
                box.addView(statusLine)
                box.addView(logLine)
            }
            // 内容窗让位按钮条：停靠=条在底（内容其上），浮条=条在顶（内容其下）
            addWindow(box, WindowManager.LayoutParams.MATCH_PARENT,
                if (dock) (c.resources.displayMetrics.heightPixels * 0.40f).toInt()
                else WindowManager.LayoutParams.WRAP_CONTENT,
                passThrough = true,
                gravity = if (dock) Gravity.BOTTOM else Gravity.TOP,
                yOff = barPx)
        }

        barView = buttonsRow(c).apply {
            addWindow(this, WindowManager.LayoutParams.MATCH_PARENT, barPx,
                passThrough = false,
                gravity = if (dock) Gravity.BOTTOM else Gravity.TOP,
                yOff = 0)
        }
        Log.i(TAG, if (dock) "console docked (split mode, touch-through)" else "panel shown (compact)")
    }

    private val barPx: Int
        get() = context?.resources?.displayMetrics?.density?.let { (50 * it).toInt() } ?: 132

    private fun addWindow(v: LinearLayout, w: Int, h: Int, passThrough: Boolean,
                          gravity: Int, yOff: Int) {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (passThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.y = yOff
        }
        try {
            wm = context?.getSystemService(WindowManager::class.java)
            wm!!.addView(v, params)
        } catch (e: Exception) {
            Log.e(TAG, "addWindow failed: ${e.message}")
        }
    }

    private fun detach() {
        for (v in listOf(contentView, barView)) {
            try {
                wm?.removeView(v)
            } catch (_: Exception) {}
        }
        contentView = null; barView = null
        statusLine = null; logLine = null
        consoleStatus = null; perception = null; decision = null; timeline = null
    }

    private fun buttonsRow(c: Context): LinearLayout {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, onClick: (Button) -> Unit) = Button(c).apply {
            text = label; textSize = 11f
            setOnClickListener { v -> onClick(v as Button) }
            row.addView(this)
        }
        btn("暂停") { b ->
            paused = !paused
            py(if (paused) "request_pause" else "request_resume")
            b.text = if (paused) "继续" else "暂停"
        }
        btn("停止") { _ -> py("request_stop") }
        btn(if (docked) "浮条" else "停靠") { _ -> if (docked) undock() else dock() }
        btn("收起") { _ -> hide() }
        return row
    }

    // ---------------- 文本更新 ----------------

    private fun append(line: String) {
        val v = logLine ?: return
        v.text = (if (v.text.isNotEmpty()) v.text.toString() + "\n" else "") + line
        if ((v.text.split("\n").size) > 4) {
            v.text = v.text.split("\n").takeLast(4).joinToString("\n")
        }
    }

    private fun addTimeline(line: String) {
        if (timelineLines.size >= 6) timelineLines.removeFirst()
        timelineLines.addLast(line)
        timeline?.text = timelineLines.joinToString("\n")
    }

    private fun setAllStatus(s: String) {
        statusLine?.text = "Hachimi · $s"
        consoleStatus?.text = "Hachimi 控制台 · $s" +
                (if (docked) " · 分屏模式" else "")
    }

    /** 面板按钮 → Python bridge 控制面（挂在 KernelHostService 缓存的 bridge 模块上）。 */
    private fun py(method: String) {
        try {
            KernelHostService.pyBridgeModule?.callAttr(method)
        } catch (e: Exception) {
            Log.w(TAG, "py $method: ${e.message}")
        }
    }
}
