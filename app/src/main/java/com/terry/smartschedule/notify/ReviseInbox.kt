package com.terry.smartschedule.notify

/**
 * 应用内「改期待办」的内存队列。
 *
 * 为什么只放内存、不落库：
 * - 真正的兜底是通知栏那条询问 + PendingIntent extras。进程被杀之后，
 *   用户点通知仍然能把参数带回来处理；
 * - 落库要加表、走迁移、写过期清理，收益不成比例 —— 这是"回头看一眼"的提问，
 *   不是一条需要长期保留的日程（v1.38 的取舍，升级点 4A）。
 *
 * 上限 5 条、3 天过期：防止一个群反复刷「改到3点」把内存和界面堆满。
 * 所有方法加锁：服务在 IO 线程写，界面在主线程读。
 */
object ReviseInbox {

    const val MAX = 5
    const val TTL_MS = 3L * 24 * 3600_000L

    data class Item(
        /** 原文归一化键，用来给同一条询问去重 */
        val key: String,
        val text: String,
        val oldStartAt: Long?,
        val newStartAt: Long?,
        val newHasTime: Boolean,
        val meridiemGuessed: Boolean,
        val newLocation: String?,
        /** 新时间段的结束时间；0 = 原文没写，沿用原日程时长 */
        val newEndAt: Long = 0L,
        val at: Long = System.currentTimeMillis()
    )

    private val items = ArrayDeque<Item>()

    @Synchronized
    fun add(item: Item, now: Long = System.currentTimeMillis()) {
        prune(now)
        // 同一条通知重渲染时只保留最新一份，不要堆出五张一样的卡片
        items.removeAll { it.key == item.key }
        items.addLast(item.copy(at = now))
        while (items.size > MAX) items.removeFirst()
    }

    @Synchronized
    fun remove(key: String) {
        items.removeAll { it.key == key }
    }

    @Synchronized
    fun clear() {
        items.clear()
    }

    @Synchronized
    fun snapshot(now: Long = System.currentTimeMillis()): List<Item> {
        prune(now)
        return items.toList()
    }

    /** 过期项只会在队头 —— add 永远追加在尾部，时间单调递增 */
    private fun prune(now: Long) {
        while (items.isNotEmpty() && now - items.first().at > TTL_MS) {
            items.removeFirst()
        }
    }
}
