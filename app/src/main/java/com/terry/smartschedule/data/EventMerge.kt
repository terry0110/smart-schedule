package com.terry.smartschedule.data

import com.terry.smartschedule.parse.TextNorm
import java.time.Instant
import java.time.ZoneId

/**
 * 「这条通知和库里那条是不是同一件事」。
 *
 * 纯函数，[EventMergeTest] 守着。用在通知入库前：
 * - 同一条通知先被截断、后来补全（全天 → 带钟点）
 * - 同一条通知多了「现时间有变」这类前缀
 * 这类情况标题/时间不完全相等，`findSameEvent` 的精确口径挡不住。
 *
 * 分寸：
 * - 同一天、地点不冲突、标题宽松同题才比；
 * - 两条都带钟点时，时间必须落在 5 分钟内，否则可能是另一场会；
 * - 一条全天一条带钟点，才允许合并（"补全时间"）。
 */
object EventMerge {

    const val SAME_TIME_MS = 5 * 60_000L

    fun shouldMerge(existing: Event, incoming: Event): Boolean {
        if (existing.done || existing.revoked) return false
        if (!TextNorm.sameTitleLoose(existing.title, incoming.title)) return false
        if (!sameLocation(existing.location, incoming.location)) return false
        if (!sameDay(existing.startAt, incoming.startAt)) return false

        // 全天 vs 带钟点：同一天、同题、地点不冲突，视为同一条被补全/截断
        if (!existing.hasTime || !incoming.hasTime) return true

        return kotlin.math.abs(existing.startAt - incoming.startAt) <= SAME_TIME_MS
    }

    /**
     * 去重命中时，这次的新信息能不能把结束时间补给库里那条。
     *
     * 「已经有了」不等于"新信息可以丢"：先收到「明天下午六点开会」（没有结束时间），
     * 后来完整版写了「六点到九点」，如果直接按重复跳过，九点就永远补不上去。
     * 只补"库里没有、这次有"的情况；两边都有结束时间时不猜谁对。
     */
    fun canFillEnd(existing: Event, incomingStartAt: Long, incomingHasTime: Boolean, incomingEndAt: Long): Boolean =
        incomingHasTime && incomingEndAt > incomingStartAt &&
            (!existing.hasTime || existing.endAt <= existing.startAt)

    /** 地点一边为空时不算冲突；两边都有值就必须相同 */
    fun sameLocation(a: String?, b: String?): Boolean {
        val x = a?.trim().orEmpty()
        val y = b?.trim().orEmpty()
        return x.isEmpty() || y.isEmpty() || x == y
    }

    fun sameDay(a: Long, b: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val da = Instant.ofEpochMilli(a).atZone(zone).toLocalDate()
        val db = Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
        return da == db
    }
}
