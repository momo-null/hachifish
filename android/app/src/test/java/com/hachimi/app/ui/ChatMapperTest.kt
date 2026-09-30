package com.hachimi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatMapper 纯函数单测（P1：事件→消息流映射，与 kernel 叙事事件对拍）。
 * 事件字段语义 = bridge._emit 契约（start/message/step/finish/error）。
 */
class ChatMapperTest {

    private fun stepEvent(ok: Boolean? = null, error: String? = null, step: Int = 3) =
        mapOf<String, Any?>(
            "event" to "step", "tool" to "tap_by_id", "step" to step,
            "ok" to ok, "error" to error
        )

    @Test
    fun `start event produces no item but thinking phase`() {
        assertNull(ChatMapper.item("start", mapOf("objective" to "x")))
        assertEquals(ChatPhase.THINKING, ChatMapper.phase("start", mapOf()))
    }

    @Test
    fun `step event maps to tool card with three-state ok`() {
        val running = ChatMapper.item("step", stepEvent(ok = null)) as ChatItem.ToolCard
        assertNull(running.ok)
        assertEquals("tap_by_id", running.tool)
        assertEquals(3, running.step)

        val ok = ChatMapper.item("step", stepEvent(ok = true)) as ChatItem.ToolCard
        assertTrue(ok.ok!!)
        assertNull(ok.error)

        val err = ChatMapper.item("step", stepEvent(ok = false, error = "tap miss")) as ChatItem.ToolCard
        assertFalse(err.ok!!)
        assertEquals("tap miss", err.error)
        assertEquals(ChatPhase.WORKING, ChatMapper.phase("step", stepEvent()))
    }

    @Test
    fun `finish done maps to done panel with summary`() {
        val item = ChatMapper.item("finish", mapOf<String, Any?>(
            "status" to "done", "summary" to "便签已创建",
            "steps" to 6, "wall_clock_s" to 23.5)) as ChatItem.DonePanel
        assertEquals("便签已创建", item.summary)
        assertEquals(6, item.steps)
        assertEquals(23.5, item.wallClockS!!, 0.001)
        assertEquals(ChatPhase.DONE, ChatMapper.phase("finish", mapOf("status" to "done")))
    }

    @Test
    fun `finish chat is dialogue-final not failure`() {
        // 逻辑自洽走查修复：闲聊终态 status=chat——气泡已由 message 事件渲染，
        // 无面板；相位回 IDLE（非 FAILED，防"这次没办好"误报）
        assertNull(ChatMapper.item("finish", mapOf<String, Any?>(
            "status" to "chat", "summary" to "主人你好呀")))
        assertEquals(ChatPhase.IDLE, ChatMapper.phase("finish", mapOf("status" to "chat")))
    }

    @Test
    fun `finish incomplete maps to fail panel with summary as reason`() {
        val item = ChatMapper.item("finish", mapOf<String, Any?>(
            "status" to "incomplete", "summary" to "墙钟预算耗尽")) as ChatItem.FailPanel
        assertEquals("墙钟预算耗尽", item.reason)
        assertEquals(ChatPhase.FAILED, ChatMapper.phase("finish", mapOf("status" to "incomplete")))
    }

    @Test
    fun `finish incomplete without summary falls back to status`() {
        val item = ChatMapper.item("finish", mapOf<String, Any?>("status" to "stopped")) as ChatItem.FailPanel
        assertEquals("stopped", item.reason)
    }

    @Test
    fun `error event maps to fail panel`() {
        val item = ChatMapper.item("error", mapOf<String, Any?>("error" to "boom")) as ChatItem.FailPanel
        assertEquals("boom", item.reason)
        assertEquals(ChatPhase.FAILED, ChatMapper.phase("error", mapOf()))
    }

    @Test
    fun `message event maps to assistant bubble only when non-blank`() {
        val item = ChatMapper.item("message", mapOf<String, Any?>("content" to " 收到！ ")) as ChatItem.AssistantMsg
        assertEquals("收到！", item.text)
        assertNull(ChatMapper.item("message", mapOf<String, Any?>("content" to "  ")))
        assertNull(ChatMapper.item("message", mapOf<String, Any?>()))
        assertNull(ChatMapper.phase("message", mapOf()))   // 闲聊不改变相位
    }

    @Test
    fun `unknown event types are ignored`() {
        assertNull(ChatMapper.item("mode", mapOf("docked" to true)))
        assertNull(ChatMapper.item("paused", mapOf()))
        assertNull(ChatMapper.phase("mode", mapOf()))
        assertNull(ChatMapper.phase("paused", mapOf()))
    }

    @Test
    fun `thinking placeholder replaced by first substantive item`() {
        assertTrue(ChatMapper.replacesThinking(ChatItem.ToolCard(1, "observe", null, null)))
        assertTrue(ChatMapper.replacesThinking(ChatItem.AssistantMsg("hi")))
        assertTrue(ChatMapper.replacesThinking(ChatItem.DonePanel("", 0, null)))
        assertTrue(ChatMapper.replacesThinking(ChatItem.FailPanel("x")))
        assertFalse(ChatMapper.replacesThinking(null))
        assertFalse(ChatMapper.replacesThinking(ChatItem.UserMsg("x")))
    }

    @Test
    fun `missing numeric fields degrade gracefully`() {
        val item = ChatMapper.item("finish", mapOf<String, Any?>("status" to "done")) as ChatItem.DonePanel
        assertEquals(0, item.steps)
        assertNull(item.wallClockS)

        val step = ChatMapper.item("step", mapOf<String, Any?>("tool" to "observe")) as ChatItem.ToolCard
        assertNull(step.step)
        assertEquals("observe", step.tool)
    }

    @Test
    fun `tool glyph grouping`() {
        assertEquals("👆", ChatMapper.toolGlyph("tap_by_id"))
        assertEquals("👀", ChatMapper.toolGlyph("observe"))
        assertEquals("🚀", ChatMapper.toolGlyph("launch_app"))
        assertEquals("🏁", ChatMapper.toolGlyph("task_done"))
        assertEquals("🔧", ChatMapper.toolGlyph("weird_tool"))
    }

    @Test
    fun `tool label is user-friendly chinese phrase`() {
        assertEquals("打开应用", ChatMapper.toolLabel("launch_app"))
        assertEquals("输入文字", ChatMapper.toolLabel("type_text"))
        assertEquals("点击控件", ChatMapper.toolLabel("tap_by_id"))
        assertEquals("点击屏幕", ChatMapper.toolLabel("tap_xy"))
        assertEquals("查看屏幕", ChatMapper.toolLabel("observe"))
        assertEquals("滑动屏幕", ChatMapper.toolLabel("gesture"))
        assertEquals("按键操作", ChatMapper.toolLabel("press"))
        assertEquals("完成", ChatMapper.toolLabel("task_done"))
        assertEquals("识别画面", ChatMapper.toolLabel("look"))
        assertEquals("等待加载", ChatMapper.toolLabel("wait"))
        // 未命中回退原始名 + 前缀（诚实口径，不臆造）
        assertEquals("操作·weird_tool", ChatMapper.toolLabel("weird_tool"))
    }

    @Test
    fun `tool card count defaults to one`() {
        val running = ChatMapper.item("step", stepEvent(ok = null)) as ChatItem.ToolCard
        assertEquals(1, running.count)
    }
}
