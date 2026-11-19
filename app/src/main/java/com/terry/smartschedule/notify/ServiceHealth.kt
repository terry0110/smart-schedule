package com.terry.smartschedule.notify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.terry.smartschedule.parse.Prefs
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 通知监听服务的"真实"健康状态。
 *
 * 注意区分三件事，之前只有第一件是能查的，另外两件全靠用户猜：
 *  1. 有没有授权（NotificationManagerCompat 能查）
 *  2. 服务实例是不是真的活着（进程被杀/被 ROM 冻结时，授权还在但服务已经死了）
 *  3. 最近有没有真的收到过通知（判断"到底在不在工作"最直接）
 */
object ServiceHealth {

    /** 由 NotificationCaptureService 维护，进程活着时才有意义 */
    @Volatile
    var connected: Boolean = false
        internal set

    /** 最近一次成功收到并处理通知的时间戳，0 表示从没收到过 */
    @Volatile
    var lastEventAt: Long = 0L
        internal set

    /**
     * 最近一次**回调进来**的时间戳。
     *
     * 和 lastEventAt 的区别很关键：lastEventAt 要整条链路都成功（识别出来、落库）才更新，
     * 而通知大多不是日程，所以它长期是 0，用户看到的就是"从没收到过通知"，
     * 明明服务在正常工作。这个值是"服务确实在收通知"的唯一证据，只要回调进来就更新。
     */
    @Volatile
    var lastCallbackAt: Long = 0L
        internal set

    /** 前台保活服务当前是否在跑。主界面每次 onResume 都会尝试拉起，靠这个避免反复重建通知 */
    @Volatile
    var keepAliveRunning: Boolean = false
        internal set

    /** 有没有授予通知使用权 */
    fun isGranted(c: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    /**
     * 当前监听状态 —— **唯一判断源**。
     *
     * 主界面副标题、设置页文案、常驻通知全部走这里。三处各写一套 if-else 的话，
     * 迟早出现"应用说连上了、通知栏说没连上"，真机上就是这么被发现的。
     */
    fun status(c: Context): ListenerStatus = when {
        // Prefs 正常已 init，兜一层，别在启动阶段的极端时序里崩
        !runCatching { Prefs.captureEnabled }.getOrDefault(false) -> ListenerStatus.OFF
        !isGranted(c) -> ListenerStatus.NO_PERMISSION
        connected -> ListenerStatus.RUNNING
        else -> ListenerStatus.CONNECTING
    }

    /** 上一次主动请求重绑的时间 */
    @Volatile
    private var lastRebindAt: Long = 0L

    /** 本次运行请求过几次重绑。反复失败时要引导用户走系统设置那条路 */
    @Volatile
    var rebindCount: Int = 0
        private set

    /**
     * 主动要求系统重新绑定监听服务。
     *
     * 注意它只是**发一个请求**，系统可以不理。实测在部分 ROM 上：
     *   - 请求后给系统 1-3 秒，多半能连上
     *   - **连点会打断正在进行的绑定**，结果反而一直连不上
     * 所以调用方必须防重入：点一次之后就等着看，不要再发。
     *
     * @param force 用户主动点时传 true，绕过自动调用的节流窗口
     */
    fun requestRebind(c: Context, force: Boolean = false): RebindResult {
        val now = System.currentTimeMillis()
        if (!force && now - lastRebindAt < REBIND_MIN_INTERVAL_MS) return RebindResult.THROTTLED
        lastRebindAt = now
        rebindCount++
        return runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(c, NotificationCaptureService::class.java)
            )
            RebindResult.SENT
        }.getOrDefault(RebindResult.FAILED)
    }

    /**
     * 是否被系统列入了电池优化白名单。
     * 国产 ROM 上这一步比什么都管用——不在白名单里，服务随时会被冻。
     */
    fun isIgnoringBatteryOptimizations(c: Context): Boolean {
        // minSdk 26，不需要再判 M 以下
        val pm = c.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }

    /** 一键跳到"忽略电池优化"的申请页 */
    fun requestIgnoreBatteryOptimizations(c: Context) {
        runCatching {
            c.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${c.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            // 有些 ROM 把这个 action 阉了，退回到电池优化列表让用户自己找
            runCatching {
                c.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /**
     * 尝试跳各家 ROM 的"自启动 / 后台运行"管理页。
     * 这些页面没有标准 action，只能按包名硬试；跳不进去就返回 false，由调用方给出文字指引。
     */
    fun openAutoStartSettings(c: Context): Boolean {
        val candidates = listOf(
            // 小米 / 红米
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为 / 荣耀
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            // OPPO / 一加 / realme
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            // vivo / iQOO
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            // 三星
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            // 魅族
            "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity"
        )
        for ((pkg, cls) in candidates) {
            val ok = runCatching {
                c.startActivity(
                    Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /** 按需把前台保活服务拉起来；失败不抛（部分 ROM 会拦后台启动） */
    fun startKeepAlive(c: Context) {
        if (keepAliveRunning) return
        runCatching {
            ContextCompat.startForegroundService(
                c, Intent(c, KeepAliveService::class.java)
            )
        }
    }

    fun stopKeepAlive(c: Context) {
        runCatching { c.stopService(Intent(c, KeepAliveService::class.java)) }
        keepAliveRunning = false
    }

    /**
     * 自动请求的最小间隔。原来 30 秒太长 —— 用户从桌面切回来触发一次自动请求，
     * 接着进设置页手点，就必然落在窗口里，界面上会显示"系统节流"，看起来像坏了。
     * 5 秒足够挡住连点，也不会让手动操作被误伤。
     */
    private const val REBIND_MIN_INTERVAL_MS = 5_000L
}

/** 主动请求重绑的结果。三态是必须的 —— 用 Boolean 时"被节流"和"调用失败"没法区分 */
enum class RebindResult {
    /** 请求已交给系统，等它重绑 */
    SENT,

    /** 距上次请求太近，这次没发（只有自动调用会走到这里） */
    THROTTLED,

    /** 调用本身抛异常，通常说明组件状态异常，得走系统设置那条路 */
    FAILED
}

/**
 * 监听状态。抽成枚举是为了让所有界面用同一套判断 ——
 * 之前主界面看 `connected`、常驻通知看在启动那一刻的 `connected`，
 * 两者天然会不一致。
 */
enum class ListenerStatus {
    /** 用户关掉了「监听通知并自动建日程」总开关 */
    OFF,

    /** 没授予通知使用权 */
    NO_PERMISSION,

    /** 已授权，但服务还没连上（刚启动，或被系统摘掉了） */
    CONNECTING,

    /** 正常工作 */
    RUNNING
}
