package com.hachimi.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hachimi.app.ui.Ds
import com.hachimi.app.ui.TasksPage

/**
 * S1 首次初始化（终态）：四步授权链——无障碍 → 屏幕录制 → 电池优化白名单 →
 * 模型接入（BYOK）。权限引导仅首次出现（prefs onboarding_done，完成时写入）；
 * 跳转均为系统官方入口（电池白名单走设置列表页——R2 零新增权限）。
 */
class OnboardingActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var stepsHost: LinearLayout
    private lateinit var page: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ds.dp(this, 20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.setPadding(pad, Ds.dp(this, 12), pad, Ds.dp(this, 20))

        box.addView(Ds.appBar(this, "Hachifish",
            listOf(Ds.tag(this, "v0.1 · 初始化", Ds.TEXT_2, Ds.GRAY_BG))))

        // —— P5 欢迎首屏（V1：大肥鱼「没吃饱」自嘲萌开场，四步授权之前）——
        val welcome = Ds.card(this)
        welcome.gravity = android.view.Gravity.CENTER_HORIZONTAL
        welcome.addView(android.widget.ImageView(this).apply {
            setImageResource(com.hachimi.app.ui.FishAssets.GUIDE)
            adjustViewBounds = true
            maxHeight = Ds.dp(this@OnboardingActivity, 150)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
        welcome.addView(TextView(this).apply {
            text = "才不是吃白饭的好吗！说一句目标，我替你点完"
            textSize = 12.5f; setTextColor(Ds.TEXT_2)
            gravity = android.view.Gravity.CENTER
            setLineSpacing(Ds.dp(this@OnboardingActivity, 2).toFloat(), 1f)
            setPadding(0, Ds.dp(this@OnboardingActivity, 10), 0, 0)
        })
        welcome.addView(TextView(this).apply {
            text = "大肥鱼前来报到"
            textSize = 18f; setTextColor(Ds.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(0, Ds.dp(this@OnboardingActivity, 8), 0, Ds.dp(this@OnboardingActivity, 4))
        })
        welcome.addView(Ds.small(this, "说一句目标，大肥鱼替你点完——文件、App、消息都行").apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, Ds.dp(this@OnboardingActivity, 10))
        })
        // V1 功能列表三项（mockup onboard-features）
        listOf(
            Pair("📱", "打开 App 替你点完目标"),
            Pair("⌨", "输入文字、设置提醒"),
            Pair("🧠", "越用越懂你，自动学习")
        ).forEach { (glyph, text) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, Ds.dp(this@OnboardingActivity, 4), 0, 0)
            }
            row.addView(TextView(this).apply {
                this.text = glyph; textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                this.text = text; textSize = 13f; setTextColor(Ds.TEXT_2)
                setPadding(Ds.dp(this@OnboardingActivity, 10), 0, 0, 0)
            })
            welcome.addView(row)
        }
        box.addView(welcome, Ds.vp(bottom = Ds.dp(this, 14)))

        box.addView(Ds.small(this, "四步授权：无障碍 → 投屏 → 白名单 → BYOK。研究机一次配好，之后免维护。").apply {
            setPadding(0, 0, 0, Ds.dp(this@OnboardingActivity, 12))
        })

        stepsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(stepsHost)

        box.addView(Ds.button(this, "完成，进入主界面") {
            getSharedPreferences(TasksPage.PREFS, 0).edit().putBoolean("onboarding_done", true).apply()
            finish()
        }, Ds.vp(top = Ds.dp(this, 16)))
        box.addView(Ds.small(this, "权限引导仅首次出现 · 跳转均为系统官方入口").apply {
            gravity = android.view.Gravity.CENTER; setPadding(0, Ds.dp(this@OnboardingActivity, 10), 0, 0)
        })

        page = ScrollView(this).apply { addView(box) }
        Ds.systemBars(this, page, extraBottomDp = 8)
        setContentView(page)
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        if (!::stepsHost.isInitialized) return
        stepsHost.removeAllViews()
        val cfg = BrainConfigStore.load(this)
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val card = Ds.card(this)
        step(card, 1, "无障碍服务",
            if (HachimiAccessibilityService.instance != null) "已开启 · 操作通道（官方 API，不 root）" else "操作通道，必选",
            HachimiAccessibilityService.instance != null) {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        step(card, 2, "屏幕录制",
            if (ProjectionHolder.isRunning) "已授权 · 录屏模式一次授权，连续取帧" else "视觉观察通道（可稍后在主界面开启）",
            ProjectionHolder.isRunning) { requestProjection() }
        step(card, 3, "电池优化白名单",
            if (pm.isIgnoringBatteryOptimizations(packageName))
                "已加入 · ColorOS 另请在 应用管理→Hachifish 打开「允许自启动」"
            else "防止系统回收无障碍服务",
            pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        step(card, 4, "模型接入（BYOK）",
            if (cfg != null) "已配置 · ${cfg.model}" else "填入你自备的端点与密钥",
            cfg != null) { startActivity(Intent(this, SettingsActivity::class.java)) }
        card.addView(Ds.small(this,
            "Key 仅存本机 Keystore，不落库。服务保活说明：无障碍与录屏会话跟随应用进程，" +
            "进程被杀（划掉后台/安装）后需重拨无障碍、重授权录屏；正常使用（Back 键退出）全程免维护。").apply {
            setPadding(0, Ds.dp(this@OnboardingActivity, 8), 0, 0)
        })
        stepsHost.addView(card)

        // 就绪项变化实时回显（无障碍开启在系统页，回到本页 onResume 刷新；内核冷启动轮询补帧）
        if (KernelHostService.pyBridgeModule == null) {
            main.postDelayed({ refresh() }, 2000)
        }
    }

    /** 步骤行（mockup S1 .step）：✓ 绿 / 当前步序号，名称 + 状态小字 + 去开启。 */
    private fun step(host: LinearLayout, no: Int, title: String, desc: String, ok: Boolean, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Ds.dp(this@OnboardingActivity, 8), 0, Ds.dp(this@OnboardingActivity, 8))
        }
        row.addView(TextView(this).apply {
            text = if (ok) "✓" else no.toString()
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (ok) Ds.GREEN else Ds.PRIMARY)
            background = Ds.round(this@OnboardingActivity, 14,
                if (ok) Color.argb(30, 0x52, 0xC4, 0x1A) else Ds.GRAY_BG)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(Ds.dp(this@OnboardingActivity, 30), Ds.dp(this@OnboardingActivity, 30))
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title; textSize = 14.5f; setTextColor(Ds.TEXT); typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(Ds.small(this, desc).apply { setPadding(0, Ds.dp(this@OnboardingActivity, 1), 0, 0) })
        row.addView(col.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(Ds.dp(this@OnboardingActivity, 10), 0, 0, 0) }
        })
        if (!ok) {
            row.addView(Ds.button(this, "去开启", primary = false) { action() }.apply {
                textSize = 12f
                setPadding(Ds.dp(this@OnboardingActivity, 10), Ds.dp(this@OnboardingActivity, 4),
                    Ds.dp(this@OnboardingActivity, 10), Ds.dp(this@OnboardingActivity, 4))
            })
        }
        host.addView(row)
        if (no < 4) host.addView(Ds.divider(this))
    }

    /** 录屏授权（与主界面同一系统弹窗；结果交 KernelHostService 起观察通道）。 */
    private fun requestProjection() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            KernelHostService.startProjection(this, resultCode, data)
            main.postDelayed({ refresh() }, 800)
        }
    }

    companion object {
        private const val REQ_PROJECTION = 2001
    }
}
