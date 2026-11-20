package com.terry.smartschedule.data

import com.terry.smartschedule.parse.TextNorm
import com.terry.smartschedule.util.TimeFmt
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 新日程与库里已有日程的冲突检查。
 *
 * 只回答"要不要让人确认"，不改数据：
 * 1. **同一时间**：两条都带钟点，开始时间相差 ≤5 分钟 —— 硬冲突；
 * 2. **名称 + 地点高度重合**：前后 3 天内，标题字符重合 ≥80%，
 *    地点归一化后相同或互相包含 —— 可能只是同一条消息的另一个版本。
 *
 * 为什么需要第二类：很多群通知改期/改地点时不会写「原定 / 改到」，
 * 只发一条新的。直接建第二条会让列表里同时出现两条互相矛盾的时间；
 * 自动覆盖又可能改错。交给 pending 让人确认，是两边代价最平衡的做法。
 */
object EventConflict {

    /** 和 [EventMerge] 用同一个容差：不同来源推出来的时间常差几分钟 */
    const val SAME_TIME_MS = 5 * 60_000L

    /** 只和前后 3 天内的日程比"名称/地点重合"，避免把远处的系列日程翻出来 */
    const val LOOKAROUND_DAYS = 3L

    /** 标题字符太少时不做重合度判断（"开会"和"部门会议"不算同一条） */
    const val MIN_TITLE_KEY = 4

    /** 字符集合包含度门槛。0.8 能放过"部门周会/部门会议"，抓住"贺老师…盖章"的截断版 */
    const val MIN_TITLE_COVER = 0.8

    enum class Kind { SAME_TIME, SIMILAR }

    data class Hit(val kind: Kind, val event: Event)

    /**
     * @param now 显式传入，测试才能固定"过去/未来"
     * @return 最需要确认的一条；没有冲突返回 null
     */
    fun check(incoming: Event, existing: List<Event>, now: Long): Hit? {
        val pool = existing.filter {
            it.id > 0 && it.id != incoming.id &&
                !it.archived && !it.done && !it.revoked &&
                // 已经过去的日程不参与：新通知几乎都指向未来，拿旧日程来比只会制造噪音
                it.startAt >= now
        }
        if (pool.isEmpty()) return null

        // 1) 同一时间优先。标题不同也算硬冲突 —— 那个时段已经有一件事了
        if (incoming.hasTime) {
            pool.asSequence()
                .filter {
                    it.hasTime &&
                        kotlin.math.abs(it.startAt - incoming.startAt) <= SAME_TIME_MS
                }
                .minByOrNull { kotlin.math.abs(it.startAt - incoming.startAt) }
                ?.let { return Hit(Kind.SAME_TIME, it) }
        }

        // 2) 名称 + 地点高度重合
        val zone = ZoneId.systemDefault()
        val inDate = Instant.ofEpochMilli(incoming.startAt).atZone(zone).toLocalDate()
        val best = pool.mapNotNull { e ->
            val eDate = Instant.ofEpochMilli(e.startAt).atZone(zone).toLocalDate()
            val dayGap = kotlin.math.abs(ChronoUnit.DAYS.between(inDate, eDate))
            if (dayGap > LOOKAROUND_DAYS) return@mapNotNull null

            val title = titleCover(incoming.title, e.title)
            if (title < MIN_TITLE_COVER) return@mapNotNull null
            if (locationCover(incoming.location, e.location) == 0.0) return@mapNotNull null
            e to title
        }.maxByOrNull { it.second }

        return best?.let { Hit(Kind.SIMILAR, it.first) }
    }

    /**
     * 标题重合度：
     * - 先看 [TextNorm.sameTitleLoose]（截断版、改期前缀）——命中就是 1.0；
     * - 否则用字符集合的"包含度"：交集 / 较短标题的字符数。
     *   不用二元组，是因为中文短标题的二元组太少，"贺老师盖章"和
     *   "贺老师签假条盖章"会被低估。
     */
    fun titleCover(a: String, b: String): Double {
        if (TextNorm.sameTitleLoose(a, b)) return 1.0
        val ka = TextNorm.key(a).toSet()
        val kb = TextNorm.key(b).toSet()
        if (ka.size < MIN_TITLE_KEY || kb.size < MIN_TITLE_KEY) return 0.0
        val inter = ka.intersect(kb).size
        return inter.toDouble() / minOf(ka.size, kb.size)
    }

    /**
     * 地点重合：必须是"同一地点"或一个包含另一个。
     *
     * 刻意不做字符重合 —— "A栋302" 和 "A栋303" 共享 4 个字符，
     * 但那是两个房间；地点只差门牌号时，按不同地点处理。
     * 两边都必须有地点：一边为空说明这条通知没给地址，不满足"大量地点重合"。
     */
    fun locationCover(a: String?, b: String?): Double {
        val x = TextNorm.key(a.orEmpty())
        val y = TextNorm.key(b.orEmpty())
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return if (x == y || x.contains(y) || y.contains(x)) 1.0 else 0.0
    }

    /** 给用户看的待确认理由 */
    fun reason(hit: Hit): String {
        val whenText = TimeFmt.summary(hit.event.startAt, hit.event.hasTime, hit.event.location)
        return when (hit.kind) {
            Kind.SAME_TIME ->
                "与「${hit.event.title}」（$whenText）同一时间，请确认是不是两件事"
            Kind.SIMILAR ->
                "与「${hit.event.title}」（$whenText）名称和地点高度重合，可能是同一条的变化，请确认"
        }
    }
}
