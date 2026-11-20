package com.terry.smartschedule.ui

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.terry.smartschedule.R
import com.terry.smartschedule.data.Repo
import com.terry.smartschedule.databinding.ActivityReminderBinding
import com.terry.smartschedule.notify.Notifications
import com.terry.smartschedule.notify.ReminderScheduler
import com.terry.smartschedule.util.TimeFmt
import kotlinx.coroutines.launch

/**
 * 到点的全屏提醒。
 *
 * 为什么要有这一页，而不是只发一条通知：
 *
 * 通知栏里的一条，对正在写代码、上课、或者刷视频的人是**不存在**的。
 * 之前的表现就是这样 —— 通知确实发了（模拟器上抓到了 NotificationRecord），
 * 但没声音、没震动、不弹窗，用户只有在主动翻通知栏或者打开应用时才看得到。
 * 那就不算提醒，只能算日志。
 *
 * 这一页承担三件事：
 *  1. 亮屏并盖在锁屏上（`setShowWhenLocked` / `setTurnScreenOn`）
 *  2. 一眼看清是什么事、几点、在哪
 *  3. 两个动作都能单手完成，处理完立刻消失
 *
 * 它由通知的 `fullScreenIntent` 拉起。系统要是拒绝弹（Android 14 起对全屏意图更严），
 * 通知本身还在，退化成横幅 + 响铃，不会两头落空。
 */
class ReminderActivity : AppCompatActivity() {

    private lateinit var b: ActivityReminderBinding

    /** 一进来就取到的日程；撤通知、标记完成都要用它的 id */
    private var eventId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityReminderBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 锁屏上也显示，并且直接把屏幕点亮。两个都要：只设前者会亮着屏但盖在锁屏下看不清，
        // 只设后者会在屏幕熄灭时啥也看不到。
        showOverLockScreen()

        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)

        // 先把能立刻显示的内容填上（标题在 intent 里没有，只能查库），
        // 让页面不是空白等着 —— 全屏提醒最怕"弹出来一片黑"。
        b.tvRemindTime.text = getString(R.string.remind_tag)
        b.tvRemindTime.textSize = 22f
        b.tvRemindDate.text = ""
        b.tvRemindTitle.text = "正在读取…"

        b.btnRemindDone.setOnClickListener { markDone() }
        b.btnRemindSnooze.setOnClickListener { snooze() }
        b.btnRemindOpen.setOnClickListener { openApp() }

        load()
    }

    /**
     * 页面还开着的时候又来了一条提醒。
     *
     * `singleTop` 会复用这个实例，**不处理 onNewIntent 就会一直显示上一条的内容** ——
     * 实测就是这样：页面上写着 21:05 的 TestC，而刚响的其实是 21:16 的评审会。
     * 时间地点全不对，比不弹还糟。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        if (newId <= 0 || newId == eventId) return
        eventId = newId
        load()
    }

    private fun load() {
        if (eventId <= 0) {
            // 没有 id（比如从最近任务里手动打开）就直接关掉，别留一个空壳
            finish()
            return
        }
        lifecycleScope.launch {
            val e = runCatching { Repo.findById(eventId) }.getOrNull()
            if (e == null) {
                finish()
                return@launch
            }
            // 这一页已经弹出来了，把通知降级：否则每次息屏系统都会再弹一次
            runCatching { Notifications.demoteRemind(this@ReminderActivity, e) }

            b.tvRemindTime.textSize = 72f
            b.tvRemindTime.text = if (e.hasTime) TimeFmt.time(e.startAt) else getString(R.string.remind_all_day)
            b.tvRemindDate.text = TimeFmt.dayHeader(TimeFmt.toDate(e.startAt))
            b.tvRemindTitle.text = e.title
            if (e.location.isNullOrBlank()) {
                b.tvRemindLocation.visibility = android.view.View.GONE
            } else {
                b.tvRemindLocation.visibility = android.view.View.VISIBLE
                b.tvRemindLocation.text = e.location
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // 锁屏状态下直接跳过锁屏界面：提醒不该要求用户先解锁才能看到是什么事
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    /**
     * 完成 = 归档：从主列表移到「归档日志」，需要时可以在归档页恢复。
     *
     * 注意顺序：**先干活，最后才 finish()**。
     *
     * 这里踩过一次：先 `finish()` 再 `lifecycleScope.launch`，Activity 销毁时
     * lifecycleScope 会被取消，于是"撤通知"这一步时有时无 —— 实测表现是点了
     * 「稍后 10 分钟」，闹钟确实排上了，但通知栏里那条还赖着不走。
     * 会不会丢取决于协程抢在销毁前跑了几行，这种偶发问题最难查。
     */
    private fun markDone() {
        val id = eventId
        if (id <= 0) {
            finish()
            return
        }
        lifecycleScope.launch {
            runCatching {
                Repo.archive(id)
                Notifications.clearRemind(this@ReminderActivity, id)
            }
            finish()
        }
    }

    private fun snooze() {
        val id = eventId
        if (id <= 0) {
            finish()
            return
        }
        lifecycleScope.launch {
            runCatching {
                Notifications.clearRemind(this@ReminderActivity, id)
                ReminderScheduler.snooze(this@ReminderActivity, id, 10)
            }
            finish()
        }
    }

    private fun openApp() {
        val id = eventId
        finish()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (id > 0) putExtra(EXTRA_FOCUS_EVENT, id)
            }
        )
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"

        /** 从提醒页跳进应用时要定位的那条日程 */
        const val EXTRA_FOCUS_EVENT = "focus_event_id"
    }
}
