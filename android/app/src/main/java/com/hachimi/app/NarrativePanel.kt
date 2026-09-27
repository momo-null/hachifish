package com.hachimi.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * 悬浮叙事面板（架构 §6 前台引导模式组成，交互原型 S3）：
 * 步骤/当前动作/日志 + 暂停/继续/停止。TYPE_APPLICATION_OVERLAY 悬浮于目标 App 之上，
 * FLAG_NOT_FOCUSABLE 不抢输入焦点。数据源 = Python bridge 叙事事件（register_narrative）。
 * 需要「显示在其他应用上层」权限；研究机经 adb appops 授予。
 */
object NarrativePanel {

    private const val TAG = "HachimiPanel"
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var view: LinearLayout? = null
    private var statusLine: TextView? = null
    private var logLine: TextView? = null
    private var paused = false

    /** Python 叙事事件入口（bridge.register_narrative 注入的 sink）。 */
    @JvmStatic
    fun push(json: String) {
        main.post {
            try {
                val e = JSONObject(json)
                when (e.optString("event")) {
                    "start" -> append("▶ 目标: " + e.optString("objective").take(40))
                    "step" -> {
                        val tool = e.optString("tool")
                        val ok = if (e.has("ok")) (if (e.optBoolean("ok")) "✓" else "✗") else "…"
                        val err = if (e.isNull("error")) "" else e.optString("error")
                        append("step ${e.optInt("step")} $tool $ok" +
                                (if (err.isNotEmpty()) " $err".take(60) else ""))
                    }
                    "paused" -> append("⏸ 已暂停（面板继续可恢复）")
                    "finish" -> append("■ 完成 ${e.optString("status")} · ${e.optInt("steps")}步")
                    "error" -> append("■ 错误: " + e.optString("error").take(80))
                }
                if (e.has("event")) statusLine?.text =
                    "Hachimi · run ${e.optString("run_id").take(14)}"
            } catch (ex: Exception) {
                Log.w(TAG, "push parse: ${ex.message}")
            }
        }
    }

    @Synchronized
    fun show(context: Context) {
        if (view != null) return
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.w(TAG, "overlay permission missing (adb appops SYSTEM_ALERT_WINDOW)")
            return
        }
        val pad = (12 * context.resources.displayMetrics.density).toInt()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6000000.toInt())
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val title = TextView(context).apply {
            text = "Hachimi 叙事面板"
            setTextColor(Color.WHITE); textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
        }
        statusLine = TextView(context).apply {
            text = "Hachimi · idle"
            setTextColor(0xFF9FD8FF.toInt()); textSize = 10f
        }
        logLine = TextView(context).apply {
            text = ""
            setTextColor(Color.WHITE); textSize = 11f
            maxLines = 4
        }
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, onClick: (Button) -> Unit) = Button(context).apply {
            text = label; textSize = 11f
            setOnClickListener { v -> onClick(v as Button) }
            buttons.addView(this)
        }
        btn("暂停") { b ->
            paused = !paused
            py(if (paused) "request_pause" else "request_resume")
            b.text = if (paused) "继续" else "暂停"
        }
        btn("停止") { _ -> py("request_stop") }
        btn("收起") { _ -> hide() }
        box.addView(title)
        box.addView(statusLine)
        box.addView(logLine)
        box.addView(buttons)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        try {
            wm = context.getSystemService(WindowManager::class.java)
            wm!!.addView(box, params)
            view = box
            Log.i(TAG, "panel shown")
        } catch (e: Exception) {
            Log.e(TAG, "show failed: ${e.message}")
        }
    }

    @Synchronized
    fun hide() {
        val v = view ?: return
        try {
            wm?.removeView(v)
        } catch (_: Exception) {}
        view = null
        logLine = null
        statusLine = null
    }

    private fun append(line: String) {
        val v = logLine ?: return
        v.text = (if (v.text.isNotEmpty()) v.text.toString() + "\n" else "") + line
        if ((v.text.split("\n").size) > 4) {
            v.text = v.text.split("\n").takeLast(4).joinToString("\n")
        }
    }

    /** 面板按钮 → Python bridge 控制面（挂在 KernelHostService 缓存的 bridge 模块上）。 */
    private fun py(method: String) {
        try {
            KernelHostService.pyBridgeModule?.callAttr(method)
        } catch (e: Exception) {
            Log.w(TAG, "py $method: ${e.message}")
        }
    }
}
