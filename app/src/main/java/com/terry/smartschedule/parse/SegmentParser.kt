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
        val usable = segments.map { it.trim() }
            .filter { it.length >= MIN_SEGMENT }
            .distinct()
        if (usable.isEmpty()) return null

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
        if (usable.size > 1) {
            val whole = parse(usable.joinToString(" "))
                ?.takeIf { it.dateSource != DateTimeParser.DateSource.NONE }
            if (whole != null && isCleanTitle(whole.title)) return whole
            if (whole != null && fallback == null) fallback = whole
        }
        return fallback
    }

    /**
     * 标题像不像"一件事的名字"。
     *
     * 只排除一种情况：**标题里带数字**。那多半是没挖干净的时间残渣 ——
     * 「明天下午3」这种。真正的事件名（开会 / 年级大会 / 交材料）不含数字。
     *
     * 「3号会议室」这类会被误判成残渣，但代价很小：退到整体解析而已。
     */
    private fun isCleanTitle(title: String): Boolean =
        title.length >= 2 && title.none { it.isDigit() }
}
