package com.terry.smartschedule.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager

/**
 * 切到后台时禁止截屏，回到前台再放开。
 *
 * 为什么是「后台才开」而不是「全程开」：
 * FLAG_SECURE 是全局的、一刀切的 —— 打上之后连用户自己按电源键截图得到的也是黑屏。
 * 而「把日程截图发给同学 / 发群里」是这个应用里很自然的动作，全程开会把它直接禁掉。
 *
 * 所以只在前台退出的那一刻上锁：
 *   - 在前台     → 能正常截图、录屏、分享
 *   - 切到后台   → 最近任务里的缩略图变空白，别人拿起手机翻不到你的日程
 *
 * 挂在 Application 的 ActivityLifecycleCallbacks 上统一处理，而不是每个 Activity 里
 * 各写一遍 onPause / onResume，是为了以后新增页面不会漏掉 —— 漏一个页面就等于没做。
 */
object ScreenGuard {

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityPaused(activity: Activity) {
                // 失去前台焦点就上锁。addFlags 是幂等的，重复调用没有副作用。
                // 时机很关键：必须在这一刻打上。最近任务的缩略图是在窗口不可见之后
                // 才取缓存的，放到 onStop 甚至之后就已经拍完存下来了。
                runCatching {
                    activity.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            override fun onActivityResumed(activity: Activity) {
                // 回到前台解锁，用户自己的截图、录屏、屏幕共享都正常
                runCatching {
                    activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
