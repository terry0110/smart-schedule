package com.terry.smartschedule.data

import com.terry.smartschedule.parse.TextNorm
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 找出**已经躺在库里**的重复日程，供设置页「清理重复日程」使用。
 *
 * 入库时的 [EventMerge] 只能挡住"以后"；旧版本已经建出来的重复不会自己消失。
 *
 * 分寸比入库合并更保守：
 * - 标题必须宽松同题（[TextNorm.sameTitleLoose]）；
 * - 地点不冲突；
 * - **创建时间相差 ≤12 小时**：同一次通知反复推送/旧版本建出来的重复通常在同一时段，
 *   这样不会把每天、每周的例会当成重复；
 * - 日期相差 ≤3 天：允许「21 日改 22 日」这种旧版没识别出来的改期；
 * - 同一天且都带钟点的，时间必须落在 5 分钟内，否则是两场会。
 *
 * 只负责分组。删除由用户在设置页确认后执行。
 */
object EventDeduper {

    const val MAX_CREATED_GAP_MS = 12L * 3600_000L
    const val MAX_DAY_GAP = 3L

    data class Group(val keep: Event, val drop: List<Event>)

    /**
     * 删除前把被删副本独有的地点/备注补到保留项上。
     *
     * keep 是按「有时间 > 非待确认 > 最新创建」选的，不保证字段最全：
     * 典型情况是 keep 为带时间的较新版本，drop 是唯一带地点/原文的全天版。
     * 只删不并会让"保留最完整的一条"名不副实，用户的地点或原文就再也找不回来。
     */
    fun mergeKeep(group: Group): Event {
        val keep = group.keep
        val location = keep.location?.takeIf { it.isNotBlank() }
            ?: group.drop.firstNotNullOfOrNull { it.location?.takeIf(String::isNotBlank) }
        val note = keep.note?.takeIf { it.isNotBlank() }
            ?: group.drop.firstNotNullOfOrNull { it.note?.takeIf(String::isNotBlank) }
        // 结束时间也要补：keep 可能是"带钟点但没有结束时间"的旧版，
        // drop 里有完整区间（六点到九点）。只删不并会把九点一起删掉。
        val duration = (keep.endAt - keep.startAt).takeIf { keep.hasTime && it > 0 }
            ?: group.drop.firstNotNullOfOrNull { e ->
                (e.endAt - e.startAt).takeIf {
                    keep.hasTime && e.hasTime && it > 0 &&
                        kotlin.math.abs(e.startAt - keep.startAt) <= EventMerge.SAME_TIME_MS
                }
            }
        val endAt = if (duration != null) keep.startAt + duration else keep.endAt
        return keep.copy(location = location, note = note, endAt = endAt)
    }

    fun group(events: List<Event>): List<Group> {
        val pool = events.filter { it.id > 0 && !it.archived && !it.done && !it.revoked }
        if (pool.size < 2) return emptyList()

        // 用并查集求「相似」关系的连通分量，而不是"以某一条为锚"贪心分组。
        // 贪心有两个真实问题：
        // 1) A≈B、B≈C、A≉C 时（比如创建时间隔了 22 小时），C 会被漏掉；
        // 2) 结果依赖列表顺序，同样的三条数据换个顺序可能少分一组。
        val parent = HashMap<Long, Long>(pool.size)
        pool.forEach { parent[it.id] = it.id }

        fun find(id: Long): Long {
            var root = id
            while (parent.getValue(root) != root) root = parent.getValue(root)
            var cur = id
            while (parent.getValue(cur) != root) {
                val next = parent.getValue(cur)
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: Long, b: Long) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        for (i in pool.indices) {
            for (j in i + 1 until pool.size) {
                if (similar(pool[i], pool[j])) union(pool[i].id, pool[j].id)
            }
        }

        val components = LinkedHashMap<Long, MutableList<Event>>()
        pool.forEach { components.getOrPut(find(it.id)) { ArrayList() }.add(it) }

        return components.values.mapNotNull { all ->
            if (all.size < 2) return@mapNotNull null
            // 优先保留带具体时间的、非待确认的、最新创建的
            val keep = all.maxWithOrNull(KEEP_ORDER) ?: all.first()
            Group(keep, all.filter { it.id != keep.id })
        }
    }

    /**
     * 删除前按当前库重新确认哪些 drop 还能删。
     *
     * - 弹窗期间没动过的条目（当前值和预览快照完全一致）：预览时已经确认过，
     *   直接沿用分组。这样链式重复（A≈B、B≈C、A 与 C 不直接相似）里和 keep
     *   不相似的那条也不会在删除阶段被漏掉。
     * - 动过的条目：只要还和组里任意一条当前条目相似，就仍属于这组重复。
     * - keep 被改过时，所有 drop 都按“与任意成员相似”重新判断。
     */
    fun dropsToDelete(group: Group, current: Map<Long, Event>): List<Event> {
        val keep = current[group.keep.id] ?: return emptyList()
        if (keep.archived || keep.done || keep.revoked) return emptyList()

        val live = group.drop.mapNotNull { snap ->
            current[snap.id]?.takeIf { !it.archived && !it.done && !it.revoked }
        }
        if (live.isEmpty()) return emptyList()

        val keepUnchanged = keep == group.keep
        val members = listOf(keep) + live

        return live.filter { now ->
            val snap = group.drop.firstOrNull { it.id == now.id } ?: return@filter false
            if (keepUnchanged && now == snap) return@filter true
            members.any { other -> other.id != now.id && similar(other, now) }
        }
    }

    fun similar(a: Event, b: Event): Boolean {
        if (!TextNorm.sameTitleLoose(a.title, b.title)) return false
        if (!EventMerge.sameLocation(a.location, b.location)) return false
        if (kotlin.math.abs(a.createdAt - b.createdAt) > MAX_CREATED_GAP_MS) return false

        val zone = ZoneId.systemDefault()
        val da = Instant.ofEpochMilli(a.startAt).atZone(zone).toLocalDate()
        val db = Instant.ofEpochMilli(b.startAt).atZone(zone).toLocalDate()
        val dayGap = kotlin.math.abs(ChronoUnit.DAYS.between(da, db))
        if (dayGap > MAX_DAY_GAP) return false

        // 同一天又都带钟点：时间不接近就是两场会
        if (da == db && a.hasTime && b.hasTime &&
            kotlin.math.abs(a.startAt - b.startAt) > EventMerge.SAME_TIME_MS
        ) {
            return false
        }
        return true
    }

    private val KEEP_ORDER: Comparator<Event> =
        compareBy<Event> { if (it.hasTime) 1 else 0 }
            .thenBy { if (it.pending) 0 else 1 }
            .thenBy { it.createdAt }
}
