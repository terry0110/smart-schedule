package com.terry.smartschedule.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.terry.smartschedule.R
import com.terry.smartschedule.parse.Prefs
import com.terry.smartschedule.ui.MainActivity

/**
 * 前台保活服务。
 *
 * 说清楚一件事：Android 上**没有**真正的"永久保活"。应用能不能常驻，最终由系统内存策略
 * 和厂商 ROM 决定，不是靠代码能硬顶的。这个服务能做的是把进程优先级从"可随意回收"
 * 抬到"前台服务"，显著降低被杀的几率；再配合电池优化白名单 + 自启动权限，才接近"一直在跑"。
 *
 * 代价是通知栏有一条常驻提示，所以做成开关，用户能自己关。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 用户把开关关掉之后，服务仍可能被系统按 START_STICKY 重新拉起来。
        // 之前没检查这个，结果"关了开关，通知栏那条常驻提示还在"。
        if (!Prefs.keepAlive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val n = buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTI_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTI_ID, n)
        }
        ServiceHealth.keepAliveRunning = true
        // 被系统杀掉后尽量重拉
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceHealth.keepAliveRunning = false
        // 这里不放 stopSelf()：onDestroy 时已经要销毁了，调了也没用。
        // 真正拦"关了开关还在跑"的是上面 onStartCommand 里的开关检查。
    }

    companion object {
        const val CH_KEEP = "keep_alive"
        const val NOTI_ID = 1001

        fun ensureChannel(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            // LOW：不响不震，只在通知栏占一行
            val ch = NotificationChannel(
                CH_KEEP, "后台运行提示", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持通知监听，避免自动建日程失效"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }

        /**
         * 状态一变就刷一下通知栏。
         *
         * 不刷新的话会出这么个怪现象：进程一启动，App.onCreate 先把常驻服务拉起来，
         * 这时通知监听还没绑上，于是通知里永远写着"还没连上"；而几秒后服务其实连上了，
         * 主界面也跟着变成"正在监听"——**应用说连上了，通知栏说没连上**。
         * 真机上就是这么被发现的。
         */
        // 权限已经用 Notifications.hasPermission 查过了，runCatching 也兜着异常；
        // lint 只认内联的 checkSelfPermission，认不出这个封装，所以这里 suppress。
        @SuppressLint("MissingPermission")
        fun refresh(c: Context) {
            if (!ServiceHealth.keepAliveRunning) return
            // Android 13+ 没给通知权限时 notify() 会抛 SecurityException。
            // 前台服务那条是系统强制显示的，但"更新它"仍算普通通知。
            if (!Notifications.hasPermission(c)) return
            runCatching {
                NotificationManagerCompat.from(c).notify(NOTI_ID, buildNotification(c))
            }
        }

        fun buildNotification(c: Context): Notification {
            val pi = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            // 文案跟主界面、设置页用同一个状态源，避免三处对上不
            val (title, text) = when (ServiceHealth.status(c)) {
                ListenerStatus.RUNNING ->
                    "智汇日程 · 正在监听" to "识别到日程会自动记录"
                ListenerStatus.CONNECTING ->
                    "智汇日程 · 监听未连接" to "点这里重新连接"
                ListenerStatus.NO_PERMISSION ->
                    "智汇日程 · 缺少通知使用权" to "点这里去开启"
                ListenerStatus.OFF ->
                    "智汇日程 · 后台运行中" to "通知监听已关闭"
            }
            return NotificationCompat.Builder(c, CH_KEEP)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .build()
        }
    }
}
