package com.terry.smartschedule.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.terry.smartschedule.data.Repo
import com.terry.smartschedule.parse.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 闹钟响了：回查数据库，确认这条日程还在且未完成，再发提醒 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id <= 0) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 进程可能是被这条闹钟单独拉起来的，静态初始化不一定跑过。
                // 少了这一步，[Notifications.postRemind] 里记"已提醒"那步会静默失败，
                // 表现就是补发逻辑分不清响过没响过。
                Prefs.init(appContext)
                Repo.init(appContext)
                val event = Repo.findById(id)
                if (event != null && !event.done) {
                    Notifications.postRemind(appContext, event)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "event_id"
    }
}
