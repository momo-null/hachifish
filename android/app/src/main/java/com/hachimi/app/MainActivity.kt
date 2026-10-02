package com.hachimi.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hachimi.app.ui.ChatPage
import com.hachimi.app.ui.Ds
import com.hachimi.app.ui.KnowledgePage
import com.hachimi.app.ui.SettingsPage
import com.hachimi.app.ui.TasksPage

/**
 * 产品主壳（P1 起为 4-Tab：聊天/任务/知识/设置，聊天默认——chat 为主助手形态，
 * redesign_plan A3）。P5 移除旧任务 Tab 后回到 3-Tab 终态。
 * - 内核随 App 启动（幂等；harness 依赖：MainActivity 拉起 KernelHostService——红线，
 *   重写时不可动）。
 * - 任务执行链路 = Chaquopy 直调（TasksPage 保留 + ChatPage 复刻同链路）。
 * - 首次进入进 OnboardingActivity（S1 四步授权；「权限引导仅首次出现」）。
 * - readableErrorStatic：人话错误映射单一来源（DoD4），各页共用。
 */
class MainActivity : Activity() {

    private lateinit var content: FrameLayout
    private lateinit var navBar: LinearLayout
    private var chatPage: ChatPage? = null
    private var tasksPage: TasksPage? = null
    private var knowledgePage: KnowledgePage? = null
    private var settingsPage: SettingsPage? = null
    private var currentTab = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoleStore.ensure(this)   // A2：角色配置 → filesDir/character.md 幂等同步（K0 注入生效前提）
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ds.PAGE)
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        navBar = buildNavBar()
        root.addView(navBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ds.dp(this, 58)))
        setContentView(root)
        Ds.systemBars(this, root)
        // 内核随 App 启动（幂等；harness 依赖不变）
        KernelHostService.start(this)
        // 首次初始化引导（权限引导仅首次出现）；例外：覆盖安装/重装后系统会禁用
        // 无障碍服务，此时 prefs 里 onboarding_done 仍为 true 但通道已断——
        // 必须重弹引导页让用户重拨（代码注释里"安装后需重拨无障碍"的产品化闭环）。
        // 无障碍态查系统 ENABLED_ACCESSIBILITY_SERVICES 而非 service.instance：
        // 冷启动时服务未必已 bind，instance 竞态会漏判。
        val accOn = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true
        if (!getSharedPreferences(TasksPage.PREFS, 0).getBoolean("onboarding_done", false) || !accOn) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        switchTab(0)
        SettingsPage.onRunModeChanged = { tasksPage?.onResume() }
        // 多窗口（真分屏）下提供一键切控制台页：S4 语义——分屏里 Hachimi 这一半是 agent 面板
        if (isInMultiWindowMode) {
            val tip = TextView(this).apply {
                text = "▣ 分屏中 · 切到控制台视图"
                textSize = 13f; setTextColor(Ds.PRIMARY)
                background = Ds.round(this@MainActivity, 10, com.hachimi.app.ui.Ds.GRAY_BG)
                setPadding(Ds.dp(this@MainActivity, 14), Ds.dp(this@MainActivity, 8),
                    Ds.dp(this@MainActivity, 14), Ds.dp(this@MainActivity, 8))
                setOnClickListener { startActivity(Intent(this@MainActivity, ConsoleActivity::class.java)) }
            }
            content.addView(tip, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onResume() {
        super.onResume()
        when (currentTab) {
            0 -> { chatPage?.onResume(); tasksPage?.onResume() }   // tasksPage 惰性不再创建，?. 恒 null 安全
            1 -> knowledgePage?.onResume()
            else -> settingsPage?.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        // 事件订阅与可见性同步（K4 转发器生命周期；后台时浮窗继续消费事件）
        chatPage?.onPause()
    }

    // ---------------- 底部导航（对齐设计稿 V8：💬 聊天 / 🧠 知识 / ⚙ 设置） ----------------

    private fun buildNavBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
        }
        val spec = listOf(
            Triple("💬", "聊天", 0), Triple("🧠", "知识", 1), Triple("⚙", "设置", 2))
        spec.forEach { (glyph, label, tab) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { switchTab(tab) }
            }
            cell.addView(TextView(this).apply {
                text = glyph; textSize = 17f; gravity = Gravity.CENTER
            })
            cell.addView(TextView(this).apply {
                text = label; textSize = 11f; gravity = Gravity.CENTER
                setPadding(0, Ds.dp(this@MainActivity, 1), 0, 0)
            })
            cell.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            bar.addView(cell)
        }
        return bar
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        content.removeAllViews()
        val page: View = when (tab) {
            0 -> (chatPage ?: ChatPage(this).also { chatPage = it }).view
            1 -> (knowledgePage ?: KnowledgePage(this).also { knowledgePage = it }).view
            else -> (settingsPage ?: SettingsPage(this).also { settingsPage = it }).view
        }
        content.addView(page, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        navBar.cells().forEachIndexed { i, v ->
            val on = i == tab
            val glyph = v.getChildAt(0) as TextView
            val label = v.getChildAt(1) as TextView
            glyph.setTextColor(if (on) Ds.PRIMARY else Ds.TEXT_3)
            label.setTextColor(if (on) Ds.PRIMARY else Ds.TEXT_3)
            label.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        if (tab == 0) chatPage?.onResume()
        if (tab == 1) knowledgePage?.onResume()
        // 设置页同样如此：此前只有聊天/知识 Tab 在切换时重读状态，设置页从未被
        // 调用 —— 偏好回显只在 Activity.onResume 生效，表现为「点回设置页全是未选中」。
        if (tab == 2) settingsPage?.onResume()
    }

    private fun LinearLayout.cells(): List<LinearLayout> =
        (0 until childCount).map { getChildAt(it) as LinearLayout }

    // ---------------- 录屏授权宿主（TasksPage 环境卡与 Onboarding 共用入口） ----------------

    fun requestProjection() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            KernelHostService.startProjection(this, resultCode, data)
            tasksPage?.onResume()
        }
    }

    companion object {
        private const val REQ_PROJECTION = 1002

        /** DoD4：错误必须明确可读——按常见失败模式给出人话 + 保留原始信息。
         *  静态单一来源：TasksPage / SettingsPage / RunResultView 共用。 */
        fun readableErrorStatic(raw: String): String = when {
            raw.contains("task already running") ->
                "已有任务在运行中。请先按 ⏹ 停止，再开始新任务。"
            raw.contains("no brain configured") ->
                "未配置模型端点（BYOK）。请到 设置 → 通道（BYOK） 填写并保存。"
            raw.contains("HTTP 401") || raw.contains("HTTP 403") ->
                "API 密钥无效或无权限（401/403）。请检查设置页的 API Key。\n[$raw]"
            raw.contains("HTTP 404") ->
                "端点不存在（404）。base_url 需填到 /v1 这一级（应用会自动拼接 /chat/completions）。\n[$raw]"
            raw.contains("HTTP 429") ->
                "请求过于频繁或账户额度不足（429）。\n[$raw]"
            raw.contains("URLError") || raw.contains("timed out", true) ->
                "网络不通或请求超时。请检查网络连接与端点地址是否可达。\n[$raw]"
            raw.contains("unexpected response shape") ->
                "端点已连通，但响应不是 OpenAI 兼容格式。请确认模型服务支持 /chat/completions。\n[$raw]"
            raw.contains("HTTP 400") ->
                "请求被端点拒绝（400）。可能该端点/模型不支持图片输入，或模型名有误。\n[$raw]"
            else -> raw
        }
    }
}
