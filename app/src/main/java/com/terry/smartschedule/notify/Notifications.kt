package com.terry.smartschedule.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.terry.smartschedule.R
import com.terry.smartschedule.data.Event
import com.terry.smartschedule.parse.Prefs
import com.terry.smartschedule.ui.ReminderActivity
import com.terry.smartschedule.util.TimeFmt

/** 通知渠道与发通知 */
object Notifications {

    /**
     * 到点提醒的渠道 id。
     *
     * **带 `_v2` 是有意的，不是随手改名。** 通知渠道的配置（声音、震动、重要性）
     * 一旦创建就**永久固定**，之后代码里再 `createNotificationChannel` 改它，
     * 系统会直接忽略 —— 只有删掉渠道重建、或者换个新 id 才生效。
     *
     * 旧渠道 `remind` 创建时只设了 IMPORTANCE_HIGH、没设震动，模拟器上实测是
     * `mVibrationEnabled=false`：手机放兜里，一条不响不震的提醒等于没有。
     * 用户感受到的就是"它只在应用里提醒我"。
     */
    const val CH_REMIND = "remind_v2"

    /**
     * 自动建日程的回执。**低优先级 + 无声**，因为它只是"我知道了"，
     * 不是需要人立刻处理的事。
     */
    const val CH_CAPTURE = "capture"

    /**
     * 静默提醒：只往通知栏放一条，不响不震。
     *
     * 重要性低到 LOW 才能真的不出声 —— 同一个渠道做不到"有时响有时不响"，
     * 渠道的响铃/震动是固定的，所以"静默"必须是**另一个渠道**。
     */
    const val CH_REMIND_QUIET = "remind_quiet"

    /**
     * 只震动、不响铃的提醒。
     *
     * 和 [CH_REMIND_QUIET] 一样，必须独立一个渠道：渠道的声音 / 震动在建好那一刻就固定了，
     * 同一个渠道做不到"这次响、下次不响"。
     *
     * IMPORTANCE_HIGH 是为了拿到横幅 —— 它只要不响，会议室里就只是屏幕上方闪一下；
     * 而 LOW 的重要性连横幅都不出，用户只能靠手感发现，太容易漏。
     * 声音显式置 null：这个渠道**永远不响**。
     */
    const val CH_REMIND_VIBRATE = "remind_vibrate"

    /** 两短一长。四个渠道共用同一套节奏，用户的手感才是一致的 */
    private val VIBRATION_PATTERN = longArrayOf(0, 300, 200, 300, 200, 700)

    /** 需要用户确认的日程（带确认/删除按钮），要能被看见 */
    const val CH_INFO = "info"

    /**
     * 这个渠道发出去的是不是"到点提醒"本体。
     *
     * 单独抽成函数，是因为清扫逻辑（[ReminderScheduler] 的孤儿提醒回收）要按渠道判断
     * "这条通知该不该跟着日程一起撤"。而**每加一档提醒方式就会多一个渠道** ——
     * v1.27 加振动档时漏在这里写上，结果振动档发出的残留提醒不会被兜底清扫。
     * 收口到一处 + 单测覆盖，下次加第五档时测试会直接报出来。
     */
    fun isRemindChannel(ch: String?): Boolean =
        ch == CH_REMIND || ch == CH_REMIND_QUIET || ch == CH_REMIND_VIBRATE

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "到点提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "日程开始前的提醒，会响铃、震动；息屏或锁屏时弹出全屏提示"
                // 震动模式：两短一长，比默认"嗡一下"更容易把人从别的事里拉出来
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
                // 走闹钟音量 + 闹钟音效：手机媒体音量为 0 时也能听见。
                // 铃声单独用闹钟音，是因为"日程到点"和"收到一条消息"该有不同的份量。
                val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                if (alarmSound != null) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(alarmSound, attrs)
                }
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CH_REMIND_QUIET, "到点提醒（静默）", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "选了「只通知栏」时的提醒：不响不震，只在通知栏占一行"
                setShowBadge(true)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CH_REMIND_VIBRATE, "到点提醒（仅震动）", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "选了「振动」时的提醒：只震不响，不弹全屏"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
                setSound(null, null)
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(CH_CAPTURE, "自动记录回执", NotificationManager.IMPORTANCE_LOW).apply {
                description = "识别到日程后的简短提示，不响不震"
                setShowBadge(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "需要确认的日程", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "自动识别出来但没把握，等你确认"
            }
        )
    }

    fun hasPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 到点提醒。方式取"这条日程自己选的"；没单独选（-1）就退回设置里的默认值。
     *
     * | 方式 | 渠道 | 全屏意图 | 响铃 | 震动 |
     * |---|---|---|---|---|
     * | 全屏 | remind_v2 (HIGH) | 挂 | 有 | 有 |
     * | 横幅 | remind_v2 (HIGH) | **不挂** | 有 | 有 |
     * | 振动 | remind_vibrate (HIGH，无声) | 不挂 | **无** | **有** |
     * | 只通知栏 | remind_quiet (LOW) | 不挂 | 无 | 无 |
     *
     * 「横幅」和「全屏」用同一个渠道，区别只在**挂不挂全屏意图**：
     * 挂了系统才会在锁屏/息屏时弹整页；不挂就只会从顶部滑一条横幅下来，不盖住当前应用
     * —— 这就是打游戏、看视频时想要的效果。
     * 「振动」和「只通知栏」则必须换渠道：渠道一建好，有没有声音、震不震就固定了。
     * 「知道了」「稍后 10 分钟」两个按钮四档都留着，静默模式也得能一键处理掉。
     */
    fun postRemind(c: Context, event: Event) {
        if (!hasPermission(c)) return

        val style = RemindStyle.resolve(event.remindStyle, Prefs.remindStyle)
        val quiet = style.quiet
        val vibrateOnly = style.vibrateOnly
        val channel = when {
            quiet -> CH_REMIND_QUIET
            vibrateOnly -> CH_REMIND_VIBRATE
            else -> CH_REMIND
        }
        val body = TimeFmt.summary(event.startAt, event.hasTime, event.location)
        val fullScreen = fullScreenIntent(c, event.id)

        val builder = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(event.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(
                when {
                    quiet -> NotificationCompat.PRIORITY_LOW
                    // 振动档要横幅：不响，但屏幕上得能看见一眼
                    vibrateOnly -> NotificationCompat.PRIORITY_HIGH
                    else -> NotificationCompat.PRIORITY_MAX
                }
            )
            .setCategory(
                if (quiet || vibrateOnly) NotificationCompat.CATEGORY_REMINDER
                else NotificationCompat.CATEGORY_ALARM
            )
            .setAutoCancel(true)
            // 点通知永远进提醒页：静默模式下用户是主动点进来的，这时候该给他看详情
            .setContentIntent(fullScreen)
            .addAction(0, "知道了", actionIntent(c, event.id, NotifyAction.ACTION_DONE, 3))
            .addAction(0, "稍后 10 分钟", actionIntent(c, event.id, NotifyAction.ACTION_SNOOZE, 4))
            .setTimeoutAfter(60 * 60 * 1000L)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreenSafe(c, "有一项日程到点了"))

        // 只有"全屏"这一档才挂全屏意图。
        //
        // 挂上之后具体弹不弹仍由系统判断（锁屏/息屏弹整页，正在操作时退回横幅），
        // 但"不挂"是确定性的：选了横幅就绝不会盖住游戏。
        // 重复弹的问题由 [demoteRemind] 兜 —— 页面显示过一次就把通知降级。
        if (style.popsFullScreen) {
            builder.setFullScreenIntent(fullScreen, true)
        }

        notifySafely(c, NotificationIds.remind(event.id), builder.build())

        // 记一笔"这条已经响过"，供补发逻辑判断。
        // 放在这里而不是 [ReminderScheduler]，是因为所有提醒最终都从这一个出口发出。
        runCatching {
            Prefs.remindedKeys = Prefs.remindedKeys + ReminderScheduler.remindedKey(event)
        }
        markPosted(event.id)
    }

    /**
     * 把一条还在通知栏里的提醒降级成普通通知：不再响、不再弹全屏。
     *
     * 为什么需要这一步：全屏意图是**跟着通知**挂着的，通知一直在栏里，
     * 系统每次判定"该弹"就会再弹一次。实测（Android 9）：提醒发出五分钟、
     * 用户刚在应用里看过，一按电源键息屏，那张提醒页又糊上来一次；
     * 同时有两条待处理提醒时，它们还会互相抢页面。
     *
     * 正确的语义是"一次提醒只打断一次"，所以页面显示之后立刻降级。
     */
    fun demoteRemind(c: Context, event: Event) {
        if (!hasPermission(c)) return
        val body = TimeFmt.summary(event.startAt, event.hasTime, event.location)
        val n = NotificationCompat.Builder(c, CH_REMIND)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(event.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(c, event.id.toInt()))
            .addAction(0, "知道了", actionIntent(c, event.id, NotifyAction.ACTION_DONE, 3))
            .addAction(0, "稍后 10 分钟", actionIntent(c, event.id, NotifyAction.ACTION_SNOOZE, 4))
            .setTimeoutAfter(60 * 60 * 1000L)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreenSafe(c, "有一项日程到点了"))
            .build()
        notifySafely(c, NotificationIds.remind(event.id), n)

        // 记一笔"这条已经响过"，供补发逻辑判断。
        // 放在这里而不是 [ReminderScheduler]，是因为所有提醒最终都从这一个出口发出。
        runCatching {
            Prefs.remindedKeys = Prefs.remindedKeys + ReminderScheduler.remindedKey(event)
        }
        markPosted(event.id)
    }

    /**
     * 记一笔"这条提醒挂在栏里"。
     *
     * 记账而不是回看系统，是因为 `getActiveNotifications()` 在部分设备上返回不全
     * （详见 [Prefs.postedReminds]）。清扫靠这份账，才能把已经删掉的日程的提醒立刻撤掉，
     * 而不是等一小时的 `setTimeoutAfter`。
     */
    private fun markPosted(eventId: Long) {
        runCatching { Prefs.postedReminds = Prefs.postedReminds + eventId }
    }

    /** 到点提醒被处理掉（点了知道了/稍后）之后撤掉 */
    fun clearRemind(c: Context, eventId: Long) {
        runCatching { NotificationManagerCompat.from(c).cancel(NotificationIds.remind(eventId)) }
        runCatching { Prefs.postedReminds = Prefs.postedReminds - eventId }
    }

    /**
     * 地点变了，把**还挂在通知栏里**的到点提醒同步成新地址。
     *
     * 只更新、不重发：
     * - 用户已经划掉的提醒不复活（划掉 = 处理过了，再弹一次就是打扰）；
     * - 还没到点的提醒没有通知可更新，下次 [postRemind] 自然用新地点；
     * - `setSilent` + `setOnlyAlertOnce`：这是一次内容更正，不是第二次提醒；
     * - **不挂全屏意图**：这条已经响过一次，不能因为改了个地址再打断用户一次。
     */
    fun refreshRemind(c: Context, event: Event) {
        if (!hasPermission(c)) return

        // getActiveNotifications 只能看到本应用自己的，够用；
        // 个别 ROM 返回不全时最多是"这次没刷新"，不会误伤别的通知
        val showing = runCatching {
            NotificationManagerCompat.from(c).activeNotifications.any {
                it.id == NotificationIds.remind(event.id)
            }
        }.getOrDefault(false)
        if (!showing) return

        val style = RemindStyle.resolve(event.remindStyle, Prefs.remindStyle)
        val channel = when {
            style.quiet -> CH_REMIND_QUIET
            style.vibrateOnly -> CH_REMIND_VIBRATE
            else -> CH_REMIND
        }
        val body = TimeFmt.summary(event.startAt, event.hasTime, event.location)

        val n = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(event.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(
                when {
                    style.quiet -> NotificationCompat.PRIORITY_LOW
                    style.vibrateOnly -> NotificationCompat.PRIORITY_HIGH
                    else -> NotificationCompat.PRIORITY_MAX
                }
            )
            .setCategory(
                if (style.quiet || style.vibrateOnly) NotificationCompat.CATEGORY_REMINDER
                else NotificationCompat.CATEGORY_ALARM
            )
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(c, event.id.toInt()))
            .addAction(0, "知道了", actionIntent(c, event.id, NotifyAction.ACTION_DONE, 3))
            .addAction(0, "稍后 10 分钟", actionIntent(c, event.id, NotifyAction.ACTION_SNOOZE, 4))
            .setTimeoutAfter(60 * 60 * 1000L)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreenSafe(c, "有一项日程到点了"))
            .build()
        notifySafely(c, NotificationIds.remind(event.id), n)
    }

    /**
     * 自动建日程后的回执。
     *
     * **所有回执共用一条通知**，只更新内容。原来是一条日程一个通知 id，
     * 群里安静一点的一天也要扔好几条进去；被系统的通知限流当成刷屏之后，
     * 连到点提醒都被一起静音（模拟器实测 `muted=149/150`）。
     * 回执本身只是"顺手知道一下"，不值得占那么多槽位。
     */
    fun postCaptured(c: Context, event: Event) {
        if (!hasPermission(c)) return
        val body = TimeFmt.summary(event.startAt, event.hasTime, event.location)
        val n = NotificationCompat.Builder(c, CH_CAPTURE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("已记录：${event.title}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            // 反复更新同一条时不要每次都响一遍
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(c, event.id.toInt()))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreenSafe(c, "已自动记录一条日程"))
            .build()
        notifySafely(c, NotificationIds.CAPTURE_SUMMARY_ID, n)
    }

    /**
     * 识别出来但没把握时的提示。
     * 刻意不说"已记录"——它还没算数，等用户确认。
     *
     * 带两个按钮，**不用打开应用就能处理**：
     *   「确认」→ 转正 + 排提醒
     *   「删除」→ 直接丢掉
     * 这条通知不自动消失（`autoCancel = false`）：它是一条待办，
     * 处理掉才会消失；点内容进应用也不该让它凭空不见。
     */
    fun postPending(c: Context, event: Event) {
        if (!hasPermission(c)) return
        val body = buildString {
            append(TimeFmt.summary(event.startAt, event.hasTime, event.location))
            event.pendingReason?.let { append("\n").append(it) }
        }
        val n = NotificationCompat.Builder(c, CH_INFO)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("需要确认：${event.title}")
            .setContentText(TimeFmt.summary(event.startAt, event.hasTime, event.location))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(false)
            .setContentIntent(openAppIntent(c, event.id.toInt() + 20_000))
            .addAction(0, "确认", actionIntent(c, event.id, NotifyAction.ACTION_CONFIRM, 1))
            .addAction(0, "删除", actionIntent(c, event.id, NotifyAction.ACTION_DELETE, 2))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreenSafe(c, "有一条日程需要确认"))
            .build()
        notifySafely(c, pendingNotiId(event.id), n)
    }

    /**
     * 待确认通知的通知 id。算法在 [NotificationIds] 里，那边有单测 ——
     * 发送和清除必须用同一个，否则「点了确认但通知还在」。
     */
    fun pendingNotiId(eventId: Long): Int = NotificationIds.pending(eventId)

    /** 撤掉某条日程的待确认通知 */
    fun clearPending(c: Context, eventId: Long) {
        runCatching { NotificationManagerCompat.from(c).cancel(pendingNotiId(eventId)) }
    }

    /**
     * 「这句改期没认准，点开告诉我是哪一条」。
     *
     * 只在两种情况下发：
     * 1. 新时间有了，但时间窗里有不止一条（或者一条都对不上）；
     * 2. 原文写了「原定…」，但新时间压根没解析出来。
     *
     * 信息完整时在通知监听里就直接改了，不会走到这里 —— 这是一次询问，
     * 不是一条待办，所以 autoCancel：点开就消失，别在栏里越积越多。
     *
     * 正文与正文标题都可能带日程内容，锁屏用脱敏版兜住。
     */
    fun postReviseAsk(
        c: Context,
        text: String,
        oldStartAt: Long?,
        newStartAt: Long?,
        newEndAt: Long,
        newHasTime: Boolean,
        meridiemGuessed: Boolean,
        newLocation: String? = null
    ) {
        if (!hasPermission(c)) return
        val body = when {
            newStartAt != null ->
                "改到 ${TimeFmt.rangeSummary(newStartAt, newEndAt, newHasTime, newLocation)}？" +
                    "点开确认改的是哪一条"
            newLocation != null ->
                "换到 $newLocation？点开确认改的是哪一条"
            else -> "通知里没说清新时间，点开选一条手动改"
        }
        val intent = Intent(c, com.terry.smartschedule.ui.MainActivity::class.java).apply {
            action = NotifyAction.ACTION_REVISE_CONFIRM
            // CLEAR_TOP + SINGLE_TOP：应用已经开着（哪怕正在设置页）时回到主界面并走
            // onNewIntent，而不是再叠一个 MainActivity 实例。
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NotifyAction.EXTRA_REVISE_TEXT, text.take(200))
            putExtra(NotifyAction.EXTRA_REVISE_NEW_START, newStartAt ?: -1L)
            putExtra(NotifyAction.EXTRA_REVISE_NEW_END, newEndAt)
            putExtra(NotifyAction.EXTRA_REVISE_NEW_HAS_TIME, newHasTime)
            putExtra(NotifyAction.EXTRA_REVISE_OLD_START, oldStartAt ?: -1L)
            putExtra(NotifyAction.EXTRA_REVISE_MERIDIEM, meridiemGuessed)
            if (newLocation != null) {
                putExtra(NotifyAction.EXTRA_REVISE_NEW_LOCATION, newLocation)
            }
        }
        val pi = PendingIntent.getActivity(
            c,
            NotificationIds.REVISE_ASK_REQUEST,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(c, CH_INFO)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("有一条改期需要确认")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            // 固定 id + 只响一次：群通知重渲染 / 服务重启重放时，同一条询问
            // 不应该反复出声，内容更新就够了
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreenSafe(c, "有一条改期需要你确认"))
            .build()
        notifySafely(c, NotificationIds.REVISE_ASK_ID, n)
    }

    /** 用户点开处理了，或者已经手动改过，撤掉这条询问 */
    fun clearReviseAsk(c: Context) {
        runCatching { NotificationManagerCompat.from(c).cancel(NotificationIds.REVISE_ASK_ID) }
    }

    /**
     * 通知按钮的 PendingIntent。
     *
     * `requestCode` 必须按「日程 id + 动作」唯一。全用同一个 requestCode 的话，
     * 系统会把多条日程的按钮当成同一个 PendingIntent —— 点哪条都是第一条的 id，
     * 而且从通知栏完全看不出问题，只有用户发现"确认了 A 结果 B 变了"才会暴露。
     */
    private fun actionIntent(c: Context, eventId: Long, action: String, slot: Int): PendingIntent {
        val intent = Intent(c, PendingActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotifyAction.EXTRA_EVENT_ID, eventId)
        }
        return PendingIntent.getBroadcast(
            c,
            NotificationIds.actionRequestCode(eventId, slot),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 权限检查通过后仍可能被系统拒绝（用户在设置里刚撤回），
     * 所以真正发通知这一步再兜一层 SecurityException。
     */
    private fun notifySafely(c: Context, id: Int, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(c).notify(id, n)
        } catch (e: SecurityException) {
            // 没有通知权限，静默失败即可，不影响日程本身入库
        }
    }

    /**
     * 锁屏上显示的"脱敏版"通知：只说有这么件事，不说是什么事。
     * 日程标题往往就是隐私（"复诊""面试""交房租"），不该放在锁屏给旁人看。
     */
    private fun lockScreenSafe(c: Context, text: String): android.app.Notification =
        NotificationCompat.Builder(c, CH_INFO)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("智能日程")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun openAppIntent(c: Context, code: Int): PendingIntent {
        val intent = Intent(c, com.terry.smartschedule.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            c, code, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 全屏提醒页的 PendingIntent。
     *
     * 单独一个 Activity 而不是直接开 MainActivity：全屏提醒要能亮屏、能盖在锁屏上、
     * 一眼看完就关掉。把整个应用呼起来太重，而且用户从锁屏点进应用再找那条日程，
     * 中间多出来的这两步正是"太无感"的来源。
     */
    private fun fullScreenIntent(c: Context, eventId: Long): PendingIntent {
        val intent = Intent(c, ReminderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ReminderActivity.EXTRA_EVENT_ID, eventId)
        }
        return PendingIntent.getActivity(
            c,
            NotificationIds.actionRequestCode(eventId, 5),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
