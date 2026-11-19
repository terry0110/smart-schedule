package com.terry.smartschedule.notify

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.terry.smartschedule.data.Event
import com.terry.smartschedule.data.Repo
import com.terry.smartschedule.parse.Prefs

/**
 * 用 AlarmManager 排提醒；不需要常驻前台服务。
 *
 * 什么时候响的**判断**在 [ReminderPlan]（纯计算、有单测），这里只负责把结论落到系统上。
 */
object ReminderScheduler {

    /**
     * "这条提醒已经响过"的键。带开始时间戳，改了时间就自动重新算未提醒。
     *
     * 必须落盘（见 [com.terry.smartschedule.parse.Prefs.remindedKeys]）：
     * 只在内存里的话，进程一重启补发就会把已经响过的（甚至是用户刚点了"稍后"的）
     * 再弹一遍。
     */
    fun remindedKey(event: Event): String = "${event.id}:${event.startAt}"

    fun schedule(context: Context, event: Event) {
        when (val p = ReminderPlan.plan(event, System.currentTimeMillis())) {
            is ReminderPlan.Plan.Skip -> {
                // "已经开始了" 不必撤闹钟（本来就没有）；但"已完成/不提醒"要主动撤，
                // 否则用户改成不提醒之后，之前排好的闹钟还会响。
                if (p.reason.startsWith("已完成") || p.reason.startsWith("这条设了不提醒")) {
                    cancel(context, event.id)
                }
            }

            is ReminderPlan.Plan.At -> setAlarm(context, event.id, p.triggerAt)
        }
    }

    fun cancel(context: Context, eventId: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pendingIntent(context, eventId)) }
    }

    /**
     * 延后 n 分钟再响一次。
     *
     * 用的是和正常提醒同一个 PendingIntent（同一个 requestCode），所以**会覆盖**
     * 这条日程原来的闹钟。这是有意的：用户按了"稍后 10 分钟"就是不想现在被打断，
     * 保留原闹钟反而会在两分钟后照旧响起来。
     */
    fun snooze(context: Context, eventId: Long, minutes: Int = 10) {
        setAlarm(context, eventId, System.currentTimeMillis() + minutes * 60_000L)
    }

    /** 开机 / 应用更新后重排所有未来的提醒 */
    suspend fun rescheduleAll(context: Context) {
        Repo.upcoming().forEach { schedule(context, it) }
    }

    /**
     * 补发漏掉的提醒。
     *
     * 什么情况下会漏：手机重启、系统清掉闹钟、应用被强杀、或者**事件创建时提醒点已经过去**
     * （用户看到消息时离开会只剩三分钟）。这几种在界面上都看不出来，只有一个后果 ——
     * 该响的时候没响。
     *
     * 判断依据很直接：[ReminderPlan] 里"该立刻响"的那一档就是漏掉的。
     *
     * @return 补发了几条
     */
    suspend fun catchUp(context: Context): Int {
        val now = System.currentTimeMillis()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return 0

        // 从"今天零点"开始查，而不是从现在 —— 全天日程的 startAt 是当天 09:00，
        // 下午补发时按"现在"查会把它们整类漏掉。
        val dayStart = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val upcoming = Repo.upcoming(dayStart)

        // 顺手清掉失效的键：日程删了、时间改了，旧键留着只会让集合无限长大
        val valid = upcoming.mapTo(HashSet()) { remindedKey(it) }
        val reminded = Prefs.remindedKeys
        val pruned = if (reminded.size > valid.size) reminded.intersect(valid) else reminded
        if (pruned.size != reminded.size) Prefs.remindedKeys = pruned

        sweepOrphans(context, nm)

        var fired = 0
        for (event in upcoming) {
            val p = ReminderPlan.plan(event, now)
            if (!ReminderPlan.isImmediate(p, now)) continue

            // 已经响过了。用户点过"稍后 10 分钟"也算响过 —— 他明确说了现在别烦他，
            // 进来一次应用就再弹一次是最讨人厌的那种"智能"。
            if (remindedKey(event) in reminded) continue

            // 通知栏里还有这条的提醒，说明响过但用户没清掉
            val showing = runCatching {
                nm.activeNotifications.any { it.id == NotificationIds.remind(event.id) }
            }.getOrDefault(false)
            if (showing) continue

            Notifications.postRemind(context, event)
            fired++
        }
        return fired
    }

    /**
     * 清掉"日程已经不存在、通知还挂在栏里"的提醒。
     *
     * 为什么需要兜这一层：删除走的是一条条显式 cancel 的路，只要有一条路径漏了
     * （批量删、通知栏点删除、进程在中间被杀、清空数据……），用户就会在通知栏里看到一条
     * 点进去啥也没有的提醒。这种残留自己不会消失，只能等一小时超时。
     *
     * 与其把每条删除路径都盯死，不如每次进补发时顺手扫一遍 —— 覆盖所有路径。
     */
    /**
     * 该撤掉哪些提醒：发出去过、但日程已经不在了。
     *
     * 抽成纯函数是为了能单测 —— 判断错了的代价是用户通知栏里留一条点开啥也没有的提醒。
     */
    fun orphanReminds(posted: Set<Long>, alive: Set<Long>): Set<Long> = posted - alive

    private suspend fun sweepOrphans(
        context: Context,
        nm: NotificationManager
    ) {
        val alive = runCatching { Repo.all().mapTo(HashSet()) { it.id } }.getOrNull() ?: return

        // ① 主力：自己记的账（详见 [Prefs.postedReminds]）。
        //
        // 关键是**不依赖系统回看**：发过什么只有自己最清楚，问系统"栏里现在有什么"
        // 既可能不全，也查不到"发出去过、但已经被用户划掉"的那些。自己记账则对
        // 每条路径都成立 —— 不管日程是怎么没的，只要它没了、而我们发过提醒，就撤。
        val posted = Prefs.postedReminds
        val orphans = orphanReminds(posted, alive)
        for (id in orphans) {
            runCatching { nm.cancel(NotificationIds.remind(id)) }
            // 顺手撤掉可能还排着的闹钟：日程都没了，再响一次最莫名其妙
            runCatching { cancel(context, id) }
        }
        if (orphans.isNotEmpty()) Prefs.postedReminds = posted - orphans

        // ② 兜底：系统回看。能看见就顺手扫一遍，收掉没记上账的老提醒（比如旧版本发的）。
        val active = runCatching { nm.activeNotifications }.getOrNull() ?: return
        for (sbn in active) {
            if (sbn.packageName != context.packageName) continue
            val ch = sbn.notification.channelId

            // 到点提醒本体（remind_v2 / remind_quiet / remind_vibrate）
            val isRemind = Notifications.isRemindChannel(ch)

            // 还有它的**锁屏脱敏版**（channel=info，id 和本体相同）。
            // 实测（Android 9）：取消提醒本体时系统不会连带清掉这个脱敏版，
            // 于是一条已经处理掉的提醒会在锁屏上阴魂不散。
            // 待确认通知的脱敏版 id 是 eventId + 偏移量，所以要排除掉，别误删。
            val isRemindLockScreen = ch == Notifications.CH_INFO &&
                sbn.id.toLong() < NotificationIds.PENDING_OFFSET

            if (!isRemind && !isRemindLockScreen) continue
            if (sbn.id.toLong() in alive) continue
            runCatching { nm.cancel(sbn.id) }
        }
    }

    private fun setAlarm(context: Context, eventId: Long, triggerAt: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, eventId)

        // canScheduleExactAlarms 在个别 ROM 上不准（说能排，真排的时候抛 SecurityException）。
        // 排提醒失败不该把整个建日程流程带崩，所以这里兜一层，失败就退化成非精确。
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        val exactOk = canExact && runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }.isSuccess
        if (!exactOk) {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
        }
    }

    /** 这台机器上能不能排"准点"的闹钟（Android 12+ 需要用户单独授权） */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    private fun pendingIntent(context: Context, eventId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_ID, eventId)
        }
        return PendingIntent.getBroadcast(
            context,
            eventId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
