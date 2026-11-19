package com.terry.smartschedule

import com.terry.smartschedule.notify.TextFilter
import com.terry.smartschedule.parse.DateTimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 真机上报上来的两类新误报：
 *
 * 1. 微信昵称被当成日期：`12.31lyd：今天天气不错`
 *    —— `12.31` 后面紧跟字母 `l`，点分日期正则没排除。
 * 2. 流量限额短信被当成日程：`…流量8.5GB…0.29元/MB…`
 *    —— `8.5` 被当成 8 月 5 日。
 *
 * 顺带处理了"只有模糊日期、没有任何事件"的闲聊（`阿伟：今天天气不错`）。
 */
class NicknameAndCarrierTest {

    private val now = LocalDateTime.of(2026, 10, 24, 12, 0)
    private fun p(s: String) = DateTimeParser.parse(s, now)

    // ---------- 昵称里的数字不能当日期 ----------

    @Test
    fun `昵称里的点分数字不算日期`() {
        // 昵称本身没有日期词，应当完全解析不出
        assertNull("12.31lyd 不是日期", p("12.31lyd"))
        assertNull(p("10-25abc 说：收到"))

        // 这条里还有"今天"，会被解析成今天 —— 但绝不该是 12 月 31 日
        val r = p("12.31lyd：今天天气不错")
        assertNotNull("有「今天」还能解析出相对日期", r)
        assertNotEquals("不该把昵称当 12 月 31 日", 12, r!!.start.monthValue)
    }

    @Test
    fun `正常的点分日期不受影响`() {
        // 修昵称问题时不能把它们一起干掉
        p("3.15 消费者权益日")?.let { assertEquals(3, it.start.monthValue) }
        p("12.31 期末答辩")?.let {
            assertEquals(12, it.start.monthValue)
            assertEquals(31, it.start.dayOfMonth)
        }
        p("10.25 交材料")?.let { assertEquals(10, it.start.monthValue) }
    }

    // ---------- 资费类短信 ----------

    @Test
    fun `流量和话费短信被判为日程之外`() {
        assertTrue(
            TextFilter.isNonScheduleNotice(
                "您本月已使用流量8.5GB，剩余1.5GB，超出部分按0.29元/MB计费"
            )
        )
        assertTrue(TextFilter.isNonScheduleNotice("您的套餐流量已用尽，请及时充值"))
        assertTrue(TextFilter.isNonScheduleNotice("本月话费账单已出，应还金额1580.00元"))
    }

    @Test
    fun `带真实时间的仍然当日程处理`() {
        // 「明天去营业厅充值」是日程，不能因为含"充值"就被拦掉
        assertFalse(TextFilter.isNonScheduleNotice("明天下午3点去营业厅交话费"))
        assertFalse(TextFilter.isNonScheduleNotice("后天上午去办套餐"))
    }

    // ---------- 闲聊判别 ----------

    @Test
    fun `只有模糊日期没有事情的按闲聊处理`() {
        assertTrue(TextFilter.isSmallTalk("阿伟：今天天气不错"))
        assertTrue(TextFilter.isSmallTalk("阿伟：今天好累"))
        assertTrue(TextFilter.isSmallTalk("明天又要上班了"))
        assertTrue(TextFilter.isSmallTalk("今晚吃什么"))
    }

    @Test
    fun `有明确时间点的不是闲聊`() {
        assertFalse(TextFilter.isSmallTalk("张三：明天下午3点开会"))
        assertFalse(TextFilter.isSmallTalk("明天9点半集合"))
        assertFalse(TextFilter.isSmallTalk("后天 14:00 面试"))
    }

    @Test
    fun `有事件词或动作的仍算日程`() {
        // 有动作"去"：这条虽然模糊，但确实是在说一件事
        assertFalse(TextFilter.isSmallTalk("李四：明天去北京"))
        // 有事件词"考试"
        assertFalse(TextFilter.isSmallTalk("明天考试"))
        // 有绝对日期
        assertFalse(TextFilter.isSmallTalk("12月31日交材料"))
    }

    @Test
    fun `没有日期词的完全不管`() {
        // 没有模糊日期，解析器本来也建不出日程，不该在这里耗
        assertFalse(TextFilter.isSmallTalk("天气不错"))
        assertFalse(TextFilter.isSmallTalk("哈哈哈"))
        assertFalse(TextFilter.isSmallTalk(""))
    }

    // ---------- 组合：完整走一遍 ----------

    @Test
    fun `昵称加闲聊整条不该产生日程`() {
        val text = "12.31lyd：今天天气不错"
        // 第一步：昵称里的数字不再被当成 12 月 31 日
        val r = p(text)
        assertNotEquals("昵称不该被当日期", 12, r?.start?.monthValue ?: 0)
        // 第二步：即使能识别"今天"，也会被闲聊规则拦下
        assertTrue(TextFilter.isSmallTalk(text))
    }

    @Test
    fun `带事件的昵称消息仍然能建`() {
        val r = p("张三：明天下午3点在体育馆开会")
        assertNotNull(r)
        assertEquals(15, r!!.start.hour)
        assertEquals("体育馆", r.location)
    }
}
