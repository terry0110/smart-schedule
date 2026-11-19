package com.terry.smartschedule.notify

import com.terry.smartschedule.data.Event
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 一条日程**该在什么时候响**。纯计算，不碰 Android，所以能直接单测。
 *
 * 抽出来的原因很实际：原来这段逻辑写在 `ReminderScheduler` 里，一行 `if` 判断
 * 就把整类日程漏掉了 —— 而漏掉的后果是"这条日程永远不提醒"，界面上完全看不出来，
 * 只有等用户某天发现"我说好要开会，手机一声没吭"。
 *
 * 具体漏的是哪一类（模拟器上实测出来的）：
 *
 * ```
 * 20:47 创建一条 20:52 开始的日程，提前量 10 分钟
 *   → 提醒点 20:42 已经过去了 → 直接 return → 闹钟没排 → 永远不会提醒
 * ```
 *
 * 这不是边角情况。用户从微信看到"三点开会"时，往往已经两点五十了 —— 越紧急的事越容易漏。
 */
object ReminderPlan {

    /**
     * 提醒点已经过去、但事情还没开始时，隔多久响。
     *
     * 不设 0：立刻响的话，用户刚点完"添加"就被弹一脸通知，像是操作出错。
     * 两秒足够界面回到列表，感觉是"然后手机提醒你"。
     */
    const val IMMEDIATE_DELAY_MS = 2_000L

    sealed interface Plan {
        /** 在这个时间点响 */
        data class At(val triggerAt: Long) : Plan

        /** 不响，理由是给日志/排查看的 */
        data class Skip(val reason: String) : Plan
    }

    /**
     * @param now 当前毫秒时间戳。显式传进来而不是内部取 `System.currentTimeMillis()`，
     *            否则这个函数没法测。
     */
    fun plan(event: Event, now: Long): Plan {
        if (event.done) return Plan.Skip("已完成")
        // 后来的通知说这条取消了：再响一次是纯噪音，就算用户还没去删它
        if (event.revoked) return Plan.Skip("据通知已取消")
        if (event.remindMinutes < 0) return Plan.Skip("这条设了不提醒")
        if (event.pending) return Plan.Skip("还待确认，确认后才排提醒")

        // 全天日程和带钟点的日程，"作废"的判据不一样：
        //
        //   带钟点：过了开始时间就没意义了（会已经开完了）
        //   全天  ：startAt 被填成当天 09:00，但那一天**一整天都还算数** ——
        //           按 09:00 判"已开始"的话，上午十点建的"今天交材料"就永远不提醒了
        //
        // 所以全天日程用"当天结束"作废，而不是用 startAt。
        val triggerAt: Long
        if (event.hasTime) {
            if (event.startAt <= now) return Plan.Skip("已经开始了，不补提醒")
            triggerAt = event.startAt - event.remindMinutes * 60_000L
        } else {
            val dayEnd = endOfDay(event.startAt)
            if (dayEnd <= now) return Plan.Skip("那天已经过去了")
            // 全天日程的提醒点就是当天 09:00，不减提前量：
            // "提前 10 分钟"对一件没有具体时间的事没有意义。
            triggerAt = event.startAt
        }

        // 提醒点已过但事还没结束 —— 立刻响，不要装没事
        if (triggerAt <= now) return Plan.At(now + IMMEDIATE_DELAY_MS)

        return Plan.At(triggerAt)
    }

    /** 这个时间戳所在那一天的 24:00（即次日零点） */
    private fun endOfDay(ms: Long): Long {
        val zone = ZoneId.systemDefault()
        val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).toLocalDate()
        return date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * 这个计划是不是"现在就该响"。
     *
     * 补发逻辑用它来判断哪些日程漏了提醒：漏掉的特征就是计划退化成"立刻响"。
     */
    fun isImmediate(plan: Plan, now: Long): Boolean =
        plan is Plan.At && plan.triggerAt <= now + IMMEDIATE_DELAY_MS
}
