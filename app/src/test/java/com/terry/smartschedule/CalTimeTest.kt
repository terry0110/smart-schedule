package com.terry.smartschedule

import com.terry.smartschedule.sync.CalTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * 系统日历的时间换算。
 *
 * 只测一件事，但这件事错了最难被发现的：全天日程的 DTSTART 必须是 UTC 零点。
 * 时区为零的环境（很多模拟器）怎么算都对，一到东八区真机就整体错一天。
 */
class CalTimeTest {

    private val beijing = ZoneId.of("Asia/Shanghai")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `东八区的全天日程落在当天 UTC 零点`() {
        // 2026-11-19 09:00 +08 这条"全天"日程
        val local = java.time.LocalDateTime.of(2026, 11, 19, 9, 0)
            .atZone(beijing).toInstant().toEpochMilli()

        val start = CalTime.allDayStartUtc(local, beijing)

        // 换算结果的 UTC 日期必须还是 19 号 —— 写成"本地零点"的话这里会是 18 号 16:00Z
        assertEquals(java.time.LocalDate.of(2026, 11, 19), java.time.Instant.ofEpochMilli(start).atZone(utc).toLocalDate())
        assertEquals(java.time.LocalTime.MIDNIGHT, java.time.Instant.ofEpochMilli(start).atZone(utc).toLocalTime())
    }

    @Test
    fun `同一天的任意时刻换算出同一个零点`() {
        val morning = java.time.LocalDateTime.of(2026, 11, 19, 0, 1)
            .atZone(beijing).toInstant().toEpochMilli()
        val night = java.time.LocalDateTime.of(2026, 11, 19, 23, 59)
            .atZone(beijing).toInstant().toEpochMilli()

        assertEquals(
            CalTime.allDayStartUtc(morning, beijing),
            CalTime.allDayStartUtc(night, beijing)
        )
    }

    @Test
    fun `跨时区不改变结果`() {
        val local = java.time.LocalDateTime.of(2026, 11, 19, 12, 0)
            .atZone(beijing).toInstant().toEpochMilli()

        // UTC 环境下"19 号中午"本身就落在 19 号，两边都必须给出 19 号零点
        assertEquals(
            java.time.LocalDate.of(2026, 11, 19),
            java.time.Instant.ofEpochMilli(CalTime.allDayStartUtc(local, utc)).atZone(utc).toLocalDate()
        )
    }

    @Test
    fun `每月一日不出错`() {
        // 月末月初是日期换算最容易翻车的点
        val local = java.time.LocalDateTime.of(2026, 12, 1, 8, 30)
            .atZone(beijing).toInstant().toEpochMilli()
        assertEquals(
            java.time.LocalDate.of(2026, 12, 1),
            java.time.Instant.ofEpochMilli(CalTime.allDayStartUtc(local, beijing)).atZone(utc).toLocalDate()
        )
    }
}
