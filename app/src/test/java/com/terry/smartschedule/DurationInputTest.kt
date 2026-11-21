package com.terry.smartschedule

import com.terry.smartschedule.util.DurationInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 「其它，请输入」的输入口径：合法写法、拒绝写法、回显。 */
class DurationInputTest {

    private fun min(n: Long) = n * 60_000L

    @Test
    fun `纯数字按分钟算`() {
        assertEquals(min(90), DurationInput.parse("90"))
        assertEquals(min(5), DurationInput.parse(" 5 "))
    }

    @Test
    fun `中文写法`() {
        assertEquals(min(90), DurationInput.parse("1小时30分钟"))
        assertEquals(min(60), DurationInput.parse("1小时"))
        assertEquals(min(30), DurationInput.parse("30分"))
        assertEquals(min(80), DurationInput.parse("1个小时20分钟"))
    }

    @Test
    fun `英文写法不区分大小写`() {
        assertEquals(min(120), DurationInput.parse("2h"))
        assertEquals(min(90), DurationInput.parse("1H30m"))
        assertEquals(min(90), DurationInput.parse("90m"))
        assertEquals(min(45), DurationInput.parse("45M"))
    }

    @Test
    fun `分钟超60照样累加而不是拒绝`() {
        assertEquals(min(150), DurationInput.parse("2小时30分钟"))
        assertEquals(min(150), DurationInput.parse("150分钟"))
    }

    @Test
    fun `零负数空串和乱码一律拒绝`() {
        assertNull(DurationInput.parse("0"))
        assertNull(DurationInput.parse("-5"))
        assertNull(DurationInput.parse(""))
        assertNull(DurationInput.parse("   "))
        assertNull(DurationInput.parse("abc"))
        assertNull(DurationInput.parse("1.5小时"))
        assertNull(DurationInput.parse("1小时30秒"))
        assertNull(DurationInput.parse("小时"))
    }

    @Test
    fun `超过七天拒绝`() {
        assertNull(DurationInput.parse("10081"))
        assertEquals(DurationInput.MAX_MINUTES * 60_000L, DurationInput.parse("10080"))
    }

    @Test
    fun `回显与解析互补`() {
        assertEquals("全天", DurationInput.label(0L))
        assertEquals("15分钟", DurationInput.label(min(15)))
        assertEquals("1小时", DurationInput.label(min(60)))
        assertEquals("1小时30分钟", DurationInput.label(min(90)))
        assertEquals(min(90), DurationInput.parse(DurationInput.label(min(90))))
    }
}
