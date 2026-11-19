package com.terry.smartschedule

import com.terry.smartschedule.parse.DateTimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 只有"几号"、没写月份的日期。
 *
 * 起因是真机截图：原文 `Yore: 截止到27号晚23:59` 被建成 **今天（10月24日）23:59**，
 * 地点还变成了「27号晚23:59」。当时解析器只认"月+日"（`10月25日` / `10.25`），
 * 认不出「27号」——而这是口语里最常见的写法。
 */
class DayOnlyDateTest {

    /** 2026-10-24 是周六，当月还剩 7 天 */
    private val now = LocalDateTime.of(2026, 10, 24, 15, 40)

    private fun p(text: String): DateTimeParser.Result? = DateTimeParser.parse(text, now)

    // ---------- 截图那条 ----------

    @Test
    fun `截止到27号晚23点59`() {
        val r = p("Yore: 截止到27号晚23:59")
        assertNotNull(r)
        assertEquals("日期必须是 27 号，不是今天", 27, r!!.start.dayOfMonth)
        assertEquals(10, r.start.monthValue)
        assertEquals(23, r.start.hour)
        assertEquals(59, r.start.minute)
        assertEquals(DateTimeParser.DateSource.DAY_ONLY, r.dateSource)
        assertEquals("标题应该是「截止」", "截止", r.title)
        assertNull("时间不该被当成地点", r.location)
    }

    @Test
    fun `日期和时间连写也能认出来`() {
        // 「27号23:59」中间没有空格，"号"后面紧跟数字
        val r = p("截止到27号23:59")
        assertNotNull(r)
        assertEquals(27, r!!.start.dayOfMonth)
        assertEquals(23, r.start.hour)
        assertEquals("截止", r.title)
    }

    // ---------- 月份推断 ----------

    @Test
    fun `本月的日子直接用本月`() {
        val r = p("27号交材料")
        assertNotNull(r)
        assertEquals(10, r!!.start.monthValue)
        assertEquals(27, r.start.dayOfMonth)
        assertTrue("本月还没到，不该弹警告", r.warnings.isEmpty())
    }

    @Test
    fun `当月最后一天也行`() {
        val r = p("31号截止")
        assertNotNull(r)
        assertEquals(10, r!!.start.monthValue)
        assertEquals(31, r.start.dayOfMonth)
    }

    @Test
    fun `已经过去的日子顺延到下个月并提示`() {
        // 今天 24 号，说"15号"指的是下个月（说本月的话已经过了）
        val r = p("15号下午3点开会")
        assertNotNull(r)
        assertEquals("应该是 11 月", 11, r!!.start.monthValue)
        assertEquals(15, r.start.dayOfMonth)
        assertEquals(15, r.start.hour)
        assertTrue("顺延到下个月要提示用户", r.warnings.any { it.contains("15号") })
        assertTrue("要进待确认，不能默默入库", r.needsConfirm)
    }

    @Test
    fun `今天当天不算过去`() {
        // 24 号说"24号"，就是今天
        val r = p("24号晚上8点开会")
        assertNotNull(r)
        assertEquals(24, r!!.start.dayOfMonth)
        assertEquals(20, r.start.hour)
    }

    // ---------- 别误伤地点 ----------

    @Test
    fun `号字后面接量词的不是日期`() {
        // 这几个如果被当成日期，会建出莫名其妙的日程
        assertNull(p("3号线地铁站见面"))
        assertNull(p("5号楼302开会"))
        assertNull(p("8号厅集合"))
    }

    @Test
    fun `本月这种写法也认`() {
        val r = p("本月27号前提交")
        assertNotNull(r)
        assertEquals(27, r!!.start.dayOfMonth)
        assertEquals(10, r.start.monthValue)
    }

    // ---------- 不能把已有的写法挤掉 ----------

    @Test
    fun `写了月份就还是按绝对日期处理`() {
        // RE_MD 在 RE_DAY_ONLY 之前，不能因为新规则把优先级搞乱
        val r = p("10月25日 14:00 开会")
        assertNotNull(r)
        assertEquals(DateTimeParser.DateSource.ABS, r!!.dateSource)
        assertEquals(25, r.start.dayOfMonth)
        assertEquals(14, r.start.hour)
        assertEquals("开会", r.title)
    }

    @Test
    fun `只有号没有别的，标题只能是它自己`() {
        // 没有事件名可取，这是原文信息量决定的，不算错
        val r = p("27号")
        assertNotNull(r)
        assertEquals(27, r!!.start.dayOfMonth)
    }

    // ---------- 顺带修的：地点不该吞掉时间 ----------

    @Test
    fun `地点里有号字也不能被日期规则带偏`() {
        // 这条的时间是"明天上午9点到12点"，地点是"体育馆"。
        // 之前候选区间横跨时间时会把地点一起丢掉。
        val r = p("明天上午9点到12点在体育馆体检")
        assertNotNull(r)
        assertEquals("体育馆", r!!.location)
    }

    @Test
    fun `时间段落不能被当成地点`() {
        val r = p("会议改到16:30")
        if (r != null && r.hasTime) {
            assertTrue("地点被抽成了时间：${r.location}", r.location?.contains(":") != true)
        }
    }
}
