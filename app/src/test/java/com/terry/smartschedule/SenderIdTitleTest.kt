package com.terry.smartschedule

import com.terry.smartschedule.notify.SegmentPicker
import com.terry.smartschedule.notify.TextFilter
import com.terry.smartschedule.parse.DateTimeParser
import com.terry.smartschedule.parse.SegmentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * QQ 通知会把发送人的号码当成标题，结果日程标题变成一串数字。
 *
 * 根因是 `extractSegments` 把 `EXTRA_TITLE` 也当成了候选段 —— 而聊天类通知的
 * 标题是"会话名"（群名 / 联系人昵称 / 对方的 QQ 号），根本不是日程内容。
 *
 * 两层修复：有 MessagingStyle 消息时不把标题当候选；所有段再过一遍"像不像账号"。
 */
class SenderIdTitleTest {

    private val now = LocalDateTime.of(2026, 10, 24, 10, 0)
    private fun p(s: String) = DateTimeParser.parse(s, now)

    // ---------- 账号识别 ----------

    @Test
    fun `纯数字账号被识别为发送人`() {
        assertTrue(TextFilter.looksLikeSenderId("123456789"))
        assertTrue(TextFilter.looksLikeSenderId("123456"))
        assertTrue(TextFilter.looksLikeSenderId("  87654321  "))
    }

    @Test
    fun `日期和年份不能被误判成账号`() {
        // 这几个要是被当成账号丢掉，会直接损失信息
        assertFalse(TextFilter.looksLikeSenderId("3.15"))
        assertFalse(TextFilter.looksLikeSenderId("2026"))
        assertFalse(TextFilter.looksLikeSenderId("12345"))
        assertFalse(TextFilter.looksLikeSenderId("10.25"))
    }

    @Test
    fun `带前缀的账号`() {
        assertTrue(TextFilter.looksLikeSenderId("用户12345678"))
        assertTrue(TextFilter.looksLikeSenderId("wxid_ndadocjl41io22"))
        assertTrue(TextFilter.looksLikeSenderId("abc123"))
    }

    @Test
    fun `中文昵称和正常标题不算账号`() {
        // 昵称没法可靠区分，不动它；这里只保证"正常标题"不被误杀
        assertFalse(TextFilter.looksLikeSenderId("张三"))
        assertFalse(TextFilter.looksLikeSenderId("明天下午3点开会"))
        assertFalse(TextFilter.looksLikeSenderId("部门例会"))
        assertFalse(TextFilter.looksLikeSenderId("Meeting"))
    }

    @Test
    fun `长文件名不算账号`() {
        // 超过 24 字符直接排除，避免把 "smart-schedule-v1.8-debug.apk" 当账号
        assertFalse(TextFilter.looksLikeSenderId("smart-schedule-v1.8-debug.apk"))
    }

    // ---------- 过滤之后标题是干净的 ----------

    @Test
    fun `只有正文参与，标题里的 QQ 号进不来`() {
        // 真实链路：service 层只读正文，EXTRA_TITLE（这里的 QQ 号）根本不传进来
        val segments = SegmentPicker.pick(
            bodies = listOf("张三：明天下午3点在体育馆开会"),
            lines = emptyList(),
            messages = emptyList()
        )

        val r = SegmentParser.pick(segments) { p(it) }
        assertNotNull("正文应能独立解析出日程", r)
        assertFalse("标题不该带 QQ 号，实际：${r!!.title}", r.title.contains("123456789"))
        assertEquals("体育馆", r.location)
    }

    @Test
    fun `不过滤的话号码确实会污染标题`() {
        // 固定根因：把号码和正文拼一起，标题就会被弄脏。
        // 哪天解析器能自动剥离这段前缀，这个测试会失败 —— 那时可以简化 service 层逻辑。
        val r = SegmentParser.pick(listOf("123456789 明天下午3点开会")) { p(it) }
        assertNotNull(r)
        assertTrue(
            "预期号码会进标题，实际：${r!!.title}",
            r.title.contains("123456789")
        )
    }
}
