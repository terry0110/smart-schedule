package com.terry.smartschedule.util

/**
 * 编辑面板「其它，请输入」的时长解析。
 *
 * 不依赖 Android，跑在 JVM 单测上。规则：
 * - 纯数字 = 分钟，如 `90`
 * - 数字 + 单位，如 `1小时30分钟` / `2h` / `1h30m` / `90m`
 * - 必须大于 0，且不超过 [MAX_MINUTES]（7 天），防止溢出和误输入
 */
object DurationInput {

    const val MAX_MINUTES = 7 * 24 * 60L

    private val RE = Regex(
        """^\s*(?:(\d+)\s*(?:小时|个小时|h|时))?\s*(?:(\d+)\s*(?:分钟|分|m))?\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** 解析成毫秒；输入不合法返回 null，调用方负责提示。 */
    fun parse(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null

        // 纯数字走分钟，避免正则把 "90" 误判成 90 小时
        s.toLongOrNull()?.let { minutes ->
            return if (minutes in 1..MAX_MINUTES) minutes * 60_000L else null
        }

        val m = RE.matchEntire(s) ?: return null
        val hours = m.groupValues[1].toLongOrNull() ?: 0L
        val minutes = m.groupValues[2].toLongOrNull() ?: 0L
        val total = hours * 60L + minutes
        if (total <= 0L || total > MAX_MINUTES) return null
        return total * 60_000L
    }

    /** 按钮回显：0 = 全天，其余转成 15分钟 / 1小时 / 1小时30分钟。 */
    fun label(ms: Long): String {
        if (ms <= 0L) return "全天"
        val total = ms / 60_000L
        val h = total / 60L
        val m = total % 60L
        return when {
            h > 0L && m > 0L -> "${h}小时${m}分钟"
            h > 0L -> "${h}小时"
            else -> "${m}分钟"
        }
    }
}
