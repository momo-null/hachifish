package com.hachimi.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * BYOK 设置页（P0 DoD3/4）：端点 / 密钥 / 模型三项，Keystore 加密落盘（BrainConfigStore），
 * 重启 App 后仍生效；「测试连通」走 bridge.test_brain（与任务同一 BrainClient 链路）。
 * 密钥明文不出本机：仅存 App 私有目录加密文件，仅与用户配置的端点通信（原则 2/5）。
 */
class SettingsActivity : Activity() {

    private lateinit var baseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var resultView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loadCurrent()
    }

    private fun buildUi(): View {
        val pad = (14 * resources.displayMetrics.density).toInt()
        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        box.addView(TextView(this).apply {
            text = "BYOK 模型设置"
            textSize = 20f
            setPadding(0, 0, 0, pad / 2)
        })
        box.addView(TextView(this).apply {
            text = "自带端点与密钥（Bring Your Own Key）。配置只存本机（Keystore 加密），\n" +
                    "应用只与你填写的端点通信。"
            textSize = 12f; setTextColor(0xFF6B7280.toInt())
            setPadding(0, 0, 0, pad)
        })

        box.addView(label("端点 base_url（OpenAI 兼容，填到 /v1 这一级）"))
        baseUrl = EditText(this).apply { hint = "https://api.example.com/v1" }
        box.addView(baseUrl)

        box.addView(label("API Key"))
        apiKey = EditText(this).apply { hint = "sk-…" }
        box.addView(apiKey)

        box.addView(label("模型名"))
        model = EditText(this).apply { hint = "例如 deepseek-flash" }
        box.addView(model)

        box.addView(Button(this).apply {
            text = "保存"
            setOnClickListener { save() }
        })
        box.addView(Button(this).apply {
            text = "测试连通"
            setOnClickListener { test() }
        })

        resultView = TextView(this).apply {
            textSize = 13f; setPadding(0, pad / 2, 0, 0)
        }
        box.addView(resultView)

        root.addView(box)
        return root
    }

    private fun loadCurrent() {
        val cfg = BrainConfigStore.load(this)
        if (cfg != null) {
            baseUrl.setText(cfg.baseUrl)
            apiKey.setText(cfg.apiKey)
            model.setText(cfg.model)
            resultView.text = "已加载本机保存的配置（模型 ${cfg.model}）"
        }
    }

    private fun save() {
        val url = baseUrl.text.toString().trim()
        val key = apiKey.text.toString().trim()
        val m = model.text.toString().trim()
        if (url.isEmpty() || m.isEmpty()) {
            show(false, "端点和模型名不能为空")
            return
        }
        val ok = BrainConfigStore.save(this, url, key, m)
        show(ok, if (ok) "已保存（密钥经 Keystore 加密落盘，重启 App 后仍生效）"
        else "保存失败（Keystore 或文件写入异常），请重试")
    }

    /** 先保存当前输入再测——「测试通过 = 保存的配置可用」，避免测的是旧值。 */
    private fun test() {
        save()
        val url = baseUrl.text.toString().trim()
        if (url.isEmpty()) return
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) {
            show(false, "内核未就绪（首次启动约需 10 秒），稍后再试；或先回主界面等待内核就绪。")
            return
        }
        resultView.text = "测试中（最长 20 秒）…"
        Thread {
            try {
                val r = JSONObject(bridge.callAttr(
                    "test_brain_json", url, apiKey.text.toString().trim(),
                    model.text.toString().trim()).toString())
                runOnUiThread {
                    if (r.optBoolean("ok")) {
                        show(true, "连通 ✓ ${r.optInt("latency_ms")}ms · 模型回复：" +
                                "${r.optString("reply").ifEmpty { "(空)" }}")
                    } else {
                        show(false, readableError(r.optString("error")))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    show(false, "测试调用失败：${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }.start()
    }

    private fun show(ok: Boolean, msg: String) {
        resultView.text = msg
        resultView.setTextColor(if (ok) Color.rgb(0x2F, 0x6B, 0x3A) else Color.rgb(0xB4, 0x23, 0x18))
    }

    /** 与 MainActivity 同一套人话映射（DoD4）。 */
    private fun readableError(raw: String): String = when {
        raw.contains("HTTP 401") || raw.contains("HTTP 403") ->
            "密钥无效或无权限（401/403）。请检查 API Key 是否正确、账户是否可用。\n[$raw]"
        raw.contains("HTTP 404") ->
            "端点不存在（404）。base_url 需填到 /v1 这一级，应用会自动拼接 /chat/completions。\n[$raw]"
        raw.contains("HTTP 429") -> "请求过于频繁或账户额度不足（429）。\n[$raw]"
        raw.contains("URLError") || raw.contains("timed out", true) ->
            "网络不通或请求超时。检查手机联网与端点地址是否可达。\n[$raw]"
        raw.contains("unexpected response shape") ->
            "端点已连通，但响应不是 OpenAI 兼容格式（需支持 /chat/completions）。\n[$raw]"
        raw.contains("HTTP 400") ->
            "请求被端点拒绝（400）。可能该端不支持 thinking 参数或模型名有误。\n[$raw]"
        else -> raw
    }

    private fun label(s: String): TextView = TextView(this).apply {
        text = s; textSize = 12f; setTextColor(0xFF6B7280.toInt())
        setPadding(0, 12, 0, 2)
    }
}
