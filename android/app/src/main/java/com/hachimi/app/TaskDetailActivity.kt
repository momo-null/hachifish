package com.hachimi.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hachimi.app.ui.ChatPage
import com.hachimi.app.ui.Ds
import com.hachimi.app.ui.SettingsPage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务详情页（2026-11-04 全内核化）：数据面 = 环回内核 GET /api/runtime/tasks/{id}/
 * history（Web 同源；mini_loop 旧库 task_trace_json 随执行脑退役，完整内核任务在
 * 旧库无数据——此前点历史会话"没反应"的根因）。渲染 = 按消息序还原：用户消息卡 +
 * 工具步骤时间线（✓绿/✗红/→蓝）+ 每轮回复；继续对话 = 同 task 内核 chat 追加一轮
 * （POST /api/runtime/chat body.task_id），轮询任务状态到终态后刷新历史。
 */
class TaskDetailActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var taskId = ""
    @Volatile private var following = false
    /** 续话 SSE 代际（停止读流/重连循环用）。 */
    @Volatile private var followGen = 0

    private lateinit var traceHost: LinearLayout
    private lateinit var continueInput: EditText
    private lateinit var sendBtn: TextView
    private lateinit var liveHost: LinearLayout
    private lateinit var liveStatus: TextView
    private lateinit var summaryHost: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        taskId = intent.getStringExtra("task_id") ?: ""

        val pad = Ds.dp(this, 20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ds.dp(this@TaskDetailActivity, 12), pad, Ds.dp(this@TaskDetailActivity, 24))
        }
        box.addView(Ds.appBar(this, "任务详情", listOf()))
        box.addView(Ds.small(this, "执行历史 · 消息与工具步骤全量回放；可继续对话（同任务追加一轮）").apply {
            setPadding(0, 0, 0, Ds.dp(this@TaskDetailActivity, 10))
        })

        box.addView(summaryHost)
        renderSummary(null)
        if (taskId.isNotEmpty()) {
            // 「查看完整轨迹」入口只带 task_id（无 objective/state extras）——补拉内核 meta
            Thread {
                val meta = runCatching {
                    OmniAgentRemote.getJson(ChatPage.OMNI_HOST_BASE, "",
                        "/api/runtime/tasks/" + java.net.URLEncoder.encode(taskId, "UTF-8"), 10000)
                        .optJSONObject("task")
                }.getOrNull()
                if (meta != null) main.post { renderSummary(meta) }
            }.start()
        }

        // 续话实时进度卡（发送后可见）
        liveHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        box.addView(liveHost)

        // 历史区
        traceHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(traceHost)
        loadHistory()

        // 继续对话卡
        box.addView(continueCard(), Ds.vp(top = Ds.dp(this, 14)))

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ds.PAGE) }
        root.addView(ScrollView(this).apply {
            addView(box)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        setContentView(root)
        Ds.systemBars(this, root)
        if (intent.getBooleanExtra("focus_input", false)) continueInput.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (!following) loadHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
        following = false
        followGen++
    }

    // ---------------- 概要 ----------------

    /**
     * 概要卡：meta=null 时按 intent extras 渲染（任务列表入口）；meta 到手后整卡
     * 重渲染（查看完整轨迹入口只带 task_id，kernel meta 补齐 objective/state/步数）。
     */
    private fun renderSummary(meta: JSONObject?) {
        summaryHost.removeAllViews()
        val card = Ds.card(this)
        card.addView(TextView(this).apply {
            text = (meta?.optString("objective")
                ?: intent.getStringExtra("objective")).orEmpty().ifEmpty { "(空目标)" }
            textSize = 15f; setTextColor(Ds.TEXT); typeface = Typeface.DEFAULT_BOLD
        })
        val state = meta?.optString("state") ?: intent.getStringExtra("state") ?: ""
        val ok = meta?.optBoolean("success") ?: intent.getBooleanExtra("success", false)
        val steps = meta?.optInt("steps", -1) ?: intent.getIntExtra("steps", -1)
        val wall = meta?.optDouble("wall_s", -1.0) ?: intent.getDoubleExtra("wall_s", -1.0)
        val tags = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = Ds.vp(top = Ds.dp(this@TaskDetailActivity, 8), w = LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun addTag(t: String, fg: Int) = tags.addView(Ds.tag(this, t, fg, Ds.GRAY_BG),
            Ds.vp(right = Ds.dp(this, 6), w = LinearLayout.LayoutParams.WRAP_CONTENT))
        when {
            state == "running" -> addTag("▶ 运行中", Ds.ORANGE_TXT)
            ok -> addTag("✓ 成功", Ds.GREEN_TXT)
            state == "failed" || state == "error" -> addTag("✗ 未完成", Ds.RED_TXT)
            state == "stopped" -> addTag("⏹ 已停止", Ds.TEXT_2)
            else -> addTag("· " + state.ifEmpty { "pending" }, Ds.TEXT_2)
        }
        if (steps >= 0) addTag("$steps 步", Ds.PRIMARY)
        if (wall >= 0) addTag("${wall.toInt()}s", Ds.PURPLE_TXT)
        card.addView(tags)
        val doneWhen = (meta?.optString("done_when") ?: intent.getStringExtra("done_when")).orEmpty()
        card.addView(Ds.small(this, "完成判据：" + doneWhen.ifEmpty { "（未填 · 由模型自行判断）" }).apply {
            setPadding(0, Ds.dp(this@TaskDetailActivity, 8), 0, 0)
        })
        card.addView(Ds.small(this,
            (meta?.optString("created_at") ?: intent.getStringExtra("created_at")
                ?: "").take(16).replace("T", " ") + " · " + taskId)
            .apply { setPadding(0, Ds.dp(this@TaskDetailActivity, 2), 0, 0) })
        summaryHost.addView(card)
    }

    // ---------------- 执行历史（内核 /history） ----------------

    private fun loadHistory() {
        if (taskId.isEmpty()) {
            traceHost.removeAllViews()
            traceHost.addView(Ds.emptyState(this, "▤", "没有 task_id", "该记录缺少可回放的执行标识"))
            return
        }
        traceHost.removeAllViews()
        traceHost.addView(sectionTitle("执行历史"))
        traceHost.addView(Ds.small(this, "读取中…").apply {
            setPadding(0, Ds.dp(this@TaskDetailActivity, 8), 0, 0)
        })
        Thread {
            try {
                val r = OmniAgentRemote.kernelTaskHistory(ChatPage.OMNI_HOST_BASE, taskId)
                main.post { renderHistory(r) }
            } catch (e: Exception) {
                main.post { renderHistoryError("${e.javaClass.simpleName}: ${e.message}") }
            }
        }.start()
    }

    private fun renderHistoryError(msg: String) {
        traceHost.removeAllViews()
        traceHost.addView(sectionTitle("执行历史"))
        traceHost.addView(Ds.small(this, "历史加载失败：$msg").apply {
            setTextColor(Ds.RED_TXT); setPadding(0, Ds.dp(this@TaskDetailActivity, 8), 0, 0)
        })
    }

    /** messages[] → 按序还原：用户卡 + （该轮工具步骤时间线）+ 回复卡。 */
    private fun renderHistory(r: JSONObject) {
        traceHost.removeAllViews()
        traceHost.addView(sectionTitle("执行历史"))
        val msgs = r.optJSONArray("messages")
        if (msgs == null || msgs.length() == 0) {
            traceHost.addView(Ds.emptyState(this, "▤", "还没有执行记录",
                "该任务还没有可回放的执行历史"))
            return
        }
        var hadTool = false
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "user" -> traceHost.addView(userCard(m.optString("content")),
                    Ds.vp(top = Ds.dp(this@TaskDetailActivity, 10)))
                "assistant", "agent" -> {
                    val extra = m.optJSONObject("extra")
                    val steps = extra?.optJSONArray("steps")
                    // 工具步骤 → 染色时间线（thinking 跳过，与对话页现场一致）
                    if (steps != null && steps.length() > 0) {
                        val sb = StringBuilder()
                        var n = 0
                        for (j in 0 until steps.length()) {
                            val st = steps.optJSONObject(j) ?: continue
                            if (st.optString("type") != "tool_call") continue
                            hadTool = true
                            n++
                            val res = st.optString("result")
                            val ok = parseToolOk(res)
                            val mark = when (ok) { true -> "✓"; false -> "✗"; null -> "→" }
                            sb.append(mark).append(' ').append(st.optString("name").ifEmpty { "?" })
                                .append(' ').append(st.optString("arguments").take(70))
                            if (ok == false) sb.append("  ⚠ ").append(res.take(60))
                            sb.append('\n')
                        }
                        if (n > 0) {
                            val tl = Ds.darkLog(this, maxLines = 300)
                            tl.text = colored(sb.toString())
                            tl.setPadding(dp(10), dp(8), dp(10), dp(8))
                            traceHost.addView(tl, Ds.vp(top = Ds.dp(this@TaskDetailActivity, 10)))
                        }
                    }
                    val reply = m.optString("content").trim()
                    if (reply.isNotEmpty()) traceHost.addView(replyCard(reply),
                        Ds.vp(top = Ds.dp(this@TaskDetailActivity, 8)))
                }
            }
        }
        // 轮次结果 tag（extra.meta：内核判定）
        msgs.optJSONObject(msgs.length() - 1)?.optJSONObject("extra")?.optJSONObject("meta")?.let { meta ->
            val ok = meta.optBoolean("success")
            val line = (if (ok) "✓ 办好了" else "✗ 未办成") +
                    " · ${meta.optInt("steps", 0)} 步"
            traceHost.addView(Ds.small(this, line).apply {
                setTextColor(if (ok) Ds.GREEN_TXT else Ds.RED_TXT)
                setPadding(0, Ds.dp(this@TaskDetailActivity, 10), 0, 0)
            })
        }
        if (!hadTool) {
            traceHost.addView(Ds.small(this, "（纯对话轮：无工具步骤）").apply {
                setTextColor(Ds.TEXT_3); setPadding(0, Ds.dp(this@TaskDetailActivity, 8), 0, 0)
            })
        }
    }

    private fun userCard(text: String): View = Ds.card(this).also { card ->
        card.addView(Ds.small(this, "我").apply { setTextColor(Ds.PRIMARY) })
        card.addView(TextView(this).apply {
            this.text = text; textSize = 13.5f; setTextColor(Ds.TEXT)
            setPadding(0, Ds.dp(this@TaskDetailActivity, 4), 0, 0)
        })
    }

    private fun replyCard(text: String): View = Ds.card(this).also { card ->
        card.addView(Ds.small(this, "大肥鱼").apply { setTextColor(Ds.TEXT_2) })
        card.addView(TextView(this).apply {
            this.text = text; textSize = 13.5f; setTextColor(Ds.TEXT)
            setPadding(0, Ds.dp(this@TaskDetailActivity, 4), 0, 0)
        })
    }

    /**
     * 工具结果三态 (true/false/null)。上游工具框架统一包 {"ok":bool,"error"?}：
     * JSON 优先；历史里 result 也可能是 str(dict)（Python repr：单引号/True 大写），
     * 正则同吃两种形态——宽松的"含 ok 字样"启发会把观测内容误判成失败，宁可不判。
     */
    private fun parseToolOk(res: String): Boolean? {
        val s = res.trim()
        if (s.isEmpty()) return null
        try {
            val o = JSONObject(s)
            if (o.has("ok")) return o.optBoolean("ok")
            return true
        } catch (_: Exception) { /* 非 JSON */ }
        return when (Regex("[\"']ok[\"']\\s*:\\s*(True|False|true|false)")
            .find(s)?.groupValues?.get(1)?.lowercase()) {
            "false" -> false
            "true" -> true
            else -> null
        }
    }

    /** 逐行染色（✓ 绿 / ✗ 红 / → 蓝 / 其余暗底白，NarrativePanel.colored 同语义）。 */
    private fun colored(text: String): CharSequence {
        val sb = SpannableString(text)
        var idx = 0
        for (line in text.split("\n")) {
            if (line.isNotEmpty()) {
                val color = when (line.first()) {
                    '✓' -> Ds.GREEN_LOG
                    '✗' -> Ds.RED
                    '→' -> Ds.ACCENT
                    else -> Ds.DARK_TXT
                }
                sb.setSpan(ForegroundColorSpan(color), idx, idx + line.length, 0)
            }
            idx += line.length + 1
        }
        return sb
    }

    // ---------------- 继续对话（同 task 内核 chat 追加一轮） ----------------

    private fun continueCard(): View {
        val card = Ds.card(this)
        card.addView(TextView(this).apply {
            text = "继续对话"; textSize = 14f; setTextColor(Ds.TEXT); typeface = Typeface.DEFAULT_BOLD
        })
        continueInput = Ds.field(this, "例如：把那条笔记标题改成「周五买牛奶」", single = false).apply {
            minLines = 2; gravity = Gravity.TOP; background = null
            setPadding(0, Ds.dp(this@TaskDetailActivity, 6), 0, 0)
        }
        card.addView(continueInput)
        sendBtn = Ds.button(this, "▶ 发送并继续执行") { sendContinue() }
        card.addView(sendBtn, Ds.vp(top = Ds.dp(this@TaskDetailActivity, 10)))
        card.addView(Ds.small(this,
            "同一任务追加一轮：内核带上本任务历史继续推进，而不是重新开始。")
            .apply { setPadding(0, Ds.dp(this@TaskDetailActivity, 6), 0, 0) })
        return card
    }

    private fun sendContinue() {
        val msg = continueInput.text.toString().trim()
        if (msg.isEmpty()) { toast("先输入要继续的指令"); return }
        if (taskId.isEmpty()) { toast("该任务没有可续的 task_id"); return }
        if (following) return
        sendBtn.isEnabled = false
        following = true
        main.post { showLiveStarting() }
        Thread {
            try {
                // 同 task 续轮：chat body.task_id（内核沿用任务历史；max_steps=步数预算）
                OmniAgentRemote.chat(ChatPage.OMNI_HOST_BASE, "", msg, taskId,
                    false, SettingsPage.maxSteps(this))
                startFollowStream()
                pollFollow()
            } catch (e: Exception) {
                main.post {
                    liveHost.visibility = View.GONE
                    toast("发送失败：${MainActivity.readableErrorStatic(e.message ?: "")}")
                    sendBtn.isEnabled = true; following = false
                }
            }
        }.start()
    }

    /**
     * 续话轮 SSE 订阅（审批转发）：详情页此前不订阅流——内核 standard 档的审批卡
     * 无人应答，AutoDeny 等满 600s（2026-11-04 实录：launch_app 卡死到超时）。
     * 这里只把 approval 事件转给 GateActivity 应答；渲染仍走轮询 + 历史刷新。
     */
    private fun startFollowStream() {
        val gen = ++followGen
        Thread {
            var attempts = 0
            while (following && gen == followGen && !isDestroyed) {
                attempts++
                try {
                    OmniAgentRemote.stream(ChatPage.OMNI_HOST_BASE, "", taskId, "task-detail") { ev ->
                        if (gen != followGen || isDestroyed) return@stream false
                        if (ev.event == "approval") {
                            val approvalId = ev.data.optString("approval_id")
                            if (approvalId.isNotEmpty()) {
                                val tool = ev.data.optString("tool").ifEmpty { "?" }
                                val args = ev.data.optJSONObject("arguments")?.toString() ?: ""
                                com.hachimi.app.gate.GateManager.enforceRemote(
                                    this, "kernel", tool, "风险:${ev.data.optString("risk")} $args".take(220)
                                ) { approve, remember ->
                                    Thread {
                                        runCatching {
                                            OmniAgentRemote.decideApproval(ChatPage.OMNI_HOST_BASE,
                                                "", approvalId, approve, remember)
                                        }.onFailure {
                                            Rlog.log("task-detail approval decide failed: ${it.message}")
                                        }
                                    }.start()
                                }
                            }
                        }
                        true
                    }
                } catch (_: Exception) {
                    if (gen != followGen || !following || isDestroyed) break
                    runCatching { Thread.sleep(800L * attempts.coerceAtMost(4)) }
                }
            }
        }.start()
    }

    private fun showLiveStarting() {
        liveHost.removeAllViews()
        liveStatus = TextView(this).apply {
            text = "▶ 续话运行中 · 0s"
            textSize = 13f; setTextColor(Ds.PRIMARY); typeface = Typeface.DEFAULT_BOLD
        }
        liveHost.addView(liveStatus)
        liveHost.addView(Ds.small(this, "任务结束后自动刷新执行历史").apply {
            setPadding(0, Ds.dp(this@TaskDetailActivity, 4), 0, 0)
        })
        liveHost.visibility = View.VISIBLE
    }

    /** 续话轮询（1s → GET /tasks/{id}）：state 离开 running/pending 即收尾并刷新历史。 */
    private fun pollFollow() {
        val deadline = System.currentTimeMillis() + 30 * 60_000
        val t0 = System.currentTimeMillis()
        Thread {
            while (following) {
                if (System.currentTimeMillis() > deadline) break
                try {
                    val r = OmniAgentRemote.getJson(ChatPage.OMNI_HOST_BASE, "",
                        "/api/runtime/tasks/" + java.net.URLEncoder.encode(taskId, "UTF-8"), 8000)
                    val meta = r.optJSONObject("task")
                    val st = meta?.optString("state") ?: ""
                    val success = meta?.optBoolean("success") ?: false
                    val running = st.isEmpty() || st == "running" || st == "pending"
                    val el = (System.currentTimeMillis() - t0) / 1000
                    main.post {
                        if (::liveStatus.isInitialized) {
                            liveStatus.text = "▶ 续话运行中 · ${el}s"
                        }
                    }
                    if (!running) {
                        main.post { finishFollow(success) }
                        break
                    }
                } catch (_: Exception) {
                }
                Thread.sleep(1000)
            }
        }.start()
    }

    private fun finishFollow(ok: Boolean) {
        following = false
        followGen++
        sendBtn.isEnabled = true
        continueInput.setText("")
        liveHost.visibility = View.GONE
        toast(if (ok) "续话完成 ✓" else "续话结束 · 执行历史已刷新")
        // 完成弹窗与对话页/任务页同一体验（后台也能看到结果 + 一键返回）
        com.hachimi.app.FinishPopup.show(this, ok,
            if (ok) "续话轮已完成 · 历史已刷新" else "续话轮未完成 · 历史已刷新", 0)
        loadHistory()
    }

    // ---------------- 杂项 ----------------

    private fun sectionTitle(text: String): View = Ds.h2(this, text)

    private fun dp(v: Int) = Ds.dp(this, v)

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}
