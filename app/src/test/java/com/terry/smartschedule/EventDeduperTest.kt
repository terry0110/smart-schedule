package com.terry.smartschedule

import com.terry.smartschedule.data.Event
import com.terry.smartschedule.data.EventDeduper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「清理重复日程」的分组。
 *
 * 对应真实截图：同一条盖章通知在库里留下全天截断版、带「现有变」前缀版、完整版。
 * 清理必须敢合并这三条，但绝不能把每天/每周的例会当成重复。
 */
class EventDeduperTest {

    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, month, day, hour, minute)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun created(hour: Int): Long = at(11, 19, hour)

    private fun event(
        id: Long,
        title: String = "最近已经跟贺老师请过假需要签假条的同学",
        startAt: Long = at(11, 21, 15),
        hasTime: Boolean = true,
        location: String? = "知产楼1224",
        createdAt: Long = created(10),
        archived: Boolean = false,
        done: Boolean = false
    ) = Event(
        id = id,
        title = title,
        startAt = startAt,
        hasTime = hasTime,
        location = location,
        createdAt = createdAt,
        archived = archived,
        done = done
    )

    @Test
    fun `同一次通知留下的三条会归成一组`() {
        val allDay = event(
            id = 1,
            title = "最近已经跟贺老师请过假需要签假条的同学，可以在",
            startAt = at(11, 21, 9),
            hasTime = false,
            location = null,
            createdAt = created(10)
        )
        val prefix = event(id = 2, title = "现有变，最近已经跟贺老师请过假需要签假条的同学", createdAt = created(10))
        val full = event(id = 3, createdAt = created(11))
        val groups = EventDeduper.group(listOf(allDay, prefix, full))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].drop.size)
        // 优先保留带具体时间的、最新的
        assertEquals(3L, groups[0].keep.id)
    }

    @Test
    fun `隔一天的旧版和新版也会归组，保留最新创建的那条`() {
        val old = event(id = 1, startAt = at(11, 21, 15), createdAt = created(9))
        val new = event(id = 2, startAt = at(11, 22, 15), createdAt = created(11))
        val groups = EventDeduper.group(listOf(old, new))
        assertEquals(1, groups.size)
        assertEquals(2L, groups[0].keep.id)
    }

    @Test
    fun `每天例会创建时间差一天 不合并`() {
        val day1 = event(id = 1, startAt = at(11, 21, 9), createdAt = at(11, 20, 9))
        val day2 = event(id = 2, startAt = at(11, 22, 9), createdAt = at(11, 21, 9))
        assertTrue(EventDeduper.group(listOf(day1, day2)).isEmpty())
    }

    @Test
    fun `同一天同标题但时间差两小时 不合并`() {
        val a = event(id = 1, startAt = at(11, 21, 15))
        val b = event(id = 2, startAt = at(11, 21, 17))
        assertTrue(EventDeduper.group(listOf(a, b)).isEmpty())
    }

    @Test
    fun `地点冲突不合并`() {
        val a = event(id = 1, location = "A栋")
        val b = event(id = 2, location = "B栋")
        assertTrue(EventDeduper.group(listOf(a, b)).isEmpty())
    }

    @Test
    fun `短标题不靠子串合并`() {
        val a = event(id = 1, title = "开会")
        val b = event(id = 2, title = "开会讨论")
        assertTrue(EventDeduper.group(listOf(a, b)).isEmpty())
    }

    @Test
    fun `已归档和已完成的不参与清理`() {
        val archived = event(id = 1, archived = true)
        val done = event(id = 2, done = true)
        val full = event(id = 3)
        val groups = EventDeduper.group(listOf(archived, done, full))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `不同标题不合并`() {
        val a = event(id = 1, title = "最近已经跟贺老师请过假需要签假条的同学")
        val b = event(id = 2, title = "部门周会")
        assertFalse(EventDeduper.similar(a, b))
    }

    @Test
    fun `删除前会把被删条目的独有地点和备注补到保留项`() {
        val keep = event(id = 1, location = null).copy(note = null)
        val drop = event(id = 2, location = "知产楼1224").copy(note = "原始通知全文")
        val merged = EventDeduper.mergeKeep(EventDeduper.Group(keep, listOf(drop)))
        assertEquals("知产楼1224", merged.location)
        assertEquals("原始通知全文", merged.note)
    }

    @Test
    fun `保留项已有的地点和备注不被覆盖`() {
        val keep = event(id = 1, location = "A栋").copy(note = "保留项原文")
        val drop = event(id = 2, location = "B栋").copy(note = "被删项原文")
        val merged = EventDeduper.mergeKeep(EventDeduper.Group(keep, listOf(drop)))
        assertEquals("A栋", merged.location)
        assertEquals("保留项原文", merged.note)
    }

    @Test
    fun `创建时间拉成链的三条也能归成一组`() {
        // A≈B、B≈C，但 A 与 C 的创建时间差了 22 小时，不再直接相似。
        // 以 A 为锚的贪心会把 C 漏掉；并查集应该把三条连成一个分量。
        val a = event(id = 1, startAt = at(11, 21, 15), createdAt = created(0))
        val b = event(id = 2, startAt = at(11, 22, 15), createdAt = created(11))
        val c = event(id = 3, startAt = at(11, 23, 15), createdAt = created(22))

        val groups = EventDeduper.group(listOf(a, b, c))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].drop.size)
        assertEquals(3L, groups[0].keep.id)

        // 结果不能依赖列表顺序
        val reversed = EventDeduper.group(listOf(c, b, a))
        assertEquals(1, reversed.size)
        assertEquals(2, reversed[0].drop.size)
        assertEquals(3L, reversed[0].keep.id)
    }

    @Test
    fun `弹窗没变化时链式重复的每一条都能删`() {
        val a = event(id = 1, startAt = at(11, 21, 15), createdAt = created(0))
        val b = event(id = 2, startAt = at(11, 22, 15), createdAt = created(11))
        val c = event(id = 3, startAt = at(11, 23, 15), createdAt = created(22))
        val group = EventDeduper.group(listOf(a, b, c)).single()
        val current = listOf(a, b, c).associateBy { it.id }
        assertEquals(2, EventDeduper.dropsToDelete(group, current).size)
    }

    @Test
    fun `弹窗期间被改到别处的条目不再删`() {
        val old = event(id = 1, startAt = at(11, 21, 15), createdAt = created(9))
        val new = event(id = 2, startAt = at(11, 22, 15), createdAt = created(11))
        val group = EventDeduper.group(listOf(old, new)).single()
        assertEquals(2L, group.keep.id)

        val moved = old.copy(startAt = at(12, 2, 15)) // 超过 3 天，已经不是同一组
        val current = mapOf(1L to moved, 2L to new)
        assertTrue(EventDeduper.dropsToDelete(group, current).isEmpty())
    }
}
