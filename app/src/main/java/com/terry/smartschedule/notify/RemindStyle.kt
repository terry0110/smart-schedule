package com.terry.smartschedule.notify

/**
 * 到点了怎么提醒。
 *
 * 为什么要让用户选，而不是替他决定：
 *
 * 这四种方式的"打扰程度"差得很远，而最合适的那个取决于用户当时在干什么 ——
 * 在开会/打游戏，全屏弹页是灾难；在骑车、在厨房忙，只有静默通知等于没提醒。
 * 应用猜不出用户在干什么（要读前台应用得申请"使用情况访问"，为这点事不值得），
 * 所以把这个选择交出去。
 *
 * 尤其要说清楚 [FULLSCREEN] 的真实边界 —— 这里踩过坑，原注释写错过一次：
 *
 * 全屏页**只在息屏 / 锁屏时**才真的盖住界面。屏幕亮着、用户正在用手机时，
 * 系统会把 fullScreenIntent 降级成 heads-up 横幅，**不会盖住游戏**。
 * 这是 Android 的明文行为（原文：the system UI may choose to display a heads-up
 * notification, instead of launching this intent, while the user is using the device），
 * 不是这个应用能绕过的。要突破必须申请 SYSTEM_ALERT_WINDOW 悬浮窗权限，
 * 代价是用户得手动授权，而且会真的盖住一切 —— 目前没有做。
 *
 * 实测（Android 9，1.24）：
 *   息屏 → ReminderActivity 弹到栈顶，屏幕被点亮
 *   亮屏解锁 → 只有通知栏一条，栈顶还是桌面
 *
 * 所以 [FULLSCREEN] 和 [BANNER] 在**亮屏状态下表现完全相同**，两档的全部差别
 * 只在息屏 / 锁屏时。设置页的文案必须按这个事实写，不能写"任何时候都盖住屏幕"。
 */
enum class RemindStyle(val value: Int) {

    /**
     * 全屏弹页 + 闹钟铃声 + 震动。
     *
     * 最能叫醒人。**但注意：整页只在息屏 / 锁屏时弹**，亮屏时系统会降级成横幅
     * （原因见类注释）。
     */
    FULLSCREEN(0),

    /**
     * 顶部横幅（heads-up）+ 闹钟铃声 + 震动。
     *
     * **不盖住当前应用**：横幅从屏幕顶部滑下来，几秒后自己收回去，游戏不会被打断，
     * 声音和震动照样有。适合游戏、看视频、演示这类"别挡我屏幕"的场景。
     */
    BANNER(1),

    /**
     * 只往通知栏放一条，不响不震不弹。
     *
     * 适合"我自己会看手机"的场景。这是最弱的一档，容易真的错过。
     */
    SILENT(2),

    /**
     * 只震动，不响铃。
     *
     * 存在的理由很具体：开会、上课、图书馆、哄孩子睡觉 —— 这些场合声音不能出，
     * 但"手机在兜里震一下"能把你叫住。以前只有"响铃+震动"和"完全不响"两福，
     * 这两类场合只能二选一：要么吵到别人，要么真的错过。
     *
     * 不弹全屏页（和 [BANNER] 一样只出横幅），否则它在会议室里比铃声还显眼。
     */
    VIBRATE(3);

    /**
     * 要不要挂全屏意图。
     *
     * 只有这一档挂。挂上之后**只在息屏 / 锁屏时真正弹页**，亮屏时由系统降级成横幅 ——
     * 所以这一档和 [BANNER] 在亮屏状态下看起来一样，差别全在息屏时。
     */
    val popsFullScreen: Boolean get() = this == FULLSCREEN

    /** 要不要静音（不响不震）。静默模式必须换渠道，因为渠道的响铃是固定的 */
    val quiet: Boolean get() = this == SILENT

    /** 要不要只震不响。同样必须换渠道 —— 渠道的"有没有声音"建好就改不了 */
    val vibrateOnly: Boolean get() = this == VIBRATE

    companion object {
        /** 从存下来的数字还原。取值异常时退回 [FULLSCREEN]，不因为一个脏数据把提醒弄哑 */
        fun from(v: Int): RemindStyle = values().firstOrNull { it.value == v } ?: FULLSCREEN

        /** 单条日程"跟随设置"的哨兵值，存进 Event.remindStyle */
        const val FOLLOW_DEFAULT = -1

        /**
         * 发通知时真正生效的方式：单条自己选了就用单条的，没选或值不认识
         * （[FOLLOW_DEFAULT]、脏数据）就退回设置里的默认值。
         * 这是唯一一处决定"到底按哪档发"的地方，免得将来有人一处用单条的、
         * 一处用全局的，行为对不上。
         *
         * 注意单条的脏数据**不走 [from] 的全屏兜底**：那条日程从来没选过全屏，
         * 替它做主跳到最能打扰的一档不对，按全局默认来。
         */
        fun resolve(perEvent: Int, global: Int): RemindStyle =
            values().firstOrNull { it.value == perEvent } ?: from(global)
    }
}
