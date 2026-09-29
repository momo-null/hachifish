package com.hachimi.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import com.hachimi.app.BrainConfigStore.BrainConfig
import org.json.JSONObject

/**
 * BYOK 设置页（单模型区 + 视觉能力开关，测试阶段简化形态）：
 * 一组端点/密钥/模型（规划与视觉共用同一端点），勾选「视觉能力」= 开启截图问模型
 * 的观察兜底，不勾 = 纯无障碍树（P0 行为）。Keystore 加密落盘（BrainConfigStore v3）。
 * 「测试连通」走 bridge.test_brain；「测试视觉」用 Kotlin 生成的纯色图走 bridge.test_vision
 * ——两者与正式任务同一客户端链路。
 */
class SettingsActivity : Activity() {

    private lateinit var baseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var visionToggle: CheckBox
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
            text = "模型设置"
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
        model = EditText(this).apply { hint = "例如 deepseek-v4-flash-vision" }
        box.addView(model)

        box.addView(CheckBox(this).apply {
            text = "视觉能力（看不清的界面自动截图问模型；模型需支持图片输入）"
            textSize = 13f
            setPadding(0, 12, 0, 0)
        }.also { visionToggle = it })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "保存"; setOnClickListener { save() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Button(this).apply {
            text = "测试连通"; setOnClickListener { testBrain() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        box.addView(row)
        box.addView(Button(this).apply {
            text = "测试视觉（发一张纯色图问主色）"
            setOnClickListener { testVision() }
        })

        resultView = TextView(this).apply {
            textSize = 13f; setPadding(0, pad / 2, 0, 0)
        }
        box.addView(resultView)

        root.addView(box)
        return root
    }

    private fun loadCurrent() {
        val cfg = BrainConfigStore.load(this) ?: return
        baseUrl.setText(cfg.baseUrl)
        apiKey.setText(cfg.apiKey)
        model.setText(cfg.model)
        visionToggle.isChecked = cfg.visionEnabled
        val vis = if (cfg.visionEnabled) "视觉开" else "视觉关"
        resultView.text = "已加载本机保存的配置（${cfg.model} · $vis）"
    }

    private fun collect(): BrainConfig? {
        val url = baseUrl.text.toString().trim()
        val key = apiKey.text.toString().trim()
        val m = model.text.toString().trim()
        if (url.isEmpty() || m.isEmpty()) {
            show(false, "端点和模型名不能为空")
            return null
        }
        return BrainConfig(url, key, m, visionToggle.isChecked)
    }

    private fun save(): Boolean {
        val cfg = collect() ?: return false
        val ok = BrainConfigStore.save(this, cfg)
        show(ok, if (ok) {
            "已保存（Keystore 加密落盘，重启 App 后仍生效；" +
                    if (cfg.visionEnabled) "视觉开）" else "视觉关）"
        } else "保存失败（Keystore 或文件写入异常），请重试")
        return ok
    }

    /** 测的是存的：先保存再测，避免「测通过但没保存」的错觉。 */
    private fun testBrain() {
        if (!save()) return
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) {
            show(false, "内核未就绪（首次启动约需 10 秒），稍后再试。")
            return
        }
        resultView.text = "测试中（最长 20 秒）…"
        val url = baseUrl.text.toString().trim()
        Thread {
            try {
                val r = JSONObject(bridge.callAttr(
                    "test_brain_json", url, apiKey.text.toString().trim(),
                    model.text.toString().trim()).toString())
                runOnUiThread {
                    if (r.optBoolean("ok")) {
                        show(true, "连通 ✓ ${r.optInt("latency_ms")}ms · 回复：" +
                                r.optString("reply").ifEmpty { "(空)" })
                    } else {
                        show(false, readableError(r.optString("error")))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { show(false, "测试调用失败：${e.javaClass.simpleName}: ${e.message}") }
            }
        }.start()
    }

    private fun testVision() {
        val cfg = collect() ?: return
        if (!BrainConfigStore.save(this, cfg)) { show(false, "保存失败"); return }
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) {
            show(false, "内核未就绪（首次启动约需 10 秒），稍后再试。")
            return
        }
        resultView.text = "生成测试图并发送（最长 60 秒）…"
        val b64 = makeTestImageB64()
        Thread {
            try {
                val r = JSONObject(bridge.callAttr(
                    "test_vision_json", cfg.baseUrl, cfg.apiKey,
                    cfg.model, b64).toString())
                runOnUiThread {
                    if (r.optBoolean("ok")) {
                        show(true, "视觉连通 ✓ ${r.optInt("latency_ms")}ms · 模型回答：" +
                                r.optString("text").take(60).ifEmpty { "(空)" } +
                                "（测试图是纯红色，回答含「红」即全链路正确）")
                    } else {
                        show(false, readableError(r.optString("error")))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { show(false, "测试调用失败：${e.javaClass.simpleName}: ${e.message}") }
            }
        }.start()
    }

    /** 64×64 纯红 JPEG → base64（视觉链路测试图；主色问题对任何 VLM 都该答「红」）。 */
    private fun makeTestImageB64(): String {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(0xD4, 0x2B, 0x1C))
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        bmp.recycle()
        return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
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
            "端点已连通，但响应不是 OpenAI 兼容格式（需支持 /chat/completions；" +
                    "视觉模型还需支持图片输入）。\n[$raw]"
        raw.contains("HTTP 400") ->
            "请求被端点拒绝（400）。可能该端点/模型不支持图片输入，或模型名有误。\n[$raw]"
        else -> raw
    }

    private fun label(s: String): TextView = TextView(this).apply {
        text = s; textSize = 12f; setTextColor(0xFF6B7280.toInt())
        setPadding(0, 12, 0, 2)
    }
}
