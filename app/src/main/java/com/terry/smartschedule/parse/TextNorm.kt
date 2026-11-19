package com.terry.smartschedule.parse

/**
 * 文本归一化。**只做去重用的比较键，不改动用户看到的任何文字。**
 *
 * 为什么需要：同一条通知在系统里会被反复重发 —— 群里每来一条新消息，微信就把
 * 整个会话通知重渲染一次（MessagingStyle 会带上历史消息）；用户手动点"检测"
 * 也会把通知栏里的东西再过一遍。这些"同一条消息"在字节层面长得并不一样：
 *
 * ```
 * "李四：明天下午3点开会"          ← 第一次
 * "宣传部通知群: 李四：明天 15:00 开会！"  ← 重渲染后（前缀变了、空格变了、标点变了）
 * ```
 *
 * 直接比原文等于没去重。所以比较前先抹掉这些差异：剥发送者/群名前缀、
 * 去掉空白与标点、Emoji、全角转半角、转小写。剩下的"字"才是内容本体。
 *
 * 注意：数字和汉字一律保留。抹掉数字等于把"3点"和"5点"当成同一件事。
 */
object TextNorm {

    /**
     * 发送者 / 群名前缀，如 `李四：`、`宣传部通知群: `。
     * 限定 1–20 字且不含冒号本身 —— 微信的消息前缀就这个形态，
     * 放宽会把正文里正常的"时间：10月25日"整段吃掉。
     */
    private val SENDER_PREFIX = Regex("^\\s*[^：:\\n]{1,20}[：:]\\s*")

    /** `10:30` 这种以时间开头的正文，不能被当成"发送者：正文"剥掉 */
    private val TIME_HEAD = Regex("^\\s*\\d{1,2}[:：]\\d{2}")

    /** 剥前缀。最多剥两层（"群名: 昵称: 正文"），再往里就可能是正文自身的冒号了 */
    fun stripSender(text: String): String {
        var s = text.trim()
        repeat(2) {
            if (TIME_HEAD.containsMatchIn(s)) return s
            val m = SENDER_PREFIX.find(s) ?: return s
            // "明天 15：00 开会" 的 "15：" 是时间不是发送者：
            // 冒号前是数字、冒号后紧跟数字的，一律不剥 —— 剥了 "明天 15" 就没了，
            // 归一化键会从"明天1530开会"变成"00开会"，把时间也丢了
            val colonIdx = m.range.last
            var j = colonIdx - 1
            while (j >= 0 && s[j].isWhitespace()) j--
            val beforeDigit = j >= 0 && s[j].isDigit()
            val afterDigit = colonIdx + 1 < s.length && s[colonIdx + 1].isDigit()
            if (beforeDigit && afterDigit) return s
            val next = s.replaceFirst(SENDER_PREFIX, "").trim()
            if (next == s) return s
            s = next
        }
        return s
    }

    /**
     * 比较键：只留字母、数字、汉字，全角转半角，转小写，最长 [max] 字。
     *
     * 超过 200 字直接截断 —— 键只是用来判重的，长通告的尾巴不参与比较
     * 也不影响结论（同一条通告的前 200 字必然相同）。
     */
    fun key(text: String, max: Int = 200): String {
        val sb = StringBuilder(minOf(text.length, max))
        for (ch in stripSender(text)) {
            if (sb.length >= max) break
            val c = fold(ch) ?: continue
            sb.append(c)
        }
        return sb.toString()
    }

    /** 两个标题是不是"同一件事"（去重口径，不是展示口径） */
    fun sameTitle(a: String, b: String): Boolean {
        val ka = key(a)
        return ka.isNotEmpty() && ka == key(b)
    }

    /** 全角→半角；非字母数字汉字（标点、空白、emoji、零宽字符）一律丢弃 */
    private fun fold(ch: Char): Char? = when {
        ch in '０'..'９' -> '0' + (ch - '０')
        ch in 'Ａ'..'Ｚ' -> 'A' + (ch - 'Ａ')
        ch in 'ａ'..'ｚ' -> 'a' + (ch - 'ａ')
        Character.isLetterOrDigit(ch) -> ch.lowercaseChar()
        else -> null
    }
}
