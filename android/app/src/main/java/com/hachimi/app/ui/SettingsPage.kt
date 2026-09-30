package com.hachimi.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hachimi.app.BrainConfigStore
import com.hachimi.app.BrainConfigStore.BrainConfig
import com.hachimi.app.KernelHostService
import com.hachimi.app.MainActivity
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 设置页（P4 起：角色分组置顶 + 模型配置简化）：
 * ⓪角色——大肥鱼×我（点击进 RoleSettingsActivity，人设/称呼/头像）；
 * ①通道 BYOK——单端点三栏 + 「启用视觉」开关（on=与主模型共用端点，2026-09-30
 *   用户定简化形态；存储 v4 零改动，vision_* 落盘继承主端点值）；
 * ②执行——默认模式 + 门控级别；③知识占位；④实验连点入口。
 */
class SettingsPage(private val act: Activity) {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var planUrl: EditText
    private lateinit var planKey: EditText
    private lateinit var planModel: EditText
    private lateinit var maxStepsField: EditText
    private lateinit var resultView: TextView
    private var roleNameLabel: TextView? = null
    private var roleDescLabel: TextView? = null
    private var modeSeg: Ds.Seg? = null
    private var gateSeg: Ds.Seg? = null
    private var visSeg: Ds.Seg? = null
    private var injectSeg: Ds.Seg? = null
    private var taps = 0

    /** 主界面分段 ↔ 设置页「默认模式」双向同步钩子（TasksPage 注册）。 */
    companion object {
        @JvmStatic var onRunModeChanged: ((Int) -> Unit)? = null

        /** 门控级别（GateManager.enforce 读取）：standard=type_text+launch_app，relaxed=仅 type_text。 */
        fun gateLevel(c: Activity): String =
            c.getSharedPreferences(TasksPage.PREFS, 0).getString("gate_level", "standard") ?: "standard"

        /** 步数上限（每任务执行预算）：TasksPage.startTask / 详情页续话写入 /task body
         *  的 max_steps。内核默认 24（便签类任务实测需 ~15 步），可配范围 4–2000。 */
        fun maxSteps(c: Activity): Int =
            c.getSharedPreferences(TasksPage.PREFS, 0).getInt("max_steps", 24).coerceIn(4, 2000)

        /**
         * 墙钟预算随步数线性放大（45s/步，下限 900s）：不放大时内核 15 分钟默认墙钟
         * 会在大步数设置下先到限，「步数上限」形同虚设。仅产品 UI 路径显式下发；
         * 内核默认 900s 不动（实验线/对拍口径零影响）。
         */
        fun wallClockS(steps: Int): Long = maxOf(900L, steps * 45L)
    }

    private lateinit var page: ScrollView
    val view: View
        get() {
            if (!::page.isInitialized) { page = build(); loadCurrent() }
            return page
        }

    /** 回前台重读偏好回显（角色卡文案/默认模式/门控级别可能已被其他页改动）。 */
    fun onResume() {
        if (!::page.isInitialized) return
        val role = com.hachimi.app.RoleStore.read(act)
        roleNameLabel?.text = role.roleName
        roleDescLabel?.text = "角色卡 · 称呼你为「${role.userName}」"
        modeSeg?.select(act.getSharedPreferences(TasksPage.PREFS, 0).getInt("run_mode", 0))
        gateSeg?.select(if (gateLevel(act) == "relaxed") 1 else 0)
        injectSeg?.select(if (act.getSharedPreferences(TasksPage.PREFS, 0)
                .getBoolean("knowledge_enabled", true)) 1 else 0)
    }

    // ---------------- 构建 ----------------

    private fun build(): ScrollView {
        val pad = Ds.dp(act, 20)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.setPadding(pad, Ds.dp(act, 12), pad, Ds.dp(act, 20))

        box.addView(Ds.appBar(act, "设置", listOf()))

        // —— ⓪ 角色（置顶入口）——
        val roleCard = Ds.card(act)
        val role = com.hachimi.app.RoleStore.read(act)
        val roleRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                act.startActivity(Intent(act, com.hachimi.app.RoleSettingsActivity::class.java))
            }
        }
        roleRow.addView(android.widget.ImageView(act).apply {
            setImageBitmap(com.hachimi.app.ui.FishAssets.circle(
                act, com.hachimi.app.ui.FishAssets.AVATAR, 36))
        }, LinearLayout.LayoutParams(Ds.dp(act, 36), Ds.dp(act, 36)))
        val roleCol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ds.dp(act, 12)
            setPadding(p, 0, 0, 0)
        }
        roleNameLabel = TextView(act).apply {
            text = role.roleName; textSize = 14f; setTextColor(Ds.TEXT)
            typeface = Typeface.DEFAULT_BOLD
        }
        roleCol.addView(roleNameLabel)
        roleDescLabel = Ds.small(act, "角色卡 · 称呼你为「${role.userName}」")
        roleCol.addView(roleDescLabel)
        roleRow.addView(roleCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        roleRow.addView(TextView(act).apply {
            text = "›"; textSize = 16f; setTextColor(Ds.TEXT_3)
        })
        roleCard.addView(roleRow)
        box.addView(roleCard)

        // —— ① 通道（BYOK：单端点 + 视觉开关）——
        box.addView(sectionTitle("通道（BYOK）", topPad = 14))
        val ch = Ds.card(act)
        ch.addView(sectionLabel("模型端点"))
        planUrl = field(ch, "https://api.example.com/v1")
        planKey = field(ch, "sk-…")
        planModel = field(ch, "例如 deepseek-v4-flash-vision")
        ch.addView(divider(ch))
        ch.addView(sectionLabel("启用视觉（与主模型共用端点）"))
        visSeg = Ds.seg(act, listOf("关", "开"), 0) { visSeg?.select(it) }
        ch.addView(visSeg!!.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        ch.addView(Ds.small(act, "开 = 视觉观察走主端点（模型需支持图片输入）；关 = 纯无障碍树。").apply {
            setPadding(0, Ds.dp(act, 4), 0, 0)
        })
        // 步数上限（2026-09-30 用户反馈：默认写死的 24 改为可配置）
        ch.addView(divider(ch))
        ch.addView(sectionLabel("步数上限（每任务）"))
        maxStepsField = field(ch, "默认 24 · 可填 4–2000").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        ch.addView(Ds.small(act, "单次任务最多执行的工具步数；复杂任务（多层弹窗/多输入）"
                + "可调大，超出即按「未完成」终止。").apply {
            setPadding(0, Ds.dp(act, 6), 0, 0)
        })
        ch.addView(Ds.small(act,
            "Key 仅存本机 Keystore，不落库 · HTTPS 直连 · 零自有服务器。\n" +
                    "视觉端点留空时，观察兜底自动关闭（行为退回纯无障碍树）。").apply {
            setPadding(0, Ds.dp(act, 6), 0, 0)
        })
        ch.addView(buttonRow(listOf(
            Ds.button(act, "保存", primary = false) { save() },
            Ds.button(act, "测试连通") { testBrain() })))
        ch.addView(Ds.button(act, "测试视觉（发一张纯色图问主色）", primary = false) { testVision() },
            Ds.vp(top = Ds.dp(act, 8)))
        resultView = TextView(act).apply { textSize = 13f; setPadding(0, Ds.dp(act, 8), 0, 0) }
        ch.addView(resultView)
        box.addView(ch)

        // —— ② 执行 ——
        box.addView(sectionTitle("执行", topPad = 14))
        val ex = Ds.card(act)
        ex.addView(sectionLabel("默认模式（分段与主界面同步）"))
        modeSeg = Ds.seg(act, listOf("前台引导", "分屏"),
            act.getSharedPreferences(TasksPage.PREFS, 0).getInt("run_mode", 0)) {
            act.getSharedPreferences(TasksPage.PREFS, 0).edit().putInt("run_mode", it).apply()
        }.also { ex.addView(it.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        ex.addView(sectionLabel("授权门控级别"))
        gateSeg = Ds.seg(act, listOf("标准（输入+跳转确认）", "宽松（仅输入确认）"),
            if (gateLevel(act) == "relaxed") 1 else 0) { i ->
            act.getSharedPreferences(TasksPage.PREFS, 0).edit()
                .putString("gate_level", if (i == 1) "relaxed" else "standard").apply()
        }.also { ex.addView(it.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        box.addView(ex)

        // —— ③ 知识（K7 接真：弱注入开关 + Curator 说明） ——
        box.addView(sectionTitle("知识", topPad = 14))
        val kn = Ds.card(act)
        kn.addView(sectionLabel("弱注入（技能/记忆摘要进下次任务）"))
        injectSeg = Ds.seg(act, listOf("关", "开"),
            if (act.getSharedPreferences(TasksPage.PREFS, 0)
                    .getBoolean("knowledge_enabled", true)) 1 else 0) { i ->
            injectSeg?.select(i)
            val on = i == 1
            act.getSharedPreferences(TasksPage.PREFS, 0).edit()
                .putBoolean("knowledge_enabled", on).apply()
            // 同步 kernel（K7a 开关；kernel 侧默认开，双向保持一致）
            Thread {
                try {
                    KernelHostService.pyBridgeModule?.callAttr("set_knowledge_enabled", on)
                } catch (_: Exception) {}
            }.start()
        }
        kn.addView(injectSeg!!.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        kn.addView(divider(kn))
        kn.addView(prefRow("Curator 蒸馏", "任务完成后自动蒸馏技能/记忆（无后台定时器）", null))
        kn.addView(Ds.small(act, "弱注入内容携带弱化提示（可能过时，以实际观测为准）；产物在「知识」Tab 查看。").apply {
            setPadding(0, Ds.dp(act, 6), 0, 0); setTextColor(Ds.TEXT_3)
        })
        box.addView(kn)

        // —— ④ 实验（版本号连点 5 次入口） ——
        box.addView(sectionTitle("实验", topPad = 14))
        val lab = Ds.card(act)
        lab.addView(prefRow("实验模式", "连点版本号 5 次开启（研究者入口）", null))
        lab.addView(TextView(act).apply {
            text = "Hachimi v0.1 · P1.5 UI 终态"
            textSize = 12f; setTextColor(Ds.TEXT_3); setPadding(0, Ds.dp(act, 8), 0, 0)
            setOnClickListener {
                if (++taps >= 5) {
                    taps = 0
                    act.startActivity(Intent(act, com.hachimi.app.ExperimentActivity::class.java))
                }
            }
        })
        box.addView(lab)
        return ScrollView(act).apply { addView(box) }
    }

    private fun sectionTitle(text: String, topPad: Int = 0): View =
        Ds.h2(act, text).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, Ds.dp(act, topPad), 0, Ds.dp(act, 4)) }
        }

    private fun sectionLabel(text: String): TextView = TextView(act).apply {
        setText(text); textSize = 12f; setTextColor(Ds.TEXT_2)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, Ds.dp(act, 4), 0, Ds.dp(act, 4))
    }

    private fun field(host: LinearLayout, hint: String): EditText =
        Ds.field(act, hint).apply {
            host.addView(this)
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, Ds.dp(act, 6))
        }

    private fun divider(host: LinearLayout): View = Ds.divider(act).apply {
        (layoutParams as LinearLayout.LayoutParams).setMargins(0, Ds.dp(act, 8), 0, Ds.dp(act, 4))
    }

    /** 设置行（mockup 设置组）：名称+说明，右侧状态 tag；enabled=false 时灰态。 */
    private fun prefRow(name: String, desc: String, enabled: Boolean?): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ds.dp(act, 6), 0, Ds.dp(act, 6))
        }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(act).apply {
            text = name; textSize = 14f
            setTextColor(if (enabled == false) Ds.TEXT_3 else Ds.TEXT)
        })
        col.addView(Ds.small(act, desc).apply { setPadding(0, Ds.dp(act, 1), 0, 0) })
        row.addView(col.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Ds.tag(act, when (enabled) { true -> "开"; false -> "关"; null -> "—" },
            if (enabled == true) Ds.GREEN_TXT else Ds.TEXT_3, Ds.GRAY_BG))
        return row
    }

    private fun buttonRow(buttons: List<View>): View {
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEachIndexed { i, b ->
            b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(Ds.dp(act, if (i == 0) 0 else 6), Ds.dp(act, 10), 0, 0) }
            r.addView(b)
        }
        return r
    }

    // ---------------- 配置读写与测试（P0 语义原样迁移） ----------------

    private fun loadCurrent() {
        maxStepsField.setText(maxSteps(act).toString())
        val cfg = BrainConfigStore.load(act) ?: return
        planUrl.setText(cfg.baseUrl)
        planKey.setText(cfg.apiKey)
        planModel.setText(cfg.model)
        visSeg?.select(if (cfg.visionEnabled) 1 else 0)
        show(true, "已加载本机保存的配置（${cfg.model} · " +
                if (cfg.visionEnabled) "视觉开）" else "视觉关）")
    }

    private fun collect(): BrainConfig? {
        val url = planUrl.text.toString().trim()
        val m = planModel.text.toString().trim()
        if (url.isEmpty() || m.isEmpty()) {
            show(false, "端点和模型名不能为空")
            return null
        }
        val key = planKey.text.toString().trim()
        // 简化形态（P4 用户定）：视觉开关 on = vision_* 继承主端点；off = 空（视觉关）
        return if (visSeg?.current == "开") {
            BrainConfig(url, key, m, visionBaseUrl = url, visionApiKey = key, visionModel = m)
        } else {
            BrainConfig(url, key, m)
        }
    }

    private fun save(): Boolean {
        val cfg = collect() ?: return false
        val ok = BrainConfigStore.save(act, cfg)
        if (ok) {
            val n = maxStepsField.text.toString().trim().toIntOrNull() ?: 24
            act.getSharedPreferences(TasksPage.PREFS, 0).edit()
                .putInt("max_steps", n.coerceIn(4, 2000)).apply()
        }
        show(ok, if (ok) {
            val v = if (cfg.visionEnabled) "视觉=${cfg.visionModel}" else "视觉=关"
            "已保存（Keystore 加密落盘，重启 App 后仍生效；$v；步数上限=${maxSteps(act)}）"
        } else "保存失败（Keystore 或文件写入异常），请重试")
        return ok
    }

    /** 测的是存的：先保存再测，避免「测通过但没保存」的错觉（P0 口径）。 */
    private fun testBrain() {
        if (!save()) return
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) { show(false, "内核未就绪（首次启动约需 10 秒），稍后再试。"); return }
        resultView.text = "测试中（最长 20 秒）…"
        val url = planUrl.text.toString().trim()
        Thread {
            try {
                val r = JSONObject(bridge.callAttr("test_brain_json", url,
                    planKey.text.toString().trim(), planModel.text.toString().trim()).toString())
                main.post {
                    if (r.optBoolean("ok")) {
                        show(true, "连通 ✓ ${r.optInt("latency_ms")}ms · 回复：" +
                                r.optString("reply").ifEmpty { "(空)" })
                    } else show(false, MainActivity.readableErrorStatic(r.optString("error")))
                }
            } catch (e: Exception) {
                main.post { show(false, "测试调用失败：${e.javaClass.simpleName}: ${e.message}") }
            }
        }.start()
    }

    private fun testVision() {
        val cfg = collect() ?: return
        if (!BrainConfigStore.save(act, cfg)) { show(false, "保存失败"); return }
        if (!cfg.visionEnabled) { show(false, "视觉开关为关——先开启「启用视觉」再测"); return }
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) { show(false, "内核未就绪（首次启动约需 10 秒），稍后再试。"); return }
        resultView.text = "生成测试图并发送（最长 60 秒）…"
        val b64 = makeTestImageB64()
        Thread {
            try {
                val r = JSONObject(bridge.callAttr("test_vision_json", cfg.visionBaseUrl,
                    cfg.visionApiKey, cfg.visionModel, b64).toString())
                main.post {
                    if (r.optBoolean("ok")) {
                        show(true, "视觉连通 ✓ ${r.optInt("latency_ms")}ms · 模型回答：" +
                                r.optString("text").take(60).ifEmpty { "(空)" } +
                                "（测试图是纯红色，回答含「红」即全链路正确）")
                    } else show(false, MainActivity.readableErrorStatic(r.optString("error")))
                }
            } catch (e: Exception) {
                main.post { show(false, "测试调用失败：${e.javaClass.simpleName}: ${e.message}") }
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
        resultView.setTextColor(if (ok) Ds.GREEN_TXT else Ds.RED_TXT)
    }
}
