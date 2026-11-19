package com.terry.smartschedule.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 系统日历的时间换算。单独放一个文件、不引 android.* —— 就是为了能直接单测。
 *
 * 这里只为一件事存在：`ALL_DAY=1` 的事件，CalendarProvider 要求 DTSTART 是
 * **UTC 零点**，不是本地零点。
 *
 * 写成本地零点会怎样：东八区写 2026-11-19 00:00 (+08)，存进去是
 * 2026-11-18T16:00Z，系统按 UTC 日期一取 —— 那条日程跑到 18 号去了。
 * 这种错在时区为零的模拟器上完全看不出来，到真机上就整体错一天。
 */
object CalTime {

    fun allDayStartUtc(localMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val date: LocalDate = Instant.ofEpochMilli(localMs).atZone(zone).toLocalDate()
        return date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    }
}
