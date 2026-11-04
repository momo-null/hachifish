package com.hachimi.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hachimi.app.ui.ChatPage
import com.hachimi.app.ui.Ds
import com.hachimi.app.ui.SubPage
import org.json.JSONObject

/**
 * 工具页（知识 → 工具）：内核此刻**实际**有哪些能力，以及唯一被允许拨的那层开关。
 *
 * 三层各归各位（与上游 U2 能力闸一致，壳侧不发明第二套开关）：
 * - **能力插件**＝能力插件面（filesystem / web，构建期由 sync_upstream 带进 APK，
 *   首次启动落盘到 filesDir/plugins）——一个插件一个开关，写
 *   ``~/.omniagent/plugins/<name>.yaml`` 的 ``enabled``（PATCH /api/runtime/tools/plugins）。
 *   出厂默认关（红线 R5：能力默认关 + 显式打开）。
 * - **内核工具 / 手机原语**＝只读清单。上游刻意删掉了逐工具开关与「环境单选」，
 *   清单只读；`shell_exec` 这类「本机没有的执行能力」由工具自身按环境自门
 *   （omni_core/tools/shell_tool.py：探不到 shell 就不注册）——所以手机上根本不会出现它。
 * - **MCP** 只在页尾给个入口，编辑在 MCP 页（只有 url 型可增删）。
 */
class ToolsActivity : Activity() {

    private lateinit var page: SubPage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = SubPage(
            this, "工具",
            "手机内核实际注册的能力清单；只有「能力插件」有开关（默认关，打开后下一轮任务生效）"
        )
        page.install()
        // 数据统一由 onResume 拉（onCreate 再调一次 = 同一请求发两遍、页面闪一下）
    }

    override fun onResume() {
        super.onResume()
        if (::page.isInitialized) refresh()
    }

    private fun refresh() {
        page.load({
            // GET /tools 会实连已启用的 MCP server 探工具（单个上限 15s），超时给足
            OmniAgentRemote.requestJson("GET", ChatPage.OMNI_HOST_BASE,
                "/api/runtime/tools", null, 30000)
        }) { r -> render(r) }
    }

    private fun render(r: JSONObject) {
        val plugins = r.optJSONArray("plugins")
        val tools = r.optJSONArray("tools") ?: org.json.JSONArray()
        page.render { host ->
            host.addView(section("能力插件"))
            if (plugins == null || plugins.length() == 0) {
                host.addView(Ds.small(this,
                    "本机未打包能力插件（构建前未跑 scripts/sync_upstream.py）。").apply {
                    setPadding(0, Ds.dp(this@ToolsActivity, 2), 0, 0)
                })
            } else {
                for (i in 0 until plugins.length()) {
                    val p = plugins.optJSONObject(i) ?: continue
                    host.addView(pluginRow(p))
                }
                host.addView(Ds.small(this,
                    "关 = 不再注册它的工具（下一轮任务起）；本进程已装载的插件要等 App 重启才彻底消失" +
                            "——内核注册表不做回收，这是上游 loader 的既有语义。").apply {
                    setPadding(0, Ds.dp(this@ToolsActivity, 6), 0, 0)
                })
            }

            host.addView(section("内核能力（只读）"))
            host.addView(Ds.small(this,
                "共 ${tools.length()} 项，按提供者分组。清单只读：上游不设逐工具开关，" +
                        "本机不可用的能力由工具自己按环境决定是否注册。").apply {
                setPadding(0, 0, 0, Ds.dp(this@ToolsActivity, 4))
            })
            for ((unit, label, items) in groupUnits(tools)) {
                host.addView(unitCard(unit, label, items))
            }

            val mcp = r.optJSONObject("mcp")
            host.addView(section("MCP 外部工具"))
            host.addView(mcpSummaryRow(mcp))
        }
    }

    // ---------------- 分组渲染 ----------------

    /** 按 unit 分组并保持有义序：内核 → 手机原语 → 插件 → MCP server。 */
    private fun groupUnits(tools: org.json.JSONArray): List<Triple<String, String, List<JSONObject>>> {
        val byUnit = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val unit = t.optString("unit").ifEmpty { "core" }
            byUnit.getOrPut(unit) { mutableListOf() }.add(t)
        }
        val order = Comparator<String> { a, b -> rank(a) - rank(b) }
        return byUnit.keys.sortedWith(order).map { unit ->
            val items = byUnit.getValue(unit)
            val source = items.firstOrNull()?.optString("source") ?: "core"
            val label = when {
                unit == "core" -> "内核 core"
                unit == "hachimi_phone" -> "手机操作原语"
                source == "mcp" -> "MCP · $unit"
                source == "plugin" -> "插件 · $unit"
                else -> unit
            }
            Triple(unit, label, items.toList())
        }
    }

    private fun rank(unit: String): Int = when (unit) {
        "core" -> 0
        "hachimi_phone" -> 1
        else -> 2
    }

    private fun unitCard(unit: String, label: String, items: List<JSONObject>): View {
        val card = Ds.card(this)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(Ds.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(Ds.tag(this, "${items.size} 项", Ds.TEXT_3, Ds.GRAY_BG))
        card.addView(head)
        items.sortedBy { it.optString("name") }.forEach { t ->
            card.addView(Ds.divider(this@ToolsActivity))
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(this).apply {
                text = t.optString("name"); textSize = 13f; setTextColor(Ds.TEXT)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
            val desc = brief(t.optString("description"), 96)
            if (desc.isNotEmpty()) row.addView(Ds.small(this, desc).apply {
                setPadding(0, Ds.dp(this@ToolsActivity, 1), 0, 0)
            })
            row.setPadding(0, Ds.dp(this@ToolsActivity, 6), 0, 0)
            card.addView(row)
        }
        return card
    }

    // ---------------- 插件开关 ----------------

    private fun pluginRow(p: JSONObject): View {
        val name = p.optString("name")
        val card = Ds.card(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = p.optString("title").ifEmpty { name }; textSize = 14f; setTextColor(Ds.TEXT)
        })
        val desc = brief(p.optString("description"), 120)
        if (desc.isNotEmpty()) col.addView(Ds.small(this, desc).apply {
            setPadding(0, Ds.dp(this@ToolsActivity, 2), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val pill = togglePill(p.optBoolean("enabled"))
        row.addView(pill)
        card.addView(row)
        card.setPadding(card.paddingLeft, Ds.dp(this, 10), card.paddingRight, Ds.dp(this, 10))
        var on = p.optBoolean("enabled")
        fun flip() {
            val target = !on
            pill.text = "…"
            page.io({
                OmniAgentRemote.requestJson("PATCH", ChatPage.OMNI_HOST_BASE,
                    "/api/runtime/tools/plugins",
                    JSONObject().put("name", name).put("enabled", target), 12000)
            }) { r ->
                if (r.optBoolean("_ok")) {
                    on = target
                    pill.text = if (target) "开" else "关"
                    paintPill(pill, target)
                    Toast.makeText(this,
                        "$name 已${if (target) "启用（下一轮任务生效）" else "停用（重启 App 后彻底消失）"}",
                        Toast.LENGTH_SHORT).show()
                } else {
                    pill.text = if (on) "开" else "关"
                    paintPill(pill, on)
                    Toast.makeText(this, "开关失败：${r.optString("error")}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
        pill.setOnClickListener { flip() }
        row.setOnClickListener { flip() }
        return wrap(card)
    }

    private fun togglePill(on: Boolean): TextView =
        Ds.tag(this, if (on) "开" else "关", Ds.TEXT_2, Ds.GRAY_BG).also { paintPill(it, on) }

    private fun paintPill(pill: TextView, on: Boolean) {
        pill.setTextColor(if (on) Ds.GREEN_TXT else Ds.TEXT_3)
        pill.background = Ds.round(this, 20,
            if (on) android.graphics.Color.parseColor("#1452C41A") else Ds.GRAY_BG)
        pill.setPadding(Ds.dp(this, 14), Ds.dp(this, 6), Ds.dp(this, 14), Ds.dp(this, 6))
    }

    // ---------------- MCP 入口 ----------------

    private fun mcpSummaryRow(mcp: JSONObject?): View {
        val card = Ds.card(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val servers = mcp?.optJSONArray("servers")?.length() ?: 0
        val connected = mcp?.optJSONArray("connected")?.length() ?: 0
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "MCP 服务器"; textSize = 14f; setTextColor(Ds.TEXT)
        })
        col.addView(Ds.small(this, when {
            servers == 0 -> "未配置（手机端只支持 url 型 HTTP 服务器）"
            else -> "${if (mcp?.optBoolean("enabled") == true) "总开关开" else "总开关关"}" +
                    " · 已配 $servers 个 · 连上 $connected 个"
        }).apply { setPadding(0, Ds.dp(this@ToolsActivity, 2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ds.tag(this, "管理", Ds.PRIMARY, Ds.GRAY_BG))
        card.addView(row)
        card.setOnClickListener { startActivity(Intent(this, McpActivity::class.java)) }
        card.setPadding(card.paddingLeft, Ds.dp(this, 10),
            card.paddingRight, Ds.dp(this, 10))
        return wrap(card)
    }

    // ---------------- 小工具 ----------------

    private fun section(text: String): View = page.section(text)

    private fun wrap(v: View): View {
        val gap = LinearLayout(this)
        gap.addView(v, Ds.vp(top = Ds.dp(this, 6)))
        return gap
    }

    private fun brief(text: String, max: Int): String {
        val one = (text ?: "").replace("\r", " ").replace("\n", " ").trim()
        return if (one.length <= max) one else one.take(max) + "…"
    }
}
