package com.terry.smartschedule.notify

/**
 * 通知栏按钮的动作名。
 * 集中成常量，免得发送端和接收端各写一份字符串、拼错一个字母就静默失效。
 */
object NotifyAction {

    /** 用户点了「确认」 —— 待确认日程转正并排提醒 */
    const val ACTION_CONFIRM = "com.terry.smartschedule.CONFIRM_EVENT"

    /** 用户点了「删除」 —— 直接丢掉这条 */
    const val ACTION_DELETE = "com.terry.smartschedule.DELETE_EVENT"

    /**
     * 用户点了提醒上的「知道了」 —— 标记完成并撤掉提醒。
     *
     * 和「确认」的区别：确认是"待确认日程转正"，这个是"到点提醒已经处理完"。
     * 语义不同，不能共用一个动作，否则从通知栏没法分辨该走哪条分支。
     */
    const val ACTION_DONE = "com.terry.smartschedule.MARK_DONE"

    /** 用户点了「稍后 10 分钟」 */
    const val ACTION_SNOOZE = "com.terry.smartschedule.SNOOZE"

    const val EXTRA_EVENT_ID = "event_id"

    /** 数据在后台被改了（通知栏里确认/删除），让前台的列表刷新 */
    const val ACTION_DATA_CHANGED = "com.terry.smartschedule.DATA_CHANGED"

    /**
     * 点了「改期需要确认」通知：把主界面叫起来，让用户选出改的是哪一条。
     *
     * 这条链路**只在信息不全时**走（缺新时间 / 候选不唯一）。
     * 信息完整的情况在 [NotificationCaptureService] 里就直接改了，不会弹任何东西。
     */
    const val ACTION_REVISE_CONFIRM = "com.terry.smartschedule.REVISE_CONFIRM"

    /** 改期通知原文，用来给目标补一条「据通知改期」的备注 */
    const val EXTRA_REVISE_TEXT = "revise_text"

    /** 原定时间（毫秒）。有它才能把候选缩到 ±30 分钟 */
    const val EXTRA_REVISE_OLD_START = "revise_old_start"

    /** 新时间（毫秒）。没有这个 extra = 原文没说清新时间，只能让用户手动改 */
    const val EXTRA_REVISE_NEW_START = "revise_new_start"

    /** 新时间段的结束时间（毫秒）。0 = 原文没写结束时间，沿用原日程时长 */
    const val EXTRA_REVISE_NEW_END = "revise_new_end"

    /** 新时间有没有具体钟点（false = 只说了改到哪天） */
    const val EXTRA_REVISE_NEW_HAS_TIME = "revise_new_has_time"

    /** 新地点（"换到 B 栋 301"）。没有这个 extra = 没提地点 */
    const val EXTRA_REVISE_NEW_LOCATION = "revise_new_location"

    /** 新时间的上下午是按旧时间推的，确认时要提醒用户核对 */
    const val EXTRA_REVISE_MERIDIEM = "revise_meridiem"
}
