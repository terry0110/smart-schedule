package com.terry.smartschedule

import com.terry.smartschedule.notify.CapturePolicy
import com.terry.smartschedule.parse.DateTimeParser
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「只有时间没有日期」必须被挡掉。
 *
 * 典型原文：「五点去吃饭」「三点开会」。这类文本里没有任何日期线索，
 * 日期只能是猜的（今天还是明天？过了就顺延）。
 *
 * 为什么不能像"周X"那样进待确认：用户没法确认出"哪天"来 —— 原文根本没有。
 * 把猜的结果摆出来让他核对，等于把猜的责任转交给他，而列表里多一条错日期
 * 比少一条更糟。
 *
 * 注意和"周X"的区别：「下周三」也是推的，但原文给了线索，所以它进待确认是对的。
 */
class CapturePolicyTest {

    private fun result(
        dateSource: DateTimeParser.DateSource,
        hasTime: Boolean = true
    ) = DateTimeParser.Result(
        title = "吃饭",
        startAt = LocalDateTime.of(2026, 10, 24, 17, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        hasTime = hasTime,
        location = null,
        confidence = 60,
        dateSource = dateSource
    )

    @Test
    fun `只有时间没有日期的结果被拒绝`() {
        val reason = CapturePolicy.rejectReason(result(DateTimeParser.DateSource.TIME_ONLY))
        assertNotNull("TIME_ONLY 必须被挡掉", reason)
        assert(reason!!.contains("没有日期"))
    }

    @Test
    fun `有明确日期的照常通过`() {
        assertNull(CapturePolicy.rejectReason(result(DateTimeParser.DateSource.ABS)))
    }

    @Test
    fun `相对日期通过（原文写了今天明天）`() {
        assertNull(CapturePolicy.rejectReason(result(DateTimeParser.DateSource.REL)))
    }

    @Test
    fun `只有周X的仍然走待确认，不在这里拦`() {
        // 「下周三」原文给了线索，该由置信度机制转成待确认，不是直接丢
        assertNull(CapturePolicy.rejectReason(result(DateTimeParser.DateSource.WEEK)))
    }

    @Test
    fun `只有几号的仍然走待确认，不在这里拦`() {
        assertNull(CapturePolicy.rejectReason(result(DateTimeParser.DateSource.DAY_ONLY)))
    }

    // ---------------- 端到端：原文进来，判断出去 ----------------

    private fun parse(text: String) = DateTimeParser.parse(
        text, LocalDateTime.of(2026, 10, 24, 15, 40)
    )

    private fun rejected(text: String): Boolean {
        val r = parse(text) ?: return true // 解析不出来也算没建
        return CapturePolicy.rejectReason(r) != null
    }

    @Test
    fun `五点去吃饭这种闲聊不建日程`() {
        assert(rejected("五点去吃饭"))
        assert(rejected("三点开会"))
        assert(rejected("晚上八点看电影"))
    }

    @Test
    fun `写了日期的照常建`() {
        assert(!rejected("明天下午3点开会"))
        assert(!rejected("10月25日 15:00 开会"))
        assert(!rejected("27号晚23:59 截止"))
        assert(!rejected("下周三晚上八点聚餐"))
    }
}
