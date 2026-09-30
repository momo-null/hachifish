package com.hachimi.app.ui

/**
 * 对话消息流数据模型 + 事件映射纯函数（redesign_plan A3 / K3-K4）。
 *
 * 纯函数层：零 Android 依赖（org.json 不入此层——NarrativePanel 侧把
 * JSONObject 解成 Map 后调用 [ChatMapper]），JVM 单测直接覆盖。
 *
 * 事件语义（与父项目 turn_steps 对齐，rev3 定稿）：
 * - start    → 生命周期：进入「思考中」（前端三点状态机，K3a）
 * - message  → 口播/答案气泡（P2 统一入口启用；任务执行中的穿插口播同型）
 * - step     → tool 卡片（tool_call）
 * - finish   → 终态面板：status=done → 完成面板（summary）；否则失败面板
 * - error    → 失败面板（kernel 异常）
 * thinking 不存在（手机端无深度思考，K3a）。
 */
sealed class ChatItem {

    /** 用户消息（本地插入，右气泡）。 */
    data class UserMsg(val text: String) : ChatItem()

    /** 大肥鱼口播/答案（左气泡；P1 用于 finish 前的最终答复，P2 扩展穿插口播）。 */
    data class AssistantMsg(val text: String) : ChatItem()

    /** tool 执行卡片：icon 色按 [ok] 三态（null=进行中）。 */
    data class ToolCard(
        val step: Int?,
        val tool: String,
        val ok: Boolean?,
        val error: String?
    ) : ChatItem()

    /** 完成面板（fish_done 彩蛋 + summary + 再来一单）。 */
    data class DonePanel(
        val summary: String,
        val steps: Int,
        val wallClockS: Double?
    ) : ChatItem()

    /** 失败面板（fish_fail + 人话错误 + 重试）。 */
    data class FailPanel(val reason: String) : ChatItem()

    /** 「思考中」三点指示（K3a 前端状态机：start 后首个事件前）。 */
    object Thinking : ChatItem()
}

/** 聊天头部状态（对齐 V3 头部语义）。 */
enum class ChatPhase(val label: String) {
    IDLE("待命中"),
    THINKING("思考中…"),
    WORKING("正在干活"),
    DONE("任务完成 · 求夸奖"),
    FAILED("遇到点麻烦…");
}

object ChatMapper {

    /**
     * 叙事事件 → 消息流条目（+ 头部相位）。
     *
     * @param type 事件名（start/message/step/finish/error）
     * @param e   事件字段（已解 Map；数值类型由解析层归一为 String/Int/Boolean/Double）
     * @return null = 事件不产生消息条目（start 只驱动相位；finish status=chat 的
     *         终态已由 message 事件出过气泡，不重复出面板）
     */
    fun item(type: String, e: Map<String, Any?>): ChatItem? = when (type) {
        "message" -> (e["content"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { ChatItem.AssistantMsg(it) }
        "step" -> ChatItem.ToolCard(
            step = (e["step"] as? Number)?.toInt(),
            tool = (e["tool"] as? String) ?: "?",
            ok = e["ok"] as? Boolean,
            error = e["error"] as? String
        )
        "finish" -> when (e["status"] as? String) {
            "done" -> ChatItem.DonePanel(
                summary = (e["summary"] as? String) ?: "",
                steps = (e["steps"] as? Number)?.toInt() ?: 0,
                wallClockS = (e["wall_clock_s"] as? Number)?.toDouble()
            )
            // chat = 对话轮终态：气泡已由 message 事件渲染，无面板
            "chat" -> null
            else -> ChatItem.FailPanel(
                reason = (e["summary"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (e["status"] as? String) ?: "未完成"
            )
        }
        "error" -> ChatItem.FailPanel(
            reason = (e["error"] as? String) ?: "发生错误"
        )
        else -> null   // start / paused / mode：只驱动相位，不进消息流
    }

    /** 事件 → 头部相位（null = 相位不变）。 */
    fun phase(type: String, e: Map<String, Any?>): ChatPhase? = when (type) {
        "start" -> ChatPhase.THINKING
        "step" -> ChatPhase.WORKING
        "message" -> null   // 口播不改变工作相位语义
        "finish" -> when (e["status"] as? String) {
            "done" -> ChatPhase.DONE
            "chat" -> ChatPhase.IDLE   // 对话轮结束：回待命（非任务完成/失败）
            else -> ChatPhase.FAILED
        }
        "error" -> ChatPhase.FAILED
        else -> null
    }

    /** 事件到达时若存在 Thinking 占位则应移除（首个实质事件替换思考态）。 */
    fun replacesThinking(item: ChatItem?): Boolean =
        item is ChatItem.ToolCard || item is ChatItem.AssistantMsg
            || item is ChatItem.DonePanel || item is ChatItem.FailPanel

    /** tool 名 → 图标语义分组（渲染用，纯函数可测）。 */
    fun toolGlyph(tool: String): String = when {
        tool.contains("tap") -> "👆"
        tool.contains("type") || tool.contains("input") -> "⌨"
        tool.contains("gesture") || tool.contains("swipe") -> "🤏"
        tool.contains("observe") || tool.contains("screenshot") -> "👀"
        tool.contains("launch") || tool.contains("open") -> "🚀"
        tool.contains("press") || tool.contains("back") -> "⬅"
        tool == "task_done" -> "🏁"
        else -> "🔧"
    }
}
