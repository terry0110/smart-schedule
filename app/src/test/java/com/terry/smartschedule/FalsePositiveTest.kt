package com.terry.smartschedule

import com.terry.smartschedule.notify.TextFilter
import com.terry.smartschedule.parse.DateTimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 真机上出现过的误报：下载完成通知被建成了日程。
 *
 * 原文是「smart-schedule-v1.8-debug.apk.1 已保存至"文件管理/安装包"，点击查看。」
 * 根因是 `v1.8` 里的 `1.8` 被当成 1 月 8 日，于是标题变成 "smart-schedule-v -debug"，
 * 还因为"1 月 8 日今年过了"自动推到明年，进了一条待确认日程。
 *
 * 这里从两层锁住：解析器不再把版本号当日期 + 通知层过滤文件类通知。
 */
class FalsePositiveTest {

    private val now = LocalDateTime.of(2026, 10, 24, 2, 0)
    private fun p(s: String) = DateTimeParser.parse(s, now)

    // ---------- 第一层：解析器不该把版本号/文件名里的数字当日期 ----------

    @Test
    fun `版本号里的数字不算日期`() {
        assertNull("v1.8 不该被解析成日期", p("smart-schedule-v1.8-debug.apk.1"))
        assertNull(p("版本 2.0 就要发布了"))
        assertNull(p("v1.2.3 更新完成"))
    }

    @Test
    fun `含版本号的完整下载通知也解析不出日期`() {
        val text = """smart-schedule-v1.8-debug.apk.1 已保存至"文件管理/安装包"，点击查看。"""
        assertNull("这条是下载完成通知，不是日程", p(text))
    }

    @Test
    fun `点分日期的正常写法不能被误伤`() {
        // 这几条以前能用，修版本号问题时不能把它们一起干掉
        p("3.15 消费者权益日")?.let { assertEquals(3, it.start.monthValue) }
        p("11.11 购物节开始")?.let { assertEquals(11, it.start.monthValue) }
        p("2026.10.25 考试")?.let {
            assertEquals(2026, it.start.year)
            assertEquals(10, it.start.monthValue)
            assertEquals(25, it.start.dayOfMonth)
        }
    }

    @Test
    fun `日期区间写法不受影响`() {
        // 1.8-1.10：后面是"-数字"不是"-字母"，不该被当成版本号排除
        val r = p("1.8-1.10 期间闭馆")
        assertNotNull("区间起始日仍应能解析", r)
        assertEquals(1, r!!.start.monthValue)
        assertEquals(8, r.start.dayOfMonth)
    }

    // ---------- 第二层：通知层的语义过滤 ----------

    @Test
    fun `文件与系统类通知要识别出来`() {
        assertTrue(TextFilter.isNonScheduleNotice("""smart-schedule-v1.8-debug.apk.1 已保存至"文件管理/安装包"，点击查看。"""))
        assertTrue(TextFilter.isNonScheduleNotice("下载完成：10月25日会议纪要.docx"))
        assertTrue(TextFilter.isNonScheduleNotice("周报.docx 已保存"))
        assertTrue(TextFilter.isNonScheduleNotice("安装包已就绪，点击安装"))
        assertTrue(TextFilter.isNonScheduleNotice("电池电量低，请及时充电"))
    }

    @Test
    fun `带真实时间表达的仍然当日程处理`() {
        // 「明天下午3点前把报告.docx发我」是真的日程，不能因为带 .docx 就被丢掉
        assertFalse(TextFilter.isNonScheduleNotice("明天下午3点前把报告.docx发我"))
        assertFalse(TextFilter.isNonScheduleNotice("周五之前把周报.docx交上去"))
        // 没有文件特征的普通通知更不该被拦
        assertFalse(TextFilter.isNonScheduleNotice("明天上午9点开会"))
        assertFalse(TextFilter.isNonScheduleNotice("微信 3 条新消息"))
        assertFalse(TextFilter.isNonScheduleNotice("11.11 购物节开始"))
    }
}
