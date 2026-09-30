package com.hachimi.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hachimi.app.ui.Ds
import org.json.JSONObject

/**
 * 悬浮叙事层（架构 §6 两种执行形态的 UI 组成，P1.5 视觉终态对齐 mockup S3/S4）：
 * - 浮条（前台引导模式）：顶部窄条，步骤/当前动作 + 暂停/停止。
 * - 停靠（分屏模式）：下半屏 agent 控制台——感知/决策/时间线（✓绿 →蓝 ■白 染色）。
 * - 收起 → 缩成小药丸（可点回完整面板；运行期不再有「找不回」状态，2026-09-30 用户反馈）。
 * - 红框定位指示（mockup .ground）：tap 类 step 事件时在目标点位画红色虚线框
 *   呼吸动画 ~1.2s（画在独立触摸穿透窗，操作零干扰；tap_by_id 无坐标暂不画）。
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
    private var pillView: LinearLayout? = null
    private var docked = false
    private var paused = false
    private var collapsed = false
    private var lastStatus = "idle"   // 跨 attach 保留：停靠/浮条切换后回放（start 事件先于 dock 到达时 console 否则停留 idle）

    // ---------------- 事件转发器（K4 第一步：ChatPage 消费同一叙事流） ----------------

    /** 叙事事件订阅者（主线程回调；ChatPage/未来会话列表页挂载）。 */
    interface Listener {
        fun onNarrative(type: String, fields: Map<String, Any?>)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    @JvmStatic fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    @JvmStatic fun removeListener(l: Listener) { listeners.remove(l) }

    /** JSONObject → 归一化字段 Map（数值/布尔保持原生类型，供 ChatMapper 纯函数消费）。 */
    private fun toFields(e: org.json.JSONObject): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        for (k in e.keys()) {
            val v = e.opt(k) ?: continue
            out[k] = when {
                v is org.json.JSONArray -> v.toString()
                v is org.json.JSONObject -> v.toString()
                else -> v   // String / Int / Boolean / Double 原样
            }
        }
        return out
    }

    // 浮条视图引用
    private var statusLine: TextView? = null
    private var logLine: TextView? = null

    // 停靠控制台视图引用
    private var consoleStatus: TextView? = null
    private var perception: TextView? = null
    private var decision: TextView? = null
    private var timeline: TextView? = null
    private val timelineLines = ArrayDeque<String>()

    // 红框定位指示（.ground）
    private var frameView: View? = null

    /** ConsoleActivity 可见时抑制 overlay（真分屏的下半屏就是控制台，双 UI 互相踩） */
    @Volatile @JvmStatic var consoleVisible = false

    /** 控制台页轮询快照：状态行 / 感知 / 决策 / 时间线（面板内序）。 */
    @JvmStatic
    @Synchronized
    fun snapshot(): JSONObject {
        val sb = StringBuilder()
        for (line in timelineLines) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        return JSONObject()
            .put("status", lastStatus)
            .put("docked", docked)
            .put("perception", perception?.text?.toString() ?: "")
            .put("decision", decision?.text?.toString() ?: "")
            .put("timeline", sb.toString())
    }

    /** Python 叙事事件入口（bridge.register_narrative 注入的 sink）。 */
    @JvmStatic
    fun push(json: String) {
        main.post {
            try {
                val e = JSONObject(json)
                val type = e.optString("event")
                // K4 转发器：解析后先分发给订阅页（ChatPage 消息流），浮窗渲染照旧
                if (listeners.isNotEmpty()) {
                    val fields = toFields(e)
                    for (l in listeners) {
                        try { l.onNarrative(type, fields) } catch (ex: Exception) {
                            Log.w(TAG, "listener: ${ex.message}")
                        }
                    }
                }
                when (e.optString("event")) {
                    "start" -> {
                        collapsed = false
                        lastStatus = "run " + e.optString("run_id").take(14)
                        append("▶ 目标: " + e.optString("objective").take(40))
                        addTimeline("▶ " + e.optString("objective").take(36))
                        setAllStatus(lastStatus)
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
                        // 红框定位指示：有归一化坐标的动作（tap_xy/gesture 首点）
                        val args = e.optJSONObject("args")
                        val x = args?.optDouble("x")
                        val y = args?.optDouble("y")
                        if ((tool == "tap_xy") && x != null && y != null &&
                            !x.isNaN() && !y.isNaN()) {
                            showGround(x, y)
                        } else if (tool == "gesture") {
                            val p0 = args?.optJSONArray("points")?.optJSONArray(0)
                            if (p0 != null) showGround(p0.optDouble(0), p0.optDouble(1))
                        }
                    }
                    "paused" -> append("⏸ 已暂停（面板可恢复）")
                    "finish" -> {
                        append("■ 完成 ${e.optString("status")} · ${e.optInt("steps")}步")
                        addTimeline("■ ${e.optString("status")} · ${e.optInt("steps")}步")
                        scheduleHide(2500)
                    }
                    "error" -> {
                        append("■ 错误: " + e.optString("error").take(80))
                        scheduleHide(4000)
                    }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "push parse: ${ex.message}")
            }
        }
    }

    @Synchronized
    fun show(context: Context) {
        this.context = context.applicationContext
        // 自投递主线程：调用方可能在任意线程（loopback HTTP / Python 桥线程），
        // WindowManager.addView 需要带 Looper 的线程——在调用线程直接 add 会
        // 留下「已赋值未挂载」的脏状态（2026-09-29 实录）
        main.post {
            if (contentView == null && canOverlay(this.context!!)) {
                this.context?.let { attach(it, docked) }
            }
        }
    }

    @Synchronized
    fun dock() {
        docked = true
        if (consoleVisible) return   // 真分屏控制台在场：overlay 降级退出
        val c = context ?: return
        main.post {
            if (contentView != null || pillView != null) detachAll()
            if (canOverlay(c) && !collapsed) attach(c, true)
        }
    }

    @Synchronized
    fun undock() {
        docked = false
        if (consoleVisible) return
        val c = context ?: return
        main.post {
            if (contentView != null || pillView != null) detachAll()
            if (canOverlay(c) && !collapsed) attach(c, false)
        }
    }

    @Synchronized
    fun hide() {
        main.post { detachAll() }
    }

    /**
     * 收起（用户点「收起」）：不再整体消失——缩成小药丸（单点回完整面板）。
     * 2026-09-30 用户反馈「点收起就再也找不到了」。
     */
    @Synchronized
    fun collapse() {
        collapsed = true
        val c = context ?: return
        main.post {
            detachAll()
            if (canOverlay(c)) attachPill(c)
        }
    }

    @Synchronized
    fun expand() {
        collapsed = false
        val c = context ?: return
        main.post {
            detachAll()
            if (canOverlay(c)) attach(c, docked)
        }
    }

    /** run 收尾自动收起（2026-09-27 实测：残留 overlay 按钮条拦截目标 App 底部
     *  触摸——时钟 tab 栏被吞，烧穿 T3/T8/T14 预算）。新 run 的 /task 会重新 show。 */
    private fun scheduleHide(delayMs: Long) {
        main.postDelayed({ detachAll() }, delayMs)
    }

    // ---------------- 视图构建 ----------------

    private fun canOverlay(c: Context): Boolean =
        android.provider.Settings.canDrawOverlays(c)

    /** 小药丸：收起态的唯一驻留物，点击回完整面板（含状态缩写，一眼可辨）。 */
    private fun attachPill(c: Context) {
        val pill = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.round(c, 18, 0xF21E2026.toInt())
            setPadding(Ds.dp(c, 12), Ds.dp(c, 5), Ds.dp(c, 12), Ds.dp(c, 5))
            setOnClickListener { expand() }
        }
        pill.addView(TextView(c).apply {
            text = "▣ Hachimi ▸"
            textSize = 11f; typeface = Typeface.MONOSPACE
            setTextColor(Ds.ACCENT)
        })
        pillView = pill
        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = if (docked) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                          else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            wm = c.getSystemService(WindowManager::class.java)
            wm!!.addView(pill, params)
        } catch (e: Exception) {
            Log.w(TAG, "pill add: ${e.message}")
            pillView = null
        }
    }

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
            setBackgroundColor(0xF21E2026.toInt())
            setPadding(pad, pad / 2, pad, pad / 2)
        }.also { box ->
            if (dock) {
                consoleStatus = TextView(c).apply {
                    text = "AGENT 控制台 · idle · 分屏模式"
                    setTextColor(Ds.ACCENT); textSize = 10f
                    typeface = Typeface.MONOSPACE
                }
                perception = TextView(c).apply {
                    text = "感知: -"
                    setTextColor(Ds.GREEN_LOG); textSize = 11f; maxLines = 2
                }
                decision = TextView(c).apply {
                    text = "决策: -"
                    setTextColor(0xFFFFD9A0.toInt()); textSize = 11f; maxLines = 1
                    typeface = Typeface.MONOSPACE
                }
                timeline = TextView(c).apply {
                    text = ""
                    setTextColor(Ds.DARK_TXT); textSize = 11f; maxLines = 6
                    typeface = Typeface.MONOSPACE
                }
                box.addView(consoleStatus)
                box.addView(perception)
                box.addView(decision)
                box.addView(timeline)
            } else {
                consoleStatus = null; perception = null; decision = null; timeline = null
                statusLine = TextView(c).apply {
                    text = "▶ 前台引导 · idle"
                    setTextColor(Ds.ACCENT); textSize = 10f
                    typeface = Typeface.MONOSPACE
                }
                logLine = TextView(c).apply {
                    text = ""
                    setTextColor(Ds.DARK_TXT); textSize = 11f; maxLines = 4
                    typeface = Typeface.MONOSPACE
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
        setAllStatus(lastStatus)
        // 重挂（如收起→展开）时回放既有时间线，避免空白等待下一事件
        if (dock) timeline?.text = colored(timelineLines.joinToString("\n"))
        else logLine?.text = colored(logLine?.text?.toString() ?: "")
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

    private fun detachAll() {
        for (v in listOf(contentView, barView)) {
            try {
                wm?.removeView(v)
            } catch (_: Exception) {}
        }
        try { pillView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        contentView = null; barView = null; pillView = null
        statusLine = null; logLine = null
        consoleStatus = null; perception = null; decision = null; timeline = null
    }

    private fun buttonsRow(c: Context): LinearLayout {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, danger: Boolean = false, onClick: (Button) -> Unit) = Button(c).apply {
            text = label; textSize = 11f
            // 暗底胶囊小按钮（mockup .ctrl）；停止=红字
            setTextColor(if (danger) Ds.RED else Ds.DARK_TXT)
            typeface = Typeface.DEFAULT_BOLD
            background = Ds.round(c, 8, if (danger) 0x33EA6668.toInt() else Ds.DARK_SUB)
            setPadding(Ds.dp(c, 12), 0, Ds.dp(c, 12), 0)
            setOnClickListener { v -> onClick(v as Button) }
            row.addView(this)
        }
        btn("暂停") { b ->
            paused = !paused
            py(if (paused) "request_pause" else "request_resume")
            b.text = if (paused) "继续" else "暂停"
        }
        btn("停止", danger = true) { _ -> py("request_stop") }
        btn(if (docked) "浮条" else "停靠") { _ -> if (docked) undock() else dock() }
        btn("收起") { _ -> collapse() }
        return row
    }

    // ---------------- 文本更新 ----------------

    private fun append(line: String) {
        val v = logLine ?: return
        v.text = (if (v.text.isNotEmpty()) v.text.toString() + "\n" else "") + line
        if ((v.text.split("\n").size) > 4) {
            v.text = v.text.split("\n").takeLast(4).joinToString("\n")
        }
        v.text = colored(v.text.toString())
    }

    private fun addTimeline(line: String) {
        if (timelineLines.size >= 6) timelineLines.removeFirst()
        timelineLines.addLast(line)
        timeline?.text = colored(timelineLines.joinToString("\n"))
    }

    /** 日志/时间线逐行染色（mockup .log 语义）：✓ 绿 / → 蓝 / ✗ 红 / 其余白。 */
    private fun colored(text: String): CharSequence {
        val sb = SpannableString(text)
        var idx = 0
        for (line in text.split("\n")) {
            if (line.isNotEmpty()) {
                val color = when (line.first()) {
                    '✓' -> Ds.GREEN_LOG
                    '→' -> Ds.ACCENT
                    '✗' -> Ds.RED
                    else -> Ds.DARK_TXT
                }
                sb.setSpan(ForegroundColorSpan(color), idx, idx + line.length, 0)
            }
            idx += line.length + 1
        }
        return sb
    }

    private fun setAllStatus(s: String) {
        lastStatus = s
        statusLine?.text = "▶ 前台引导 · $s"
        consoleStatus?.text = "AGENT 控制台 · $s" +
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

    // ---------------- 红框定位指示（mockup .ground，1.2s 呼吸后自消） ----------------

    private fun showGround(nx: Double, ny: Double) {
        val c = context ?: return
        main.post {
            removeGround()
            if (!canOverlay(c)) return@post
            val v = GroundView(c, nx, ny)
            try {
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                c.getSystemService(WindowManager::class.java).addView(v, params)
                frameView = v
                main.postDelayed({ removeGround() }, 1200)
            } catch (e: Exception) {
                Log.w(TAG, "ground add: ${e.message}")
            }
        }
    }

    private fun removeGround() {
        frameView?.let {
            try {
                it.context.getSystemService(WindowManager::class.java).removeView(it)
            } catch (_: Exception) {}
        }
        frameView = null
    }

    /** 全屏透明窗上的目标框：红色虚线圆角框 + 呼吸透明度（模拟 target 锁定）。 */
    private class GroundView(c: Context, nx: Double, ny: Double) : View(c) {
        private val cx = (nx * c.resources.displayMetrics.widthPixels).toFloat()
        private val cy = (ny * c.resources.displayMetrics.heightPixels).toFloat()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Ds.RED
            strokeWidth = Ds.dp(c, 3).toFloat()
            pathEffect = DashPathEffect(floatArrayOf(Ds.dp(c, 9).toFloat(), Ds.dp(c, 6).toFloat()), 0f)
        }
        private val anim = android.view.animation.AlphaAnimation(0.35f, 1f)
            .apply { duration = 600; repeatCount = android.view.animation.Animation.INFINITE; repeatMode = android.view.animation.Animation.REVERSE }

        override fun onDraw(canvas: Canvas) {
            // 框尺寸对齐 mockup .ground（64px/330px 屏宽 ≈ 19%）→ 真机半宽 54dp
            val half = Ds.dp(context, 54)
            canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half,
                Ds.dp(context, 20).toFloat(), Ds.dp(context, 20).toFloat(), paint)
        }

        init { startAnimation(anim) }
    }
}
