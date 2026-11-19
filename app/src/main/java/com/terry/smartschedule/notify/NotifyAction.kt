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
}
