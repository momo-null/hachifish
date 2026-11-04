package com.hachimi.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hachimi.app.ui.ChatPage
import com.hachimi.app.ui.Ds
import com.hachimi.app.ui.SubPage
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 页（知识 → MCP）：外部工具服务器接入，字段与写入口径对齐 PC 页（同源契约）：
 * 读 = ``GET /api/runtime/tools`` 的 ``mcp`` 节（env 只回键名，密钥不回明文）；
 * 写 = ``PUT /api/settings`` 的 ``runtime.mcp`` 整表覆盖（内核据此写
 * ``~/.omniagent/mcp.json``，下一轮任务起生效）。PC 的 SkillsAndTools·MCP 段提交的
 * 也是同一组字段（name/enabled/command/args/url），壳侧不另立第二套写入口（R6）。
 *
 * 手机只把 **HTTP(url) 型**做成可增删改：stdio 型要起本机可执行文件，Android 沙箱里
 * 没有那个进程，所以 stdio 行标灰、只保留原值透传（编辑它 = 原样写回，不丢配置）。
 */
class McpActivity : Activity() {

    private lateinit var page: SubPage

    /** 当前表（服务端回读的结构，写回时逐字段透传）。 */
    private var servers = JSONArray()
    private var masterOn = false

    /** 总开关 pill 的引用：Ds.seg 的回调在它自己的初始化器里，够不到局部 val，
     *  所以挂到属性上供失败回滚调用 select()。 */
    private var masterSeg: Ds.Seg? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = SubPage(this, "MCP",
            "外部工具服务器；接入后其工具与自研工具平级。手机端支持 HTTP(url) 型，stdio 型只读")
        page.install()
        // 数据统一由 onResume 拉（onCreate 再调一次 = 同一请求发两遍、页面闪一下）
    }

    override fun onResume() {
        super.onResume()
        if (::page.isInitialized) refresh()
    }

    private fun refresh() {
        page.load({
            OmniAgentRemote.requestJson("GET", ChatPage.OMNI_HOST_BASE,
                "/api/runtime/tools", null, 30000)
        }) { r ->
            val mcp = r.optJSONObject("mcp") ?: JSONObject()
            masterOn = mcp.optBoolean("enabled", false)
            servers = mcp.optJSONArray("servers") ?: JSONArray()
            render(mcp, r.optJSONArray("tools") ?: JSONArray())
        }
    }

    // ---------------- 渲染 ----------------

    private fun render(mcp: JSONObject, tools: JSONArray) {
        val connected = mcp.optJSONArray("connected") ?: JSONArray()
        page.render { host ->
            // 总开关（与服务器表一起写回，PUT 是整表覆盖，单独拨开关也要带全表）
            val masterCard = Ds.card(this)
            val masterRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            masterRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@McpActivity).apply {
                    text = "MCP 总开关"; textSize = 14f; setTextColor(Ds.TEXT)
                })
                addView(Ds.small(this@McpActivity,
                    "关 = 所有服务器不接入，任务里看不到它们的工具").apply {
                    setPadding(0, Ds.dp(this@McpActivity, 2), 0, 0)
                })
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val seg = Ds.seg(this, listOf("关", "开"), if (masterOn) 1 else 0) { i ->
                val on = i == 1
                if (on != masterOn) persist(servers, on) { ok ->
                    // 写失败要把 pill 拨回去：Ds.seg 点击时已先绘新选中态，
                    // 不回滚就出现「显示开、服务端仍关」的假状态。
                    if (ok) masterOn = on else masterSeg?.select(if (masterOn) 1 else 0)
                }
            }
            masterSeg = seg
            masterRow.addView(seg.view, LinearLayout.LayoutParams(
                Ds.dp(this, 132), LinearLayout.LayoutParams.WRAP_CONTENT))
            masterCard.addView(masterRow)
            host.addView(masterCard)

            host.addView(page.section("服务器"))
            if (servers.length() == 0) {
                host.addView(Ds.emptyState(this, "⇄", "还没有 MCP 服务器",
                    "点「添加 HTTP 服务器」填一个 name + url 即可；保存后下一轮任务生效。"))
            } else {
                for (i in 0 until servers.length()) {
                    val s = servers.optJSONObject(i) ?: continue
                    host.addView(serverRow(s, i, connected, tools))
                }
            }

            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Ds.button(this, "添加 HTTP 服务器") { editServer(-1) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            host.addView(actions, Ds.vp(top = Ds.dp(this, 12)))
            host.addView(Ds.small(this,
                "stdio 型（command/args）需要本机可执行文件，Android 沙箱起不了进程——" +
                        "这类条目在手机上只读，原样保留；env / headers 等高级字段请在 PC 端维护" +
                        "（手机保存会按 name/enabled/command/args/url 整表回写，与 PC 页口径一致）。"
            ).apply { setPadding(0, Ds.dp(this@McpActivity, 8), 0, 0) })
        }
    }

    private fun serverRow(s: JSONObject, idx: Int, connected: JSONArray, tools: JSONArray): View {
        val name = s.optString("name")
        val url = s.optString("url")
        val isHttp = url.isNotBlank()
        val isStdio = !isHttp && s.optString("command").isNotBlank()
        val envKeys = s.optJSONArray("env_keys")

        val card = Ds.card(this)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = name.ifBlank { if (isStdio) s.optString("command") else url }
            textSize = 14f; setTextColor(if (s.optBoolean("enabled", true)) Ds.TEXT else Ds.TEXT_3)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 2
        })
        head.addView(Ds.tag(this,
            when {
                isHttp -> "HTTP"
                isStdio -> "stdio"
                else -> "占位"
            },
            if (isHttp) Ds.PURPLE_TXT else Ds.TEXT_3,
            if (isHttp) Ds.PURPLE_BG else Ds.GRAY_BG))
        card.addView(head)

        val detail = if (isHttp) url else (s.optString("command") + " " +
                (s.optJSONArray("args")?.let { a ->
                    (0 until a.length()).joinToString(" ") { a.optString(it) }
                } ?: ""))
        card.addView(Ds.small(this, detail.trim()).apply {
            setPadding(0, Ds.dp(this@McpActivity, 4), 0, 0)
        })

        val on = s.optBoolean("enabled", true)
        val linked = indexOfName(connected, name) >= 0
        val n = countTools(tools, name)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.addView(Ds.small(this, when {
            !on -> "已停用"
            isStdio -> "本机不支持启动，不接入"
            linked -> "已连上 · $n 个工具"
            else -> "未连上（配置后未连接或连接失败）"
        }).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (envKeys != null && envKeys.length() > 0) {
            statusRow.addView(Ds.tag(this, "env ${envKeys.length()}", Ds.TEXT_3, Ds.GRAY_BG))
        }
        val pill = Ds.tag(this, if (on) "开" else "关",
            if (on) Ds.GREEN_TXT else Ds.TEXT_3,
            if (on) android.graphics.Color.parseColor("#1452C41A") else Ds.GRAY_BG)
        pill.setPadding(Ds.dp(this, 14), Ds.dp(this, 6), Ds.dp(this, 14), Ds.dp(this, 6))
        pill.setOnClickListener {
            val next = withField(s, "enabled", !on)
            persist(replaceAt(idx, next), masterOn) { ok ->
                if (ok) pill.text = if (!on) "开" else "关"
            }
        }
        statusRow.addView(pill)
        card.addView(statusRow, Ds.vp(top = Ds.dp(this, 6)))

        if (isHttp) {
            val ops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            ops.addView(Ds.button(this, "编辑", primary = false) { editServer(idx) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val del = Ds.button(this, "删除", primary = false, danger = true) {
                confirmDelete(idx)
            }
            ops.addView(del, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = Ds.dp(this@McpActivity, 8) })
            card.addView(ops, Ds.vp(top = Ds.dp(this, 8)))
        } else {
            card.addView(Ds.small(this, "stdio 型在手机上只读（无本机可执行文件）").apply {
                setTextColor(Ds.TEXT_3)
                setPadding(0, Ds.dp(this@McpActivity, 6), 0, 0)
            })
        }
        return card.also { c ->
            c.setPadding(c.paddingLeft, Ds.dp(this, 10), c.paddingRight, Ds.dp(this, 10))
        }.let { wrapped(it) }
    }

    // ---------------- 增删改 ----------------

    /** idx < 0 = 新增；否则编辑该条（url 型）。name/url 两项，与 PC 页字段一致。 */
    private fun editServer(idx: Int) {
        val cur = if (idx >= 0) servers.optJSONObject(idx) else null
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ds.dp(this@McpActivity, 16)
            setPadding(p, p / 2, p, 0)
        }
        val nameInput = Ds.field(this, "名称（如 context7）").apply {
            setText(cur?.optString("name") ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val urlInput = Ds.field(this, "URL（https://…/mcp）").apply {
            setText(cur?.optString("url") ?: "")
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        form.addView(labelOf("名称")); form.addView(nameInput)
        form.addView(labelOf("地址")); form.addView(urlInput)
        form.addView(Ds.small(this, "名称建议只用字母数字与 - _（工具名会拼成 mcp_<名称>__<工具>）")
            .apply { setPadding(0, Ds.dp(this@McpActivity, 6), 0, 0) })

        android.app.AlertDialog.Builder(this)
            .setTitle(if (idx < 0) "添加 HTTP 服务器" else "编辑服务器")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("保存") { _, _ ->
                val nm = nameInput.text.toString().trim()
                val u = urlInput.text.toString().trim()
                if (nm.isEmpty() || !u.startsWith("http")) {
                    Toast.makeText(this, "名称必填，URL 需以 http(s):// 开头",
                        Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val obj = JSONObject()
                    .put("name", nm).put("url", u).put("enabled", true)
                    .put("command", "").put("args", JSONArray())
                val next = if (idx < 0) append(obj) else replaceAt(idx, obj)
                persist(next, true) { ok -> if (ok) masterOn = true }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(idx: Int) {
        val name = servers.optJSONObject(idx)?.optString("name") ?: "?"
        android.app.AlertDialog.Builder(this)
            .setTitle("删除「$name」？")
            .setMessage("只删配置，不影响已跑过的任务。")
            .setPositiveButton("删除") { _, _ ->
                val next = JSONArray()
                for (i in 0 until servers.length()) if (i != idx) {
                    servers.optJSONObject(i)?.let { next.put(it) }
                }
                persist(next, masterOn)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 整表写回（``PUT /api/settings`` 的 ``runtime.mcp`` 是覆盖语义，必须带全表）。
     * 成功后重读渲染——connected / 工具数由内核实连得出，不在壳侧臆测。
     */
    private fun persist(next: JSONArray, enabled: Boolean, done: ((Boolean) -> Unit)? = null) {
        page.io({
            OmniAgentRemote.requestJson("PUT", ChatPage.OMNI_HOST_BASE, "/api/settings",
                JSONObject().put("runtime", JSONObject().put("mcp",
                    JSONObject().put("enabled", enabled).put("servers", next))), 15000)
        }) { r ->
            val ok = r.optBoolean("_ok") && r.optBoolean("ok", true)
            Toast.makeText(this,
                if (ok) "已保存（下一轮任务生效）" else "保存失败：${r.optString("error")}",
                Toast.LENGTH_SHORT).show()
            done?.invoke(ok)
            refresh()
        }
    }

    // ---------------- JSON 小工具（不可变改写，避免原地改脏回读对象） ----------------

    private fun withField(o: JSONObject, key: String, value: Any): JSONObject {
        val copy = JSONObject(o.toString())
        copy.put(key, value)
        return copy
    }

    private fun append(o: JSONObject): JSONArray {
        val next = JSONArray(servers.toString())
        next.put(o)
        return next
    }

    private fun replaceAt(idx: Int, o: JSONObject): JSONArray {
        val next = JSONArray(servers.toString())
        next.put(idx, o)
        return next
    }

    private fun indexOfName(arr: JSONArray, name: String): Int {
        for (i in 0 until arr.length()) if (arr.optString(i) == name) return i
        return -1
    }

    private fun countTools(tools: JSONArray, server: String): Int {
        var n = 0
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            if (t.optString("source") == "mcp" && t.optString("server") == server) n++
        }
        return n
    }

    private fun labelOf(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(Ds.TEXT_2)
        setPadding(0, Ds.dp(this@McpActivity, 8), 0, Ds.dp(this@McpActivity, 2))
    }

    private fun wrapped(v: View): View {
        val gap = LinearLayout(this)
        gap.addView(v, Ds.vp(top = Ds.dp(this, 6)))
        return gap
    }
}
