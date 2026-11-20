package com.terry.smartschedule

import com.terry.smartschedule.data.Event
import com.terry.smartschedule.notify.ReminderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 提醒到底会不会响。
 *
 * 这组测试针对的是一个**不看代码永远发现不了**的问题：日程建出来了、列表里显示得好好的，
 * 但闹钟根本没排上，到点一声不吭。界面上没有任何地方会表现异常。
 *
 * 模拟器上实测出来的原始现象：
 * ```
 * 20:47 建一条 20:52 开始的日程（提前量 10 分钟）
 *   → 提醒点 20:42 已经过去 → 旧代码直接 return → 闹钟没排 → 永远不提醒
 * ```
 */
class ReminderPlanTest {

    /** 基准时刻：2026-10-24 20:47 */
    private val now = at(2026, 10, 24, 20, 47)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun event(
        startAt: Long,
        hasTime: Boolean = true,
        remindMinutes: Int = 10,
        done: Boolean = false,
        pending: Boolean = false
    ) = Event(
        id = 1L,
        title = "开会",
        startAt = startAt,
        hasTime = hasTime,
        remindMinutes = remindMinutes,
        done = done,
        pending = pending
    )

    private fun planOf(e: Event) = ReminderPlan.plan(e, now)

    // ---------------- 核心：提醒点在过去的，必须还能响 ----------------

    @Test
    fun `归档的日程不提醒`() {
        val e = event(startAt = now + 60 * 60_000L).copy(archived = true)
        val p = planOf(e)
        assertTrue("归档后不该再响，实际 $p", p is ReminderPlan.Plan.Skip)
        assertTrue((p as ReminderPlan.Plan.Skip).reason.contains("已归档"))
    }

    @Test
    fun `事件五分钟后开始时立刻提醒，而不是永不提醒`() {
        // 20:52 开始，提前 10 分钟 → 提醒点 20:42 已过
        val p = planOf(event(startAt = at(2026, 10, 24, 20, 52)))
        assertTrue("应该是「马上响」，实际：$p", p is ReminderPlan.Plan.At)
        p as ReminderPlan.Plan.At
        assertEquals(now + ReminderPlan.IMMEDIATE_DELAY_MS, p.triggerAt)
    }

    @Test
    fun `提前量大于剩余时间时也是立刻提醒`() {
        // 60 分钟提前量，事件 3 分钟后开始
        val p = planOf(event(startAt = at(2026, 10, 24, 20, 50), remindMinutes = 60))
        assertTrue(p is ReminderPlan.Plan.At)
        assertEquals(now + ReminderPlan.IMMEDIATE_DELAY_MS, (p as ReminderPlan.Plan.At).triggerAt)
    }

    @Test
    fun `事件已开始就不再打扰`() {
        val p = planOf(event(startAt = at(2026, 10, 24, 20, 40)))
        assertTrue("已经开始的日程不该补提醒，实际：$p", p is ReminderPlan.Plan.Skip)
    }

    // ---------------- 正常情况 ----------------

    @Test
    fun `远期事件按提前量排`() {
        val start = at(2026, 10, 25, 15, 0)
        val p = planOf(event(startAt = start))
        assertEquals(start - 10 * 60_000L, (p as ReminderPlan.Plan.At).triggerAt)
    }

    @Test
    fun `提前量设为不提醒时不排`() {
        assertTrue(planOf(event(at(2026, 10, 25, 15, 0), remindMinutes = -1)) is ReminderPlan.Plan.Skip)
    }

    @Test
    fun `已完成的日程不排`() {
        assertTrue(planOf(event(at(2026, 10, 25, 15, 0), done = true)) is ReminderPlan.Plan.Skip)
    }

    @Test
    fun `待确认的日程不排提醒`() {
        // 还没确认的日程不该响 —— 用户可能根本不认这条
        assertTrue(planOf(event(at(2026, 10, 25, 15, 0), pending = true)) is ReminderPlan.Plan.Skip)
    }

    // ---------------- 全天日程：原来完全不会提醒 ----------------

    @Test
    fun `全天日程在当天九点提醒`() {
        // 解析器把全天日程的 startAt 填成当天 09:00，直接按它响
        val nine = at(2026, 10, 25, 9, 0)
        val p = planOf(event(nine, hasTime = false))
        assertEquals(nine, (p as ReminderPlan.Plan.At).triggerAt)
    }

    @Test
    fun `今天的全天日程已过九点时立刻提醒`() {
        // 现在是 20:47，今天的全天日程九点就该提醒了
        val p = planOf(event(at(2026, 10, 24, 9, 0), hasTime = false))
        assertTrue("今天的全天日程应该马上补提醒，实际：$p", p is ReminderPlan.Plan.At)
        assertEquals(now + ReminderPlan.IMMEDIATE_DELAY_MS, (p as ReminderPlan.Plan.At).triggerAt)
    }

    @Test
    fun `昨天的全天日程不提醒`() {
        val p = planOf(event(at(2026, 10, 23, 9, 0), hasTime = false))
        assertTrue(p is ReminderPlan.Plan.Skip)
    }

    // ---------------- 补发判据 ----------------

    @Test
    fun `立刻提醒的计划会被认成漏发`() {
        val p = planOf(event(at(2026, 10, 24, 20, 52)))
        assertTrue(ReminderPlan.isImmediate(p, now))
    }

    @Test
    fun `远期计划不会被认成漏发`() {
        val p = planOf(event(at(2026, 10, 25, 15, 0)))
        assertTrue(!ReminderPlan.isImmediate(p, now))
    }
}
