package com.hachimi.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hachimi.app.ui.ChatPage
import com.hachimi.app.ui.Ds
import com.hachimi.app.ui.SubPage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 技能页（知识 → 技能）：内核自动蒸馏出的「可复用操作套路」，能看、能回放、能升全局。
 *
 * 数据面全走环回内核的既有端点（PC 控制台同源，壳侧零新接口）：
 * - 读：``GET /api/runtime/skills?task_id=``（``_read_skills`` 按知识分层 v2 合并
 *   project 优先 + global，条目标 ``scope``）；
 * - 回放：``POST /api/runtime/skill/run``（确定性回放 substeps，非执行类步骤跳过；
 *   会话占用时内核回 409，这里诚实提示）；
 * - 升全局：``POST /api/runtime/skill/promote``（A7：到 global 的唯一路径是用户手动）；
 * - 删除：``POST /api/runtime/skill/delete``（物理删，先二次确认）。
 *
 * 技能归属 project（由 task 解析），所以页头要先选会话：默认取对话页当前会话
 * （prefs ``last_task_id``），否则取最近一次任务。
 */
class SkillsActivity : Activity() {

    private lateinit var page: SubPage
    private var tasks = JSONArray()
    private var selected = ""
    private var expanded = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = SubPage(this, "技能",
            "任务成功后内核自动蒸馏的可复用套路；按会话所属项目合并（项目优先 + 全局）")
        page.install()
        // 首帧数据由 onResume 统一拉（onCreate 里再调一次 = 同一份请求发两遍，
        // 两个回调先后重挂 chips/技能，界面会闪一下）；占位文案由 SubPage.load 负责
    }

    override fun onResume() {
        super.onResume()
        if (::page.isInitialized) loadTasks()
    }

    // ---------------- 会话选择 → 技能清单 ----------------

    private fun loadTasks() {
        page.load({
            val r = OmniAgentRemote.requestJson("GET", ChatPage.OMNI_HOST_BASE,
                "/api/runtime/tasks", null, 12000)
            val arr = r.optJSONArray("tasks") ?: JSONArray()
            // 只取子页真正要用的两列（task_id / objective），口径与任务页同源
            val norm = JSONArray()
            for (i in 0 until minOf(arr.length(), 6)) {
                val t = arr.optJSONObject(i) ?: continue
                norm.put(JSONObject()
                    .put("task_id", t.optString("task_id"))
                    .put("objective", t.optString("objective")))
            }
            JSONObject().put("_ok", r.optBoolean("_ok")).put("_status", r.optInt("_status"))
                .put("error", r.optString("error")).put("tasks", norm)
        }) { r ->
            tasks = r.optJSONArray("tasks") ?: JSONArray()
            if (tasks.length() == 0) {
                page.empty("✦", "还没有任务",
                    "技能在任务成功后自动蒸馏——先让大肥鱼跑成一个任务，再回到这里。")
                return@load
            }
            val last = getSharedPreferences("hachimi_ui", 0).getString("last_task_id", null)
            selected = if (last != null && indexOfTask(last) >= 0) last
            else tasks.optJSONObject(0)?.optString("task_id").orEmpty()
            mountChips()
            loadSkills()
        }
    }

    private fun indexOfTask(id: String): Int {
        for (i in 0 until tasks.length()) {
            if (tasks.optJSONObject(i)?.optString("task_id") == id) return i
        }
        return -1
    }

    /** 会话 chips 行 + 技能宿主一起装进 body（换会话 = 重挂 chips + 重读技能）。 */
    private val skillsHost: LinearLayout by lazy {
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    }

    /**
     * 会话选择：3 列等宽 chip 网格（末行补占位，保证列宽一致）。
     *
     * 为什么不是一条横排 LinearLayout：6 个 chip 直排时超出屏宽的那两个被压成 0 宽，
     * 文本改走纵向换行，把整行撑到 600+px 高（emulator 实录 2026-11-04）。等宽 +
     * 单行省略后，chip 数量再多也只按行增长。
     */
    private fun mountChips() {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val perRow = 3
        var idx = 0
        while (idx < tasks.length()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(row, Ds.vp(bottom = Ds.dp(this, 6)))
            for (col in 0 until perRow) {
                val t = if (idx < tasks.length()) tasks.optJSONObject(idx) else null
                idx++
                val lp = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (col > 0) lp.leftMargin = Ds.dp(this@SkillsActivity, 6)
                // 占位 View 只为把列宽钉住（否则末行 chip 独占整行宽度）
                row.addView(t?.let { chipFor(it) } ?: View(this), lp)
            }
        }
        page.render { host ->
            host.addView(grid)
            host.addView(Ds.small(this, "技能按所选会话所在的项目合并（项目优先 + 全局）"))
            host.addView(skillsHost, Ds.vp(top = Ds.dp(this, 8)))
        }
    }

    private fun chipFor(t: JSONObject): TextView {
        val tid = t.optString("task_id")
        val on = tid == selected
        return Ds.tag(this, t.optString("objective").ifEmpty { tid.take(8) },
            if (on) Ds.PRIMARY else Ds.TEXT_3,
            if (on) android.graphics.Color.parseColor("#1A8BC8EA") else Ds.GRAY_BG).apply {
            setPadding(Ds.dp(this@SkillsActivity, 8), Ds.dp(this@SkillsActivity, 5),
                Ds.dp(this@SkillsActivity, 8), Ds.dp(this@SkillsActivity, 5))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener {
                if (selected == tid) return@setOnClickListener
                selected = tid
                expanded.clear()
                mountChips()
                loadSkills()
            }
        }
    }

    // ---------------- 技能清单 ----------------

    private fun loadSkills() {
        skillsHost.removeAllViews()
        skillsHost.addView(Ds.small(this, "读取技能中…"))
        page.io({
            OmniAgentRemote.requestJson("GET", ChatPage.OMNI_HOST_BASE,
                "/api/runtime/skills?task_id=" + java.net.URLEncoder.encode(selected, "UTF-8"),
                null, 12000)
        }) { r ->
            skillsHost.removeAllViews()
            if (!r.optBoolean("_ok")) {
                skillsHost.addView(Ds.small(this, "读取技能失败：${r.optString("error")}"))
                return@io
            }
            val skills = r.optJSONArray("skills") ?: JSONArray()
            val real = mutableListOf<JSONObject>()
            for (i in 0 until skills.length()) {
                val s = skills.optJSONObject(i) ?: continue
                if (s.has("name")) real.add(s)
                else skillsHost.addView(Ds.small(this, "技能库读取异常：${s.optString("error")}"))
            }
            if (real.isEmpty() && skillsHost.childCount == 0) {
                skillsHost.addView(Ds.emptyState(this, "✦", "这个项目还没有技能",
                    "任务跑成功后内核会蒸馏出步骤套路；也可以在对话里让它复用。"))
            }
            real.forEach { skillsHost.addView(skillCard(it)) }
        }
    }

    private fun skillCard(s: JSONObject): View {
        val name = s.optString("name")
        val scope = s.optString("scope")
        val status = s.optString("status")
        val card = Ds.card(this)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(Ds.tag(this, if (status == "active") "已激活" else "候选",
            if (status == "active") Ds.GREEN_TXT else Ds.TEXT_3,
            if (status == "active") android.graphics.Color.parseColor("#1452C41A") else Ds.GRAY_BG))
        head.addView(Ds.tag(this, if (scope == "global") "全局" else "项目",
            Ds.PURPLE_TXT, Ds.PURPLE_BG).apply {
            setPadding(Ds.dp(this@SkillsActivity, 8), Ds.dp(this@SkillsActivity, 2),
                Ds.dp(this@SkillsActivity, 8), Ds.dp(this@SkillsActivity, 2))
        })
        head.addView(TextView(this).apply {
            text = name; textSize = 13f; setTextColor(Ds.TEXT)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(Ds.dp(this@SkillsActivity, 6), 0, 0, 0)
            maxLines = 1
        })
        card.addView(head)

        card.addView(Ds.small(this, brief(s.optString("objective_pattern"), 90)).apply {
            setTextColor(Ds.TEXT)
            textSize = 13f
            setPadding(0, Ds.dp(this@SkillsActivity, 6), 0, 0)
        })
        val uses = s.optInt("total_uses")
        card.addView(Ds.small(this, "成功率 ${s.optInt("success_count")}/$uses · " +
                "步骤 ${(s.optJSONArray("substeps") ?: JSONArray()).length()} 步").apply {
            setPadding(0, Ds.dp(this@SkillsActivity, 2), 0, 0)
        })

        // 步骤明细（默认折叠——回放前想确认它到底做什么）
        val steps = s.optJSONArray("substeps") ?: JSONArray()
        if (steps.length() > 0) {
            val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            detail.visibility = if (expanded.contains(name)) View.VISIBLE else View.GONE
            for (i in 0 until steps.length()) {
                val st = steps.optJSONObject(i) ?: continue
                detail.addView(Ds.small(this, "${i + 1}. ${st.optString("tool")} " +
                        brief(st.optJSONObject("args")?.toString() ?: "{}", 70)).apply {
                    setTextColor(Ds.TEXT_2)
                })
            }
            card.addView(detail, Ds.vp(top = Ds.dp(this, 6)))
            card.addView(TextView(this).apply {
                text = if (expanded.contains(name)) "收起步骤" else "展开步骤"
                textSize = 12f; setTextColor(Ds.PRIMARY)
                setPadding(0, Ds.dp(this@SkillsActivity, 6), 0, 0)
                setOnClickListener {
                    if (expanded.contains(name)) expanded.remove(name) else expanded.add(name)
                    detail.visibility =
                        if (expanded.contains(name)) View.VISIBLE else View.GONE
                    text = if (expanded.contains(name)) "收起步骤" else "展开步骤"
                }
            })
        }

        val ops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ops.addView(Ds.button(this, "回放") { runSkill(name) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (scope != "global") {
            ops.addView(Ds.button(this, "设为全局", primary = false) { promote(name) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = Ds.dp(this@SkillsActivity, 6) })
        }
        ops.addView(Ds.button(this, "删除", primary = false, danger = true) {
            confirmDelete(name, scope)
        },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = Ds.dp(this@SkillsActivity, 6) })
        card.addView(ops, Ds.vp(top = Ds.dp(this, 8)))

        val gap = LinearLayout(this)
        gap.addView(card, Ds.vp(top = Ds.dp(this, 8)))
        return gap
    }

    // ---------------- 三个动作 ----------------

    private fun skillPost(path: String, name: String, onOk: () -> Unit) {
        page.io({
            OmniAgentRemote.requestJson("POST", ChatPage.OMNI_HOST_BASE, path,
                JSONObject().put("task_id", selected).put("skill_name", name), 12000)
        }) { r ->
            if (r.optBoolean("_ok") && r.optBoolean("ok", true)) onOk()
            else Toast.makeText(this, when (r.optInt("_status")) {
                409 -> "这个会话正在跑任务——等它结束或先去对话页停止"
                else -> "失败：${r.optString("error")}"
            }.apply { Rlog.log("skill $path failed: ${r.optString("error")}") },
                Toast.LENGTH_LONG).show()
        }
    }

    private fun runSkill(name: String) = skillPost("/api/runtime/skill/run", name) {
        Toast.makeText(this, "已回放「$name」——进度在对话页那条会话里", Toast.LENGTH_LONG).show()
    }

    private fun promote(name: String) = skillPost("/api/runtime/skill/promote", name) {
        Toast.makeText(this, "已设为全局（跨项目可用）", Toast.LENGTH_SHORT).show()
        loadSkills()
    }

    private fun confirmDelete(name: String, scope: String) {
        // 文案跟着 scope 走：全局技能删的是全局库，说「本项目」会误导（实录：提升后再删）
        val where = if (scope == "global") "全局技能库" else "本项目"
        android.app.AlertDialog.Builder(this)
            .setTitle("删除技能「$name」？")
            .setMessage("物理删除${where}的这条技能，不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                skillPost("/api/runtime/skill/delete", name) {
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    loadSkills()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun brief(text: String, max: Int): String {
        val one = (text ?: "").replace("\r", " ").replace("\n", " ").trim()
        return if (one.length <= max) one else one.take(max) + "…"
    }
}
