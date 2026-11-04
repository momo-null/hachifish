package com.hachimi.app.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hachimi.app.OmniAgentRemote
import com.hachimi.app.Rlog
import org.json.JSONObject

/**
 * 知识 Tab（2026-11-04 死配置清理后定稿；同日技能/工具/MCP 移植上机后再分层）：
 *
 * 主体仍是「记忆」列表（环回内核 brain，Web DebugPanel 同源 /api/debug/memory）——
 * 总览卡 + atoms 列表 + 单条物理删除；其上是一排**分节入口**（技能 / 工具 / MCP），
 * 每个入口开一个全屏子页（无二级 Tabs，对齐仓内 RoleSettingsActivity 惯例）：
 * - 技能 → SkillsActivity（/api/runtime/skills，回放/升全局/删除）
 * - 工具 → ToolsActivity（/api/runtime/tools 只读清单 + 能力插件 enabled 开关）
 * - MCP  → McpActivity（PUT /api/settings runtime.mcp，url 型可增删，stdio 只读）
 *
 * 头部 tag = 内核记忆层开关回显（/api/settings runtime.knowledge.memory.enabled，
 * 设置页同一事实源）。入口副标题的量值来自一次 /api/runtime/tools 读取（失败留原文案）。
 * 数据在 onResume 拉取（每次进 Tab 刷新）。
 */
class KnowledgePage(private val act: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private var injectTag: TextView? = null
    private lateinit var bodyHost: LinearLayout
    private lateinit var page: ScrollView
    private var skillsSub: TextView? = null
    private var toolsSub: TextView? = null
    private var mcpSub: TextView? = null

    val view: View
        get() {
            if (!::page.isInitialized) page = build()
            return page
        }

    fun onResume() {
        if (!::page.isInitialized) return
        refreshInjectTag()
        refreshEntryCounts()
        load()
    }

    /** 头部 tag = 内核记忆层开关（与设置页「记忆与技能」同一事实源，只读回显）。 */
    private fun refreshInjectTag() {
        Thread {
            val on = runCatching {
                OmniAgentRemote.getJson(ChatPage.OMNI_HOST_BASE, "", "/api/settings", 10000)
                    .optJSONObject("runtime")?.optJSONObject("knowledge")
                    ?.optJSONObject("memory")?.optBoolean("enabled", true)
            }.getOrNull() ?: true
            main.post {
                injectTag?.text = "记忆注入：${if (on) "开" else "关"}"
                injectTag?.setTextColor(if (on) Ds.GREEN_TXT else Ds.TEXT_3)
            }
        }.start()
    }

    private fun build(): ScrollView {
        val pad = Ds.dp(act, 20)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.setPadding(pad, Ds.dp(act, 12), pad, Ds.dp(act, 20))

        injectTag = Ds.tag(act, "记忆注入：开", Ds.GREEN_TXT, Ds.GRAY_BG)
        box.addView(Ds.appBar(act, "知识", listOf(injectTag!!)))

        // 分节入口：技能 / 工具 / MCP 各开一个全屏子页（记忆仍是本页主体）
        val entries = Ds.card(act)
        entries.addView(entryRow("技能", "任务成功后自动蒸馏的套路 · 回放 / 设为全局",
            { skillsSub = it }) {
            act.startActivity(android.content.Intent(act, com.hachimi.app.SkillsActivity::class.java))
        })
        entries.addView(Ds.divider(act))
        entries.addView(entryRow("工具", "内核能力清单 · 能力插件开关（默认关）",
            { toolsSub = it }) {
            act.startActivity(android.content.Intent(act, com.hachimi.app.ToolsActivity::class.java))
        })
        entries.addView(Ds.divider(act))
        entries.addView(entryRow("MCP", "外部工具服务器 · HTTP(url) 型可增删",
            { mcpSub = it }) {
            act.startActivity(android.content.Intent(act, com.hachimi.app.McpActivity::class.java))
        })
        box.addView(entries)

        box.addView(Ds.h2(act, "记忆").apply {
            setPadding(0, Ds.dp(act, 16), 0, 0)
        })
        box.addView(Ds.small(act, "全局记忆 —— 对话与任务结束后自动沉淀，下次按相关度检索注入").apply {
            setPadding(0, Ds.dp(act, 4), 0, Ds.dp(act, 10))
        })

        bodyHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(bodyHost, Ds.vp(top = Ds.dp(act, 10)))
        return ScrollView(act).apply { addView(box) }
    }

    /** 分节入口行（标题 + 副标题 + ›），副标题文本可被后续异步刷新持有。 */
    private fun entryRow(title: String, desc: String,
                         onSub: (TextView) -> Unit, open: () -> Unit): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Ds.dp(act, 8), 0, Ds.dp(act, 8))
        }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(act).apply {
            text = title; textSize = 14f; setTextColor(Ds.TEXT)
        })
        val sub = Ds.small(act, desc).apply { setPadding(0, Ds.dp(act, 1), 0, 0) }
        col.addView(sub)
        onSub(sub)
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(act).apply {
            text = "›"; textSize = 16f; setTextColor(Ds.TEXT_3)
            setPadding(Ds.dp(act, 10), 0, 0, 0)
        })
        row.setOnClickListener { open() }
        return row
    }

    /** 入口副标题的真实量值（一次 /api/runtime/tools 读取；内核未就绪就留原文案）。 */
    private fun refreshEntryCounts() {
        Thread {
            val r = runCatching {
                OmniAgentRemote.getJson(ChatPage.OMNI_HOST_BASE, "",
                    "/api/runtime/tools", 20000)
            }.getOrNull() ?: return@Thread
            if (r.isNull("plugins")) return@Thread
            val plugins = r.optJSONArray("plugins")?.length() ?: 0
            val tools = r.optJSONArray("tools")?.length() ?: 0
            val mcp = r.optJSONObject("mcp")
            val servers = mcp?.optJSONArray("servers")?.length() ?: 0
            val linked = mcp?.optJSONArray("connected")?.length() ?: 0
            main.post {
                toolsSub?.text = "内核能力 $tools 项 · 能力插件 $plugins 个（默认关）"
                mcpSub?.text = if (servers == 0) "HTTP(url) 型可增删 · 尚未配置"
                else "已配 $servers 个 · 连上 $linked 个 · stdio 本机只读"
            }
        }.start()
    }

    private fun load() {
        bodyHost.removeAllViews()
        bodyHost.addView(Ds.small(act, "读取中…"))
        Thread {
            try {
                val r = OmniAgentRemote.getJson(ChatPage.OMNI_HOST_BASE, "",
                    "/api/debug/memory", 12000)
                main.post { renderMemoryKernel(r) }
            } catch (e: Exception) {
                main.post {
                    bodyHost.removeAllViews()
                    bodyHost.addView(Ds.emptyState(act, "!", "读取失败",
                        "${e.javaClass.simpleName}——回前台重试。"))
                }
            }
        }.start()
    }

    /** 记忆 = 环回内核 brain：总览卡 + atoms 列表（状态/kind/强度/更新时间）+ 单条物理删除。 */
    private fun renderMemoryKernel(r: JSONObject) {
        bodyHost.removeAllViews()
        if (!r.optBoolean("ok")) {
            bodyHost.addView(Ds.emptyState(act, "!", "记忆库读取失败", r.optString("error")))
            return
        }
        val ov = r.optJSONObject("overview")
        val head = Ds.card(act)
        head.addView(TextView(act).apply {
            text = "记忆脑 · 共 ${r.optInt("count")} 条" +
                    (ov?.let { " · 活跃 ${it.optInt("active", 0)}" } ?: "")
            textSize = 14f; setTextColor(Ds.TEXT); typeface = Typeface.DEFAULT_BOLD
        })
        ov?.optJSONObject("by_kind")?.let { kinds ->
            val parts = mutableListOf<String>()
            for (k in kinds.keys()) parts.add("$k ${kinds.optInt(k)}")
            if (parts.isNotEmpty()) head.addView(Ds.small(act, parts.joinToString(" · "))
                .apply { setPadding(0, Ds.dp(act, 2), 0, 0) })
        }
        bodyHost.addView(head)
        val atoms = r.optJSONArray("atoms")
        if (atoms == null || atoms.length() == 0) {
            bodyHost.addView(Ds.emptyState(act, "◈", "记忆还是空的",
                "跑几个任务后，记忆会自动沉淀在这里。"))
            return
        }
        for (i in 0 until atoms.length()) {
            val a = atoms.optJSONObject(i) ?: continue
            val card = Ds.card(act)
            val headL = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val state = a.optString("state")
            headL.addView(Ds.tag(act, when (state) {
                "active" -> "活跃"; "superseded" -> "已替代"; else -> state
            }, if (state == "active") Ds.GREEN_TXT else Ds.TEXT_3, Ds.GRAY_BG))
            headL.addView(TextView(act).apply {
                text = a.optString("kind"); textSize = 11f; setTextColor(Ds.TEXT_3)
                setPadding(Ds.dp(act, 6), 0, 0, 0)
            })
            val del = TextView(act).apply {
                text = "✕"; textSize = 13f; gravity = android.view.Gravity.CENTER; setTextColor(Ds.TEXT_3)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            del.setOnClickListener {
                val rid = a.optString("record_id")
                Thread {
                    runCatching {
                        OmniAgentRemote.postJson(ChatPage.OMNI_HOST_BASE, "",
                            "/api/debug/memory/delete", JSONObject().put("record_id", rid))
                        main.post { load() }
                    }.onFailure { Rlog.log("memory delete failed: ${it.message}") }
                }.start()
            }
            headL.addView(del)
            card.addView(headL)
            card.addView(TextView(act).apply {
                text = a.optString("content"); textSize = 13f; setTextColor(Ds.TEXT)
                setPadding(0, Ds.dp(act, 4), 0, 0)
            })
            card.addView(Ds.small(act, "强度 ${a.optDouble("importance", 0.0)} · " +
                    a.optString("updated").take(16)).apply { setPadding(0, Ds.dp(act, 2), 0, 0) })
            bodyHost.addView(card)
        }
    }
}
