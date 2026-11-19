package com.terry.smartschedule.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.terry.smartschedule.parse.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 重启后 AlarmManager 里的闹钟全部丢失，这里补排一次；顺手把保活服务拉起来 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Prefs.init(context)
        // 开机自启的窗口期允许启动前台服务，别浪费
        if (Prefs.captureEnabled && Prefs.keepAlive) {
            ServiceHealth.startKeepAlive(context)
        }

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReminderScheduler.rescheduleAll(appContext)
                // 关机期间错过的那几条，开机补上
                ReminderScheduler.catchUp(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
