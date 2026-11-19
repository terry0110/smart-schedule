package com.terry.smartschedule

import com.terry.smartschedule.data.Event
import com.terry.smartschedule.data.EventSearch
import com.terry.smartschedule.ui.SearchHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日程检索。
 *
 * 检索错了不会崩，但会让人"明明有这条却搜不到"，这比崩溃更损耗信任 ——
 * 崩溃至少知道坏了，搜不到会以为数据丢了。所以边界要钉死：
 * 拆词、多词 AND、全角半角、大小写、命中范围、区间合并。
 */
class SearchTest {

    private fun ev(
        title: String,
        location: String? = null,
        note: String? = null
    ) = Event(title = title, startAt = 0L, location = location, note = note)

    private fun hit(e: Event, q: String, extra: String? = null): Boolean =
        EventSearch.matches(e, EventSearch.tokens(q), extra)

    // ---------------- 拆词 ----------------

    @Test
    fun `空查询拆出空列表`() {
        assertTrue(EventSearch.tokens("").isEmpty())
        assertTrue(EventSearch.tokens("   \n\t ").isEmpty())
        assertTrue(EventSearch.tokens("　").isEmpty()) // 全角空格
    }

    @Test
    fun `空白分隔多词且去重`() {
        assertEquals(listOf("图书馆", "开会"), EventSearch.tokens("  图书馆   开会 "))
        // 重复的词只算一次：用户手抖打两遍不该改变语义
        assertEquals(listOf("开会"), EventSearch.tokens("开会 开会"))
    }

    @Test
    fun `全角转半角转小写`() {
        // "ＡＢＣ１２３" 全角 -> "abc123"
        assertEquals(listOf("abc123"), EventSearch.tokens("ＡＢＣ１２３"))
        assertEquals(listOf("abc"), EventSearch.tokens("ABC"))
    }

    @Test
    fun `词数上限 8 个`() {
        val q = (1..20).joinToString(" ") { "k$it" }
        assertEquals(8, EventSearch.tokens(q).size)
    }

    // ---------------- 匹配 ----------------

    @Test
    fun `标题命中`() {
        val e = ev("高数作业")
        assertTrue(hit(e, "高数"))
        assertFalse(hit(e, "物理"))
    }

    @Test
    fun `地点也参与匹配`() {
        val e = ev("开会", location = "图书馆三楼")
        assertTrue(hit(e, "图书馆"))
        assertTrue(hit(e, "三楼"))
    }

    @Test
    fun `原文也参与匹配`() {
        // 通知抓来的日程标题往往是精简过的，细节只在原文里 —— 搜不到原文等于白搜
        val e = ev("开会", note = "记得带学生证和报名表")
        assertTrue(hit(e, "学生证"))
        assertTrue(hit(e, "报名表"))
    }

    @Test
    fun `多词是 AND 不是 OR`() {
        val e = ev("宣传部例会", location = "图书馆")
        assertTrue(hit(e, "宣传部 图书馆"))
        // 「图书馆」有、「食堂」没有 → 必须不命中。OR 的话加词会越加越多，
        // 用户没法靠加词收窄结果
        assertFalse(hit(e, "宣传部 食堂"))
    }

    @Test
    fun `空查询视为不过滤`() {
        val e = ev("随便什么")
        assertTrue(hit(e, ""))
        assertTrue(hit(e, "   "))
    }

    @Test
    fun `大小写不敏感`() {
        val e = ev("DDL 是 Friday")
        assertTrue(hit(e, "ddl"))
        assertTrue(hit(e, "FRIDAY"))
    }

    @Test
    fun `忽略空白`() {
        // 原文中间被排版加了空格，用户按正常写法搜也要能中
        val e = ev("开 会 通 知")
        assertTrue(hit(e, "开会"))
    }

    @Test
    fun `标点保留 精确命中`() {
        // 归一化刻意不抹标点（和 TextNorm 判重键相反）：
        // 抹掉冒号会让 "1030" 命中 "10:30"，也会让用户搜 "15:00" 变得不可控
        val e = ev("15:00 开会")
        assertTrue(hit(e, "15:00"))
        assertFalse(hit(e, "1030"))
    }

    @Test
    fun `分类名可作额外命中源`() {
        val e = ev("高数作业")
        // 不传 extra：标题里没有"学习"
        assertFalse(hit(e, "学习"))
        // 传了分类名：能捞出来
        assertTrue(hit(e, "学习", extra = "学习"))
    }

    @Test
    fun `null 字段不炸`() {
        val e = Event(title = "只有标题", startAt = 0L)
        assertTrue(hit(e, "只有"))
        assertFalse(hit(e, "图书馆"))
    }

    // ---------------- 高亮区间 ----------------

    @Test
    fun `区间按原文位置给出`() {
        val r = EventSearch.ranges("明天去图书馆开会", listOf("图书馆"))
        assertEquals(1, r.size)
        assertEquals(3, r[0].first)
        assertEquals(5, r[0].last)
    }

    @Test
    fun `多处出现全部标出`() {
        val r = EventSearch.ranges("图书馆集合，图书馆三楼", listOf("图书馆"))
        assertEquals(2, r.size)
        assertEquals(0, r[0].first)
        assertEquals(6, r[1].first) // "图书馆集合，" 是 6 个字符，第二处从这里开始
    }

    @Test
    fun `重叠与相邻区间合并`() {
        // "开会" 和 "会通" 在 "开会通知" 里重叠 → 合并成一个段，不然 span 会互相覆盖
        val r = EventSearch.ranges("开会通知", listOf("开会", "会通"))
        assertEquals(1, r.size)
        assertEquals(0, r[0].first)
        assertEquals(2, r[0].last)
    }

    @Test
    fun `区间不乱序`() {
        val r = EventSearch.ranges("abc abc abc", listOf("abc"))
        assertEquals(listOf(0, 4, 8), r.map { it.first })
    }

    @Test
    fun `没命中就没有区间`() {
        assertTrue(EventSearch.ranges("开会", listOf("考试")).isEmpty())
        assertTrue(EventSearch.ranges("", listOf("开会")).isEmpty())
        assertTrue(EventSearch.ranges("开会", emptyList()).isEmpty())
    }

    @Test
    fun `大小写不影响高亮位置`() {
        val r = EventSearch.ranges("Friday 交 DDL", listOf("friday"))
        assertEquals(1, r.size)
        assertEquals(0, r[0].first)
    }

    @Test
    fun `高亮区间不越过原文长度`() {
        // 全角/半角不一致时归一化后能命中、但原文里找不到 —— 这时宁可不标，也不许越界
        val r = EventSearch.ranges("ＡＢＣ", listOf("abc"))
        assertTrue(r.isEmpty())
        for (x in r) assertTrue(x.last < 3)
    }

    // ---------------- 命中片段 ----------------

    @Test
    fun `片段裁在命中附近并带省略号`() {
        val text = "a".repeat(30) + "关键词" + "b".repeat(30)
        val s = SearchHighlight.snippet(text, listOf("关键词"))
        assertTrue(s != null)
        assertTrue(s!!.startsWith("…"))
        assertTrue(s.endsWith("…"))
        assertTrue(s.contains("关键词"))
    }

    @Test
    fun `片段把换行摊平`() {
        val s = SearchHighlight.snippet("第一行\n第二行 开会\n第三行", listOf("开会"))
        assertTrue(s != null)
        assertFalse(s!!.contains('\n'))
        assertTrue(s.contains("开会"))
    }

    @Test
    fun `没命中就没有片段`() {
        assertTrue(SearchHighlight.snippet("不相干的原文", listOf("开会")) == null)
        assertTrue(SearchHighlight.snippet("开会", emptyList()) == null)
    }

    @Test
    fun `命中判定不依赖 Context`() {
        assertTrue(SearchHighlight.hits("高数作业", listOf("高数")))
        assertFalse(SearchHighlight.hits("高数作业", listOf("物理")))
        assertFalse(SearchHighlight.hits(null, listOf("高数")))
    }
}
