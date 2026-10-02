package com.hachimi.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 设计系统单一来源（P1.5 UI 终态）：色板/字号/圆角/组件工厂全部收敛于此，
 * 对齐 doc/ux_mockup.html <style>。全 app 禁止再散落硬编码颜色（DoD1）。
 * 继续「全代码构建 UI」惯例（零 XML，diff 评审友好）。
 */
object Ds {

    // ---------------- 色板（mockup <style> 逐条对应） ----------------
    val PAGE = Color.parseColor("#F4F3EE")          // 页面底色
    val TEXT = Color.parseColor("#1A1B1C")          // 正文
    val TEXT_2 = Color.parseColor("#6B7280")        // 次级
    val TEXT_3 = Color.parseColor("#9AA0A6")        // 弱化
    val PRIMARY = Color.parseColor("#235F7E")       // 品牌主色（按钮/选中/标题）
    val ACCENT = Color.parseColor("#8BC8EA")        // 浅蓝强调（h2 边条/seg 选中/时间线当前）
    val GREEN = Color.parseColor("#52C41A")         // 状态点/成功
    val GREEN_TXT = Color.parseColor("#3D8B13")
    val RED = Color.parseColor("#EA6668")           // 定位框/危险按钮描边
    val RED_TXT = Color.parseColor("#A04042")
    val RED_TINT = Color.parseColor("#1AEA6668")    // stop 底 rgba(234,102,104,.1)
    val ORANGE_BG = Color.parseColor("#38F4B393")   // 重试/暂停底 rgba(.22)
    val ORANGE_TXT = Color.parseColor("#8C4A2B")
    val PURPLE_BG = Color.parseColor("#339EACEA")   // 实验/视觉模型 rgba(.2)
    val PURPLE_TXT = Color.parseColor("#4A55A0")
    val GRAY_BG = Color.parseColor("#EEF0F3")       // 次级按钮/分段底
    val CARD_BORDER = Color.parseColor("#E4E3DD")
    val DARK = Color.parseColor("#1E2026")          // 日志窗/控制台底
    val DARK_TXT = Color.parseColor("#E6E9F0")
    val GREEN_LOG = Color.parseColor("#7BD88F")     // 日志 ok 行
    val DARK_SUB = Color.parseColor("#3A3D45")      // 暗底上的分隔/元素

    fun dp(c: Context, v: Int): Int =
        (v * c.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 页面根视图吃掉系统条 insets（2026-09-30 魅族 Android 16 实录：targetSDK 35+
     * 强制 edge-to-edge，标题与状态栏重合）。关闭 decor 贴合后按真实 insets 补
     * 顶/底留白，Android 12 与 16 行为一致；状态栏/导航条透明由调用方主题保证。
     */
    fun systemBars(activity: android.app.Activity, root: View,
                   extraTopDp: Int = 0, extraBottomDp: Int = 0) {
        activity.window.setDecorFitsSystemWindows(false)
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                android.view.WindowInsets.Type.statusBars()
                        or android.view.WindowInsets.Type.displayCutout()
                        or android.view.WindowInsets.Type.navigationBars())
            // IME insets：edge-to-edge 下 adjustResize 失效，键盘弹出必须手动把底部
            // padding 增加键盘高度，否则输入区被键盘覆盖（P1.5 UI 反馈 3，魅族实录）。
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
            v.setPadding(v.paddingLeft, bars.top + dp(activity, extraTopDp),
                v.paddingRight, bars.bottom + ime.bottom + dp(activity, extraBottomDp))
            insets
        }
    }

    /** LinearLayout 子项显式边距参数：addView(v, Ds.vp(...))——在 addView 前就带齐
     *  layoutParams（杜绝「先 addView 再改 params」的空指针/顺序坑）。 */
    fun vp(top: Int = 0, left: Int = 0, right: Int = 0, bottom: Int = 0,
           w: Int = LinearLayout.LayoutParams.MATCH_PARENT,
           h: Int = LinearLayout.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply { setMargins(left, top, right, bottom) }

    // ---------------- 容器 ----------------

    /** 白底圆角 14 卡片（mockup .card），带 1px 描边。 */
    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = round(c, 14, Color.WHITE, 1, CARD_BORDER)
        val p = dp(c, 14)
        setPadding(p, p, p, p)
    }

    /** 卡片列表项分隔用细灰线。 */
    fun divider(c: Context): View = View(c).apply {
        setBackgroundColor(CARD_BORDER)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)))
    }

    fun round(c: Context, radiusDp: Int, bg: Int, strokeW: Int = 0, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(c, radiusDp).toFloat()
            if (strokeW > 0) setStroke(Math.max(1, dp(c, strokeW)), stroke)
        }

    /** 区块标题（mockup h2）：17sp/600 + 4dp 浅蓝左边条。 */
    fun h2(c: Context, text: String): View {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(View(c).apply {
            layoutParams = LinearLayout.LayoutParams(dp(c, 4), LinearLayout.LayoutParams.MATCH_PARENT)
            background = round(c, 2, ACCENT)
        })
        row.addView(TextView(c).apply {
            setText(text); textSize = 17f; setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(c, 8), 0, 0, 0)
        })
        return row
    }

    /** 灰色小字说明（mockup .small）。 */
    fun small(c: Context, text: String): TextView = TextView(c).apply {
        setText(text); textSize = 11.5f; setTextColor(TEXT_2)
        setLineSpacing(dp(c, 2).toFloat(), 1f)
    }

    // ---------------- 控件 ----------------

    /** 胶囊按钮（mockup .btn-p 主色 / .btn-s 灰底 / danger=红 tint）。 */
    fun button(c: Context, label: String, primary: Boolean = true,
               danger: Boolean = false, onClick: () -> Unit): TextView {
        val bg = when {
            danger -> RED_TINT
            primary -> PRIMARY
            else -> GRAY_BG
        }
        val fg = when {
            danger -> RED_TXT
            primary -> Color.WHITE
            else -> PRIMARY
        }
        return TextView(c).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
            gravity = Gravity.CENTER
            background = round(c, 22, bg)
            val p = dp(c, 12)
            setPadding(p, p / 2 + dp(c, 2), p, p / 2 + dp(c, 2))
            setOnClickListener { onClick() }
        }
    }

    /** 小标签（mockup .tag + t-* 前景色）。 */
    fun tag(c: Context, text: String, fg: Int, bg: Int): TextView = TextView(c).apply {
        setText(text); textSize = 11f; setTextColor(fg)
        background = round(c, 20, bg)
        setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2))
    }

    /** appbar 连通 chip：绿点=已配置，灰点=未配置（mockup .chips/.dot）。 */
    fun chip(c: Context, label: String, ok: Boolean): TextView =
        tag(c, (if (ok) "● " else "○ ") + label,
            if (ok) GREEN_TXT else TEXT_3,
            if (ok) Color.parseColor("#1452C41A") else GRAY_BG)

    /** 输入框：白底圆角 10 描边。 */
    fun field(c: Context, hint: String, single: Boolean = true): EditText = EditText(c).apply {
        this.hint = hint
        textSize = 14f; setTextColor(TEXT); setHintTextColor(TEXT_3)
        setSingleLine(single)
        background = round(c, 10, Color.WHITE, 1, CARD_BORDER)
        val p = dp(c, 10)
        setPadding(p, p / 2 + dp(c, 2), p, p / 2 + dp(c, 2))
    }

    /**
     * 分段控件（mockup .seg/.on）：外层灰底圆角 12，选中段浅蓝圆角 9。
     * options 与 onPick 下标一一对应；select() 供外部改选中（双向同步）。
     */
    fun seg(c: Context, options: List<String>, selected: Int,
            onPick: (Int) -> Unit): Seg {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(c, 12, GRAY_BG)
            // 组件内部钉满父宽：cells 的 weight=1 才有分配空间（否则窄条折行）
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            val p = dp(c, 3)
            setPadding(p, p, p, p)
        }
        val cells = mutableListOf<TextView>()
        options.forEachIndexed { i, opt ->
            val cell = TextView(c).apply {
                text = opt; textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val cp = dp(c, 8)
                setPadding(cp, cp / 2 + dp(c, 2), cp, cp / 2 + dp(c, 2))
            }
            cells.add(cell)
            row.addView(cell)
        }
        // 构造后立即绘制选中态：此前只把下标存进 Seg 不绘制 —— 未经外部 select()
        // 的分段控件全是「无高亮」外观（看着像未选择）。
        val seg = Seg(row, cells, selected, options).also { it.select(selected) }
        // 点击先绘选中态再回调业务：此前每个调用点各自负责 select()，漏了就变成
        // 「点了没反应」（设置页运行模式、知识页技能/记忆/世界模型即此坑）。
        cells.forEachIndexed { i, cell ->
            cell.setOnClickListener { seg.select(i); onPick(i) }
        }
        return seg
    }

    class Seg internal constructor(
        val view: LinearLayout,
        private val cells: List<TextView>,
        private var selected: Int,
        private val options: List<String>
    ) {
        /** 重绘选中态（外部状态变化时调用，如设置页与主界面双向同步）。 */
        fun select(i: Int) {
            selected = i
            cells.forEachIndexed { j, cell ->
                val on = j == selected
                cell.background = if (on) round(cell.context, 9, ACCENT)
                else GradientDrawable().apply { setColor(Color.TRANSPARENT) }
                cell.setTextColor(if (on) PRIMARY else TEXT_2)
                cell.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }

        val current: String get() = options.getOrNull(selected) ?: options.first()
    }

    /** 暗色等宽日志窗（mockup .log，#1E2026 圆角 10）。 */
    fun darkLog(c: Context, maxLines: Int = 4): TextView = TextView(c).apply {
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextColor(DARK_TXT)
        setMaxLines(maxLines)
        background = round(c, 10, DARK)
        val p = dp(c, 10)
        setPadding(p, p / 2, p, p / 2)
        setLineSpacing(dp(c, 2).toFloat(), 1f)
    }

    /** appbar：logo 行（15sp/700）+ 右侧 chips。 */
    fun appBar(c: Context, title: String, right: List<View>): LinearLayout {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(c).apply {
            text = title; textSize = 15f; setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        right.forEach { row.addView(it) }
        return row
    }

    /** 空态占位卡（诚实空态口径：居中图形符号 + 主文案 + 说明）。 */
    fun emptyState(c: Context, glyph: String, title: String, detail: String): LinearLayout {
        val box = card(c)
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(box.paddingLeft, dp(c, 28), box.paddingRight, dp(c, 28))
        box.addView(TextView(c).apply {
            text = glyph; textSize = 30f; gravity = Gravity.CENTER
            setTextColor(TEXT_3)
        })
        box.addView(TextView(c).apply {
            text = title; textSize = 15f; setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, dp(c, 10), 0, 0)
        })
        box.addView(small(c, detail).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(c, 4), 0, 0)
        })
        return box
    }
}
