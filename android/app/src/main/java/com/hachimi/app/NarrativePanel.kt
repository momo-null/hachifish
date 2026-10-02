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
    private var pillView: View? = null
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

    // P3 V10 面板 / V11 球视图引用与进度态
    private var actionLine: TextView? = null     // 当前动作行（口播优先，tool 次之）
    private var stepLine: TextView? = null       // 「第 n/total 步 · 目标」副标题
    private var progressFill: View? = null
    private var lastProgressN = 0
    private var lastProgressTotal = 0
    private var lastObjective = ""               // 当前任务目标（浮动面板副标题）
    private var ring: RingDrawable? = null
    private var ballBadge: TextView? = null
    private var pulseView: View? = null          // 头像外圈脉冲（mockup .float-avatar fishpulse）
    private var panelBtns: LinearLayout? = null  // V10 独立按钮窗（圆形图标组）
    private var menuView: LinearLayout? = null   // ⋯ overflow 菜单窗（overlay 自绘）

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

    /**
     * 实验静默模式（R15）：run 期间**完全不挂**任何悬浮层（球/浮条/控制台/红框）。
     * 球与浮条都是可触摸窗，agent 的 tap_xy 打到它们会被吞掉——2026-10-02 实录：
     * 冷基线 run 全程在跟自己的 UI 战斗（模型口播 "my gestures are hitting this
     * panel instead of the phone"）。自动化实验必须能整体关掉；经 debug 广播
     * ``panel_silent`` 切换。置 true 时立即摘除已挂窗口。
     */
    @Volatile @JvmStatic var silent: Boolean = false
        set(value) {
            field = value
            if (value) hide()
        }

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
                        // R11：新一轮起手=收起态（只留球，点按展开）；已挂载的面板同步
                        // 变球，避免上一轮的展开态泄漏到新一轮。
                        collapsed = true
                        if (contentView != null || pillView != null) applyLayout()
                        // 进度复位：收起态下球是第一视线，残留上一轮进度会误导
                        lastProgressN = 0
                        lastProgressTotal = 0
                        // 门控会话随任务重置（GateManager 契约「allow_always 仅当前任务」）：
                        // 产品路径（聊天发送 / 回环 /task）都不经过 TasksPage，必须在此统一重置，
                        // 否则上一任务的放行泄漏到所有后续任务（2026-10-01 真机实录）。
                        com.hachimi.app.gate.GateManager.beginTask()
                        lastStatus = "run " + e.optString("run_id").take(14)
                        lastObjective = e.optString("objective").trim().replace('\n', ' ').take(30)
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
                        // P3：进度（K2 total）+ 当前动作行 + 球进度环/角标
                        val total = e.optInt("total", 0)
                        if (total > 0) {
                            lastProgressTotal = total
                            lastProgressN = e.optInt("step", lastProgressN)
                        }
                        actionLine?.text = "▸ $tool"
                        updateProgressUi()
                        updateStepLine()
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
                    "message" -> {
                        // K3 口播：当前动作行优先显示口播（比工具名好懂）
                        val content = e.optString("content").trim()
                        if (content.isNotEmpty()) actionLine?.text = content.take(60)
                    }
                    "finish" -> {
                        val st = e.optString("status")
                        append("■ 完成 $st · ${e.optInt("steps")}步")
                        addTimeline("■ $st · ${e.optInt("steps")}步")
                        // V10/V11 终态：进度满环 + 动作行收尾语
                        actionLine?.text = if (st == "done") "✓ 办好了~" else "■ $st"
                        if (lastProgressTotal > 0) {
                            lastProgressN = lastProgressTotal
                            updateProgressUi()
                        }
                        updateStepLine()
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

    /**
     * 任务信号到达 → 挂载 overlay。**起手一律收起态**（R11：只留大肥鱼球，点按才
     * 展开）——三个入口同此（TasksPage 派发 / ChatPage 首个 tool step / 回环 /task），
     * 执行期不再铺开面板遮挡目标 App；展开只由用户点按球触发（[expand]）。
     */
    @Synchronized
    fun show(context: Context) {
        this.context = context.applicationContext
        // 自投递主线程：调用方可能在任意线程（loopback HTTP / Python 桥线程），
        // WindowManager.addView 需要带 Looper 的线程——在调用线程直接 add 会
        // 留下「已赋值未挂载」的脏状态（2026-09-29 实录）
        main.post {
            collapsed = true
            when {
                // R15：静默模式一个窗都不挂（已挂的摘掉）
                silent -> detachAll()
                // R15 修复：上一轮遗留的**展开态**面板原样带进新一轮（show 只在
                // "什么都没挂"时才挂球）——必须强制收回球，而不是放任不管
                contentView != null || pillView != null -> applyLayout()
                canOverlay(this.context!!) -> this.context?.let { attachPill(it) }
            }
        }
    }

    /**
     * dock/undock 只负责**已挂载**面板的形态切换，不负责创建窗口（2026-10-01 修复）：
     * kernel 每轮 run（含纯闲聊）都 emit mode 事件，此前未挂载时也会直接 attach 出浮条
     * ——表现为「闲聊也弹任务浮窗」。窗口创建只认显式任务信号：TasksPage 派发、
     * 回环 /task、ChatPage 首个真实 tool step（均经 show() 进入）。
     */
    @Synchronized
    fun dock() {
        docked = true
        if (consoleVisible) return   // 真分屏控制台在场：overlay 降级退出
        applyLayout()
    }

    @Synchronized
    fun undock() {
        docked = false
        if (consoleVisible) return
        applyLayout()
    }

    /** 按当前 docked/collapsed 重建**已存在**的 overlay；未挂载则什么都不做。 */
    private fun applyLayout() {
        // 未挂载 = 本轮尚无任务信号：只记录形态，绝不凭 mode 事件自己冒出来
        if (contentView == null && pillView == null) return
        val c = context ?: return
        main.post {
            detachAll()
            if (silent) return@post          // R15：静默模式不回挂
            if (!canOverlay(c)) return@post
            // 收起态维持药丸球（不因形态事件被"撑回"完整面板）
            if (collapsed) attachPill(c) else attach(c, docked)
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
        if (silent) return               // R15：静默模式无窗可收
        collapsed = true
        val c = context ?: return
        main.post {
            detachAll()
            if (canOverlay(c)) attachPill(c)
        }
    }

    @Synchronized
    fun expand() {
        if (silent) return               // R15：静默模式不展开（球都不在场）
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

    /** 小药丸 → V11 大肥鱼球：54dp 进度环 + 头像 + 步数角标；可拖动贴边，点按展开。 */
    private fun attachPill(c: Context) {
        val size = Ds.dp(c, 54)
        ring = RingDrawable(Ds.PRIMARY, Ds.CARD_BORDER).apply {
            progress = if (lastProgressTotal > 0)
                lastProgressN.toFloat() / lastProgressTotal else 0f
        }
        val ball = android.widget.FrameLayout(c).apply {
            background = ring
        }
        ball.addView(android.widget.ImageView(c).apply {
            setImageBitmap(com.hachimi.app.ui.FishAssets.circle(c, com.hachimi.app.ui.FishAssets.AVATAR, 44))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                Ds.dp(c, 44), Ds.dp(c, 44), Gravity.CENTER)
        })
        ballBadge = TextView(c).apply {
            text = if (lastProgressN > 0) "$lastProgressN" else "•"
            textSize = 9f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ds.round(c, 9, Ds.PRIMARY)
            val p = Ds.dp(c, 4)
            setPadding(p, 0, p, 0)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Ds.dp(c, 18), Gravity.TOP or Gravity.END)
        }
        ball.addView(ballBadge)
        // V11 轻浮动动画（mockup .fish-ball floaty）：3s 循环上下 5dp，
        // 遵循系统减弱动效（animator 禁用时 TranslateAnimation 不生效即静态）
        val floaty = android.view.animation.TranslateAnimation(0f, 0f, 0f, -Ds.dp(c, 5).toFloat()).apply {
            duration = 1500
            repeatCount = android.view.animation.Animation.INFINITE
            repeatMode = android.view.animation.Animation.REVERSE
        }
        if (android.provider.Settings.Global.getFloat(
                c.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f) {
            ball.startAnimation(floaty)
        }
        // 拖动贴边 + 点按展开（超 touchSlop 判拖动，否则点击）
        val slop = android.view.ViewConfiguration.get(c).scaledTouchSlop
        val down = FloatArray(2)
        val moved = booleanArrayOf(false)
        ball.setOnTouchListener { _, ev ->
            val lp = pillParams ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    down[0] = ev.rawX; down[1] = ev.rawY; moved[0] = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - down[0]; val dy = ev.rawY - down[1]
                    if (!moved[0] && (Math.abs(dx) > slop || Math.abs(dy) > slop)) moved[0] = true
                    if (moved[0]) {
                        lp.x = (ev.rawX - size / 2f).toInt().coerceAtLeast(0)
                        lp.y = (ev.rawY - size / 2f).toInt().coerceAtLeast(0)
                        try { wm?.updateViewLayout(ball, lp) } catch (_: Exception) {}
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved[0]) expand()
                    else {  // 吸附左右边缘（半出屏钳制）
                        val w = c.resources.displayMetrics.widthPixels
                        lp.x = if (lp.x + size / 2 < w / 2) 0 else (w - size)
                        try { wm?.updateViewLayout(ball, lp) } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }
        pillView = ball
        try {
            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                // 球态改为坐标定位（可拖动）；初始贴右上（不挡目标 App 底部操作区）
                gravity = Gravity.TOP or Gravity.START
                x = c.resources.displayMetrics.widthPixels - size - Ds.dp(c, 16)
                y = c.resources.displayMetrics.heightPixels / 3
            }
            pillParams = params
            wm = c.getSystemService(WindowManager::class.java)
            wm!!.addView(ball, params)
        } catch (e: Exception) {
            Log.w(TAG, "pill add: ${e.message}")
            pillView = null
        }
    }

    private var pillParams: WindowManager.LayoutParams? = null

    /** V11 进度环：底环浅色 + 主色扫过弧 + 内圆白底（invalidateSelf 随步刷新）。 */
    private class RingDrawable(private val ringColor: Int, private val trackColor: Int) :
        android.graphics.drawable.Drawable() {
        var progress = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidateSelf() }
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
        }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            val stroke = Math.min(w, h) * 0.06f
            val r = Math.min(w, h) / 2 - stroke / 2
            val cx = w / 2; val cy = h / 2
            paint.strokeWidth = stroke
            paint.color = trackColor
            canvas.drawCircle(cx, cy, r, paint)
            paint.color = ringColor
            val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, -90f, 360f * progress, false, paint)
            canvas.drawCircle(cx, cy, r - stroke, fill)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
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
            // P3 V10：浮条=米白卡片（白底 1px 描边 / 圆角 16 / 8dp 投影）；停靠台保留暗色（高级模式）
            if (dock) {
                background = Ds.round(c, 10, 0xF21E2026.toInt())
                setPadding(pad, pad / 2, pad, pad / 2)
            } else {
                background = Ds.round(c, 16, Color.WHITE, 1, Ds.CARD_BORDER)
                setPadding(Ds.dp(c, 12), Ds.dp(c, 10), Ds.dp(c, 12), Ds.dp(c, 10))
                // mockup box-shadow(0 8 24)：轮廓取自 GradientDrawable 圆角背景
                elevation = Ds.dp(c, 8).toFloat()
            }
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
                // V10 头行（.float-main）：30dp 呼吸头像 + 动作/第 n 步 · 目标
                val head = LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                // 头像 + 外圈脉冲（40dp 容器，脉冲圈画在头像下层）
                val avWrap = android.widget.FrameLayout(c)
                pulseView = View(c).apply {
                    background = Ds.round(c, 20, Ds.ACCENT)
                    alpha = 0.35f
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        Ds.dp(c, 40), Ds.dp(c, 40), Gravity.CENTER)
                }
                avWrap.addView(pulseView)
                avWrap.addView(android.widget.ImageView(c).apply {
                    setImageBitmap(com.hachimi.app.ui.FishAssets.circle(
                        c, com.hachimi.app.ui.FishAssets.AVATAR, 30))
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        Ds.dp(c, 30), Ds.dp(c, 30), Gravity.CENTER)
                })
                head.addView(avWrap, LinearLayout.LayoutParams(Ds.dp(c, 40), Ds.dp(c, 40)))
                startPulse(pulseView!!)
                val headText = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = Ds.dp(c, 10)
                    setPadding(p, 0, 0, 0)
                }
                actionLine = TextView(c).apply {
                    text = "准备中…"
                    setTextColor(Ds.TEXT); textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                stepLine = TextView(c).apply {
                    text = ""                 // 「第 n/total 步 · 目标」（updateStepLine）
                    setTextColor(Ds.TEXT_3); textSize = 10f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                headText.addView(actionLine)
                headText.addView(stepLine)
                head.addView(headText, LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                // 右侧占位：给浮在卡片上方的独立按钮窗留位置（4 圆钮 + gap），不让文字被压
                head.addView(View(c), LinearLayout.LayoutParams(btnReservePx(c), 1))
                box.addView(head)
                // 进度条（mockup .float-progress）：n/total（K2 total 事件字段）
                val track = android.widget.FrameLayout(c).apply {
                    background = Ds.round(c, 2, Ds.PAGE)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Ds.dp(c, 3)).apply {
                        topMargin = Ds.dp(c, 8)
                    }
                }
                progressFill = View(c).apply {
                    background = Ds.round(c, 2, Ds.PRIMARY)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        0, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                }
                track.addView(progressFill)
                box.addView(track)
                // 最近 3 步（mockup .float-steps）：顶部 1px 分隔 + gap 3dp
                box.addView(View(c).apply {
                    setBackgroundColor(Ds.PAGE)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Ds.dp(c, 1)).apply {
                        topMargin = Ds.dp(c, 8)
                    }
                })
                logLine = TextView(c).apply {
                    text = ""
                    setTextColor(Ds.TEXT_3); textSize = 10f; maxLines = 3
                    typeface = Typeface.MONOSPACE
                    setLineSpacing(Ds.dp(c, 3).toFloat(), 1f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = Ds.dp(c, 7)
                    }
                }
                box.addView(logLine)
                updateProgressUi()
                updateStepLine()
            }
            // 内容窗：停靠=屏底 40% 控制台；浮条=顶部米白卡片（左右 10dp / 上 8dp，mockup .float-panel）
            addWindow(box,
                if (dock) WindowManager.LayoutParams.MATCH_PARENT
                else c.resources.displayMetrics.widthPixels - Ds.dp(c, 20),
                if (dock) (c.resources.displayMetrics.heightPixels * 0.40f).toInt()
                else WindowManager.LayoutParams.WRAP_CONTENT,
                passThrough = true,
                gravity = if (dock) Gravity.BOTTOM else Gravity.TOP or Gravity.START,
                xOff = if (dock) 0 else Ds.dp(c, 10),
                yOff = if (dock) barPx else Ds.dp(c, 8))
        }

        if (dock) {
            barView = buttonsRow(c).apply {
                addWindow(this, WindowManager.LayoutParams.MATCH_PARENT, barPx,
                    passThrough = false,
                    gravity = Gravity.BOTTOM,
                    yOff = 0)
            }
        } else {
            // V10：圆形图标按钮组挂独立窗浮于卡片右上角，仅按钮自身拦截触摸（内容仍穿透）
            panelBtns = circleBtnRow(c).apply {
                addWindow(this, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    passThrough = false,
                    gravity = Gravity.TOP or Gravity.END,
                    xOff = -Ds.dp(c, 22),      // 与卡片内容右缘对齐（外边距 10 + 内边距 12）
                    yOff = Ds.dp(c, 24))       // 卡片 top 8 + padding 10 + 行内居中（40-28)/2
            }
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
                          gravity: Int, yOff: Int, xOff: Int = 0) {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (passThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = xOff
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
        for (v in listOf(contentView, barView, panelBtns, menuView)) {
            try {
                wm?.removeView(v)
            } catch (_: Exception) {}
        }
        try { pillView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { pulseView?.animate()?.cancel() } catch (_: Exception) {}
        contentView = null; barView = null; pillView = null; pillParams = null
        panelBtns = null; menuView = null; pulseView = null
        statusLine = null; logLine = null
        actionLine = null; stepLine = null; progressFill = null; ring = null; ballBadge = null
        consoleStatus = null; perception = null; decision = null; timeline = null
    }

    private fun buttonsRow(c: Context): LinearLayout {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, danger: Boolean = false, onClick: (Button) -> Unit) = Button(c).apply {
            text = label; textSize = 11f
            // 浅色胶囊小按钮（P3 V10 米白风）；停止=红
            setTextColor(if (danger) Ds.RED else Ds.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            background = Ds.round(c, 8, if (danger) Ds.RED_TINT else Ds.GRAY_BG)
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

    // ---------------- V10 圆形图标按钮组（mockup .float-btns） ----------------

    /** 头行右侧为按钮窗预留的宽度：4 圆钮 28dp + gap 4dp×3 + 文案安全间距 10dp。 */
    private fun btnReservePx(c: Context): Int = Ds.dp(c, 28 * 4 + 4 * 3 + 10)

    /** 28dp 圆形图标按钮（mockup .float-btn）：TextView 自绘，不用原生 Button（避免默认样式/涟漪）。 */
    private fun roundBtn(c: Context, glyph: String, fg: Int,
                         onClick: (TextView) -> Unit): TextView = TextView(c).apply {
        text = glyph; textSize = 12f
        setTextColor(fg)
        gravity = Gravity.CENTER
        background = Ds.round(c, 14, Ds.PAGE)
        setOnClickListener { v -> onClick(v as TextView) }
    }

    /** V10 按钮组：⏸（暂停/继续）· ✕（停止，红）· ⌄（收起为球）· ⋯（overflow 次级，弱化色）。 */
    private fun circleBtnRow(c: Context): LinearLayout {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun add(v: TextView) = row.addView(v, LinearLayout.LayoutParams(
            Ds.dp(c, 28), Ds.dp(c, 28)).apply { marginEnd = Ds.dp(c, 4) })
        add(roundBtn(c, if (paused) "▶" else "⏸", Ds.TEXT) { b ->
            paused = !paused
            py(if (paused) "request_pause" else "request_resume")
            b.text = if (paused) "▶" else "⏸"
        })
        add(roundBtn(c, "✕", Ds.RED) { _ -> py("request_stop") })
        add(roundBtn(c, "⌄", Ds.TEXT) { _ -> collapse() })
        add(roundBtn(c, "⋯", Ds.TEXT_3) { _ -> toggleMenu(c) })
        return row
    }

    /**
     * ⋯ overflow 菜单：overlay 自绘（PopupMenu 需要 Activity token，本面板挂在 WindowManager
     * 的 application overlay 上拿不到）。通用「更多操作」入口：停靠/浮条切换 + 隐藏面板，
     * 后续新能力直接加项即可。再次点 ⋯ 或点任意项关闭。
     */
    private fun toggleMenu(c: Context) {
        if (menuView != null) { hideMenu(); return }
        val items = listOf(
            (if (docked) "切回浮条" else "AGENT 控制台（分屏）") to {
                hideMenu(); if (docked) undock() else dock()
            },
            "隐藏面板" to { hideMenu(); detachAll() }
        )
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.round(c, 12, Color.WHITE, 1, Ds.CARD_BORDER)
            elevation = Ds.dp(c, 6).toFloat()
            setPadding(Ds.dp(c, 4), Ds.dp(c, 4), Ds.dp(c, 4), Ds.dp(c, 4))
        }
        for ((label, act) in items) {
            box.addView(TextView(c).apply {
                text = label; textSize = 13f; setTextColor(Ds.TEXT)
                setPadding(Ds.dp(c, 12), Ds.dp(c, 9), Ds.dp(c, 12), Ds.dp(c, 9))
                setOnClickListener { act() }
            })
        }
        val wmInst = wm ?: c.getSystemService(WindowManager::class.java).also { wm = it }
        try {
            wmInst.addView(box, WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = -Ds.dp(c, 22)   // 与按钮组右对齐
                y = Ds.dp(c, 52)    // 落在按钮行下方
            })
            menuView = box
        } catch (e: Exception) {
            Log.w(TAG, "menu add: ${e.message}")
            menuView = null
        }
    }

    private fun hideMenu() {
        menuView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        menuView = null
    }

    /** 头像外圈脉冲（mockup @keyframes fishpulse）：alpha .35→0 + 微放大，1.6s 往复。
     *  800ms 单向 tween + postDelayed 反向，视图脱离窗口后自动停（parent == null）。 */
    private fun startPulse(v: View) {
        v.alpha = 0.35f
        val tick = object : Runnable {
            override fun run() {
                if (v.parent == null) return          // 面板已 detach：停表
                val out = v.alpha > 0.2f
                v.animate().alpha(if (out) 0f else 0.35f)
                    .scaleX(if (out) 1.1f else 1f)
                    .scaleY(if (out) 1.1f else 1f)
                    .setDuration(800).start()
                v.postDelayed(this, 800)
            }
        }
        v.post(tick)
    }

    // ---------------- 文本更新 ----------------

    private fun append(line: String) {
        val v = logLine ?: return
        v.text = (if (v.text.isNotEmpty()) v.text.toString() + "\n" else "") + line
        if ((v.text.split("\n").size) > 3) {
            v.text = v.text.split("\n").takeLast(3).joinToString("\n")
        }
        v.text = coloredLight(v.text.toString())
    }

    /** 进度 UI 同步（P3）：V10 进度条填充 + V11 球进度环与步数角标。 */
    private fun updateProgressUi() {
        val ratio = if (lastProgressTotal > 0)
            lastProgressN.toFloat() / lastProgressTotal else 0f
        progressFill?.let { fill ->
            fill.post {
                val parent = fill.parent as? android.view.ViewGroup ?: return@post
                fill.layoutParams.width = (parent.width * ratio).toInt().coerceAtLeast(0)
                fill.requestLayout()
            }
        }
        ring?.progress = ratio
        ballBadge?.text = if (lastProgressN > 0) "$lastProgressN" else "•"
    }

    /** 「第 n/total 步 · 目标」副标题（mockup .float-step）。 */
    private fun updateStepLine() {
        val v = stepLine ?: return
        val head = if (lastProgressTotal > 0) "第 $lastProgressN/$lastProgressTotal 步"
                   else "第 $lastProgressN 步"
        v.text = if (lastObjective.isEmpty()) head else "$head · $lastObjective"
    }

    /** 浅底逐行染色（V10 米白面板）：✓ 绿 / → 蓝 / ✗ 红 / 其余灰。 */
    private fun coloredLight(text: String): CharSequence {
        val sb = SpannableString(text)
        var idx = 0
        for (line in text.split("\n")) {
            if (line.isNotEmpty()) {
                val color = when (line.first()) {
                    '✓' -> Ds.GREEN
                    '→' -> Ds.PRIMARY
                    '✗' -> Ds.RED
                    else -> Ds.TEXT_3
                }
                sb.setSpan(ForegroundColorSpan(color), idx, idx + line.length, 0)
            }
            idx += line.length + 1
        }
        return sb
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
            if (silent) return@post          // R15：静默模式连红框都不画
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
