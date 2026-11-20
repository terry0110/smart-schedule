package com.terry.smartschedule.parse

import android.content.Context
import android.content.SharedPreferences

/** 应用设置，全部落在 SharedPreferences 里 */
object Prefs {

    private const val FILE = "settings"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!this::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    /** 是否监听系统通知并自动建日程 */
    var captureEnabled: Boolean
        get() = sp.getBoolean("capture_enabled", false)
        set(v) {
            sp.edit().putBoolean("capture_enabled", v).apply()
        }

    /**
     * 是否常驻前台服务保活。
     * 默认开——通知识别一旦被系统杀掉就是"莫名其妙不工作了"，比多一条通知更烦人。
     */
    var keepAlive: Boolean
        get() = sp.getBoolean("keep_alive", true)
        set(v) {
            sp.edit().putBoolean("keep_alive", v).apply()
        }

    /**
     * 没把握的识别结果是否进"待确认"，而不是直接丢掉。
     * 默认开：宁可多让用户点一下，也不要把通知里的日程漏掉。
     */
    var pendingWhenUnsure: Boolean
        get() = sp.getBoolean("pending_when_unsure", true)
        set(v) {
            sp.edit().putBoolean("pending_when_unsure", v).apply()
        }

    /**
     * 抓取日志里是否保存通知原文。
     * 默认关：日志只记"哪个应用、结果如何、为什么"，不记内容本身。
     * 排查特定问题时可以临时打开。
     */
    var logRawText: Boolean
        get() = sp.getBoolean("log_raw_text", false)
        set(v) {
            sp.edit().putBoolean("log_raw_text", v).apply()
        }

    /**
     * 日程里是否保存识别/抓取到的原文。
     * 默认开：原文用于回溯"这条是怎么来的"，也是编辑面板里那行「原文」的来源。
     * 关掉只影响**之后**新建/改期的日程，已有日程里的原文不会自动删除。
     */
    var saveEventNote: Boolean
        get() = sp.getBoolean("save_event_note", true)
        set(v) {
            sp.edit().putBoolean("save_event_note", v).apply()
        }

    /**
     * 诊断模式：把**每一个**通知回调都记进日志，包括被判为"不像日程"而放过的那些。
     *
     * 默认关。关着的时候，日志里只会出现"像日程却没能建出来"的记录 ——
     * 于是"回调根本没进来"和"回调进来了但被筛掉"在界面上长得一模一样，
     * 这正是"服务显示已连接、通知却毫无反应"最难查的地方。
     * 打开后能直接看出卡在哪一步（文本太短 / 常驻通知 / 折叠摘要 / 不含时间线索 ...）。
     */
    var captureVerbose: Boolean
        get() = sp.getBoolean("capture_verbose", false)
        set(v) {
            sp.edit().putBoolean("capture_verbose", v).apply()
        }

    /** 删除全部本地数据时用：清空所有设置（授权状态归系统管，清不掉也不该清） */
    fun clearAll() {
        sp.edit().clear().apply()
    }

    /**
     * 是否把日程同步进系统日历。
     *
     * 默认关：写系统日历要 READ/WRITE_CALENDAR 两个运行时权限，
     * 默认就伸手要权限的应用令人反感。用户主动打开时才申请、才批量补推存量日程。
     */
    var calendarSync: Boolean
        get() = sp.getBoolean("calendar_sync", false)
        set(v) {
            sp.edit().putBoolean("calendar_sync", v).apply()
        }

    /**
     * 到点了怎么提醒：0 全屏 / 1 横幅 / 2 只通知栏。见 [com.terry.smartschedule.notify.RemindStyle]。
     *
     * 默认全屏：用户装这个应用的目的是"别让我忘事"，第一优先级是叫得醒。
     * 嫌吵的（尤其打游戏的）自己去设置里改成一档。
     */
    var remindStyle: Int
        get() = sp.getInt("remind_style", 0)
        set(v) {
            sp.edit().putInt("remind_style", v).apply()
        }

    /**
     * 已经响过提醒的日程，键是 `事件id:开始时间戳`。
     *
     * 为什么必须落盘：补发逻辑（[com.terry.smartschedule.notify.ReminderScheduler.catchUp]）
     * 的判断依据是"提醒点过了但还没响"，而进程重启后内存里的记录全没了。
     * 只放内存的话，用户早上点了「稍后 10 分钟」、中午重新进一次应用，就会被立刻再弹一次。
     *
     * 键里带开始时间戳，是为了让"改了时间的日程"自动变成未提醒状态 ——
     * 改了时间当然应该重新提醒一次，不需要额外的清理逻辑。
     */
    var remindedKeys: Set<String>
        get() = sp.getStringSet("reminded", emptySet()) ?: emptySet()
        set(v) {
            sp.edit().putStringSet("reminded", v).apply()
        }

    /**
     * 已经发出去、通知栏里可能还挂着的提醒（日程 id）。
     *
     * 为什么自己记账，而不是回看系统"栏里现在有什么"：
     *
     * 1. **发过什么只有自己清楚**。系统回看（`getActiveNotifications()`）只能给出
     *    "此刻还在"的那些，而"该不该撤"取决于"我们发过、而日程已经没了" ——
     *    这是两件事。
     * 2. **不依赖系统的实现细节**。查得到什么、什么时候更新，各版本各 ROM 都不一样；
     *    自己记的一笔账在任何设备上行为一致。
     * 3. **路径无关**。日程可能是批量删的、在通知栏划掉的、清空数据清掉的，
     *    甚至是进程被杀时没走到撤销那一步的 —— 只要账上有、日程没了，就撤。
     *
     * 落盘是因为进程随时可能被杀：只在内存里的话，重启后这份账就空了。
     */
    var postedReminds: Set<Long>
        get() = sp.getString("posted_reminds", null)
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.toSet()
            ?: emptySet()
        set(v) {
            sp.edit().putString("posted_reminds", v.sorted().joinToString(",")).apply()
        }

    /** 默认提前提醒分钟数，-1 表示不提醒 */
    var defaultRemind: Int
        get() = sp.getInt("default_remind", 10)
        set(v) {
            sp.edit().putInt("default_remind", v).apply()
        }

    /** 上一条被处理过的通知签名，用于去重 */
    var lastSig: String
        get() = sp.getString("last_sig", "") ?: ""
        set(v) {
            sp.edit().putString("last_sig", v).apply()
        }

    var lastSigAt: Long
        get() = sp.getLong("last_sig_at", 0L)
        set(v) {
            sp.edit().putLong("last_sig_at", v).apply()
        }
}
