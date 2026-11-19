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

/**
 * 通知栏按钮的处理端。
 *
 * 存在的意义：待确认的日程如果只能点开 App 才能确认，用户看到通知还得专门进去一趟。
 * 这里把「确认」和「删除」做成通知按钮，一次点击就落地，不用打开应用。
 *
 * 实现上要注意两点：
 * 1. `onReceive` 跑在主线程且必须很快返回，所以用 `goAsync()` + 协程做 IO。
 *    忘了 `finish()` 会让系统等满超时（约 10 秒）再回收这个广播。
 * 2. 这个 receiver 是 `exported=false`，靠 `PendingIntent` 由系统代理投递，
 *    外部应用发不进来。
 */
class PendingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(NotifyAction.EXTRA_EVENT_ID, -1L)
        val action = intent.action ?: return
        if (id <= 0) return

        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 进程可能是被这个广播单独拉起的，静态初始化不一定跑过
                Prefs.init(app)
                Repo.init(app)
                CaptureLog.init(app)
                when (action) {
                    NotifyAction.ACTION_CONFIRM -> confirm(app, id)
                    NotifyAction.ACTION_DELETE -> remove(app, id)
                    NotifyAction.ACTION_DONE -> markDone(app, id)
                    NotifyAction.ACTION_SNOOZE -> snooze(app, id)
                }
            } catch (t: Throwable) {
                // 通知栏点一下不该把进程搞崩
                runCatching {
                    CaptureLog.record(
                        "通知栏", "", CaptureRecord.OUT_SKIPPED,
                        "处理失败：${t::class.java.simpleName}"
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** 确认：转正 + 排提醒 + 撤掉待确认通知 */
    private suspend fun confirm(c: Context, id: Long) {
        val e = Repo.findById(id) ?: return
        Repo.confirmPending(id)
        val confirmed = e.copy(pending = false, pendingReason = null)
        ReminderScheduler.schedule(c, confirmed)
        Notifications.clearPending(c, id)
        CaptureLog.record("通知栏", "", CaptureRecord.OUT_CREATED, "已在通知栏确认：${e.title}")
        broadcastChanged(c)
    }

    /**
     * 「知道了」：标记完成。
     *
     * 不删记录 —— 用户说的是"这件事我知道了"，不是"当它没发生"。
     * 列表里保留一条打勾的，比凭空消失更符合直觉（也是他事后核对"我到底办了没"的依据）。
     */
    private suspend fun markDone(c: Context, id: Long) {
        val e = Repo.findById(id) ?: return
        if (e.done) {
            Notifications.clearRemind(c, id)
            return
        }
        Repo.update(e.copy(done = true))
        // 完成了就不该再响
        ReminderScheduler.cancel(c, id)
        Notifications.clearRemind(c, id)
        CaptureLog.record("通知栏", "", CaptureRecord.OUT_SKIPPED, "已在通知栏标记完成：${e.title}")
        broadcastChanged(c)
    }

    /** 「稍后 10 分钟」：撤掉当前这条，十分钟后重新响 */
    private suspend fun snooze(c: Context, id: Long) {
        val e = Repo.findById(id) ?: return
        Notifications.clearRemind(c, id)
        ReminderScheduler.snooze(c, id, 10)
        CaptureLog.record(
            "通知栏", "", CaptureRecord.OUT_SKIPPED,
            "已推迟 10 分钟：${e.title}"
        )
    }

    /** 删除：连提醒一起撤 */
    private suspend fun remove(c: Context, id: Long) {
        val e = Repo.findById(id)
        Repo.delete(id)
        ReminderScheduler.cancel(c, id)
        Notifications.clearPending(c, id)
        CaptureLog.record(
            "通知栏", "", CaptureRecord.OUT_SKIPPED,
            "已在通知栏删除：${e?.title ?: "#$id"}"
        )
        broadcastChanged(c)
    }

    /** 告诉正在前台的应用刷新列表；不在前台就没人收，不影响 */
    private fun broadcastChanged(c: Context) {
        runCatching {
            c.sendBroadcast(
                Intent(NotifyAction.ACTION_DATA_CHANGED).setPackage(c.packageName)
            )
        }
    }
}
