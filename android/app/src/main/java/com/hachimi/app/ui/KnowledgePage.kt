package com.hachimi.app.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hachimi.app.KernelHostService
import org.json.JSONObject

/**
 * 知识 Tab（K7 接真数据，redesign_plan 原里程碑 P2 完成）：
 * - 技能：kernel skills_list_json（Curator 蒸馏/晋升产出的全局 skill）
 * - 记忆：memory_json（MEMORY.md 聚合视图）+ rollouts_list_json（蒸馏记录）
 * - 弱注入开关状态实时回显（设置页 ↔ 头部 tag 双向同步）
 * 世界模型段保留诚实占位（task 级资产，产品视图后置）。
 * 数据在 onResume 拉取（每次进 Tab 刷新——Curator 任务后随时过来看新货）。
 */
class KnowledgePage(private val act: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private var seg: Ds.Seg? = null
    private var injectTag: TextView? = null
    private lateinit var bodyHost: LinearLayout
    private lateinit var page: ScrollView
    private var section = 0

    val view: View
        get() {
            if (!::page.isInitialized) page = build()
            return page
        }

    fun onResume() {
        if (!::page.isInitialized) return
        refreshInjectTag()
        load(section)
    }

    private fun refreshInjectTag() {
        val on = act.getSharedPreferences(TasksPage.PREFS, 0)
            .getBoolean("knowledge_enabled", true)
        injectTag?.text = "弱注入：${if (on) "开" else "关"}"
        injectTag?.setTextColor(if (on) Ds.GREEN_TXT else Ds.TEXT_3)
    }

    private fun build(): ScrollView {
        val pad = Ds.dp(act, 20)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.setPadding(pad, Ds.dp(act, 12), pad, Ds.dp(act, 20))

        injectTag = Ds.tag(act, "弱注入：开", Ds.GREEN_TXT, Ds.GRAY_BG)
        box.addView(Ds.appBar(act, "知识", listOf(injectTag!!)))
        box.addView(Ds.small(act, "技能库 · 全局记忆 —— 任务跑完自动蒸馏，下次任务弱注入参考").apply {
            setPadding(0, 0, 0, Ds.dp(act, 10))
        })

        seg = Ds.seg(act, listOf("技能", "记忆", "世界模型"), 0) {
            section = it; load(it)
        }.also { box.addView(it.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }

        bodyHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(bodyHost, Ds.vp(top = Ds.dp(act, 10)))
        return ScrollView(act).apply { addView(box) }
    }

    // ---------------- 数据拉取（K7c 数据面消费） ----------------

    private fun load(sec: Int) {
        seg?.select(sec)
        if (sec == 2) {   // 世界模型：task 级资产，产品视图后置（诚实占位）
            renderWorldModel()
            return
        }
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) {
            bodyHost.removeAllViews()
            bodyHost.addView(Ds.emptyState(act, "⏳", "内核启动中",
                "首次约需 10 秒；就绪后下拉本页刷新。"))
            return
        }
        bodyHost.removeAllViews()
        bodyHost.addView(Ds.small(act, "读取中…"))
        Thread {
            try {
                val payload = if (sec == 0) {
                    bridge.callAttr("skills_list_json").toString()
                } else {
                    bridge.callAttr("memory_json").toString()
                }
                val extra = if (sec == 1)
                    bridge.callAttr("rollouts_list_json", 30).toString() else null
                main.post { render(sec, payload, extra) }
            } catch (e: Exception) {
                main.post {
                    bodyHost.removeAllViews()
                    bodyHost.addView(Ds.emptyState(act, "!", "读取失败",
                        "${e.javaClass.simpleName}——回前台重试。"))
                }
            }
        }.start()
    }

    private fun render(sec: Int, payload: String, extra: String?) {
        val r = JSONObject(payload)
        if (sec == 0) renderSkills(r) else renderMemory(r, extra)
    }

    /** 技能列表：名称 + 匹配规则 + 描述。 */
    private fun renderSkills(r: JSONObject) {
        bodyHost.removeAllViews()
        val arr = r.optJSONArray("skills") ?: return
        if (arr.length() == 0) {
            bodyHost.addView(Ds.emptyState(act, "◈", "还没有技能",
                "任务成功跑完后，大肥鱼会把操作套路蒸馏成候选技能；\n" +
                "连续 3 次成功自动晋升为正式技能（可注入下次任务参考）。"))
            return
        }
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val card = Ds.card(act)
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(act).apply {
                text = s.optString("name"); textSize = 14f; setTextColor(Ds.TEXT)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // V7 徽标：7 天内创建 = NEW；已使用 = ACTIVE
            val created = s.optString("created_at")
            val isNew = runCatching {
                !created.isBlank() && System.currentTimeMillis() -
                    java.time.OffsetDateTime.parse(created).toInstant().toEpochMilli() < 7 * 86400_000L
            }.getOrDefault(false)
            val used = s.optInt("use_count", 0) > 0
            head.addView(Ds.tag(act,
                if (isNew) "NEW" else if (used) "ACTIVE" else "技能",
                if (isNew) Ds.PRIMARY else if (used) Ds.GREEN_TXT else Ds.PRIMARY,
                Color.parseColor("#E8F1FA")))
            card.addView(head)
            s.optString("objective_pattern").takeIf { it.isNotBlank() }?.let {
                card.addView(Ds.small(act, "匹配：$it").apply { setPadding(0, Ds.dp(act, 4), 0, 0) })
            }
            s.optString("description").takeIf { it.isNotBlank() }?.let {
                card.addView(Ds.small(act, it).apply { setPadding(0, Ds.dp(act, 2), 0, 0) })
            }
            // V7 统计行：使用次数 + 成功率（数据缺省时跳过该行）
            val useCount = s.optInt("use_count", 0)
            val successCount = s.optInt("success_count", 0)
            if (useCount > 0) {
                val rate = if (useCount > 0) successCount * 100 / useCount else 0
                card.addView(Ds.small(act, "已使用 $useCount 次 · 成功率 $rate%").apply {
                    setTextColor(Ds.TEXT_3); setPadding(0, Ds.dp(act, 4), 0, 0)
                })
            }
            bodyHost.addView(card, Ds.vp(top = Ds.dp(act, 8)))
        }
    }

    /** 记忆：MEMORY.md 摘要卡 + 蒸馏记录列表。 */
    private fun renderMemory(r: JSONObject, extra: String?) {
        bodyHost.removeAllViews()
        val master = r.optString("master")
        val rollouts = r.optInt("rollouts", 0)
        if (master.isBlank() && rollouts == 0) {
            bodyHost.addView(Ds.emptyState(act, "≡", "还没有记忆",
                "任务跑完后大肥鱼会蒸馏跨任务经验（rollout），\n" +
                "沉淀进全局记忆，下次任务前弱注入参考。"))
            return
        }
        if (master.isNotBlank()) {
            val card = Ds.card(act)
            card.addView(TextView(act).apply {
                text = "全局记忆 · ${r.optInt("master_chars")} 字"
                textSize = 14f; setTextColor(Ds.TEXT); typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(Ds.small(act, master.take(600) + if (master.length > 600) "\n…" else "")
                .apply { setPadding(0, Ds.dp(act, 6), 0, 0) })
            bodyHost.addView(card)
        }
        if (extra != null) {
            val er = JSONObject(extra)
            val arr = er.optJSONArray("rollouts")
            if (arr != null && arr.length() > 0) {
                bodyHost.addView(Ds.h2(act, "蒸馏记录").apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(0, Ds.dp(act, 16), 0, Ds.dp(act, 4)) }
                })
                for (i in 0 until arr.length()) {
                    val it2 = arr.getJSONObject(i)
                    val card = Ds.card(act)
                    val row = LinearLayout(act).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    }
                    row.addView(TextView(act).apply {
                        text = it2.optString("task_id"); textSize = 13f
                        typeface = Typeface.MONOSPACE; setTextColor(Ds.TEXT)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(Ds.small(act, "${it2.optInt("chars")} 字"))
                    card.addView(row)
                    bodyHost.addView(card, Ds.vp(top = Ds.dp(act, 6)))
                }
            }
        }
    }

    private fun renderWorldModel() {
        bodyHost.removeAllViews()
        bodyHost.addView(Ds.emptyState(act, "◇", "世界模型（随任务积累）",
            "App 布局/控件事实存在各任务资产目录（world_model.md），\n" +
            "任务执行时由大肥鱼自维护参考；产品级汇总视图后置。"))
    }
}
