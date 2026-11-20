package com.terry.smartschedule.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 时间格式化。统一 24 小时制，中文习惯。 */
object TimeFmt {

    private val D_MMDD = DateTimeFormatter.ofPattern("M月d日")
    private val D_HM = DateTimeFormatter.ofPattern("HH:mm")
    private val D_FULL = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")

    fun toDateTime(ms: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    fun toDate(ms: Long): LocalDate = toDateTime(ms).toLocalDate()

    /** 只取时间部分，如 15:30 */
    fun time(ms: Long): String = toDateTime(ms).format(D_HM)

    /** 日期 + 时间 */
    fun full(ms: Long): String = toDateTime(ms).format(D_FULL)

    /** 列表里的时间列：全天日程显示"全天" */
    fun timeLabel(ms: Long, hasTime: Boolean): String =
        if (hasTime) time(ms) else "全天"

    /**
     * 时间列下面的小字：`至21:00` / `至次日01:00`。
     * 原文没写结束时间返回 null —— 不要用"开始+1小时"去猜。
     */
    fun endLabel(startAt: Long, endAt: Long, hasTime: Boolean): String? {
        if (!hasTime || endAt <= startAt) return null
        return if (toDate(endAt) != toDate(startAt)) {
            "至次日${time(endAt)}"
        } else {
            "至${time(endAt)}"
        }
    }

    /** 紧凑区间：18:00-21:00 / 23:00-次日01:00；没有结束时间只显示开始 */
    fun clockRange(startAt: Long, endAt: Long, hasTime: Boolean): String {
        if (!hasTime) return "全天"
        if (endAt <= startAt) return time(startAt)
        val end = if (toDate(endAt) != toDate(startAt)) {
            "次日${time(endAt)}"
        } else {
            time(endAt)
        }
        return "${time(startAt)}-$end"
    }

    /** 只取日期，如 10月24日 周六 */
    fun dateLabel(date: LocalDate): String {
        val week = "一二三四五六日"[date.dayOfWeek.value - 1]
        return "${date.format(D_MMDD)} 周$week"
    }

    /** 分组标题：10月24日 周六 · 明天 */
    fun dayHeader(date: LocalDate, today: LocalDate = LocalDate.now()): String {
        val rel = when (ChronoUnit.DAYS.between(today, date)) {
            0L -> "今天"
            1L -> "明天"
            2L -> "后天"
            -1L -> "昨天"
            else -> null
        }
        val base = dateLabel(date)
        return if (rel != null) "$base · $rel" else base
    }

    /** 通知里的一句话描述 */
    fun summary(ms: Long, hasTime: Boolean, location: String?): String {
        val sb = StringBuilder()
        sb.append(dayHeader(toDate(ms)))
        sb.append(' ')
        sb.append(if (hasTime) time(ms) else "全天")
        if (!location.isNullOrBlank()) sb.append(" · ").append(location)
        return sb.toString()
    }

    /** 带结束时间的一句话描述；结束时间未知时退回 [summary] */
    fun rangeSummary(startAt: Long, endAt: Long, hasTime: Boolean, location: String?): String {
        if (!hasTime) return summary(startAt, false, location)
        if (endAt <= startAt) return summary(startAt, true, location)
        val sb = StringBuilder()
        sb.append(dayHeader(toDate(startAt))).append(' ').append(time(startAt))
        if (toDate(endAt) != toDate(startAt)) {
            sb.append("-次日").append(time(endAt))
        } else {
            sb.append('-').append(time(endAt))
        }
        if (!location.isNullOrBlank()) sb.append(" · ").append(location)
        return sb.toString()
    }
}
