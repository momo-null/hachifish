package com.hachimi.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hachimi.app.ui.Ds

/**
 * S8 实验模式（骨架+诚实空态口径）：研究者界面壳——harness 连接状态/注入快照/
 * Curator 冻结/实时指标的真实数据面随研究线（M4' 12/15 暂停）恢复后接回。
 * 入口 = 设置 → 实验 → 版本号连点 5 次。红色警示语义（蒸馏不写入正式库）保留原文。
 */
class ExperimentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ds.dp(this, 20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.setPadding(pad, Ds.dp(this, 12), pad, Ds.dp(this, 20))

        box.addView(Ds.appBar(this, "🔬 实验模式", listOf(
            Ds.tag(this, "实验线已冻结", Ds.PURPLE_TXT, Ds.PURPLE_BG))))

        // harness 状态条（紫）：真实连接随研究线恢复
        box.addView(TextView(this).apply {
            text = "实验线暂停中（M4' 冻结于 12/15）——harness 通道、组别注入、\n" +
                    "断点续跑随研究线恢复后重新开放。"
            textSize = 13f; setTextColor(Ds.PURPLE_TXT)
            background = Ds.round(this@ExperimentActivity, 10, Ds.PURPLE_BG)
            setPadding(Ds.dp(this@ExperimentActivity, 12), Ds.dp(this@ExperimentActivity, 8),
                Ds.dp(this@ExperimentActivity, 12), Ds.dp(this@ExperimentActivity, 8))
        }, Ds.vp(top = Ds.dp(this, 10)))

        // 注入快照（只读）卡
        box.addView(Ds.h2(this, "注入快照（只读）").apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, Ds.dp(this@ExperimentActivity, 16), 0, Ds.dp(this@ExperimentActivity, 6)) }
        })
        box.addView(Ds.emptyState(this, "🔒", "实验期冻结",
            "组任务蒸馏触发点被断言禁止（Curator SUPPRESSED）；快照 SHA 校验通道保留。"))

        // 红色警示卡（mockup 原文语义）
        box.addView(TextView(this).apply {
            text = "⚠ 实验模式下蒸馏结果不写入正式知识库；每组每任务 ≥30 次。"
            textSize = 13f; setTextColor(Ds.RED_TXT)
            background = Ds.round(this@ExperimentActivity, 10, Color.TRANSPARENT, 2, Ds.RED)
            setPadding(Ds.dp(this@ExperimentActivity, 12), Ds.dp(this@ExperimentActivity, 10),
                Ds.dp(this@ExperimentActivity, 12), Ds.dp(this@ExperimentActivity, 10))
        }, Ds.vp(top = Ds.dp(this, 12)))

        val root = ScrollView(this).apply { addView(box) }
        Ds.systemBars(this, root, extraBottomDp = 8)
        setContentView(root)
    }
}
