package com.hachimi.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 产品主界面（S2 · P0）：输入目标 → 直调内核执行 → 实时状态与环境引导。
 *
 * - 执行链路 = Chaquopy 直调（KernelHostService 缓存的 bridge 模块）：
 *   Keystore 读 BYOK → set_brain → start_task——不绕回环 HTTP，但 Keystore
 *   注入段与 /task 路径语义一致（product_milestones.md §1 实现约束一）。
 * - 完成判据可留空：内核走 objective 关键短语弱探测（引号文本/数字，约束二）。
 * - 八原语调试面板移至 DebugPanelActivity（研究/排障入口在页脚）。
 */
class MainActivity : Activity() {

    private lateinit var envBox: LinearLayout
    private lateinit var objInput: EditText
    private lateinit var doneInput: EditText
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button
    private lateinit var statusView: TextView
    private lateinit var errView: TextView

    private val main = Handler(Looper.getMainLooper())
    private val polling = AtomicBoolean(false)

    // ---------------- 生命周期 ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        // 内核随 App 启动（幂等；harness 依赖：MainActivity 拉起 KernelHostService）
        KernelHostService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refreshEnv()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val pad = (14 * resources.displayMetrics.density).toInt()
        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        box.addView(TextView(this).apply {
            text = "Hachimi"
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 2)
        })
        box.addView(TextView(this).apply {
            text = "说一句话，把事办完 · v0.1 P0"
            textSize = 12f; setTextColor(0xFF6B7280.toInt())
            setPadding(0, 0, 0, pad)
        })

        // —— 环境就绪卡片（P0：无障碍/悬浮窗/BYOK/内核 四行状态 + 一键跳转）——
        envBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 3 / 4, pad / 2, pad * 3 / 4, pad / 2)
            setBackgroundColor(0xFFF1F5F9.toInt())
        }
        box.addView(envBox)
        box.addView(spacer(pad / 2))

        // —— 任务卡片 ——
        box.addView(label("目标"))
        objInput = EditText(this).apply {
            hint = "例如：在便签里创建一条「买牛奶」的提醒"
            minLines = 2; gravity = Gravity.TOP
            setSingleLine(false)
        }
        box.addView(objInput)
        box.addView(label("完成判据（可留空）"))
        doneInput = EditText(this).apply {
            hint = "留空时按目标中的引号文本/数字做弱探测；填写则要求该文字出现在最终屏幕"
        }
        box.addView(doneInput)
        box.addView(spacer(pad / 2))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnStart = button("▶ 开始执行") { startTask() }
        btnPause = button("⏸ 暂停") { pauseOrResume() }
        btnStop = button("⏹ 停止") { stopTask() }
        row.addView(btnStart)
        row.addView(btnPause)
        row.addView(btnStop)
        box.addView(row)

        statusView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        }
        box.addView(statusView)
        errView = TextView(this).apply {
            textSize = 13f; setTextColor(Color.rgb(0xB4, 0x23, 0x18))
            setPadding(0, pad / 4, 0, 0)
        }
        box.addView(errView)

        // —— 页脚入口 ——
        box.addView(spacer(pad))
        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        links.addView(button("BYOK 设置") { startActivity(Intent(this, SettingsActivity::class.java)) }
            .apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        links.addView(button("八原语调试面板") { startActivity(Intent(this, DebugPanelActivity::class.java)) }
            .apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        box.addView(links)

        root.addView(box)
        renderIdle()
        return root
    }

    /** 环境卡片：状态行 + 未就绪项的一键跳转。 */
    private fun refreshEnv() {
        if (!::envBox.isInitialized) return
        envBox.removeAllViews()

        fun row(label: String, ok: Boolean, okText: String, action: (() -> Unit)?) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(TextView(this).apply {
                text = "$label：${if (ok) okText else "未就绪"}" +
                        if (ok) " ✓" else ""
                textSize = 13f
                setTextColor(if (ok) 0xFF2F6B3A.toInt() else 0xFFB42318.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!ok && action != null) {
                r.addView(Button(this).apply {
                    text = "去开启"; textSize = 12f
                    setOnClickListener { action() }
                })
            }
            envBox.addView(r)
        }

        val a11y = HachimiAccessibilityService.instance != null
        row("无障碍通道", a11y, "已连接") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        row("悬浮窗权限", android.provider.Settings.canDrawOverlays(this), "已授权") {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        val cfg = BrainConfigStore.load(this)
        row("模型端点(BYOK)", cfg != null, cfg?.model ?: "未配置") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        row("内核", KernelHostService.pyBridgeModule != null, "就绪", null)

        // 内核冷启动需数秒：未就绪时低频重查，直到 attach 完成
        if (KernelHostService.pyBridgeModule == null) {
            main.postDelayed({ refreshEnv() }, 2000)
        }
    }

    // ---------------- 任务控制（Chaquopy 直调路径） ----------------

    private fun startTask() {
        val objective = objInput.text.toString().trim()
        if (objective.isEmpty()) {
            showError("请先输入目标，例如：在便签里创建一条「买牛奶」的提醒")
            return
        }
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) {
            showError("内核启动中（首次约需 10 秒），稍后再试")
            return
        }
        btnStart.isEnabled = false
        errView.text = ""
        statusView.text = "正在启动任务…"
        Thread {
            try {
                // /task 路径的 Keystore → set_brain 注入段在直调路径复刻（约束一）
                val cfg = BrainConfigStore.load(this@MainActivity)
                if (cfg == null) {
                    ui { showError("未配置模型端点（BYOK）：请先到 BYOK 设置页填写并保存") }
                    return@Thread
                }
                bridge.callAttr("set_brain", cfg.baseUrl, cfg.apiKey, cfg.model)
                // 悬浮窗 addView 必须在主线程（面板已收起时 show 会真正重建窗口）
                runOnUiThread { NarrativePanel.show(this@MainActivity) }
                val body = JSONObject()
                    .put("objective", objective)
                    .put("done_when", doneInput.text.toString().trim())
                val r = JSONObject(bridge.callAttr("start_task_json", body.toString()).toString())
                ui {
                    if (r.optBoolean("ok")) {
                        statusView.text = "已启动 ${r.optString("run_id")}，等待第一步…"
                        beginPolling()
                    } else {
                        showError(readableError(r.optString("error")))
                        renderIdle()
                    }
                }
            } catch (e: Exception) {
                ui {
                    showError("启动失败：${e.javaClass.simpleName}: ${e.message}")
                    renderIdle()
                }
            }
        }.start()
    }

    private fun pauseOrResume() {
        val pausedNow = btnPause.text.toString().contains("继续")
        Thread {
            try {
                KernelHostService.pyBridgeModule
                    ?.callAttr(if (pausedNow) "request_resume" else "request_pause")
            } catch (_: Exception) {}
            ui { btnPause.text = if (pausedNow) "⏸ 暂停" else "▶ 继续" }
        }.start()
    }

    private fun stopTask() {
        Thread {
            try {
                KernelHostService.pyBridgeModule?.callAttr("request_stop")
            } catch (_: Exception) {}
        }.start()
        statusView.text = "停止请求已发出…"
    }

    /** 运行期轮询 bridge.status()：步数/耗时/当前动作/phase（500ms，主界面数据源）。 */
    private fun beginPolling() {
        if (!polling.compareAndSet(false, true)) return
        Thread {
            while (polling.get()) {
                val bridge = KernelHostService.pyBridgeModule
                if (bridge == null) break
                try {
                    val s = JSONObject(bridge.callAttr("status_json").toString())
                    ui { renderStatus(s) }
                    if (s.optString("phase") != "running") break
                } catch (_: Exception) {
                    break
                }
                Thread.sleep(500)
            }
            polling.set(false)
        }.start()
    }

    // ---------------- 状态渲染 ----------------

    private fun renderStatus(s: JSONObject) {
        val phase = s.optString("phase")
        val paused = s.optBoolean("paused")
        val steps = s.optInt("steps_so_far")
        val elapsed = if (s.has("started") && !s.isNull("started"))
            (System.currentTimeMillis() / 1000 - s.optDouble("started")).toInt() else 0

        when (phase) {
            "running" -> {
                val la = s.optJSONObject("last_action")
                val act = if (la != null) {
                    val ok = when {
                        !la.has("ok") || la.isNull("ok") -> "…"
                        la.optBoolean("ok") -> "✓"
                        else -> "✗"
                    }
                    "第 ${la.optInt("step")} 步 ${la.optString("tool")} $ok\n" +
                            (la.optJSONObject("args")?.toString()?.take(80) ?: "")
                } else "等待第一步…"
                statusView.text = buildString {
                    append(if (paused) "⏸ 已暂停" else "▶ 运行中")
                    append(" · ${steps} 步 · ${elapsed}s\n").append(act)
                }
                btnStart.isEnabled = false
                btnPause.isEnabled = true
                btnPause.text = if (paused) "▶ 继续" else "⏸ 暂停"
                btnStop.isEnabled = true
            }
            "done" -> {
                val sum = s.optJSONObject("summary")
                statusView.text = "✓ 完成 · ${sum?.optInt("steps") ?: steps} 步 · " +
                        "${sum?.optDouble("wall_clock_s")?.toInt() ?: elapsed}s\n" +
                        (sum?.optString("summary")?.take(100) ?: "")
                renderIdle()
            }
            "error" -> {
                val sum = s.optJSONObject("summary")
                // 预算耗尽等 loop 级终止无 error 字段，原因在 summary.summary
                val raw = s.optString("error").ifEmpty { sum?.optString("summary") ?: "" }
                if (raw.contains("stopped by request") || raw.contains("已停止")) {
                    statusView.text = "■ 已停止 · ${steps} 步 · ${elapsed}s"
                    errView.text = ""
                } else {
                    statusView.text = "■ 任务未完成 · ${steps} 步 · ${elapsed}s"
                    showError(readableError(raw))
                }
                renderIdle()
            }
        }
    }

    private fun renderIdle() {
        btnStart.isEnabled = true
        btnPause.isEnabled = false
        btnPause.text = "⏸ 暂停"
        btnStop.isEnabled = false
    }

    private fun showError(msg: String) {
        errView.text = msg
    }

    /** DoD4：错误必须明确可读——按常见失败模式给出人话 + 保留原始信息。 */
    private fun readableError(raw: String): String = when {
        raw.contains("task already running") ->
            "已有任务在运行中。请先按 ⏹ 停止，再开始新任务。"
        raw.contains("no brain configured") ->
            "未配置模型端点（BYOK）。请到 BYOK 设置页填写并保存。"
        raw.contains("HTTP 401") || raw.contains("HTTP 403") ->
            "API 密钥无效或无权限（401/403）。请检查设置页的 API Key。\n[$raw]"
        raw.contains("HTTP 404") ->
            "端点不存在（404）。base_url 需填到 /v1 这一级（应用会自动拼接 /chat/completions）。\n[$raw]"
        raw.contains("HTTP 429") ->
            "请求过于频繁或账户额度不足（429）。\n[$raw]"
        raw.contains("URLError") || raw.contains("timed out", true) ->
            "网络不通或请求超时。请检查网络连接与端点地址是否可达。\n[$raw]"
        raw.contains("unexpected response shape") ->
            "端点已连通，但响应不是 OpenAI 兼容格式。请确认模型服务支持 /chat/completions。\n[$raw]"
        else -> raw
    }

    private fun ui(block: () -> Unit) {
        if (isDestroyed || isFinishing) return
        runOnUiThread(block)
    }

    // ---------------- 通用 ----------------

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply { text = label; setOnClickListener { onClick() } }

    private fun label(s: String): TextView = TextView(this).apply {
        text = s; textSize = 12f; setTextColor(0xFF6B7280.toInt())
        setPadding(0, 12, 0, 2)
    }

    private fun spacer(h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }
}
