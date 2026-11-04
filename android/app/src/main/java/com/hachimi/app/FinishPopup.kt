package com.hachimi.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hachimi.app.ui.Ds
import com.hachimi.app.ui.FishAssets

/**
 * 任务完成弹窗（2026-11-04 用户需求）：任务终态把成功/失败展示卡（与对话流
 * DonePanel/FailPanel 同视觉：大肥鱼 + 「办好了~/这次没办好…」+ 摘要）以 overlay
 * 弹窗呈现——执行期间用户在别的 App（悬浮球干活形态），结束后能立刻知道结果；
 * 「返回 Hachifish」一键回主界面。TYPE_APPLICATION_OVERLAY + 非聚焦：不抢键盘，
 * 点卡片外即收起。悬浮窗权限缺失时静默跳过（对话流内仍有同款面板兜底）。
 */
object FinishPopup {

    private var wm: WindowManager? = null
    private var root: View? = null
    private val main = Handler(Looper.getMainLooper())

    /** 弹出（主线程调用；已开则替换内容）。 */
    fun show(context: Context, success: Boolean, summary: String,
             steps: Int, wallClockS: Double? = null) {
        if (!android.provider.Settings.canDrawOverlays(context)) return
        main.post { display(context, success, summary, steps, wallClockS) }
    }

    private fun display(context: Context, success: Boolean, summary: String,
                        steps: Int, wallClockS: Double?) {
        dismiss()
        try {
            val c = context.applicationContext
            val w = c.getSystemService(WindowManager::class.java) ?: return
            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = Ds.round(c, 16, Color.WHITE, 1, Ds.CARD_BORDER)
                val p = Ds.dp(c, 14)
                setPadding(p, p, p, p)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            // 头：大肥鱼成功/失败像 + 标题（与对话流面板同构，64dp 用户定版）
            val head = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(ImageView(c).apply {
                setImageResource(if (success) FishAssets.DONE else FishAssets.FAIL)
                adjustViewBounds = true
                maxHeight = Ds.dp(c, 64)
            }, LinearLayout.LayoutParams(Ds.dp(c, 64), LinearLayout.LayoutParams.WRAP_CONTENT))
            val info = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ds.dp(c, 10), 0, 0, 0)
            }
            info.addView(TextView(c).apply {
                text = if (success) "办好了~" else "这次没办好…"
                textSize = 15f; setTextColor(Ds.TEXT); typeface = Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(c).apply {
                text = if (success)
                    "$steps 步" + (wallClockS?.let { " · ${"%.1f".format(it)}s" } ?: "")
                else "它真尽力了，别嘲笑它"
                textSize = 11f; setTextColor(Ds.TEXT_2)
            })
            head.addView(info)
            card.addView(head)
            if (summary.isNotBlank()) {
                card.addView(TextView(c).apply {
                    text = summary; textSize = 13f; setTextColor(Ds.TEXT_2)
                    setLineSpacing(Ds.dp(c, 2).toFloat(), 1f)
                    setPadding(0, Ds.dp(c, 10), 0, 0)
                })
            }
            // 返回 Hachifish：回主界面（后台弹出场景的核心动作）
            val back = TextView(c).apply {
                text = "返回 Hachifish"; textSize = 13.5f; setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = Ds.round(c, 10, Ds.PRIMARY)
                val p2 = Ds.dp(c, 12)
                setPadding(p2, Ds.dp(c, 10), p2, Ds.dp(c, 10))
                setOnClickListener {
                    dismiss()
                    c.startActivity(Intent(c, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                }
            }
            card.addView(back, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ds.dp(c, 14)
            })
            // 知道了：仅收起
            val ok = TextView(c).apply {
                text = "知道了"; textSize = 12.5f; setTextColor(Ds.TEXT_2)
                gravity = Gravity.CENTER
                setPadding(Ds.dp(c, 12), Ds.dp(c, 8), Ds.dp(c, 12), Ds.dp(c, 2))
                setOnClickListener { dismiss() }
            }
            card.addView(ok, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))

            // 根：半透明遮罩 + 居中卡片；点遮罩 = 收起（NOT_FOCUSABLE 不挡触摸）
            val r = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#66000000"))
                setOnClickListener { dismiss() }
            }
            r.addView(card)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT)
            w.addView(r, params)
            wm = w; root = r
        } catch (_: Exception) {
        }
    }

    fun dismiss() {
        try { root?.let { wm?.removeView(it) } } catch (_: Exception) {}
        root = null; wm = null
    }
}
