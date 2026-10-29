package com.hachimi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OmniAgent 远程客户端纯函数单测（红线 D：纯函数逻辑用单测覆盖，无真机环境）。
 * 覆盖 SSE 行解析（doc/plans/remote-client-contract 的线上格式：`event: <name>\ndata: <json>`）
 * 与远程配置就绪判定。
 *
 * 解析器输出 raw 字符串（org.json 不入纯函数层，ChatModels 同款约定）——
 * data 断言用字符串包含，JSON 解析语义由运行时层负责。
 */
class OmniAgentRemoteTest {

    private fun feed(parser: OmniAgentRemote.SseParser, vararg lines: String) =
        lines.mapNotNull { parser.feed(it) }

    @Test
    fun `event and data pair terminated by blank line`() {
        val evs = feed(OmniAgentRemote.SseParser(),
            "event: message", "data: {\"content\":\"你好\"}", "")
        assertEquals(1, evs.size)
        assertEquals("message", evs[0].event)
        assertTrue(evs[0].data.contains("你好"))
    }

    @Test
    fun `realistic transcript yields thinking message status sequence`() {
        val evs = feed(OmniAgentRemote.SseParser(),
            "event: thinking", "data: {\"content\":\"思考中\"}", "",
            "event: message", "data: {\"role\":\"assistant\",\"block_id\":\"b1\",\"content\":\"今天\"}", "",
            "event: chat", "data: {\"role\":\"assistant\",\"content\":\"今天天气不错\"}", "",
            "event: status", "data: {\"running\":false,\"task_id\":\"t1\"}", "")
        assertEquals(4, evs.size)
        assertEquals(listOf("thinking", "message", "chat", "status"), evs.map { it.event })
        assertTrue(evs[2].data.contains("今天天气不错"))
        assertTrue(evs[3].data.contains("\"running\":false"))
    }

    @Test
    fun `comment lines and crlf are ignored`() {
        val evs = feed(OmniAgentRemote.SseParser(),
            ": keep-alive", "event: status\r", "data: {\"running\":false}\r", "\r")
        assertEquals(1, evs.size)
        assertEquals("status", evs[0].event)
        assertTrue(evs[0].data.contains("false"))
    }

    @Test
    fun `dataless event fires with empty data`() {
        val evs = feed(OmniAgentRemote.SseParser(), "event: status", "")
        assertEquals(1, evs.size)
        assertEquals("status", evs[0].event)
        assertEquals("", evs[0].data)
    }

    @Test
    fun `parser is reusable across events`() {
        val p = OmniAgentRemote.SseParser()
        feed(p, "event: message", "data: {\"content\":\"a\"}", "")
        val second = feed(p, "event: chat", "data: {\"content\":\"b\"}", "")
        assertEquals(1, second.size)
        assertEquals("chat", second[0].event)
        assertTrue(second[0].data.contains("b"))
    }

    @Test
    fun `remote config ready requires url and token`() {
        assertFalse(RemoteConfigStore.RemoteConfig("", "t").ready)
        assertFalse(RemoteConfigStore.RemoteConfig("http://x", "").ready)
        assertTrue(RemoteConfigStore.RemoteConfig("http://192.168.1.10:8000", "t").ready)
    }
}
