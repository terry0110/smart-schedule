package com.terry.smartschedule.sync

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.terry.smartschedule.data.Event
import java.util.TimeZone

/**
 * 把日程写进系统日历（CalendarProvider）。
 *
 * 这一层只做"和系统日历打交道"的脏活，不碰自己的数据库 —— 谁该同步、同步到哪一行，
 * 由 [com.terry.smartschedule.data.Repo] 记账（`events.cal_event_id`）。
 *
 * 三个容易踩的点，都在这儿处理掉了：
 *
 * 1. **不是每台机器都有可写日历**。系统日历账户可能没登录、可能全被设成只读。
 *    没有就地建一个 `ACCOUNT_TYPE_LOCAL` 的本地日历 —— 这种日历不参与任何账号同步，
 *    只存在这台设备上，和"纯本地"的产品定位一致。
 * 2. **全天日程的时间必须是 UTC 午夜**。CalendarProvider 对 `ALL_DAY=1` 的行要求
 *    DTSTART/DTEND 是 UTC 零点，写本地时间会让日历里那条往前或往后错一天（时区非零时必错）。
 * 3. **不挂系统闹钟**（`HAS_ALARM=0`）。提醒由本应用的 AlarmManager 负责，
 *    挂了就成了双响：系统日历响一次、应用再响一次，用户会以为程序出 bug。
 */
object CalendarSync {

    private const val TAG = "CalendarSync"

    /** 我们自己建的那个本地日历的名字，也是识别"这行是不是我们建的"的依据 */
    const val LOCAL_ACCOUNT = "智汇日程"

    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    /** 内存缓存，省掉每次写日程都查一遍 Calendars（进程重启后重查一次，成本可忽略） */
    private var cachedCalendarId: Long = 0L

    fun hasPermission(context: Context): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 找一个能写的日历 id；一个都没有就建本地日历。
     *
     * @return null 表示没有权限、或者系统日历整个不可用（部分 ROM 精简掉了 CalendarProvider）
     */
    fun calendarId(context: Context): Long? {
        if (!hasPermission(context)) return null
        if (cachedCalendarId > 0 && stillWritable(context, cachedCalendarId)) return cachedCalendarId

        val found = queryWritable(context)
        if (found != null) {
            cachedCalendarId = found
            return found
        }
        val created = createLocalCalendar(context)
        cachedCalendarId = created ?: 0L
        return created
    }

    private fun stillWritable(context: Context, id: Long): Boolean = runCatching {
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id),
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.DELETED
            ),
            null, null, null
        )?.use { c ->
            c.moveToFirst() &&
                c.getInt(1) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR &&
                c.getInt(2) == 0
        } ?: false
    }.getOrDefault(false)

    private fun queryWritable(context: Context): Long? = runCatching {
        val sel = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=? AND " +
            "${CalendarContract.Calendars.VISIBLE}=1 AND " +
            "${CalendarContract.Calendars.DELETED}=0"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        // 优先本机日历：用户明确在用的那个大概率是系统日历，本地账户通常是同步过来的
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            sel, args,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()

    /**
     * 建一个只在本机的日历。
     *
     * 必须带 `CALLER_IS_SYNCADAPTER=true` 和 ACCOUNT_NAME/ACCOUNT_TYPE 两个查询参数，
     * 否则 Provider 直接拒绝插入（这是 Calendars 表的硬性要求，不是可选项）。
     */
    private fun createLocalCalendar(context: Context): Long? = runCatching {
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT
            )
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL
            )
            .build()

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_ACCOUNT)
            // 和主色一致的蓝，日历里一眼能认出哪些是这边同步过来的
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF2563EB.toInt())
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER
            )
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri2 = context.contentResolver.insert(uri, values) ?: return null
        ContentUris.parseId(uri2)
    }.onFailure { Log.w(TAG, "建本地日历失败", it) }.getOrNull()

    /** 新建一条系统日历事件，返回系统那边的 id */
    fun push(context: Context, event: Event): Long? {
        val calId = calendarId(context) ?: return null
        return runCatching {
            val uri = context.contentResolver.insert(
                CalendarContract.Events.CONTENT_URI, values(event, calId)
            ) ?: return null
            ContentUris.parseId(uri)
        }.onFailure { Log.w(TAG, "写日历失败", it) }.getOrNull()
    }

    /**
     * 更新已经同步过的那条。
     *
     * @return false 表示系统那边那行已经不在了（用户从日历里删了、或换过日历账户）——
     *         调用方要据此改回"未同步"，下次重新推一条，而不是一直往一个不存在的 id 上写。
     */
    fun update(context: Context, event: Event, calId: Long): Boolean {
        if (calId <= 0) return false
        if (!hasPermission(context)) return false
        return runCatching {
            val target = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, calId)
            val n = context.contentResolver.update(target, values(event, 0L), null, null)
            n > 0
        }.onFailure { Log.w(TAG, "更新日历失败", it) }.getOrDefault(false)
    }

    fun remove(context: Context, calId: Long) {
        if (calId <= 0 || !hasPermission(context)) return
        runCatching {
            val target = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, calId)
            context.contentResolver.delete(target, null, null)
        }.onFailure { Log.w(TAG, "删日历事件失败", it) }
    }

    /** 系统日历里还在不在（批量导入前避免重复建） */
    fun exists(context: Context, calId: Long): Boolean {
        if (calId <= 0 || !hasPermission(context)) return false
        return runCatching {
            context.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, calId),
                arrayOf(CalendarContract.Events._ID), null, null, null
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    /**
     * Event → ContentValues。
     *
     * 抽出来是为了能单独验证时间换算（[allDayStartUtc]），
     * 全天日程错一天是这类功能最常见、也最难被发现的 bug。
     */
    fun values(event: Event, calId: Long): ContentValues {
        val cv = ContentValues()
        if (calId > 0) cv.put(CalendarContract.Events.CALENDAR_ID, calId)
        cv.put(CalendarContract.Events.TITLE, event.title)
        cv.put(CalendarContract.Events.DESCRIPTION, description(event))
        cv.put(CalendarContract.Events.HAS_ALARM, 0) // 提醒由本应用负责，见类注释
        cv.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)

        if (event.hasTime) {
            cv.put(CalendarContract.Events.ALL_DAY, 0)
            cv.put(CalendarContract.Events.DTSTART, event.startAt)
            // 原文写了结束时间就按原文；没写才给一小时，别把"3点-5点"压成一小时
            val end = if (event.endAt > event.startAt) event.endAt else event.startAt + 60 * 60_000L
            cv.put(CalendarContract.Events.DTEND, end)
        } else {
            cv.put(CalendarContract.Events.ALL_DAY, 1)
            // 全天事件必须是 UTC 零点，否则东八区会整体往前错一天（见类注释第 2 条）
            val start = allDayStartUtc(event.startAt)
            cv.put(CalendarContract.Events.DTSTART, start)
            cv.put(CalendarContract.Events.DTEND, start + 24 * 60 * 60_000L)
            cv.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        }
        return cv
    }

    /** 本地日期 → 那一天的 UTC 零点（毫秒）。换算逻辑在 [CalTime]，那边有单测 */
    fun allDayStartUtc(localMs: Long): Long = CalTime.allDayStartUtc(localMs)

    private fun description(event: Event): String = buildString {
        val loc = event.location?.takeIf { it.isNotBlank() }
        if (loc != null) append("地点：").append(loc).append('\n')
        val note = event.note?.takeIf { it.isNotBlank() }
        if (note != null) append(note).append('\n')
        append("由智汇日程同步")
    }

    /** 权限被撤销或换设备时，缓存要作废，否则会一直往旧 id 上写 */
    fun invalidateCache() {
        cachedCalendarId = 0L
    }
}
