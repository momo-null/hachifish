package com.hachimi.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hachimi.app.AudioPlayer
import com.hachimi.app.DuplexListener
import com.hachimi.app.EchoGuard
import com.hachimi.app.KernelHostService
import com.hachimi.app.MainActivity
import com.hachimi.app.NarrativePanel
import com.hachimi.app.Rlog
import com.hachimi.app.OmniAgentRemote
import com.hachimi.app.RemoteConfigStore
import com.hachimi.app.RoleStore
import com.hachimi.app.VoiceProviderStore
import com.hachimi.app.TtsResult
import com.hachimi.app.voiceProviderTts
import org.json.JSONObject

/**
 * 对话式主界面（redesign_plan A3 / P1 壳）：chat 为主助手形态。
 *
 * P1 语义：消息全部按任务派发（start_task_json 直调链路复刻自 TasksPage——
 * BYOK set_brain/set_vision + body 组装 + HOME 让位 + readableError）；
 * 事件流经 NarrativePanel 转发器（K4 第一步）驱动消息渲染：
 * start→思考中三点（K3a 前端状态机），step→tool 卡，finish/error→终态面板。
 * P2 接统一入口后：闲聊不弹浮窗、message 气泡、口播穿插自动就位（映射层已备）。
 *
 * 无真机环境：状态机与映射逻辑全部收敛在 ChatMapper（JVM 测），
 * 本类只做视图编排与 bridge IO。
 */
class ChatPage(private val act: Activity) : NarrativePanel.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val items = mutableListOf<ChatItem>()
    private var phase = ChatPhase.IDLE
    private var sending = false
    private var lastObjective = ""
    /** 当前会话 task_id（K1a：start 事件捕获；后续轮同会话续发，闲聊/任务同源）。 */
    private var currentTaskId: String? = null
    /** 本轮是否已让位到桌面（首个 tool 步才让位——闲聊不跳桌面，K4）。 */
    private var yieldedHome = false
    // ---- 远程内核（手机伴侣 C1-C3：Tier 2 遥控模式，doc/plans/omniagent_remote_client_plan.md）----
    /** 本轮实际生效的 Tier 配置（⏹ 停止按它寻址；remoteSend 进入时记）。 */
    @Volatile private var remoteActiveRc: RemoteConfigStore.RemoteConfig? = null
    /** 远程会话 task_id（与本地内核 currentTaskId 独立——两套 Tier 的会话不混流）。 */
    private var remoteTaskId: String? = null
    /** 远程轮运行中（SSE status.running=true）；false = 轮次边界。 */
    @Volatile private var remoteRunning = false
    /** 本轮是**用户按停止**停掉的（收尾按"已停止"说话，不当失败；每轮下发时清零）。 */
    @Volatile private var stopRequested = false
    /** 流式块 → 消息流下标（thinking/message 各一份）。
     *  内核 upsert 的是「截至当前的全文」，同块再来必须**覆盖**那张卡；旧实现
     *  对 message 做 `+= text` 叠加全文（越流越长且翻倍），2026-11-04 修正。 */
    private val remoteMsgBlocks = LinkedHashMap<String, Int>()
    private val remoteThinkBlocks = LinkedHashMap<String, Int>()
    /** 最后一个口播块（id + 下标）：chat 终态是同一句话的定稿时就原地覆盖，不另开气泡。 */
    private var remoteLastBlock = ""
    private var remoteLastIdx = -1
    /** 本远程轮是否出现过 tool 卡（轮末相位 DONE vs IDLE）。 */
    private var remoteHadTools = false
    /** 最终回复文本（最终 chat 事件；轮末完成/失败面板与结果气泡同源去重）。 */
    private var remoteFinalText = ""
    /** 内核最终 chat 事件 extra.meta 的 steps/success（轮末面板依据）。 */
    private var remoteMetaSteps = 0
    private var remoteMetaSuccess: Boolean? = null
    /** 本远程轮起始时钟（完成面板用时）。 */
    private var remoteT0 = 0L
    /** 本远程轮技能目录（debug skill_catalog → 知识注入卡展示）。 */
    private val remoteSkills = mutableListOf<String>()
    /** 已渲染的内核 toolcall 事件 id（断线重连会重放窗口事件，按 id 去重防双卡）。 */
    private val remoteToolIds = HashSet<String>()
    /** 本远程轮工具步计数（内核 toolcall 事件不带序号——悬浮球步数靠本地计数）。 */
    private var remoteStepN = 0
    /** 本远程轮步数预算（chat 下发的 max_steps；悬浮球进度环分母）。 */
    private var remoteMaxSteps = 0
    /** 播报代际（TTS 合成要几秒，打断了也要让旧合成结果作废：gen 不匹配即不播）。 */
    @Volatile private var speakGen = 0
    /** 本轮是否由语音发起（+ 菜单 · 通话）——**只有语音轮才播报**（2026-11-04）：
     *  打字轮是"我在看屏幕"，念出来是打扰；语音轮是"我在听你说话"，不念才是故障。 */
    @Volatile private var voiceTurn = false
    // ---- 通话（= 免提形态的入口）：连续收音自动发送、回复播报、可随时插话 ----
    private var handsFree = false
    /** 通话全双工引擎（连续收音 + VAD 分段 + 插话判定；[DuplexListener.fullDuplex] = 能不能边听边说）。 */
    private var duplex: DuplexListener? = null
    /** 通话连续失败计数（≥3 自动关，防坏配置无限循环 toast）。 */
    private var handsErrs = 0
    /** 收音代际（watchdog 与重启只作用于自己那一代）。 */
    @Volatile private var speechGen = 0
    // ---- 会话级模型选择（模型路由 "<provider_id>/<model_id>"；空 = 跟随目录默认）----
    private var chatModel: String
    /** 对话取向（与 PC 同一个开关，2026-11-04 手机端补齐）：
     *  task = 工作 lane（工具循环 + 深度思考卡，缺省）；chat = 对话纪律（只出结果）。 */
    private var chatMode: String
    /** 会话级取向记忆（同 PC `modeByTask`；进程内，切会话时跟随）。 */
    private val modeByTask = HashMap<String, String>()
    // V13 会话列表：面板视图 + 冷启动恢复标记
    private var sessionsPanel: LinearLayout? = null
    private var sessionsList: LinearLayout? = null
    private var restoreDone = false
    /** 冷启动恢复的内核就绪轮询次数（上限 30 次 ≈ 30s）。 */
    private var restoreRetries = 0

    private val prefs get() = act.getSharedPreferences(PREFS_NAME, 0)

    init {
        // ROM 会丢 app 的 logcat（Oplus 实测全档丢弃）——排障日志落文件（Rlog）
        Rlog.init(act.filesDir)
        chatModel = prefs.getString("chat_model", "") ?: ""
        // 取向缺省 task（与 PC 同口径：modeByTask[tid] ?? "task"）；用户选过就记住
        chatMode = prefs.getString("chat_mode", "task") ?: "task"
    }

    /** 角色配置：onResume 重读——角色设置页改完返回即生效（称呼/头像），不再要求重启。 */
    private var role = RoleStore.read(act)

    // ---------------- 视图 ----------------

    val view: LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ds.PAGE)
    }
    private val headerStatus: TextView
    private val headerName: TextView
    private val btnPlus: TextView
    private val btnSend: TextView
    /** 运行中的停止钮（2026-11-04：从头部 ⏹ 搬进输入行，与 发送 同位互换）。 */
    private val btnStop: TextView
    private val recycler: RecyclerView
    private val emptyBox: LinearLayout
    private val input: EditText
    private val adapter = ChatAdapter()

    init {
        // ---- 头部：大肥鱼头像 + 角色名 + 相位 + 控制钮 ----
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            val p = Ds.dp(act, 12)
            setPadding(p, p / 2, p / 2, p / 2)
        }
        val avatar = ImageView(act).apply {
            setImageBitmap(FishAssets.circle(act, FishAssets.AVATAR, 34))
        }
        header.addView(avatar, LinearLayout.LayoutParams(Ds.dp(act, 34), Ds.dp(act, 34)))
        val info = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val p = Ds.dp(act, 10)
            setPadding(p, 0, 0, 0)
        }
        headerName = TextView(act).apply {
            text = role.roleName
            textSize = 14f; setTextColor(Ds.TEXT); typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        headerStatus = TextView(act).apply {
            text = phase.label; textSize = 11f; setTextColor(Ds.TEXT_2)
        }
        info.addView(headerName); info.addView(headerStatus)
        header.addView(info)
        // ---- 头部只留一个入口：会话列表（2026-11-04 控件归位）----
        // 停止 → 输入行（运行中唯一有意义的动作就该跟发送同一个位置）；
        // 模型选择 / 远程内核 → 发送旁 + 菜单；⏸ 随 mini_loop 退役一并删除，
        // 远程轮没有"暂停"语义（只有停与不停）。
        header.addView(sessionListBtn { openSessions() })
        view.addView(header)

        // ---- 消息流 / 空状态 ----
        val body = FrameLayout(act)
        recycler = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act)
            adapter = this@ChatPage.adapter
        }
        body.addView(recycler, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        emptyBox = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ds.PAGE)
        }   // 空态/欢迎卡由 refresh() 动态填充（V2/V3 双形态）
        body.addView(emptyBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // V13 会话面板（body 内覆盖层；GONE 起）
        sessionsPanel = buildSessionsPanel()
        body.addView(sessionsPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 输入区 ----
        val inputArea = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            val p = Ds.dp(act, 10)
            setPadding(p, p / 2, p, p / 2)
        }
        input = EditText(act).apply {
            hint = "${role.userName}，说点什么…"
            textSize = 14f; setTextColor(Ds.TEXT); setHintTextColor(Ds.TEXT_3)
            background = Ds.round(act, 20, Ds.PAGE)
            maxLines = 4
            setPadding(Ds.dp(act, 14), Ds.dp(act, 10), Ds.dp(act, 14), Ds.dp(act, 10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnSend = TextView(act).apply {
            text = "↑"; textSize = 16f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ds.round(act, 18, Ds.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(Ds.dp(act, 36), Ds.dp(act, 36)).apply {
                leftMargin = Ds.dp(act, 8)
            }
            setOnClickListener { send() }
        }
        // ---- 语音供应商配置入口已收进 + 菜单（2026-11-04：对讲下线，通话是 + 里的一行，
        // 入口不再挂两个话筒按钮；配没配好由点通话时的前置检查如实回报）。
        btnPlus = TextView(act).apply {
            text = "+"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(Ds.TEXT_2)
            background = Ds.round(act, 18, Ds.GRAY_BG)
            layoutParams = LinearLayout.LayoutParams(Ds.dp(act, 36), Ds.dp(act, 36)).apply {
                leftMargin = Ds.dp(act, 8)
            }
            setOnClickListener { showPlusMenu(it) }
        }
        btnStop = TextView(act).apply {
            // ■（U+25A0，Unicode 1.1）而不是 ⏹（U+23F9，11.0 才有）：旧 ROM 字库没有后者
            text = "■"; textSize = 15f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ds.round(act, 18, Ds.RED_TXT)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(Ds.dp(act, 36), Ds.dp(act, 36)).apply {
                leftMargin = Ds.dp(act, 8)
            }
            setOnClickListener { stopRunningTurn() }
        }
        inputArea.addView(input); inputArea.addView(btnSend); inputArea.addView(btnStop)
        inputArea.addView(btnPlus)
        view.addView(inputArea)
        refresh()   // 首帧：空态（V2）或欢迎卡（V3，冷启动恢复后被 refresh 替换）
    }

    /**
     * 左上「两横」会话入口（替代 🗂 文件夹 emoji）：自绘两条圆头横杠——
     * 一是头部不再堆控件后只剩这一个动作，不必用图形隐喻解释它；二是 emoji
     * 字形按 ROM 字库碰运气（🧠 实测豆腐块），自绘不受字库摆布。
     */
    private fun sessionListBtn(onClick: () -> Unit): View = object : View(act) {
        private val bar = android.graphics.Paint().apply {
            isAntiAlias = true; color = Ds.TEXT_2
        }
        private val rect = android.graphics.RectF()

        init {
            background = Ds.round(act, 16, Ds.GRAY_BG)
            layoutParams = LinearLayout.LayoutParams(Ds.dp(act, 32), Ds.dp(act, 32)).apply {
                leftMargin = Ds.dp(act, 6)
            }
            contentDescription = "会话列表"
            setOnClickListener { onClick() }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val t = Ds.dp(act, 2).toFloat()          // 横杠粗细（圆头 = 半径 t/2）
            val cx = width / 2f
            // 上长下短两条：清单的最小 UI 语言
            listOf(height * 0.42f to Ds.dp(act, 14).toFloat(),
                   height * 0.58f to Ds.dp(act, 9).toFloat()).forEach { (y, len) ->
                rect.set(cx - len / 2, y - t / 2, cx + len / 2, y + t / 2)
                canvas.drawRoundRect(rect, t / 2, t / 2, bar)
            }
        }
    }

    /** 发送 ↔ 停止同位互换：轮次运行中输入框本身是禁用的，此时"发送"无意义，停到台面上。 */
    private fun setRunningControls(running: Boolean) {
        btnSend.visibility = if (running) View.GONE else View.VISIBLE
        btnStop.visibility = if (running) View.VISIBLE else View.GONE
    }

    /** 空闲态（V2）：吃白饭大肥鱼 + 主人称呼 + 建议 chips。 */
    private fun buildEmpty(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(Ds.PAGE)
        val p = Ds.dp(act, 24)
        setPadding(p, Ds.dp(act, 40), p, p)
    }.also { box ->
        box.addView(ImageView(act).apply {
            setImageResource(FishAssets.TASK)
            adjustViewBounds = true
            maxHeight = Ds.dp(act, 150)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        box.addView(TextView(act).apply {
            text = "今天还没有任务"
            textSize = 16f; setTextColor(Ds.TEXT); typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, Ds.dp(act, 12), 0, 0)
            gravity = Gravity.CENTER
        })
        box.addView(TextView(act).apply {
            text = "${role.userName}，给${role.roleName}派个活？"
            textSize = 13f; setTextColor(Ds.TEXT_2)
            setPadding(0, Ds.dp(act, 4), 0, Ds.dp(act, 16))
            gravity = Gravity.CENTER
        })
        // 建议 chips 按八原语真实能力筛选（诚实口径：不承诺不稳的链路）
        listOf(
            "打开便签，写一条「买牛奶」提醒",
            "打开时钟，定一个 7:30 的闹钟",
            "问问大肥鱼你能帮我做什么"
        ).forEach { s ->
            box.addView(TextView(act).apply {
                text = s; textSize = 13f; setTextColor(Ds.PRIMARY)
                gravity = Gravity.CENTER
                background = Ds.round(act, 12, Color.WHITE, 1, Ds.CARD_BORDER)
                val p = Ds.dp(act, 12)
                setPadding(p, Ds.dp(act, 10), p, Ds.dp(act, 10))
                setOnClickListener { input.setText(s); send() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ds.dp(act, 8)
            })
        }
    }

    // ---------------- 事件消费（K4 转发器） ----------------

    override fun onNarrative(type: String, fields: Map<String, Any?>) {
        if (act.isDestroyed) return   // Activity 已销毁：不再碰 UI（listener 常驻的代价防护）
        // K1a：start 事件捕获会话 task_id（后续轮同会话续发 + 冷启动恢复锚点）
        if (type == "start") {
            (fields["task_id"] as? String)?.takeIf { it.isNotBlank() }?.let {
                currentTaskId = it
                prefs.edit().putString("last_task_id", it).apply()
            }
        }
        // K4：首个 tool 步才让位到桌面 + 弹浮窗（闲聊全程留在对话页）
        if (type == "step" && !yieldedHome) {
            yieldedHome = true
            main.post {
                NarrativePanel.show(act)
                act.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
        // 结果气泡（完整 summary）与终态面板（摘要）是同一 finish 事件的两件产物，
        // 气泡在前、面板在后；两者同源不同文（digest 短文本返回空串，不重复）。
        val bubble = ChatMapper.finishBubble(type, fields)
        val item = ChatMapper.item(type, fields)
        val newPhase = ChatMapper.phase(type, fields)
        // 终态恢复输入（停止钮不按相位翻转——它跟的是"这一轮跑没跑"，唯一真源
        // remoteRunning：下发时置、内核轮次边界/失败收尾/停止看门狗清）
        if (newPhase != null) {
            phase = newPhase
            headerStatus.text = phase.label
            if (phase == ChatPhase.IDLE || phase == ChatPhase.DONE || phase == ChatPhase.FAILED) {
                sending = false
                input.isEnabled = true
            }
        }
        if (bubble != null) pushItem(bubble)
        if (item != null) pushItem(item)
        if (bubble != null || item != null) refresh()
    }

    /**
     * 消息流入列唯一入口（现场事件 + 会话回放共用，R10）：Thinking 占位替换与
     * 连续 observe 折叠两条降噪规则只有一份实现 —— 回放此前自己拼 items，
     * 规则分裂正是「重进后结果跑到卡片上面」的根源。
     */
    private fun pushItem(item: ChatItem) {
        // 首个实质事件替换思考占位（K3a 状态机）
        if (ChatMapper.replacesThinking(item) && items.lastOrNull() == ChatItem.Thinking) {
            items.removeAt(items.size - 1)
        }
        // 连续同类 observe 折叠（P1.5 UI）：末尾也是 observe ToolCard → count+1 不新增，
        // 降噪（「查看屏幕 ×N」），保留首次语义与 ok 态。
        val last = items.lastOrNull()
        if (item is ChatItem.ToolCard && item.tool.contains("observe") && last is ChatItem.ToolCard
            && last.tool.contains("observe") && last.count > 0) {
            items[items.size - 1] = last.copy(count = last.count + 1)
        } else {
            items.add(item)
        }
    }

    private fun refresh() {
        // V3 欢迎卡：有会话（恢复/切换过）且流为空 → 展示自我介绍卡而非空状态
        if (items.isEmpty() && currentTaskId != null) {
            emptyBox.removeAllViews()
            emptyBox.addView(buildWelcome())
        } else if (items.isEmpty()) {
            emptyBox.removeAllViews()
            emptyBox.addView(buildEmpty())
        }
        adapter.submit(items)
        emptyBox.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isNotEmpty()) recycler.scrollToPosition(items.size - 1)
    }

    /** V3 欢迎卡：大肥鱼已上线 + 能力 tags（会话存在但无消息时的首屏角色）。 */
    private fun buildWelcome(): View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(Ds.PAGE)
        val p = Ds.dp(act, 24)
        setPadding(p, Ds.dp(act, 60), p, p)
    }.also { box ->
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(act).apply {
            setImageBitmap(FishAssets.circle(act, FishAssets.AVATAR, 44))
        }, LinearLayout.LayoutParams(Ds.dp(act, 44), Ds.dp(act, 44)))
        val info = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p2 = Ds.dp(act, 12)
            setPadding(p2, 0, 0, 0)
        }
        info.addView(TextView(act).apply {
            text = "${role.roleName}已上线"
            textSize = 14f; setTextColor(Ds.TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        info.addView(TextView(act).apply {
            text = "你的全能小帮手"
            textSize = 11f; setTextColor(Ds.TEXT_2)
        })
        head.addView(info)
        box.addView(head)
        box.addView(TextView(act).apply {
            text = "${role.userName}好！我帮你点手机、管文件、发消息，说一句目标就行。"
            textSize = 13f; setTextColor(Ds.TEXT_2)
            setLineSpacing(Ds.dp(act, 2).toFloat(), 1f)
            setPadding(0, Ds.dp(act, 12), 0, Ds.dp(act, 10))
        })
        val tags = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("📱 App 操作", "⌨ 替你输入", "🔍 看屏幕找控件").forEachIndexed { i, t ->
            // 显式双参 addView：单参 addView + apply 改 layoutParams 会在 addView 前
            // 访问 null LP → `as LinearLayout.LayoutParams` NPE（真机崩溃根因）。
            tags.addView(Ds.tag(act, t, Ds.PRIMARY, Color.parseColor("#E8F1FA")),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) leftMargin = Ds.dp(act, 6)
                })
        }
        box.addView(tags)
    }

    // ---------------- 发送（P1：全部按任务派发，链路复刻 TasksPage） ----------------

    private fun send(text: String? = null) {
        val objective = (text ?: input.text.toString()).trim()
        if (objective.isEmpty()) return
        // 播报归属只认"这一轮是谁发起的"：通话发起才念回复，打字不念。
        voiceTurn = text != null
        // C2 barge-in：远程轮运行中允许说话打断（chat force=true）；本地内核轮维持独占
        if (sending && !remoteRunning) { toast("大肥鱼正在干活，稍等或先停止"); return }
        if (text == null) {
            // 打字路径：立即清输入 + 收键盘（prelude 的 typed 恒 false，输入框此前从不清空）
            input.setText("")
            input.clearFocus()
            (act.getSystemService(Activity.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
        }
        val remoteCfg = RemoteConfigStore.load(act)?.takeIf { it.ready && it.auto }
        // Tier 决策（Phase 2 收敛）：PC 可达走 Tier 2；否则**只走环回完整内核**。
        // mini_loop 兜底已退役——第二大脑意味着发送路径按探活运气分裂成两种行为
        // （2026-10-31 实录：内核重启窗口期静默掉进兜底，任务建了不跑，UI 永卡
        // 干活中）。内核没就绪就等它（进程重启后 Chaquopy 导入需 ~15s），不就绪
        // 超时才明确报错。
        Thread {
            if (remoteCfg != null && OmniAgentRemote.health(remoteCfg.baseUrl, remoteCfg.token)) {
                main.post { remoteSend(remoteCfg, objective, force = remoteRunning) }
                return@Thread
            }
            // 等本机内核就绪（最多 ~30s；就绪瞬间立即进入）
            main.post {
                phase = ChatPhase.THINKING
                headerStatus.text = "本机内核 · 启动中…"
                refresh()
            }
            var waited = 0
            while (!OmniAgentRemote.health(OMNI_HOST_BASE, "") && waited < 30) {
                Thread.sleep(1000); waited++
            }
            if (!OmniAgentRemote.health(OMNI_HOST_BASE, "")) {
                main.post { remoteFail("本机内核未就绪（等待 ${waited}s 超时）。请稍后重试；若持续失败到 设置 里重启 App。") }
                return@Thread
            }
            val rc = RemoteConfigStore.RemoteConfig(
                baseUrl = OMNI_HOST_BASE, token = "", auto = true)
            main.post { remoteSend(rc, objective, force = remoteRunning) }
        }.start()
    }

    /** 路径公共前置：用户气泡 + 思考占位 + 输入禁用（两条 Tier 共用；收键盘仅打字路径）。 */
    private fun prelude(objective: String, typed: Boolean) {
        com.hachimi.app.FinishPopup.dismiss()   // 新轮开始：上一单的完成弹窗不再挡道
        sending = true
        input.isEnabled = false
        if (typed) {
            input.setText("")
            input.clearFocus()
            (act.getSystemService(Activity.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
        }
        lastObjective = objective
        items.add(ChatItem.UserMsg(objective))
        items.add(ChatItem.Thinking)
        phase = ChatPhase.THINKING
        headerStatus.text = phase.label
        refresh()
    }

    // ---------------- 远程轮（Tier 1 环回 / Tier 2 遥控，同一 wire contract） ----------------

    /** 头部文案按 Tier 区分：环回=本机内核（"远程内核"字样曾误导用户以为连了 PC）。 */
    private fun tierLabel(rc: RemoteConfigStore.RemoteConfig) =
        if (rc.baseUrl == OMNI_HOST_BASE) "本机内核" else "PC 内核"

    /** Tier 2/1 共用：chat 下发 → SSE 订阅（断线重连）→ 气泡/播报/轮次收尾。 */
    private fun remoteSend(rc: RemoteConfigStore.RemoteConfig, objective: String, force: Boolean) {
        remoteActiveRc = rc
        prelude(objective, typed = false)
        remoteMsgBlocks.clear()
        remoteThinkBlocks.clear()
        remoteLastBlock = ""
        remoteLastIdx = -1
        remoteHadTools = false
        remoteFinalText = ""
        remoteMetaSteps = 0
        remoteMetaSuccess = null
        remoteSkills.clear()
        remoteToolIds.clear()
        remoteStepN = 0
        stopRequested = false
        // 取向 = 会话记忆优先（同 PC `modeByTask[currentTaskId] ?? "task"`）
        val mode = modeFor(remoteTaskId)
        // chat 取向是"只回话"：40 步预算轮被内核按 chat lane 处理，本地不再
        // 拿任务预算当分母（悬浮球进度环在对话轮没有意义）。
        remoteMaxSteps = if (mode == "chat") 0
            else runCatching { SettingsPage.maxSteps(act) }.getOrDefault(0)
        remoteT0 = System.currentTimeMillis()
        Rlog.log("send tier=${tierLabel(rc)} mode=$mode steps=$remoteMaxSteps model=${chatModel.ifBlank { "默认" }}")
        Thread {
            try {
                val tid = OmniAgentRemote.chat(rc.baseUrl, rc.token, objective, remoteTaskId,
                    force, remoteMaxSteps, chatModel.ifBlank { null }, mode)
                remoteTaskId = tid
                modeByTask[tid] = mode
                remoteRunning = true
                // 执行链浮窗接入（远程轮 K4 管线）：start 复位浮窗 + 门控会话随任务重置
                NarrativePanel.pushRemote(JSONObject()
                    .put("event", "start").put("run_id", tid)
                    .put("objective", objective).toString())
                main.post {
                    phase = ChatPhase.WORKING
                    headerStatus.text = tierLabel(rc) + " · 干活中"
                    setRunningControls(true)
                    refresh()
                }
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                // 断线重连（C3「下轮探活自愈」的真实现）：旁路订阅断开时服务端清游标，
                // 重连即从当前窗口重放（outbox 有界 300，丢最旧的，终态必然还在）——
                // 静默 EOF 不再让 UI 永卡干活中（2026-10-31 真机实录：TCP 连接消失、
                // stream 无异常返回、remoteSend 无收尾）。
                var attempts = 0
                while (remoteRunning) {
                    attempts++
                    try {
                        OmniAgentRemote.stream(rc.baseUrl, rc.token, tid, "hachimi-phone") { ev ->
                            // 返回 true = 继续读（false = 停止）。**此返回值自 C1 起写反**
                            // （曾返回 done.get()：运行中=false 被流当停止指令 → 每条
                            // 连接只送 1 个事件即断，2026-10-31 文件日志实锤）。
                            // !remoteRunning 也算停止指令：/api/runtime/stream 是内核侧
                            // `while True` 的常驻流（不会自己关），用户停止后本地收口了
                            // 却不退读，就会留一条永远在收 status 的死连接。
                            if (done.get() || !remoteRunning) return@stream false
                            var end = false
                            main.post {
                                end = handleRemoteEvent(ev, rc)
                                if (end) done.set(true)
                            }
                            !done.get()
                        }
                    } catch (e: Exception) {
                        Rlog.log("stream attempt $attempts failed: ${e.message}")
                        if (attempts >= 5 || !remoteRunning) throw e
                        Thread.sleep(600L * attempts)
                    }
                }
            } catch (e: Exception) {
                main.post { remoteFail(e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    /**
     * 远程 SSE 事件 → UI（主线程；返回 true = 本轮结束，调用方停止读流）。
     * 事件映射与本地 Tier 复用同一 ChatMapper（R10：渲染规则只有一份实现）。
     */
    private fun handleRemoteEvent(ev: OmniAgentRemote.SseEvent, rc: RemoteConfigStore.RemoteConfig): Boolean {
        if (act.isDestroyed) return true
        // 诊断位（ROM 丢 logcat，落文件）：事件到达流水（debug 事件只计名不落正文）
        Rlog.log("ev=${ev.event}" + if (ev.event == "debug") "" else " keys=${ev.data.keys().asSequence().toList()}")
        when (ev.event) {
            "status" -> if (!ev.data.optBoolean("running", true) && remoteRunning) {
                // 轮次收尾：与本地 finish 同款终态——内核回报失败（extra.meta.success
                // =false）出失败面板；任务轮出完成面板（digest 与结果气泡去重）；
                // 纯闲聊只回待命。
                remoteRunning = false
                sending = false
                input.isEnabled = true
                setRunningControls(false)
                collapseThinking()   // 轮次结束：还在"思考中"的卡一律收起
                if (items.lastOrNull() == ChatItem.Thinking) items.removeAt(items.size - 1)
                Rlog.log("terminal: hadTools=$remoteHadTools metaSuccess=$remoteMetaSuccess metaSteps=$remoteMetaSteps")
                if (stopRequested) {
                    // 用户按了停止：内核回报 success=false 的语义是"被我停的"，不是"它坏了"。
                    // 走失败出口会出 重试/手动接管 面板 + 「遇到点麻烦…」+ 失败弹窗——
                    // 把用户自己的动作说成故障。
                    stopRequested = false
                    NarrativePanel.pushRemote(JSONObject()
                        .put("event", "finish").put("status", "chat")
                        .put("steps", remoteStepN).put("summary", remoteFinalText).toString())
                    phase = ChatPhase.IDLE
                    headerStatus.text = "已停止"
                    refresh()
                    return true
                }
                if (remoteMetaSuccess == false) {
                    // 失败收尾：浮窗错误行由 remoteFail 统一发（唯一失败出口）
                    remoteFail(remoteFinalText.ifBlank { "远程轮未完成（内核未回报原因）" })
                    // 完成弹窗（失败态）：内核判定的失败才弹——网络断流不算（任务可能仍在跑）
                    com.hachimi.app.FinishPopup.show(act, false,
                        remoteFinalText.ifBlank { "远程轮未完成（内核未回报原因）" }, remoteMetaSteps)
                    return true
                }
                // 执行链浮窗终态（done=任务轮收尾隐藏；chat=闲聊轮）
                NarrativePanel.pushRemote(JSONObject()
                    .put("event", "finish")
                    .put("status", if (remoteHadTools) "done" else "chat")
                    .put("steps", remoteMetaSteps)
                    .put("summary", remoteFinalText).toString())
                if (remoteHadTools) {
                    ChatMapper.item("finish", mapOf<String, Any?>(
                        "status" to "done", "summary" to remoteFinalText,
                        "steps" to remoteMetaSteps,
                        "wall_clock_s" to (System.currentTimeMillis() - remoteT0) / 1000.0
                    ))?.let { pushItem(it) }
                    // 完成弹窗（成功态）：后台执行时立刻可见结果 + 一键返回
                    com.hachimi.app.FinishPopup.show(act, true, remoteFinalText,
                        remoteMetaSteps, (System.currentTimeMillis() - remoteT0) / 1000.0)
                }
                phase = if (remoteHadTools) ChatPhase.DONE else ChatPhase.IDLE
                headerStatus.text = phase.label
                refresh()
                return true
            }
            "thinking" -> {
                // 深度思考（native 取向的推理链通道）：task 取向出卡，chat 取向丢弃
                val text = ev.data.optString("content")
                if (text.isNotBlank() && remoteRunning) upsertThinking(
                    ev.data.optString("id").ifEmpty { ev.data.optString("block_id") }.ifEmpty { "th-global" },
                    text, ev.data.optString("model"), true)
            }
            "message" -> {
                val raw = ev.data.optString("content").ifEmpty { ev.data.optString("text") }
                if (raw.isNotEmpty() && remoteRunning) {
                    // 流式块键：内核 push_message_stream 的字段是 id（block_id 是旧名）——
                    // 读错键会让跨工具的两段口播续写进同一气泡
                    val block = ev.data.optString("id").ifEmpty { ev.data.optString("block_id") }
                        .ifEmpty { "m-global" }
                    // think-tag 取向的口播块**原文里带着思考段**（上游只在回合终态剥）：
                    // 客户端同口径拆——正文进气泡，思考段进深度思考卡（task 取向）。
                    // 不拆就是 2026-11-04 用户看到的「思考在对话框里，收尾整块换成结果」。
                    val (think, visible) = ChatMapper.splitThinkTag(raw)
                    if (think.isNotEmpty()) upsertThinking("tag-$block", think,
                        ev.data.optString("model"), true)
                    if (visible.isNotBlank()) upsertMessage(block, visible,
                        ev.data.optString("role"))
                    // 浮窗口播行（K3：当前动作行优先显示口播）
                    NarrativePanel.pushRemote(JSONObject()
                        .put("event", "message").put("content", visible.ifEmpty { raw }).toString())
                    refresh()
                }
            }
            "toolcall" -> {
                val tool = ev.data.optString("name").ifEmpty { ev.data.optString("tool") }
                if (tool.isNotEmpty() && remoteRunning) {
                    // 断线重连会重放窗口事件：按内核事件 id 去重，防止双卡
                    val tcId = ev.data.optString("id")
                    if (tcId.isNotEmpty() && !remoteToolIds.add(tcId)) return false
                    if (items.lastOrNull() == ChatItem.Thinking) items.removeAt(items.size - 1)
                    // 阶段边界：这一步要开工了，上一段思考不再是"正在流式"→ 卡自动收起
                    collapseThinking()
                    remoteHadTools = true
                    remoteStepN++
                    // K4 同款：首个 tool 步让位桌面 + 弹执行链浮窗（远程轮同享；
                    // 闲聊全程留在对话页）
                    if (!yieldedHome) {
                        yieldedHome = true
                        NarrativePanel.show(act)
                        act.startActivity(Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                    // 内核 toolcall 是完成后整卡（name+arguments+result）：result 按工具
                    // 框架统一包裹 {"ok":bool,"error"?,"output"?} 推导三态——与本地
                    // step 事件的 ok/error 语义一致（绿勾/红叉+错误文案），不再永远
                    // 渲染成「进行中」。
                    val (ok, err) = parseToolResult(ev.data.optString("result"))
                    Rlog.log("toolcall #$remoteStepN tool=$tool ok=$ok err=${err?.take(60)}")
                    ChatMapper.item("step", mapOf<String, Any?>(
                        "tool" to tool, "ok" to ok, "error" to err, "step" to remoteStepN))
                        ?.let { pushItem(it) }
                    // 执行链浮窗步进行（步数=本地计数，喂悬浮球进度；tap_xy/gesture 带红框）
                    NarrativePanel.pushRemote(JSONObject()
                        .put("event", "step").put("tool", tool)
                        .put("step", remoteStepN)
                        .putOpt("total", if (remoteMaxSteps > 0) remoteMaxSteps else null)
                        .putOpt("ok", ok).putOpt("error", err)
                        .putOpt("args", toolArgs(ev.data.optString("arguments")))
                        .toString())
                    phase = ChatPhase.WORKING
                    headerStatus.text = tierLabel(rc) + " · 干活中"
                    refresh()
                }
            }
            "approval" -> {
                // 内核审批卡 → 复用 GateActivity 弹窗（允许一次/本次任务都放行/拒绝，
                // 与 mini_loop 时代门控同语义）；决议 POST 回内核（remember=任务级记忆）
                val approvalId = ev.data.optString("approval_id")
                if (approvalId.isNotEmpty() && remoteRunning) {
                    val tool = ev.data.optString("tool").ifEmpty { "?" }
                    val pkg = ev.data.optString("unit").ifEmpty { "kernel" }
                    val args = ev.data.optJSONObject("arguments")?.toString() ?: ""
                    val risk = ev.data.optString("risk")
                    com.hachimi.app.gate.GateManager.enforceRemote(
                        act, pkg, tool, "风险:$risk $args".take(220)) { approve, remember ->
                        Rlog.log("approval decide: id=$approvalId approve=$approve remember=$remember")
                        Thread {
                            runCatching {
                                OmniAgentRemote.decideApproval(rc.baseUrl, rc.token,
                                    approvalId, approve, remember)
                            }.onFailure { Rlog.log("approval decide failed: ${it.message}") }
                        }.start()
                    }
                }
            }
            "approval_resolved" -> {
                // 异端决议（Web 端先答了）：弹窗若还在会自行超时收口，这里只留痕
                Rlog.log("approval_resolved: ${ev.data.optString("approval_id")}")
            }
            "debug" -> {
                // 内核调试流里与用户可见面相关的两类 → 知识注入提示卡（本地
                // knowledge 事件同款；其余 debug 事件属 Debug 面板，不进对话区）
                val extra = ev.data.optJSONObject("extra") ?: return false
                when (extra.optString("kind")) {
                    "skill_catalog" -> extra.optJSONObject("payload")?.optJSONArray("names")
                        ?.let { arr ->
                            for (i in 0 until arr.length())
                                arr.optString(i).takeIf { it.isNotBlank() }
                                    ?.let { remoteSkills.add(it) }
                        }
                    "memory_inject" -> if (remoteRunning) {
                        val p = extra.optJSONObject("payload")
                        if (items.lastOrNull() == ChatItem.Thinking) items.removeAt(items.size - 1)
                        ChatMapper.item("knowledge", mapOf<String, Any?>(
                            "memory" to ((p?.optInt("hits", 0) ?: 0) > 0),
                            "skills_names" to remoteSkills.joinToString("、"),
                            "chars" to (p?.optInt("chars", 0) ?: 0)
                        ))?.let { pushItem(it) }
                        refresh()
                    }
                }
            }
            "chat" -> {
                val role = ev.data.optString("role")
                // 内核 chat 事件载荷字段是 text（content 是旧约定）——只读 content
                // 会把最终回复整体丢掉（2026-10-31 真机实录：任务成功但 UI 无回复）。
                val raw = ev.data.optString("content").ifEmpty { ev.data.optString("text") }
                // meta 供轮末面板（steps/success）与失败判定；失败轮 content 可能
                // 为空，取 meta 不能放在 content 判空之后
                ev.data.optJSONObject("extra")?.optJSONObject("meta")?.let { meta ->
                    remoteMetaSteps = meta.optInt("steps", 0)
                    if (meta.has("success")) remoteMetaSuccess = meta.optBoolean("success")
                }
                if ((role == "agent" || role == "assistant") && raw.isNotBlank()) {
                    collapseThinking()
                    // 终态结论按上游口径 = **收尾大脑口播块的原文**（B10「结论直取」），
                    // 所以同一次拆标签：正文进气泡，思考段补回该块的深度思考卡。
                    val (think, visible) = ChatMapper.splitThinkTag(raw)
                    val content = visible.ifBlank { raw.trim() }
                    remoteFinalText = content
                    if (think.isNotEmpty() && remoteLastBlock.isNotEmpty())
                        upsertThinking("tag-$remoteLastBlock", think, "", false)
                    val streamed = (items.getOrNull(remoteLastIdx) as? ChatItem.AssistantMsg)?.text
                    // 本轮已流式过这句（同文 / 互为前缀）→ **就地定稿**，不再另开一条：
                    // 旧实现无条件替换最后一个气泡，会把执行单元的穿插口播顶成结论。
                    val sameSentence = streamed != null &&
                        (content == streamed || content.startsWith(streamed) ||
                            streamed.startsWith(content))
                    if (sameSentence) {
                        items[remoteLastIdx] = ChatItem.AssistantMsg(content)
                    } else {
                        if (items.lastOrNull() == ChatItem.Thinking) items.removeAt(items.size - 1)
                        pushItem(ChatItem.AssistantMsg(content))
                        remoteLastIdx = items.size - 1
                    }
                    // 播报规则（2026-11-04）：**只有通话发起的轮才念**——打字轮（含建议
                    // chips）只上屏。开关式配置已删。
                    if (voiceTurn) speakReply(content)
                    refresh()
                }
            }
            "error" -> remoteFail(ev.data.optString("error").ifEmpty { "远程轮出错" })
        }
        return false
    }

    // ---------------- 流式块落位（thinking / message 各一张卡，同块覆盖） ----------------

    /** 本轮取向：会话记忆优先（同 PC `modeByTask[currentTaskId] ?? "task"`）。 */
    private fun modeFor(tid: String?): String = tid?.let { modeByTask[it] } ?: chatMode

    /** 当前会话的有效取向（+ 菜单显示它，选完立即反映在勾上）。 */
    private fun activeMode(): String = modeFor(remoteTaskId ?: currentTaskId)

    /** 口播块 → 气泡（内核投的是「截至当前全文」，同块再来必须覆盖，不能叠加）。 */
    private fun upsertMessage(block: String, text: String, role: String) {
        val idx = remoteMsgBlocks[block]
        if (idx != null && idx in items.indices && items[idx] is ChatItem.AssistantMsg) {
            items[idx] = ChatItem.AssistantMsg(text)
        } else {
            if (items.lastOrNull() == ChatItem.Thinking) items.removeAt(items.size - 1)
            pushItem(ChatItem.AssistantMsg(text))
            remoteMsgBlocks[block] = items.size - 1
        }
        // 结论定稿只认**大脑**的口播块（执行单元的穿插口播不能被结论顶掉）
        if (role.isBlank() || role == "brain" || role == "assistant" || role == "agent") {
            remoteLastBlock = block
            remoteLastIdx = remoteMsgBlocks[block] ?: -1
        }
    }

    /** 深度思考卡 upsert：只在 task 取向建卡（chat 取向 = 只出结果，思考不进对话流）。 */
    private fun upsertThinking(key: String, text: String, model: String, streaming: Boolean) {
        if (modeFor(remoteTaskId) != "task") return
        val idx = remoteThinkBlocks[key]
        if (idx != null && idx in items.indices && items[idx] is ChatItem.ThinkingCard) {
            val old = items[idx] as ChatItem.ThinkingCard
            items[idx] = old.copy(text = text,
                model = model.ifBlank { old.model }, streaming = streaming)
        } else {
            if (items.lastOrNull() == ChatItem.Thinking) items.removeAt(items.size - 1)
            pushItem(ChatItem.ThinkingCard(text, model, streaming))
            remoteThinkBlocks[key] = items.size - 1
        }
        refresh()
    }

    /** 阶段/轮次边界：流式中的思考卡收起（PC 同规则：结果或下一阶段出现即折叠）。 */
    private fun collapseThinking() {
        var changed = false
        for (i in items.indices) {
            val c = items[i] as? ChatItem.ThinkingCard ?: continue
            if (c.streaming) { items[i] = c.copy(streaming = false); changed = true }
        }
        if (changed) refresh()
    }

    /**
     * 内核 toolcall.result → 卡片三态 (ok, error)。上游工具框架把所有工具结果统一
     * 包成 {"ok": bool, "error"?, "output"?}（omni_core/tools/base.py 执行包装），
     * JSON 解析优先；非 JSON 文本只认框架的失败字面量——宽松的"含 error 字样"启发
     * 会把「屏幕上写着 error」这类观测内容误判成失败，宁可不判也不给红卡。
     */
    private fun parseToolResult(res: String): Pair<Boolean?, String?> {
        val s = res.trim()
        if (s.isEmpty()) return null to null
        try {
            val o = JSONObject(s)
            if (o.has("ok")) {
                // optString 对 JSON null 返回字面量 "null"，需一并排除
                val err = o.optString("error").takeIf { it.isNotBlank() && it != "null" }
                return o.optBoolean("ok") to err
            }
            return true to null
        } catch (_: Exception) { /* 非 JSON */ }
        // 内核 toolcall.result 实为 str(dict)（Python repr：单引号 / True 首字母大写，
        // org.json 解析不了）——正则同时吃 JSON 与 repr 两种形态，展示级足够
        val okRe = Regex("[\"']ok[\"']\\s*:\\s*(True|False|true|false)")
        if (okRe.find(s)?.groupValues?.get(1)?.lowercase() == "false") {
            val errRe = Regex("[\"']error[\"']\\s*:\\s*[\"'](.*?)[\"']", RegexOption.DOT_MATCHES_ALL)
            return false to (errRe.find(s)?.groupValues?.get(1)?.take(120) ?: s.take(120))
        }
        return true to null
    }

    /** 内核 toolcall.arguments（JSON 字符串）→ 浮窗红框定位用 args 对象（tap_xy 的
     *  x/y、gesture 的 points）；解析失败返回 null（浮窗自动无定位）。 */
    private fun toolArgs(raw: String): JSONObject? = try {
        JSONObject(raw.trim())
    } catch (_: Exception) { null }

    /** 远程轮失败收尾（FailPanel + 恢复输入；不回退 Tier——下一轮探活再决定）。 */
    private fun remoteFail(reason: String) {
        sending = false
        remoteRunning = false
        input.isEnabled = true
        setRunningControls(false)
        // 执行链浮窗错误行（唯一失败出口：stream 断裂/内核回报失败都汇到这里）
        NarrativePanel.pushRemote(JSONObject()
            .put("event", "error").put("error", reason).toString())
        if (items.lastOrNull() == ChatItem.Thinking) items.removeAt(items.size - 1)
        items.add(ChatItem.FailPanel(reason))
        phase = ChatPhase.FAILED
        headerStatus.text = phase.label
        refresh()
    }

    /**
     * 停止当前轮（输入行 ■，2026-11-04 从头部 ⏹ 搬来并把线接实）。四步都做：
     * ① 先闭嘴：口播在按下之后还在念，是"这按钮没接线"最直接的观感来源——
     *    speakGen++ 让在途 TTS 合成结果作废，AudioPlayer.stop() 停已排队的播放；
     * ② 对**本轮实际生效 Tier** 下发 /api/runtime/stop。内核侧是硬取消不是置标志：
     *    AgentCore.request_stop() 同时 cancel 编排图 Task 与当前 chunk 的 SDK Task
     *    （句柄逐 chunk 更新），tool loop 每步再协作式检查，收尾通常 1s 内；
     * ③ 本轮 Tier 没记上时按**本机内核**（OMNI_HOST_BASE）寻址——环回是常驻执行体，
     *    存档的 PC 地址不是本轮的停对象（2026-10-31 踩过：环回轮点 ⏹ 打到 PC 上），
     *    而"没有 PC 配置就 return"等于按钮白按，所以这里不再静默退出；
     * ④ 收尾以内核 `status.running=false` 为准；5s 没等到就本地收口（静默降级，
     *    不把输入框永久卡死），并把"没等到"这件事落日志。
     */
    private fun stopRunningTurn() {
        val rc = remoteActiveRc ?: RemoteConfigStore.RemoteConfig(
            baseUrl = OMNI_HOST_BASE, token = "", auto = true)
        val tid = remoteTaskId
        stopRequested = true
        Rlog.log("stop: tier=${tierLabel(rc)} task=$tid")
        speakGen++
        AudioPlayer.stop()
        setRunningControls(false)
        headerStatus.text = "停止中…"
        Thread {
            try { OmniAgentRemote.stop(rc.baseUrl, rc.token, tid) }
            catch (e: Exception) { Rlog.log("stop request failed: ${e.message}") }
        }.start()
        main.postDelayed({
            if (!remoteRunning) return@postDelayed   // 内核已回报轮次边界（正常路径）
            Rlog.log("stop watchdog: 5s 内没等到内核 status.running=false，本地收口")
            settleStopped()
        }, 5000)
    }

    /** 停止后的本地收口（内核迟迟不回报边界时的降级；正常收尾仍走 status 事件）。 */
    private fun settleStopped() {
        remoteRunning = false
        stopRequested = false
        sending = false
        input.isEnabled = true
        collapseThinking()
        if (items.lastOrNull() == ChatItem.Thinking) items.removeAt(items.size - 1)
        NarrativePanel.pushRemote(JSONObject()
            .put("event", "finish").put("status", "chat")
            .put("steps", remoteStepN).put("summary", remoteFinalText).toString())
        phase = ChatPhase.IDLE
        headerStatus.text = "已停止"
        setRunningControls(false)
        refresh()
    }

    /** 远程配置对话框（C1 最小形态：base_url + token + 自动切换）。入口在 + 菜单 ·
     *  内核与模型（2026-11-04：头部 ☁ 下线）。这里没有语音开关：播不播由本轮是打字还是
     *  通话决定，语音端点只在 设置 里配。 */
    private fun showRemoteDialog() {
        val cur = RemoteConfigStore.load(act)
        val base = EditText(act).apply {
            hint = "http://<PC 内网 IP>:8000"; textSize = 13f
            setText(cur?.baseUrl ?: ""); setSingleLine(true)
        }
        val token = EditText(act).apply {
            hint = "server.auth_token（非回环必填）"; textSize = 13f
            setText(cur?.token ?: ""); setSingleLine(true)
        }
        val auto = android.widget.CheckBox(act).apply {
            // 默认不勾（2026-11-04）：填了地址≠要用地址，勾上才允许每轮探活把话发给 PC。
            text = "自动切换（探活可达走远程，不可达回本地）"; isChecked = cur?.auto ?: false; textSize = 13f
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ds.dp(act, 18)
            setPadding(p, p / 2, p, 0)
            addView(Ds.small(act, "远程内核 base_url")); addView(base)
            addView(Ds.small(act, "访问 token")); addView(token)
            addView(auto)
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("远程内核（OmniAgent）")
            .setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val cfg = RemoteConfigStore.RemoteConfig(
                    baseUrl = base.text.toString().trim(),
                    token = token.text.toString().trim(),
                    auto = auto.isChecked)
                RemoteConfigStore.save(act, cfg)
                toast(if (cfg.ready)
                    "远程内核已保存 · 自动切换${if (cfg.auto) "开" else "关（话仍走本机内核）"}"
                else "远程内核未配置（本地模式）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 语音入口前置检查（2026-11-04 严格化）：识别与播报的端点**只**来自
     * 设置 · 语音供应商，没配就在入口拦下并指向设置——不录音、不发送、不留半截轮，
     * 也不再悄悄改用端侧系统 ASR/TTS（那会让人以为"语音坏了"而不是"没配"）。
     */
    private fun voiceReadyOrToast(needStt: Boolean, needTts: Boolean): Boolean {
        val msg = VoiceProviderStore.missing(act, needStt, needTts) ?: return true
        toast(msg)
        return false
    }

    /**
     * 回复播报：供应商 TTS 是唯一通路（拿不到音频就如实提示，不再回退端侧朗读）。
     *
     * 免提期间额外做两件对讲不需要的事——
     * ① 合成前就把这段话交给 [EchoGuard]：消不干净的余音万一被转写回来，靠文本重合判掉；
     * ② 播放走 [AudioPlayer] 的通话声道（USAGE_VOICE_COMMUNICATION）：平台 AEC
     *    只有在这条链路上才拿得到参考信号，才有 echo 可对消。
     */
    private fun speakReply(text: String) {
        if (text.isBlank()) return
        val gen = ++speakGen
        EchoGuard.note(text)
        Thread {
            val r = voiceProviderTts(act, text)
            if (gen != speakGen) return@Thread   // 已被插话/点气泡打断：旧合成结果作废
            when (r) {
                is TtsResult.Audio -> {
                    if (!AudioPlayer.play(act, r.wav)) {
                        main.post { toast("这段音频播不了（非 PCM16 或本机不支持该采样率）") }
                    }
                }
                is TtsResult.NoProvider ->
                    main.post { toast("未配置语音供应商 → 设置 · 语音供应商") }
                is TtsResult.Failed ->
                    main.post { toast("语音播报失败：${r.message}") }
            }
        }.start()
    }

    /**
     * 通话 = 语音自由对话（形态即原「免提」，2026-11-04 收进 + 菜单；对讲已整段下线）：
     * 说完自动发送、回复念出来、念的过程中随时能插话。识别与播报都要配好（它会念回复）。
     *
     * "边听边说"的前提是**平台回声消除真的在跑**（[DuplexListener.start] 的返回口径）：
     * 没有 AEC 的机器上引擎自己退成半双工（播报期间不收音），这里如实说明形态——
     * 不假装能插话，否则用户体验是"我喊了它不理，还把我自己念的话发出去了"。
     * 那种机器上的打断替代 = 点正在播报的那条气泡（见 assistantRow）。
     */
    private fun startHandsFree() {
        if (!voiceReadyOrToast(needStt = true, needTts = true)) return
        val vp = VoiceProviderStore.active(act) ?: return
        handsFree = true
        handsErrs = 0
        speakGen++            // 新一轮通话开始：在途播报作废
        AudioPlayer.stop()
        val gen = ++speechGen
        val e = DuplexListener(act, vp,
            onUtterance = { t -> main.post { if (gen == speechGen) onHeard(t) } },
            onBargeIn = { main.post { if (gen == speechGen) onBargeIn() } },
            onError = { m -> main.post { if (gen == speechGen) onHandsError(m) } })
        duplex = e
        val refused = e.start()
        if (refused != null) {
            duplex = null
            stopHandsFree("通话开不起来：$refused")
            return
        }
        headerStatus.text = "通话中 · 说话即发送"
        Rlog.log("call: fullDuplex=${e.fullDuplex}")
        toast(if (e.fullDuplex)
            "通话开启：说完自动发送、回复念给你听；念的时候直接插话就能打断"
        else
            "通话开启：说完自动发送、回复念给你听。" +
                "这台机器没有可用的回声消除，念的时候不收音（要打断就点它的气泡）")
    }

    /** 通话听到的一句（余 echo 已在引擎里按文本判掉）：发出去。 */
    private fun onHeard(text: String) {
        if (!handsFree) return
        handsErrs = 0
        send(text = text)
    }

    /**
     * 插话：大肥鱼正在念 → 立刻闭嘴让位。收音不停，这句话照常收完、转写、发出去
     * （远程轮运行中 force=true 打断在途那一轮——见 [send] 的派发）。
     */
    private fun onBargeIn() {
        if (!handsFree) return
        if (AudioPlayer.isPlaying()) {
            speakGen++
            AudioPlayer.stop()
        }
    }

    /** 通话运行时错误（转写挂了/设备异常）：连续 3 次自动关，防坏配置无限循环 toast。 */
    private fun onHandsError(msg: String) {
        if (!handsFree || duplex == null) return
        if (++handsErrs >= 3) { stopHandsFree("通话已停止：$msg"); return }
        toast(msg)
    }

    private fun stopHandsFree(why: String? = null) {
        handsFree = false
        speechGen++
        duplex?.stop(); duplex = null
        EchoGuard.clear()
        headerStatus.text = phase.label
        toast(why ?: "通话结束")
    }

    // ---------------- + 菜单（取向 / 通话：同类收进一个入口，不再挂三个按钮） ----------------

    /** 当前展开的 + 菜单（行内点击后据此关窗）。 */
    private var plusMenu: android.widget.PopupWindow? = null

    /**
     * + 菜单：这轮"怎么说、说什么模型、话发给哪个内核"都收在这儿（2026-11-04 头部
     * 控件全部搬进来，同类收一处）。
     * 对讲（按住说话）已整段删除——它本质是"语音转文字再打字"，通话是它的超集。
     */
    private fun showPlusMenu(anchor: View) {
        val w = Ds.dp(act, 260)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.round(act, 14, Color.WHITE, 1, Ds.CARD_BORDER)
            val p = Ds.dp(act, 6)
            setPadding(p, p, p, p)
        }
        box.addView(menuCaption("对话取向"))
        val m = activeMode()
        box.addView(menuRow("工作 lane（干活 + 深度思考）", m == "task") { setChatMode("task") })
        box.addView(menuRow("对话纪律（只回话，不出思考）", m == "chat") { setChatMode("chat") })
        box.addView(menuDivider())
        box.addView(menuCaption("内核与模型"))
        val rc = RemoteConfigStore.load(act)
        val remoteOn = rc != null && rc.ready && rc.auto
        box.addView(menuRow("远程内核 · " + if (remoteOn) "走 PC" else "未启用（话走本机内核）",
            remoteOn) { showRemoteDialog() })
        box.addView(menuRow("本会话模型 · " + modelLabel(), false) { showModelPicker() })
        box.addView(menuDivider())
        box.addView(menuRow(if (handsFree) "结束通话" else "语音通话",
            handsFree) { if (handsFree) stopHandsFree() else startHandsFree() })
        val win = android.widget.PopupWindow(box, w, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        win.elevation = Ds.dp(act, 8).toFloat()
        plusMenu = win
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        // 右对齐锚点、向上展开（输入行贴屏幕底，向下会被裁掉）
        win.showAsDropDown(anchor, anchor.width - w, -(box.measuredHeight + anchor.height))
        win.setOnDismissListener { plusMenu = null }
    }

    private fun menuCaption(text: String): TextView = TextView(act).apply {
        this.text = text; textSize = 11f; setTextColor(Ds.TEXT_3)
        val p = Ds.dp(act, 10)
        setPadding(p, Ds.dp(act, 6), p, Ds.dp(act, 2))
    }

    private fun menuDivider(): View = View(act).apply {
        setBackgroundColor(Ds.CARD_BORDER)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ds.dp(act, 1))).apply {
            topMargin = Ds.dp(act, 4); bottomMargin = Ds.dp(act, 4)
        }
    }

    /** 菜单行：选中项打勾并染主色（无边框、同底色）。 */
    private fun menuRow(label: String, checked: Boolean, onClick: () -> Unit): TextView =
        TextView(act).apply {
            text = (if (checked) "✓ " else "　 ") + label
            textSize = 13f
            setTextColor(if (checked) Ds.PRIMARY else Ds.TEXT)
            val p = Ds.dp(act, 10)
            setPadding(p, Ds.dp(act, 9), p, Ds.dp(act, 9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick(); plusMenu?.dismiss() }
        }

    /** 会话模型的可读名（+ 菜单那一行用）："<provider>/<model>" 只留模型名，空 = 默认槽位。 */
    private fun modelLabel(): String =
        (chatModel.ifBlank { "默认" }).split("/").lastOrNull()?.take(10) ?: "默认"

    /** 选取向：全局记住（跨会话缺省）+ 写进当前会话的记忆（下条消息即生效）。 */
    private fun setChatMode(m: String) {
        if (m == chatMode && modeByTask[remoteTaskId ?: currentTaskId] == m) return
        chatMode = m
        prefs.edit().putString("chat_mode", m).apply()
        remoteTaskId?.let { modeByTask[it] = m }
        currentTaskId?.let { modeByTask[it] = m }
        toast(if (m == "chat") "对话纪律：只出结果，不显示深度思考"
              else "工作 lane：可干活，思考过程出深度思考卡")
    }

    /** 会话级模型选择：读内核目录（每次打开拉最新）→ 单选（"默认" + 全部模型）。 */
    private fun showModelPicker() {
        toast("读取模型目录…")
        Thread {
            val arr = runCatching {
                OmniAgentRemote.getJson(OMNI_HOST_BASE, "", "/api/runtime/models", 12000)
                    .optJSONArray("providers")
            }.getOrNull()
            main.post { renderModelPicker(arr) }
        }.start()
    }

    private fun renderModelPicker(providers: org.json.JSONArray?) {
        val entries = mutableListOf("" to "默认（跟随设置的主模型）")
        providers?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val pid = p.optString("id")
                val ms = p.optJSONArray("models") ?: continue
                for (j in 0 until ms.length()) {
                    val m = ms.optJSONObject(j) ?: continue
                    entries.add("$pid/${m.optString("id")}" to
                            "${p.optString("label")} · ${m.optString("id")}")
                }
            }
        }
        if (entries.size == 1) { toast("目录为空：先到 设置→模型 添加端点"); return }
        val sels = entries.map { it.second }.toTypedArray()
        val checked = entries.indexOfFirst { it.first == chatModel }
        android.app.AlertDialog.Builder(act)
            .setTitle("本会话模型")
            .setSingleChoiceItems(sels, checked) { d, which ->
                chatModel = entries[which].first
                prefs.edit().putString("chat_model", chatModel).apply()
                d.dismiss()
                toast(if (chatModel.isBlank()) "已恢复默认模型" else "本会话模型 = $chatModel")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show()

    fun onResume() {
        NarrativePanel.addListener(this)
        // 角色设置页返回即刷新（roleName/头像/称呼均为失焦即存，无「保存」按钮）
        role = RoleStore.read(act)
        headerName.text = role.roleName
        maybeRestoreLastSession()
    }

    /**
     * 事件订阅**常驻**（逻辑自洽走查修复）：首个 step 让位桌面是主流程
     * （HOME 跳转触发 Activity.onPause）——若此处移除 listener，执行期间
     * 的 step/finish 全丢，回 App 只见永久"思考中"（K4 闭环断链）。
     * 页面随 MainActivity 单实例常驻；销毁防护见 onNarrative isDestroyed。
     */
    fun onPause() { /* listener 常驻，不移除 */ }

    /**
     * 页面销毁：免提的全双工引擎必须收麦。它握着麦克风 + 把设备带进通话模式
     * （MODE_IN_COMMUNICATION），页面没了还继续听 = 后台偷录音 + 别人打电话异常，
     * 两者都是不可接受的残留态。
     */
    fun onDestroy() {
        if (handsFree) stopHandsFree("通话已随页面退出结束")   // 内含 duplex?.stop()
    }

    // ---------------- V13 会话列表 ----------------

    /** 会话面板（body 覆盖层）：标题行（新建/关闭）+ 可滚动会话行。 */
    private fun buildSessionsPanel(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ds.PAGE)
        visibility = View.GONE
    }.also { panel ->
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            val p = Ds.dp(act, 14)
            setPadding(p, p / 2, p / 2, p / 2)
        }
        head.addView(TextView(act).apply {
            text = "会话"; textSize = 15f; setTextColor(Ds.TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(act).apply {
            text = "＋ 新建会话"; textSize = 12f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = Ds.round(act, 9, Ds.PRIMARY)
            setPadding(Ds.dp(act, 14), Ds.dp(act, 8), Ds.dp(act, 14), Ds.dp(act, 8))
            setOnClickListener { newSession() }
        })
        head.addView(TextView(act).apply {
            text = "✕"; textSize = 15f; setTextColor(Ds.TEXT_2)
            setPadding(Ds.dp(act, 14), 0, 0, 0)
            setOnClickListener { sessionsPanel?.visibility = View.GONE }
        })
        panel.addView(head, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        sessionsList = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ds.dp(act, 10)
            setPadding(p, p, p, p)
        }
        panel.addView(android.widget.ScrollView(act).apply { addView(sessionsList) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun openSessions() {
        if (sending) { toast("任务进行中，先停止再切换会话"); return }
        sessionsPanel?.visibility = View.VISIBLE
        sessionsList?.removeAllViews()
        sessionsList?.addView(Ds.small(act, "读取中…"))
        // Phase 2（mini_loop 退役）：会话面板改读环回内核 /api/runtime/tasks（Web 同源，
        // 含项目名映射）——旧 bridge.list_tasks_json 读的是 mini_loop 库，完整内核
        // 任务从未出现在列表里（2026-11-03 用户实测问题）
        Thread {
            try {
                val arr = OmniAgentRemote.kernelTasks(OMNI_HOST_BASE, 30)
                main.post {
                    if (sessionsPanel?.visibility != View.VISIBLE) return@post
                    sessionsList?.removeAllViews()
                    for (i in 0 until arr.length()) {
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = Ds.dp(act, 8)
                        }
                        sessionsList?.addView(sessionRow(arr.getJSONObject(i)), lp)
                    }
                    if (arr.length() == 0) sessionsList?.addView(Ds.small(act, "还没有会话"))
                }
            } catch (e: Exception) {
                main.post {
                    sessionsList?.removeAllViews()
                    sessionsList?.addView(Ds.small(act, "读取会话失败：${e.javaClass.simpleName}"))
                }
            }
        }.start()
    }

    /** 单条会话行：状态符 + 首条消息 + 相对时间·步数；当前会话描边高亮。 */
    private fun sessionRow(t: JSONObject): View {
        val tid = t.optString("task_id")
        val label = SessionUi.stateLabel(t.optString("state"), t.optBoolean("success"))
        val glyphColor = when {
            label.startsWith("✓") -> Ds.GREEN_TXT
            label.startsWith("✗") -> Ds.RED_TXT
            label.startsWith("▶") -> Ds.PRIMARY
            else -> Ds.TEXT_3
        }
        val current = tid == currentTaskId
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.round(act, 12, Color.WHITE,
                if (current) 2 else 1, if (current) Ds.PRIMARY else Ds.CARD_BORDER)
            val p = Ds.dp(act, 12)
            setPadding(p, p, p, p)
            setOnClickListener { switchSession(tid) }
        }.also { row ->
            row.addView(TextView(act).apply {
                text = label.take(1); textSize = 13f; setTextColor(glyphColor)
            })
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                val p = Ds.dp(act, 10)
                setPadding(p, 0, 0, 0)
            }
            col.addView(TextView(act).apply {
                text = t.optString("objective").ifEmpty { "（无标题）" }
                textSize = 13f; setTextColor(Ds.TEXT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            col.addView(TextView(act).apply {
                text = SessionUi.subtitle(
                    SessionUi.relativeTime(t.optString("created_at")), t.optInt("steps", -1))
                textSize = 10f; setTextColor(Ds.TEXT_2)
                setPadding(0, Ds.dp(act, 2), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (current) row.addView(Ds.tag(act, "当前", Ds.PRIMARY, Color.parseColor("#E8F1FA")))
            // 行内删除按钮（P1.5 UI）：删历史会话；点击 consume 不触发整行切换。
            // 用独立 TextView 而非 setOnClickListener 包裹，避免与行点击冲突。
            // 符号用抽象「✕」（非 emoji 🗑），次级灰、视觉克制。
            row.addView(TextView(act).apply {
                text = "✕"; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(Ds.TEXT_2)
                setOnClickListener {
                    android.app.AlertDialog.Builder(act)
                        .setTitle("删除这个会话？")
                        .setMessage("将删除「${t.optString("objective").ifEmpty { "（无标题）" }}」及其全部执行记录，不可恢复。")
                        .setPositiveButton("删除") { _, _ -> deleteSession(tid) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }, LinearLayout.LayoutParams(Ds.dp(act, 30), Ds.dp(act, 30)).apply {
                leftMargin = Ds.dp(act, 6)
            })
        }
    }

    /** 删除会话：内核 DELETE /tasks/{id}（Phase 2 内核化）→ 刷新列表；若是当前会话则清 currentTaskId/last_task_id/items。 */
    private fun deleteSession(tid: String) {
        Thread {
            val r = runCatching { OmniAgentRemote.deleteTask(OMNI_HOST_BASE, tid) }
                .getOrElse { JSONObject().put("ok", false).put("error", "${it.message}") }
            main.post {
                if (r.optBoolean("ok", true)) {
                    if (tid == currentTaskId) {
                        currentTaskId = null
                        prefs.edit().remove("last_task_id").apply()
                        items.clear()
                        phase = ChatPhase.IDLE
                        headerStatus.text = phase.label
                        refresh()
                    }
                    toast("已删除")
                    openSessions()
                } else {
                    toast("删除失败：${r.optString("error")}")
                }
            }
        }.start()
    }

    /** 切换会话：last_task_id 落盘 + 会话回放（对话消息 + 执行轨迹）。 */
    private fun switchSession(tid: String) {
        prefs.edit().putString("last_task_id", tid).apply()
        currentTaskId = tid
        items.clear()
        sessionsPanel?.visibility = View.GONE
        refresh()
        replaySession(tid) {
            phase = ChatPhase.IDLE
            headerStatus.text = phase.label
            sending = false
            input.isEnabled = true
            refresh()
        }
    }

    /** 新建会话：清空当前流 + last_task_id 移除（下条消息自动建新 task）。 */
    private fun newSession() {
        prefs.edit().remove("last_task_id").apply()
        currentTaskId = null
        items.clear()
        sending = false
        input.isEnabled = true
        phase = ChatPhase.IDLE
        headerStatus.text = phase.label
        sessionsPanel?.visibility = View.GONE
        refresh()
    }

    /** 冷启动恢复（V13）：last_task_id 存在且流为空 → session_messages 回放。
     *  内核未就绪时保留重试（restoreDone 不置位，下次 onResume 再试）。 */
    private fun maybeRestoreLastSession() {
        if (restoreDone || act.isDestroyed) return
        if (items.isNotEmpty()) { restoreDone = true; return }   // 用户已抢先发消息
        val last = prefs.getString("last_task_id", null)
        if (last.isNullOrBlank()) { restoreDone = true; return }
        // 内核 attach 约需 10s，首次 onResume 常赶在就绪之前：轮询到就绪为止
        // （上限 ~30s），不必等用户再切一次前后台才回放出来。
        if (KernelHostService.pyBridgeModule == null) {
            if (restoreRetries++ < 30) main.postDelayed({ maybeRestoreLastSession() }, 1000)
            return
        }
        restoreDone = true
        currentTaskId = last
        replaySession(last, keepIfUserTyped = true) { refresh() }
    }

    // ---------------- 会话回放（对话消息 + 执行轨迹） ----------------

    /**
     * 回放一条会话（Phase 2 内核化）：数据面 = 环回内核 /tasks/{id}/history（Web 同源）。
     * mini_loop 旧库（session_messages_json/task_trace_json）随执行脑退役——完整内核
     * 任务在旧库无数据，此前切历史会话「没反应」的根因。语义不变：闲聊轮只出对话
     * 气泡；跑过任务的轮还原成 tool 卡 + 结果气泡 + 终态面板，与执行现场视觉一致。
     */
    private fun replaySession(tid: String, keepIfUserTyped: Boolean = false, after: () -> Unit) {
        Thread {
            val msgs = try {
                OmniAgentRemote.kernelTaskHistory(OMNI_HOST_BASE, tid)
                    .optJSONArray("messages") ?: org.json.JSONArray()
            } catch (_: Exception) { org.json.JSONArray() }
            main.post {
                if (keepIfUserTyped && items.isNotEmpty()) return@post   // 用户已抢先发消息
                items.clear()
                appendReplay(msgs)
                after()
            }
        }.start()
    }

    /** history.messages → 对话流（user 气泡 / 工具步骤卡 / 回复气泡 / 终态面板）。 */
    private fun appendReplay(msgs: org.json.JSONArray) {
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "user" -> pushItem(ChatItem.UserMsg(m.optString("content")))
                "assistant", "agent" -> {
                    val extra = m.optJSONObject("extra")
                    var shown = 0
                    extra?.optJSONArray("steps")?.let { steps ->
                        for (j in 0 until steps.length()) {
                            val st = steps.optJSONObject(j) ?: continue
                            if (st.optString("type") != "tool_call") continue
                            val (ok, err) = parseToolResult(st.optString("result"))
                            ChatMapper.item("step", mapOf<String, Any?>(
                                "tool" to st.optString("name").ifEmpty { "?" },
                                "ok" to ok, "error" to err,
                                "step" to (shown + 1)))?.let { pushItem(it); shown++ }
                        }
                    }
                    val content = m.optString("content").trim()
                    if (content.isNotEmpty()) pushItem(ChatItem.AssistantMsg(content))
                    // 终态面板：仅任务轮（有工具步骤）出，与现场一致；meta 供成败
                    extra?.optJSONObject("meta")?.let { meta ->
                        if (shown > 0) {
                            val ok = if (meta.has("success")) meta.optBoolean("success") else true
                            if (ok) {
                                ChatMapper.item("finish", mapOf<String, Any?>(
                                    "status" to "done", "summary" to content,
                                    "steps" to meta.optInt("steps", shown),
                                ))?.let { pushItem(it) }
                            } else {
                                ChatMapper.item("finish", mapOf<String, Any?>(
                                    "status" to "failed",
                                    "summary" to content.ifEmpty { "内核未回报原因" },
                                    "steps" to meta.optInt("steps", shown),
                                ))?.let { pushItem(it) }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "hachimi_ui"
        /** Phase 1 环回 omni 内核（omni_host.py 固定端口；仅 127.0.0.1，无鉴权面）。 */
        const val OMNI_HOST_BASE = "http://127.0.0.1:18471"
    }

    // ---------------- Adapter（消息流多类型渲染） ----------------

    private inner class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var data: List<ChatItem> = emptyList()

        fun submit(list: List<ChatItem>) {
            data = list
            notifyDataSetChanged()   // P1 简化：列表短（一轮任务 <30 条），全量刷新够用
        }

        override fun getItemCount(): Int = data.size

        override fun getItemViewType(position: Int): Int = when (data[position]) {
            is ChatItem.UserMsg -> 0
            is ChatItem.AssistantMsg -> 1
            is ChatItem.ToolCard -> 2
            is ChatItem.DonePanel -> 3
            is ChatItem.FailPanel -> 4
            ChatItem.Thinking -> 5
            is ChatItem.KnowledgeHint -> 6
            is ChatItem.ThinkingCard -> 7
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val root = LinearLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val p = Ds.dp(parent.context, 8)
                setPadding(p, p / 2, p, p / 2)
            }
            return object : RecyclerView.ViewHolder(root) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val root = h.itemView as LinearLayout
            root.removeAllViews()
            root.addView(when (val it = data[pos]) {
                is ChatItem.UserMsg -> userRow(it)
                is ChatItem.AssistantMsg -> assistantRow(it)
                is ChatItem.ToolCard -> toolRow(it)
                is ChatItem.DonePanel -> doneRow(it)
                is ChatItem.FailPanel -> failRow(it)
                ChatItem.Thinking -> thinkingRow()
                is ChatItem.KnowledgeHint -> knowledgeRow(it)
                is ChatItem.ThinkingCard -> thinkingCardRow(it, pos)
            })
        }

        private fun userRow(m: ChatItem.UserMsg): View = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            // 行必须 MATCH_PARENT 宽，gravity=END 才会把子 View 推到右侧；
            // 默认 WRAP_CONTENT 行无剩余宽度，END 失效导致用户气泡挤在左侧（真机实录）。
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }.also { row ->
            row.addView(TextView(act).apply {
                text = m.text; textSize = 14f; setTextColor(Color.WHITE)
                background = Ds.round(act, 16, Ds.PRIMARY)
                val p = Ds.dp(act, 12)
                setPadding(p, p / 2 + Ds.dp(act, 2), p, p / 2 + Ds.dp(act, 2))
                maxWidth = Ds.dp(act, 300)
                setTextIsSelectable(true)   // 长按选择/复制（用户消息同样可复制）
            })
            // 用户头像：气泡右侧，用「用户角色」头像（上传图 > 称呼首字），与大肥鱼（左）对称
            row.addView(ImageView(act).apply {
                setImageBitmap(userAvatar28())
            }, LinearLayout.LayoutParams(Ds.dp(act, 28), Ds.dp(act, 28)).apply {
                leftMargin = Ds.dp(act, 8); topMargin = Ds.dp(act, 2)
            })
        }

        private fun assistantRow(m: ChatItem.AssistantMsg): View = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            // 行 MATCH_PARENT 宽让 gravity=START 生效（同 userRow 根因修复）。
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }.also { row ->
            row.addView(ImageView(act).apply {
                setImageBitmap(avatar30)
            }, LinearLayout.LayoutParams(Ds.dp(act, 28), Ds.dp(act, 28)))
            row.addView(TextView(act).apply {
                text = m.text; textSize = 14f; setTextColor(Ds.TEXT)
                background = Ds.round(act, 16, Color.WHITE, 1, Ds.CARD_BORDER)
                val p = Ds.dp(act, 12)
                setPadding(p + Ds.dp(act, 4), p / 2 + Ds.dp(act, 2), p + Ds.dp(act, 4), p / 2 + Ds.dp(act, 2))
                maxWidth = Ds.dp(act, 300)
                setTextIsSelectable(true)   // 长按选择/复制（现场与回放气泡同型同权）
                // 点一下 = 让它闭嘴。对讲下线后，这是没有平台回声消除的机器上唯一的
                // 播报打断入口（那种设备播期间不收音，插话声听不到）。
                setOnClickListener {
                    if (AudioPlayer.isPlaying()) { speakGen++; AudioPlayer.stop() }
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Ds.dp(act, 8); topMargin = Ds.dp(act, 2)
            })
        }

        private fun toolRow(t: ChatItem.ToolCard): View = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.round(act, 12, Color.WHITE, 1, Ds.CARD_BORDER)
            val p = Ds.dp(act, 10)
            setPadding(p, p / 2, p, p / 2)
            // 宽度对齐对话气泡（WRAP_CONTENT），不再占满整行显得过长；
            // 左缩进与大肥鱼头像对齐（头像 28dp + 文本 margin 8dp = 36dp）。
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Ds.dp(act, 36); topMargin = Ds.dp(act, 4)
            }
        }.also { card ->
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(act).apply {
                text = ChatMapper.toolGlyph(t.tool); textSize = 14f
            })
            head.addView(TextView(act).apply {
                // 中文友好名（toolLabel）；折叠时带 ×N
                text = if (t.count > 1) "${ChatMapper.toolLabel(t.tool)} ×${t.count}"
                       else ChatMapper.toolLabel(t.tool)
                textSize = 12f; setTextColor(Ds.TEXT)
                val p = Ds.dp(act, 6)
                setPadding(p, 0, 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            head.addView(TextView(act).apply {
                text = when (t.ok) {
                    null -> "…"
                    true -> "✓"
                    false -> "✗"
                }
                textSize = 12f
                setTextColor(when (t.ok) {
                    null -> Ds.TEXT_3; true -> Ds.GREEN_TXT; false -> Ds.RED_TXT
                })
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.END
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            card.addView(head)
            if (t.error != null && t.error.isNotBlank()) {
                card.addView(TextView(act).apply {
                    text = t.error.take(80); textSize = 11f; setTextColor(Ds.RED_TXT)
                    setPadding(0, Ds.dp(act, 4), 0, 0)
                    ellipsize = TextUtils.TruncateAt.END; maxLines = 2
                })
            }
        }

        /** 完成面板（V5）：✓ + summary + 步数/耗时 + 再来一单 + fish_done 彩蛋。 */
        private fun doneRow(d: ChatItem.DonePanel): View = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.round(act, 16, Color.WHITE, 1, Ds.CARD_BORDER)
            val p = Ds.dp(act, 14)
            setPadding(p, p, p, p)
        }.also { card ->
            // 宽度按屏幕比例：全程 dp 单位（screenDp 与 Ds.dp 混算会把 px 当 dp，
            // 2.75x 屏上卡片被压成 ~125dp 窄条，真机实录），封顶 320dp。
            val screenDp = (act.resources.displayMetrics.widthPixels /
                act.resources.displayMetrics.density).toInt()
            val cardW = (screenDp - 24).coerceAtMost(320)
            card.layoutParams = ViewGroup.LayoutParams(Ds.dp(act, cardW), ViewGroup.LayoutParams.WRAP_CONTENT)
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            // 与 V4 失败态同构（设计图 ux_mockup_v3 .fail-panel-header）：大肥鱼居左，
            // 标题 + 台词在右，gap 10dp；成功/失败两态视觉一致。
            // 尺寸 64dp：设计图规范为 56dp，用户定放大一档增强角色存在感（内容区 292dp，
            // 文字仍余 218dp，不挤）。
            head.addView(ImageView(act).apply {
                setImageResource(FishAssets.DONE)
                adjustViewBounds = true
                maxHeight = Ds.dp(act, 64)
            }, LinearLayout.LayoutParams(Ds.dp(act, 64), LinearLayout.LayoutParams.WRAP_CONTENT))
            head.addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                val p2 = Ds.dp(act, 10)
                setPadding(p2, 0, 0, 0)
            }.also { info ->
                info.addView(TextView(act).apply {
                    text = "办好了~"; textSize = 15f; setTextColor(Ds.TEXT)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                info.addView(TextView(act).apply {
                    text = "${d.steps} 步" + (d.wallClockS?.let { " · ${"%.1f".format(it)}s" } ?: "")
                    textSize = 11f; setTextColor(Ds.TEXT_2)
                })
            })
            card.addView(head)
            if (d.summary.isNotBlank()) {
                card.addView(TextView(act).apply {
                    text = d.summary; textSize = 13f; setTextColor(Ds.TEXT_2)
                    setLineSpacing(Ds.dp(act, 2).toFloat(), 1f)
                    setPadding(0, Ds.dp(act, 10), 0, 0)
                })
            }
            card.addView(TextView(act).apply {
                text = "再来一单"; textSize = 13f; setTextColor(Ds.PRIMARY)
                gravity = Gravity.CENTER
                background = Ds.round(act, 10, Ds.PAGE)
                val p2 = Ds.dp(act, 12)
                setPadding(p2, Ds.dp(act, 9), p2, Ds.dp(act, 9))
                setOnClickListener { input.requestFocus() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Ds.dp(act, 12)
                }
            })
            // 成功态轨迹入口：对话流只保留对话语义（回放不含工具过程，内核口径），
            // 执行细节统一交给 TaskDetailActivity（task_trace_json，每轮 run 一节）。
            // 无会话 task_id 时不给入口（点了也查不到）。
            currentTaskId?.let { tid ->
                card.addView(TextView(act).apply {
                    text = "查看完整轨迹"; textSize = 12f; setTextColor(Ds.PRIMARY)
                    gravity = Gravity.CENTER
                    background = Ds.round(act, 10, Color.WHITE, 1, Ds.CARD_BORDER)
                    val p2 = Ds.dp(act, 12)
                    setPadding(p2, Ds.dp(act, 8), p2, Ds.dp(act, 8))
                    setOnClickListener {
                        act.startActivity(Intent(act, com.hachimi.app.TaskDetailActivity::class.java)
                            .putExtra("task_id", tid))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = Ds.dp(act, 8)
                    }
                })
            }
        }

        /** 失败面板（V4）：fish_fail + 人话错误 + 重试。 */
        private fun failRow(f: ChatItem.FailPanel): View = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.round(act, 16, Color.WHITE, 1, Ds.CARD_BORDER)
            val p = Ds.dp(act, 14)
            setPadding(p, p, p, p)
        }.also { card ->
            // 同 doneRow：全程 dp 单位，避免 dp/px 混算压窄卡片
            val screenDp = (act.resources.displayMetrics.widthPixels /
                act.resources.displayMetrics.density).toInt()
            val cardW = (screenDp - 24).coerceAtMost(320)
            card.layoutParams = ViewGroup.LayoutParams(Ds.dp(act, cardW), ViewGroup.LayoutParams.WRAP_CONTENT)
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // 设计图 .fail-panel-header img 规范 56dp；与完成态同步放大到 64dp（用户定）
            head.addView(ImageView(act).apply {
                setImageResource(FishAssets.FAIL)
                adjustViewBounds = true
                maxHeight = Ds.dp(act, 64)
            }, LinearLayout.LayoutParams(Ds.dp(act, 64), LinearLayout.LayoutParams.WRAP_CONTENT))
            head.addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                val p2 = Ds.dp(act, 12)
                setPadding(p2, 0, 0, 0)
            }.also { info ->
                info.addView(TextView(act).apply {
                    text = "这次没办好…"; textSize = 15f; setTextColor(Ds.TEXT)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                info.addView(TextView(act).apply {
                    text = "它真尽力了，别嘲笑它"; textSize = 11f; setTextColor(Ds.TEXT_2)
                })
            })
            card.addView(head)
            card.addView(TextView(act).apply {
                text = f.reason; textSize = 12f; setTextColor(Ds.RED_TXT)
                background = Ds.round(act, 8, Ds.PAGE)
                val p2 = Ds.dp(act, 10)
                setPadding(p2, p2 / 2, p2, p2 / 2)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            card.addView(TextView(act).apply {
                text = "重试一次"; textSize = 13f; setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = Ds.round(act, 10, Ds.PRIMARY)
                val p2 = Ds.dp(act, 12)
                setPadding(p2, Ds.dp(act, 9), p2, Ds.dp(act, 9))
                setOnClickListener {
                    if (lastObjective.isNotBlank()) { input.setText(lastObjective); send() }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Ds.dp(act, 12)
                }
            })
            // V4「手动接管」：看完整轨迹（task 详情页回放），人接手判断
            card.addView(TextView(act).apply {
                text = "手动接管（看完整轨迹）"; textSize = 12f; setTextColor(Ds.PRIMARY)
                gravity = Gravity.CENTER
                background = Ds.round(act, 10, Color.WHITE, 1, Ds.CARD_BORDER)
                val p2 = Ds.dp(act, 12)
                setPadding(p2, Ds.dp(act, 8), p2, Ds.dp(act, 8))
                setOnClickListener {
                    currentTaskId?.let { tid ->
                        act.startActivity(Intent(act, com.hachimi.app.TaskDetailActivity::class.java)
                            .putExtra("task_id", tid))
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Ds.dp(act, 8)
                }
            })
        }

        /** 思考中三点（K3a：本地状态机，无内核流式依赖）。 */
        private fun thinkingRow(): View = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }.also { row ->
            row.addView(ImageView(act).apply { setImageBitmap(avatar30) },
                LinearLayout.LayoutParams(Ds.dp(act, 28), Ds.dp(act, 28)))
            row.addView(TextView(act).apply {
                text = "•  •  •"
                textSize = 14f; setTextColor(Ds.TEXT_3)
                background = Ds.round(act, 14, Color.WHITE, 1, Ds.CARD_BORDER)
                val p = Ds.dp(act, 12)
                setPadding(p, p / 2 + Ds.dp(act, 2), p, p / 2 + Ds.dp(act, 2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = Ds.dp(act, 8); topMargin = Ds.dp(act, 2)
                }
            })
        }

        /** 深度思考卡（task 取向；PC `ThinkBlock` 同构）：灰底可折叠，流式期间自动展开、
         *  阶段/轮次收尾自动收起。长文限高内滚动，短文本高度自适应。 */
        private fun thinkingCardRow(t: ChatItem.ThinkingCard, pos: Int): View {
            val opened = t.open ?: t.streaming
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = Ds.round(act, 12, Ds.GRAY_BG)
                val p = Ds.dp(act, 10)
                setPadding(p, p / 2 + Ds.dp(act, 2), p, p / 2)
                // 左缩进对齐对话气泡（头像 28dp + margin 8dp），与 tool 卡同一条竖线
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = Ds.dp(act, 36); rightMargin = Ds.dp(act, 8); topMargin = Ds.dp(act, 4)
                }
            }
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    // 手动开合覆盖"跟随流式"的自动态（open=null 才自动）
                    val cur = items.getOrNull(pos) as? ChatItem.ThinkingCard ?: return@setOnClickListener
                    items[pos] = cur.copy(open = !(cur.open ?: cur.streaming))
                    refresh()
                }
            }
            // 💭 而不是 🧠：后者是 Unicode 11，旧 ROM 的 emoji 字库没有，机上实测是豆腐块
            head.addView(TextView(act).apply { text = "💭"; textSize = 12f })
            head.addView(TextView(act).apply {
                text = if (t.streaming) "深度思考中…" else "深度思考"
                textSize = 11f; setTextColor(Ds.TEXT_3)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(Ds.dp(act, 6), 0, 0, 0)
            })
            if (t.model.isNotBlank()) head.addView(TextView(act).apply {
                text = t.model.split("/").lastOrNull()?.take(14) ?: t.model
                textSize = 10f; setTextColor(Ds.TEXT_3)
                background = Ds.round(act, 7, Color.WHITE, 1, Ds.CARD_BORDER)
                val q = Ds.dp(act, 6)
                setPadding(q, 0, q, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = Ds.dp(act, 6)
                }
            })
            head.addView(TextView(act).apply {
                text = if (opened) "︿" else "﹀"
                textSize = 11f; setTextColor(Ds.TEXT_3); gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(head)
            if (opened) card.addView(android.widget.ScrollView(act).apply {
                addView(TextView(act).apply {
                    text = t.text; textSize = 12f; setTextColor(Ds.TEXT_2)
                    setLineSpacing(Ds.dp(act, 2).toFloat(), 1f)
                    setTextIsSelectable(true)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (t.text.length > 120) Ds.dp(act, 130) else ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ds.dp(act, 6) })
            return card
        }

        /** 知识注入提示行（P2 DoD2）：小字灰卡，注明本次注入的记忆/技能；空块出弱提示。 */
        private fun knowledgeRow(k: ChatItem.KnowledgeHint): View = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Ds.dp(act, 36)
            }
        }.also { row ->
            val what = buildString {
                val parts = mutableListOf<String>()
                if (k.memory) parts.add("全局记忆")
                if (k.skills.isNotEmpty()) parts.add("技能「${k.skills.joinToString("」「")}」")
                append(if (parts.isEmpty()) "本次无知识可注入"
                       else "已注入：${parts.joinToString(" + ")}")
                // P2-R Phase 3：注入块体量可见（去重后字节数）——知识页之外的运行时观测点
                if (k.chars > 0) append("（%d 字符）".format(k.chars))
            }
            row.addView(TextView(act).apply {
                text = "🧠 $what"
                textSize = 11f; setTextColor(Ds.TEXT_3)
                background = Ds.round(act, 10, Ds.GRAY_BG)
                val p = Ds.dp(act, 8)
                setPadding(p, p / 2, p, p / 2)
            })
        }

        private val avatar30 by lazy { FishAssets.circle(act, FishAssets.AVATAR, 28) }

        /** 用户角色头像（28dp）：上传图 > 称呼首字圆（与大肥鱼头像区分，P1.5 UI）。
         *  缓存按 path+mtime 失效：角色页换头像回到聊天即重解码，无需重启。 */
        private var userAvatarKey: Pair<String, Long>? = null
        private var userAvatarBmp: android.graphics.Bitmap? = null

        private fun userAvatar28(): android.graphics.Bitmap {
            val f = RoleStore.userAvatarFile(act, role.userAvatarPath)
            val key = f?.let { it.absolutePath to it.lastModified() }
            userAvatarBmp?.let { if (key == userAvatarKey) return it }
            val bmp = f?.let { ff ->
                android.graphics.BitmapFactory.decodeFile(ff.absolutePath)
                    ?.let { raw -> FishAssets.circle(raw, Ds.dp(act, 28)) }
            } ?: run {
                val size = Ds.dp(act, 28)
                val out = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(out)
                canvas.drawColor(Ds.PRIMARY)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = size * 0.44f
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val y = size / 2f - (paint.descent() + paint.ascent()) / 2
                canvas.drawText(role.userInitial, size / 2f, y, paint)
                out
            }
            userAvatarKey = key
            userAvatarBmp = bmp
            return bmp
        }
    }
}
