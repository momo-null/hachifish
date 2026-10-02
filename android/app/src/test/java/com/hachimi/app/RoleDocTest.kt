package com.hachimi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RoleDoc 纯函数单测（redesign_plan A2：无真机环境，逻辑层 JVM 覆盖）。
 *
 * 与 kernel 侧 test_k0_persona.py 对拍：这里验证 Android 产出 character.md 的
 * 组装规则，kernel 侧验证读取与注入——两端共保「前端写 → 后端生效」闭环。
 */
class RoleDocTest {

    @Test
    fun `persona without address line gets address appended`() {
        val out = RoleDoc.compose("你是大肥鱼，勤快爱自嘲", "主人")
        assertTrue(out.startsWith("你是大肥鱼"))
        assertTrue(out.endsWith("称呼用户为「主人」。"))
    }

    @Test
    fun `persona already containing address is not duplicated`() {
        val persona = "你是大肥鱼，爱用「主人」称呼用户，可以适度卖萌。"
        assertEquals(persona, RoleDoc.compose(persona, "主人"))
    }

    @Test
    fun `persona truncated to limit`() {
        val out = RoleDoc.compose("字".repeat(RoleStore.PERSONA_LIMIT + 50), "主人")
        // 截断后补称呼段（append 在截断之后）
        assertEquals(RoleStore.PERSONA_LIMIT, out.indexOf("\n\n称呼用户为"))
    }

    @Test
    fun `blank persona produces empty doc`() {
        assertEquals("", RoleDoc.compose("   \n ", "主人"))
    }

    @Test
    fun `blank user name falls back to default`() {
        val out = RoleDoc.compose("你是大肥鱼", "  ")
        assertTrue(out.endsWith("称呼用户为「主人」。"))
    }

    @Test
    fun `whitespace persona is trimmed`() {
        val out = RoleDoc.compose("  你是大肥鱼  ", "主人")
        assertTrue(out.startsWith("你是大肥鱼"))
        assertFalse(out.startsWith(" "))
    }

    // ---------------- 幂等写盘判据 ----------------

    @Test
    fun `needsWrite true when hash differs`() {
        assertTrue(RoleDoc.needsWrite("old", "content", fileExists = true))
    }

    @Test
    fun `needsWrite true when file missing`() {
        assertTrue(RoleDoc.needsWrite(RoleDoc.fingerprint("content"), "content", fileExists = false))
    }

    @Test
    fun `needsWrite false when unchanged and file exists`() {
        val c = "content"
        assertFalse(RoleDoc.needsWrite(RoleDoc.fingerprint(c), c, fileExists = true))
    }

    @Test
    fun `fingerprint stable for same content`() {
        assertEquals(RoleDoc.fingerprint("abc"), RoleDoc.fingerprint("abc"))
    }

    @Test
    fun `compose output injectable by kernel`() {
        // 对拍契约：产出非空且含称呼段 → kernel load_character_text 注入后
        // MiniLoop prompt 前置人格块（kernel 侧 test_full_chain 已验证读取端）
        val out = RoleDoc.compose("你是大肥鱼，勤快爱自嘲", "主人")
        assertTrue(out.isNotBlank())
        assertTrue(out.contains("大肥鱼"))
    }
}
