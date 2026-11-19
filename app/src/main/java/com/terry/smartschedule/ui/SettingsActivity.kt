package com.terry.smartschedule.ui

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.terry.smartschedule.R
import com.terry.smartschedule.data.Repo
import com.terry.smartschedule.databinding.ActivitySettingsBinding
import com.terry.smartschedule.notify.CaptureLog
import com.terry.smartschedule.notify.ListenerStatus
import com.terry.smartschedule.notify.NotificationCaptureService
import com.terry.smartschedule.notify.Notifications
import com.terry.smartschedule.notify.RebindResult
import com.terry.smartschedule.notify.ReminderScheduler
import com.terry.smartschedule.notify.SeenStore
import com.terry.smartschedule.notify.ServiceHealth
import com.terry.smartschedule.parse.Prefs
import com.terry.smartschedule.sync.CalendarSync
import com.terry.smartschedule.notify.RemindStyle
import com.terry.smartschedule.util.Insets
import com.terry.smartschedule.util.TimeFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    /** 程序化改开关状态时置位，避免触发监听里的"用户操作"分支（会互相递归） */
    private var calUiBusy = false

    /**
     * 日历权限。
     *
     * 一次要 READ + WRITE 两个：只读也能查日历，但没法写；只写在某些 ROM 上会被拒。
     * 授权后立刻补推存量日程 —— 用户刚打开开关，手上那些日程就该出现在日历里，
     * 让他再去找一个"立即同步"按钮是多余的一步。
     */
    private val calPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val ok = grants.isNotEmpty() && grants.values.all { it }
        setCalSwitch(ok)
        Prefs.calendarSync = ok
        if (ok) {
            syncCalendar(announce = true)
        } else {
            toast("没有日历权限，同步没开。可以到系统设置里给本应用开放日历权限后再试")
        }
        updateCalendarState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.edgeToEdge(this)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.apply(
            this, b.root,
            statusBarSpacer = b.statusBarSpace,
            bottomTarget = b.scroll,
            bottomExtraDp = 16
        )

        b.btnBack.setOnClickListener { finish() }

        // 载入当前设置
        b.swCapture.isChecked = Prefs.captureEnabled
        b.swKeepAlive.isChecked = Prefs.keepAlive
        b.swPending.isChecked = Prefs.pendingWhenUnsure
        b.swCalendar.isChecked = Prefs.calendarSync
        updateCalendarState()
        b.etRemind.setText(Prefs.defaultRemind.toString())

        // 提醒方式：四档单选。**选了就立刻生效**，和这一页其他开关一致 ——
        // 用户改这个的动机往往是"刚才被弹烦了"，让他再去按一次保存很不合理。
        b.tgRemindStyle.check(styleButtonId(Prefs.remindStyle))
        updateStyleDesc(Prefs.remindStyle)
        b.tgRemindStyle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val v = styleValueOf(checkedId)
            Prefs.remindStyle = v
            updateStyleDesc(v)
        }
        b.swLogRaw.isChecked = Prefs.logRawText
        b.swVerbose.isChecked = Prefs.captureVerbose

        // 开关立刻生效，不用等"保存"——这些开关用户期待马上起作用
        b.swCapture.setOnCheckedChangeListener { _, checked ->
            Prefs.captureEnabled = checked
            if (checked) {
                if (!isListenerGranted()) {
                    promptGrant()
                } else {
                    if (Prefs.keepAlive) ServiceHealth.startKeepAlive(this)
                }
            } else {
                ServiceHealth.stopKeepAlive(this)
            }
            updateListenerState()
        }

        b.swKeepAlive.setOnCheckedChangeListener { _, checked ->
            Prefs.keepAlive = checked
            if (checked && Prefs.captureEnabled && isListenerGranted()) {
                ServiceHealth.startKeepAlive(this)
                toast("已开启后台常驻，通知栏会出现一条提示")
            } else {
                ServiceHealth.stopKeepAlive(this)
            }
        }

        b.swPending.setOnCheckedChangeListener { _, checked ->
            Prefs.pendingWhenUnsure = checked
        }

        b.swCalendar.setOnCheckedChangeListener { _, checked ->
            if (calUiBusy) return@setOnCheckedChangeListener
            if (checked) {
                if (CalendarSync.hasPermission(this)) {
                    Prefs.calendarSync = true
                    updateCalendarState()
                    syncCalendar(announce = true)
                } else {
                    calPermLauncher.launch(CalendarSync.PERMISSIONS)
                }
            } else {
                // 只是停止后续同步，**不删**已经同步到日历里的条目：
                // 那些已经进入用户的日历视图，可能已经被他当成自己的日程在用了。
                Prefs.calendarSync = false
                updateCalendarState()
                toast("已停止同步，日历里已有的条目保留")
            }
        }

        b.btnCalendarSync.setOnClickListener { syncCalendar(announce = true) }

        b.swLogRaw.setOnCheckedChangeListener { _, checked ->
            Prefs.logRawText = checked
        }

        b.swVerbose.setOnCheckedChangeListener { _, checked ->
            Prefs.captureVerbose = checked
        }

        b.btnWipe.setOnClickListener { confirmWipe() }

        b.btnListener.setOnClickListener {
            when (ServiceHealth.status(this)) {
                ListenerStatus.OFF -> toast("先打开上面的「监听通知并自动建日程」")
                ListenerStatus.NO_PERMISSION -> openListenerSettings()
                ListenerStatus.RUNNING -> toast("通知监听正常，不用处理")
                ListenerStatus.CONNECTING -> startReconnect()
            }
        }
        b.btnListenerSettings.setOnClickListener { openListenerSettings() }
        b.btnBattery.setOnClickListener {
            if (ServiceHealth.isIgnoringBatteryOptimizations(this)) {
                toast("已经在白名单里了，系统不会限制后台")
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("允许后台常驻")
                    .setMessage(
                        "国产 ROM 上，应用不在电池优化白名单里就很容易被冻结。\n\n" +
                            "接下来会跳到系统页面，选择「允许」或「不限制」。"
                    )
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton("去设置") { _, _ ->
                        ServiceHealth.requestIgnoreBatteryOptimizations(this)
                    }
                    .show()
            }
        }
        b.btnAutoStart.setOnClickListener {
            if (!ServiceHealth.openAutoStartSettings(this)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("手动设置自启动")
                    .setMessage(
                        "你的系统没有通用的自启动设置页，请按机型找：\n\n" +
                            "· 小米：「设置 → 应用设置 → 应用管理 → 智能日程 → 自启动」\n" +
                            "· 华为：「设置 → 应用 → 应用启动管理 → 智能日程 → 手动管理」\n" +
                            "· OPPO/一加：「设置 → 电池 → 应用耗电管理 → 智能日程 → 允许后台运行」\n" +
                            "· vivo：「设置 → 电池 → 后台高耗电 → 允许智能日程」\n\n" +
                            "找不到就先用「允许后台常驻」，效果差不多。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
        b.btnLog.setOnClickListener {
            startActivity(Intent(this, CaptureLogActivity::class.java))
        }
        b.btnScan.setOnClickListener {
            // 手动把通知栏里已有的通知过一遍。不用干等新通知，是验证管道通不通最快的办法。
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    NotificationCaptureService.scanActive()
                }
                when {
                    // 拿不到 activeNotifications —— 服务没连上，不是"没有通知"
                    r == null -> {
                        ServiceHealth.requestRebind(this@SettingsActivity)
                        toast("还拿不到通知列表，已请求重连。等状态变成「通知监听正常」再点一次")
                        ui.removeCallbacks(refresh)
                        ui.postDelayed(refresh, 1500)
                    }
                    r.total == 0 -> toast("通知栏里现在没有通知。可以先让微信来一条带时间的消息")
                    // 有通知但都不是微信/QQ —— 这是当前过滤策略的正常结果，得说清楚，
                    // 否则用户会以为自己的通知被漏掉了
                    r.watched == 0 -> toast(
                        "通知栏有 ${r.total} 条通知，但没有微信/QQ 的。" +
                            "当前只分析微信和 QQ 的通知"
                    )
                    else -> {
                        toast("扫了 ${r.watched} 条微信/QQ 通知，打开处理日志看结果")
                        startActivity(Intent(this@SettingsActivity, CaptureLogActivity::class.java))
                    }
                }
            }
        }
        b.btnAlarm.setOnClickListener { openExactAlarmSettings() }
        b.btnSave.setOnClickListener { saveAndFinish() }

        b.tvTip.text = "解析全部在本机完成：本地规则逐条匹配日期、时间、地点。" +
            "应用没有申请网络权限，通知原文不可能离开这台设备。\n\n" +
            "通知监听只处理微信/QQ 的通知，也只读得到通知栏里显示的文本 —— " +
            "读不到聊天记录里的历史消息，读不到别的应用的内容。\n\n" +
            "如果开了监听却什么都没发生：先点「立即检测当前通知栏」，再看「处理日志」里的记录。" +
            "日志只记被跳过的原因、不记正文；打开「诊断模式」后连不相关的通知也会记一条，" +
            "用来确认服务到底有没有收到东西。"
    }

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    /** 重连请求已发出、正在等系统响应。期间禁止再点 —— 连点会打断系统绑定 */
    private var reconnecting = false

    /** 只观察、不发请求的轮询计数（从系统设置回来时用） */
    private var watching = false
    private var watchTicks = 0
    private var reconnectTicks = 0

    /** 观察轮询。写成 object 是因为它要 postDelayed 自己，lambda 推不出类型 */
    private val refresh = object : Runnable {
        override fun run() {
            updateListenerState()
            val running = ServiceHealth.status(this@SettingsActivity) == ListenerStatus.RUNNING
            if (running || ++watchTicks >= 6) {
                watching = false
            } else {
                ui.postDelayed(this, 800)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateListenerState()
        // 从系统授权页 / 应用外回来时，系统可能**正在绑定**。
        // 这里只观察、不再发请求 —— 多发一次反而会打断它，
        // 这也是"关开授权后还要再点几次"的原因之一。
        if (ServiceHealth.status(this) == ListenerStatus.CONNECTING) startWatch()
    }

    override fun onDestroy() {
        ui.removeCallbacks(refresh)
        super.onDestroy()
    }

    /**
     * 状态写得具体一点：
     * 「授权了但服务没起来」和「根本没授权」是两种完全不同的故障，
     * 混成一句"未授权"会让人白折腾。
     */
    private fun updateListenerState() {
        val st = ServiceHealth.status(this)
        val batteryOk = ServiceHealth.isIgnoringBatteryOptimizations(this)
        val lastAt = ServiceHealth.lastEventAt

        val sb = StringBuilder()
        sb.append(
            when (st) {
                ListenerStatus.OFF -> "通知监听：已关闭（上面的总开关没开）"
                ListenerStatus.NO_PERMISSION -> "通知使用权：未授予"
                ListenerStatus.RUNNING -> "通知使用权：已授予，服务正在运行"
                ListenerStatus.CONNECTING -> buildString {
                    append("通知使用权：已授予，但服务还没连上。\n")
                    if (ServiceHealth.rebindCount > 0) {
                        append("已请求重连 ${ServiceHealth.rebindCount} 次。")
                    }
                    append("点一次「重新连接」后给系统 4 秒，别连点 —— ")
                    append("连点会打断系统正在进行的绑定，反而越点越连不上。")
                }
            }
        )
        sb.append("\n电池优化白名单：").append(if (batteryOk) "已加入" else "未加入，后台容易被冻结")
        if (lastAt > 0) {
            sb.append("\n最近收到通知：").append(TimeFmt.full(lastAt))
        } else if (st == ListenerStatus.RUNNING) {
            sb.append("\n本次运行还没收到过任何通知")
        }

        b.tvListenerState.text = sb.toString()
        b.tvListenerState.setTextColor(
            getColor(if (st == ListenerStatus.RUNNING) R.color.brand_dark else R.color.warn)
        )

        if (reconnecting) {
            // 等系统响应期间不能再点：连点会打断绑定
            b.btnListener.isEnabled = false
            b.btnListener.text = "正在连接…"
        } else {
            b.btnListener.isEnabled = st != ListenerStatus.RUNNING
            b.btnListener.text = when (st) {
                ListenerStatus.NO_PERMISSION -> getString(R.string.settings_grant_listener)
                ListenerStatus.RUNNING -> "通知监听正常"
                ListenerStatus.OFF -> "通知监听已关闭"
                ListenerStatus.CONNECTING -> "重新连接监听服务"
            }
        }

        b.btnBattery.text = if (batteryOk) {
            "已在电池优化白名单"
        } else {
            getString(R.string.settings_battery)
        }
    }

    private fun isListenerGranted(): Boolean = ServiceHealth.isGranted(this)

    private fun promptGrant() {
        MaterialAlertDialogBuilder(this)
            .setTitle("还差一步授权")
            .setMessage("监听通知需要系统授权。在接下来的列表里找到「智能日程」，打开「允许通知使用权」。")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("去授权") { _, _ -> openListenerSettings() }
            .show()
    }

    /**
     * 打开系统的「通知使用权」页面。
     *
     * Android 11+ 可以直接跳到本应用的**详情页**，省得用户在二三十个应用里翻找；
     * 老版本和不支持该 action 的 ROM 退回到列表页。
     */
    private fun openListenerSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this, NotificationCaptureService::class.java).flattenToString()
                )
            if (runCatching { startActivity(detail) }.isSuccess) return
        }
        val ok = runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.isSuccess
        if (!ok) {
            toast("打不开系统设置，请手动到 设置 → 通知 → 通知使用权 里授权")
        }
    }

    /**
     * 重连：**只发一次请求，然后等系统**。
     *
     * 之前是点一次发一次，用户连点就会一遍遍打断系统正在进行的绑定 ——
     * 表现成"有时候点一两次就行、有时候点很多次反而不行"。
     * 现在等的那几秒按钮置灰，最多等 4.5 秒，还不行就引导走系统设置重置授权。
     */
    private fun startReconnect() {
        if (reconnecting) return
        reconnecting = true
        reconnectTicks = 0
        ui.removeCallbacks(refresh)
        ui.removeCallbacks(reconnectPoll)

        val r = ServiceHealth.requestRebind(this, force = true)
        updateListenerState()
        if (r == RebindResult.FAILED) {
            reconnecting = false
            updateListenerState()
            showRebindFailedDialog()
            return
        }
        toast("已请求重连，给系统 4 秒左右")

        ui.postDelayed(reconnectPoll, 900)
    }

    private val reconnectPoll = object : Runnable {
        override fun run() {
            updateListenerState()
            if (ServiceHealth.status(this@SettingsActivity) == ListenerStatus.RUNNING) {
                reconnecting = false
                updateListenerState()
                toast("已连接")
                return
            }
            reconnectTicks++
            if (reconnectTicks < 5) {
                ui.postDelayed(this, 900)
            } else {
                reconnecting = false
                updateListenerState()
                showRebindFailedDialog()
            }
        }
    }

    /** 只观察不发请求 */
    private fun startWatch() {
        if (watching || reconnecting) return
        watching = true
        watchTicks = 0
        ui.removeCallbacks(refresh)
        ui.postDelayed(refresh, 600)
    }

    /**
     * 重连没生效时的出路。
     * requestRebind 只是"请求"，系统可以不理；能强制重绑的只有关开授权。
     */
    private fun showRebindFailedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("系统的重连请求没有生效")
            .setMessage(
                "「重新连接」只是向系统发一个请求，系统可以选择不理会，在部分机型上时灵时不灵。\n\n" +
                    "真正能强制重绑的办法只有一个：打开系统的通知使用权页面，" +
                    "把「智能日程」的开关关掉，等一秒，再打开。\n\n" +
                    "要不要现在跳过去？"
            )
            .setNegativeButton("稍后", null)
            .setPositiveButton("去系统设置") { _, _ -> openListenerSettings() }
            .show()
    }

    /** 存下来的数字 -> 对应的按钮 id */
    private fun styleButtonId(v: Int): Int = when (RemindStyle.from(v)) {
        RemindStyle.FULLSCREEN -> R.id.btnStyleFull
        RemindStyle.BANNER -> R.id.btnStyleBanner
        RemindStyle.VIBRATE -> R.id.btnStyleVibrate
        RemindStyle.SILENT -> R.id.btnStyleSilent
    }

    /** 按钮 id -> 存下来的数字。认不出来的一律按全屏，别因为一个 id 把提醒弄哑 */
    private fun styleValueOf(buttonId: Int): Int = when (buttonId) {
        R.id.btnStyleBanner -> RemindStyle.BANNER.value
        R.id.btnStyleVibrate -> RemindStyle.VIBRATE.value
        R.id.btnStyleSilent -> RemindStyle.SILENT.value
        else -> RemindStyle.FULLSCREEN.value
    }

    /** 选中那档的效果说明。用户选"横幅"时最想知道的就是"会不会盖住游戏"。 */
    private fun updateStyleDesc(v: Int) {
        b.tvStyleDesc.setText(
            when (RemindStyle.from(v)) {
                RemindStyle.FULLSCREEN -> R.string.remind_style_desc_full
                RemindStyle.BANNER -> R.string.remind_style_desc_banner
                RemindStyle.VIBRATE -> R.string.remind_style_desc_vibrate
                RemindStyle.SILENT -> R.string.remind_style_desc_silent
            }
        )
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            toast("当前系统不需要额外授权")
            return
        }
        val am = getSystemService(AlarmManager::class.java)
        if (am != null && am.canScheduleExactAlarms()) {
            toast("已允许精确提醒")
            return
        }
        val ok = runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")
                )
            )
        }.isSuccess
        if (!ok) {
            toast("打不开授权页，请手动到 设置 → 应用 → 特殊权限 → 闹钟和提醒 里允许")
        }
    }

    /** 程序化设置开关状态，不触发监听 */
    private fun setCalSwitch(on: Boolean) {
        calUiBusy = true
        b.swCalendar.isChecked = on
        calUiBusy = false
    }

    /**
     * 补推存量日程。
     *
     * @param announce true 时把结果告诉用户（手动点按钮 / 刚授权）；
     *                 静默场景（进页面顺手补）不打扰
     */
    private fun syncCalendar(announce: Boolean) {
        if (!CalendarSync.hasPermission(this)) {
            if (announce) toast("先允许日历权限")
            return
        }
        lifecycleScope.launch {
            val n = runCatching { Repo.syncAllToCalendar() }.getOrDefault(0)
            updateCalendarState()
            if (announce) {
                toast(if (n > 0) "已同步 $n 条到系统日历" else "系统日历里已经是最新的")
            }
        }
    }

    /** 开关下方的说明：同步开没开、能不能写、有多少条已同步 */
    private fun updateCalendarState() {
        val on = Prefs.calendarSync
        b.btnCalendarSync.isEnabled = on && CalendarSync.hasPermission(this)
        b.tvCalendarDesc.text = when {
            !on -> getString(R.string.settings_calendar_desc_off)
            !CalendarSync.hasPermission(this) ->
                getString(R.string.settings_calendar_desc_no_perm)
            else -> getString(R.string.settings_calendar_desc_on)
        }
    }

    private fun saveAndFinish() {
        val remind = b.etRemind.text?.toString()?.trim()?.toIntOrNull() ?: 10
        Prefs.defaultRemind = remind.coerceIn(-1, 24 * 60)

        // 提醒方式已经在点选时存过了，这里再写一次是兜底：
        // 有些机型上把单选组恢复成上次状态时不会触发 checked 回调。
        Prefs.remindStyle = styleValueOf(b.tgRemindStyle.checkedButtonId)

        toast("已保存")
        finish()
    }

    /**
     * 删除所有本地数据。
     * 授权状态归系统管（在系统设置里），这里清不掉也不该偷偷改。
     */
    private fun confirmWipe() {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除所有本地数据？")
            .setMessage(
                "会删掉：\n" +
                    "· 全部日程\n" +
                    "· 通知抓取日志\n" +
                    "· 所有设置项（含系统日历同步开关）\n\n" +
                    "删除后无法恢复。系统里的「通知使用权」授权不受影响。\n" +
                    "如果开着系统日历同步，日历里由本应用建的那些条目也会一并删掉。"
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("全部删除") { _, _ ->
                lifecycleScope.launch {
                    runCatching {
                        // 先取出 id 撤提醒，再删库 —— 反了就查不到了
                        val ids = Repo.all().map { it.id }
                        ids.forEach { ReminderScheduler.cancel(this@SettingsActivity, it) }
                        // 待确认通知也要撤，否则数据删了通知栏还挂着"需要确认"
                        ids.forEach { Notifications.clearPending(this@SettingsActivity, it) }
                        // 通知栏里的到点提醒同样要撤。原来只撤了闹钟，于是"清空数据"
                        // 之后提醒还挂在通知栏里；而紧接着的 Prefs.clearAll() 会把
                        // 清扫用的账本一起清掉，兜底的那层也没法再救 —— 必须在这里撤。
                        ids.forEach { Notifications.clearRemind(this@SettingsActivity, it) }
                        Repo.deleteAll()
                        CaptureLog.clear()
                        // 判重台账也要清。不清的话，用户删完数据、通知栏里那条通知
                        // 又被系统回调一次，会被判成"处理过"直接跳过 —— 表现为"删了
                        // 之后再也抓不回来"。
                        SeenStore.clear()
                        Prefs.clearAll()
                    }
                    toast("已删除全部本地数据")
                    finish()
                }
            }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
