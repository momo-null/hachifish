package com.hachimi.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hachimi.app.ui.Ds
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * S4 真分屏控制台页：用户把 Hachimi 与目标 App 上下分屏（最近任务 → 应用图标 →
 * 分屏，Android 12 无程序化公开 API），Hachimi 这一半打开本页即 agent 控制台——
 * 感知 / 决策 / 时间线染色 + 暂停/停止，数据 = status_json 500ms 轮询 +
 * NarrativePanel.snapshot()（面板事件流）。
 * 本页可见时 NarrativePanel.consoleVisible=true：overlay 停靠自动退出（防双 UI）。
 * 主界面在多窗口模式下出现「控制台视图」入口；本页 finish 返回主界面。
 */
class ConsoleActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val polling = AtomicBoolean(false)

    private lateinit var statusLine: TextView
    private lateinit var objectiveLine: TextView
    private lateinit var perception: TextView
    private lateinit var decision: TextView
    private lateinit var timeline: TextView
    private lateinit var pauseBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val pad = Ds.dp(this, 14)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.setPadding(pad, pad / 2, pad, pad / 2)

        box.addView(TextView(this).apply {
            text = "AGENT 控制台 · 分屏模式"
            textSize = 11f; typeface = Typeface.MONOSPACE
            setTextColor(Ds.ACCENT)
        })
        statusLine = TextView(this).apply {
            text = "idle"; textSize = 11f; typeface = Typeface.MONOSPACE
            setTextColor(Ds.DARK_TXT)
        }
        box.addView(statusLine)
        objectiveLine = TextView(this).apply {
            textSize = 12.5f; setTextColor(Ds.DARK_TXT); maxLines = 2
        }
        box.addView(objectiveLine)
        perception = TextView(this).apply {
            text = "感知: -"; textSize = 12f; setTextColor(Ds.GREEN_LOG); maxLines = 3
        }
        box.addView(perception)
        decision = TextView(this).apply {
            text = "决策: -"; textSize = 12f; setTextColor(0xFFFFD9A0.toInt()); maxLines = 1
            typeface = Typeface.MONOSPACE
        }
        box.addView(decision)
        timeline = TextView(this).apply {
            textSize = 11.5f; setTextColor(Ds.DARK_TXT); maxLines = 8
            typeface = Typeface.MONOSPACE
        }
        box.addView(timeline, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(0, pad / 2, 0, pad / 2)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pauseBtn = Ds.button(this, "⏸ 暂停", primary = false) { togglePause() }
        row.addView(pauseBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ds.button(this, "⏹ 停止", primary = false, danger = true) {
            Thread { try { KernelHostService.pyBridgeModule?.callAttr("request_stop") } catch (_: Exception) {} }.start()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(Ds.dp(this@ConsoleActivity, 6), 0, 0, 0)
        })
        box.addView(row)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xF21E2026.toInt())
            addView(box)
        })
    }

    override fun onResume() {
        super.onResume()
        NarrativePanel.consoleVisible = true
        NarrativePanel.hide()
        beginPolling()
    }

    override fun onPause() {
        super.onPause()
        NarrativePanel.consoleVisible = false
    }

    private fun togglePause() {
        val pausedNow = pauseBtn.text.contains("继续")
        Thread {
            try {
                KernelHostService.pyBridgeModule
                    ?.callAttr(if (pausedNow) "request_resume" else "request_pause")
            } catch (_: Exception) {}
        }.start()
    }

    private fun beginPolling() {
        if (!polling.compareAndSet(false, true)) return
        Thread {
            while (polling.get()) {
                try {
                    val bridge = KernelHostService.pyBridgeModule
                    if (bridge == null) break
                    val s = JSONObject(bridge.callAttr("status_json").toString())
                    val snap = NarrativePanel.snapshot()
                    main.post { render(s, snap) }
                    if (s.optString("phase") !in listOf("running", "idle")) {
                        main.postDelayed({ finish() }, 2500)   // 收尾回主界面看结果卡
                        break
                    }
                } catch (_: Exception) {}
                Thread.sleep(500)
            }
            polling.set(false)
        }.start()
    }

    private fun render(s: JSONObject, snap: JSONObject) {
        val phase = s.optString("phase")
        val paused = s.optBoolean("paused")
        val la = s.optJSONObject("last_action")
        statusLine.text = (if (phase == "running") (if (paused) "⏸ 已暂停" else "▶ 运行中") else phase) +
                " · " + s.optInt("steps_so_far") + " 步" +
                (if (la == null) "" else " · " + la.optInt("step") + " " + la.optString("tool") +
                        (if (!la.isNull("ok")) (if (la.optBoolean("ok")) " ✓" else " ✗") else " …"))
        objectiveLine.text = "目标: " + s.optString("objective").take(60)
        perception.text = snap.optString("perception").ifEmpty { "感知: -" }
        decision.text = snap.optString("decision").ifEmpty { "决策: -" }
        timeline.text = snap.optString("timeline").ifEmpty { "（暂无步骤）" }
        pauseBtn.text = if (paused) "▶ 继续" else "⏸ 暂停"
    }

    companion object {
        private const val TAG = "HachimiConsole"
    }
}
