package com.terry.smartschedule

import com.terry.smartschedule.data.Event
import com.terry.smartschedule.data.EventMerge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「通知截断 / 多版本」的合并判断。
 *
 * 这组测试对应真实截图：同一条「贺老师盖章」通知在列表里出现了三条 ——
 * 一条只有「…可以在」（全天）、一条带「现有变」前缀、一条完整版。
 * 精确判重比标题+时间，三条互不相等，所以一条都没挡住。
 */
class EventMergeTest {

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, 21, hour, minute)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun event(
        title: String = "最近已经跟贺老师请过假需要签假条的同学",
        startAt: Long = at(15),
        hasTime: Boolean = true,
        location: String? = "知产楼1224",
        createdAt: Long = 1L
    ) = Event(
        title = title,
        startAt = startAt,
        hasTime = hasTime,
        location = location,
        createdAt = createdAt
    )

    @Test
    fun `截断成全天的版本遇到完整版要合并`() {
        val truncated = event(
            title = "最近已经跟贺老师请过假需要签假条的同学，可以在",
            startAt = at(9),
            hasTime = false,
            location = null
        )
        val full = event()
        assertTrue(EventMerge.shouldMerge(truncated, full))
    }

    @Test
    fun `带钟点的完整版不会被全天版覆盖`() {
        // 判断层认为可合并；入库层看到"已有带时间的"会跳过新来的全天版
        assertTrue(EventMerge.shouldMerge(event(), event(hasTime = false, startAt = at(9))))
    }

    @Test
    fun `改期前缀不影响同题判断`() {
        val withPrefix = event(title = "现有变，最近已经跟贺老师请过假需要签假条的同学")
        assertTrue(EventMerge.shouldMerge(event(), withPrefix))
    }

    @Test
    fun `地点冲突不合并`() {
        assertFalse(EventMerge.shouldMerge(event(location = "A栋"), event(location = "B栋")))
    }

    @Test
    fun `不是同一天不合并`() {
        val nextDay = LocalDateTime.of(2026, 11, 22, 15, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertFalse(EventMerge.shouldMerge(event(), event(startAt = nextDay)))
    }

    @Test
    fun `都带钟点时只容忍五分钟`() {
        assertTrue(EventMerge.shouldMerge(event(startAt = at(15, 0)), event(startAt = at(15, 5))))
        assertFalse(EventMerge.shouldMerge(event(startAt = at(15, 0)), event(startAt = at(15, 6))))
    }

    @Test
    fun `短标题不靠子串放宽`() {
        assertFalse(EventMerge.shouldMerge(event(title = "开会"), event(title = "开会讨论")))
    }

    @Test
    fun `普通的两条不同日程不合并`() {
        assertFalse(EventMerge.shouldMerge(event(), event(title = "部门周会")))
    }
}
