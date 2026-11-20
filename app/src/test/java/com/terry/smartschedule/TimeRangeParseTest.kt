package com.terry.smartschedule

import com.terry.smartschedule.parse.DateTimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 时间区间解析：结束时间要能用于冲突检测，不能只取开始时间。
 */
class TimeRangeParseTest {

    private val now = LocalDateTime.of(2026, 11, 19, 21, 50)

    private fun ldt(ms: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    private fun parse(text: String): DateTimeParser.Result {
        val r = DateTimeParser.parse(text, now)
        assertNotNull("应该能解析：$text", r)
        return r!!
    }

    @Test
    fun `下午3点到5点带出结束时间`() {
        val r = parse("明天下午3点-5点到知产楼1224盖章")
        assertEquals(LocalDateTime.of(2026, 11, 20, 15, 0), ldt(r.startAt))
        assertEquals(LocalDateTime.of(2026, 11, 20, 17, 0), ldt(r.endAt))
    }

    @Test
    fun `9点到11点带出结束时间`() {
        val r = parse("明天9:00-11:00开会")
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 0), ldt(r.startAt))
        assertEquals(LocalDateTime.of(2026, 11, 20, 11, 0), ldt(r.endAt))
    }

    @Test
    fun `上午11点到1点按下午1点算`() {
        val r = parse("明天上午11点到1点开会")
        assertEquals(LocalDateTime.of(2026, 11, 20, 11, 0), ldt(r.startAt))
        assertEquals(LocalDateTime.of(2026, 11, 20, 13, 0), ldt(r.endAt))
    }

    @Test
    fun `晚上8点到10点按22点算`() {
        val r = parse("明天晚上8点到10点开会")
        assertEquals(LocalDateTime.of(2026, 11, 20, 20, 0), ldt(r.startAt))
        assertEquals(LocalDateTime.of(2026, 11, 20, 22, 0), ldt(r.endAt))
    }

    @Test
    fun `23点到凌晨1点跨到第二天`() {
        val r = parse("明天23:00-01:00值班")
        assertEquals(LocalDateTime.of(2026, 11, 20, 23, 0), ldt(r.startAt))
        assertEquals(LocalDateTime.of(2026, 11, 21, 1, 0), ldt(r.endAt))
    }

    @Test
    fun `只有开始时间时结束时间为0`() {
        val r = parse("明天下午3点开会")
        assertEquals(0L, r.endAt)
        assertTrue(r.hasTime)
    }

    @Test
    fun `至和波浪号也能识别结束时间`() {
        val a = parse("明天下午3点至5点开会")
        assertEquals(LocalDateTime.of(2026, 11, 20, 17, 0), ldt(a.endAt))
        val b = parse("明天14:00~16:30开会")
        assertEquals(LocalDateTime.of(2026, 11, 20, 16, 30), ldt(b.endAt))
    }

    @Test
    fun `到5号楼不是时间区间`() {
        val r = parse("明天下午3点到5号楼开会")
        assertEquals(0L, r.endAt)
    }
}
