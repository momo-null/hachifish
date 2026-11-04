package com.hachimi.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hachimi.app.MainActivity
import com.hachimi.app.OmniAgentRemote
import com.hachimi.app.Rlog
import com.hachimi.app.VoiceProviderStore
import com.hachimi.app.SettingsActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设置页（P4 起：角色分组置顶 + 模型配置简化）：
 * ⓪角色——大肥鱼×我（点击进 RoleSettingsActivity，人设/称呼/头像）；
 * ①通道 BYOK——单端点三栏 + 「启用视觉」开关（on=与主模型共用端点，2026-09-30
 *   用户定简化形态；存储 v4 零改动，vision_* 落盘继承主端点值）；
 * ②执行——默认模式 + 门控级别；③知识占位；④实验连点入口。
 */
class SettingsPage(private val act: Activity) {

    private val main = Handler(Looper.getMainLooper())

    // —— 模型目录（Phase 2 多端点）：数据面 = 环回内核 /api/runtime/models（Web 设置同源）。 ——
    /** 单个模型条目（vision = 模型原生视觉能力，内核能力块按它生成）。 */
    private class CatModel(val id: String, val vision: Boolean)
    /** 单个端点（api_key 永不明文回传：api_key_set 表示内核已存密钥）。 */
    private class CatProvider(val id: String, val label: String, val baseUrl: String,
                              val apiKeySet: Boolean, val models: List<CatModel>)
    private var catalog: List<CatProvider> = emptyList()
    private var catalogMain = ""        // 槽位 main 当前选择 "<provider_id>/<model_id>"
    private var providersHost: LinearLayout? = null
    private var modelTagRef: TextView? = null
    private var defaultTagRef: TextView? = null
    private lateinit var maxStepsField: EditText
    private lateinit var resultView: TextView
    private var roleNameLabel: TextView? = null
    private var roleDescLabel: TextView? = null
    private var gateSeg: Ds.Seg? = null
    private var injectSeg: Ds.Seg? = null
    // 运行权限行 tag 引用（onResume 从系统设置返回时刷新状态）
    private var a11yRowRef: TextView? = null
    private var projRowRef: TextView? = null
    // 授权门控 tag 引用（点击切换时实时更新文本）
    private var gateTagRef: TextView? = null
    private var fullAccessOn = false
    private var fullAccessTagRef: TextView? = null
    private var voiceTagRef: TextView? = null
    private var taps = 0

    // —— 通道卡折叠（P1.5 UI：默认折叠，点击标题展开/收起）——
    private var channelCard: View? = null
    private var channelCollapsed = true
    private val chArrow: TextView by lazy {
        TextView(act).apply {
            text = "▸"; textSize = 14f; setTextColor(Ds.TEXT_3)
            setPadding(Ds.dp(act, 6), 0, 0, 0)
        }
    }

    /** 授权门控级别（GateManager.enforce 读取）：standard=type_text+launch_app，relaxed=仅 type_text。 */
    companion object {
        fun gateLevel(c: Activity): String =
            c.getSharedPreferences(TasksPage.PREFS, 0).getString("gate_level", "standard") ?: "standard"

        /** 步数上限（每任务执行预算）：TasksPage / 详情页续话写入 chat body 的
         *  max_steps（悬浮球进度环分母）。默认 2000 = 内核 runtime.default_max_steps
         *  同值——24 是 mini_loop 时代口径，完整内核下会截断复杂任务（2026-11-04 修正）。 */
        fun maxSteps(c: Activity): Int =
            c.getSharedPreferences(TasksPage.PREFS, 0).getInt("max_steps", 2000).coerceIn(4, 2000)

        /** 无障碍服务是否已开启（GateService 类名命中即认为已启用）。 */
        fun a11yEnabled(c: Activity): Boolean = runCatching {
            android.provider.Settings.Secure.getString(c.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains("com.hachimi.app") ?: false
        }.getOrDefault(false)

        /** 屏幕录制（MediaProjection）是否正在运行（以 ProjectionHolder 真实状态为准）。 */
        fun projectionEnabled(c: Activity): Boolean =
            com.hachimi.app.ProjectionHolder.isRunning
    }

    private lateinit var page: ScrollView
    val view: View
        get() {
            if (!::page.isInitialized) { page = build(); loadCurrent() }
            return page
        }

    /** 回前台重读回显（角色卡文案/门控级别可能已被其他页改动；知识开关回读内核）。 */
    fun onResume() {
        if (!::page.isInitialized) return
        val role = com.hachimi.app.RoleStore.read(act)
        roleNameLabel?.text = role.roleName
        roleDescLabel?.text = "人设 · System Prompt · 称呼你为「${role.userName}」"
        gateSeg?.select(if (gateLevel(act) == "relaxed") 1 else 0)
        refreshKnowledgeSwitch()
        refreshPermissionTags()
        // 录屏授权走 startForegroundService 异步启动，onResume 时 virtualDisplay 可能还没建完；
        // 延迟两拍重刷，覆盖 ProjectionService onStartCommand 完成的时间窗。
        main.postDelayed({ refreshPermissionTags() }, 600)
        main.postDelayed({ refreshPermissionTags() }, 1500)
    }

    /** 知识开关回显（内核 /api/settings 真源；失败保持当前态）。 */
    private fun refreshKnowledgeSwitch() {
        Thread {
            val on = runCatching {
                OmniAgentRemote.getJson(ChatPage.OMNI_HOST_BASE, "", "/api/settings", 10000)
                    .optJSONObject("runtime")?.optJSONObject("knowledge")
                    ?.optJSONObject("memory")?.optBoolean("enabled", true)
            }.getOrNull() ?: return@Thread
            main.post { injectSeg?.select(if (on) 1 else 0) }
        }.start()
    }

    private fun refreshPermissionTags() {
        a11yRowRef?.let { t ->
            val ok = a11yEnabled(act)
            t.text = if (ok) "已开" else "未开"
            t.setTextColor(if (ok) Ds.GREEN_TXT else Ds.TEXT_3)
        }
        projRowRef?.let { t ->
            val ok = projectionEnabled(act)
            t.text = if (ok) "已授权" else "未授权"
            t.setTextColor(if (ok) Ds.GREEN_TXT else Ds.TEXT_3)
        }
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
        roleDescLabel = Ds.small(act, "人设 · System Prompt · 称呼你为「${role.userName}」")
        roleCol.addView(roleDescLabel)
        roleRow.addView(roleCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        roleRow.addView(TextView(act).apply {
            text = "›"; textSize = 16f; setTextColor(Ds.TEXT_3)
        })
        roleCard.addView(roleRow)
        box.addView(roleCard)

        // —— ① 模型（内核模型目录：多端点 + 默认模型 + 会话级选择；/api/runtime/models
        //      为端点唯一真源，Web 设置同源；BYOK 单端点表单随多端点退役）——
        box.addView(sectionTitle("模型", topPad = 14))
        val modelRow = Ds.card(act)
        val modelRowClick = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ds.dp(act, 2), 0, Ds.dp(act, 2))
            setOnClickListener {
                val expand = channelCollapsed
                toggleChannel()
                if (expand) loadCatalog()   // 展开 = 拉最新目录（密钥脱敏视图）
            }
        }
        modelRowClick.addView(TextView(act).apply {
            text = "🔑"; textSize = 16f
            layoutParams = LinearLayout.LayoutParams(Ds.dp(act, 30), Ds.dp(act, 30))
        })
        val modelRowCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        modelRowCol.addView(TextView(act).apply {
            text = "模型端点（多端点）"; textSize = 14f; setTextColor(Ds.TEXT)
            typeface = Typeface.DEFAULT_BOLD
        })
        modelRowCol.addView(Ds.small(act, "端点目录 · 默认模型（对齐 Web 设置）").apply {
            setPadding(0, Ds.dp(act, 1), 0, 0)
        })
        modelRowClick.addView(modelRowCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modelRowClick.addView(Ds.tag(act, "…", Ds.TEXT_3, Ds.GRAY_BG).also { modelTagRef = it })
        modelRowClick.addView(chArrow)
        modelRow.addView(modelRowClick)
        box.addView(modelRow)

        val ch = Ds.card(act)
        channelCard = ch
        ch.visibility = View.GONE   // 默认折叠
        ch.addView(sectionLabel("端点（点行编辑；内核按目录路由模型）"))
        providersHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        ch.addView(providersHost)
        ch.addView(Ds.button(act, "＋ 新增端点", primary = false) {
            providerEditDialog(null)
        }, Ds.vp(top = Ds.dp(act, 6)))
        ch.addView(divider(ch))
        val defaultRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ds.dp(act, 6), 0, Ds.dp(act, 6))
            setOnClickListener { defaultModelDialog() }
        }
        val defaultCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        defaultCol.addView(TextView(act).apply {
            text = "默认模型"; textSize = 14f; setTextColor(Ds.TEXT)
        })
        defaultCol.addView(Ds.small(act, "主模型槽位（main）；会话内可临时切换").apply {
            setPadding(0, Ds.dp(act, 1), 0, 0)
        })
        defaultRow.addView(defaultCol, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        defaultRow.addView(Ds.tag(act, "…", Ds.TEXT_3, Ds.GRAY_BG).also { defaultTagRef = it })
        ch.addView(defaultRow)
        // 步数上限（2026-09-30 用户反馈：默认写死的 24 改为可配置）
        ch.addView(divider(ch))
        ch.addView(sectionLabel("步数上限（每任务）"))
        maxStepsField = field(ch, "默认 2000 · 可填 4–2000").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        ch.addView(Ds.small(act, "单次任务最多执行的工具步数；复杂任务（多层弹窗/多输入）"
                + "可调大，超出即按「未完成」终止。").apply {
            setPadding(0, Ds.dp(act, 6), 0, 0)
        })
        ch.addView(buttonRow(listOf(
            Ds.button(act, "保存步数上限", primary = false) { saveSteps() },
            Ds.button(act, "检测目录") { loadCatalog() })))
        resultView = TextView(act).apply { textSize = 13f; setPadding(0, Ds.dp(act, 8), 0, 0) }
        ch.addView(resultView)
        box.addView(ch)

        // —— ② 行为（设计稿 V8：授权门控 + 任务通知 + 运行模式 + 运行权限）——
        // 通用设置行：左 label+desc，右 tag（可更新），整行可点击。返回 row + tagView。
        fun behaviorRow(label: String, desc: String, tagText: String, tagOk: Boolean,
                        onClick: () -> Unit): Pair<LinearLayout, TextView> {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ds.dp(act, 6), 0, Ds.dp(act, 6))
                setOnClickListener { onClick() }
            }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply { text = label; textSize = 14f; setTextColor(Ds.TEXT) })
            col.addView(Ds.small(act, desc).apply { setPadding(0, Ds.dp(act, 1), 0, 0) })
            row.addView(col.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val tag = Ds.tag(act, tagText,
                if (tagOk) Ds.GREEN_TXT else Ds.TEXT_3, Ds.GRAY_BG)
            row.addView(tag)
            return row to tag
        }

        box.addView(sectionTitle("行为", topPad = 14))
        val ex = Ds.card(act)
        // 授权门控：标准/宽松，点击切换并实时更新 tag
        val gatePair = behaviorRow("授权门控", "敏感操作前确认",
            if (gateLevel(act) == "relaxed") "宽松" else "标准", true) {
            val next = if (gateLevel(act) == "relaxed") "standard" else "relaxed"
            act.getSharedPreferences(TasksPage.PREFS, 0).edit()
                .putString("gate_level", next).apply()
            gateTagRef?.text = if (next == "relaxed") "宽松" else "标准"
        }
        ex.addView(gatePair.first)
        gateTagRef = gatePair.second
        // 完全访问（内核安全档位）：开 = full_access（危险动作免审批直接执行）；
        // 关 = standard（危险动作经 SSE approval 出弹窗，由 GateActivity 应答）。
        // 持久化在内核 /api/settings（跨重启生效），与本地「授权门控」档位独立。
        ex.addView(divider(ex))
        val faPair = behaviorRow("完全访问", "危险动作免审批直接执行（下一次任务生效）",
            if (fullAccessOn) "完全访问" else "标准", fullAccessOn) {
            val next = !fullAccessOn
            fullAccessOn = next
            fullAccessTagRef?.text = if (next) "完全访问" else "标准"
            fullAccessTagRef?.setTextColor(if (next) Ds.GREEN_TXT else Ds.TEXT_3)
            Thread {
                runCatching {
                    OmniAgentRemote.settingsPut(ChatPage.OMNI_HOST_BASE, "",
                        JSONObject().put("security", JSONObject()
                            .put("mode", if (next) "full_access" else "standard")))
                }.onFailure { Rlog.log("full-access put failed: ${it.message}") }
            }.start()
        }
        ex.addView(faPair.first)
        fullAccessTagRef = faPair.second
        Thread {
            val mode = runCatching {
                OmniAgentRemote.settingsGet(ChatPage.OMNI_HOST_BASE, "")
                    .optJSONObject("security")?.optString("mode")
            }.getOrNull()
            if (mode != null) main.post {
                fullAccessOn = mode == "full_access"
                fullAccessTagRef?.text = if (fullAccessOn) "完全访问" else "标准"
                fullAccessTagRef?.setTextColor(if (fullAccessOn) Ds.GREEN_TXT else Ds.TEXT_3)
            }
        }.start()
        // 运行模式（前台引导/分屏）与 任务通知 已随 mini_loop 退役删除（2026-11-04
        // 死配置清理：前者只剩提示文案差异，后者零消费方）。
        // 运行权限：无障碍/录屏状态，点击跳系统设置
        ex.addView(divider(ex))
        ex.addView(sectionLabel("运行权限"))
        val (a11yRow, a11yTag) = behaviorRow("无障碍服务", "操作手机的开关",
            if (a11yEnabled(act)) "已开" else "未开", a11yEnabled(act)) {
            act.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        ex.addView(a11yRow)
        val (projRow, projTag) = behaviorRow("屏幕录制（观察）", "看屏幕的权限",
            if (projectionEnabled(act)) "已授权" else "未授权", projectionEnabled(act)) {
            // 两种宿主都支持：MainActivity 内嵌 tab / SettingsActivity 独立入口
            (act as? MainActivity)?.requestProjection()
                ?: (act as? SettingsActivity)?.requestProjection()
        }
        ex.addView(projRow)
        box.addView(ex)
        // 记录权限行 tag 引用，onResume 刷新
        a11yRowRef = a11yTag
        projRowRef = projTag

        // —— ②.5 语音供应商（M2.5b：OpenAI 音频兼容通用接入，多槽位可切换） ——
        box.addView(sectionTitle("语音供应商", topPad = 14))
        val vc = Ds.card(act)
        // 2026-11-04 严格化：这里是语音端点的唯一真源，没配就在语音入口报错，
        // 不再悄悄回退端侧系统 ASR/TTS（"还能出声"会掩盖"其实没配"）。
        vc.addView(sectionLabel("🎤 对讲与「免提」的识别/播报来源（唯一）；未配置 = 语音直接报错"))
        val vpNow = VoiceProviderStore.active(act)
        val vpName = vpNow?.name?.takeIf { it.isNotBlank() } ?: "未配置"
        val vpPair = behaviorRow("当前供应商", "点击切换/新增（OpenAI 音频兼容）",
            vpName, true) {
            showVoiceProviderDialog()
        }
        vc.addView(vpPair.first)
        voiceTagRef = vpPair.second
        box.addView(vc)

        // —— ②.6 人设与 System Prompt 已整合进「角色卡」（RoleSettingsActivity，
        //      2026-11-04 用户定：人设配置单一入口，设置页不再重复） ——

        // —— ③ 关于（设计稿 V8：关于大肥鱼；弱注入/实验入口归此保功能） ——
        box.addView(sectionTitle("关于", topPad = 14))
        val about = Ds.card(act)
        about.addView(TextView(act).apply {
            text = "关于大肥鱼"; textSize = 14f; setTextColor(Ds.TEXT)
            typeface = Typeface.DEFAULT_BOLD
        })
        about.addView(Ds.small(act, "DeepSeek 鲸鱼娘萌化形象 · 全能手机小帮手").apply {
            setPadding(0, Ds.dp(act, 2), 0, 0)
        })
        // 引导页入口（V1「大肥鱼没吃饱」）：重置首次标记 + 打开引导页，随时可重看
        about.addView(TextView(act).apply {
            text = "查看引导页 ›"; textSize = 13f; setTextColor(Ds.PRIMARY)
            setPadding(0, Ds.dp(act, 10), 0, 0)
            setOnClickListener {
                act.getSharedPreferences(TasksPage.PREFS, 0).edit()
                    .putBoolean("onboarding_done", false).apply()
                act.startActivity(Intent(act, com.hachimi.app.OnboardingActivity::class.java))
            }
        })
        about.addView(divider(about))
        // 知识层总开关（真源 = 内核 /api/settings）：注入（memory/skills.enabled）+
        // 任务后蒸馏（skill.auto_distill）一起拨；关 = 零注入零沉淀。
        about.addView(sectionLabel("记忆与技能（内核知识层总开关）"))
        // 开关直写内核 /api/settings（2026-11-04 起 live）：memory.enabled + skills.enabled
        // + auto_distill 一起拨——原 prefs knowledge_enabled → bridge set_knowledge_enabled
        // 链路随 mini_loop 退役（bridge 侧 flag 已无任何门控点，纯死配置）。
        injectSeg = Ds.seg(act, listOf("关", "开"), 1) { i ->
            injectSeg?.select(i)
            val on = i == 1
            Thread {
                runCatching {
                    OmniAgentRemote.settingsPut(ChatPage.OMNI_HOST_BASE, "", org.json.JSONObject()
                        .put("runtime", org.json.JSONObject().put("knowledge", org.json.JSONObject()
                            .put("memory", org.json.JSONObject().put("enabled", on))
                            .put("skills", org.json.JSONObject().put("enabled", on))))
                        .put("skill", org.json.JSONObject().put("auto_distill", on)))
                }
            }.start()
        }
        about.addView(injectSeg!!.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        about.addView(prefRow("Curator 蒸馏", "任务完成后自动蒸馏技能/记忆（随上方开关联动；无后台定时器）", null))
        about.addView(TextView(act).apply {
            text = "实验模式 · Hachifish v0.1 · P1.5 UI 终态"
            textSize = 12f; setTextColor(Ds.TEXT_3); setPadding(0, Ds.dp(act, 10), 0, 0)
            setOnClickListener {
                if (++taps >= 5) {
                    taps = 0
                    act.startActivity(Intent(act, com.hachimi.app.ExperimentActivity::class.java))
                }
            }
        })
        box.addView(about)
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
        // 显式赋值 layoutParams：addView 前默认 LP 是 null，`as` 强转会 NPE。
        // 提前设好 LinearLayout.LayoutParams，addView 时会复用。
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ds.dp(act, 1)).apply {
            setMargins(0, Ds.dp(act, 8), 0, Ds.dp(act, 4))
        }
    }

    /** 切换通道卡折叠（默认收起；展开/收起更新箭头 ▸/▾）。 */
    private fun toggleChannel() {
        channelCollapsed = !channelCollapsed
        channelCard?.visibility = if (channelCollapsed) View.GONE else View.VISIBLE
        chArrow.text = if (channelCollapsed) "▸" else "▾"
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

    // ---------------- 模型目录读写（Phase 2 多端点；/api/runtime/models） ----------------

    private fun loadCurrent() {
        maxStepsField.setText(maxSteps(act).toString())
        show(true, "端点目录由内核保存（跨重启生效）；展开即读取最新。")
    }

    /** 读内核目录（GET，密钥脱敏）→ 渲染端点行 + 默认模型 tag。 */
    private fun loadCatalog() {
        show(true, "读取模型目录…")
        Thread {
            val r = runCatching {
                OmniAgentRemote.getJson(ChatPage.OMNI_HOST_BASE, "", "/api/runtime/models", 12000)
            }.getOrElse { JSONObject() }
            val arr = r.optJSONArray("providers")
            catalog = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val p = arr!!.optJSONObject(i) ?: return@mapNotNull null
                val ms = p.optJSONArray("models")
                CatProvider(
                    id = p.optString("id"), label = p.optString("label").ifEmpty { p.optString("id") },
                    baseUrl = p.optString("base_url"), apiKeySet = p.optBoolean("api_key_set"),
                    models = (0 until (ms?.length() ?: 0)).mapNotNull { j ->
                        val m = ms!!.optJSONObject(j) ?: return@mapNotNull null
                        CatModel(m.optString("id"), m.optBoolean("vision"))
                    })
            }
            catalogMain = r.optJSONObject("current")?.optJSONObject("main")
                ?.optString("selection").orEmpty()
            main.post { renderCatalog() }
        }.start()
    }

    private fun renderCatalog() {
        val host = providersHost ?: return
        host.removeAllViews()
        if (catalog.isEmpty()) {
            host.addView(Ds.small(act, "还没有端点——「＋ 新增端点」填 base_url / api_key / 模型；" +
                    "这是 agent 的模型真源（对齐 Web 设置）。"))
        }
        catalog.forEach { p ->
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ds.dp(act, 6), 0, Ds.dp(act, 6))
                setOnClickListener { providerEditDialog(p) }
            }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply {
                text = (if (catalogMain.startsWith("${p.id}/")) "● " else "○ ") +
                        p.label + "（" + p.id + "）"
                textSize = 14f
                setTextColor(if (catalogMain.startsWith("${p.id}/")) Ds.PRIMARY else Ds.TEXT)
                typeface = Typeface.DEFAULT_BOLD
            })
            val modelNames = p.models.joinToString(" / ") { m ->
                m.id + if (m.vision) "👁" else "" }
            col.addView(Ds.small(act, p.baseUrl + (if (modelNames.isNotEmpty()) " · $modelNames" else ""))
                .apply { setPadding(0, Ds.dp(act, 1), 0, 0) })
            row.addView(col, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(act).apply {
                text = "改"; textSize = 13f; setTextColor(Ds.PRIMARY)
                setPadding(Ds.dp(act, 10), 0, Ds.dp(act, 10), 0)
                setOnClickListener { providerEditDialog(p) }
            })
            row.addView(TextView(act).apply {
                text = "删"; textSize = 13f; setTextColor(Ds.RED_TXT)
                setOnClickListener {
                    android.app.AlertDialog.Builder(act)
                        .setTitle("删除端点「${p.label}」？")
                        .setPositiveButton("删除") { _, _ -> removeProvider(p.id) }
                        .setNegativeButton("取消", null).show()
                }
            })
            host.addView(row)
        }
        modelTagRef?.text = if (catalog.isEmpty()) "未配置" else "${catalog.size} 端点"
        modelTagRef?.setTextColor(if (catalog.isEmpty()) Ds.TEXT_3 else Ds.GREEN_TXT)
        defaultTagRef?.text = catalogMain.ifEmpty { "跟随配置" }
        show(true, "目录已读取（${catalog.size} 个端点）")
    }

    /** 整体写回（PUT providers：api_key 传空 = 保持内核已存密钥不变）。 */
    private fun putCatalog(providersObj: JSONObject, msg: String) {
        Thread {
            val r = runCatching {
                OmniAgentRemote.putJson(ChatPage.OMNI_HOST_BASE, "", "/api/runtime/models",
                    JSONObject().put("providers", providersObj))
            }.getOrElse { JSONObject().put("ok", false).put("error", "${it.message}") }
            main.post {
                if (r.optBoolean("ok", false)) show(true, msg) else show(false, "保存失败：${r.optString("error")}")
                loadCatalog()
            }
        }.start()
    }

    /** 当前目录 → PUT 用的 providers 对象（apiKeySet 的端点一律传空 = 保持）。 */
    private fun providersPayload(): JSONObject {
        val obj = JSONObject()
        catalog.forEach { p ->
            obj.put(p.id, JSONObject()
                .put("label", p.label)
                .put("base_url", p.baseUrl)
                .put("api_key", "")
                .put("proxy", "")
                .put("models", JSONArray().apply {
                    p.models.forEach { m ->
                        put(JSONObject().put("id", m.id).put("label", m.id).put("vision", m.vision))
                    }
                }))
        }
        return obj
    }

    private fun removeProvider(pid: String) {
        catalog = catalog.filter { it.id != pid }
        putCatalog(providersPayload(), "端点已删除")
        // 默认选择落在被删端点上 → 清槽位（PATCH selection 缺省 = 跟随配置）
        if (catalogMain.startsWith("$pid/")) {
            Thread {
                runCatching {
                    OmniAgentRemote.patchJson(ChatPage.OMNI_HOST_BASE, "",
                        "/api/runtime/models/default",
                        JSONObject().put("slot", "main"))
                }
            }.start()
        }
    }

    /** 新增/编辑端点弹窗（id/label/base_url/api_key + 模型行：id + 视觉开关）。 */
    private fun providerEditDialog(p: CatProvider?) {
        fun input(hint: String, value: String, num: Boolean = false): android.widget.EditText =
            android.widget.EditText(act).apply {
                this.hint = hint; setText(value); textSize = 13f; setSingleLine(true)
                if (num) inputType = android.text.InputType.TYPE_CLASS_TEXT
            }
        val isNew = p == null
        val idF = input("id（唯一，如 gitclaw）", p?.id ?: "").apply { isEnabled = isNew }
        val labelF = input("名称（如 Gitclaw）", p?.label ?: "")
        val urlF = input("base_url（https://…/v1）", p?.baseUrl ?: "")
        val keyF = input(if (p?.apiKeySet == true) "api_key（已保存·留空=不变）" else "api_key", "")
            .apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        // 模型动态行：id 输入 + 👁 视觉开关 + 删
        val modelsHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val modelIds = mutableListOf<Pair<android.widget.EditText, TextView>>()
        fun addModelRow(id: String, vision: Boolean) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            val et = input("模型 id", id).apply {
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val vis = TextView(act).apply {
                text = "👁"; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(if (vision) Ds.PRIMARY else Ds.TEXT_3)
                setPadding(Ds.dp(act, 10), 0, Ds.dp(act, 10), 0)
                setOnClickListener {
                    val next = currentTextColor != Ds.PRIMARY
                    setTextColor(if (next) Ds.PRIMARY else Ds.TEXT_3)
                }
            }
            row.addView(et); row.addView(vis)
            modelIds.add(et to vis)
            modelsHost.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ds.dp(act, 4) })
        }
        p?.models?.forEach { addModelRow(it.id, it.vision) }
        if (p?.models?.isEmpty() != false) addModelRow("", false)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(idF); box.addView(labelF); box.addView(urlF); box.addView(keyF)
        box.addView(Ds.small(act, "模型（点 👁 标记原生视觉）：").apply {
            setPadding(0, Ds.dp(act, 8), 0, Ds.dp(act, 2))
        })
        box.addView(modelsHost)
        box.addView(Ds.button(act, "＋ 模型", primary = false) { addModelRow("", false) })
        android.app.AlertDialog.Builder(act)
            .setTitle(if (isNew) "新增端点" else "编辑端点 · ${p?.label}")
            .setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val pid = idF.text.toString().trim()
                val baseUrl = urlF.text.toString().trim()
                if (pid.isEmpty() || baseUrl.isEmpty()) {
                    show(false, "id 与 base_url 不能为空"); return@setPositiveButton
                }
                val models = modelIds.mapNotNull { (et, vis) ->
                    val mid = et.text.toString().trim()
                    if (mid.isEmpty()) null else CatModel(mid, vis.currentTextColor == Ds.PRIMARY)
                }
                if (models.isEmpty()) { show(false, "至少填一个模型 id"); return@setPositiveButton }
                val key = keyF.text.toString().trim()
                val replaced = catalog.filter { it.id != pid }
                val np = CatProvider(pid, labelF.text.toString().trim().ifEmpty { pid },
                    baseUrl, key.isEmpty() && (p?.apiKeySet == true), models)
                catalog = (replaced + np).sortedBy { it.id }
                // PUT 整体写回：新端点带明文 key（可空），其余端点 key 传空 = 保持
                val obj = JSONObject()
                catalog.forEach { c ->
                    obj.put(c.id, JSONObject()
                        .put("label", c.label).put("base_url", c.baseUrl)
                        .put("api_key", if (c.id == pid && key.isNotEmpty()) key else "")
                        .put("proxy", "")
                        .put("models", JSONArray().apply {
                            c.models.forEach { m ->
                                put(JSONObject().put("id", m.id)
                                    .put("label", m.id).put("vision", m.vision))
                            }
                        }))
                }
                putCatalog(obj, "端点已保存（${np.label}）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 默认模型弹窗：目录内全部 <provider>/<model>，点选即 PATCH 槽位 main。 */
    private fun defaultModelDialog() {
        val entries = catalog.flatMap { p -> p.models.map { m -> "${p.id}/${m.id}" } }
        if (entries.isEmpty()) { show(false, "先添加端点和模型"); return }
        val names = entries.toTypedArray()
        val checked = entries.indexOf(catalogMain)
        android.app.AlertDialog.Builder(act)
            .setTitle("默认模型（主模型槽位）")
            .setSingleChoiceItems(names, checked) { d, which ->
                val sel = entries[which]
                d.dismiss()
                Thread {
                    val r = runCatching {
                        OmniAgentRemote.patchJson(ChatPage.OMNI_HOST_BASE, "",
                            "/api/runtime/models/default",
                            JSONObject().put("slot", "main").put("selection", sel))
                    }.getOrElse { JSONObject().put("ok", false).put("error", "${it.message}") }
                    main.post {
                        if (r.optBoolean("ok", false)) show(true, "默认模型 = $sel")
                        else show(false, "设置失败：${r.optString("error")}")
                        loadCatalog()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveSteps(): Boolean {
        val n = maxStepsField.text.toString().trim().toIntOrNull() ?: 24
        act.getSharedPreferences(TasksPage.PREFS, 0).edit()
            .putInt("max_steps", n.coerceIn(4, 2000)).apply()
        show(true, "已保存 · 步数上限=${maxSteps(act)}")
        return true
    }

    /** 语音供应商管理弹窗（M2.5b）：多槽位（点行启用）+ 增删改，OpenAI 音频兼容。 */
    private fun showVoiceProviderDialog() {
        val list = VoiceProviderStore.list(act).toMutableList()
        val active = runCatching {
            act.getSharedPreferences("hachimi_ui", 0).getInt("voice_active", 0)
        }.getOrDefault(0)
        val host = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        fun rebuild() {
            host.removeAllViews()
            if (list.isEmpty()) {
                host.addView(Ds.small(act, "尚未配置——🎤 对讲与「免提」会在入口直接报错。" +
                        "两种形态都要识别+播报（stt_model/tts_model）：打字轮不播报，不配也能用。"))
            }
            list.forEachIndexed { idx, p ->
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, Ds.dp(act, 6), 0, Ds.dp(act, 6))
                    setOnClickListener {
                        VoiceProviderStore.save(act, list, idx)
                        voiceTagRef?.text = p.name.ifBlank { "未命名" }
                        rebuild()
                    }
                }
                val mark = if (idx == active) "● " else "○ "
                val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(act).apply {
                    text = mark + p.name.ifBlank { "未命名" }
                    textSize = 14f; setTextColor(if (idx == active) Ds.PRIMARY else Ds.TEXT)
                    typeface = Typeface.DEFAULT_BOLD
                })
                col.addView(Ds.small(act, p.baseUrl).apply { setPadding(0, Ds.dp(act, 1), 0, 0) })
                row.addView(col.apply {
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val edit = TextView(act).apply {
                    text = "改"; textSize = 13f; setTextColor(Ds.PRIMARY)
                    setPadding(Ds.dp(act, 10), 0, Ds.dp(act, 10), 0)
                    setOnClickListener { providerEditDialog(list, idx) { rebuild() } }
                }
                row.addView(edit)
                val del = TextView(act).apply {
                    text = "删"; textSize = 13f; setTextColor(Ds.RED_TXT)
                    setOnClickListener {
                        list.removeAt(idx)
                        VoiceProviderStore.save(act, list, 0)
                        voiceTagRef?.text = VoiceProviderStore.active(act)?.name ?: "未配置"
                        rebuild()
                    }
                }
                row.addView(del)
                host.addView(row)
            }
            host.addView(Ds.button(act, "＋ 新增供应商", primary = false) {
                list.add(VoiceProviderStore.Provider("", "", "", "whisper-1", "tts-1", ""))
                providerEditDialog(list, list.size - 1) { rebuild() }
            })
        }
        rebuild()
        android.app.AlertDialog.Builder(act)
            .setTitle("语音供应商（点行启用）")
            .setView(android.widget.ScrollView(act).apply { addView(host) })
            .setPositiveButton("完成", null)
            .show()
    }

    /** 单个供应商字段编辑（name/base_url/api_key/stt_model/tts_model/voice）。 */
    private fun providerEditDialog(list: MutableList<VoiceProviderStore.Provider>,
                                   idx: Int, after: () -> Unit) {
        val p = list[idx]
        fun input(hint: String, value: String): android.widget.EditText =
            android.widget.EditText(act).apply {
                this.hint = hint; setText(value); textSize = 13f; setSingleLine(true)
            }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val fields = listOf(
            input("名称（如：云TTS / 我的引擎）", p.name),
            input("base_url（…/v1）", p.baseUrl),
            input("api_key（可空）", p.apiKey),
            input("STT 模型（如 whisper-1）", p.sttModel),
            input("TTS 模型（如 tts-1）", p.ttsModel),
            input("音色（如 alloy，可空）", p.voice))
        fields.forEach {
            box.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ds.dp(act, 6) })
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("语音供应商")
            .setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                list[idx] = VoiceProviderStore.Provider(
                    fields[0].text.toString().trim(), fields[1].text.toString().trim(),
                    fields[2].text.toString().trim(), fields[3].text.toString().trim(),
                    fields[4].text.toString().trim(), fields[5].text.toString().trim())
                VoiceProviderStore.save(act, list, idx)
                voiceTagRef?.text = list[idx].name.ifBlank { "未命名" }
                after()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun show(ok: Boolean, msg: String) {
        resultView.text = msg
        resultView.setTextColor(if (ok) Ds.GREEN_TXT else Ds.RED_TXT)
    }
}
