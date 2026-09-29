package com.hachimi.app

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import kotlin.random.Random

/**
 * Canvas 盲区测试页（P1 DoD3 fixture）：交互目标纯 Canvas 绘制——无文本、
 * 无 contentDescription、无 view_id，a11y 树里只有一个不可交互的裸节点；
 * 目标位置只能经视觉通道（look → VLM）获取，且每次命中后红色目标换位
 * （防坐标记忆）。三重验证：
 * 1) filesDir/hits.log（run-as 可读）= 客观命中记录；
 * 2) TextView hits=N 是唯一 a11y 可见状态，done_when 弱探测据此在真实命中后放行；
 * 3) 投影帧可见目标换位与计数。
 * 测试夹具：exported 仅组件显式可达，无 intent-filter。
 */
private data class Shape(val cx: Float, val cy: Float, val r: Float,
                         val color: Int, val target: Boolean)

class CanvasProbeActivity : Activity() {

    private lateinit var status: TextView
    private var hits = 0
    private val startMs = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val probe = ProbeView(this)
        box.addView(probe, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        status = TextView(this).apply {
            text = "hits=0"
            textSize = 18f
            setPadding(pad, pad / 2, pad, pad)
        }
        box.addView(status)
        setContentView(box)
    }

    private fun onHit(x: Float, y: Float) {
        hits += 1
        status.text = "hits=$hits"
        try {
            val loc = IntArray(2)
            window.decorView.getLocationOnScreen(loc)
            // 屏幕归一化（真实 1080x2400，测试夹具固定口径），与 tap_xy 轨迹同空间
            File(filesDir, "hits.log").appendText(
                "hit,$hits,${System.currentTimeMillis() - startMs}," +
                        "%.3f".format((loc[0] + x) / 1080f) + "," +
                        "%.3f".format((loc[1] + y) / 2400f) + "\n")
        } catch (_: Exception) {
        }
    }

    private inner class ProbeView(context: android.content.Context) : View(context) {

        private val rng = Random(System.currentTimeMillis())
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.WHITE
        }
        private var shapes: List<Shape> = emptyList()

        /** 目标+干扰项布点（含命中后换位）。画布上部 62% 区域，避开底部计数行。 */
        private fun deal() {
            val w = width.toFloat()
            val h = height.toFloat() * 0.62f
            if (w < 200f || h < 200f) return
            val r = (w.coerceAtMost(h) * 0.19f).coerceIn(70f, 210f)
            fun place(): Pair<Float, Float> =
                Pair(rng.nextFloat() * (w - 2 * r) + r, rng.nextFloat() * (h - 2 * r) + r)
            val out = mutableListOf<Shape>()
            val (tx, ty) = place()
            out.add(Shape(tx, ty, r, Color.rgb(0xE5, 0x39, 0x35), target = true))
            // 客观核验：红圈落位（屏幕归一化，与 tap_xy 同空间）写日志对账
            try {
                val loc = IntArray(2)
                this@ProbeView.getLocationOnScreen(loc)
                File(filesDir, "hits.log").appendText(
                    "deal," + "%.3f".format((loc[0] + tx) / 1080f) + "," +
                            "%.3f".format((loc[1] + ty) / 2400f) + "\n")
            } catch (_: Exception) {
            }
            for (i in 0 until 4) {
                var (cx, cy) = place()
                var guard = 0
                // 干扰项与已放目标/干扰项保持间距，避免视觉上叠住红圈
                while (out.any { val dx = it.cx - cx; val dy = it.cy - cy;
                                 dx * dx + dy * dy < (2.6f * r) * (2.6f * r) } && guard++ < 40) {
                    val p = place(); cx = p.first; cy = p.second
                }
                out.add(Shape(cx, cy, r, Color.rgb(0x19, 0x76, 0xD2), target = false))
            }
            shapes = out
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(0x14, 0x16, 0x1A))
            if (shapes.isEmpty()) deal()
            for (s in shapes) {
                fill.color = s.color
                canvas.drawCircle(s.cx, s.cy, s.r, fill)
                if (s.target) canvas.drawCircle(s.cx, s.cy, s.r + 14f, ring)
            }
        }

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (e.action != android.view.MotionEvent.ACTION_DOWN) return true
            val hit = shapes.firstOrNull {
                val dx = it.cx - e.x; val dy = it.cy - e.y
                dx * dx + dy * dy <= it.r * it.r
            }
            if (hit?.target == true) {
                onHit(e.x, e.y)
                deal()   // 命中即换位：逼出逐次视觉定位
                invalidate()
            }
            return true
        }
    }
}
