package com.terry.smartschedule.parse

import com.terry.smartschedule.data.Event
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「这条消息是在改一条已经存在的安排」—— 认出这种句子，并把它拆成旧时间 / 新时间。
 *
 * ## 为什么需要单独一层
 *
 * 通用解析器的前提是「一段话 = 一条新日程」。遇到改期通知就全错，实测四种典型句式：
 *
 * ```
 * 明天下午四点在教学楼开会        → 20日 16:00   （对）
 * 原定明天下午四点的会议将提前至3点 → 20日 15:00   （对，但会**新建**一条，原来那条还在）
 * 原定明天下午四点的会议改到后天上午十点 → 21日 16:00 （错，应该是 10:00）
 * 原定明天下午四点的会议取消了    → null          （静默丢掉，用户毫无感知）
 * ```
 *
 * 所以判「是不是改期」必须发生在解析之前，靠句式而不是靠解析结果。
 *
 * ## 边界：只认「有明确改期动词」的句子
 *
 * 宁可不认，也不能把一句普通通知当成改期去动用户的日程。因此：
 * - 必须有 [RE_MOVE] 或 [RE_CANCEL] 里的动词；
 * - 改期必须能抽出**新时间**，抽不出就返回 [Verdict.Unclear]，由调用方决定不动数据；
 * - 「原定 X」抽不出来时 [Verdict.Move.oldStartAt] 为 null，靠标题去库里找，找不到就不改。
 *
 * 这一层不碰数据库、不碰 Android，因此可以逐句钉单测（[ReviseTest]）。
 */
object Revise {

    sealed interface Verdict {

        /** 不是改期/取消，按新日程走原来的流程 */
        data object None : Verdict

        /**
         * 改时间。
         *
         * @param oldStartAt 原文「原定 …」里的旧时间；原文没提就是 null
         * @param newStartAt 改成的新时间（已继承旧时间的日期）
         * @param newHasTime false 表示只说了改到哪天、没说几点，按全天处理
         * @param meridiemGuessed true 表示新时间只写了「3点」这种，上下午是按旧时间推的
         * @param newLocation 同一句话里还换了地点（「改到 B 栋 301」）；没提就是 null
         */
        data class Move(
            val oldStartAt: Long?,
            val newStartAt: Long,
            val newHasTime: Boolean,
            val meridiemGuessed: Boolean,
            val newLocation: String? = null
        ) : Verdict

        /**
         * 只换地点，没提时间（「换到 B 栋 301 了」）。
         *
         * 单独一个类型而不是塞进 [Move]：Move 的前提是"有一个新时间"，
         * 为了地点变更硬填一个 startAt 会让调用方分不清"时间真的没变"和"没解析出来"。
         */
        data class Relocate(
            val oldStartAt: Long?,
            val newLocation: String
        ) : Verdict

        /** 取消。旧时间为 null 时只能靠标题找 */
        data class Cancel(val oldStartAt: Long?) : Verdict

        /**
         * 看着像改期，但关键信息不全 —— 不动数据。
         *
         * @param oldStartAt 原文「原定 …」里能抽出的旧时间；抽不出就是 null。
         *                   带着它是为了让界面能把候选日程缩到那半小时里，
         *                   而不是让用户在接下来两天里瞎猜。
         */
        data class Unclear(
            val why: String,
            val oldStartAt: Long? = null
        ) : Verdict
    }

    /** 「原定 / 之前说的」这类对旧时间的引用 */
    private val RE_OLD_REF = Regex("(原定|原本|原来|原先|之前说的|之前定的|之前那个|前面说的|刚刚说的)")

    /** 改期动词。带地点语气的「换到 / 移到 / 搬到 / 调到」也算 —— 具体是改时间还是改地点，由动词后的内容决定 */
    private val RE_MOVE = Regex("(推迟|延期|延后|顺延|提前|提早|推前|改到|改在|改为|改成|调整到|调整至|挪到|挪至|换到|移到|搬到|调到)")

    /** 「不取消」「没取消」不算取消 */
    private val RE_CANCEL = Regex("(?<![不没别])(取消|不开了|不办了|不搞了|作废|撤销)")

    /** 片段里有没有明说上下午 */
    private val RE_MERIDIEM = Regex("(上午|早上|下午|中午|晚上|傍晚|凌晨)")

    /** 片段里有没有「几点」的痕迹（用来区分「改到后天」和「改到后天五点」） */
    private val RE_HOUR_MARK = Regex("[0-9一二三四五六七八九十]\\s*[点时]")

    /**
     * 地点形态词。**不能只靠「在」判断** —— 「在图书馆开会」是正常日程，不是地点变更。
     * 这里要求动词后面真的出现这些字，才认为它是一句换地点的话。
     */
    private val RE_PLACE_HINT = Regex("(楼|栋|座|室|馆|厅|场|区|园|院|层|食堂|宿舍|操场|线上|腾讯会议|钉钉)")

    /** 地点片段里一旦出现这些，就说明是时间不是地点（「改到明天下午3点」） */
    private val RE_TIME_IN_CHUNK = Regex(
        "([0-9一二三四五六七八九十]\\s*[点时])|(今天|明天|后天|大后天|今晚|明早|明晚|上午|下午|中午|晚上|傍晚|凌晨|周[一二三四五六日天])"
    )

    private val CN_DIGIT = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    /** 片段解析结果。[dateSource] 用来判断日期是不是原文写死的 */
    private data class Parsed(
        val dt: LocalDateTime,
        val hasTime: Boolean,
        val dateSource: DateTimeParser.DateSource
    )

    /**
     * 主入口。
     *
     * @param now 显式传入，否则这个函数没法测。调用方传 `LocalDateTime.now()`。
     */
    fun detect(text: String, now: LocalDateTime = LocalDateTime.now()): Verdict {
        val t = text.trim()
        if (t.isEmpty()) return Verdict.None

        val move = RE_MOVE.find(t)
        val cancel = RE_CANCEL.find(t)

        // 既像改期又像取消时，先看改期那半能不能给出新时间：
        //   「会取消了，改到下周」→ 能给出新时间，这是延期，按改期处理
        //   「原定明天的会取消了，不用提前去了」→ 改期那半抽不出时间，按取消处理
        // 顺序反了会把前者错判成取消，把用户一条日程直接标死。
        if (move != null) {
            val mv = detectMove(t, move.range, now)
            if (mv is Verdict.Move || mv is Verdict.Relocate) return mv
            if (cancel != null) {
                return Verdict.Cancel(parseOld(t, cancel.range.first, now)?.dt?.let(::toMillis))
            }
            return mv
        }
        if (cancel != null) {
            return Verdict.Cancel(parseOld(t, cancel.range.first, now)?.dt?.let(::toMillis))
        }
        return Verdict.None
    }

    private fun detectMove(t: String, range: IntRange, now: LocalDateTime): Verdict {
        val oldParsed = parseOld(t, range.first, now)
        val oldStart = oldParsed?.dt
        val oldMs = oldStart?.let(::toMillis)

        val newChunk = newChunkAfter(t, range.last)
        val parsed = if (newChunk.isBlank()) null else parseChunk(newChunk, now)
        val loc = locationAfter(t, range.last)

        // 地点优先：动词后面是一段地址时，不能因为解析器吐了个"全天/半天"就当时间改。
        // 判据是片段里**没有任何时间词**（RE_TIME_IN_CHUNK），而不是解析结果是否为空 ——
        // "B栋301" 里的 301 有可能被解析器当成某种时间，那种情况下改时间会酿成错案。
        val looksLikeTime = parsed != null && parsed.hasTime &&
            RE_TIME_IN_CHUNK.containsMatchIn(newChunk)
        if (loc != null && !looksLikeTime) {
            return Verdict.Relocate(oldMs, loc)
        }

        if (newChunk.isBlank()) {
            return Verdict.Unclear("有改期动词，但后面没有时间", oldMs)
        }

        if (parsed == null) {
            return Verdict.Unclear("改期时间解析不出来：$newChunk", oldMs)
        }

        // 「改到后天五点」里钟点没解析出来 —— 解析器漏了，不能当成"改到后天全天"
        if (!parsed.hasTime && RE_HOUR_MARK.containsMatchIn(newChunk)) {
            return Verdict.Unclear(
                "改期时间里有钟点但没解析出来：$newChunk",
                oldMs
            )
        }

        val withDate = inheritDate(parsed, oldStart)

        if (!parsed.hasTime) {
            return Verdict.Move(
                oldStartAt = oldMs,
                newStartAt = toMillis(withDate),
                newHasTime = false,
                meridiemGuessed = false,
                newLocation = loc
            )
        }

        val fixed = if (oldStart != null) {
            fixMeridiem(withDate, oldStart, RE_MERIDIEM.containsMatchIn(newChunk))
        } else {
            withDate to false
        }

        return Verdict.Move(
            oldStartAt = oldMs,
            newStartAt = toMillis(fixed.first),
            newHasTime = true,
            meridiemGuessed = fixed.second,
            newLocation = loc
        )
    }

    /**
     * 动词后面那一段如果长得像地址，返回它。
     *
     * 三条约束，少一条都会误伤：
     * 1. **必须出现地点形态词**（楼/栋/馆/室…）。光有"在"不算 ——
     *    「在图书馆开会」是正常日程，不是地点变更。
     * 2. **不能出现时间词**。否则「改到明天下午3点」会被抽成地点「明天下午3点」。
     * 3. 长度 ≤30、在第一个标点处截断，并且剥掉句尾语气词（"换到B栋301了"）。
     */
    private fun locationAfter(text: String, moveEnd: Int): String? {
        val chunk = text.substring(moveEnd + 1)
            .trimStart('至', '到', '为', '成', '在', '：', ':', ' ', '　')
            .substringBefore('，').substringBefore('。')
            .substringBefore('；').substringBefore(';')
            .substringBefore('！').substringBefore('!')
            .trim()
            .trimEnd('了', '吧', '哦', '呢', '啊')
            .trim()

        if (chunk.isEmpty() || chunk.length > 30) return null
        if (RE_TIME_IN_CHUNK.containsMatchIn(chunk)) return null
        if (!RE_PLACE_HINT.containsMatchIn(chunk)) return null
        return chunk
    }

    // ---------------- 选哪一条 ----------------

    /**
     * 从时间窗口内的候选里挑出「这次改的是哪一条」。
     *
     * 抽成纯函数而不是写在 [com.terry.smartschedule.data.Repo] 里，是因为
     * "挑错一条"是这个功能最危险的失败方式 —— 用户的时间被悄悄挪走，他很难发现。
     * 这里必须能被单测逐条钉住。
     *
     * 规则：
     * - 只有一条 → 就是它；
     * - 多条 → 用通知原文里的字去筛，**剩下唯一一条才算数**；
     * - 筛完还是多条 / 一条都不剩 → null，宁可不改。
     *
     * @param candidates 已经按时间窗口过滤过的日程（调用方负责过滤已完成/已取消/待确认）
     * @param titleHint  传整句通知原文
     */
    fun pick(candidates: List<Event>, titleHint: String): Event? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        val hint = TextNorm.key(titleHint).toSet()
        if (hint.isEmpty()) return null
        val hit = candidates.filter { e ->
            TextNorm.key(e.title).toSet().any { it in hint }
        }
        return hit.singleOrNull()
    }

    /**
     * 信息不全时，值不值得发通知打扰用户。
     *
     * 「原定四点那个会推迟了」值得问：用户知道是哪件事，只是没说新时间，
     * 点进来手动补一下就行。
     *
     * 「会议提前了」不值得：连对象都没有，问了他也无从选起。这类只记日志 ——
     * 通知栏里每来一句含"提前/取消"的闲聊就弹一次，比漏改更让人烦。
     *
     * 注意：Move 只在**没匹配到目标**时才会被调用（见 NotificationCaptureService），
     * 所以这里返回 true 不等于"每条改期都要问"。
     */
    fun worthAsking(v: Verdict): Boolean = when (v) {
        is Verdict.Move -> true
        is Verdict.Relocate -> true
        is Verdict.Unclear -> v.oldStartAt != null
        is Verdict.Cancel -> false
        Verdict.None -> false
    }

    // ---------------- 片段切分 ----------------

    /** 取「原定 … 的」里那段时间，切到第一个「的」为止；没有「的」就取前 12 个字 */
    private fun oldChunkBefore(text: String, moveStart: Int): String {
        val ref = RE_OLD_REF.find(text) ?: return ""
        if (ref.range.first >= moveStart) return ""
        val raw = text.substring(ref.range.last + 1, moveStart)
        val cut = raw.indexOf('的')
        return (if (cut > 0) raw.substring(0, cut) else raw.take(12)).trim()
    }

    private fun parseOld(text: String, before: Int, now: LocalDateTime): Parsed? {
        val chunk = oldChunkBefore(text, before)
        if (chunk.isBlank()) return null
        return parseChunk(chunk, now)
    }

    /** 改期动词后面那段时间，去掉「至/到/为/成/在」这类连接词，并在第一个标点处截断 */
    private fun newChunkAfter(text: String, moveEnd: Int): String =
        text.substring(moveEnd + 1)
            .trimStart('至', '到', '为', '成', '在', '：', ':', ' ', '　')
            .substringBefore('，').substringBefore('。')
            .substringBefore('；').substringBefore(';')
            .trim()

    // ---------------- 时间解析 ----------------

    private fun parseChunk(chunk: String, now: LocalDateTime): Parsed? {
        val normalized = cnHourToDigit(chunk)
        val r = DateTimeParser.parse(normalized, now) ?: return null
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(r.startAt), ZoneId.systemDefault())
        return Parsed(dt, r.hasTime, r.dateSource)
    }

    /**
     * 「三点」→「3点」。
     *
     * 解析器认「下午三点」（有时段词能推），但不认光秃秃的「三点」「五点」——
     * 实测 `parse("明天五点")` 会退化成"明天全天 09:00"，钟点直接丢掉。
     * 只转换紧跟「点/时」的中文数字，不动其他位置，避免把「一点小事」当成 1 点。
     */
    private fun cnHourToDigit(s: String): String =
        Regex("([一二三四五六七八九十]{1,3})(?=[点时])").replace(s) { m ->
            cnToInt(m.groupValues[1])?.toString() ?: m.value
        }

    private fun cnToInt(s: String): Int? = when {
        s == "十" -> 10
        s.length == 2 && s[0] == '十' -> CN_DIGIT[s[1]]?.let { 10 + it }
        s.length == 2 && s[1] == '十' -> CN_DIGIT[s[0]]?.let { it * 10 }
        s.length == 1 -> CN_DIGIT[s[0]]
        else -> null
    }

    /**
     * 新时间只写了「3点」没写日期 —— 用旧时间那天的日期。
     *
     * 判据用 [DateTimeParser.DateSource.TIME_ONLY]（解析器自己说的"只有时间"），
     * 不去反推 LocalDateTime 差几天：那个办法在"今天 23:50 说 3 点"这种跨零点场景会错。
     */
    private fun inheritDate(p: Parsed, old: LocalDateTime?): LocalDateTime =
        if (p.dateSource == DateTimeParser.DateSource.TIME_ONLY && old != null) {
            LocalDateTime.of(old.toLocalDate(), p.dt.toLocalTime())
        } else {
            p.dt
        }

    /**
     * 上下午推断。
     *
     * 「原定下午四点的会提前至3点」——「3点」没写上下午。中文口语里这种多半指**同半天的 3 点**，
     * 也就是 15:00，而不是凌晨 3:00。所以：旧时间在 12 点之后、新时间在 12 点之前、
     * 且新片段本身没写上下午 → 加 12 小时，并把"这是猜的"记下来。
     *
     * @return 修正后的时间 + 有没有猜
     */
    private fun fixMeridiem(
        newStart: LocalDateTime,
        old: LocalDateTime,
        newHasMeridiem: Boolean
    ): Pair<LocalDateTime, Boolean> {
        if (newHasMeridiem) return newStart to false
        if (newStart.hour >= 12) return newStart to false
        if (old.hour < 12) return newStart to false
        return newStart.plusHours(12) to true
    }

    private fun toMillis(dt: LocalDateTime): Long =
        dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
