package com.hachimi.app.gate

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hachimi.app.ui.Ds
import com.hachimi.app.ui.FishAssets

/**
 * 授权门控弹层（P3 换皮 + 超时，redesign_plan A4 / V12）：
 * - 大肥鱼疑问脸 +「主人，这个操作要你点头」——冷冰冰的权限弹窗变委屈萌请示
 * - **10s 倒计时自动拒绝**（安全默认值：用户不在场时不放行敏感操作；
 *   倒计时可见，拒绝语义沿用 gate=12 = 拒绝并停止）
 * - 三级授权不变：10 仅本次允许 / 11 总是允许 / 12 拒绝并停止
 * 由无障碍服务在敏感操作前以 dialog 主题拉起。
 */
class GateActivity : Activity() {

    private val timer = Handler(Looper.getMainLooper())
    private var secondsLeft = TIMEOUT_S
    private var countdownLabel: TextView? = null
    private var timedOut = false

    /** 每秒刷新倒计时；归零自动拒绝（幂等：手动选择后 timer 已清）。 */
    private val tick = object : Runnable {
        override fun run() {
            if (isFinishing) return
            secondsLeft--
            if (secondsLeft <= 0) {
                timedOut = true
                finishWith(RESULT_DENY)
                return
            }
            countdownLabel?.text = "$secondsLeft 秒未选择将自动拒绝"
            timer.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetPkg = intent.getStringExtra(EXTRA_PACKAGE) ?: "?"
        val opType = intent.getStringExtra(EXTRA_OP) ?: "?"
        val preview = intent.getStringExtra(EXTRA_PREVIEW) ?: ""

        window.apply {
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(-1)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val c = this
        val sheet = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.round(c, 18, Color.WHITE)
            setPadding(Ds.dp(c, 20), Ds.dp(c, 18), Ds.dp(c, 20), Ds.dp(c, 20))
        }
        sheet.addView(View(c).apply {
            background = Ds.round(c, 2, Ds.CARD_BORDER)
            layoutParams = LinearLayout.LayoutParams(Ds.dp(c, 36), Ds.dp(c, 4))
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        })
        // 头部：大肥鱼疑问脸 + 请示文案（V12 语义）
        val head = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ds.dp(c, 12), 0, 0)
        }
        head.addView(ImageView(c).apply {
            setImageResource(FishAssets.EMPTY)
            adjustViewBounds = true
            maxHeight = Ds.dp(c, 64)
        }, LinearLayout.LayoutParams(Ds.dp(c, 64), LinearLayout.LayoutParams.WRAP_CONTENT))
        val headText = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ds.dp(c, 12)
            setPadding(p, 0, 0, 0)
        }
        headText.addView(TextView(c).apply {
            text = "主人，这个操作要你点头"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ds.TEXT)
        })
        headText.addView(Ds.small(c, "大肥鱼不会自作主张，敏感操作必先请示"))
        head.addView(headText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sheet.addView(head)
        // 操作详情（透明三行：操作 / 用途红线 / 定位说明）
        sheet.addView(TextView(c).apply {
            text = "即将执行：在「$targetPkg」中 $opType" +
                    (if (preview.isNotBlank()) "\n内容：$preview" else "")
            textSize = 13f; setTextColor(Ds.TEXT)
            setLineSpacing(Ds.dp(c, 2).toFloat(), 1f)
            setPadding(0, Ds.dp(c, 10), 0, Ds.dp(c, 2))
        })
        sheet.addView(Ds.small(c, "此操作由 AI 发起 · 红色虚线框为当前定位目标 · 可随时停止").apply {
            setPadding(0, 0, 0, Ds.dp(c, 8))
        })
        // 三级授权（V12 文案）
        // 注意：不能 addView 单参 + apply 改 layoutParams——apply 在 addView 之前执行，
        // 此时 layoutParams 为 null，`as LinearLayout.LayoutParams` 强转即 NPE（真机崩溃根因）。
        // 必须显式传双参 addView（先构造 LP）。
        fun spaced(view: TextView, topDp: Int) {
            sheet.addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ds.dp(c, topDp)
            })
        }
        spaced(Ds.button(c, "允许一次") { finishWith(RESULT_ALLOW_ONCE) }, 0)
        spaced(Ds.button(c, "本次任务都放行") { finishWith(RESULT_ALLOW_ALWAYS) }, 10)
        spaced(Ds.button(c, "拒绝并停止任务", primary = false, danger = true) {
            finishWith(RESULT_DENY)
        }, 10)
        countdownLabel = TextView(c).apply {
            text = "$secondsLeft 秒未选择将自动拒绝"
            textSize = 11f; setTextColor(Ds.TEXT_3)
            gravity = Gravity.CENTER
            setPadding(0, Ds.dp(c, 8), 0, 0)
        }
        sheet.addView(countdownLabel)

        setContentView(sheet)
        timer.postDelayed(tick, 1000)
    }

    override fun onDestroy() {
        timer.removeCallbacks(tick)   // 手动选择/系统回收都清倒计时，防泄漏与双触发
        super.onDestroy()
    }

    private fun finishWith(code: Int) {
        timer.removeCallbacks(tick)
        try {
            val choice = when (code) {
                RESULT_ALLOW_ONCE -> "allow_once"
                RESULT_ALLOW_ALWAYS -> "allow_always"
                else -> if (timedOut) "deny_timeout" else "deny"   // 超时拒绝留痕可观测
            }
            val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: "?"
            openFileOutput("gate_result.json", MODE_PRIVATE).use {
                it.write("""{"package":"$pkg","op":"${intent.getStringExtra(EXTRA_OP)}","choice":"$choice"}"""
                    .toByteArray())
            }
        } catch (_: Exception) {}
        val req = intent.getLongExtra(EXTRA_REQUEST, -1)
        if (req >= 0) {
            if (timedOut) GateManager.resolveTimeout(req) else GateManager.resolve(req, code)
        }
        setResult(code)
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_OP = "op"
        const val EXTRA_PREVIEW = "preview"
        const val EXTRA_REQUEST = "request"
        const val RESULT_ALLOW_ONCE = 10
        const val RESULT_ALLOW_ALWAYS = 11
        const val RESULT_DENY = 12
        const val TIMEOUT_S = 10
    }
}
