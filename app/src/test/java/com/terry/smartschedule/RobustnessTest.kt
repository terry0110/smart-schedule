package com.terry.smartschedule

import com.terry.smartschedule.parse.DateTimeParser
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * 健壮性 / 边界测试。
 *
 * 目的不是"验证功能对不对"（那是 DateTimeParserTest 的活），而是：
 *  1. 畸形输入不许抛异常 —— 通知正文来自第三方应用，什么鬼字符都可能有；
 *     解析器在 NotificationListenerService 里跑，抛一次异常就可能把监听服务搞死
 *  2. 非法日期不许"硬凑" —— 2月30日、13月、25点 这些必须被拒，不能兜成某一天
 *  3. 超长输入不许卡死 —— 正则的回溯在长文本上是真实风险
 *
 * 基准时间固定 2026-10-23 14:05（周五），和其它测试保持一致。
 */
class RobustnessTest {

    private val now = LocalDateTime.of(2026, 10, 23, 14, 5)

    private fun p(s: String) = try {
        DateTimeParser.parse(s, now)
    } catch (t: Throwable) {
        throw AssertionError("解析抛异常了：「${s.take(60)}」→ ${t::class.java.simpleName}: ${t.message}", t)
    }

    // ---------------- 1. 空 / 无意义输入 ----------------

    @Test
    fun `空文本和纯噪声返回 null`() {
        val noise = listOf(
            "",
            " ",
            "\n\n\n",
            "\t\t",
            "！@#￥%……&*（）——+",
            "🎉🎉🎉",
            "。。。。。",
            "——————",
            "???",
            "1234567890"
        )
        for (s in noise) {
            val r = p(s)
            // 纯数字不该被当成日程；即使解析出来也不许带"有把握"的分
            if (r != null) {
                assertTrue("纯噪声「$s」不该是高分结果：${r.confidence}", r.confidence < 85)
            }
        }
    }

    // ---------------- 2. 非法日期必须被拒绝 ----------------

    @Test
    fun `不存在的日期不硬凑`() {
        // safeDate 会拒掉，不应该退化成"某个月某一天"
        val cases = listOf(
            "2月30日开会",
            "2月29日开会",      // 2027 不是闰年
            "4月31日交表",
            "13月40日体检",
            "0月0日聚会",
            "2026-13-45 开会",
            "2026年2月30日答辩"
        )
        for (s in cases) {
            val r = p(s)
            if (r != null) {
                val d = r.start.toLocalDate()
                assertTrue(
                    "非法日期「$s」被凑成了 $d",
                    d.monthValue in 1..12 && d.dayOfMonth in 1..31
                )
                // 关键：不能是 3月2日 / 3月1日 这类"溢出顺延"
                assertTrue("非法日期「$s」被溢出顺延成 $d", !(d.monthValue == 3 && d.dayOfMonth <= 2))
            }
        }
    }

    @Test
    fun `非法时间点不硬凑`() {
        val cases = listOf("25:00 开会", "25点开会", "99点开会", "13:99 交作业", "60分后集合")
        for (s in cases) {
            val r = p(s)
            if (r != null && r.hasTime) {
                val t = r.start.toLocalTime()
                assertTrue("「$s」被凑成了 $t", t.hour in 0..23 && t.minute in 0..59)
            }
        }
    }

    // ---------------- 3. 边界时间 ----------------

    @Test
    fun `24点按当天最后一刻处理`() {
        val r = p("10月25日 24:00 前提交")
        assertNotNull("24:00 应该能解析出来", r)
        val d = r!!.start
        assertTrue("24:00 不该溢出到下一天：$d", d.toLocalDate() == LocalDate.of(2026, 10, 25))
        assertTrue("24:00 应落在当天末尾：$d", d.hour == 23)
    }

    @Test
    fun `零点正常解析`() {
        val r = p("10月25日 0点 集合")
        assertNotNull(r)
        assertTrue("0 点被改成了 ${r!!.start.hour} 点", r.start.hour == 0)
    }

    @Test
    fun `跨年月份推断不会算到过去`() {
        // 12月31日 在 10月23日 视角下应该是今年（还没到），不该跳到明年
        val r = p("12月31日 年终总结")
        assertNotNull(r)
        assertTrue("12月31日 应该是今年", r!!.start.year == 2026)

        // 1月5日 今年已经过了，应该推到明年
        val r2 = p("1月5日 开学典礼")
        assertNotNull(r2)
        assertTrue("1月5日 已经过了，应推到 2027，实际 ${r2!!.start.toLocalDate()}", r2.start.year == 2027)
    }

    // ---------------- 4. 星期边界 ----------------

    @Test
    fun `星期八这类非法写法不崩`() {
        val cases = listOf("星期八开会", "周八集合", "礼拜九上课", "周0开会")
        for (s in cases) {
            p(s) // 只要不抛异常
        }
    }

    @Test
    fun `下下周一能解析且在未来`() {
        val r = p("下下周一 上午10点 交材料")
        assertNotNull(r)
        assertTrue("下下周一应在本周之后：${r!!.start.toLocalDate()}", r.start.toLocalDate().isAfter(now.toLocalDate()))
    }

    // ---------------- 5. 超长 / 病态输入（正则回溯与耗时） ----------------

    @Test
    fun `超长文本不会卡死`() {
        val cases = mapOf(
            "长数字串" to "1".repeat(5000),
            "重复点" to "1点".repeat(2000),
            "重复月" to "1月".repeat(2000),
            "重复括号" to "【】".repeat(2000),
            "重复空格+数字" to "1 ".repeat(3000),
            "重复斜杠" to "1/".repeat(3000),
            "超长正文" to "明天下午三点半在图书馆三楼报告厅开会，请全体同学准时参加。".repeat(200)
        )
        for ((name, s) in cases) {
            val t0 = System.nanoTime()
            p(s)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("「$name」耗时 ${ms}ms，超过 2000ms，存在正则回溯风险", ms < 2000)
        }
    }

    // ---------------- 6. 随机 fuzz：只要求不崩 ----------------

    @Test
    fun `随机字符 fuzz 不抛异常`() {
        val pool = "0123456789年月日号点时半今明后天周星期礼拜上下午晚早中午夜分秒在到于" +
                "开会集合培训讲座活动答辩考试交作业提交截止报名签到聚餐例会：" +
                " \n\t-/:.、，。！？（）【】《》「」@#%&*"
        val rnd = Random(20261023)
        repeat(2000) {
            val len = rnd.nextInt(0, 60)
            val sb = StringBuilder(len)
            repeat(len) { sb.append(pool[rnd.nextInt(pool.length)]) }
            p(sb.toString()) // 只要不抛异常
        }
    }

    @Test
    fun `fuzz 出来的结果时间必须合法`() {
        val pool = "0123456789年月日点时半今明后天周星期下午晚中午分" + " 开会集合"
        val rnd = Random(777)
        repeat(800) {
            val len = rnd.nextInt(1, 40)
            val sb = StringBuilder(len)
            repeat(len) { sb.append(pool[rnd.nextInt(pool.length)]) }
            val s = sb.toString()
            val r = p(s) ?: return@repeat
            // 解析成功的，时间必须落在合法范围，且不能离谱到 1900 年之前 / 2200 年之后
            val y = r.start.year
            assertTrue("「$s」算出了非法年份 $y", y in 1900..2200)
            assertTrue("「$s」算出了非法时间 ${r.start}", r.start.hour in 0..23 && r.start.minute in 0..59)
        }
    }

    // ---------------- 7. 前后矛盾检测 ----------------

    @Test
    fun `日期和星期矛盾必须带警告`() {
        // 2026-10-25 是周日，原文却写周六
        val r = p("10月25日 周六 开会")
        assertNotNull(r)
        assertTrue("日期和星期对不上却没报警告", r!!.warnings.isNotEmpty())
        assertTrue("矛盾时应强制人工确认", r.needsConfirm)
    }

    @Test
    fun `已过去的日期必须带警告`() {
        val r = p("10月1日 提交材料")
        assertNotNull(r)
        assertTrue("过去的日期没报警告", r!!.warnings.isNotEmpty())
        assertTrue("过去的日期不该自动入库", r.confidence < 85)
    }

    // ---------------- 8. 回归：本轮实测发现并修掉的缺陷 ----------------

    @Test
    fun `取消类通知不建日程`() {
        // 这些原文没有任何"要做什么"的信息，之前会老老实实建一条日程挂在列表里
        val cancelled = listOf(
            "明天不开了",
            "明天没有会议",
            "取消明天下午的会",
            "本次活动作废",
            "原定培训停办"
        )
        for (s in cancelled) {
            assertNull("「$s」是取消通知，不该建成日程", p(s))
        }
    }

    @Test
    fun `变更类通知仍要解析出新时间`() {
        // 含"改到/推迟"的是变更，不能当取消丢掉
        val r = p("会议改到 10月25日 16:30")
        assertNotNull("变更通知被当成取消丢掉了", r)
        assertTrue("变更后的时间没解析出来：${r!!.start}", r.start.hour == 16 && r.start.minute == 30)
    }

    @Test
    fun `中文数字时间能识别`() {
        val r = p("明天下午三点半在体育馆集合")
        assertNotNull("「下午三点半」没解析出来", r)
        assertTrue("中文数字时间算错了：${r!!.start}", r.start.hour == 15 && r.start.minute == 30)
        assertTrue("中文数字时间不该进自动入库档：${r.confidence}", r.confidence >= 85)
    }

    @Test
    fun `中文数字先别误伤日常用语`() {
        // "一点小事""差点"不是时间
        val r = p("有一点小事想跟你说")
        if (r != null) {
            assertTrue("「一点小事」被当成 1 点了：${r.start}", r.start.hour != 1 || !r.hasTime)
        }
    }

    @Test
    fun `周五前的 前 不该残留在标题里`() {
        val r = p("周五前提交材料")
        assertNotNull(r)
        assertTrue("标题残留了「前」：${r!!.title}", !r.title.startsWith("前"))
        assertTrue("标题应该像一句话：${r.title}", r.title.contains("提交"))
    }

    @Test
    fun `本周X不该被报没说清哪一周`() {
        val r = p("本周五开会")
        assertNotNull(r)
        val hit = r!!.warnings.any { it.contains("没说清") }
        assertTrue("「本周五」是明确写法，不该报「没说清哪一周」：${r.warnings}", !hit)
    }

    @Test
    fun `地点候选不吞时间残渣`() {
        val r = p("明天上午9点到12点在体育馆体检")
        assertNotNull(r)
        // 之前会抽成 "12点在体育馆"
        assertTrue("地点里混进了时间：${r!!.location}", r.location?.contains("点") != true)
        assertTrue("地点应该是体育馆：${r.location}", r.location?.contains("体育馆") == true)

        val r2 = p("会议改到16:30")
        if (r2 != null && r2.hasTime) {
            assertTrue("地点被抽成了时间：${r2.location}", r2.location?.contains(":") != true)
        }
    }
}
