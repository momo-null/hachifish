package com.hachimi.app.gate

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 授权门控弹层（架构 §4.4，交互原型 S3 GateUI）。
 * 由无障碍服务在敏感操作（文本输入/跨 App 跳转/删除类）前以 dialog 主题拉起。
 * 结果码：10 仅本次允许 / 11 总是允许 / 12 拒绝。
 * M1' 后续：白名单（App × 操作类型）持久化；与内核的阻塞等待桥接。
 */
class GateActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetPkg = intent.getStringExtra(EXTRA_PACKAGE) ?: "?"
        val opType = intent.getStringExtra(EXTRA_OP) ?: "?"
        val preview = intent.getStringExtra(EXTRA_PREVIEW) ?: ""

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply {
            text = "授权门控\n\nHachimi 请求在「$targetPkg」中执行 $opType" +
                    (if (preview.isNotBlank()) "\n\n$preview" else "") +
                    "\n\n此操作由 AI 发起，可随时停止"
        })
        root.addView(Button(this).apply {
            text = "仅本次允许"
            setOnClickListener { finishWith(RESULT_ALLOW_ONCE) }
        })
        root.addView(Button(this).apply {
            text = "总是允许该应用的此类操作"
            setOnClickListener { finishWith(RESULT_ALLOW_ALWAYS) }
        })
        root.addView(Button(this).apply {
            text = "拒绝并停止"
            setOnClickListener { finishWith(RESULT_DENY) }
        })
        setContentView(root)
    }

    private fun finishWith(code: Int) {
        setResult(code)
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_OP = "op"
        const val EXTRA_PREVIEW = "preview"
        const val RESULT_ALLOW_ONCE = 10
        const val RESULT_ALLOW_ALWAYS = 11
        const val RESULT_DENY = 12
    }
}
