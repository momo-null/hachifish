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
import com.hachimi.app.BrainConfigStore
import com.hachimi.app.KernelHostService
import com.hachimi.app.MainActivity
import com.hachimi.app.NarrativePanel
import com.hachimi.app.RoleStore
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
    // V13 会话列表：面板视图 + 冷启动恢复标记
    private var sessionsPanel: LinearLayout? = null
    private var sessionsList: LinearLayout? = null
    private var restoreDone = false
    /** 冷启动恢复的内核就绪轮询次数（上限 30 次 ≈ 30s）。 */
    private var restoreRetries = 0

    private val prefs get() = act.getSharedPreferences(PREFS_NAME, 0)

    /** 角色配置：onResume 重读——角色设置页改完返回即生效（称呼/头像），不再要求重启。 */
    private var role = RoleStore.read(act)

    // ---------------- 视图 ----------------

    val view: LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ds.PAGE)
    }
    private val headerStatus: TextView
    private val headerName: TextView
    private val btnPause: TextView
    private val btnStop: TextView
    private val recycler: RecyclerView
    private val emptyBox: LinearLayout
    private val input: EditText
    private val btnSend: TextView
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
        btnPause = controlBtn("⏸") { bridgeCall("request_pause") }
        btnStop = controlBtn("⏹") { bridgeCall("request_stop") }
        btnPause.visibility = View.GONE; btnStop.visibility = View.GONE
        header.addView(btnPause); header.addView(btnStop)
        header.addView(controlBtn("🗂") { openSessions() })
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
        inputArea.addView(input); inputArea.addView(btnSend)
        view.addView(inputArea)
        refresh()   // 首帧：空态（V2）或欢迎卡（V3，冷启动恢复后被 refresh 替换）
    }

    private fun controlBtn(glyph: String, onClick: () -> Unit): TextView =
        TextView(act).apply {
            text = glyph; textSize = 16f
            gravity = Gravity.CENTER
            background = Ds.round(act, 16, Ds.GRAY_BG)
            layoutParams = LinearLayout.LayoutParams(Ds.dp(act, 32), Ds.dp(act, 32)).apply {
                leftMargin = Ds.dp(act, 6)
            }
            setOnClickListener { onClick() }
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
        // 终态恢复输入；控制钮只在干活态出现
        if (newPhase != null) {
            phase = newPhase
            headerStatus.text = phase.label
            // 控制钮仅任务执行态显示（闲聊 brain 单次调用不可中断，按钮无意义）
            val working = phase == ChatPhase.WORKING
            btnPause.visibility = if (working) View.VISIBLE else View.GONE
            btnStop.visibility = if (working) View.VISIBLE else View.GONE
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

    private fun send() {
        val objective = input.text.toString().trim()
        if (objective.isEmpty()) return
        if (sending) { toast("大肥鱼正在干活，稍等或先停止"); return }
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) { toast("内核启动中（首次约需 10 秒），稍后再试"); return }
        val cfg = BrainConfigStore.load(act)
        if (cfg == null) {
            toast("未配置模型端点（BYOK）：请到 设置 → 通道（BYOK） 填写")
            (act as? MainActivity)?.let { a -> a.startActivity(Intent(a, com.hachimi.app.SettingsActivity::class.java)) }
            return
        }
        sending = true
        input.isEnabled = false
        input.setText("")
        // 点发送即收起输入法（P1.5 UI 反馈 3）：adjustResize 下输入区上移到键盘上方，
        // 发送后收起键盘让消息流/输入区回到完整可见高度。
        input.clearFocus()
        (act.getSystemService(Activity.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        lastObjective = objective
        yieldedHome = false
        items.add(ChatItem.UserMsg(objective))
        items.add(ChatItem.Thinking)
        phase = ChatPhase.THINKING
        headerStatus.text = phase.label
        refresh()
        Thread {
            try {
                bridge.callAttr("set_brain", cfg.baseUrl, cfg.apiKey, cfg.model)
                if (cfg.visionEnabled) {
                    bridge.callAttr("set_vision", cfg.visionBaseUrl, cfg.visionApiKey, cfg.visionModel)
                } else {
                    bridge.callAttr("set_vision", "", "", "")
                }
                // K1a 统一入口：message + 当前会话 task_id（首条不带，kernel 自动建；
                // start 事件回传 task_id 续存）。闲聊/任务由大脑自决（K1b），
                // HOME 让位与浮窗 show 挪到首个 step 事件（onNarrative，K4）。
                val body = JSONObject()
                    .put("message", objective)
                    .put("done_when", "")
                    .put("max_steps", SettingsPage.maxSteps(act))
                    .put("wall_clock_s", SettingsPage.wallClockS(SettingsPage.maxSteps(act)))
                currentTaskId?.let { body.put("task_id", it) }
                val r = JSONObject(bridge.callAttr("start_task_json", body.toString()).toString())
                if (!r.optBoolean("ok")) {
                    main.post {
                        sending = false
                        input.isEnabled = true
                        val reason = MainActivity.readableErrorStatic(r.optString("error"))
                        if (items.lastOrNull() == ChatItem.Thinking) items.removeAt(items.size - 1)
                        items.add(ChatItem.FailPanel(reason))
                        phase = ChatPhase.FAILED
                        headerStatus.text = phase.label
                        refresh()
                        act.startActivity(Intent(act, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            } catch (e: Exception) {
                main.post {
                    sending = false
                    input.isEnabled = true
                    if (items.lastOrNull() == ChatItem.Thinking) items.removeAt(items.size - 1)
                    items.add(ChatItem.FailPanel("启动失败：${e.javaClass.simpleName}"))
                    phase = ChatPhase.FAILED
                    headerStatus.text = phase.label
                    refresh()
                }
            }
        }.start()
    }

    private fun bridgeCall(method: String) {
        Thread {
            try { KernelHostService.pyBridgeModule?.callAttr(method) } catch (_: Exception) {}
        }.start()
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
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) {
            sessionsList?.removeAllViews()
            sessionsList?.addView(Ds.small(act, "内核启动中（首次约需 10 秒），稍后再试"))
            return
        }
        Thread {
            try {
                val r = JSONObject(bridge.callAttr("list_tasks_json", 30).toString())
                val arr = r.optJSONArray("tasks") ?: org.json.JSONArray()
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

    /** 删除会话：bridge 删除 task 资产 → 刷新列表；若是当前会话则清 currentTaskId/last_task_id/items。 */
    private fun deleteSession(tid: String) {
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) { toast("内核未就绪，稍后再试"); return }
        Thread {
            try {
                val r = JSONObject(bridge.callAttr("delete_task_json", tid).toString())
                main.post {
                    if (r.optBoolean("ok")) {
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
            } catch (e: Exception) {
                main.post { toast("删除失败：${e.javaClass.simpleName}") }
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
     * 回放一条会话：session_messages_json（对话）+ task_trace_json（执行轨迹）。
     * 语义（用户需求）：闲聊轮（run 无任何工具步骤）只出对话气泡，保持对话感；
     * 跑过任务的 run 还原成 tool 卡 + 结果气泡 + 终态面板，与执行现场视觉一致。
     * 交错顺序：按 ts 把消息排在对应 run 之前（用户先说，后跟这轮的轨迹）。
     */
    private fun replaySession(tid: String, keepIfUserTyped: Boolean = false, after: () -> Unit) {
        val bridge = KernelHostService.pyBridgeModule ?: return
        Thread {
            val msgs = try {
                JSONObject(bridge.callAttr("session_messages_json", tid).toString())
                    .optJSONArray("messages") ?: org.json.JSONArray()
            } catch (_: Exception) { org.json.JSONArray() }
            val trace = try {
                JSONObject(bridge.callAttr("task_trace_json", tid).toString())
            } catch (_: Exception) { null }
            main.post {
                if (keepIfUserTyped && items.isNotEmpty()) return@post   // 用户已抢先发消息
                items.clear()
                appendReplay(msgs, trace)
                after()
            }
        }.start()
    }

    private fun appendReplay(msgs: org.json.JSONArray, trace: JSONObject?) {
        var mi = 0
        /**
         * 消费 ts <= [ts] 的消息。[skipExact] 同文的 assistant 跳过：它已由本轮
         * finish 事件的结果气泡承担，再出一遍就是重复（R10 去重）。
         */
        fun flushBefore(ts: String, skipExact: String? = null) {
            while (mi < msgs.length() &&
                (ts.isBlank() || msgs.getJSONObject(mi).optString("ts") <= ts)) {
                val m = msgs.getJSONObject(mi++)
                when (m.optString("role")) {
                    "user" -> pushItem(ChatItem.UserMsg(m.optString("content")))
                    "assistant" -> {
                        val c = m.optString("content")
                        if (skipExact == null || c.trim() != skipExact.trim()) {
                            pushItem(ChatItem.AssistantMsg(c))
                        }
                    }
                }
            }
        }
        /** 窗口内最后一条 assistant 文案（旧 run 无 summary 时的兜底；不消费游标）。 */
        fun peekLastAssistant(ts: String): String? {
            var last: String? = null
            for (k in mi until msgs.length()) {
                val m = msgs.getJSONObject(k)
                if (ts.isNotBlank() && m.optString("ts") > ts) break
                if (m.optString("role") == "assistant") last = m.optString("content")
            }
            return last?.trim()?.takeIf { it.isNotEmpty() }
        }
        val runs = trace?.optJSONArray("runs")
        if (runs == null || runs.length() == 0) { flushBefore(""); return }
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            val run = r.optJSONObject("run")
            flushBefore(run?.optString("start_ts") ?: "")
            // 轨迹行 → step 事件：卡片由 ChatMapper 产出、pushItem 入列（折叠与现场同源）
            var shown = 0
            val lines = r.optJSONArray("lines")
            if (lines != null) {
                for (j in 0 until lines.length()) {
                    val ln = lines.getJSONObject(j)
                    val tool = ln.optJSONObject("action")?.optString("tool") ?: continue
                    if (tool.isBlank()) continue
                    val res = ln.optJSONObject("result")
                    val card = ChatMapper.item("step", mapOf<String, Any?>(
                        "tool" to tool,
                        "ok" to (if (res == null || !res.has("ok")) null else res.optBoolean("ok")),
                        "error" to res?.optString("error")?.takeIf { it.isNotBlank() },
                        "step" to (if (ln.has("step")) ln.optInt("step") else null)
                    )) ?: continue
                    pushItem(card)
                    shown++
                }
            }
            if (run == null) continue
            // 本轮窗口内的消息（用户消息可能晚于 start_ts 落盘）排在终态之前
            val endTs = run.optString("end_ts")
            val summary = run.optString("summary").takeIf { it.isNotBlank() }
                ?: peekLastAssistant(endTs)
                ?: run.optString("reason").takeIf { it.isNotBlank() }
                ?: ""
            flushBefore(endTs, summary)
            // 终态：还原成 finish 事件走 ChatMapper —— status 由 run 落盘（旧数据按
            // success 推导：有步骤=done，纯闲聊=chat），闲聊轮不出面板（与现场一致）
            val finish = mapOf<String, Any?>(
                "status" to (run.optString("status").takeIf { it.isNotBlank() }
                    ?: if (run.optBoolean("success")) (if (shown > 0) "done" else "chat")
                    else "failed"),
                "summary" to summary,
                "steps" to run.optInt("steps", shown),
                "wall_clock_s" to wallClockS(run)
            )
            ChatMapper.finishBubble("finish", finish)?.let { pushItem(it) }
            ChatMapper.item("finish", finish)?.let { pushItem(it) }
        }
        flushBefore("")
    }

    /** run 起止 ISO → 耗时秒（解析失败返回 null，面板自动省略耗时）。 */
    private fun wallClockS(run: JSONObject): Double? = try {
        val s = java.time.OffsetDateTime.parse(run.optString("start_ts"))
        val e = java.time.OffsetDateTime.parse(run.optString("end_ts"))
        java.time.Duration.between(s, e).toMillis() / 1000.0
    } catch (_: Exception) { null }

    companion object {
        private const val PREFS_NAME = "hachimi_ui"
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
