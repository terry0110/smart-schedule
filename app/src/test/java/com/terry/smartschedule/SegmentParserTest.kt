package com.terry.smartschedule

import com.terry.smartschedule.parse.DateTimeParser
import com.terry.smartschedule.parse.SegmentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 覆盖"从通知拆出来的多段文本里挑出真日程"这段逻辑。
 *
 * 这段代码错了的表现是"通知来了应用毫无反应"，在真机上极难复现，
 * 所以必须在这里把各种群聊形态固定下来。
 */
class SegmentParserTest {

    private val now = LocalDateTime.of(2026, 10, 23, 14, 5)
    private fun parse(s: String) = DateTimeParser.parse(s, now)

    @Test
    fun `时间和事件分在两段时拼起来才完整`() {
        // 标题只有时间、正文只有事件名 —— 分开看谁都解析不出完整日程，拼起来才完整
        val r = SegmentParser.pick(listOf("明天下午3点", "部门开会")) { parse(it) }
        assertNotNull("拼起来应该能解析出日期", r)
        assertEquals(24, r!!.start.dayOfMonth)
        assertEquals(15, r.start.hour)
        assertTrue("标题应该是事件名，实际：${r.title}", r.title.contains("开会"))
    }

    @Test
    fun `群里只有一条消息是真日程时能挑出来`() {
        // 微信/QQ 群通知的真实形态：前后都是闲聊，中间一条正经事
        val segs = listOf(
            "张三：哈哈哈笑死我了",
            "李四：明天下午3点在体育馆开会",
            "王五：收到"
        )
        val r = SegmentParser.pick(segs) { parse(it) }
        assertNotNull("群聊里那条日程应该被挑出来", r)
        assertEquals(24, r!!.start.dayOfMonth)
        assertEquals(15, r.start.hour)
        assertEquals("体育馆", r.location)
    }

    @Test
    fun `发送者前缀不影响解析`() {
        // MessagingStyle 里每条消息都带 sender，拼进来会变成"李四：明天..."
        val r = SegmentParser.pick(listOf("李四：明天下午3点在体育馆开会")) { parse(it) }
        assertNotNull(r)
        assertEquals(15, r!!.start.hour)
        assertEquals("体育馆", r.location)
    }

    @Test
    fun `全都解析不出日期时返回 null`() {
        val segs = listOf("张三：哈哈哈", "李四：在吗", "王五：好的")
        assertNull(SegmentParser.pick(segs) { parse(it) })
    }

    @Test
    fun `空列表与全短段落都不崩`() {
        assertNull(SegmentParser.pick(emptyList()) { parse(it) })
        assertNull(SegmentParser.pick(listOf("哈", "嗯嗯", "ok")) { parse(it) })
    }

    @Test
    fun `逐段命中的干净标题就直接用，不再拼整体`() {
        val calls = mutableListOf<String>()
        val r = SegmentParser.pick(listOf("aaaa", "bbbPICK", "cccc", "bbbPICK")) { s ->
            calls.add(s)
            if (s.contains("PICK")) {
                DateTimeParser.Result(
                    title = "命中",
                    startAt = 0L,
                    hasTime = true,
                    location = null,
                    confidence = 90,
                    dateSource = DateTimeParser.DateSource.ABS
                )
            } else {
                null
            }
        }
        assertNotNull(r)
        assertEquals("命中", r!!.title)
        // 重复的 "bbbPICK" 只该解析一次
        assertEquals("同一段不该重复解析", 1, calls.count { it == "bbbPICK" })
        // 逐段已经拿到干净标题，不该再去拼整体（拼接会让标题变脏）
        assertEquals("不该再试整体", 0, calls.count { it.contains(" ") })
    }

    @Test
    fun `逐段标题是残渣时退回整体`() {
        // 一条只有时间、另一条只有事件名 —— 单看哪条都不完整
        val r = SegmentParser.pick(listOf("明天下午3点", "部门开会")) { parse(it) }
        assertNotNull(r)
        assertEquals(24, r!!.start.dayOfMonth)
        assertEquals(15, r.start.hour)
        assertTrue("应该用整体拼出的标题，实际：${r.title}", r.title.contains("开会"))
    }

    @Test
    fun `中文数字的纯时间标题也不抢标题`() {
        // 中文数字不是 isDigit()，以前会被当成"干净标题"，把事件名挤掉
        val r = SegmentParser.pick(listOf("明天下午六点到九点", "部门例会")) { parse(it) }
        assertNotNull(r)
        assertEquals(24, r!!.start.dayOfMonth)
        assertEquals(18, r.start.hour)
        assertTrue("标题应该是事件名，实际：${r.title}", r.title.contains("例会"))
    }

    @Test
    fun `只有中文数字时间时仍然建出来`() {
        val r = SegmentParser.pick(listOf("明天下午六点到九点")) { parse(it) }
        assertNotNull("只有时间也应该能被挑出来", r)
        assertEquals(18, r!!.start.hour)
        assertTrue("结束时间应该保留", r.endAt > r.startAt)
    }

    @Test
    fun `短段落被跳过不浪费解析`() {
        val calls = mutableListOf<String>()
        SegmentParser.pick(listOf("哈", "好的", "明天9点开会")) { s ->
            calls.add(s)
            null
        }
        assertTrue("短段落不该进入解析：$calls", calls.none { it == "哈" || it == "好的" })
    }
}
