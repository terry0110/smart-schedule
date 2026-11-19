package com.terry.smartschedule

import com.terry.smartschedule.notify.SegmentPicker
import com.terry.smartschedule.notify.TextFilter
import com.terry.smartschedule.parse.DateTimeParser
import com.terry.smartschedule.parse.SegmentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * **只从正文识别，标题永不参与**。
 *
 * 这条规则现在是**编译期保证**：[SegmentPicker.pick] 压根没有 title 参数，
 * 所以"群名 `12.31` 被当成日期"这类问题不可能再出现 —— 那个字段进不来。
 *
 * 测试验证的是另外两件事：
 *  1. 正文能独立解析出正确时间（不再依赖标题补充信息）
 *  2. 正文为空、只有标题有内容时，确实什么都得不到 —— 这是这条规则的代价，不是 bug
 */
class TitleNotUsedTest {

    /** 2026-10-24 是周六 */
    private val now = LocalDateTime.of(2026, 10, 24, 14, 30)

    private fun parse(bodies: List<String?> = emptyList(),
                      lines: List<String?> = emptyList(),
                      messages: List<String> = emptyList()) =
        SegmentParser.pick(SegmentPicker.pick(bodies, lines, messages)) {
            DateTimeParser.parse(it, now)
        }

    // ---------- 正文能独立解析 ----------

    @Test
    fun `正文里的时间正常解析，不需要标题`() {
        val r = parse(bodies = listOf("明天下午3点开会"))
        assertNotNull(r)
        assertEquals(10, r!!.start.monthValue)
        assertEquals(25, r.start.dayOfMonth)
        assertEquals(15, r.start.hour)
        assertTrue(r.hasTime)
    }

    @Test
    fun `带绝对日期的正文也正常`() {
        val r = parse(bodies = listOf("11月5日 14:00 在行政楼开会"))
        assertNotNull(r)
        assertEquals(11, r!!.start.monthValue)
        assertEquals(5, r.start.dayOfMonth)
        assertEquals(14, r.start.hour)
    }

    // ---------- 群名不会再提供日期 ----------

    @Test
    fun `群名 12点31 那个群的闲聊不因群名获得日期`() {
        // 正文完全没有日期 -> 整条建不出来
        assertNull(parse(bodies = listOf("哈哈哈")))

        // 「今晚谁去吃饭」里有"今晚"，解析成今晚是对的（这条消息算不算日程是
        // TextFilter.isSmallTalk 的职责，不在本测试范围）。关键是**不能是 12 月 31 日**。
        val r = parse(bodies = listOf("今晚谁去吃饭"))
        assertNotEquals("不该取群名的 12.31", 12, r?.start?.monthValue ?: 0)
    }

    @Test
    fun `群名 12点31 那个群的真日程，日期取自正文`() {
        // 以前会建成 2026-12-31 15:00（群名盖掉正文），现在是正文写的明天
        val r = parse(bodies = listOf("明天下午3点开会"))
        assertNotNull(r)
        assertEquals("必须取正文写的明天", 25, r!!.start.dayOfMonth)
        assertEquals(10, r.start.monthValue)
    }

    // ---------- 代价：只有标题有内容时会漏 ----------

    @Test
    fun `正文为空时什么都不建`() {
        // 这是"标题不参与"的代价。微信/QQ 的真消息一定在正文里，
        // 不存在"只有标题有内容"的通知，所以实际影响接近零。
        assertNull(parse())
        assertNull(parse(bodies = listOf(null, "", "   ")))
    }

    // ---------- MessagingStyle / 多行 / 去重 ----------

    @Test
    fun `消息体优先使用，发送者前缀不影响取时间`() {
        val r = parse(messages = listOf("张三：明天下午3点开会", "李四：哈哈哈"))
        assertNotNull(r)
        assertEquals(25, r!!.start.dayOfMonth)
        assertEquals(15, r.start.hour)
    }

    @Test
    fun `多行文本也参与`() {
        val r = parse(lines = listOf("明天下午3点开会", "请带电脑"))
        assertNotNull(r)
        assertEquals(25, r!!.start.dayOfMonth)
    }

    @Test
    fun `多段去重且保持顺序`() {
        val segs = SegmentPicker.pick(
            bodies = listOf("明天下午3点开会", null, "明天下午3点开会", "请带电脑"),
            lines = emptyList(),
            messages = emptyList()
        )
        assertEquals(listOf("明天下午3点开会", "请带电脑"), segs)
    }

    // ---------- 正文里只剩账号的情况 ----------

    @Test
    fun `正文位置上的纯账号被丢掉`() {
        // 微信有时把对方微信号塞在正文位置
        assertNull(parse(bodies = listOf("123456789")))
        assertNull(parse(bodies = listOf("wxid_ndadocjl41io22")))
        // 但纯时间要保留：那是用户敲的待办，省略了动词
        assertNotNull(parse(bodies = listOf("明天下午3点")))
    }

    @Test
    fun `账号判断不误伤日期`() {
        assertFalse(TextFilter.looksLikeSenderId("3.15"))
        assertFalse(TextFilter.looksLikeSenderId("10.25"))
        assertFalse(TextFilter.looksLikeSenderId("2026"))
    }
}
