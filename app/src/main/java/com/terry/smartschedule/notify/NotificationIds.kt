package com.terry.smartschedule.notify

/**
 * 通知 id 与按钮 requestCode 的计算。**纯算术，不依赖 Android**，所以能直接单测。
 *
 * 抽出来单独放的理由：这类"算个数字"的代码最容易写错，而错了之后
 * 从通知栏完全看不出来 —— 只有用户发现"确认了 A，结果 B 变了"才会暴露，
 * 到那时候已经很难查了。
 */
object NotificationIds {

    /** 到点提醒的通知 id。直接用日程 id，一个日程一个槽位 */
    fun remind(eventId: Long): Int = clamp(eventId)

    /**
     * 「已自动记录」回执的 id。
     *
     * **固定一个号**，所有自动抓到的日程共用这一条通知 —— 以前是每条日程一个 id，
     * 群里一天几条日程就等于往通知栏扔几条，结果被 Android 的通知限流判定为刷屏，
     * 连着真正要紧的到点提醒一起被静音（真机实测 muted=149/150）。
     */
    const val CAPTURE_SUMMARY_ID = 900_001

    /**
     * 待确认通知的偏移量，避开提醒通知的号段。
     * 单独暴露出来是为了让测试能固定这个契约。
     */
    const val PENDING_OFFSET = 500_000L

    /** 把 id 限制在 32 位非负范围。Android 的通知 id / requestCode 都是 Int */
    private fun clamp(v: Long): Int = (v and 0x7FFFFFFFL).toInt()

    /** 某条日程的待确认通知 id。发送和清除必须用同一个算法 */
    fun pending(eventId: Long): Int = clamp(eventId + PENDING_OFFSET)

    /**
     * 通知按钮的 requestCode。
     *
     * 必须按「日程 id + 动作槽位」唯一：
     *  - 同一日程的两个按钮（确认/删除）不能撞 → slot 区分
     *  - 不同日程的同名按钮也不能撞 → eventId 区分
     * 撞了的话，系统会把它们当成同一个 PendingIntent，点哪条都作用在第一条上。
     */
    fun actionRequestCode(eventId: Long, slot: Int): Int = clamp(eventId * 10 + slot)
}
