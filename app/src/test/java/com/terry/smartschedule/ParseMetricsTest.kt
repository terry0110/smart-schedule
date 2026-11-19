package com.terry.smartschedule

import com.terry.smartschedule.parse.DateTimeParser
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 解析层的两项度量（为申报材料里的两个指标提供可复现的实测数）：
 *
 *  1. 「单次文本解析平均耗时」——把标注语料反复跑 N 轮取均值。
 *     先热身（JIT 编译热点）再计时，只算 DateTimeParser.parse 本体，
 *     不含通知抓取与入库（那些是 IO，和"文本解析"不是一个东西）。
 *  2. 「本地要素识别准确率」——在带人工标注的语料上按要素逐项对答案：
 *     日期、时间、地点、标题各算各的，对不上就算错，不搞"大致对"。
 *
 * 语料与 NotificationCorpusTest（N01–N14，真实群通知收集）保持一致；
 * 那边改了语料，这边要跟着同步，两边数字才有可比性。
 *
 * 本测试**不断言**：它是度量工具，不是回归防线（防线在 Corpus 那边）。
 * 跑法：./gradlew testDebugUnitTest --tests "*ParseMetrics*"
 * 结果在 app/build/test-results/.../TEST-*.xml 的 system-out 里看。
 */
class ParseMetricsTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 23, 14, 50)
    private val dFmt = DateTimeFormatter.ofPattern("MM-dd")
    private val tFmt = DateTimeFormatter.ofPattern("HH:mm")

    /** 一条标注：原文 + 期望的日期/时间/地点/标题包含字。date 为 null 表示应整体丢弃 */
    private data class Labeled(
        val id: String,
        val raw: String,
        val date: String?,   // "MM-dd"，null = 应解析为 null
        val time: String?,   // "HH:mm"，"ALL" = 全天，null = 不校验
        val loc: String?,    // null = 不该识别出地点
        val titleHas: String?
    )

    private val corpus = listOf(
        Labeled(
            "N01",
            "📢【时光机·首届“名场面”仿拍大赏来啦！】🎬 平时看剧的时候，是不是总有几个片段让你忍不住想自己上去演两把？" +
                "📅 时间：11月1日（周日）下午 📍 地点：具体地点后续在群里通知",
            "11-01", "ALL", null, "名场面"
        ),
        Labeled(
            "N02",
            "【第11届时光机影视协会成立大会举办通知】亲爱的各位前辈、顾问、部长、成员们：承蒙各位的厚爱与支持。" +
                "⏰时间：10月17日14:00 📍地点：新传楼一楼融媒体大厅 👚着装：建议穿着会服出席",
            "10-17", "14:00", "新传楼一楼融媒体大厅", "成立大会"
        ),
        Labeled(
            "N03",
            "📢【宣传部培训通知✨】@所有人 各位同学👋：宣传部将于10月25日（周日）晚上18:30（第十节课）📍知识产权楼321开展培训📚",
            "10-25", "18:30", "知识产权楼321", "培训"
        ),
        Labeled(
            "N04",
            "作业布置 请从课本P22的T3 5个话题中任选一个写段落。Deadline：10.19 24:00 作业邮箱：homework2463@163.com",
            "10-19", "23:59", null, "作业"
        ),
        Labeled(
            "N05",
            "🔴请各宿舍在10月26日中午12:00前按要求完成作品提交，逾期视为无效",
            "10-26", "12:00", null, null
        ),
        Labeled("N06", "⏳ 活动时间：10月16日—10月26日", "10-16", "ALL", null, null),
        Labeled(
            "N07",
            "另外，10月31日（周六）下午，【学院团委书记严梓菡老师】将给大家开一次简短的工作启动大会，" +
                "请全体同学预留出时间，按时到场，时间地点另行通知~",
            "10-31", "ALL", null, "启动大会"
        ),
        Labeled(
            "N08",
            "@所有人 麻烦大家填写一下11-12月进组意愿统计表，方便编导部确定剧本数量，ddl今晚20:00",
            "10-23", "20:00", null, "进组"
        ),
        Labeled(
            "N09",
            "培训时间：2026年10月21日（星期三）18:30—20:00 培训形式：腾讯会议线上培训（会议号：8999-4168-389，密码：261021）",
            "10-21", "18:30", null, "培训"
        ),
        Labeled(
            "N10",
            "@所有人 国术协会明天的活动表格已发！欢迎有兴趣者可以前来体验！明日的活动时间是下午3点至下午5点半。",
            "10-24", "15:00", null, "国术"
        ),
        Labeled(
            "N11",
            "✨指尖上的创意时光！手工社即将开展第一次手工活动啦～📅 活动时间：本周日晚19:00-22:30 📍 活动地点：学生活动中心302 " +
                "🎯 温馨提示：本次活动限50人参与，报名截止时间为周四23:59，先到先得哦！",
            "10-25", "19:00", "学生活动中心302", "手工"
        ),
        Labeled(
            "N12",
            "宣传部通知群: 阿May: 宣传部将于10月25日（周日）晚上18:30在知识产权楼321开展培训",
            "10-25", "18:30", "知识产权楼321", "培训"
        ),
        Labeled(
            "N13",
            "@所有人 中心特邀心理专家为心理委员开展专题培训，详情见文件通知。",
            null, null, null, null
        ),
        Labeled(
            "N14",
            "3天后的中午12点半和导师碰面聊课题",
            "10-26", "12:30", null, "导师"
        )
    )

    /** 逐要素对答案，返回 (该要素应校验数, 对的数) */
    private fun grade(): Map<String, Pair<Int, Int>> {
        var dOk = 0; var dN = 0
        var tOk = 0; var tN = 0
        var lOk = 0; var lN = 0
        var tiOk = 0; var tiN = 0
        for (c in corpus) {
            val r = DateTimeParser.parse(c.raw, now)
            if (c.date == null) {
                // 期望丢弃：解析出任何东西都算日期错
                dN++
                if (r == null) dOk++
                continue
            }
            dN++
            if (r != null && dFmt.format(r.start) == c.date) dOk++
            if (r == null) continue

            if (c.time != null) {
                tN++
                val ok = if (c.time == "ALL") !r.hasTime else r.hasTime && tFmt.format(r.start) == c.time
                if (ok) tOk++
            }
            lN++
            if (r.location == c.loc) lOk++   // loc=null 时识别不出才算对
            if (c.titleHas != null) {
                tiN++
                if (r.title.contains(c.titleHas)) tiOk++
            }
        }
        return mapOf(
            "日期" to (dOk to dN),
            "时间" to (tOk to tN),
            "地点" to (lOk to lN),
            "标题" to (tiOk to tiN)
        )
    }

    @Test
    fun `度量：要素识别准确率`() {
        val g = grade()
        var all = 0; var ok = 0
        println("==== 要素识别准确率（语料 N01–N14，共 ${corpus.size} 条真实群通知）====")
        for ((k, v) in g) {
            println("  $k: ${v.first}/${v.second}")
            all += v.second; ok += v.first
        }
        val pct = 100.0 * ok / all
        println(String.format("  合计: %d/%d = %.1f%%", ok, all, pct))
    }

    @Test
    fun `度量：单次文本解析平均耗时`() {
        // 每轮把 14 条语料各解析一次；先热身后计时
        val warmupRounds = 400
        val timedRounds = 3000

        repeat(warmupRounds) { corpus.forEach { DateTimeParser.parse(it.raw, now) } }

        var best = Long.MAX_VALUE
        var totalNs = 0L
        var count = 0L
        repeat(timedRounds) {
            val t0 = System.nanoTime()
            corpus.forEach { DateTimeParser.parse(it.raw, now) }
            val roundNs = System.nanoTime() - t0
            totalNs += roundNs
            count += corpus.size
            if (roundNs < best) best = roundNs
        }

        val avgUs = totalNs / count / 1000.0
        val bestAvgUs = best.toDouble() / corpus.size / 1000.0
        val chars = corpus.sumOf { it.raw.length }
        println("==== 单次文本解析平均耗时 ====")
        println(String.format("  语料 %d 条 / %d 字符，热身 %d 轮 + 计时 %d 轮（JVM，热机）", corpus.size, chars, warmupRounds, timedRounds))
        println(String.format("  平均 %.1f µs/条（%.3f ms）", avgUs, avgUs / 1000.0))
        println(String.format("  最快一轮均值 %.1f µs/条", bestAvgUs))
        println(String.format("  吞吐约 %.0f 条/秒", 1e9 / (totalNs / count)))
    }
}
