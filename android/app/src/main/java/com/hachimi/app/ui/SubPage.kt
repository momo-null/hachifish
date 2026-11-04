package com.hachimi.app.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * 全屏子页骨架（技能 / 工具 / MCP 三页共用，从「知识」页分节入口进入）。
 *
 * 形态沿用仓内既有惯例（红线级 UI 约定，见 RoleSettingsActivity）：零 XML 纯代码、
 * 无二级 Tabs（每件事一个全屏页）、返回行 + 居中标题 + 一句说明 + 单列卡片流、
 * 后台 Thread + 主线程回调。这里只收敛三页真正共用的部分：头部、状态行、内容宿主、
 * 后台请求封装、带重试的内核就绪轮询。
 */
class SubPage(
    private val act: Activity,
    title: String,
    subtitle: String,
    backLabel: String = "‹ 知识"
) {

    /** 内容宿主（子页各渲染函数只动它）。 */
    val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    /** 状态行：读取中 / 失败原因 / 写回结果（诚实回显，不做静默失败）。 */
    private val hint = Ds.small(act, "")

    private val box = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ds.PAGE)
        val pad = Ds.dp(act, 20)
        setPadding(pad, Ds.dp(act, 12), pad, Ds.dp(act, 24))
    }

    private val scroll = ScrollView(act)

    init {
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(act).apply {
            text = backLabel; textSize = 15f; setTextColor(Ds.PRIMARY)
            setOnClickListener { act.finish() }
        })
        head.addView(TextView(act).apply {
            text = title; textSize = 17f; setTextColor(Ds.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(View(act), LinearLayout.LayoutParams(Ds.dp(act, 44), 1))  // 与返回行配平
        box.addView(head)
        box.addView(Ds.small(act, subtitle).apply {
            setPadding(0, Ds.dp(act, 4), 0, Ds.dp(act, 10))
        })
        box.addView(hint)
        box.addView(body, Ds.vp(top = Ds.dp(act, 6)))
        scroll.addView(box)
    }

    /** 装上并处理系统条（edge-to-edge 留白由 Ds.systemBars 负责）。 */
    fun install() {
        act.setContentView(scroll)
        Ds.systemBars(act, box)
    }

    fun status(text: String) {
        hint.text = text
        hint.setTextColor(Ds.TEXT_2)
    }

    fun statusError(text: String) {
        hint.text = text
        hint.setTextColor(Ds.RED_TXT)
    }

    /** 重渲染：清空宿主后填入新内容（各页只做「读 → 渲染」，不做增量 patch）。 */
    fun render(block: (LinearLayout) -> Unit) {
        body.removeAllViews()
        block(body)
    }

    fun loading(text: String = "读取中…") {
        render { host -> host.addView(Ds.small(act, text)) }
    }

    fun empty(glyph: String, title: String, detail: String) {
        render { host -> host.addView(Ds.emptyState(act, glyph, title, detail)) }
    }

    /** 段落标题（与设置页同款 h2）。 */
    fun section(text: String): View = Ds.h2(act, text).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, Ds.dp(act, 14), 0, Ds.dp(act, 6)) }
    }

    /**
     * 后台一次请求 → 主线程回调。异常在这里收口成 `{error, _ok:false}`，
     * 页面永远拿到一个能直接呈现的 JSON（壳侧不出现空 catch）。
     */
    fun io(work: () -> JSONObject, onResult: (JSONObject) -> Unit) {
        Thread {
            val r = runCatching(work).getOrElse {
                JSONObject().put("error", "${it.javaClass.simpleName}: ${it.message ?: ""}")
                    .put("_ok", false).put("_status", 0)
            }
            act.runOnUiThread { if (!act.isFinishing) onResult(r) }
        }.start()
    }

    /**
     * 读一次并渲染，自带内核冷启动兜底：attach 约 10–15s，首次进子页常赶在就绪之前
     * （传输层失败 = `_status` 0），按 1.5s 退避重试，最多 [maxRetry] 次后诚实报错。
     */
    private var attempt = 0

    fun load(work: () -> JSONObject, render: (JSONObject) -> Unit) {
        loading()
        io(work) { r ->
            if (!r.optBoolean("_ok") && r.optInt("_status") == 0 && attempt < maxRetry) {
                attempt++
                status("内核启动中…（第 $attempt 次重试）")
                act.window.decorView.postDelayed({ load(work, render) }, 1500)
                return@io
            }
            attempt = 0
            if (r.optBoolean("_ok")) {
                status("")
                render(r)
            } else {
                val why = if (r.optInt("_status") == 0)
                    "内核未就绪——稍后退出本页重进一次。"
                else "HTTP ${r.optInt("_status")}：${r.optString("error")}"
                statusError(why)
                empty("!", "读取失败", why)
            }
        }
    }

    companion object {
        private const val maxRetry = 10
    }
}
