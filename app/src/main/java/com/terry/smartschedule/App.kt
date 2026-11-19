package com.terry.smartschedule

import android.app.Application
import com.terry.smartschedule.data.Repo
import com.terry.smartschedule.notify.CaptureLog
import com.terry.smartschedule.notify.Notifications
import com.terry.smartschedule.notify.ServiceHealth
import com.terry.smartschedule.parse.Prefs
import com.terry.smartschedule.util.ScreenGuard

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 切到后台时禁止截屏，回前台放开（见 ScreenGuard 里的说明）
        ScreenGuard.install(this)
        Prefs.init(this)
        Repo.init(this)
        Notifications.ensureChannels(this)
        CaptureLog.init(this)
        // 上次开着保活就把前台服务拉起来。
        // 部分 ROM 禁止后台启动前台服务，失败也无所谓——下次进应用时会再试一次。
        if (Prefs.captureEnabled && Prefs.keepAlive) {
            runCatching { ServiceHealth.startKeepAlive(this) }
        }
    }
}
