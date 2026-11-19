package com.terry.smartschedule

import com.terry.smartschedule.notify.MsgSig
import com.terry.smartschedule.notify.SeenStore
import com.terry.smartschedule.parse.TextNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息去重的比较键与指纹。
 *
 * 这两个函数是去重的**唯一判据**：判宽了会吞掉正经日程，判严了等于没去重。
 * 真机上出错的表现都很隐蔽 —— 前者是"少了一条日程"，后者是"多了一条一模一样的"，
 * 都只能靠这里把边界钉死。
 */
class DedupeTest {

    // ---------------- 归一化 ----------------

    @Test
    fun `发送者前缀不影响比较`() {
        // 微信 MessagingStyle 每条消息都带 sender，剥不剥决定了"同一条消息"能不能认出来
        assertEquals(
            TextNorm.key("明天下午3点开会"),
            TextNorm.key("李四：明天下午3点开会")
        )
    }

    @Test
    fun `群名加昵称的两层前缀也能剥掉`() {
        assertEquals(
            TextNorm.key("明天下午3点开会"),
            TextNorm.key("宣传部通知群: 阿May：明天下午3点开会")
        )
    }

    @Test
    fun `空格标点和表情符号不参与比较`() {
        // 通知重渲染时这些最容易变，一变原文哈希就不同。
        // 注意口径：只抹词形差异，"下午3点"和"15：00"这种同一时刻的不同写法
        // 不归这层管 —— 那是解析层的事（都解析成 15:00，再由事件级判重兜住）
        assertEquals(
            TextNorm.key("明天下午3点开会"),
            TextNorm.key("明天 下午3点 开会！！！🎉🎉")
        )
    }

    @Test
    fun `全角数字与半角数字视为同一个`() {
        assertEquals(TextNorm.key("10月25日交作业"), TextNorm.key("１０月２５日交作业"))
    }

    @Test
    fun `以时间开头的正文不被当成发送者前缀`() {
        // "10:30开会" 若被剥成 "开会"，会和任何"开会"撞车 —— 这是吞日程的典型走法
        val k = TextNorm.key("10:30开会")
        assertTrue("归一化后应保留时间数字，实际：$k", k.contains("1030"))
        assertNotEquals(TextNorm.key("开会"), k)
    }

    @Test
    fun `数字不同就不能算同一条`() {
        // 判宽了会把"3点开会"和"5点开会"合并，后者直接消失
        assertNotEquals(TextNorm.key("下午3点开会"), TextNorm.key("下午5点开会"))
        assertNotEquals(TextNorm.key("10月25日交"), TextNorm.key("10月26日交"))
    }

    @Test
    fun `纯符号文本归一化后为空`() {
        assertEquals("", TextNorm.key("！！！🎉😀"))
        assertEquals("", TextNorm.key("   "))
    }

    @Test
    fun `长文本截断到 200 字且前缀相同则键相同`() {
        val body = "关于本周六上午九点在体育馆举行运动会的通知".repeat(20)
        val a = TextNorm.key(body)
        val b = TextNorm.key(body + "（另：请自带水杯）")
        assertEquals(200, a.length)
        assertEquals(a, b)
    }

    // ---------------- 指纹 ----------------

    @Test
    fun `同一内容不同渲染方式指纹相同`() {
        val a = MsgSig.of("李四：明天下午3点开会")
        val b = MsgSig.of("宣传部通知群: 李四：明天下午3点开会！🎉")
        assertEquals(a, b)
    }

    @Test
    fun `不同内容指纹不同`() {
        assertNotEquals(MsgSig.of("明天下午3点开会"), MsgSig.of("明天下午4点开会"))
    }

    @Test
    fun `指纹是 16 位十六进制`() {
        val sig = MsgSig.of("明天下午3点开会")
        assertEquals(16, sig.length)
        assertTrue("应只含 0-9a-f，实际：$sig", sig.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `没有有效字符时不产生指纹`() {
        // 返回空串而不是某种"固定值"—— 固定值会让所有纯表情通知互相判重
        assertEquals("", MsgSig.of("🎉🎉🎉"))
        assertFalse(MsgSig.of("😀").isNotEmpty())
    }

    @Test
    fun `指纹与原文顺序有关`() {
        // 归一化会去掉空格，但绝不能把词序也去掉
        assertNotEquals(MsgSig.of("明天开会 后天交作业"), MsgSig.of("明天交作业 后天开会"))
    }

    // ---------------- 会话范围 ----------------

    @Test
    fun `不同会话的同一条文字不算重复`() {
        // 班级群和社团群都发"明天下午3点开会"，是两件事
        assertNotEquals(
            MsgSig.of("明天下午3点开会", scope = "班级群"),
            MsgSig.of("明天下午3点开会", scope = "社团群")
        )
    }

    @Test
    fun `会话名里的表情和空格不影响范围`() {
        assertEquals(
            MsgSig.of("明天开会", scope = "宣传部通知群"),
            MsgSig.of("明天开会", scope = "宣传部通知群 📢")
        )
    }

    // ---------------- 日志人话 ----------------

    @Test
    fun `时间差写成中文`() {
        val now = 1_700_000_000_000L
        assertEquals("30 秒前", SeenStore.agoLabel(now - 30_000, now))
        assertEquals("5 分钟前", SeenStore.agoLabel(now - 5 * 60_000, now))
        assertEquals("3 小时前", SeenStore.agoLabel(now - 3 * 3_600_000, now))
    }
}
