package com.terry.smartschedule.parse

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 中文日程文本解析器（纯 Kotlin，无 Android 依赖，可单元测试）。
 *
 * 覆盖规则：
 *  - 绝对日期：2026-10-25 / 2026/10/25 / 2026年10月25日 / 10月25日 / 10.19 / 10-25
 *  - 相对日期：今天 明天 后天 大后天 今天/今晚/明早、N天后
 *  - 星期：周五 星期五 礼拜天 下周三 下下周一 这个周日
 *  - 时间：15:00 15：00 15点 15点半 3点30分 下午3点 晚上8点 中午12点半 24:00
 *  - 时段修正：凌晨/早上/上午/中午/下午/傍晚/晚上
 *  - 地点：地点：X / 📍X / 在X开会 / 到X集合 / @X
 *
 * 设计要点：群通知正文往往是长篇通告，标题必须"像一句话"而不是整段。
 * 见 buildTitle()：优先取开头【】里的中文标题，否则取首行并清掉时间/地点残渣。
 */
object DateTimeParser {

    /**
     * 日期是从哪来的。档位越靠前越可信：
     * 原文写死的日期，直接采信；靠"周X""明天"推算出来的，必须让用户看一眼。
     */
    enum class DateSource {
        /** 原文写死了年月日，最可信 */
        ABS,

        /** 今天/明天/后天/N天后，确定性高但依赖基准日 */
        REL,

        /** 只有"周X"，是靠基准日推出来的，最容易错 */
        WEEK,

        /**
         * 只有"几号"，没写月份："27号"。
         * 日期本身明确，但落在哪个月是推的（本月没过就本月，过了就下个月）。
         */
        DAY_ONLY,

        /** 没看到任何日期，只看到时间，按"今天或明天"猜的 */
        TIME_ONLY,

        /** 完全没有日期时间，靠兜底 */
        NONE
    }

    data class Result(
        val title: String,
        /** 开始时间的时间戳（毫秒） */
        val startAt: Long,
        /** 原文里是否给出了具体时间点；false 表示只有日期（全天日程） */
        val hasTime: Boolean,
        val location: String?,
        /** 0-100 置信度：>=85 可放心自动入库，55-84 需人工确认，<55 基本是噪声 */
        val confidence: Int,
        /** 日期来源，决定要不要强制人工确认 */
        val dateSource: DateSource = DateSource.NONE,
        /** 一句话说明日期是怎么来的，例如「按「下周三」推的」 */
        val dateHint: String = "",
        /**
         * 需要用户核对的问题，例如"原文日期和星期对不上"。
         * 非空就表示：不许静默入库，必须摆到用户面前让他确认。
         */
        val warnings: List<String> = emptyList(),
        /**
         * 结束时间；0 表示原文没写结束时间。
         * 冲突检测遇到 0 会按默认时长算，别把它当成"全天"。
         */
        val endAt: Long = 0L
    ) {
        val start: LocalDateTime
            get() = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(startAt), ZoneId.systemDefault()
            )

        /** 有警告就必须人工过一眼 */
        val needsConfirm: Boolean get() = warnings.isNotEmpty()
    }

    /** 标题最多这么长，超了在句读处截断 */
    private const val TITLE_MAX = 24

    // ---------- 正则（改这里请同步回归测试 DateTimeParserTest + 通知语料） ----------
    private val RE_FULL = Regex("""(?<y>\d{4})\s*[-/年.]\s*(?<m>\d{1,2})\s*[-/月.]\s*(?<d>\d{1,2})\s*[日号]?""")
    private val RE_MD = Regex("""(?<m>\d{1,2})\s*月\s*(?<d>\d{1,2})\s*[日号]?""")
    /** 10-25 / 10/25；后面跟"月"的是月份区间（11-12月），不能当日期 */
    private val RE_MD2 = Regex("""(?<![\d:A-Za-z.])(?<m>\d{1,2})\s*[/-]\s*(?<d>\d{1,2})(?![\d:月A-Za-z])""")
    // 10.19 这种点分日期。
    // 前面不能紧邻数字/点/字母 —— 少了"字母"这半，v1.8、v2.0 里的 1.8 全会被当成
    // "1 月 8 日"，于是"smart-schedule-v1.8-debug.apk.1 已保存至…"这种下载完成通知
    // 会被建成一条日程（真机上就是这么误报的）。
    // 后面要紧跟字母也排除 —— `12.31lyd` 是微信昵称，不是 12 月 31 日；
    // 同时不影响 1.8-1.10 的区间写法（那里是"-数字"）。
    private val RE_MD3 = Regex("""(?<![\d.A-Za-z])(?<m>\d{1,2})\s*\.\s*(?<d>\d{1,2})(?![\d.A-Za-z]|-[A-Za-z])""")
    /**
     * 只有"几号"，没写月份："27号"、"15日"。
     *
     * 这是真机上暴露的漏洞：原文「截止到27号晚23:59」只认出 23:59，
     * 日期按"今天"算，地点还变成了「27号晚23:59」。
     *
     * 后面排除一批量词 —— "3号线""2号门""5号楼""8号厅"里的"号"不是日期。
     * 前面排除数字/月/点/冒号/斜杠，避免从 "10月25日"（RE_MD 已处理）、"10.25" 里再切一段。
     */
    private val RE_DAY_ONLY = Regex(
        """(?<![\d.:：/])(?<d>\d{1,2})\s*[号日](?![号日线门楼院栋厅床柜桌窗口])"""
    )
    private val RE_NDAY = Regex("""(?<n>\d{1,2}|[一二两三四五六七八九十]+)\s*天[之以]?后(?:的)?""")
    private val RE_WEEK = Regex("""(?<p>下下|下个|下|本|这|这个|上个|上)?\s*(?:周|星期|礼拜)\s*(?<w>[一二三四五六日天1-7])""")
    private val RE_ABS_TIME = Regex("""(?<h>\d{1,2})\s*[:：]\s*(?<mi>\d{2})""")
    private val RE_CN_TIME = Regex("""(?<h>\d{1,2})\s*[点时](?:(?<half>半)|(?<mi>\d{1,2})\s*分?)?""")
    /**
     * 中文数字时间：三点、两点半、十点一刻（暂不支持"一刻"）、下午四点二十。
     *
     * 之前只认阿拉伯数字，导致"下午三点到五点值班"整条解析不出来——
     * 而中文通知里"下午三点"比"下午3点"还常见，这是真实漏识别的主要来源之一。
     *
     * 风险："一点小事"里的"一点"会被误认。所以下面 requireTimeContext() 还要再过一道。
     */
    private val RE_CN_TIME_CN = Regex(
        """(?<h>[一二两三四五六七八九十]{1,3})\s*[点时](?:(?<half>半)|(?<mi>[一二三四五六七八九十]{1,3})\s*分?)?"""
    )
    /**
     * 省略"点"字的口语写法：晚上7 / 下午3 / 明早8。
     * 后面不能接"点时分"——否则"下午3点半"会被截成 3 点，把半小时丢掉。
     */
    private val RE_PERIOD_TIME = Regex(
        """(?<p>凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|夜晚)\s*(?<h>\d{1,2})\s*(?![\d:年月日号点时分])"""
    )
    private val RE_PERIOD = Regex("""(凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|夜晚|今晚|明晚|昨晚)""")

    /** 地点：X（必须带冒号，"时间地点另行通知"不能被识别成地点） */
    private val RE_LOC1 = Regex("""(?:地点|地址|位置|会场)\s*[:：]\s*([^\s,，。;；]{2,20})""")
    /** 没有冒号的写法："地点 图书馆"。LOC_FAKE_WORDS 会挡住"地点另行通知" */
    private val RE_LOC1B = Regex("""(?:地点|地址|位置|会场)\s+([^\s,，。;；]{2,20})""")
    private val RE_LOC_PIN = Regex("""(?:📍|🧭|🏫|🏢)\s*([^\s,，。;；]{2,20})""")
    private val RE_LOC2 = Regex(
        """(?:在|到)\s*([^\s,，。;；]{2,20}?)(?=开会|集合|上课|见|碰|吃|参加|讨论|会议|活动|面试|报到|办理|聚餐|培训|考试|体检|值班|开展|举办|进行|签到|看|逛|听|买|玩|找|约|$|[,，。;；])"""
    )
    private val RE_AT = Regex("""@\s*([^\s,，。;；]{1,20})""")
    /** 地点候选里出现这些词，说明是"另行通知""群里通知"一类的托辞，不是地点 */
    private val LOC_FAKE_WORDS = listOf("通知", "后续", "待定", "另行", "群里", "群内", "暂无", "另告", "待告")
    /** 地点候选的尾巴如果是动作，说明把动作吃掉了一部分，得剥掉 */
    private val LOC_TAIL_VERBS = listOf(
        "开展", "举办", "进行", "签到", "参加", "开会", "集合", "上课", "聚餐",
        "培训", "面试", "报到", "办理", "体检", "考试", "值班", "领取", "提交"
    )
    private val LOC_LABEL = Regex("""^(?:活动|培训|会议)?(?:地点|地址|位置)\s*[:：]?\s*""")
    /** 这些词是状语/元信息，不是地点（"玩到一起""在群里""线上"） */
    private val LOC_STOP = listOf(
        "一起", "时候", "现场", "这里", "那里", "群里", "群内", "线上", "线下",
        "网上", "附近", "旁边", "此", "内", "外", "手", "口", "前", "后", "上", "下"
    )

    /** 地点候选开头的时间残渣："12点" "16:30" "9点到12点" "五点"（中文数字也算） */
    private val RE_LEAD_TIME = Regex(
        """^(?:\d{1,2}\s*[:：]\s*\d{2}""" +
            """|\d{1,2}\s*[点时](?:半|\d{1,2}\s*分?)?""" +
            """|[一二两三四五六七八九十]{1,3}\s*[点时](?:半|[一二三四五六七八九十]{1,3}\s*分?)?""" +
            """|[\d一二两三四五六七八九十]{1,3}\s*[-~到至]\s*[\d一二两三四五六七八九十]{1,3}\s*[点时]?""" +
            """)[\s,，]*"""
    )
    private val RE_LEAD_PREP = Regex("""^(?:在|到|于|从)\s*""")
    /** 变更通知的尾巴："会议改到" → "会议"（时间已经被挖走了） */
    private val RE_CHANGE_TAIL = Regex("""(?:改到|改至|推迟到|延期到|调整到|变更到|顺延到)\s*""")
    /** 时段词 + 中文数字时间，标题清理用（"下午两点半"）；不单独清"一点"，避免误伤"一点小事" */
    private val RE_PERIOD_CN_TIME = Regex(
        """(?:凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|夜晚|今晚|明晚)\s*[一二两三四五六七八九十]{1,3}\s*(?:[点时]\s*(?:半|[一二三四五六七八九十]{1,3}\s*分?)?)?"""
    )
    private val RE_SPEAKER = Regex("""^[^\s:：]{1,12}\s*[:：]\s*""")
    private val RE_TAG = Regex("""[【\[（(][^】\]）)]{0,40}[】\]）)]""")
    /** 开头【】里的内容，用来当标题（限定长度与字符，避免抓到整段正文） */
    private val RE_BRACKET = Regex("""【([^】\n]{2,30})】""")
    private val RE_TIMETAIL = Regex("""^\s*(?:之前|以前|前|整|左右|许|钟)""")
    private val RE_LEAD = Regex("""^(?:的|了|着|把|是|和|跟|与|去|要|一起)\s*""")
    /** 时间被挖掉后残留的"时/点"：只在前一个字符是空格或数字时才吃，且躲开"时间""时候""时光" */
    private val RE_DIAN_LEFTOVER = Regex("""(?<=[\s\d])[点时](?![间候光段期评子头赞击名亮钟心])\s*(?:半|\d{1,2}\s*分?)?\s*""")
    private val RE_WEEK_LEFTOVER = Regex("""\s*(?:下下|下个|下|本|这|上个|上)?\s*[周星期礼拜]\s*(?:[一二三四五六日天]|\d)\s*""")
    /** "周五前""周五之前"——日期片段后面要一并吃掉的尾巴 */
    private val RE_AFTER_TAIL = Regex("""^\s*(?:之前|以前|前)""")
    /** 时间区间的后半段："9:00-11:00" 的 "-11:00"、"三点到五点" 的 "到五点" */
    private val RE_TIME_RANGE = Regex(
        """^\s*(?:到|至|-|–|~|～)\s*""" +
            """(?:(?<p>凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|夜晚)\s*)?""" +
            """(?<h>\d{1,2}|[一二两三四五六七八九十]{1,3})\s*""" +
            """(?::\s*(?<mi1>\d{2})|[点时]\s*(?:(?<half>半)|(?<mi2>\d{1,2})\s*分?)?)"""
    )
    /** 取消类通知：命中这些就直接不建日程 */
    private val CANCEL_WORDS = listOf(
        "取消", "作废", "停办", "不开了", "不举行", "不再举行",
        "没有会议", "没有活动", "没有安排"
    )
    /** 变更类关键词：出现这些说明是"改时间"，不能当取消处理 */
    private val CHANGE_WORDS = listOf(
        "改到", "改期", "改至", "变更到", "调整到", "推迟", "延期", "顺延", "延后"
    )
    /** 中文数字时间后面常跟的词，用来判断"三点"到底是不是钟点 */
    private val AFTER_TIME_WORDS = listOf(
        "开会", "集合", "上课", "开始", "结束", "签到", "出发", "例会", "聚餐",
        "活动", "会议", "面试", "答辩", "典礼", "升旗", "报名", "截止",
        "培训", "体检", "考试", "值班", "领取", "提交", "交", "见", "到"
    )
    /** "在 前"（日期被挖走后的残留） */
    private val RE_ZAI_QIAN = Regex("""在\s*(?:前|之前|以前)""")
    /** 标题里残留的字段名 */
    private val RE_LABEL = Regex("""(?:活动|培训|会议|集合|讲座)?(?:时间|地点|地址|形式|内容|人员|对象|着装|联系人)\s*[:：]?""")
    private val RE_SEP = Regex("""^\s*[—–\-~～·、,，]+\s*|\s*[—–\-~～·、,，]+\s*$""")
    private val RE_AT_NOISE = Regex("""@\s*(?:所有人|全体成员|每人)\s*""")
    /** 时间/地点被挖走后的残留虚词："宣传部将于 开展培训" */
    private val RE_FILLER = Regex("""(?:将于|将在|于)\s+""")
    /** 地点清洗专用：括号不算可裁标点（"海底捞（万象城店）"） */
    private val TRAIL_NO_BRACKET =
        " \t\n,，。.、;；:：-—_~·!！?？\"'“”‘’".toCharArray()
    private val WHITESPACE = Regex("""\s+""")
    private val CJK_GAP = Regex("""(?<=[\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])""")

    private val NOISE_PREFIX = listOf(
        "提醒我", "记得", "别忘了", "别忘记", "要记得", "需要", "请",
        "帮我", "麻烦", "我想", "我要", "安排", "有个", "有一个", "准时", "按时",
        "另外", "此外", "还有", "ps", "PS", "ddl", "DDL"
    )
    /** 带这些词的行才算"真正在说一件事"，用来跳过宣传口号；TextFilter 判闲聊时也用它 */
    internal val EVENT_WORDS = listOf(
        "活动", "会议", "大会", "培训", "讲座", "面试", "考试", "聚餐", "聚会",
        "典礼", "比赛", "演出", "报名", "提交", "作业", "打卡", "值班", "答辩",
        "体检", "开会", "集合", "上课", "课程", "排练", "签到", "汇报", "例会",
        "班会", "党课", "招新", "换届", "截止", "领取", "填报", "填写"
    )

    /** 【】里出现这些就不是标题，是角标 */
    private val TAG_BLACKLIST = listOf(
        "提醒", "通知", "公告", "重要", "提示", "注意", "活动", "会议", "转发", "置顶"
    )
    private val REL_DAYS = listOf(
        "大后天" to 3L, "后天" to 2L, "明天" to 1L, "明日" to 1L, "明早" to 1L, "明晚" to 1L,
        "今天" to 0L, "今日" to 0L, "今晚" to 0L, "今早" to 0L, "当天" to 0L
    )
    private val TRAIL = " \t\n,，。.、;；:：-—_~·!！?？\"'“”‘’（）()【】[]".toCharArray()
    /** 周几的中文数字，下标 0..6 对应周一..周日 */
    private val WEEK_CHARS = "一二三四五六日"
    private val CN_NUM = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10
    )

    // ---------- 对外入口 ----------
    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now()): Result? {
        val text = normalize(raw)
        if (text.isEmpty()) return null

        // 取消/作废类通知不该建日程。
        // 之前"明天不开了"会老老实实建一条日程挂在列表里，用户看到会骂人。
        if (isCancellationNotice(text)) return null

        val today = now.toLocalDate()
        val warnings = ArrayList<String>(4)

        var date: LocalDate? = null
        var dateSpan: Pair<Int, Int>? = null
        var dateSource = DateSource.NONE
        /** 一句话说明日期依据，进「待确认」时当理由展示 */
        var dateHint = ""

        // 1) 完整年月日：原文写死的，最可信
        RE_FULL.find(text)?.let { m ->
            val ld = safeDate(m.groupInt("y"), m.groupInt("m"), m.groupInt("d"))
            if (ld != null) {
                date = ld
                dateSource = DateSource.ABS
                dateSpan = m.span()
                dateHint = "原文写的是 ${mdWeek(ld)}"
            }
        }

        // 2) 月日 / 月-日 / 月.日（年份按"未过则今年，已过则明年"推断）
        if (date == null) {
            for (rx in listOf(RE_MD, RE_MD2, RE_MD3)) {
                val m = rx.find(text) ?: continue
                val mo = m.groupInt("m")
                val d = m.groupInt("d")
                val crossYear = mo < now.monthValue
                val y = if (crossYear) now.year + 1 else now.year
                val ld = safeDate(y, mo, d) ?: continue
                date = ld
                dateSource = DateSource.ABS
                dateSpan = m.span()
                dateHint = if (crossYear) {
                    "原文只写了 ${mo}月${d}日，年份按 $y 推的"
                } else {
                    "原文写的是 ${mdWeek(ld)}"
                }
                if (crossYear) {
                    warnings += "原文没写年份，${mo}月${d}日 今年已经过了，我按 $y 年算的"
                }
                break
            }
        }

        // 2.5) 只有"几号"，没写月份。月份靠推断：本月那天还没到（含今天）就用本月，
        //      已经过了就顺延到下个月。
        if (date == null) {
            RE_DAY_ONLY.find(text)?.let { m ->
                val d = m.groupInt("d")
                val raw = m.value.trim()
                var ld = safeDate(now.year, now.monthValue, d)
                val sameMonthOk = ld != null && !ld.isBefore(today)
                if (!sameMonthOk) {
                    val nm = today.plusMonths(1)
                    ld = safeDate(nm.year, nm.monthValue, d)
                }
                if (ld != null) {
                    date = ld
                    dateSource = DateSource.DAY_ONLY
                    dateSpan = m.span()
                    dateHint = "原文只写了「$raw」，没写月份"
                    // 只有顺延到下个月才需要提醒：本月的日子不用问
                    if (!sameMonthOk) {
                        warnings += "原文只写了「$raw」，这个月已经过了，我按 ${mdWeek(ld)} 算的"
                    }
                }
            }
        }

        // 3) 相对日：今天/明天/后天/N天后
        if (date == null) {
            for ((kw, off) in REL_DAYS) {
                val i = text.indexOf(kw)
                if (i >= 0) {
                    date = today.plusDays(off)
                    dateSpan = i to (i + kw.length)
                    dateSource = DateSource.REL
                    dateHint = "按「$kw」推的"
                    break
                }
            }
        }

        // 4) N 天后
        if (date == null) {
            RE_NDAY.find(text)?.let { m ->
                val n = cnToInt(m.groups["n"]!!.value)
                if (n in 1..365) {
                    date = today.plusDays(n.toLong())
                    dateSpan = m.span()
                    dateSource = DateSource.REL
                    dateHint = "按「${m.value.trim()}」推的"
                }
            }
        }

        // 5) 星期几 —— 纯推算，最容易错，必须留痕
        if (date == null) {
            RE_WEEK.find(text)?.let { m ->
                val p = m.groups["p"]?.value ?: ""
                val wi = weekdayIdx(m.groups["w"]!!.value)
                if (wi in 1..7) {
                    val (d, vague) = resolveWeekday(today, wi, p)
                    date = d
                    // "周五前提交"里的"前"也要算进日期片段，否则标题会剩下一个孤零零的"前"
                    dateSpan = extendWeekTail(text, m.range.first, m.range.last + 1)
                    dateSource = DateSource.WEEK
                    dateHint = "按「${text.substring(dateSpan.first, dateSpan.second).trim()}」推的"
                    // 只有"说不清是哪一周"的情况才打断用户；"下周三"这种明确的只走待确认
                    if (vague) {
                        warnings += "原文只写了「${m.value.trim()}」，没说清是哪一周，" +
                            "我按 ${mdWeek(d)} 算的，对不上就手动改"
                    }
                }
            }
        }

        // ---------- 时间 ----------
        var hour: Int? = null
        var minute = 0
        var endHour: Int? = null
        var endMinute = 0
        var timeSpan: Pair<Int, Int>? = null

        RE_ABS_TIME.find(text)?.let { m ->
            val h = m.groupInt("h")
            val mi = m.groupInt("mi")
            // 24:00 表示当天结束，按 23:59 存
            if (h == 24 && mi == 0) {
                hour = 23
                minute = 59
                timeSpan = m.span()
            } else if (h in 0..23 && mi in 0..59) {
                hour = h
                minute = mi
                timeSpan = m.span()
            }
        }

        if (hour == null) {
            RE_CN_TIME.find(text)?.let { m ->
                val h = m.groupInt("h")
                val half = m.groups["half"]?.value
                val miStr = m.groups["mi"]?.value
                val mi = when {
                    half != null -> 30
                    miStr != null -> miStr.toInt()
                    else -> 0
                }
                if (h in 0..24 && mi in 0..59) {
                    hour = h
                    minute = mi
                    var end = m.range.last + 1
                    // 把"点之前""点半左右"这类尾巴一起吃掉
                    val tail = RE_TIMETAIL.find(text.substring(end))
                    if (tail != null && tail.range.first == 0) end += tail.range.last + 1
                    timeSpan = m.range.first to end
                }
            }
        }

        // 中文数字时间："下午三点""两点半""十点"
        if (hour == null) {
            RE_CN_TIME_CN.find(text)?.let { m ->
                val h = cnToInt(m.groups["h"]!!.value)
                val half = m.groups["half"]?.value
                val miStr = m.groups["mi"]?.value
                val mi = when {
                    half != null -> 30
                    miStr != null -> cnToInt(miStr)
                    else -> 0
                }
                // "一点小事""差点迟到"这类不是时间，得有上下文才认
                if (h in 0..24 && mi in 0..59 && hasTimeContext(text, m.range.first, m.range.last)) {
                    hour = h
                    minute = mi
                    timeSpan = m.range.first to (m.range.last + 1)
                }
            }
        }

        // "晚上7"这种省略"点"的写法，放在最后兜底
        if (hour == null) {
            RE_PERIOD_TIME.find(text)?.let { m ->
                val h = m.groupInt("h")
                if (h in 0..23) {
                    hour = h
                    minute = 0
                    timeSpan = m.range.first to (m.range.last + 1)
                }
            }
        }

        val pm = RE_PERIOD.find(text)
        val period = pm?.value
        val periodSpan = pm?.span()
        val rawHour = hour
        if (rawHour != null && period != null) {
            hour = when {
                period == "凌晨" || period == "清晨" -> if (rawHour == 12) 0 else rawHour
                period == "早上" || period == "早晨" || period == "上午" -> rawHour
                period == "中午" -> if (rawHour in 1..3) rawHour + 12 else rawHour
                else -> if (rawHour < 12) rawHour + 12 else rawHour
            }
        } else if (rawHour != null && rawHour in 1..11 &&
            (text.contains("晚") || text.contains("夜"))
        ) {
            hour = rawHour + 12
        }
        if (hour == 24) hour = 0

        // 时间区间："9:00-11:00" / "三点到五点" 的后半段既要从标题里挖掉，
        // 也要记住结束时间。冲突检测现在看整段，不再只看开始时间。
        timeSpan?.let { sp ->
            if (sp.second < text.length) {
                val tail = RE_TIME_RANGE.find(text.substring(sp.second))
                if (tail != null && tail.range.first == 0) {
                    timeSpan = sp.first to (sp.second + tail.range.last + 1)
                    if (hour != null) {
                        parseRangeEnd(tail, hour!!)?.let { (h, mi) ->
                            endHour = h
                            endMinute = mi
                        }
                    }
                }
            }
        }

        val hasTime = hour != null

        // 只有时间没有日期：别装作知道是哪天，明说按今天/明天猜的
        if (date == null && hasTime) {
            dateSource = DateSource.TIME_ONLY
            dateHint = "原文只有时间，日期是推的"
            val cand = LocalDateTime.of(today, LocalTime.of(hour!!, minute))
            val guessToday = !cand.isBefore(now)
            date = if (guessToday) today else today.plusDays(1)
            warnings += "原文没写日期，只写了时间，我按${if (guessToday) "今天" else "明天"}算的，" +
                "也就是 ${mdWeek(date)}"
        }

        if (date == null) return null

        // ---------- 日期可靠性校验：算错了要摆出来，不能装没事 ----------
        // a) 原文同时写了日期和星期，两者应当吻合
        if (dateSource == DateSource.ABS) {
            RE_WEEK.find(text)?.let { wm ->
                val wi = weekdayIdx(wm.groups["w"]!!.value)
                if (wi in 1..7 && wi != date.dayOfWeek.value) {
                    warnings += "原文写的是 ${mdWeek(date)}，但同一段里还提到了「${wm.value.trim()}」，" +
                        "两者对不上，请核对到底是哪天"
                }
            }
        }

        // b) 算出来的日期已经过去了，多半是月份/星期理解偏了
        if (date.isBefore(today) && dateSource != DateSource.REL) {
            warnings += "算出来的日期 ${mdWeek(date)} 已经过了，确认一下是不是把月份搞错了"
        }

        // c) 太远的日期往往是误把"11-12月"这类区间当成了具体某天
        if (ChronoUnit.DAYS.between(today, date) > 180) {
            warnings += "算出来的日期在 ${ChronoUnit.DAYS.between(today, date)} 天后，" +
                "如果原文是活动周期而不是某一天，请手动改"
        }

        // ---------- 地点（先抽出来，才能从标题里剔除） ----------
        // 传"已识别出的日期/时间原文"，用于把那些其实是时间的假地点挡掉
        val takenText = listOfNotNull(
            dateSpan?.let { text.substring(it.first, it.second).trim() },
            timeSpan?.let { text.substring(it.first, it.second).trim() }
        )
        val locHit = findLocation(text, takenText)
        val loc = locHit?.first
        val locSpan = locHit?.second

        // ---------- 标题 ----------
        // 标题只挖日期和时间，**不挖地点**。
        // 地点是句子的一部分，挖掉会把话挖断 —— 真机上撞到的：
        // "您的快递已到菜鸟驿站，请于10月25日前取件" → 标题变成
        // "您的快递已 ，请前取件"（"到菜鸟驿站"被当地点抽走，句子散了）。
        val title = buildTitle(text, listOfNotNull(dateSpan, timeSpan, periodSpan), raw)

        // ---------- 置信度 ----------
        var confidence = when (dateSource) {
            // 原文写死的日期，最放心
            DateSource.ABS -> if (hasTime) 90 else 55
            DateSource.REL -> if (hasTime) 85 else 55
            // 靠星期几推出来的，一律不够自动入库的资格
            DateSource.WEEK -> if (hasTime) 72 else 50
            // 日期本身明确，只有月份是推的：比"周X"可靠、比写死年月日差
            DateSource.DAY_ONLY -> if (hasTime) 82 else 50
            DateSource.TIME_ONLY -> 60
            DateSource.NONE -> 45
        }
        // 有任何需要人看一眼的问题，就压到"必须确认"区间
        if (warnings.isNotEmpty()) confidence = minOf(confidence, 70)

        val hh = hour ?: 9
        val mm = if (hour == null) 0 else minute
        val start = LocalDateTime.of(date, LocalTime.of(hh, mm))
        val startAt = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // endHour 可能 >=24（跨天），转成"日期 + 天数 + 小时"
        val endAt = endHour?.let { total ->
            val days = total / 24
            val h = total % 24
            LocalDateTime.of(date, LocalTime.of(h, endMinute))
                .plusDays(days.toLong())
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                .takeIf { it > startAt } ?: 0L
        } ?: 0L
        return Result(
            title = title,
            startAt = startAt,
            hasTime = hasTime,
            location = loc,
            confidence = confidence,
            dateSource = dateSource,
            dateHint = dateHint,
            warnings = warnings,
            endAt = endAt
        )
    }

    /**
     * 解析时间区间的结束钟点。
     *
     * @return 总小时数（可能 >=24 表示跨天）+ 分钟；解析不出来返回 null
     */
    private fun parseRangeEnd(m: MatchResult, startHour: Int): Pair<Int, Int>? {
        val hRaw = m.groups["h"]?.value ?: return null
        val h0 = hRaw.toIntOrNull() ?: cnToInt(hRaw)
        val mi = when {
            m.groups["half"]?.value != null -> 30
            m.groups["mi1"]?.value != null -> m.groups["mi1"]!!.value.toInt()
            m.groups["mi2"]?.value != null -> {
                val s = m.groups["mi2"]!!.value
                s.toIntOrNull() ?: cnToInt(s)
            }
            else -> 0
        }
        if (h0 !in 0..24 || mi !in 0..59) return null

        val p = m.groups["p"]?.value
        var h = when {
            p == null -> h0
            p == "凌晨" || p == "清晨" -> if (h0 == 12) 0 else h0
            p == "早上" || p == "早晨" || p == "上午" -> h0
            p == "中午" -> if (h0 in 1..3) h0 + 12 else h0
            else -> if (h0 < 12) h0 + 12 else h0
        }
        if (p == null) {
            // 没写上下午：按开始时间继承。
            // 下午3点-5点 → 15:00-17:00；晚上8点-10点 → 20:00-22:00；
            // 23:00-01:00 → 次日 01:00（不能先 +12 再跨天，否则会变成次日 13:00）
            if (startHour >= 12 && h < 12) {
                val pm = h + 12
                if (pm > startHour) h = pm
            } else if (startHour < 12 && h <= startHour && h + 12 > startHour) {
                // 上午11点到1点 → 13:00，而不是次日凌晨
                h += 12
            }
            if (h <= startHour) h += 24
        } else if (h <= startHour) {
            // 明确写了时段，但仍可能跨天（23:00-凌晨1点）
            h += 24
        }
        return h to mi
    }

    /**
     * 把"周X + 前缀"换算成具体日期。
     * @return first = 日期；second = 这个换算是否含糊（含糊的必须让用户确认）
     */
    private fun resolveWeekday(today: LocalDate, target: Int, prefix: String): Pair<LocalDate, Boolean> {
        var delta = target - today.dayOfWeek.value
        when {
            prefix.startsWith("下下") -> return today.plusDays((delta + 14).toLong()) to false
            prefix.startsWith("下") -> return today.plusDays((delta + 7).toLong()) to false
            prefix.startsWith("上") -> return today.plusDays((delta - 7).toLong()) to false
            // "本周/这周"是明确指定，不能往前推也不能算"说不清"。之前这里走了下面那条
            // 通用分支，导致"本周五"被报成"没说清是哪一周"，属于胡说。
            prefix.startsWith("本") || prefix.startsWith("这") -> {
                val d = if (delta < 0) delta + 7 else delta
                return today.plusDays(d.toLong()) to false
            }
        }
        // 没前缀：先落在本周，过了就推到下周
        if (delta < 0) delta += 7
        // 只有"往后推 4 天以上"才真有歧义（今天周五说"周二"，可能是本周二也可能是下周二）
        // delta == 0 表示就是今天，语义明确，不该打扰用户
        val vague = delta >= 4
        return today.plusDays(delta.toLong()) to vague
    }

    private fun mdWeek(d: LocalDate): String =
        "${d.monthValue}月${d.dayOfMonth}日（周${WEEK_CHARS[d.dayOfWeek.value - 1]}）"

    // ---------- 地点 ----------
    /**
     * 找地点。
     *
     * @param takenText 已经识别出的日期/时间**原文**。
     *        提取出的地点里只要裹着它，就说明这段其实是时间不是地点 ——
     *        「截止到27号晚23:59」里 `RE_LOC2` 看到"到"就当后面是地点，
     *        可那一段是日期加时间。
     *
     *        注意比的是**洗出来的地点文字**，不是候选的原始区间。
     *        「明天上午9点到12点在体育馆体检」的候选区间横跨了时间，
     *        但洗完之后只剩下"体育馆" —— 那是真地点，不能一起丢掉。
     */
    private fun findLocation(
        text: String,
        takenText: List<String>
    ): Pair<String, Pair<Int, Int>>? {
        fun free(c: String?): Boolean =
            c != null && takenText.none { it.isNotEmpty() && c.contains(it) }

        // 1) 地点：X
        RE_LOC1.find(text)?.let { m ->
            val c = cleanLoc(m.groups[1]!!.value)
            if (free(c)) return c!! to m.span()
        }
        // 1b) 地点 X（没有冒号）
        RE_LOC1B.find(text)?.let { m ->
            val c = cleanLoc(m.groups[1]!!.value)
            if (free(c)) return c!! to m.span()
        }
        // 2) 📍X
        RE_LOC_PIN.find(text)?.let { m ->
            val c = cleanLoc(LOC_LABEL.replace(m.groups[1]!!.value, ""))
            if (free(c)) return c!! to m.span()
        }
        // 3) 在X开会 / 到X集合
        RE_LOC2.find(text)?.let { m ->
            val c = cleanLoc(m.groups[1]!!.value)
            if (free(c)) return c!! to m.span()
        }
        // 4) @X（@所有人 是群公告的圈人标记，不是地点）
        RE_AT.find(text)?.let { m ->
            val raw = m.groups[1]!!.value.trim(*TRAIL)
            if (raw.isNotEmpty() && !isAtNoise(raw)) {
                val c = cleanLoc(raw)
                if (free(c)) return c!! to m.span()
            }
        }
        return null
    }

    /** @ 后面这些不是地点：@所有人 / @全体成员 / 邮箱域名 */
    private fun isAtNoise(s: String): Boolean =
        s.startsWith("所有") || s.startsWith("全体") || s.startsWith("每人") ||
            s.contains('@') || Regex("""^[\w.-]+\.(com|cn|net|org)$""").matches(s) ||
            Regex("""^\d+$""").matches(s)

    /** 地点候选清洗：剥动作尾巴、去托辞词；不合格返回 null */
    private fun cleanLoc(raw: String): String? {
        var c = balanceBrackets(stripEmoji(raw).trim(*TRAIL_NO_BRACKET))
        // 地点候选里经常混进时间残渣。典型来源："明天9点到12点在体育馆体检"
        // 被 RE_LOC2 的"到"抓成"12点在体育馆"；"会议改到16:30"抓成"16:30"。
        // 这里把开头的时间片段和前导介词剥掉，剥完为空就说明整个候选都是时间，不是地点。
        var guardStrip = 0
        while (guardStrip++ < 3) {
            val t1 = RE_LEAD_TIME.replace(c, "")
            val t2 = RE_LEAD_PREP.replace(t1, "")
            if (t2 == c) break
            c = t2
        }
        if (c.isEmpty()) return null
        if (LOC_STOP.contains(c)) return null
        if (LOC_FAKE_WORDS.any { c.contains(it) }) return null
        var changed = true
        while (changed) {
            changed = false
            for (v in LOC_TAIL_VERBS) {
                if (c.endsWith(v) && c.length > v.length + 1) {
                    c = c.dropLast(v.length).trim(*TRAIL)
                    changed = true
                }
            }
        }
        // 结尾仍是动作词（"按要求完成作品提交"），说明抓错了，不是地点
        if (LOC_TAIL_VERBS.any { c.endsWith(it) }) return null
        if (c.length < 2) return null
        if (LOC_FAKE_WORDS.any { c.contains(it) }) return null
        return c
    }

    /** 括号被截断时补齐/删掉落单的那一半，别留半个"（" */
    private fun balanceBrackets(s: String): String {
        var c = s
        val pairs = listOf('（' to '）', '(' to ')', '【' to '】', '[' to ']')
        for ((open, close) in pairs) {
            var opens = c.count { it == open }
            var closes = c.count { it == close }
            while (opens > closes && c.endsWith(open)) {
                c = c.dropLast(1)
                opens--
            }
            while (closes > opens && c.endsWith(close)) {
                c = c.dropLast(1)
                closes--
            }
        }
        return c
    }

    // ---------- 标题 ----------
    private fun buildTitle(text: String, spans: List<Pair<Int, Int>>, raw: String): String {
        // 1) 开头【】里的中文标题优先 —— 群公告最规范的写法
        RE_BRACKET.findAll(text).forEach { m ->
            if (isLeadingNoise(text, m.range.first)) {
                val inner = sanitize(m.groupValues[1])
                if (inner.length in 2..TITLE_MAX && inner.any { isCjk(it) } &&
                    TAG_BLACKLIST.none { inner == it }
                ) return inner
            }
        }

        // 2) 正文：挖掉已识别的时间/地点，取第一行
        val body = cut(text, spans)
        val line = body.lineSequence().firstOrNull { it.isNotBlank() } ?: body
        // 3) 首句没有"事件感"就往后找一句带事件词的
        //    （"指尖上的创意时光！" → "手工社即将开展第一次手工活动啦"）
        val sentences = line.split(Regex("""(?<=[。！？!?~～])"""))
        val picked = sentences.firstOrNull { sen ->
            sen.any { isCjk(it) } && EVENT_WORDS.any { sen.contains(it) }
        } ?: sentences.firstOrNull().orEmpty()
        var title = sanitize(picked)
        if (title.isEmpty()) title = sanitize(line)
        if (title.isNotEmpty()) return title
        return normalize(raw).let { s ->
            sanitize(s).ifEmpty { stripEmoji(s).take(TITLE_MAX).trim(*TRAIL) }
        }
    }

    /** idx 之前是否只有空白/标点/emoji —— 用来判断【】是不是出现在开头 */
    private fun isLeadingNoise(text: String, idx: Int): Boolean {
        var i = 0
        while (i < idx) {
            val cp = text.codePointAt(i)
            if (!isNoiseCp(cp)) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /**
     * HTML / XML 标签。
     *
     * 真机上撞到的：原文 `<b>明天下午3点开会</b>`，标题变成 `<b> 开会</b>` ——
     * 有些 App 的通知正文里真的带标签，不清掉就会进标题。
     */
    private val RE_HTML_TAG = Regex("""</?[a-zA-Z][a-zA-Z0-9]{0,9}(?:\s[^<>]{0,60})?/?>""")

    /**
     * "面试通知：" "活动安排：" 这类**标签式前缀**。
     *
     * `RE_SPEAKER` 会把行首的"X："当成发送人剥掉，可这几类不是发送人 ——
     * "面试通知"就是这件事的名字。真机上撞到的：
     *
     * ```
     * 面试通知：10月28日下午2点，A栋302
     *   → 剥掉"面试通知："，标题只剩"A栋302"（地址变成了标题）
     * ```
     */
    private val RE_LABEL_PREFIX = Regex(
        """^[^\s:：]{1,12}(?:通知|提醒|公告|安排|邀请|告知|主题|事宜|说明)\s*[:：]\s*"""
    )

    /**
     * 中文之间多余的空格。
     *
     * 挖掉日期/地点时用的是"替换成空格"，于是一句话里会留下 `团队聚餐， 海底捞`
     * 这种空格。中文排版本来就不该在字之间加空格，直接去掉。
     * 不动数字和英文两侧的空格（"14:00 开会" 那个空格是有意义的）。
     */
    private val RE_CN_SPACE = Regex(
        """(?<=[\u4e00-\u9fa5，。、；：！？])[ \t]+(?=[\u4e00-\u9fa5，。、；：！？@])"""
    )

    /** 落在结尾的孤立时段词："截止到晚"、"开会到下午" */
    private val RE_PERIOD_TAIL = Regex(
        """(?:凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|夜晚|晚|早)\s*$"""
    )

    /** "截止到27号" 挖掉日期后剩"截止到" —— 把"到/至"还回去 */
    private val RE_DEADLINE_TAIL = Regex("""(截止|截至)\s*[到至]\s*$""")

    /** "本月27号前提交" 挖掉日期后剩"本月前提交"，"本月"是残留的日期修饰语 */
    private val RE_THIS_MONTH = Regex("""(?:本|这个|下个|上个)\s*月""")

    /**
     * 收尾清理：日期被挖走后，常留下一点残渣挂在标题末尾。
     *
     * 这几条都是实测出来的：
     * | 原文 | 挖完日期 | 清理后 |
     * |---|---|---|
     * | 截止到27号晚23:59 | 截止到晚 | 截止 |
     * | 截止到27号23:59 | 截止到 | 截止 |
     * | 本月27号前提交 | 本月前提交 | 前提交 |
     */
    private fun trimDanglingTail(input: String): String {
        var s = input
        // 时段词单独挂在结尾。要求前面还剩 2 个字以上才削，
        // 否则"昨晚"会被削成"昨"。
        RE_PERIOD_TAIL.find(s)?.let { m ->
            if (m.range.first >= 2) s = s.substring(0, m.range.first).trim(*TRAIL)
        }
        s = RE_DEADLINE_TAIL.replace(s, "$1")
        s = RE_THIS_MONTH.replace(s, "")
        return s.trim(*TRAIL)
    }

    private fun isNoiseCp(cp: Int): Boolean {
        val ch = cp.toChar()
        if (cp < 0x10000 && (ch.isWhitespace() || ch in TRAIL)) return true
        return cp in 0x1F000..0x1FAFF || cp in 0x2190..0x21FF || cp in 0x2300..0x23FF ||
            cp in 0x25A0..0x27BF || cp in 0x2B00..0x2BFF || cp == 0xFE0F ||
            cp == 0x200D || cp == 0x20E3 || cp in 0xE0020..0xE007F
    }

    /** 把一段文字洗成"标题样"：去日期时间残渣、去括号角标、去发言人前缀、限长 */
    private fun sanitize(s0: String): String {
        var s = stripEmoji(s0)
        s = RE_HTML_TAG.replace(s, " ")
        // "面试通知：xxx" 的冒号换成空格，这样下面的 RE_SPEAKER 就不会把
        // "面试通知" 当成发送人剥掉 —— 它是标题的一部分
        s = RE_LABEL_PREFIX.replace(s) { m -> m.value.replaceFirst(Regex("""\s*[:：]\s*$"""), " ") }
        s = RE_TAG.replace(s, " ")
        s = RE_WEEK_LEFTOVER.replace(s, " ")
        s = RE_DIAN_LEFTOVER.replace(s, " ")
        // 残留的其它日期/时间片段（区间后半段等）
        s = RE_FULL.replace(s, " ")
        s = RE_MD.replace(s, " ")
        s = RE_DAY_ONLY.replace(s, " ")
        s = RE_ABS_TIME.replace(s, " ")
        s = RE_CN_TIME.replace(s, " ")
        // 中文数字时间只在带时段词时才清，否则"一点小事"会被误伤
        s = RE_PERIOD_CN_TIME.replace(s, " ")
        s = RE_ZAI_QIAN.replace(s, " ")
        s = RE_CHANGE_TAIL.replace(s, " ")
        s = RE_AT_NOISE.replace(s, " ")
        s = RE_LABEL.replace(s, " ")
        s = RE_FILLER.replace(s, "")
        s = RE_SEP.replace(s, "")
        s = RE_CN_SPACE.replace(s, "")
        s = s.trim(*TRAIL)
        s = trimDanglingTail(s)

        // 发言人/群名前缀可能有好几层
        var guard = 0
        while (guard++ < 3) {
            val t = RE_SPEAKER.replace(s, "")
            if (t == s) break
            s = t.trim(*TRAIL)
        }

        var changed = true
        while (changed) {
            changed = false
            for (n in NOISE_PREFIX) {
                if (s.length > n.length && s.startsWith(n)) {
                    s = s.substring(n.length).trim(*TRAIL)
                    changed = true
                }
            }
        }
        s = RE_LEAD.replace(s, "").trim(*TRAIL)
        s = CJK_GAP.replace(s, "")
        s = WHITESPACE.replace(s, " ").trim(*TRAIL)
        s = RE_SEP.replace(s, "").trim(*TRAIL)
        return truncate(s)
    }

    /** 超长在句读处截断，别把标题切成半个词 */
    private fun truncate(s: String): String {
        if (s.length <= TITLE_MAX) return s
        val idx = s.take(TITLE_MAX + 1).indexOfLast { it in "，,、;；。!！?？" }
        val cutAt = if (idx >= 6) idx else TITLE_MAX
        return s.take(cutAt).trim(*TRAIL)
    }

    // ---------- 工具 ----------

    /** 全角转半角、去零宽字符、压缩空白；保留换行（首行用来抽标题） */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(
                when (ch) {
                    '：' -> ':'
                    '／' -> '/'
                    '　' -> ' '
                    in '０'..'９' -> '0' + (ch - '０')
                    in '\u200b'..'\u200f', '\ufeff' -> ' '
                    else -> ch
                }
            )
        }
        return sb.split('\n')
            .joinToString("\n") { WHITESPACE.replace(it, " ").trim() }
            .replace(Regex("""\n{2,}"""), "\n")
            .trim()
    }

    /** 把若干区间原地替换成空格（从后往前，避免下标位移） */
    private fun cut(text: String, spans: List<Pair<Int, Int>>): String {
        if (spans.isEmpty()) return text
        val sb = StringBuilder(text)
        spans.filter { it.second > it.first }
            .sortedByDescending { it.first }
            .forEach { (s, e) ->
                val from = s.coerceIn(0, sb.length)
                val to = e.coerceIn(0, sb.length)
                for (i in from until to) sb.setCharAt(i, ' ')
            }
        return sb.toString()
    }

    /** "周五前 / 周五之前"：把尾巴并进日期片段，否则标题会剩一个"前" */
    private fun extendWeekTail(text: String, from: Int, toEnd: Int): Pair<Int, Int> {
        if (toEnd >= text.length) return from to toEnd
        val tail = RE_AFTER_TAIL.find(text.substring(toEnd))
        return if (tail != null && tail.range.first == 0) {
            from to (toEnd + tail.range.last + 1)
        } else {
            from to toEnd
        }
    }

    /**
     * 中文数字时间的上下文校验。
     * "下午三点""十点集合"是时间；"一点小事""差点迟到"不是。
     */
    private fun hasTimeContext(text: String, start: Int, end: Int): Boolean {
        val before = text.substring(maxOf(0, start - 6), start)
        if (RE_PERIOD.containsMatchIn(before)) return true
        val after = text.substring(minOf(end + 1, text.length)).take(8)
        return AFTER_TIME_WORDS.any { after.contains(it) }
    }

    /**
     * 是不是一条"取消/作废"通知。
     *
     * 关键分辨：文本里同时出现"改到/推迟到/延期"时，这是**变更**通知，新时间才是要的，
     * 不能整条丢掉（"取消原定时间，改到后天3点"必须解析出后天3点）。
     */
    private fun isCancellationNotice(text: String): Boolean {
        if (CHANGE_WORDS.any { text.contains(it) }) return false
        return CANCEL_WORDS.any { text.contains(it) }
    }

    private fun safeDate(y: Int, mo: Int, d: Int): LocalDate? {
        if (mo !in 1..12 || d !in 1..31 || y !in 1900..2200) return null
        return try {
            LocalDate.of(y, mo, d)
        } catch (e: Exception) {
            null
        }
    }

    private fun MatchResult.span(): Pair<Int, Int> = range.first to (range.last + 1)

    private fun MatchResult.groupInt(name: String): Int =
        groups[name]?.value?.toIntOrNull() ?: -1

    private fun isCjk(ch: Char): Boolean = ch in '\u4e00'..'\u9fff'

    private fun isPunctOrEmoji(ch: Char): Boolean =
        ch in TRAIL || ch.code in 0x1F000..0x1FAFF || ch.code in 0x2600..0x27BF

    /** 去掉 emoji（按码点处理，代理对整体丢弃） */
    private fun stripEmoji(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            val drop = cp in 0x1F000..0x1FAFF || cp in 0x2190..0x21FF ||
                cp in 0x2300..0x23FF || cp in 0x25A0..0x27BF || cp in 0x2B00..0x2BFF ||
                cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 || cp in 0xE0020..0xE007F
            if (!drop) sb.append(s, i, i + n)
            i += n
        }
        return sb.toString()
    }

    private fun weekdayIdx(w: String): Int = when (w) {
        "一" -> 1
        "二" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        "日", "天" -> 7
        else -> w.toIntOrNull() ?: -1
    }

    /** 一 / 十二 / 二十 这类中文数字转换 */
    private fun cnToInt(s: String): Int {
        s.toIntOrNull()?.let { return it }
        var total = 0
        for (ch in s) {
            val v = CN_NUM[ch] ?: return -1
            total = if (v == 10) {
                if (total == 0) 10 else total * 10
            } else {
                total + v
            }
        }
        return total
    }
}
