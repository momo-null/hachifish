package com.hachimi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

/** SessionUi 纯函数单测（V13 会话列表：状态文案 / 相对时间 / 副标题）。 */
class SessionUiTest {

    private fun isoOf(minutesAgo: Long): String =
        OffsetDateTime.now().minusMinutes(minutesAgo).toString()

    @Test
    fun `state label by success overrides state`() {
        assertEquals("✓ 完成", SessionUi.stateLabel("failed", true))
        assertEquals("✗ 失败", SessionUi.stateLabel("done", false))
    }

    @Test
    fun `state label falls back to state`() {
        assertEquals("▶ 进行中", SessionUi.stateLabel("running", null))
        assertEquals("· 待命", SessionUi.stateLabel("pending", null))
        assertEquals("· 待命", SessionUi.stateLabel(null, null))
    }

    @Test
    fun `relative time buckets`() {
        val now = System.currentTimeMillis()
        assertEquals("刚刚", SessionUi.relativeTime(isoOf(0), now))
        assertEquals("5 分钟前", SessionUi.relativeTime(isoOf(5), now))
        assertEquals("3 小时前", SessionUi.relativeTime(isoOf(190), now))
        assertEquals("2 天前", SessionUi.relativeTime(isoOf(60 * 48), now))
    }

    @Test
    fun `older than week falls back to date`() {
        val now = System.currentTimeMillis()
        val out = SessionUi.relativeTime(isoOf(60 * 24 * 30), now)
        assert(Regex("\\d{2}-\\d{2}").matches(out)) { out }
    }

    @Test
    fun `bad or missing iso yields empty`() {
        assertEquals("", SessionUi.relativeTime(null))
        assertEquals("", SessionUi.relativeTime(""))
        assertEquals("", SessionUi.relativeTime("not-a-date"))
    }

    @Test
    fun `subtitle joins non-blank parts`() {
        assertEquals("2 小时前 · 8 步", SessionUi.subtitle("2 小时前", 8))
        assertEquals("刚刚", SessionUi.subtitle("刚刚", -1))
        assertEquals("5 步", SessionUi.subtitle("", 5))
        assertEquals("", SessionUi.subtitle("", -1))
    }
}
