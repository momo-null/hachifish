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
import com.hachimi.app.ui.Ds
import com.hachimi.app.ui.SettingsPage
import com.hachimi.app.ui.TasksPage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务详情页（2026-09-30 用户需求③终态）：点开详情 = 完整执行轨迹（每轮 run 一节，
 * ✓绿/✗红/→蓝 染色时间线）+ 继续对话——同 task 追加一轮 run（bridge.start_task
 * body.task_id 语义），内核自动携带上一轮轨迹尾部作背景，跨轮不失忆；
 * 而不是一次操作即终局。数据面 = bridge.task_trace_json / status_json。
 */
class TaskDetailActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var taskId = ""
    @Volatile private var following = false

    private lateinit var traceHost: LinearLayout
    private lateinit var continueInput: EditText
    private lateinit var sendBtn: TextView
    private lateinit var liveHost: LinearLayout
    private lateinit var liveStatus: TextView
    private lateinit var liveLog: TextView
    private var traceRetries = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        taskId = intent.getStringExtra("task_id") ?: ""

        val pad = Ds.dp(this, 20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ds.dp(this@TaskDetailActivity, 12), pad, Ds.dp(this@TaskDetailActivity, 24))
        }
        box.addView(Ds.appBar(this, "任务详情", listOf()))
        box.addView(Ds.small(this, "执行轨迹 · 每轮 run 一节；可继续对话（同任务追加一轮）").apply {
            setPadding(0, 0, 0, Ds.dp(this@TaskDetailActivity, 10))
        })

        box.addView(summaryCard())

        // 续话实时进度卡（发送后可见）
        liveHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        box.addView(liveHost)

        // 轨迹区
        traceHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(traceHost)
        loadTrace()

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
        if (!following) loadTrace()
    }

    override fun onDestroy() {
        super.onDestroy()
        following = false
    }

    // ---------------- 概要 ----------------

    private fun summaryCard(): View {
        val card = Ds.card(this)
        card.addView(TextView(this).apply {
            text = (intent.getStringExtra("objective") ?: "").ifEmpty { "(空目标)" }
            textSize = 15f; setTextColor(Ds.TEXT); typeface = Typeface.DEFAULT_BOLD
        })
        val state = intent.getStringExtra("state") ?: ""
        val ok = intent.getBooleanExtra("success", false)
        val steps = intent.getIntExtra("steps", -1)
        val wall = intent.getDoubleExtra("wall_s", -1.0)
        val tags = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = Ds.vp(top = Ds.dp(this@TaskDetailActivity, 8), w = LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun addTag(t: String, fg: Int) = tags.addView(Ds.tag(this, t, fg, Ds.GRAY_BG),
            Ds.vp(right = Ds.dp(this, 6), w = LinearLayout.LayoutParams.WRAP_CONTENT))
        when {
            ok -> addTag("✓ 成功", Ds.GREEN_TXT)
            state == "failed" || state == "error" -> addTag("✗ 未完成", Ds.RED_TXT)
            state == "running" -> addTag("▶ 运行中", Ds.ORANGE_TXT)
            state == "stopped" -> addTag("⏹ 已停止", Ds.TEXT_2)
            else -> addTag("· " + state.ifEmpty { "pending" }, Ds.TEXT_2)
        }
        if (steps >= 0) addTag("$steps 步", Ds.PRIMARY)
        if (wall >= 0) addTag("${wall.toInt()}s", Ds.PURPLE_TXT)
        card.addView(tags)
        val doneWhen = intent.getStringExtra("done_when").orEmpty()
        card.addView(Ds.small(this, "完成判据：" + doneWhen.ifEmpty { "（未填 · 由模型自行判断）" }).apply {
            setPadding(0, Ds.dp(this@TaskDetailActivity, 8), 0, 0)
        })
        card.addView(Ds.small(this,
            (intent.getStringExtra("created_at") ?: "").take(16).replace("T", " ") +
                    " · " + taskId).apply { setPadding(0, Ds.dp(this@TaskDetailActivity, 2), 0, 0) })
        return card
    }

    // ---------------- 执行轨迹 ----------------

    private fun loadTrace() {
        if (taskId.isEmpty()) {
            traceHost.removeAllViews()
            traceHost.addView(Ds.emptyState(this, "▤", "没有 task_id", "该记录缺少可回放的轨迹标识"))
            return
        }
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) {
            if (traceRetries++ < 5) main.postDelayed({ loadTrace() }, 2000)
            return
        }
        traceRetries = 0
        Thread {
            try {
                val r = JSONObject(bridge.callAttr("task_trace_json", taskId).toString())
                main.post { renderTrace(r) }
            } catch (e: Exception) {
                main.post { renderTraceError("${e.javaClass.simpleName}: ${e.message}") }
            }
        }.start()
    }

    private fun renderTraceError(msg: String) {
        traceHost.removeAllViews()
        traceHost.addView(sectionTitle("执行轨迹"))
        traceHost.addView(Ds.small(this, "轨迹加载失败：$msg").apply {
            setTextColor(Ds.RED_TXT); setPadding(0, Ds.dp(this@TaskDetailActivity, 8), 0, 0)
        })
    }

    private fun renderTrace(r: JSONObject) {
        traceHost.removeAllViews()
        traceHost.addView(sectionTitle("执行轨迹"))
        if (!r.optBoolean("ok")) {
            traceHost.addView(Ds.emptyState(this, "▤", "还没有执行记录",
                r.optString("error").ifEmpty { "该任务还没有可回放的 run 轨迹" }))
            return
        }
        val runs = r.optJSONArray("runs")
        if (runs == null || runs.length() == 0) {
            traceHost.addView(Ds.emptyState(this, "▤", "还没有执行记录", "该任务还没有可回放的 run 轨迹"))
            return
        }
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            traceHost.addView(runCard(run), Ds.vp(top = Ds.dp(this@TaskDetailActivity, 10)))
        }
    }

    private fun runCard(run: JSONObject): View {
        val card = Ds.card(this)
        val meta = run.optJSONObject("run")
        val lines = run.optJSONArray("lines")
        val success = if (meta == null || meta.isNull("success")) null else meta.optBoolean("success")
        val reason = meta?.optString("reason").orEmpty()

        // run 头：状态 + run 标识 + 指标/原因
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val glyph = when (success) { true -> "✓"; false -> "✗"; null -> "→" }
        val glyphColor = when (success) { true -> Ds.GREEN_TXT; false -> Ds.RED_TXT; null -> Ds.TEXT_3 }
        head.addView(TextView(this).apply {
            text = glyph; textSize = 14f; setTextColor(glyphColor); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, Ds.dp(this@TaskDetailActivity, 8), 0)
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "run " + run.optString("run_id").ifEmpty { run.optString("file") } +
                    " · " + run.optString("file").take(10)
            textSize = 12.5f; setTextColor(Ds.TEXT); typeface = Typeface.MONOSPACE
        })
        val metaLine = buildString {
            if (meta != null) {
                append("${meta.optInt("steps", -1).takeIf { it >= 0 } ?: (lines?.length() ?: 0)} 步")
                append(" · LLM ${meta.optInt("brain_calls", -1)} 次")
                if (reason.isNotEmpty()) append(" · " + reason.take(60))
            } else {
                append("${lines?.length() ?: 0} 条记录")
            }
        }
        col.addView(Ds.small(this, metaLine).apply { setPadding(0, Ds.dp(this@TaskDetailActivity, 1), 0, 0) })
        head.addView(col.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(head)

        // 步骤时间线（✓绿/✗红/→蓝 染色；无步骤时诚实占位）
        val tl = Ds.darkLog(this, maxLines = 400)
        tl.text = traceText(lines)
        tl.setPadding(dp(10), dp(8), dp(10), dp(8))
        card.addView(tl, Ds.vp(top = Ds.dp(this@TaskDetailActivity, 8)))
        return card
    }

    /** jsonl 轨迹行 → 染色单文本（kind=think 行跳过；args/err 截断防爆屏）。 */
    private fun traceText(lines: JSONArray?): CharSequence {
        val sb = StringBuilder()
        for (i in 0 until (lines?.length() ?: 0)) {
            val ln = lines!!.optJSONObject(i) ?: continue
            if (ln.optString("kind") == "think") continue
            val a = ln.optJSONObject("action") ?: continue
            val res = ln.optJSONObject("result")
            val mark = when {
                res == null || res.isNull("ok") -> "→"
                res.optBoolean("ok") -> "✓"
                else -> "✗"
            }
            sb.append(mark).append(' ').append(ln.optInt("step")).append(' ')
                .append(a.optString("tool")).append(' ')
                .append(a.optJSONObject("args")?.toString()?.take(70) ?: "")
            if (mark == "✗") sb.append("  ⚠ ").append(res?.optString("error").orEmpty().take(60))
            sb.append('\n')
        }
        return colored(sb.toString().ifEmpty { "（暂无步骤）" })
    }

    /** 逐行染色（NarrativePanel.colored 同语义）：✓ 绿 / ✗ 红 / → 蓝 / 其余暗底白。 */
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

    // ---------------- 继续对话（同 task 追加 run） ----------------

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
            "同一任务追加一轮：内核带上上一轮轨迹背景，从当前屏幕状态继续，" +
                    "而不是重新开始。").apply { setPadding(0, Ds.dp(this@TaskDetailActivity, 6), 0, 0) })
        return card
    }

    private fun sendContinue() {
        val msg = continueInput.text.toString().trim()
        if (msg.isEmpty()) { toast("先输入要继续的指令"); return }
        if (taskId.isEmpty()) { toast("该任务没有可续的 task_id"); return }
        if (following) return
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) { toast("内核启动中（首次约需 10 秒），稍后再试"); return }
        val cfg = BrainConfigStore.load(this)
        if (cfg == null) { toast("未配置模型端点（BYOK）：请先到 设置 → 通道（BYOK）填写并保存"); return }
        sendBtn.isEnabled = false
        following = true
        Thread {
            try {
                // 与主界面 startTask 同一套注入（Keystore → set_brain/set_vision）
                bridge.callAttr("set_brain", cfg.baseUrl, cfg.apiKey, cfg.model)
                if (cfg.visionEnabled) {
                    bridge.callAttr("set_vision", cfg.visionBaseUrl, cfg.visionApiKey, cfg.visionModel)
                } else {
                    bridge.callAttr("set_vision", "", "", "")
                }
                val body = JSONObject()
                    .put("task_id", taskId)
                    .put("objective", msg)
                    .put("max_steps", SettingsPage.maxSteps(this))
                    .put("wall_clock_s", SettingsPage.wallClockS(SettingsPage.maxSteps(this)))
                if (getSharedPreferences(TasksPage.PREFS, 0).getInt("run_mode", 0) == 1) {
                    body.put("mode", "split")
                }
                main.post { showLiveStarting() }
                val r = JSONObject(bridge.callAttr("start_task_json", body.toString()).toString())
                if (!r.optBoolean("ok")) {
                    main.post {
                        liveHost.visibility = View.GONE
                        toast(MainActivity.readableErrorStatic(r.optString("error")))
                        sendBtn.isEnabled = true; following = false
                    }
                    return@Thread
                }
                pollFollow()
            } catch (e: Exception) {
                main.post {
                    toast("发送失败：${e.javaClass.simpleName}: ${e.message}")
                    sendBtn.isEnabled = true; following = false
                }
            }
        }.start()
    }

    private fun showLiveStarting() {
        liveHost.removeAllViews()
        liveStatus = TextView(this).apply {
            text = "▶ 续话运行中 · 0 步"
            textSize = 13f; setTextColor(Ds.PRIMARY); typeface = Typeface.DEFAULT_BOLD
        }
        liveLog = Ds.darkLog(this, maxLines = 4).apply { text = "等待第一步…（网关偶发 30~90 秒停顿属正常）" }
        liveHost.addView(liveStatus)
        liveHost.addView(liveLog, Ds.vp(top = Ds.dp(this@TaskDetailActivity, 6)))
        liveHost.visibility = View.VISIBLE
    }

    /** 续话轮询（500ms → status_json）：phase 离开 running 即收尾并刷新轨迹。 */
    private fun pollFollow() {
        val deadline = System.currentTimeMillis() + 20 * 60_000
        Thread {
            while (following) {
                if (System.currentTimeMillis() > deadline) break
                val bridge = KernelHostService.pyBridgeModule ?: break
                try {
                    val s = JSONObject(bridge.callAttr("status_json").toString())
                    val phase = s.optString("phase")
                    main.post { renderLive(s, phase) }
                    if (phase != "running") {
                        main.post { finishFollow(phase) }
                        break
                    }
                } catch (_: Exception) {
                    break
                }
                Thread.sleep(500)
            }
        }.start()
    }

    private fun renderLive(s: JSONObject, phase: String) {
        if (!::liveStatus.isInitialized) return
        if (phase == "running") {
            liveStatus.text = (if (s.optBoolean("paused")) "⏸ 续话已暂停" else "▶ 续话运行中") +
                    " · ${s.optInt("steps_so_far")} 步"
            val la = s.optJSONObject("last_action")
            liveLog.text = if (la == null) "等待第一步…"
            else {
                val mark = when {
                    !la.has("ok") || la.isNull("ok") -> "→"
                    la.optBoolean("ok") -> "✓"
                    else -> "✗"
                }
                "${mark} 步骤${la.optInt("step")} · ${la.optString("tool")} " +
                        (la.optJSONObject("args")?.toString()?.take(70) ?: "")
            }
        }
    }

    private fun finishFollow(phase: String) {
        following = false
        sendBtn.isEnabled = true
        continueInput.setText("")
        toast(if (phase == "done") "续话完成 ✓" else "续话结束（$phase）· 最新一轮轨迹见下方")
        loadTrace()
    }

    // ---------------- 杂项 ----------------

    private fun sectionTitle(text: String): View = Ds.h2(this, text)

    private fun dp(v: Int) = Ds.dp(this, v)

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}
