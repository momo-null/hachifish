package com.hachimi.app.ui

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * 会话列表纯函数层（V13 / K6 消费端）：状态文案 + 相对时间，零 Android 依赖，
 * JVM 单测直接覆盖（redesign_plan 验证策略：逻辑进纯函数）。
 *
 * 数据源 = bridge.list_tasks_json 行（task_id/objective/state/created_at/steps）。
 */
object SessionUi {

    /** 状态徽标：✓ 完成（含 chat 对话轮）/ ✗ 失败 / ▶ 进行中 / · 待命。 */
    fun stateLabel(state: String?, success: Boolean?): String = when {
        success == true -> "✓ 完成"
        success == false -> "✗ 失败"
        state == "running" -> "▶ 进行中"
        else -> "· 待命"
    }

    /** created_at（ISO8601 带时区）→ 相对时间；解析失败/缺失返回空串（UI 跳过）。 */
    fun relativeTime(iso: String?, nowMs: Long = System.currentTimeMillis()): String {
        if (iso.isNullOrBlank()) return ""
        val ts = try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            return ""
        }
        val diff = nowMs - ts
        val sec = diff / 1000
        return when {
            sec < 60 -> "刚刚"
            sec < 3600 -> "${sec / 60} 分钟前"
            sec < 86400 -> "${sec / 3600} 小时前"
            sec < 86400 * 7 -> "${sec / 86400} 天前"
            else -> {
                val d = OffsetDateTime.parse(iso)
                "%02d-%02d".format(d.monthValue, d.dayOfMonth)
            }
        }
    }

    /** 副标题组合：相对时间 ·（步数>0 时）"N 步"。 */
    fun subtitle(relative: String, steps: Int): String {
        val s = mutableListOf<String>()
        if (relative.isNotBlank()) s.add(relative)
        if (steps > 0) s.add("$steps 步")
        return s.joinToString(" · ")
    }
}
