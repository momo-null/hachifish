package com.hachimi.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hachimi.app.DebugPanelActivity
import com.hachimi.app.HachimiAccessibilityService
import com.hachimi.app.KernelHostService
import com.hachimi.app.ProjectionHolder
import com.hachimi.app.MainActivity
import com.hachimi.app.OmniAgentRemote
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * S2 主界面（终态）：appbar 连通 chips + 执行模式分段（前台引导/分屏，真实切换
 * /task body mode）+ 任务输入卡（一句话即任务 + 可折叠完成判据）+ 运行/结果区
 * （RunResultView）+ 最近任务（真实历史，bridge.list_tasks_json）。
 * 执行链路 = Chaquopy 直调（P0 语义不变）；环境未就绪时顶部出引导卡（P0 DoD5）。
 */
class TasksPage(private val act: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private val polling = AtomicBoolean(false)

    private lateinit var objInput: EditText
    private lateinit var doneInput: EditText
    private lateinit var doneToggle: TextView
    private lateinit var chipsHost: LinearLayout
    private lateinit var envHost: LinearLayout
    private lateinit var recentHost: LinearLayout
    private lateinit var resultHost: LinearLayout
    private lateinit var startBtn: TextView

    val result = RunResultView(act)
    private lateinit var page: ScrollView

    val view: View
        get() {
            if (!::page.isInitialized) page = build()
            return page
        }

    // ---------------- 构建 ----------------

    private fun build(): ScrollView {
        val pad = Ds.dp(act, 20)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.setPadding(pad, Ds.dp(act, 12), pad, Ds.dp(act, 20))

        // appbar：logo + 连通 chips（绿点=已配置）
        chipsHost = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(Ds.appBar(act, "Hachifish", listOf(chipsHost)))
        box.addView(Ds.small(act, "说一句话，把事办完 · v0.1").apply {
            setPadding(0, 0, 0, Ds.dp(act, 10))
        })

        // 环境未就绪引导卡（就绪时 GONE——干净 S2；P0 DoD5 状态指示保留于此）
        envHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(envHost)

        // 任务输入卡（mockup：浅蓝描边白卡）
        val inputCard = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.round(act, 14, Color.WHITE, 2, Ds.ACCENT)
            val p = Ds.dp(act, 14)
            setPadding(p, p, p, p)
        }
        inputCard.addView(Ds.small(act, "目标"))
        objInput = Ds.field(act, "例如：在便签里创建一条「买牛奶」的提醒", single = false).apply {
            minLines = 2
            gravity = Gravity.TOP
            background = null
            setPadding(0, Ds.dp(act, 6), 0, 0)
        }
        inputCard.addView(objInput)

        // 完成判据（可选，折叠收进输入卡——2026-09-30 语义对齐桌面版：留空 = 模型自判完成）
        doneToggle = Ds.small(act, "完成判据（可选）▸").apply {
            setPadding(0, Ds.dp(act, 8), 0, 0)
            setOnClickListener { toggleDone() }
        }
        inputCard.addView(doneToggle)
        doneInput = Ds.field(act, "留空 = 由模型自行判断完成（桌面版语义）；填写则要求该文字出现在最终屏幕", single = false)
            .apply { minLines = 1; visibility = View.GONE }
        inputCard.addView(doneInput)

        startBtn = Ds.button(act, "▶ 开始执行") { startTask() }
        inputCard.addView(startBtn, Ds.vp(top = Ds.dp(act, 10)))
        inputCard.addView(Ds.small(act, "桌面版 /chat 单入口语义：一句话即任务，自动建 task").apply {
            setPadding(0, Ds.dp(act, 6), 0, 0)
        })
        box.addView(inputCard)

        // 运行/结果区（S5）
        resultHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        resultHost.addView(result.view)
        box.addView(resultHost)

        // 最近任务
        val recentHead = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        recentHead.addView(Ds.h2(act, "最近任务").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        recentHead.addView(Ds.small(act, "点按看详情").apply { setPadding(0, 0, 0, 0) })
        box.addView(recentHead, Ds.vp(top = Ds.dp(act, 12)))
        recentHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(recentHost)

        // 页脚：研究/排障入口
        box.addView(Ds.small(act, "八原语调试面板（研究/排障）").apply {
            setPadding(0, Ds.dp(act, 16), 0, 0)
            setOnClickListener { act.startActivity(Intent(act, DebugPanelActivity::class.java)) }
        })

        result.onRetry = { result.render(null); refreshRecent() }
        result.onControl = { onControl(it) }
        // 环境状态自动跟随（权限/录屏/内核就绪后 1.5s 内更正,无需手动刷新）
        main.postDelayed(envPoll, 1500)
        return ScrollView(act).apply { addView(box) }
    }

    /** 本页直发任务的内核 task_id（轮询/停止寻址）。 */
    private var currentTaskId: String? = null

    private fun toggleDone() {
        val show = doneInput.visibility == View.GONE
        doneInput.visibility = if (show) View.VISIBLE else View.GONE
        doneToggle.text = if (show) "完成判据（可选）▾" else "完成判据（可选）▸"
    }

    // ---------------- 环境与刷新 ----------------

    fun onResume() {
        if (!::page.isInitialized) return
        refreshChips()
        refreshEnv()
        refreshRecent()
        refreshLastResult()
    }

    /**
     * 环境就绪状态轮询（2026-09-30 真机反馈：无障碍/录屏在系统侧配置后，
     * 主界面状态卡要手动刷新才更正——权限连接与录屏会话建立都是异步事件，
     * 不保证落在 onResume 时点）。1.5s 比对就绪签名，有变化才重建，静态时零 UI 开销；
     * Activity 销毁后自终止，不持有引用。
     */
    private var lastEnvSig = ""
    private val envPoll = object : Runnable {
        override fun run() {
            if (act.isFinishing || act.isDestroyed) return
            val sig = envSignature()
            if (sig != lastEnvSig) {
                lastEnvSig = sig
                refreshChips()
                refreshEnv()
            }
            main.postDelayed(this, 1500)
        }
    }

    /** 就绪签名：无障碍 / 悬浮窗 / 视觉开启时录屏会话 / 内核（BYOK 变更走 onResume,不进轮询）。 */
    private fun envSignature(): String =
        listOf(
            HachimiAccessibilityService.instance != null,
            android.provider.Settings.canDrawOverlays(act),
            ProjectionHolder.isRunning,
            KernelHostService.pyBridgeModule != null,
        ).joinToString("|")

    /**
     * 回显上次任务结果（S5 语义补全）：不在运行期且页面没在轮询时，拉一次
     * status()——已结束的 run（done/error）直接渲染结果卡，重开 App 能看到
     * 上一单结局；phase=idle/无历史则清空结果区。
     */
    private fun refreshLastResult() {
        if (polling.get()) return
        Thread {
            try {
                val r = OmniAgentRemote.getJson(ChatPage.OMNI_HOST_BASE, "", "/api/runtime/tasks", 12000)
                val t0 = r.optJSONArray("tasks")?.optJSONObject(0)
                main.post {
                    if (polling.get()) return@post
                    if (t0 == null) { result.render(null); return@post }
                    val st = t0.optString("state")
                    val success = t0.optBoolean("success")
                    val phase = when {
                        st == "running" -> "running"
                        success -> "done"
                        st == "failed" || (st == "aborted" && !success) -> "error"
                        else -> return@post   // pending 等：维持现有结果区
                    }
                    result.render(JSONObject()
                        .put("phase", phase)
                        .put("summary", t0.optString("objective"))
                        .put("steps_so_far", t0.optJSONArray("runs")?.length() ?: 0)
                        .put("paused", false)
                        .put("run_id", t0.optString("task_id")))
                }
            } catch (_: Exception) {}
        }.start()
    }

    /** 内核模型目录是否已配好主模型（refreshChips 线程刷新；refreshEnv 环境卡读）。 */
    @Volatile private var kernelModelReady = false

    private fun refreshChips() {
        // Phase 2 内核化：模型就绪 = 内核目录主模型槽位有生效端点（/api/runtime/models）
        Thread {
            val cur = runCatching {
                OmniAgentRemote.getJson(ChatPage.OMNI_HOST_BASE, "", "/api/runtime/models", 12000)
                    .optJSONObject("current")?.optJSONObject("main")
            }.getOrNull()
            val sel = cur?.optString("model").orEmpty()
            kernelModelReady = cur?.optString("source") == "catalog" && sel.isNotEmpty()
            main.post {
                chipsHost.removeAllViews()
                chipsHost.addView(Ds.chip(act, "主模型 " + if (kernelModelReady) sel else "未配置",
                        kernelModelReady),
                    Ds.vp(left = Ds.dp(act, 4), w = LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }.start()
    }

    /** 未就绪项 → 引导卡（全部就绪则整卡 GONE；形态收敛自 P0 环境卡终态）。 */
    private fun refreshEnv() {
        envHost.removeAllViews()
        val rows = mutableListOf<Triple<String, Boolean, (() -> Unit)?>>()
        rows.add(Triple("无障碍通道", HachimiAccessibilityService.instance != null) {
            act.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        rows.add(Triple("悬浮窗权限", android.provider.Settings.canDrawOverlays(act)) {
            act.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${act.packageName}")))
        })
        rows.add(Triple("模型端点", kernelModelReady) { openSettings() })
        rows.add(Triple("屏幕观察(录屏)", ProjectionHolder.isRunning) {
            (act as? MainActivity)?.requestProjection()
        })
        rows.add(Triple("内核", KernelHostService.pyBridgeModule != null, null))
        val unready = rows.filter { !it.second }
        if (unready.isEmpty()) {
            envHost.visibility = View.GONE
            return
        }
        envHost.visibility = View.VISIBLE
        val card = Ds.card(act)
        card.addView(Ds.small(act, "环境未就绪（点击项去开启）：").apply { setPadding(0, 0, 0, Ds.dp(act, 4)) })
        unready.forEach { (label, _, action) ->
            card.addView(TextView(act).apply {
                text = "○ $label${if (action != null) " · 去开启" else "（启动中，稍候自动复查）"}"
                textSize = 13f; setTextColor(Ds.RED_TXT)
                setPadding(0, Ds.dp(act, 4), 0, Ds.dp(act, 4))
                if (action != null) setOnClickListener { action() }
            })
        }
        card.addView(Ds.small(act, "应用进程被杀（划掉后台/重装）后 ColorOS 不自动重连，" +
                "点对应项重设一次即可；正常不杀进程则一直有效。").apply {
            setPadding(0, Ds.dp(act, 8), 0, 0)
        })
        envHost.addView(card)
        envHost.addView(View(act).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.dp(act, 10))
        })
    }

    private fun refreshRecent() {
        // Phase 2（mini_loop 退役）：任务列表改读环回内核 /api/runtime/tasks（Web 同源）
        Thread {
            try {
                val out = OmniAgentRemote.kernelTasks(ChatPage.OMNI_HOST_BASE, 12)
                main.post { renderRecent(out) }
            } catch (_: Exception) {}
        }.start()
    }

    private fun renderRecent(tasks: org.json.JSONArray?) {
        recentHost.removeAllViews()
        if (tasks == null || tasks.length() == 0) {
            recentHost.addView(Ds.emptyState(act, "▦", "还没有任务",
                "在上面输入一句话开始第一个任务"))
            return
        }
        val card = Ds.card(act)
        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i) ?: continue
            if (i > 0) card.addView(Ds.divider(act))
            val ok = t.optBoolean("success")
            val state = t.optString("state")
            val glyph = when {
                ok -> "✓"
                state == "failed" -> "✗"
                state == "pending" || state == "running" -> "⏸"
                else -> "■"
            }
            val color = when {
                ok -> Ds.GREEN_TXT
                state == "failed" -> Ds.RED_TXT
                state == "pending" || state == "running" -> Ds.ORANGE_TXT
                else -> Ds.TEXT_2
            }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ds.dp(act, 8), 0, Ds.dp(act, 8))
                setOnClickListener { showTaskDetail(t) }
            }
            row.addView(TextView(act).apply {
                text = glyph; textSize = 14f; setTextColor(color)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, Ds.dp(act, 8), 0)
            })
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply {
                text = t.optString("objective").ifEmpty { "(空目标)" }
                textSize = 13.5f; setTextColor(Ds.TEXT)
                maxLines = 1
            })
            val steps = t.optInt("steps", -1)
            val wall = t.optDouble("wall_s", -1.0)
            val meta = mutableListOf<String>()
            if (steps >= 0) meta.add("$steps 步")
            if (wall >= 0) meta.add("${wall.toInt()}s")
            val proj = t.optString("project")
            if (proj.isNotEmpty() && proj != "default") meta.add("项目:$proj")
            meta.add((t.optString("created_at").take(10)))
            col.addView(Ds.small(act, meta.joinToString(" · ")).apply { setPadding(0, Ds.dp(act, 1), 0, 0) })
            row.addView(col.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            card.addView(row)
        }
        recentHost.addView(card)
    }

    /** 详情页（2026-09-30 需求③）：完整执行轨迹 + 继续对话（同 task 追加 run）。 */
    private fun showTaskDetail(t: JSONObject) {
        act.startActivity(Intent(act, com.hachimi.app.TaskDetailActivity::class.java).apply {
            putExtra("task_id", t.optString("task_id"))
            putExtra("objective", t.optString("objective"))
            putExtra("done_when", t.optString("done_when"))
            putExtra("state", t.optString("state"))
            putExtra("success", t.optBoolean("success"))
            putExtra("created_at", t.optString("created_at"))
            putExtra("steps", t.optInt("steps", -1))
            putExtra("wall_s", t.optDouble("wall_s", -1.0))
        })
    }

    private fun openSettings() {
        act.startActivity(Intent(act, com.hachimi.app.SettingsActivity::class.java))
    }

    // ---------------- 任务控制（Chaquopy 直调，P0 语义原样迁移） ----------------

    private fun startTask() {
        val objective = objInput.text.toString().trim()
        if (objective.isEmpty()) {
            result.render(null)
            toast("请先输入目标，例如：在便签里创建一条「买牛奶」的提醒")
            return
        }
        // 新任务开始：重置门控总放行（上次任务的"本次任务都放行"不带过来）
        com.hachimi.app.gate.GateManager.beginTask()
        // 无障碍未开直接跑必失败：弹窗引导去开启（Onboarding 页有完整四步）
        if (com.hachimi.app.HachimiAccessibilityService.instance == null) {
            android.app.AlertDialog.Builder(act)
                .setTitle("需要无障碍权限")
                .setMessage("大肥鱼要靠无障碍服务才能操作手机。请到开启页打开「Hachifish 无障碍服务」，完成后回来再发任务。")
                .setPositiveButton("去开启") { _, _ ->
                    act.startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        // BYOK 配置随内核 boot 注入（omni_host），此处无需 set_brain——内核未起时
        // 下发会 404，由异常路径提示
        startBtn.isEnabled = false
        main.post { result.showStarting() }
        Thread {
            try {
                // 悬浮窗 addView 必须在主线程（面板已收起时 show 会真正重建窗口）
                main.post { com.hachimi.app.NarrativePanel.show(act) }
                main.post {
                    act.startActivity(Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
                val tid = OmniAgentRemote.chat(ChatPage.OMNI_HOST_BASE, "", objective,
                    null, false, SettingsPage.maxSteps(act))
                currentTaskId = tid
                startBtn.isEnabled = true
                beginPolling(tid)
            } catch (e: Exception) {
                main.post {
                    toast("任务下发失败：${MainActivity.readableErrorStatic(e.message ?: "")}")
                    startBtn.isEnabled = true
                    result.render(null)
                }
            }
        }.start()
    }

    private fun onControl(action: String) {
        // Phase 2：pause/resume 随 mini_loop 退役（内核运行期无暂停语义）；stop 走内核 API
        if (action == "stop") {
            Thread {
                runCatching {
                    OmniAgentRemote.stop(ChatPage.OMNI_HOST_BASE, "", currentTaskId)
                }
            }.start()
        }
    }

    /** 运行期轮询内核任务状态（Phase 2）：700ms → RunResultView 渲染（运行中/终态）。 */
    fun beginPolling(taskId: String) {
        if (!polling.compareAndSet(false, true)) return
        Thread {
            var lastPhase = ""
            while (polling.get()) {
                try {
                    val r = OmniAgentRemote.getJson(ChatPage.OMNI_HOST_BASE, "",
                        "/api/runtime/tasks/" + java.net.URLEncoder.encode(taskId, "UTF-8"), 8000)
                    val meta = r.optJSONObject("task")
                    if (meta == null) break
                    val st = meta.optString("state")
                    val success = meta.optBoolean("success")
                    val phase = when {
                        st == "running" || st == "pending" -> "running"
                        success -> "done"
                        st == "failed" -> "error"
                        st == "aborted" -> "error"
                        else -> ""
                    }
                    if (phase != lastPhase && phase.isNotEmpty()) {
                        lastPhase = phase
                        val steps = r.optJSONObject("snapshot")?.optJSONArray("trajectory")?.length() ?: 0
                        val s = JSONObject()
                            .put("phase", phase)
                            .put("steps_so_far", steps)
                            .put("summary", meta.optString("objective"))
                            .put("paused", false)
                            .put("run_id", meta.optString("task_id"))
                        main.post { result.render(s) }
                        // 完成弹窗（主界面派单路径与对话页同一体验）
                        if (phase == "done") main.post {
                            com.hachimi.app.FinishPopup.show(act, true,
                                meta.optString("objective"), steps)
                        }
                        if (phase == "error") main.post {
                            com.hachimi.app.FinishPopup.show(act, false,
                                "任务未完成：" + meta.optString("objective"), steps)
                        }
                        if (phase == "done" || phase == "error") break
                    }
                } catch (_: Exception) {
                    break
                }
                Thread.sleep(700)
            }
            polling.set(false)
            main.post { refreshRecent() }
        }.start()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    companion object {
        const val PREFS = "hachimi_ui"
    }
}
