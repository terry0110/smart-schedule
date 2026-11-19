package com.terry.smartschedule

import com.terry.smartschedule.notify.SegmentPicker
import com.terry.smartschedule.parse.DateTimeParser
import com.terry.smartschedule.parse.SegmentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 一批"差异很大"的输入撞出来的问题。
 *
 * 用例是刻意写歪的：HTML 标签、标签式前缀、长句子中间埋日期、群聊多条消息、
 * 24 点、全角数字、农历…… 目的不是验证已知行为，是把边界撞出来。
 * 这个文件里每一条都对应一个真实撞到的问题。
 */
class AdversarialInputTest {

    private val now = LocalDateTime.of(2026, 10, 24, 16, 10)

    private fun p(text: String): DateTimeParser.Result? = DateTimeParser.parse(text, now)

    private fun pick(vararg segs: String): DateTimeParser.Result? =
        SegmentParser.pick(segs.toList()) { p(it) }

    // ================= 撞出来的问题 1：HTML 标签进了标题 =================

    @Test
    fun `HTML 标签不能进标题`() {
        val r = p("<b>明天下午3点开会</b>")
        assertNotNull(r)
        assertEquals("开会", r!!.title)
        assertEquals(15, r.start.hour)
    }

    @Test
    fun `带属性的标签也能清掉`() {
        val r = p("<font color=\"red\">10月28日 14:00 交材料</font>")
        assertNotNull(r)
        assertFalse("标题里不该有尖括号：${r!!.title}", r.title.contains("<") || r.title.contains(">"))
        assertEquals(14, r.start.hour)
    }

    // ================= 撞出来的问题 2：真标题被当成发送人剥掉 =================

    @Test
    fun `面试通知不是发送人，是标题`() {
        // 以前 RE_SPEAKER 把"面试通知："当发送人前缀剥掉，标题只剩"A栋302"
        val r = p("面试通知：10月28日下午2点，A栋302")
        assertNotNull(r)
        assertTrue("标题该是「面试通知…」，实际：${r!!.title}", r.title.contains("面试通知"))
        assertEquals(14, r.start.hour)
    }

    @Test
    fun `其它标签式前缀同样保留`() {
        for ((raw, must) in listOf(
            "活动安排：10月28日 19:00 开始" to "活动安排",
            "会议提醒：明天上午9点 例会" to "会议提醒",
            "【公告】主题：10月30日 换届选举" to "主题"
        )) {
            val r = p(raw)
            assertNotNull("解析不出：$raw", r)
            assertTrue("标题该含「$must」，实际：${r!!.title}", r.title.contains(must))
        }
    }

    @Test
    fun `真正的发送人前缀还是要剥掉`() {
        // 这条不能因为上面那条改动而退化
        val r = p("张三：明天下午3点在体育馆开会")
        assertNotNull(r)
        assertFalse("标题不该带人名：${r!!.title}", r.title.startsWith("张三"))
        assertEquals("体育馆", r.location)
    }

    // ================= 撞出来的问题 3：挖地点把句子挖碎了 =================

    @Test
    fun `长句子里的地点不挖`() {
        // 以前标题是「您的快递已 ，请前取件」——"到菜鸟驿站"被当地点抽走，句子散了
        val r = p("您的快递已到菜鸟驿站，请于10月25日前取件")
        assertNotNull(r)
        assertEquals(25, r!!.start.dayOfMonth)
        assertTrue("地点仍然要抽出来", r.location == "菜鸟驿站")
        assertTrue(
            "标题该保留完整句子，实际：${r.title}",
            r.title.contains("菜鸟驿站") && r.title.contains("取件")
        )
        assertFalse("标题不该有双空格", r.title.contains("  "))
    }

    // ================= 撞出来的问题 4：群聊标题拼接 =================

    @Test
    fun `群聊多条消息时标题只取第一条完整的`() {
        val segs = SegmentPicker.pick(
            bodies = emptyList(),
            lines = emptyList(),
            messages = listOf(
                "张三：10月28日 14:00 年级大会，地点：大礼堂",
                "李四：收到",
                "王五：好的",
                "赵六：+1"
            )
        )
        val r = SegmentParser.pick(segs) { p(it) }
        assertNotNull(r)
        assertEquals(28, r!!.start.dayOfMonth)
        assertEquals(14, r.start.hour)
        assertTrue("标题该是「年级大会」，实际：${r.title}", r.title.contains("年级大会"))
        assertFalse("标题不该把别人的回复拼进来：${r.title}", r.title.contains("收到"))
        assertFalse("标题不该带别人的名字：${r.title}", r.title.contains("李四"))
    }

    @Test
    fun `时间在一条、事件在另一条时仍然能拼`() {
        // 群聊标题变干净不能牺牲这个场景
        val r = pick("明天下午3点", "部门开会")
        assertNotNull(r)
        assertEquals(25, r!!.start.dayOfMonth)
        assertEquals(15, r.start.hour)
        assertTrue("实际：${r.title}", r.title.contains("开会"))
    }

    // ================= 边界确认（这些本来就对，固定住别退化） =================

    @Test
    fun `二十四点算当天最后一分钟`() {
        val r = p("10月28日 24:00 截止")
        assertNotNull(r)
        assertEquals(28, r!!.start.dayOfMonth)
        assertEquals(23, r.start.hour)
        assertEquals(59, r.start.minute)
    }

    @Test
    fun `全角数字能认`() {
        val r = p("１０月２８日开会")
        assertNotNull(r)
        assertEquals(28, r!!.start.dayOfMonth)
    }

    @Test
    fun `时间区间取起点`() {
        val r = p("10月28日 14:00-17:00 培训")
        assertNotNull(r)
        assertEquals(14, r!!.start.hour)
    }

    @Test
    fun `农历认不出就不建`() {
        assertNull(p("正月初一 拜年"))
    }

    @Test
    fun `月底前这种模糊说法不瞎猜`() {
        assertNull(p("月底前把材料交了"))
        assertNull(p("年底前完成报销"))
    }

    @Test
    fun `全角标点和纯表情不会崩`() {
        assertNull(p("！！！？？？。。。"))
        assertNull(p("😀😀😀😀😀"))
    }

    @Test
    fun `超长正文不会崩且能取到日期`() {
        val long = "各位同学请注意，本周的安排有所调整。".repeat(20) + "具体时间：10月30日下午2点。"
        val r = p(long)
        assertNotNull(r)
        assertEquals(30, r!!.start.dayOfMonth)
        assertEquals(14, r.start.hour)
        assertTrue("标题不该超长：${r.title.length}", r.title.length <= 40)
    }
}
