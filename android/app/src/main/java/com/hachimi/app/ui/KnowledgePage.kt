package com.hachimi.app.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hachimi.app.KernelHostService
import org.json.JSONObject

/**
 * 知识 Tab（K7 接真数据，redesign_plan 原里程碑 P2 完成）：
 * - 技能：kernel skills_list_json（**人工维护**的全局 skill；自蒸馏已于 2026-10-02 下线）
 * - 记忆：memory_json（MEMORY.md 聚合视图）+ rollouts_list_json（蒸馏记录）
 * - 弱注入开关状态实时回显（设置页 ↔ 头部 tag 双向同步）
 * 世界模型段保留诚实占位（task 级资产，产品视图后置）。
 * 数据在 onResume 拉取（每次进 Tab 刷新——Curator 任务后随时过来看新货）。
 */
class KnowledgePage(private val act: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private var seg: Ds.Seg? = null
    private var injectTag: TextView? = null
    private lateinit var bodyHost: LinearLayout
    private lateinit var page: ScrollView
    private var section = 0

    val view: View
        get() {
            if (!::page.isInitialized) page = build()
            return page
        }

    fun onResume() {
        if (!::page.isInitialized) return
        refreshInjectTag()
        load(section)
    }

    private fun refreshInjectTag() {
        val on = act.getSharedPreferences(TasksPage.PREFS, 0)
            .getBoolean("knowledge_enabled", true)
        injectTag?.text = "弱注入：${if (on) "开" else "关"}"
        injectTag?.setTextColor(if (on) Ds.GREEN_TXT else Ds.TEXT_3)
    }

    private fun build(): ScrollView {
        val pad = Ds.dp(act, 20)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.setPadding(pad, Ds.dp(act, 12), pad, Ds.dp(act, 20))

        injectTag = Ds.tag(act, "弱注入：开", Ds.GREEN_TXT, Ds.GRAY_BG)
        box.addView(Ds.appBar(act, "知识", listOf(injectTag!!)))
        box.addView(Ds.small(act, "技能库 · 全局记忆 —— 任务跑完自动蒸馏，下次任务弱注入参考").apply {
            setPadding(0, 0, 0, Ds.dp(act, 10))
        })

        seg = Ds.seg(act, listOf("技能", "记忆", "世界模型"), 0) {
            section = it; load(it)
        }.also { box.addView(it.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }

        bodyHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(bodyHost, Ds.vp(top = Ds.dp(act, 10)))
        return ScrollView(act).apply { addView(box) }
    }

    // ---------------- 数据拉取（K7c 数据面消费） ----------------

    private fun load(sec: Int) {
        seg?.select(sec)
        if (sec == 2) {   // 世界模型：task 级资产，产品视图后置（诚实占位）
            renderWorldModel()
            return
        }
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) {
            bodyHost.removeAllViews()
            bodyHost.addView(Ds.emptyState(act, "⏳", "内核启动中",
                "首次约需 10 秒；就绪后切走再切回本页即自动加载。"))
            return
        }
        bodyHost.removeAllViews()
        bodyHost.addView(Ds.small(act, "读取中…"))
        Thread {
            try {
                val payload = if (sec == 0) {
                    bridge.callAttr("skills_list_json").toString()
                } else {
                    bridge.callAttr("memory_json").toString()
                }
                val extra = if (sec == 1)
                    bridge.callAttr("rollouts_list_json", 30).toString() else null
                main.post { render(sec, payload, extra) }
            } catch (e: Exception) {
                main.post {
                    bodyHost.removeAllViews()
                    bodyHost.addView(Ds.emptyState(act, "!", "读取失败",
                        "${e.javaClass.simpleName}——回前台重试。"))
                }
            }
        }.start()
    }

    private fun render(sec: Int, payload: String, extra: String?) {
        val r = JSONObject(payload)
        if (sec == 0) renderSkills(r) else renderMemory(r, extra)
    }

    /** 技能列表：名称 + 匹配规则 + 描述；长按出管理菜单（启停/删除，P2 DoD3）。 */
    private fun renderSkills(r: JSONObject) {
        bodyHost.removeAllViews()
        bodyHost.addView(Ds.small(act, "长按技能卡可 停用/启用 或 删除").apply {
            setTextColor(Ds.TEXT_3); setPadding(0, 0, 0, Ds.dp(act, 4))
        })
        val arr = r.optJSONArray("skills") ?: return
        if (arr.length() == 0) {
            bodyHost.addView(Ds.emptyState(act, "◈", "还没有技能",
                "任务成功跑完后，大肥鱼会把操作套路蒸馏成候选技能；\n" +
                "连续 3 次成功自动晋升为正式技能（可注入下次任务参考）。"))
            return
        }
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val disabled = s.optBoolean("disabled", false)
            val card = Ds.card(act)
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(act).apply {
                text = (if (disabled) "⏸ " else "") + s.optString("name")
                textSize = 14f; setTextColor(if (disabled) Ds.TEXT_3 else Ds.TEXT)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // V7 徽标：停用 > NEW > ACTIVE
            when {
                disabled -> head.addView(Ds.tag(act, "已停用", Ds.TEXT_3, Ds.GRAY_BG))
                else -> {
                    val created = s.optString("created_at")
                    val isNew = runCatching {
                        !created.isBlank() && System.currentTimeMillis() -
                            java.time.OffsetDateTime.parse(created).toInstant().toEpochMilli() < 7 * 86400_000L
                    }.getOrDefault(false)
                    val used = s.optInt("use_count", 0) > 0
                    head.addView(Ds.tag(act,
                        if (isNew) "NEW" else if (used) "ACTIVE" else "技能",
                        Ds.PRIMARY, Color.parseColor("#E8F1FA")))
                }
            }
            card.addView(head)
            s.optString("objective_pattern").takeIf { it.isNotBlank() }?.let {
                card.addView(Ds.small(act, "匹配：$it").apply { setPadding(0, Ds.dp(act, 4), 0, 0) })
            }
            s.optString("description").takeIf { it.isNotBlank() }?.let {
                card.addView(Ds.small(act, it).apply { setPadding(0, Ds.dp(act, 2), 0, 0) })
            }
            // V7 统计行：使用次数 + 成功率（数据缺省时跳过该行）
            val useCount = s.optInt("use_count", 0)
            val successCount = s.optInt("success_count", 0)
            if (useCount > 0) {
                val rate = if (useCount > 0) successCount * 100 / useCount else 0
                card.addView(Ds.small(act, "已使用 $useCount 次 · 成功率 $rate%").apply {
                    setTextColor(Ds.TEXT_3); setPadding(0, Ds.dp(act, 4), 0, 0)
                })
            }
            val name = s.optString("name")
            card.setOnLongClickListener {
                skillManageDialog(name, disabled)
                true
            }
            bodyHost.addView(card, Ds.vp(top = Ds.dp(act, 8)))
        }
    }

    /** 技能管理菜单（DoD3）：启停（停用技能不进注入与目录）/ 删除（可再蒸馏）。 */
    private fun skillManageDialog(name: String, disabled: Boolean) {
        val items = if (disabled)
            arrayOf("启用（恢复注入）", "删除")
        else
            arrayOf("停用（不再注入）", "删除")
        android.app.AlertDialog.Builder(act)
            .setTitle(name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> bridgeJson("skill_toggle_json", name, !disabled)
                    1 -> android.app.AlertDialog.Builder(act)
                        .setMessage("删除技能「$name」？\n（之后同类任务成功时大肥鱼会重新蒸馏）")
                        .setPositiveButton("删除") { _, _ ->
                            bridgeJson("skill_delete_json", name)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .show()
    }

    /** 管理动作统一走桥 + 刷新当前段（后台线程；失败吐司）。 */
    private fun bridgeJson(method: String, vararg args: Any) {
        val bridge = KernelHostService.pyBridgeModule ?: return
        Thread {
            try {
                bridge.callAttr(method, *args)
                main.post { load(section) }
            } catch (e: Exception) {
                main.post {
                    android.widget.Toast.makeText(
                        act, "${e.javaClass.simpleName}: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 记忆：导出/清空动作行 + MEMORY.md 摘要卡 + 蒸馏记录列表。 */
    private fun renderMemory(r: JSONObject, extra: String?) {
        bodyHost.removeAllViews()
        // 动作行（P2 DoD3/DoD5）：导出全部知识（zip 分享）/ 一键清空
        val actions = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun actionBtn(label: String, danger: Boolean, onClick: () -> Unit) {
            actions.addView(Ds.button(act, label, primary = !danger, danger = danger) { onClick() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (actions.childCount > 0) marginStart = Ds.dp(act, 8)
                }
            })
        }
        actionBtn("整理知识", danger = false) { compactMemory() }
        actionBtn("导出全部知识", danger = false) { exportKnowledge() }
        actionBtn("清空", danger = true) { clearDialog() }
        bodyHost.addView(actions, Ds.vp(bottom = Ds.dp(act, 10)))

        val master = r.optString("master")
        val rollouts = r.optInt("rollouts", 0)
        if (master.isBlank() && rollouts == 0) {
            bodyHost.addView(Ds.emptyState(act, "≡", "还没有记忆",
                "任务跑完后大肥鱼会蒸馏跨任务经验（rollout），\n" +
                "沉淀进全局记忆，下次任务前弱注入参考。"))
            return
        }
        if (master.isNotBlank()) {
            val card = Ds.card(act)
            card.addView(TextView(act).apply {
                text = "全局记忆 · ${r.optInt("master_chars")} 字"
                textSize = 14f; setTextColor(Ds.TEXT); typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(Ds.small(act, master.take(600) + if (master.length > 600) "\n…" else "")
                .apply { setPadding(0, Ds.dp(act, 6), 0, 0) })
            bodyHost.addView(card)
        }
        if (extra != null) {
            val er = JSONObject(extra)
            val arr = er.optJSONArray("rollouts")
            if (arr != null && arr.length() > 0) {
                bodyHost.addView(Ds.h2(act, "蒸馏记录").apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(0, Ds.dp(act, 16), 0, Ds.dp(act, 4)) }
                })
                for (i in 0 until arr.length()) {
                    val it2 = arr.getJSONObject(i)
                    val card = Ds.card(act)
                    val row = LinearLayout(act).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    }
                    // 记忆行：显示实际内容摘要（preview，对齐设计稿 V7），超 2 行截断；
                    // 点击进详情（AlertDialog 展示完整 content）。
                    val contentFull = it2.optString("content")
                    val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }.apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    col.addView(TextView(act).apply {
                        text = it2.optString("preview").takeIf { it.isNotBlank() }
                            ?: it2.optString("task_id")
                        textSize = 13f; setTextColor(Ds.TEXT)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    if (contentFull.isNotBlank()) {
                        col.addView(Ds.small(act, "${it2.optInt("chars")} 字 · 点击查看详情").apply {
                            setPadding(0, Ds.dp(act, 2), 0, 0)
                        })
                    }
                    row.addView(col)
                    card.setOnClickListener {
                        android.app.AlertDialog.Builder(act)
                            .setTitle("记忆详情")
                            .setMessage(contentFull.ifBlank { "（无内容）" })
                            .setPositiveButton("关闭", null)
                            .show()
                    }
                    card.addView(row)
                    bodyHost.addView(card, Ds.vp(top = Ds.dp(act, 6)))
                }
            }
        }
    }

    private fun renderWorldModel() {
        bodyHost.removeAllViews()
        bodyHost.addView(Ds.emptyState(act, "◇", "世界模型（随任务积累）",
            "App 布局/控件事实存在各任务资产目录（world_model.md），\n" +
            "任务执行时由大肥鱼自维护参考；产品级汇总视图后置。"))
    }

    // ---------------- 管理动作（P2 DoD3 清空 / DoD5 导出 / P2-R 合并重复） ----------------

    /** 合并重复（P2-R）：对存量事实做回溯合并。
     *  语义去重只在「新增 vs 存量」时生效（保护人工编辑），调参前沉淀的同义
     *  变体会一直留在库里 —— 这里给一次显式收口，并回显合并掉几条。 */
    private fun compactMemory() {
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) {
            android.widget.Toast.makeText(act, "内核启动中，稍后再试",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val r = JSONObject(bridge.callAttr("knowledge_compact_json").toString())
                val removed = r.optInt("removed")
                val pruned = r.optJSONObject("pruned")
                val facts = pruned?.optInt("facts_pruned") ?: 0
                val tasks = pruned?.optInt("tasks_pruned") ?: 0
                val msg = when {
                    removed > 0 || facts > 0 || tasks > 0 ->
                        "合并 $removed 条重复，淘汰 $facts 条超容事实，清理 $tasks 个过期 run"
                    else -> "知识库很整洁，无需整理"
                }
                main.post {
                    android.widget.Toast.makeText(
                        act, msg, android.widget.Toast.LENGTH_SHORT).show()
                    load(section)
                }
            } catch (e: Exception) {
                main.post {
                    android.widget.Toast.makeText(
                        act, "合并失败：${e.message}",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 一键清空（DoD3）：记忆-only 或 连技能一起；画像/角色卡是用户资产不动。 */
    private fun clearDialog() {
        android.app.AlertDialog.Builder(act)
            .setTitle("清空知识")
            .setItems(arrayOf("清空记忆（MEMORY.md + 蒸馏记录）", "清空全部（记忆 + 技能）")) { _, which ->
                val scope = if (which == 0) "memory" else "all"
                android.app.AlertDialog.Builder(act)
                    .setMessage(if (which == 0)
                        "清空全部记忆？下次任务将无历史参考（技能保留）。"
                    else "清空全部记忆与技能？下次任务从零开始积累。")
                    .setPositiveButton("清空") { _, _ -> bridgeJson("knowledge_clear_json", scope) }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 导出全部知识（DoD5）：knowledge_export_json → 打 zip → 系统分享。
     *  文件落 cacheDir/share/（FileProvider 只暴露此目录）。 */
    private fun exportKnowledge() {
        val bridge = KernelHostService.pyBridgeModule
        if (bridge == null) {
            android.widget.Toast.makeText(act, "内核启动中，稍后再试",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val r = JSONObject(bridge.callAttr("knowledge_export_json").toString())
                if (!r.optBoolean("ok")) {
                    main.post { android.widget.Toast.makeText(
                        act, "导出失败：${r.optString("error")}",
                        android.widget.Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val arr = r.optJSONArray("files") ?: org.json.JSONArray()
                val shareDir = java.io.File(act.cacheDir, "share").apply { mkdirs() }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                    java.util.Locale.US).format(java.util.Date())
                val out = java.io.File(shareDir, "hachimi_knowledge_$stamp.zip")
                java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        zip.putNextEntry(java.util.zip.ZipEntry(f.optString("path")))
                        zip.write(f.optString("content").toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
                main.post {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        act, "com.hachimi.app.fileprovider", out)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    act.startActivity(android.content.Intent.createChooser(send, "导出知识"))
                }
            } catch (e: Exception) {
                main.post { android.widget.Toast.makeText(
                    act, "导出失败：${e.javaClass.simpleName}: ${e.message}",
                    android.widget.Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }
}
