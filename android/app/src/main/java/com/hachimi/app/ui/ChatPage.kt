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
import com.hachimi.app.SettingsPage
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

    private val prefs get() = act.getSharedPreferences(PREFS_NAME, 0)

    private val role = RoleStore.read(act)

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
        emptyBox = buildEmpty()
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

    /** 空状态（V2）：压力大肥鱼 + 主人称呼 + 建议 chips。 */
    private fun buildEmpty(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(Ds.PAGE)
        val p = Ds.dp(act, 24)
        setPadding(p, Ds.dp(act, 40), p, p)
    }.also { box ->
        box.addView(ImageView(act).apply {
            setImageResource(FishAssets.EMPTY)
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
        listOf("帮我整理一下桌面文件", "打开便签写点东西", "把相册截图归类").forEach { s ->
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
        if (item != null) {
            // 首个实质事件替换思考占位（K3a 状态机）
            if (ChatMapper.replacesThinking(item) && items.lastOrNull() == ChatItem.Thinking) {
                items.removeAt(items.size - 1)
            }
            items.add(item)
            refresh()
        }
    }

    private fun refresh() {
        adapter.submit(items)
        emptyBox.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isNotEmpty()) recycler.scrollToPosition(items.size - 1)
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
        }
    }

    /** 切换会话：last_task_id 落盘 + session_messages 回放为消息流。 */
    private fun switchSession(tid: String) {
        prefs.edit().putString("last_task_id", tid).apply()
        currentTaskId = tid
        items.clear()
        sessionsPanel?.visibility = View.GONE
        refresh()
        Thread {
            val bridge = KernelHostService.pyBridgeModule ?: return@Thread
            try {
                val r = JSONObject(bridge.callAttr("session_messages_json", tid).toString())
                main.post {
                    if (r.optBoolean("ok")) {
                        val arr = r.optJSONArray("messages") ?: org.json.JSONArray()
                        for (i in 0 until arr.length()) {
                            val m = arr.getJSONObject(i)
                            when (m.optString("role")) {
                                "user" -> items.add(ChatItem.UserMsg(m.optString("content")))
                                "assistant" -> items.add(ChatItem.AssistantMsg(m.optString("content")))
                            }
                        }
                    }
                    phase = ChatPhase.IDLE
                    headerStatus.text = phase.label
                    sending = false
                    input.isEnabled = true
                    refresh()
                }
            } catch (_: Exception) {}
        }.start()
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
        if (restoreDone || items.isNotEmpty()) return
        val last = prefs.getString("last_task_id", null)
        if (last.isNullOrBlank()) { restoreDone = true; return }
        val bridge = KernelHostService.pyBridgeModule ?: return   // 内核冷启动中：下次再试
        restoreDone = true
        Thread {
            try {
                val r = JSONObject(bridge.callAttr("session_messages_json", last).toString())
                if (!r.optBoolean("ok")) return@Thread
                val arr = r.optJSONArray("messages") ?: return@Thread
                main.post {
                    if (items.isNotEmpty()) return@post   // 用户已抢先发消息
                    currentTaskId = last
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        when (m.optString("role")) {
                            "user" -> items.add(ChatItem.UserMsg(m.optString("content")))
                            "assistant" -> items.add(ChatItem.AssistantMsg(m.optString("content")))
                        }
                    }
                    refresh()
                }
            } catch (_: Exception) {}
        }.start()
    }

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
            })
        }

        private fun userRow(m: ChatItem.UserMsg): View = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }.also { row ->
            row.addView(TextView(act).apply {
                text = m.text; textSize = 14f; setTextColor(Color.WHITE)
                background = Ds.round(act, 16, Ds.PRIMARY)
                val p = Ds.dp(act, 12)
                setPadding(p, p / 2 + Ds.dp(act, 2), p, p / 2 + Ds.dp(act, 2))
                maxWidth = Ds.dp(act, 260)
            })
        }

        private fun assistantRow(m: ChatItem.AssistantMsg): View = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }.also { row ->
            row.addView(ImageView(act).apply {
                setImageBitmap(avatar30)
            }, LinearLayout.LayoutParams(Ds.dp(act, 28), Ds.dp(act, 28)))
            row.addView(TextView(act).apply {
                text = m.text; textSize = 14f; setTextColor(Ds.TEXT)
                background = Ds.round(act, 16, Color.WHITE, 1, Ds.CARD_BORDER)
                val p = Ds.dp(act, 12)
                setPadding(p + Ds.dp(act, 4), p / 2 + Ds.dp(act, 2), p + Ds.dp(act, 4), p / 2 + Ds.dp(act, 2))
                maxWidth = Ds.dp(act, 240)
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Ds.dp(act, 36)
            }
        }.also { card ->
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(act).apply {
                text = ChatMapper.toolGlyph(t.tool); textSize = 14f
            })
            head.addView(TextView(act).apply {
                text = t.tool; textSize = 12f; setTextColor(Ds.TEXT)
                typeface = android.graphics.Typeface.MONOSPACE
                val p = Ds.dp(act, 6)
                setPadding(p, 0, 0, 0)
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
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(act).apply {
                text = "✓"; textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Ds.GREEN_TXT)
                background = Ds.round(act, 14, Color.parseColor("#1452C41A"))
                layoutParams = LinearLayout.LayoutParams(Ds.dp(act, 28), Ds.dp(act, 28))
            })
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
            card.addView(ImageView(act).apply {
                setImageResource(FishAssets.DONE)
                adjustViewBounds = true
                maxHeight = Ds.dp(act, 90)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END; topMargin = Ds.dp(act, 8)
            })
        }

        /** 失败面板（V4）：fish_fail + 人话错误 + 重试。 */
        private fun failRow(f: ChatItem.FailPanel): View = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.round(act, 16, Color.WHITE, 1, Ds.CARD_BORDER)
            val p = Ds.dp(act, 14)
            setPadding(p, p, p, p)
        }.also { card ->
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
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

        private val avatar30 by lazy { FishAssets.circle(act, FishAssets.AVATAR, 28) }
    }
}
