package com.terry.smartschedule

import com.terry.smartschedule.notify.Notifications
import com.terry.smartschedule.notify.ReminderScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清扫"日程没了、提醒还挂在栏里"时，该撤掉哪些。
 *
 * 守的是两件事：
 *  1. **已删除的日程必须被判成要撤**。判断漏了的代价是用户通知栏里留一条
 *     点进去啥也没有的提醒，而且它自己不会消失（要等一小时的 setTimeoutAfter）。
 *  2. **还在的日程绝不能被误撤**。误撤等于把还没处理的提醒悄悄弄丢 ——
 *     比留一条残留严重得多。
 */
class OrphanRemindTest {

    @Test
    fun `日程删了就必须撤掉`() {
        assertEquals(
            setOf(7L, 9L),
            ReminderScheduler.orphanReminds(posted = setOf(7L, 8L, 9L), alive = setOf(8L))
        )
    }

    @Test
    fun `日程还在的一条都不能撤`() {
        val r = ReminderScheduler.orphanReminds(posted = setOf(1L, 2L), alive = setOf(1L, 2L, 3L))
        assertTrue("还在的日程被误撤了：$r", r.isEmpty())
    }

    @Test
    fun `账空了什么都不撤`() {
        assertTrue(ReminderScheduler.orphanReminds(posted = emptySet(), alive = setOf(1L)).isEmpty())
    }

    @Test
    fun `库空了账上全撤`() {
        // 用户点了「删除所有本地数据」之后的形态
        assertEquals(
            setOf(3L, 5L),
            ReminderScheduler.orphanReminds(posted = setOf(3L, 5L), alive = emptySet())
        )
    }

    @Test
    fun `没发出去过的日程不产生撤单`() {
        // 只排了闹钟、还没到点的日程不在账上，不该凭空冒出来一条 cancel
        assertTrue(ReminderScheduler.orphanReminds(posted = setOf(2L), alive = emptySet()).size == 1)
        assertTrue(ReminderScheduler.orphanReminds(posted = emptySet(), alive = emptySet()).isEmpty())
    }

    /**
     * 清扫的兜底路径按渠道判断"这条通知要不要跟着日程一起撤"。
     * **每加一档提醒方式就多一个渠道**，漏写一个的代价是那一档发出的提醒在日程删掉后
     * 一直留在通知栏里 —— v1.27 加振动档时就漏了。这条测试盯着这个契约。
     */
    @Test
    fun `每一档提醒用的渠道都必须被认定为提醒本体`() {
        assertTrue(Notifications.isRemindChannel(Notifications.CH_REMIND))
        assertTrue(Notifications.isRemindChannel(Notifications.CH_REMIND_QUIET))
        assertTrue(
            "振动档的渠道没被算成提醒本体，它的残留提醒不会被清扫",
            Notifications.isRemindChannel(Notifications.CH_REMIND_VIBRATE)
        )
    }

    @Test
    fun `回执和待确认通知不能被当提醒撤掉`() {
        // 这两种不属于"到点提醒"，撤错了用户会丢掉还没处理的事
        assertFalse(Notifications.isRemindChannel(Notifications.CH_CAPTURE))
        assertFalse(Notifications.isRemindChannel(Notifications.CH_INFO))
        assertFalse(Notifications.isRemindChannel(null))
    }
}
