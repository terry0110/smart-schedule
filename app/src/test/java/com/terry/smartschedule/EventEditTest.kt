package com.terry.smartschedule

import com.terry.smartschedule.data.EventEdit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑日程时"完成状态要不要复位"的判断。
 *
 * 这条错了用户会很困惑：只改了个错别字，勾好的日程突然变回未完成。
 */
class EventEditTest {

    private val t0 = 1_700_000_000_000L
    private val t1 = t0 + 60 * 60_000L

    @Test
    fun `时间和模式都没变时不重置完成状态`() {
        // 只改了标题或地点，时间一模一样
        assertFalse(EventEdit.shouldResetDone(t0, true, t0, true))
    }

    @Test
    fun `时间点变了要重置`() {
        // 推迟到另一个时刻，说明这件事还没发生，原来的勾失效
        assertTrue(EventEdit.shouldResetDone(t0, true, t1, true))
    }

    @Test
    fun `全天与定时的切换也算时间变了`() {
        // 同一毫秒，但一个算全天一个算定时，语义上已经是不同的安排了
        assertTrue(EventEdit.shouldResetDone(t0, true, t0, false))
        assertTrue(EventEdit.shouldResetDone(t0, false, t0, true))
    }

    @Test
    fun `全天改全天不变时不重置`() {
        assertFalse(EventEdit.shouldResetDone(t0, false, t0, false))
    }
}
