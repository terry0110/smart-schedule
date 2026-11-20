package com.terry.smartschedule

import com.terry.smartschedule.data.Event
import com.terry.smartschedule.data.EventConflict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 新日程落库前的冲突检查。
 *
 * 两类命中：
 * - 同一时间（两条都带钟点，相差 ≤5 分钟）；
 * - 名称 + 地点高度重合（前后 3 天内）。
 * 两类都只决定"要不要让人确认"，不直接改数据。
 */
class EventConflictTest {

    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, month, day, hour, minute)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 固定"现在"，让过去/未来判断可重复 */
    private val now = at(11, 20, 8)

    private fun event(
        id: Long = 0,
        title: String = "贺老师签假条盖章",
        startAt: Long = at(11, 21, 15),
        endAt: Long = 0L,
        hasTime: Boolean = true,
        location: String? = "知产楼1224",
        pending: Boolean = false,
        archived: Boolean = false,
        done: Boolean = false,
        revoked: Boolean = false
    ) = Event(
        id = id,
        title = title,
        startAt = startAt,
        endAt = endAt,
        hasTime = hasTime,
        location = location,
        pending = pending,
        archived = archived,
        done = done,
        revoked = revoked
    )

    @Test
    fun `同一时间不同标题也会被拦下`() {
        val existing = event(id = 1, title = "部门周会", startAt = at(11, 21, 15))
        val incoming = event(id = 0, title = "高数作业", startAt = at(11, 21, 15, 3), location = null)
        val hit = EventConflict.check(incoming, listOf(existing), now)
        assertEquals(EventConflict.Kind.OVERLAP, hit?.kind)
        assertEquals(1L, hit?.event?.id)
    }

    @Test
    fun `开始时间不同但时间段重叠也要拦下`() {
        val existing = event(
            id = 1, title = "部门周会", startAt = at(11, 21, 15),
            endAt = at(11, 21, 17)
        )
        val incoming = event(
            id = 0, title = "高数作业", startAt = at(11, 21, 16, 30),
            endAt = at(11, 21, 17, 30), location = null
        )
        val hit = EventConflict.check(incoming, listOf(existing), now)
        assertEquals(EventConflict.Kind.OVERLAP, hit?.kind)
        assertEquals(1L, hit?.event?.id)
    }

    @Test
    fun `没写结束时间时按默认一小时算重叠`() {
        val existing = event(id = 1, title = "部门周会", startAt = at(11, 21, 15))
        val incoming = event(id = 0, title = "高数作业", startAt = at(11, 21, 15, 30), location = null)
        val hit = EventConflict.check(incoming, listOf(existing), now)
        assertEquals(EventConflict.Kind.OVERLAP, hit?.kind)
    }

    @Test
    fun `首尾相接不算冲突`() {
        val existing = event(
            id = 1, title = "部门周会", startAt = at(11, 21, 15),
            endAt = at(11, 21, 16)
        )
        val incoming = event(
            id = 0, title = "高数作业", startAt = at(11, 21, 16),
            endAt = at(11, 21, 17), location = null
        )
        assertNull(EventConflict.check(incoming, listOf(existing), now))
    }

    @Test
    fun `开始时间差超过默认时长且不重叠时不算冲突`() {
        val existing = event(id = 1, title = "部门周会", startAt = at(11, 21, 15))
        val incoming = event(id = 0, title = "高数作业", startAt = at(11, 21, 17, 6), location = null)
        assertNull(EventConflict.check(incoming, listOf(existing), now))
    }

    @Test
    fun `隔天同一钟点不会被当成时间重叠`() {
        val existing = event(id = 1, title = "部门周会", startAt = at(11, 21, 15))
        val incoming = event(id = 0, title = "高数作业", startAt = at(11, 22, 15), location = null)
        assertNull(EventConflict.check(incoming, listOf(existing), now))
    }

    @Test
    fun `名称和地点重合会作为可能的变更拦下`() {
        val existing = event(id = 1, startAt = at(11, 21, 15))
        // 标题是截断版，日期/时间都变了，地点没变
        val incoming = event(id = 0, title = "贺老师盖章", startAt = at(11, 22, 16))
        val hit = EventConflict.check(incoming, listOf(existing), now)
        assertEquals(EventConflict.Kind.SIMILAR, hit?.kind)
        assertEquals(1L, hit?.event?.id)
    }

    @Test
    fun `地点只差门牌号不算重合`() {
        val existing = event(id = 1, location = "A栋302", startAt = at(11, 21, 15))
        val incoming = event(id = 0, location = "A栋303", startAt = at(11, 22, 16))
        assertNull(EventConflict.check(incoming, listOf(existing), now))
    }

    @Test
    fun `一边没有地点不算地点重合`() {
        val existing = event(id = 1, startAt = at(11, 21, 15))
        val incoming = event(id = 0, location = null, startAt = at(11, 22, 16))
        assertNull(EventConflict.check(incoming, listOf(existing), now))
    }

    @Test
    fun `部门周会和部门会议不算高度重合`() {
        val existing = event(id = 1, title = "部门周会", location = "会议室A", startAt = at(11, 21, 15))
        val incoming = event(id = 0, title = "部门会议", location = "会议室A", startAt = at(11, 22, 16))
        assertNull(EventConflict.check(incoming, listOf(existing), now))
    }

    @Test
    fun `归档完成取消的日程不参与`() {
        val incoming = event(id = 0, title = "高数作业", startAt = at(11, 21, 15), location = null)
        val targets = listOf(
            event(id = 2, title = "部门周会", archived = true),
            event(id = 3, title = "部门周会", done = true),
            event(id = 4, title = "部门周会", revoked = true)
        )
        assertNull(EventConflict.check(incoming, targets, now))
    }

    @Test
    fun `待确认的日程也参与冲突`() {
        val incoming = event(id = 0, title = "高数作业", startAt = at(11, 21, 15), location = null)
        val pending = event(id = 1, title = "部门周会", pending = true)
        val hit = EventConflict.check(incoming, listOf(pending), now)
        assertEquals(EventConflict.Kind.OVERLAP, hit?.kind)
        assertEquals(1L, hit?.event?.id)
    }

    @Test
    fun `已经过去的日程不参与`() {
        val past = event(id = 1, startAt = at(11, 19, 15))
        val incoming = event(id = 0, startAt = at(11, 21, 15))
        assertNull(EventConflict.check(incoming, listOf(past), now))
    }

    @Test
    fun `正在进行的日程也参与冲突`() {
        // now = 16:00，已有日程 15:00-17:00 还没结束
        val ongoing = event(
            id = 1, title = "部门周会", startAt = at(11, 20, 15),
            endAt = at(11, 20, 17)
        )
        val incoming = event(
            id = 0, title = "高数作业", startAt = at(11, 20, 16, 30), location = null
        )
        val hit = EventConflict.check(incoming, listOf(ongoing), at(11, 20, 16))
        assertEquals(EventConflict.Kind.OVERLAP, hit?.kind)
    }

    @Test
    fun `时间重叠优先于名称地点重合`() {
        val similar = event(id = 1, title = "贺老师签假条盖章", startAt = at(11, 21, 14))
        val overlap = event(id = 2, title = "部门周会", startAt = at(11, 21, 15))
        val incoming = event(id = 0, title = "贺老师签假条盖章", startAt = at(11, 21, 15, 2))
        val hit = EventConflict.check(incoming, listOf(similar, overlap), now)
        assertEquals(EventConflict.Kind.OVERLAP, hit?.kind)
        assertEquals(2L, hit?.event?.id)
    }

    @Test
    fun `标题和地点的重合度口径`() {
        // 截断版：短的 5 个字，靠字符包含度而不是"短标题至少 6 个字"
        assertEquals(1.0, EventConflict.titleCover("贺老师签假条盖章", "贺老师盖章"), 0.001)
        // 只共享"部门"和"会"，3/4 = 0.75，低于门槛
        assertEquals(0.75, EventConflict.titleCover("部门周会", "部门会议"), 0.001)
        // 短标题不做字符重合
        assertEquals(0.0, EventConflict.titleCover("开会", "部门会议"), 0.001)
        // 完整地址和只写楼名算同一地点
        assertEquals(1.0, EventConflict.locationCover("知产楼1224", "知产楼"), 0.001)
        // 门牌号不同就是两个地点
        assertEquals(0.0, EventConflict.locationCover("A栋302", "A栋303"), 0.001)
    }
}
