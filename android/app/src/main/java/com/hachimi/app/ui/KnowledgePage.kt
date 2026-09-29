package com.hachimi.app.ui

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * S6 知识 Tab（骨架+诚实空态口径，P1.5 拍板）：三段位布局与设计稿一致，
 * 数据面随 P2 接线——本期只填结构，不假装有数据（能力默认关 K0 红线）。
 */
class KnowledgePage(private val act: Activity) {

    private var seg: Ds.Seg? = null
    private lateinit var bodyHost: LinearLayout
    private lateinit var page: ScrollView

    val view: View
        get() {
            if (!::page.isInitialized) page = build()
            return page
        }

    fun onResume() {}

    private fun build(): ScrollView {
        val pad = Ds.dp(act, 20)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.setPadding(pad, Ds.dp(act, 12), pad, Ds.dp(act, 20))

        box.addView(Ds.appBar(act, "知识", listOf(
            Ds.tag(act, "弱注入：关", Ds.TEXT_3, Ds.GRAY_BG))))
        box.addView(Ds.small(act, "技能库 · 全局记忆 · 世界模型（桌面版 K 系列三件套上机）").apply {
            setPadding(0, 0, 0, Ds.dp(act, 10))
        })

        seg = Ds.seg(act, listOf("技能", "记忆", "世界模型"), 0) { renderBody(it) }
            .also { box.addView(it.view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }

        bodyHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(bodyHost, Ds.vp(top = Ds.dp(act, 10)))
        renderBody(0)
        return ScrollView(act).apply { addView(box) }
    }

    private fun renderBody(section: Int) {
        seg?.select(section)
        val (glyph, title, detail) = when (section) {
            0 -> Triple("◈", "技能库尚未开启",
                "任务成功轨迹将蒸馏为候选技能，连续 3 次成功晋升正式 skill（晋升门可视化）。")
            1 -> Triple("≡", "全局记忆尚未开启",
                "跨任务事实沉淀（MEMORY.md），下次任务前做弱注入摘要。")
            else -> Triple("◇", "世界模型尚未开启",
                "App 布局/控件事实图谱，随知识能力一并上线。")
        }
        bodyHost.removeAllViews()
        bodyHost.addView(Ds.emptyState(act, glyph, title, "$detail\n\n知识能力随 P2 上线，届时在 设置 → 知识 显式开启。"))
    }
}
