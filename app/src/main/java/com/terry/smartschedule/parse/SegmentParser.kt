package com.terry.smartschedule.parse

/**
 * 从通知拆出来的若干文本段里挑一个能用的解析结果。
 *
 * 为什么不直接拼成一段解析：
 * 群聊通知常见"张三说明天开会 / 李四说哈哈哈"，拼起来会变成一句话，
 * 解析器只能取第一个日期，甚至被前后文干扰到什么都取不到。
 *
 * 策略是**逐段优先、整体兜底**：
 *  - 先逐段：群聊里一条消息通常就是完整的一件事，单看那条最准，标题也干净
 *  - 都失败再试整体：事件和时间分散在不同消息里时，拼起来才够用
 *
 * 抽成独立对象而不是写在 Service 里，是为了能直接被单元测试覆盖 ——
 * 这段逻辑错了的表现是"通知来了没反应"，在真机上极难复现。
 */
object SegmentParser {

    /** 少于这个长度基本是"哈哈""好的"，直接跳过，省得白算 */
    private const val MIN_SEGMENT = 4

    /**
     * @param segments 通知拆出来的文本段（标题、每条消息、bigText…），可能有重复
     * @param parse    解析函数，便于测试注入固定时钟
     * @return 第一个能解析出日期的结果；全都解析不出返回 null
     */
    fun pick(
        segments: List<String>,
        parse: (String) -> DateTimeParser.Result?
    ): DateTimeParser.Result? {
        // 单段解析仍要求 ≥4 字（"哈哈""好的"不值得跑正则），但整体兜底要用
        // **所有非空段**：微信里常见「明天」「开会」各发一条，单看都太短，
        // 拼起来才是一条完整日程。以前这里先按长度筛掉，短段连拼的机会都没有。
        val trimmed = segments.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (trimmed.isEmpty()) return null
        val usable = trimmed.filter { it.length >= MIN_SEGMENT }

        // 1) 逐段：群聊里一条消息通常就是完整的一件事，单独解析出来的标题最干净。
        //
        //    以前是"先整体"，4 条消息被拼成一长串，标题变成
        //    「年级大会， 李四:收到王五:好的赵六:+1」—— 日期对，标题没法看。
        //
        //    但不是所有段都能独当一面：「明天下午3点」单看只有时间，事件名在别的消息里。
        //    所以只有标题**看着像件事**才算数，否则先记着、继续找。
        var fallback: DateTimeParser.Result? = null
        for (seg in usable) {
            val r = parse(seg)?.takeIf { it.dateSource != DateTimeParser.DateSource.NONE }
                ?: continue
            if (isCleanTitle(r.title)) return r
            if (fallback == null || r.confidence > fallback.confidence) fallback = r
        }

        // 2) 整体：把各段拼起来再试一次。
        //    典型场景：一条消息只有"明天下午3点"，另一条只有"部门开会"，拼起来才完整。
        if (trimmed.size > 1) {
            val whole = parse(trimmed.joinToString(" "))
                ?.takeIf { it.dateSource != DateTimeParser.DateSource.NONE }
            if (whole != null && isCleanTitle(whole.title)) return whole
            if (whole != null && fallback == null) fallback = whole
        }
        return fallback
    }

    /**
     * 日期 / 时段 / 钟点词。标题里只剩这些，就说明它是时间残渣，不是事件名。
     *
     * `isDigit()` 只认 0-9，所以「明天下午六点到九点」会被当成干净标题 ——
     * 群聊里"时间一条、事件名一条"时，选中时间那条，建出来的日程标题就是一串时间。
     * 这里按和阿拉伯数字同一口径处理：把时间词剥掉后，至少还得剩 2 个字。
     */
    private val RE_TITLE_TIME_NOISE = Regex(
        """今天|明天|后天|大后天|今晚|明晚|今早|明早|本周|这周|下周|周末""" +
            """|周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天]""" +
            """|凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|夜晚""" +
            """|[一二两三四五六七八九十\d]{1,3}\s*[点时](?:半|[一二两三四五六七八九十\d]{1,3}\s*分?)?"""
    )

    /** 行首的发送人前缀：「张三：」 */
    private val RE_SPEAKER_PREFIX = Regex("""^[^\s:：]{1,12}\s*[:：]\s*""")

    /**
     * 标题像不像"一件事的名字"。
     *
     * 排除两种情况：
     * - 标题里带阿拉伯数字 —— 多半是没挖干净的时间残渣（「明天下午3」这种）；
     * - 剥掉时间词后剩不下东西 —— 「明天下午六点到九点」「明天到九点」这种。
     * 真正的事件名（开会 / 年级大会 / 交材料 / 签到）不受影响。
     *
     * 「3号会议室」这类会被误判成残渣，但代价很小：退到整体解析而已。
     */
    private fun isCleanTitle(title: String): Boolean {
        if (title.length < 2) return false
        if (title.any { it.isDigit() }) return false
        val body = RE_SPEAKER_PREFIX.replace(title, "")
        val left = RE_TITLE_TIME_NOISE.replace(body, "")
            .filterNot { it.isWhitespace() || it in "，,。.、;；:：-—_~～·!！?？\"'“”‘’（）()【】[]" }
        return left.length >= 2
    }
}
