package com.terry.smartschedule

import com.terry.smartschedule.notify.NotificationIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知 id 和按钮 requestCode 的唯一性。
 *
 * 这两个数字撞了的后果是"确认了 A，结果 B 变了" —— 从通知栏完全看不出来，
 * 所以必须在这里固定住。
 */
class NotificationIdsTest {

    @Test
    fun `不同日程的待确认通知 id 不重复`() {
        val ids = (1L..50L).map { NotificationIds.pending(it) }
        assertEquals("50 条日程应该产生 50 个不同的通知 id", 50, ids.toSet().size)
    }

    @Test
    fun `待确认通知 id 不会撞上提醒通知`() {
        // 提醒通知直接用 eventId 当 id，所以待确认必须偏移开
        for (id in 1L..50L) {
            assertNotEquals(
                "待确认通知(${id}) 不能和提醒通知撞",
                id.toInt(),
                NotificationIds.pending(id)
            )
        }
    }

    @Test
    fun `同一条日程的两个按钮 requestCode 不同`() {
        // slot 1 = 确认，slot 2 = 删除
        for (id in 1L..20L) {
            assertNotEquals(
                "日程 $id 的确认和删除按钮不能共用一个 requestCode",
                NotificationIds.actionRequestCode(id, 1),
                NotificationIds.actionRequestCode(id, 2)
            )
        }
    }

    @Test
    fun `不同日程的同名按钮 requestCode 不同`() {
        // 这是最关键的一条：撞了就会"点 A 改了 B"
        val codes = (1L..50L).map { NotificationIds.actionRequestCode(it, 1) }
        assertEquals("50 条日程的确认按钮应该各不重复", 50, codes.toSet().size)
    }

    @Test
    fun `改期确认通知 id 不和回执撞`() {
        // 两个固定号如果撞了，一条会覆盖另一条，用户只会看到最后那条
        assertNotEquals(
            "回执和改期确认不能用同一个 id",
            NotificationIds.CAPTURE_SUMMARY_ID,
            NotificationIds.REVISE_ASK_ID
        )
        assertTrue(NotificationIds.REVISE_ASK_ID > 0)
    }

    @Test
    fun `id 再大也不会变成负数`() {
        // 负数会被系统当成"没有 requestCode"，行为不可预期
        val bigs = listOf(1L, 100_000L, 2_147_483_647L, Long.MAX_VALUE)
        for (id in bigs) {
            assertTrue("pending($id) 不该为负", NotificationIds.pending(id) >= 0)
            assertTrue("action($id) 不该为负", NotificationIds.actionRequestCode(id, 2) >= 0)
        }
    }
}
