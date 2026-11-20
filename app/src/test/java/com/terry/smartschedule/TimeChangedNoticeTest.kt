package com.terry.smartschedule

import com.terry.smartschedule.data.Event
import com.terry.smartschedule.data.EventDeduper
import com.terry.smartschedule.parse.DateTimeParser
import com.terry.smartschedule.parse.Revise
import com.terry.smartschedule.parse.TextNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「现时间有变」这类通知。
 *
 * 真实案例：班委先发了一条「下周六（11月21日）…盖章」，随后又发
 * 「现时间有变，…下周日（11月22日）…」。第二句没有「原定」两个字，
 * 旧逻辑不认为它是改期，于是又建了一条独立日程，列表里两条互相矛盾。
 *
 * 这里钉住三件事：认得出"时间有变"、取**最后**一版的时间、没给新时间时不动数据。
 */
class TimeChangedNoticeTest {

    private val now = LocalDateTime.of(2026, 11, 19, 21, 50)

    private val first = "现时间有变，最近已经跟贺老师请过假需要签假条的同学，" +
        "可以在下周六（11月21日）下午3点-5点到知产楼1224找我盖章。如有紧急情况可以单独联系我。"

    private val second = "现时间有变，最近已经跟贺老师请过假需要签假条的同学，" +
        "可以在下周日（11月22日）下午3点-5点到知产楼1224找我盖章。如有紧急情况可以单独联系我。"

    private fun dt(ms: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    @Test
    fun `时间有变应识别成改期并取新时间`() {
        val v = Revise.detect(second, now)
        assertTrue("应该判成改期，实际 $v", v is Revise.Verdict.Move)
        v as Revise.Verdict.Move
        assertEquals(LocalDateTime.of(2026, 11, 22, 15, 0), dt(v.newStartAt))
        assertNull("原文没写「原定」，旧时间只能靠候选去匹配", v.oldStartAt)
    }

    @Test
    fun `现有变这种少字的说法也认`() {
        val typo = second.replace("现时间有变", "现有变")
        val v = Revise.detect(typo, now)
        assertTrue("应该判成改期，实际 $v", v is Revise.Verdict.Move)
        v as Revise.Verdict.Move
        assertEquals(LocalDateTime.of(2026, 11, 22, 15, 0), dt(v.newStartAt))
    }

    @Test
    fun `同一段里贴了旧版和新版时取最后一版`() {
        val v = Revise.detect("$first 和 $second", now)
        assertTrue("应该判成改期，实际 $v", v is Revise.Verdict.Move)
        v as Revise.Verdict.Move
        assertEquals(LocalDateTime.of(2026, 11, 22, 15, 0), dt(v.newStartAt))
    }

    @Test
    fun `只说时间有变没给新时间时不乱改`() {
        val v = Revise.detect("时间有变了，具体等通知", now)
        assertTrue("应该是 Unclear，实际 $v", v is Revise.Verdict.Unclear)
    }

    @Test
    fun `时间有变但只有钟点没有日期时不乱改`() {
        val v = Revise.detect("时间有变，改到下午3点", now)
        assertTrue("应该是 Unclear，实际 $v", v is Revise.Verdict.Unclear)
    }

    @Test
    fun `第二版通知能选中第一版建出的日程`() {
        val old = DateTimeParser.parse(first, now)
        assertTrue("第一段应该能解析出日程", old != null)
        old!!
        val oldEvent = Event(
            title = old.title,
            startAt = old.startAt,
            hasTime = old.hasTime,
            location = old.location,
            note = first
        )
        // 同一时间窗里放一条无关日程，确认文字重合能把目标筛出来
        val decoy = Event(
            title = "部门周会",
            startAt = old.startAt,
            hasTime = true,
            location = "会议室A",
            note = "周一上午部门开会"
        )
        assertEquals(oldEvent, Revise.pickByContent(listOf(oldEvent, decoy), second))
    }

    @Test
    fun `两个候选正文重合度接近时不猜`() {
        val a = Event(title = "盖章通知", startAt = 1L, note = second)
        val b = Event(title = "盖章通知", startAt = 2L, note = second)
        assertNull(Revise.pickByContent(listOf(a, b), second))
    }

    @Test
    fun `只有一个候选但正文不沾边时也不猜`() {
        val unrelated = Event(
            title = "部门周会",
            startAt = 1L,
            location = "会议室A",
            note = "周一上午部门开会"
        )
        assertNull(Revise.pickByContent(listOf(unrelated), second))
    }

    @Test
    fun `时间有变但钟点解析失败时不改成全天`() {
        val v = Revise.detect("时间有变，11月22日25点", now)
        assertTrue("应该是 Unclear，实际 $v", v is Revise.Verdict.Unclear)
        v as Revise.Verdict.Unclear
        assertTrue("应该说明是钟点解析失败，实际 ${v.why}", v.why.contains("钟点"))
    }

    @Test
    fun `时间调整或时间变更的公告不会被当成改期`() {
        val adjust = Revise.detect("开放时间调整通知：11月20日起闭馆", now)
        assertTrue("全新通告不应该走改期，实际 $adjust", adjust is Revise.Verdict.None)
        val change = Revise.detect("营业时间变更公告：11月20日起闭馆", now)
        assertTrue("全新通告不应该走改期，实际 $change", change is Revise.Verdict.None)
    }

    @Test
    fun `两版通知解析出的标题可以宽松同题`() {
        val a = DateTimeParser.parse(first, now)
        val b = DateTimeParser.parse(second, now)
        assertTrue("第一版应该能解析", a != null)
        assertTrue("第二版应该能解析", b != null)
        a!!
        b!!
        // 清理重复用的是宽松同题；两版只差日期，标题必须能对上，
        // 否则 EventDeduper 会把它们当成两件事，旧重复清不掉。
        assertTrue(
            "标题对不上：${a.title} vs ${b.title}",
            TextNorm.sameTitleLoose(a.title, b.title)
        )
    }

    @Test
    fun `两版真实通知解析出的日程能归成一组`() {
        val a = DateTimeParser.parse(first, now)!!
        val b = DateTimeParser.parse(second, now)!!
        val created = at(11, 19, 10)
        val ea = Event(
            id = 1, title = a.title, startAt = a.startAt, hasTime = a.hasTime,
            location = a.location, createdAt = created
        )
        val eb = Event(
            id = 2, title = b.title, startAt = b.startAt, hasTime = b.hasTime,
            location = b.location, createdAt = created + 3600_000L
        )
        // 这是「现时间有变」那两版真实文本的端到端相似性：标题、地点、
        // 创建时间、日期差任何一项对不上，清理按钮都会漏掉这条重复。
        assertTrue(EventDeduper.similar(ea, eb))
    }

    private fun at(month: Int, day: Int, hour: Int): Long =
        LocalDateTime.of(2026, month, day, hour, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
