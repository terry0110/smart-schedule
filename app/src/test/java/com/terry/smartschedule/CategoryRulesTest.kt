package com.terry.smartschedule

import com.terry.smartschedule.data.Category
import com.terry.smartschedule.parse.CategoryRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地分类规则。
 *
 * 这是一组"猜"的规则，错了不会崩，但会把用户的日程分错堆、让筛选变得不可信。
 * 所以边界要钉死：命中优先、歧义时归谁、没命中时归"其他"（不许硬凑）。
 * 语料取自 NotificationCorpusTest 里的真实群通知风格。
 */
class CategoryRulesTest {

    @Test
    fun `课业类关键词归到学习`() {
        assertEquals(Category.STUDY, CategoryRules.classify("明天下午3点交高数作业"))
        assertEquals(Category.STUDY, CategoryRules.classify("周五上午期末考试"))
        assertEquals(Category.STUDY, CategoryRules.classify("晚上7点听讲座"))
        assertEquals(Category.STUDY, CategoryRules.classify("毕业论文答辩"))
    }

    @Test
    fun `工作类关键词归到工作`() {
        assertEquals(Category.WORK, CategoryRules.classify("周一上午部门开会"))
        assertEquals(Category.WORK, CategoryRules.classify("下午三点客户汇报"))
        assertEquals(Category.WORK, CategoryRules.classify("周五前交周报"))
    }

    @Test
    fun `社团类关键词归到社团`() {
        assertEquals(Category.CLUB, CategoryRules.classify("宣传部例会，晚上八点"))
        assertEquals(Category.CLUB, CategoryRules.classify("学生会招新，周六上午"))
    }

    @Test
    fun `生活类关键词归到生活`() {
        assertEquals(Category.LIFE, CategoryRules.classify("晚上七点聚餐"))
        assertEquals(Category.LIFE, CategoryRules.classify("下午去取快递"))
        assertEquals(Category.LIFE, CategoryRules.classify("周三上午体检"))
    }

    @Test
    fun `没有关键词就归其他，不硬凑`() {
        // 硬猜一类比留在"其他"更糟：用户会以为分类坏了
        assertEquals(Category.OTHER, CategoryRules.classify("明天下午三点"))
        assertEquals(Category.OTHER, CategoryRules.classify(""))
        assertEquals(Category.OTHER, CategoryRules.classify("     "))
    }

    @Test
    fun `两边都沾时社团优先于工作`() {
        // "社团例会"对当事人是社团的事，不是工作 —— 这是真实的歧义样本
        assertEquals(Category.CLUB, CategoryRules.classify("社团例会，晚上八点"))
    }

    @Test
    fun `具体词压过泛化词`() {
        // "宣传部"（3 分）比"培训"（2 分）更能说明这是社团的事
        assertEquals(Category.CLUB, CategoryRules.classify("宣传部培训"))
        // 反过来，没有社团词时"培训"仍然是学习
        assertEquals(Category.STUDY, CategoryRules.classify("新员工培训"))
    }

    @Test
    fun `reason 说得出凭什么这么分`() {
        // 用户问"为什么这是学习"时得答得上来，不能只说"算法判的"
        assertEquals("答辩", CategoryRules.reason("下午答辩"))
        assertEquals("宣传部", CategoryRules.reason("宣传部开会"))
        assertNull("没命中就该明说没有依据", CategoryRules.reason("明天下午三点"))
    }

    @Test
    fun `分类 key 稳定`() {
        // 存进数据库的是这些字符串，改名等于把老用户的分类洗掉
        assertEquals("study", Category.STUDY.key)
        assertEquals("work", Category.WORK.key)
        assertEquals("life", Category.LIFE.key)
        assertEquals("club", Category.CLUB.key)
        assertEquals("other", Category.OTHER.key)
    }

    @Test
    fun `未知 key 退回其他`() {
        assertEquals(Category.OTHER, Category.of("不存在的分类"))
        assertEquals(Category.OTHER, Category.of(null))
        assertEquals(Category.STUDY, Category.of("study"))
    }

    @Test
    fun `每一类都有展示名和颜色`() {
        // 少一个资源，界面上就是空白标签
        for (c in Category.entries) {
            assertTrue("${c.key} 缺展示名", c.labelRes != 0)
            assertTrue("${c.key} 缺颜色", c.colorRes != 0)
        }
    }
}
