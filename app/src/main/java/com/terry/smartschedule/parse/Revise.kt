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
            val newLocation: String? = null,
            /** true = 来自「时间有变」这类没有旧时间锚点的句子，匹配时要看正文重合度 */
            val fromTimeChanged: Boolean = false,
            /** 新时间段的结束时间；0 表示原文没写结束时间，沿用原日程时长 */
            val newEndAt: Long = 0L
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

    /**
     * 没有「原定」，但明说时间变了：「现时间有变」「时间改了」。
     *
     * 真实案例是班委先发「下周六…盖章」，又发「现时间有变，…下周日…盖章」。
     * 第二句里没有「原定」，[RE_MOVE] 也匹配不到，旧逻辑会把它当成一条全新日程，
     * 于是列表里出现两条互相矛盾的时间。
     *
     * 注意：这里只负责**认出来**，新时间在句子后半段，不能用 [newChunkAfter] 的
     * "第一个逗号截断"策略去取。
     *
     * 不收裸词「时间调整」「时间变更」：场馆/图书馆的「开放时间调整通知」
     * 「营业时间变更公告」也是这些字，但它们是一条全新通告，不是对已有日程的修改。
     * 带「有/被」或改期动词时才会命中。
     */
    private val RE_TIME_CHANGED =
        Regex("(时间有变|时间有变更|时间有调整|时间被调整|时间改了|改了时间|时间有改动|现有变)")

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
        val dateSource: DateTimeParser.DateSource,
        /** 原文写了结束时间才有值；0 表示没写 */
        val endAt: Long = 0L
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
        // 取最后一次「时间有变」：同一条通知里可能把旧版和新版都贴出来，
        // 最后一版才是用户要的。用第一次会在「…下周六…和…时间有变…下周日…」里选错。
        val timeChanged = RE_TIME_CHANGED.findAll(t).lastOrNull()

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
            // 「原定…」的改期没抽出新时间，但句子里还写了「时间有变」时，
            // 再给这个更宽松的入口一次机会（它会在整段里找日期，而不是只看一个逗号）
            if (timeChanged != null) {
                val tv = detectTimeChanged(t, timeChanged.range, now)
                if (tv is Verdict.Move) return tv
            }
            return mv
        }
        if (cancel != null) {
            return Verdict.Cancel(parseOld(t, cancel.range.first, now)?.dt?.let(::toMillis))
        }
        if (timeChanged != null) {
            return detectTimeChanged(t, timeChanged.range, now)
        }
        return Verdict.None
    }

    /**
     * 「时间有变」类通知的新时间解析。
     *
     * 和 [detectMove] 的区别：不要求动词后面紧跟时间，也不在第一个逗号处截断 ——
     * 这类句子的结构是「时间有变，<一大段说明>，下周日 3 点」，
     * 时间在很后面，截断了就什么都拿不到。
     */
    private fun detectTimeChanged(t: String, range: IntRange, now: LocalDateTime): Verdict {
        val oldStart = parseOld(t, range.first, now)?.dt
        val oldMs = oldStart?.let(::toMillis)

        // 只取标记后面 160 字：再长的通告通常也把新时间写在开头附近，
        // 取整段会把落款、备注里的其他日期也扫进来
        val tail = t.substring(range.last + 1).take(160)
        val parsed = parseChunk(tail, now)
            ?: return Verdict.Unclear("说了时间有变，但没给出可用的新时间", oldMs)

        // 只有「3点」没有日期：日期纯靠猜，和"只有时间没有日期不建"的口径一致
        if (parsed.dateSource == DateTimeParser.DateSource.TIME_ONLY && oldStart == null) {
            return Verdict.Unclear("时间有变，但只有钟点没有日期", oldMs)
        }

        // 和 detectMove 一样：文本里写了钟点但解析器没抽出来，不能当成"改成全天"。
        // 这条通知可能只是"11月22日25点"这种脏格式；静默丢掉 15:00 比漏改更难发现。
        if (!parsed.hasTime && RE_HOUR_MARK.containsMatchIn(tail)) {
            return Verdict.Unclear(
                "时间有变，时间里有钟点但没解析出来：${tail.take(30)}",
                oldMs
            )
        }

        val withDate = inheritDate(parsed, oldStart)
        val fixed = if (oldStart != null) {
            fixMeridiem(withDate, oldStart, RE_MERIDIEM.containsMatchIn(tail))
        } else {
            withDate to false
        }
        return Verdict.Move(
            oldStartAt = oldMs,
            newStartAt = toMillis(fixed.first),
            newHasTime = parsed.hasTime,
            meridiemGuessed = fixed.second,
            fromTimeChanged = true,
            newEndAt = endFor(fixed.first, parsed)
        )
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

        // 没有旧时间锚点、又只有「3点」这种钟点：日期是靠"今天/明天"猜的。
        // 拿猜出来的日期去改已有日程，比漏改危险得多，所以不动数据。
        if (parsed.dateSource == DateTimeParser.DateSource.TIME_ONLY && oldStart == null) {
            return Verdict.Unclear("改期时间只有钟点，没有日期", oldMs)
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
            newLocation = loc,
            newEndAt = endFor(fixed.first, parsed)
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
     * 「时间有变」这类没有旧时间锚点的候选匹配。
     *
     * 为什么不能沿用 [pick]：它只看标题的"有没有任意一个字重合"。候选一多，
     * 「部门周会」和「下周日」共用一个「周」字就会被算作命中，几乎永远筛不干净。
     * 这里把标题、地点、**原文**一起拿来比，按重合字数打分，并且要求：
     * - 至少重合 [MIN_CONTENT_OVERLAP] 个字；
     * - 明显领先第二名（≥2 倍）。
     *
     * 不满足就返回 null，交给人工确认。没有时间锚点时，这是唯一不靠猜的办法。
     */
    fun pickByContent(candidates: List<Event>, titleHint: String): Event? {
        if (candidates.isEmpty()) return null
        // 只有一条候选也不能直接认领：无旧时间锚点时，它可能只是"未来两天里唯一一条"，
        // 和这条通知毫无关系。必须同样过正文重合门槛，否则就是静默改错日程。
        val hint = TextNorm.key(titleHint)
        if (hint.isEmpty()) return null
        // 用**二元组**而不是单字：单字重合会被"的/在/会/学"这类公共字带偏，
        // 两个候选都能拿到差不多的分，结果永远筛不出来。
        val grams = if (hint.length >= 2) hint.windowed(2).toSet() else setOf(hint)

        fun score(e: Event): Int {
            val text = TextNorm.key(
                listOfNotNull(e.title, e.location, e.note).joinToString(" ")
            )
            return grams.count { it in text }
        }

        val scored = candidates.map { it to score(it) }.sortedByDescending { it.second }
        val best = scored[0]
        if (best.second < MIN_CONTENT_OVERLAP) return null
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && best.second < runnerUp.second * 2) return null
        return best.first
    }

    /** 正文重合度门槛。低于它说明只命中了公共词，不足以定位 */
    private const val MIN_CONTENT_OVERLAP = 8

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
        return Parsed(dt, r.hasTime, r.dateSource, r.endAt)
    }

    /**
     * 把新片段里的时长换到最终的新开始时间上。
     *
     * 「改到后天上午十点到十二点」里的 10:00-12:00 是在 inheritDate / fixMeridiem
     * 挪动之前算的，结束点必须跟着新开始时间走，不能直接拿片段里的时间戳。
     * 原文没写结束时间时返回 0，调用方沿用原日程时长。
     */
    private fun endFor(newStart: LocalDateTime, parsed: Parsed): Long {
        if (!parsed.hasTime) return 0L
        val duration = parsed.endAt - toMillis(parsed.dt)
        if (duration <= 0) return 0L
        return toMillis(newStart) + duration
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
