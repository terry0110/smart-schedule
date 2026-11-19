package com.terry.smartschedule.data

/**
 * 日程检索。**纯函数，不碰数据库、不碰 Context** —— 单测可以直接喂 Event。
 *
 * 为什么在内存里筛、不写 SQL：日程是几百条的量级，[Repo.all] 本来就已经全量读进
 * 内存（列表要按日期分组、还要算待确认数），再走一趟 SQL 只是多一次 IO。
 * 更重要的是多词 AND 在 Kotlin 里三行就写完，SQL 里得拼一堆 LIKE 和
 * 转义（用户的查询里带 `%` `_` 会变成通配符）。
 *
 * 归一化口径**故意和 [com.terry.smartschedule.parse.TextNorm] 不同**：
 * 那边是判重键，连标点和空白一起抹（"开会！" 和 "开会" 算同一条）；
 * 检索不能这样 —— 用户输 "15:00" 必须精确命中，抹掉冒号会让 "10:30" 也命中。
 * 这里只做三件事：全角转半角、转小写、忽略空白。
 */
object EventSearch {

    /** 一次查询最多拆几个词。再多就不是查询，是往输入框里贴文章了 */
    private const val MAX_TOKENS = 8

    /** 单个词的合理长度。正常关键词不会超过这个长度 */
    private const val MAX_TOKEN_LEN = 32

    /**
     * 拆词：空白分隔，多个空白算一个。
     *
     * **多词是 AND 不是 OR** —— "图书馆 开会" 要的是"在图书馆开的那个会"。
     * OR 会让每次多加一个词结果变多，用户没法靠加词收窄，那是搜索倒着走。
     *
     * 空查询返回空列表，调用方据此判断"没在搜"（空列表让 [matches] 恒为 true，
     * 也正好是"不过滤"的语义）。
     */
    fun tokens(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return query.split(WHITESPACE)
            .asSequence()
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .map { if (it.length > MAX_TOKEN_LEN) it.take(MAX_TOKEN_LEN) else it }
            .distinct()
            .take(MAX_TOKENS)
            .toList()
    }

    /**
     * 一条日程的"可搜文本"：标题 + 地点 + 原文。
     *
     * 原文（note）必须算进来：通知抓来的日程标题通常是精简过的（"开会"），
     * 真正想找的细节（"三楼"、"带材料"）只在原文里。搜不到原文等于白搜。
     */
    fun haystack(event: Event): String = normalize(
        buildString {
            append(event.title)
            event.location?.let { append(' ').append(it) }
            event.note?.let { append(' ').append(it) }
        }
    )

    /**
     * 这条日程是否命中全部词。
     *
     * @param extra 额外的可搜文本，调用方用来塞"分类名"（如"学习"），
     *              让用户输分类名也能把这一类找出来。传 null 表示不参与匹配。
     */
    fun matches(event: Event, tokens: List<String>, extra: String? = null): Boolean {
        if (tokens.isEmpty()) return true
        val hay = if (extra.isNullOrBlank()) haystack(event) else haystack(event) + normalize(extra)
        return tokens.all { hay.contains(it) }
    }

    /**
     * 高亮区间：每个词在**原文**里出现的位置（大小写不敏感）。
     *
     * 为什么在高亮这一层不做全角归一化：归一化会改变字符数（虽然这里一对一，
     * 但空白被丢掉后下标就对不上了），算出来的区间落回原文会错位。
     * 代价是全角/半角不一致时不高亮 —— 只是少个标记，不影响这条被搜出来。
     *
     * 返回的区间按起点排序、互相不重叠（相邻或重叠的合并），
     * 调用方可以直接往 Spannable 上打 span。
     */
    fun ranges(text: String, tokens: List<String>): List<IntRange> {
        if (text.isEmpty() || tokens.isEmpty()) return emptyList()
        val raw = ArrayList<IntRange>()
        for (t in tokens) {
            if (t.isEmpty()) continue
            var from = 0
            while (from <= text.length - t.length) {
                val i = text.indexOf(t, from, ignoreCase = true)
                if (i < 0) break
                raw.add(i until i + t.length)
                from = i + 1
            }
        }
        if (raw.isEmpty()) return emptyList()

        raw.sortBy { it.first }
        val merged = ArrayList<IntRange>(raw.size)
        var cur = raw[0]
        for (r in raw.drop(1)) {
            cur = if (r.first <= cur.last + 1) {
                cur.first..maxOf(cur.last, r.last)
            } else {
                merged.add(cur)
                r
            }
        }
        merged.add(cur)
        return merged
    }

    private val WHITESPACE = Regex("\\s+")

    /** 全角转半角 + 转小写 + 去掉所有空白（保留标点，见类注释） */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = when {
                ch == '\u3000' -> ' ' // 全角空格
                ch in '\uFF01'..'\uFF5E' -> (ch - 0xFEE0).lowercaseChar()
                else -> ch.lowercaseChar()
            }
            if (!c.isWhitespace()) sb.append(c)
        }
        return sb.toString()
    }
}
