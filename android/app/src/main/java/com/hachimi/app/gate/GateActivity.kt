package com.hachimi.app.gate

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import com.hachimi.app.ui.Ds

/**
 * 授权门控弹层（架构 §4.4，交互原型 S3 GateUI 终态）：底部弹层——标题/请求正文/
 * 三按钮（仅本次允许 / 总是允许「App」的op / 拒绝并停止）+ 红线副文案。
 * 由无障碍服务在敏感操作（文本输入/跨 App 跳转）前以 dialog 主题拉起。
 * 结果码：10 仅本次允许 / 11 总是允许 / 12 拒绝。
 */
class GateActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetPkg = intent.getStringExtra(EXTRA_PACKAGE) ?: "?"
        val opType = intent.getStringExtra(EXTRA_OP) ?: "?"
        val preview = intent.getStringExtra(EXTRA_PREVIEW) ?: ""

        // 底部弹层形态（mockup .gate：上两角圆角 18，遮罩由 dialog 主题提供）
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
        // 抓手条
        sheet.addView(View(c).apply {
            background = Ds.round(c, 2, Ds.CARD_BORDER)
            layoutParams = LinearLayout.LayoutParams(Ds.dp(c, 36), Ds.dp(c, 4))
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        })
        sheet.addView(TextView(c).apply {
            text = "🔐 授权门控"
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ds.TEXT)
            setPadding(0, Ds.dp(c, 12), 0, Ds.dp(c, 4))
        })
        sheet.addView(TextView(c).apply {
            text = "Hachimi 请求在「$targetPkg」中执行 $opType" +
                    (if (preview.isNotBlank()) "：\n$preview" else "")
            textSize = 14f; setTextColor(Ds.TEXT)
            setPadding(0, Ds.dp(c, 4), 0, Ds.dp(c, 4))
        })
        sheet.addView(Ds.small(c, "此操作由 AI 发起 · 红色虚线框为当前定位目标 · 可随时停止").apply {
            setPadding(0, Ds.dp(c, 2), 0, Ds.dp(c, 8))
        })
        // 三按钮（mockup g1 主色 / g2 灰底主色字 / g3 红 tint）
        sheet.addView(Ds.button(c, "仅本次允许") { finishWith(RESULT_ALLOW_ONCE) })
        sheet.addView(Ds.button(c, "总是允许「$targetPkg」的$opType", primary = false) {
            finishWith(RESULT_ALLOW_ALWAYS)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ds.dp(c, 10)
        })
        sheet.addView(Ds.button(c, "拒绝并停止", primary = false, danger = true) {
            finishWith(RESULT_DENY)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ds.dp(c, 10)
        })

        setContentView(sheet)
    }

    private fun finishWith(code: Int) {
        // 结果落盘（调试桥/harness 经 run-as 读取）——M1' DoD⑥ 白名单记忆的前置
        try {
            val choice = when (code) {
                RESULT_ALLOW_ONCE -> "allow_once"
                RESULT_ALLOW_ALWAYS -> "allow_always"
                else -> "deny"
            }
            val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: "?"
            openFileOutput("gate_result.json", MODE_PRIVATE).use {
                it.write("""{"package":"$pkg","op":"${intent.getStringExtra(EXTRA_OP)}","choice":"$choice"}"""
                    .toByteArray())
            }
        } catch (_: Exception) {}
        // 阻塞桥回调：唤醒 GateManager.enforce 等待中的原语线程
        val req = intent.getLongExtra(EXTRA_REQUEST, -1)
        if (req >= 0) GateManager.resolve(req, code)
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
    }
}
