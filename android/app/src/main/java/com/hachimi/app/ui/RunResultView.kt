package com.hachimi.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.hachimi.app.MainActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * S5 完成态/运行态结果区（主界面状态区升级）：running=实时步数+当前动作+App 内
 * 暂停/停止（P0 DoD6 三处停止之其一）；done/error=结果卡——指标 tags（耗时/步数/
 * 重试/LLM 调用，status().summary 全有）+ 步骤时间线染色 + 查看轨迹/再来一次。
 * 数据面 = bridge.status_json 轮询（500ms，MainActivity.beginPolling 不变）。
 */
class RunResultView(private val act: Activity) {

    /** 渲染目标容器与回调：action ∈ {"pause","resume","stop"}；再来一次重置输入区。 */
    var onRetry: (() -> Unit)? = null
    var onControl: ((action: String) -> Unit)? = null

    private val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    val view: View get() = box

    /** 启动反馈卡（start_task 同步阻塞期间的主界面唯一可见信号——2026-09-30
     *  用户实测「界面不动」根因：新 UI 丢了旧版启动提示，阻塞返回前无任何渲染）。 */
    fun showStarting() {
        box.removeAllViews()
        val card = Ds.card(act)
        card.addView(TextView(act).apply {
            text = "▶"; textSize = 30f; gravity = android.view.Gravity.CENTER
            setTextColor(Ds.PRIMARY)
        })
        card.addView(TextView(act).apply {
            text = "正在启动任务…"
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ds.TEXT)
            gravity = android.view.Gravity.CENTER
            setPadding(0, Ds.dp(act, 4), 0, 0)
        })
        card.addView(Ds.small(act,
            "已发出指令，等待模型第一步（网关偶发 30~90 秒停顿属正常）。" +
                    "目标 App 内的操作会实时显示在悬浮叙述条/控制台。").apply {
            gravity = android.view.Gravity.CENTER; setPadding(0, Ds.dp(act, 8), 0, 0)
        })
        box.addView(card)
    }

    fun render(s: JSONObject?) {
        box.removeAllViews()
        if (s == null) return
        when (s.optString("phase")) {
            "running" -> box.addView(runningCard(s))
            "done" -> box.addView(resultCard(s, ok = true))
            "error" -> box.addView(resultCard(s, ok = false))
        }
    }

    // ---------------- 运行中 ----------------

    private fun runningCard(s: JSONObject): View {
        val paused = s.optBoolean("paused")
        val steps = s.optInt("steps_so_far")
        val elapsed = elapsedOf(s)
        val card = Ds.card(act)
        card.addView(Ds.appBar(act,
            (if (paused) "⏸ 已暂停" else "▶ 运行中") + " · $steps 步 · ${elapsed}s",
            listOf(Ds.tag(act, s.optString("run_id").take(14), Ds.TEXT_2, Ds.GRAY_BG))))
        val la = s.optJSONObject("last_action")
        val log = Ds.darkLog(act, maxLines = 3)
        log.text = if (la == null) "等待第一步…"
        else {
            val mark = when {
                !la.has("ok") || la.isNull("ok") -> "→"
                la.optBoolean("ok") -> "✓"
                else -> "✗"
            }
            "${mark} 步骤${la.optInt("step")} · ${la.optString("tool")} " +
                    (la.optJSONObject("args")?.toString()?.take(70) ?: "")
        }
        card.addView(log.apply { setPadding(dp(10), dp(6), dp(10), dp(6)) })
        card.addView(row(buttons = listOf(
            Ds.button(act, if (paused) "▶ 继续" else "⏸ 暂停", primary = false) {
                onControl?.invoke(if (paused) "resume" else "pause")
            },
            Ds.button(act, "⏹ 停止", primary = false, danger = true) {
                onControl?.invoke("stop")
            })))
        return card
    }

    // ---------------- 完成 / 未完成 ----------------

    private fun resultCard(s: JSONObject, ok: Boolean): View {
        val sum = s.optJSONObject("summary")
        val steps = sum?.optInt("steps") ?: s.optInt("steps_so_far")
        val wall = sum?.optDouble("wall_clock_s")?.toInt() ?: elapsedOf(s)
        val raw = s.optString("error").ifEmpty { sum?.optString("summary") ?: "" }
        val stopped = raw.contains("stopped by request") || raw.contains("已停止")

        val card = Ds.card(act)
        card.gravity = android.view.Gravity.CENTER_HORIZONTAL
        card.addView(TextView(act).apply {
            text = if (ok) "✅" else if (stopped) "⏹" else "⚠️"
            textSize = 34f; gravity = android.view.Gravity.CENTER
        })
        card.addView(TextView(act).apply {
            text = if (ok) "任务完成" else if (stopped) "已停止" else "任务未完成"
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (ok) Ds.GREEN_TXT else if (stopped) Ds.TEXT_2 else Ds.RED_TXT)
            gravity = android.view.Gravity.CENTER
            setPadding(0, Ds.dp(act, 4), 0, 0)
        })

        // 指标 tags（mockup S5：耗时/步数/重试/LLM 调用）
        val tags = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER }
        listOf(
            "${wall}s" to Ds.GREEN_TXT,
            "$steps 步" to Ds.PRIMARY,
            "${sum?.optInt("task_done_rejected") ?: 0} 次重试" to Ds.ORANGE_TXT,
            "LLM ${sum?.optInt("brain_calls") ?: 0} 次" to Ds.PURPLE_TXT
        ).forEach { (t, c) ->
            tags.addView(Ds.tag(act, t, c, Ds.GRAY_BG),
                Ds.vp(left = Ds.dp(act, 3), top = Ds.dp(act, 8), right = Ds.dp(act, 3),
                    w = LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        card.addView(tags)

        if (!ok && !stopped && raw.isNotEmpty()) {
            card.addView(Ds.small(act, MainActivity.readableErrorStatic(raw)).apply {
                setTextColor(Ds.RED_TXT); setPadding(0, Ds.dp(act, 8), 0, 0)
            })
        }

        // 步骤时间线（真实轨迹染色：✓ 绿 / ✗ 红 / 重试橙）
        val tl = Ds.darkLog(act, maxLines = 8)
        tl.text = timelineText(s.optJSONArray("trajectory"))
        card.addView(tl.apply { setPadding(dp(10), dp(8), dp(10), dp(8)) })

        card.addView(row(buttons = listOf(
            Ds.button(act, "查看完整轨迹", primary = false) { openDetail(s, ok, stopped) },
            Ds.button(act, "继续对话", primary = false) { openDetail(s, ok, stopped, focus = true) })))
        card.addView(row(buttons = listOf(
            Ds.button(act, "再来一次", primary = false) { onRetry?.invoke() })))
        return card
    }

    /**
     * 详情页入口（2026-09-30 需求③）：有 task_id 时跳任务详情（轨迹 + 继续对话）；
     * 无落盘（内核未持久化/旧 run）退回进程内轨迹弹层。
     */
    private fun openDetail(s: JSONObject, ok: Boolean, stopped: Boolean, focus: Boolean = false) {
        val tid = s.optString("task_id")
        if (tid.isEmpty()) { showTrajectory(s); return }
        act.startActivity(android.content.Intent(act, com.hachimi.app.TaskDetailActivity::class.java).apply {
            putExtra("task_id", tid)
            putExtra("objective", s.optString("objective"))
            putExtra("state", if (ok) "done" else if (stopped) "stopped" else "failed")
            putExtra("run_id", s.optString("run_id"))
            if (focus) putExtra("focus_input", true)
        })
    }

    private fun showTrajectory(s: JSONObject) {
        val sb = StringBuilder()
        val arr = s.optJSONArray("trajectory")
        for (i in 0 until (arr?.length() ?: 0)) {
            val st = arr!!.optJSONObject(i) ?: continue
            val a = st.optJSONObject("action")
            sb.append("step ").append(st.optInt("step")).append("  ")
                .append(a?.optString("tool") ?: "?").append("  ")
                .append(a?.optJSONObject("args")?.toString()?.take(90) ?: "").append('\n')
        }
        AlertDialog.Builder(act)
            .setTitle("轨迹 · ${s.optString("run_id").take(14)}")
            .setMessage(sb.toString().ifEmpty { "（无轨迹数据）" })
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun timelineText(arr: JSONArray?): CharSequence {
        val sb = SpannableString(buildString {
            for (i in 0 until (arr?.length() ?: 0)) {
                val st = arr!!.optJSONObject(i) ?: continue
                val r = st.optJSONObject("result")
                val ok = when {
                    r == null || r.isNull("ok") -> 0
                    r.optBoolean("ok") -> 1
                    else -> -1
                }
                append((if (ok == 1) "✓" else if (ok == -1) "✗" else "→") +
                        " ${st.optInt("step")} ${st.optJSONObject("action")?.optString("tool") ?: "?"}\n")
            }
        }.ifEmpty { "（暂无步骤）" })
        // 逐行染色：✓ 绿 / ✗ 红 / → 浅蓝
        var idx = 0
        for (line in sb.split("\n")) {
            if (line.isNotEmpty()) {
                val color = when (line.first()) {
                    '✓' -> Ds.GREEN_LOG
                    '✗' -> Ds.RED
                    else -> Ds.ACCENT
                }
                sb.setSpan(ForegroundColorSpan(color), idx, idx + 1, 0)
            }
            idx += line.length + 1
        }
        return sb
    }

    private fun row(buttons: List<View>): View {
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEachIndexed { i, b ->
            b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(Ds.dp(act, if (i == 0) 0 else 4), Ds.dp(act, 10), 0, 0) }
            r.addView(b)
        }
        return r
    }

    private fun dp(v: Int) = Ds.dp(act, v)

    private fun elapsedOf(s: JSONObject): Int =
        if (s.has("started") && !s.isNull("started"))
            (System.currentTimeMillis() / 1000 - s.optDouble("started")).toInt() else 0
}
