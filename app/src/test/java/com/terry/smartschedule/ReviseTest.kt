package com.terry.smartschedule

import com.terry.smartschedule.parse.Revise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 改期 / 取消句式的识别。
 *
 * 这层错了的代价不是崩溃，是**悄悄改掉用户的日程**或者**建出两条互相矛盾的日程** ——
 * 用户不会报 bug，只会觉得这应用在乱来。所以每种句式逐条钉：
 * 认不认、认成什么、什么时候宁可不认。
 */
class ReviseTest {

    private val now = LocalDateTime.of(2026, 11, 19, 18, 0)

    private fun dt(ms: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    private fun move(text: String): Revise.Verdict.Move {
        val v = Revise.detect(text, now)
        assertTrue("应该判成改期，实际 $v：$text", v is Revise.Verdict.Move)
        return v as Revise.Verdict.Move
    }

    private fun cancel(text: String): Revise.Verdict.Cancel {
        val v = Revise.detect(text, now)
        assertTrue("应该判成取消，实际 $v：$text", v is Revise.Verdict.Cancel)
        return v as Revise.Verdict.Cancel
    }

    private fun relocate(text: String): Revise.Verdict.Relocate {
        val v = Revise.detect(text, now)
        assertTrue("应该判成换地点，实际 $v：$text", v is Revise.Verdict.Relocate)
        return v as Revise.Verdict.Relocate
    }

    // ---------------- 改期 ----------------

    @Test
    fun `用户给的原始例子`() {
        val m = move("原定明天下午四点的会议将提前至3点")
        assertEquals(LocalDateTime.of(2026, 11, 20, 16, 0), dt(m.oldStartAt!!))
        // 「3点」没写上下午，旧时间是下午 → 推成 15:00，并标记"这是猜的"
        assertEquals(LocalDateTime.of(2026, 11, 20, 15, 0), dt(m.newStartAt))
        assertTrue(m.newHasTime)
        assertTrue(m.meridiemGuessed)
    }

    @Test
    fun `改到另一天的另一个钟点 日期和钟点都要对`() {
        // 这是当前解析器错得最狠的一种：它把"后天"配上"四点"，得出后天 16:00，
        // 而原句说的是上午十点。
        val m = move("原定明天下午四点的会议改到后天上午十点")
        assertEquals(LocalDateTime.of(2026, 11, 20, 16, 0), dt(m.oldStartAt!!))
        assertEquals(LocalDateTime.of(2026, 11, 21, 10, 0), dt(m.newStartAt))
        assertEquals(false, m.meridiemGuessed)
        assertEquals("原文没写结束时间", 0L, m.newEndAt)
    }

    @Test
    fun `改到某个时间段时结束时间一起带出来`() {
        val m = move("原定明天下午四点的会议改到后天上午十点到十二点")
        assertEquals(LocalDateTime.of(2026, 11, 21, 10, 0), dt(m.newStartAt))
        assertEquals(LocalDateTime.of(2026, 11, 21, 12, 0), dt(m.newEndAt))
    }

    @Test
    fun `上下午是推的 结束时间跟着新开始走`() {
        // 新片段只写「3点到5点」，旧时间是下午 → 开始推到 15:00，结束也应是 17:00
        val m = move("原定明天下午四点的会改到3点到5点")
        assertEquals(LocalDateTime.of(2026, 11, 20, 15, 0), dt(m.newStartAt))
        assertEquals(LocalDateTime.of(2026, 11, 20, 17, 0), dt(m.newEndAt))
        assertTrue(m.meridiemGuessed)
    }

    @Test
    fun `时间有变 也带出结束时间`() {
        val m = move("时间有变，改到明天下午六点到九点")
        assertEquals(LocalDateTime.of(2026, 11, 20, 18, 0), dt(m.newStartAt))
        assertEquals(LocalDateTime.of(2026, 11, 20, 21, 0), dt(m.newEndAt))
    }

    @Test
    fun `中文数字钟点也能认`() {
        val m = move("原定明天下午四点的会议提前至三点")
        assertEquals(LocalDateTime.of(2026, 11, 20, 15, 0), dt(m.newStartAt))
        assertTrue(m.meridiemGuessed)
    }

    @Test
    fun `推迟同样处理`() {
        val m = move("原定明天下午四点的会议推迟到后天上午十点")
        assertEquals(LocalDateTime.of(2026, 11, 21, 10, 0), dt(m.newStartAt))
    }

    @Test
    fun `没写原定时间时 oldStartAt 为空`() {
        // 有日期（明天）才能 Move；只有钟点的版本见下一条，不猜日期
        val m = move("会议改到明天下午3点")
        assertNull(m.oldStartAt)
        assertEquals(LocalDateTime.of(2026, 11, 20, 15, 0), dt(m.newStartAt))
    }

    @Test
    fun `没有旧时间又只有钟点时改为不清楚`() {
        val v = Revise.detect("会议提前到3点", now)
        assertTrue("应该是 Unclear，实际 $v", v is Revise.Verdict.Unclear)
    }

    @Test
    fun `只改日期不改钟点 按全天处理`() {
        val m = move("原定明天下午四点的会议改到后天")
        assertEquals(LocalDateTime.of(2026, 11, 20, 16, 0), dt(m.oldStartAt!!))
        assertEquals(false, m.newHasTime)
    }

    @Test
    fun `上午的会改到3点 不硬推成下午`() {
        // 旧时间是上午 10 点，没有任何线索说"3点"指下午，保持 3:00 并标记为猜
        val m = move("原定明天上午十点的会议提前至3点")
        assertEquals(LocalDateTime.of(2026, 11, 20, 3, 0), dt(m.newStartAt))
    }

    @Test
    fun `写了上午就按上午算 不猜`() {
        val m = move("原定明天下午四点的会议提前至上午10点")
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 0), dt(m.newStartAt))
        assertEquals(false, m.meridiemGuessed)
    }

    // ---------------- 取消 ----------------

    @Test
    fun `取消带原定时间`() {
        val c = cancel("原定明天下午四点的会议取消了")
        assertEquals(LocalDateTime.of(2026, 11, 20, 16, 0), dt(c.oldStartAt!!))
    }

    @Test
    fun `取消没提时间`() {
        val c = cancel("会议取消了")
        assertNull(c.oldStartAt)
    }

    @Test
    fun `实测里被当成闲聊丢掉的那句`() {
        // 端到端验证时这句被 TextFilter.isSmallTalk 拦成"像闲聊，没有具体安排"，
        // 取消完全没生效。修法是在 Service 里让 Revise 的结论先于闲聊判断，
        // 这里钉住"Revise 本身认得出它"，避免以后有人把判据改松。
        val c = cancel("原定明天下午三点的会在教学楼不开了")
        assertEquals(LocalDateTime.of(2026, 11, 20, 15, 0), dt(c.oldStartAt!!))
    }

    @Test
    fun `取消优先于改期动词`() {
        // 句子里同时出现"提前"和"取消"，以取消为准
        assertTrue(Revise.detect("原定明天的会取消了，不用提前去了", now) is Revise.Verdict.Cancel)
    }

    // ---------------- 不该误认的 ----------------

    @Test
    fun `普通日程不会被当成改期`() {
        assertEquals(Revise.Verdict.None, Revise.detect("明天下午四点在教学楼开会", now))
        assertEquals(Revise.Verdict.None, Revise.detect("明天交高数作业", now))
        assertEquals(Revise.Verdict.None, Revise.detect("下午三点图书馆见", now))
    }

    @Test
    fun `只有动词没时间 报不清楚而不是乱改`() {
        val v = Revise.detect("那个会议提前了", now)
        assertTrue("应该是 Unclear，实际 $v", v is Revise.Verdict.Unclear)
    }

    // ---------------- 换地点 ----------------

    @Test
    fun `换到B栋301能抽出地点`() {
        val r = relocate("换到B栋301了")
        assertEquals("B栋301", r.newLocation)
        assertNull("原文没提原时间", r.oldStartAt)
    }

    @Test
    fun `原定时间加换地点 两个锚点都要有`() {
        val r = relocate("原定明天下午四点的会在A栋，换到B栋301")
        assertEquals(LocalDateTime.of(2026, 11, 20, 16, 0), dt(r.oldStartAt!!))
        assertEquals("B栋301", r.newLocation)
    }

    @Test
    fun `改到下午三点仍然是改时间，不是改地点`() {
        // 带「原定」，日期有锚点，才允许把下午三点落到 11/20 15:00
        val m = move("原定明天下午四点的会改到下午三点")
        assertNull("地点不该被时间片段污染", m.newLocation)
        assertEquals(LocalDateTime.of(2026, 11, 20, 15, 0), dt(m.newStartAt))
    }

    @Test
    fun `没有旧时间又只有钟点时不猜日期`() {
        val v = Revise.detect("改到下午三点", now)
        assertTrue("应该是 Unclear，实际 $v", v is Revise.Verdict.Unclear)
    }

    @Test
    fun `在图书馆开会不是地点变更`() {
        assertEquals(Revise.Verdict.None, Revise.detect("在图书馆开会", now))
    }

    @Test
    fun `改到3号是改日期不是换地点`() {
        // 「号」不在地点形态词里：3号是日期，不是门牌
        val m = move("改到3号")
        assertNull(m.newLocation)
        assertEquals(false, m.newHasTime)
    }

    @Test
    fun `换地点也值得让用户确认`() {
        assertTrue(Revise.worthAsking(Revise.detect("换到B栋301了", now)))
    }

    // ---------------- 该不该弹确认 ----------------

    @Test
    fun `信息不全时带上原定时间，供人工确认定位`() {
        val v = Revise.detect("原定明天下午四点的会议推迟了", now)
        assertTrue("应该是 Unclear，实际 $v", v is Revise.Verdict.Unclear)
        v as Revise.Verdict.Unclear
        assertTrue("应带上原定时间，实际 ${v.oldStartAt}", v.oldStartAt != null)
        assertEquals(LocalDateTime.of(2026, 11, 20, 16, 0), dt(v.oldStartAt!!))
    }

    @Test
    fun `没有对象的模糊改期不打扰用户`() {
        val v = Revise.detect("那个会议提前了", now)
        assertTrue("应该是 Unclear，实际 $v", v is Revise.Verdict.Unclear)
        assertTrue("没有旧时间就不该发确认通知", !Revise.worthAsking(v))
    }

    @Test
    fun `有旧时间的模糊改期值得让用户确认`() {
        val v = Revise.detect("原定明天下午四点的会议推迟了", now)
        assertTrue(Revise.worthAsking(v))
    }

    @Test
    fun `取消和普通消息不弹确认`() {
        assertTrue(!Revise.worthAsking(Revise.detect("原定明天的会取消了", now)))
        assertTrue(!Revise.worthAsking(Revise.detect("明天下午三点开会", now)))
    }

    @Test
    fun `有空格的常见写法`() {
        val m = move("原定明天下午四点的会议，提前到 3 点")
        assertEquals(LocalDateTime.of(2026, 11, 20, 15, 0), dt(m.newStartAt))
    }

    // ---------------- 选哪一条 ----------------

    private fun ev(id: Long, title: String, hour: Int) = com.terry.smartschedule.data.Event(
        id = id,
        title = title,
        startAt = LocalDateTime.of(2026, 11, 20, hour, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )

    @Test
    fun `候选只有一条就用它`() {
        val one = ev(1, "开会", 16)
        assertEquals(one, Revise.pick(listOf(one), "原定明天下午四点的会议将提前至3点"))
    }

    @Test
    fun `两条候选 用原文里的字筛`() {
        val meeting = ev(1, "开会", 16)
        val dinner = ev(2, "聚餐", 16)
        val hit = Revise.pick(listOf(meeting, dinner), "原定明天下午四点的会议将提前至3点")
        assertEquals(meeting, hit)
    }

    @Test
    fun `筛完还是两条就不猜`() {
        // 「会议」和「开会」都含"会"，分不清是哪个 —— 宁可不动
        val a = ev(1, "开会", 16)
        val b = ev(2, "会议", 16)
        assertNull(Revise.pick(listOf(a, b), "原定明天下午四点的会议将提前至3点"))
    }

    @Test
    fun `一个字都对不上就不猜`() {
        val a = ev(1, "取快递", 16)
        val b = ev(2, "买洗衣液", 16)
        assertNull(Revise.pick(listOf(a, b), "原定明天下午四点的会议将提前至3点"))
    }

    @Test
    fun `空候选返回 null`() {
        assertNull(Revise.pick(emptyList(), "会议改到三点"))
    }
}
