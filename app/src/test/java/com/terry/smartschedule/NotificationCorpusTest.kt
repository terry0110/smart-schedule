package com.terry.smartschedule

import com.terry.smartschedule.parse.DateTimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 真实群通知语料回归测试（语料来源：《通知测试.docx》，2026-10 收集）。
 *
 * 这批语料暴露过 6 个真实缺陷，全部已修，留作防回归：
 *  1. `@所有人` 被当成地点（每条群通知都会中招）
 *  2. "活动时间"里的"时"被当作时间残渣删掉，标题变成"活动 间"
 *  3. 长通告的标题直接等于整段正文
 *  4. `10.19 24:00` 这种点分日期 + 24 点写法整条被丢弃
 *  5. `11-12月`（月份区间）被解析成 11月12日
 *  6. "时间地点另行通知"被识别成地点"另行通知"
 *
 * 基准时间固定为 2026-10-23 14:50（周五），保证可复现。
 */
class NotificationCorpusTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 23, 14, 50)
    private val dFmt = DateTimeFormatter.ofPattern("MM-dd")
    private val tFmt = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * @param wantTime "HH:mm"；"ALL" 表示只识别到日期、应为全天
     * @param wantLoc  null 表示不该识别出地点
     * @param wantTitleHas null 表示不校验标题
     */
    private fun check(
        id: String,
        raw: String,
        wantDate: String,
        wantTime: String,
        wantLoc: String?,
        wantTitleHas: String?,
        wantConf: Int? = null,
        /** 期望某条警告里包含这段文字；null 表示不校验 */
        wantWarnContains: String? = null
    ) {
        val r = if (wantDate == "null") {
            assertNull("$id 应该被丢弃：$raw", DateTimeParser.parse(raw, now))
            return
        } else {
            DateTimeParser.parse(raw, now).also { assertNotNull("$id 应该能解析：$raw", it) }!!
        }
        assertEquals("$id 日期", wantDate, dFmt.format(r.start))
        if (wantTime == "ALL") {
            assertEquals("$id 应该是全天（无具体钟点）", false, r.hasTime)
        } else if (wantTime.isNotEmpty()) {
            assertEquals("$id 时间", wantTime, tFmt.format(r.start))
        }
        assertEquals("$id 地点", wantLoc, r.location)
        if (wantTitleHas != null) {
            assertTrue(
                "$id 标题应含「$wantTitleHas」，实际「${r.title}」",
                r.title.contains(wantTitleHas)
            )
        }
        if (wantConf != null) assertEquals("$id 置信度", wantConf, r.confidence)
        if (wantWarnContains != null) {
            assertTrue(
                "$id 应该有包含「$wantWarnContains」的警告，实际 ${r.warnings}",
                r.warnings.any { it.contains(wantWarnContains) }
            )
            assertTrue("$id 有警告就必须要求人工确认", r.needsConfirm)
        }
    }

    @Test
    fun `N01 仿拍大赏 只有下午没有钟点 且地点是另行通知`() {
        check(
            "N01",
            "📢【时光机·首届“名场面”仿拍大赏来啦！】🎬 平时看剧的时候，是不是总有几个片段让你忍不住想自己上去演两把？" +
                "📅 时间：11月1日（周日）下午 📍 地点：具体地点后续在群里通知",
            "11-01", "ALL", null, "名场面", 55
        )
    }

    @Test
    fun `N02 成立大会 年月日加钟点加地点`() {
        check(
            "N02",
            "【第11届时光机影视协会成立大会举办通知】亲爱的各位前辈、顾问、部长、成员们：承蒙各位的厚爱与支持。" +
                "⏰时间：10月17日14:00 📍地点：新传楼一楼融媒体大厅 👚着装：建议穿着会服出席",
            "10-17", "14:00", "新传楼一楼融媒体大厅", "成立大会", 70,
            wantWarnContains = "已经过了"
        )
    }

    @Test
    fun `N03 宣传部培训 地点只有定位图标没有地点二字`() {
        check(
            "N03",
            "📢【宣传部培训通知✨】@所有人 各位同学👋：宣传部将于10月25日（周日）晚上18:30（第十节课）📍知识产权楼321开展培训📚",
            "10-25", "18:30", "知识产权楼321", "培训", 90
        )
    }

    @Test
    fun `N04 作业 ddl 点分日期加24点整 邮箱不能当地点`() {
        check(
            "N04",
            "作业布置 请从课本P22的T3 5个话题中任选一个写段落。Deadline：10.19 24:00 作业邮箱：homework2463@163.com",
            "10-19", "23:59", null, "作业", 70,
            wantWarnContains = "已经过了"
        )
    }

    @Test
    fun `N05 宿舍展提交截止 中午12点前`() {
        check(
            "N05",
            "🔴请各宿舍在10月26日中午12:00前按要求完成作品提交，逾期视为无效",
            "10-26", "12:00", null, null, 90
        )
    }

    @Test
    fun `N06 宿舍展活动区间 取起始日`() {
        check("N06", "⏳ 活动时间：10月16日—10月26日", "10-16", "ALL", null, null, 55)
    }

    @Test
    fun `N07 启动大会 只有下午 加括号人名不能当标题`() {
        check(
            "N07",
            "另外，10月31日（周六）下午，【学院团委书记严梓菡老师】将给大家开一次简短的工作启动大会，请全体同学预留出时间，按时到场，时间地点另行通知~",
            "10-31", "ALL", null, "启动大会", 55
        )
    }

    @Test
    fun `N08 进组意愿表 月份区间不能当日期 今晚20点`() {
        check(
            "N08",
            "@所有人 麻烦大家填写一下11-12月进组意愿统计表，方便编导部确定剧本数量，ddl今晚20:00",
            "10-23", "20:00", null, "进组", 85
        )
    }

    @Test
    fun `N09 心理培训 完整年月日加时间段加会议号`() {
        check(
            "N09",
            "培训时间：2026年10月21日（星期三）18:30—20:00 培训形式：腾讯会议线上培训（会议号：8999-4168-389，密码：261021）",
            "10-21", "18:30", null, "培训", 70,
            wantWarnContains = "已经过了"
        )
    }

    @Test
    fun `N10 国术协会 明日加下午3点至5点半`() {
        check(
            "N10",
            "@所有人 国术协会明天的活动表格已发！欢迎有兴趣者可以前来体验！明日的活动时间是下午3点至下午5点半。",
            "10-24", "15:00", null, "国术", 85
        )
    }

    @Test
    fun `N11 手工社 本周日晚加活动地点 标题要跳过宣传口号`() {
        check(
            "N11",
            "✨指尖上的创意时光！手工社即将开展第一次手工活动啦～📅 活动时间：本周日晚19:00-22:30 📍 活动地点：学生活动中心302 " +
                "🎯 温馨提示：本次活动限50人参与，报名截止时间为周四23:59，先到先得哦！",
            "10-25", "19:00", "学生活动中心302", "手工", 72
        )
    }

    @Test
    fun `N12 微信通知真实形态 群名加发言人前缀加在X开展`() {
        check(
            "N12",
            "宣传部通知群: 阿May: 宣传部将于10月25日（周日）晚上18:30在知识产权楼321开展培训",
            "10-25", "18:30", "知识产权楼321", "培训", 90
        )
    }

    @Test
    fun `N13 没有任何时间信号的通知直接丢弃`() {
        check(
            "N13",
            "@所有人 中心特邀心理专家为心理委员开展专题培训，详情见文件通知。",
            "null", "", null, null
        )
    }

    @Test
    fun `N14 三天后的中午12点半`() {
        check(
            "N14",
            "3天后的中午12点半和导师碰面聊课题",
            "10-26", "12:30", null, "导师", 85
        )
    }
}
