package com.terry.smartschedule.notify

import com.terry.smartschedule.parse.DateTimeParser

/**
 * 通知文本的前置判别：**这条到底是不是在说一件要发生的事**。
 *
 * 为什么需要它：
 * 解析器只能看"文本里有没有日期"，看不出"这条通知是不是在讲日程"。
 * 真机上出现过这么一条被建成了日程：
 *
 *   smart-schedule-v1.8-debug.apk.1 已保存至"文件管理/安装包"，点击查看。
 *
 * 根因是 `v1.8` 里的 `1.8` 被当成 1 月 8 日（正则的负向后顾没排除字母，已单独修）。
 * 但同类问题还会以别的形式出现 —— 文件名里带日期就会：
 *   「下载完成：10月25日会议纪要.docx」  ← 这是下载通知，不是日程
 * 所以除了修正则，还要在通知层加一道语义判断。
 */
object TextFilter {

    /** 文件 / 系统状态类通知的强特征词 */
    private val HINTS = listOf(
        "已保存至", "保存至", "已保存到", "保存到",
        "下载完成", "已下载", "正在下载", "开始下载", "下载失败",
        "安装包", "已安装", "安装完成", "更新完成", "升级完成", "已更新到",
        "已复制到", "复制到", "拷贝到", "剪贴板",
        "清理完成", "已清理", "释放了", "垃圾文件",
        "充电中", "电量低", "耗电", "省电模式"
    )

    /**
     * 运营商 / 账单类短信的特征词。
     * 这类文本里全是数字（流量 8.5GB、资费 0.29 元/MB），解析器很容易把 `8.5`
     * 当成 8 月 5 日 —— 真机上就这么把流量提醒建成了日程。
     */
    private val CARRIER_HINTS = listOf(
        "流量", "话费", "套餐", "资费", "余额不足", "欠费", "超出部分",
        "元/MB", "元/GB", "元/分钟", "本月已使用", "已用流量", "剩余流量", "积分兑换"
    )

    /**
     * 营销推送的强特征词。
     *
     * 真机样本：「小王同学 🔥 王者荣耀2026周年庆，10月24日登录游戏领好礼！【登录就送】」
     * 里面确实有"10月24日"，但这是广告，不是要你去做一件事。
     * 只收只出现在促销里的词 —— "周年庆""活动"这类真日程也会用，不能收。
     */
    private val PROMO_HINTS = listOf(
        "登录就送", "登录领取", "领好礼", "点击领取", "立即领取", "免费领",
        "优惠券", "秒杀", "满减", "折上折", "买一送一", "下单立减", "限时抢"
    )

    /** 文件扩展名。有它基本就是文件通知，不太可能是日程 */
    private val RE_FILE_EXT = Regex(
        """\.(apk|zip|rar|7z|docx?|xlsx?|pptx?|pdf|txt|csv|jpg|jpeg|png|gif|webp|mp4|mp3|wav)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 中文数字时间的强信号。
     *
     * 通知里「明天下午六点到九点」比「明天下午6点」更常见，但早年只认阿拉伯数字，
     * 结果这类正文被 `isSmallTalk` 当成"只有日期没有事情"直接丢掉 —— 通知抓取
     * 完全没反应。这里只收两种不会误伤的写法：
     * 1. 带时段词：「下午六点」「明晚七点半」；
     * 2. 区间：「六点到九点」「下午三点至五点」。
     * 单说「一点」不收 —— 「明天一点小事」是聊天，不是日程。
     */
    private val CN_TIME_SIGNAL =
        """(?:凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|夜晚|今晚|明晚)\s*[一二两三四五六七八九十]{1,3}\s*[点时]""" +
            """|[一二两三四五六七八九十]{1,3}\s*[点时]\s*(?:到|至|-|–|~|～)\s*""" +
            """(?:(?:凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|夜晚)\s*)?""" +
            """(?:[一二两三四五六七八九十\d]{1,3}\s*[点时]|\d{1,2}\s*[:：]\s*\d{2})"""

    /**
     * "这确实在说某件事要发生"的信号。
     * 只收强信号（明天/周X/几点），**故意不收 "10月25日" 这种纯日期** ——
     * 因为文件名里天天出现日期，靠它区分不了"会议纪要.docx"和"10月25日开会"。
     */
    private val RE_REAL_SCHEDULE = Regex(
        """明天|后天|今天|今晚|明早|明晚|明上午|明下午""" +
            """|下周|本周|这周|周末|周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天]""" +
            """|\d{1,2}\s*[点时]|\d{1,2}\s*[:：]\s*\d{2}|\d+\s*天后|""" + CN_TIME_SIGNAL
    )

    /** 账号前缀：带这些开头的基本可以确定是人名/账号，不是内容 */
    private val ACCOUNT_PREFIXES = listOf("用户", "QQ", "qq", "Qq", "wxid_", "wx_")

    private val RE_ACCOUNT_SHAPE = Regex("""[A-Za-z0-9_\-.]+""")

    /** 形如 10.25 / 3.15 的是日期，不是账号 —— 光看"字母数字点"的形状区分不出来 */
    private val RE_DATE_LIKE = Regex("""\d{1,4}\.\d{1,2}""")

    /**
     * 这段是不是"发送人标识"而不是内容。
     *
     * QQ / 微信的通知标题经常直接就是发送人：`123456789`、`用户12345678`、`wxid_xxx`。
     * 把它当候选段拼进正文，日程标题就会变成这串号码 —— 真机上就是这么报的。
     *
     * 判断必须保守：`3.15`、`2026` 这类是日期/年份，误伤了会丢信息。
     * 所以纯数字要求至少 6 位（5 位以内可能是年份、房间号、日期）。
     */
    fun looksLikeSenderId(text: String): Boolean {
        val t = text.trim()
        if (t.length < 5 || t.length > 24) return false
        // 日期形状先放过，否则 10.25 会被当成账号丢掉
        if (RE_DATE_LIKE.matches(t)) return false
        // 带账号前缀：只要含数字就当账号
        if (ACCOUNT_PREFIXES.any { t.startsWith(it) }) return t.any { it.isDigit() }
        // 纯数字
        if (t.all { it.isDigit() }) return t.length >= 6
        // 字母数字下划线混排（微信号、邮箱前缀），且必须含数字
        return t.matches(RE_ACCOUNT_SHAPE) && t.any { it.isDigit() }
    }

    /**
     * 是不是文件 / 系统状态类通知。
     *
     * 判断顺序很重要：先看有没有文件特征，再看有没有真实日程信号。
     * 只要出现"明天下午3点"这类表达，就一律当日程处理 ——
     * 「明天下午3点前把报告.docx发我」不能被当成文件通知丢掉。
     */
    fun isNonScheduleNotice(text: String): Boolean {
        if (text.isBlank()) return false
        val looksLikeFile = HINTS.any { text.contains(it) } || RE_FILE_EXT.containsMatchIn(text)
        val looksLikeCarrier = CARRIER_HINTS.any { text.contains(it) }
        val looksLikePromo = PROMO_HINTS.any { text.contains(it) }
        if (!looksLikeFile && !looksLikeCarrier && !looksLikePromo) return false
        // 只要出现真正的时间表达，仍然按日程处理
        return !RE_REAL_SCHEDULE.containsMatchIn(text)
    }

    // ---------- 闲聊判别 ----------

    /** 模糊日期词。只有它、别的线索都没有时，多半是在聊天 */
    private val RE_VAGUE_DATE = Regex(
        """今天|今日|今晚|今早|明天|明日|明早|明晚|后天|大后天|周[一二三四五六日天]"""
    )

    /** 明确时间点 —— 在约时间才会说"几点" */
    private val RE_EXPLICIT_TIME = Regex(
        """\d{1,2}\s*[点时]|\d{1,2}\s*[:：]\s*\d{2}|\d+\s*天后|""" + CN_TIME_SIGNAL
    )

    /**
     * 绝对日期。
     *
     * 末尾的负向前瞻必须同时排除**数字和字母**：只写 `(?![A-Za-z])` 的话，
     * `\d{1,2}` 会贪婪匹配 `31` 后失败、回溯成 `3`，此时前瞻看到的是后面的 `1`
     * （数字不是字母）就通过了 —— 于是 `12.31lyd` 被匹配成 `2.3`。
     * 这个坑很隐蔽，是单测里"昵称不该被当日期"那条抓出来的。
     */
    private val RE_ABS_DATE = Regex(
        """\d{1,2}\s*月\s*\d{1,2}[日号]?|\d{1,2}\s*[-/.]\s*\d{1,2}(?![A-Za-z\d])"""
    )

    /** 动作词：有它说明在说"要去做什么"，不是纯闲聊 */
    private val ACTION_WORDS = listOf(
        "去", "来", "回", "见", "交", "参加", "办", "搬", "取", "找", "约", "送", "拿"
    )

    /**
     * 这条是不是"纯闲聊"：只有模糊日期词，没有明确时间点、没有事件、也没有动作。
     *
     * 典型样本：「阿伟：今天天气不错」—— 有"今天"，但没有任何要发生的事。
     * 这类文本会被解析成"今天 全天"的日程，标题是"天气不错"。
     *
     * 分寸：宁可漏掉少量"明天去北京"这类模糊表达，也不要把聊天全建成日程 ——
     * 用户能接受少记一条，不能接受列表里全是噪音。
     */
    fun isSmallTalk(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        // 连模糊日期都没有的话，解析器本来也会失败，不必在这里拦
        if (!RE_VAGUE_DATE.containsMatchIn(t)) return false
        if (RE_EXPLICIT_TIME.containsMatchIn(t)) return false
        if (RE_ABS_DATE.containsMatchIn(t)) return false
        if (ACTION_WORDS.any { t.contains(it) }) return false
        if (DateTimeParser.EVENT_WORDS.any { t.contains(it) }) return false
        return true
    }
}
