package com.terry.smartschedule

import com.terry.smartschedule.parse.DateTimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * 解析器回归测试。基准时间固定为 2026-10-23 14:05（周五），
 * 这样"明天""下周三"这类相对表达才有确定的期望值。
 *
 * 跑法：Android Studio 里右键本文件 → Run，或命令行 ./gradlew test
 */
class DateTimeParserTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 23, 14, 5)

    private fun assertParse(
        raw: String,
        expectStart: String,
        expectTitle: String? = null,
        expectLocation: String? = null,
        expectHasTime: Boolean = true
    ) {
        val r = DateTimeParser.parse(raw, now)
        assertNotNull("应该能解析：$raw", r)
        r!!
        val expect = LocalDateTime.parse(expectStart)
        assertEquals("时间不对：$raw", expect, r.start)
        assertEquals("hasTime 不对：$raw", expectHasTime, r.hasTime)
        if (expectTitle != null) assertEquals("标题不对：$raw", expectTitle, r.title)
        if (expectLocation != null) assertEquals("地点不对：$raw", expectLocation, r.location)
    }

    @Test
    fun `明天下午三点半 带地点`() {
        assertParse(
            "明天下午3点半在图书馆三楼开会",
            "2026-10-24T15:30",
            // 标题不再挖掉地点 —— 挖掉会把话挖断（"在图书馆三楼开会"比"开会"完整）
            expectTitle = "在图书馆三楼开会",
            expectLocation = "图书馆三楼"
        )
    }

    @Test
    fun `说话人前缀加后天到某地`() {
        assertParse(
            "张总：后天 14:00 到公司楼下集合",
            "2026-10-25T14:00",
            expectTitle = "到公司楼下集合",
            expectLocation = "公司楼下"
        )
    }

    @Test
    fun `通知标签加@地点`() {
        assertParse(
            "【提醒】10月25日上午9点 体检 @市一医院",
            "2026-10-25T09:00",
            expectTitle = "体检@市一医院",
            expectLocation = "市一医院"
        )
    }

    @Test
    fun `下周三晚上八点 地点冒号写法`() {
        assertParse(
            "下周三晚上8点 团队聚餐，地点：海底捞（万象城店）",
            "2026-10-28T20:00",
            expectTitle = "团队聚餐，海底捞",
            expectLocation = "海底捞（万象城店）"
        )
    }

    @Test
    fun `记得这种口语前缀要剥掉`() {
        assertParse("记得明天早上8点交实验报告", "2026-10-24T08:00", expectTitle = "交实验报告")
    }

    @Test
    fun `只有日期没有时间 视为全天`() {
        assertParse("11月5日 答辩", "2026-11-05T09:00", expectTitle = "答辩", expectHasTime = false)
    }

    @Test
    fun `N天后 加中午`() {
        assertParse("3天后的中午12点半 取快递", "2026-10-26T12:30", expectTitle = "取快递")
    }

    @Test
    fun `本周的周五 就是当天`() {
        assertParse("周五 18:30 羽毛球", "2026-10-23T18:30", expectTitle = "羽毛球")
    }

    @Test
    fun `没有任何时间信息 直接丢弃`() {
        assertNull(DateTimeParser.parse("会议室已预订", now))
        assertNull(DateTimeParser.parse("【中国移动】您本月流量已用尽，请及时充值", now))
    }

    @Test
    fun `只有时间 落到最近的未来时刻`() {
        // 基准是 14:05，下午3点还没到，应该是今天
        assertParse("下午3点 面试", "2026-10-23T15:00", expectTitle = "面试")
    }

    @Test
    fun `完整年月日格式`() {
        assertParse("2027/1/2 09:00 元旦值班", "2027-01-02T09:00", expectTitle = "元旦值班")
    }

    @Test
    fun `这个周日 加在字地点`() {
        assertParse(
            "这个周日晚上7点看电影 在万达影城",
            "2026-10-25T19:00",
            expectTitle = "看电影在万达影城",
            expectLocation = "万达影城"
        )
    }

    @Test
    fun `今晚N点前 前字要吃掉`() {
        assertParse("别忘了今晚10点前打卡", "2026-10-23T22:00", expectTitle = "打卡")
    }

    @Test
    fun `周日 加括号备注`() {
        assertParse("周日下午2点 小组会议（腾讯会议）", "2026-10-25T14:00", expectTitle = "小组会议")
    }

    @Test
    fun `时间后面跟的量词要吃掉`() {
        assertParse("明天 8:00 的高铁，别迟到", "2026-10-24T08:00", expectTitle = "高铁，别迟到")
    }
}
