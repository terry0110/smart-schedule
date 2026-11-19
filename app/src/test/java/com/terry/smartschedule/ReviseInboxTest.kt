package com.terry.smartschedule

import com.terry.smartschedule.notify.ReviseInbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内改期待办队列。
 *
 * 判错的代价是"界面上出现一张点开啥也没有的卡片"，或者真正的待办被上限挤掉。
 * 这组测试钉住：去重、上限、过期、移除。
 */
class ReviseInboxTest {

    private fun item(key: String, at: Long = 0L) = ReviseInbox.Item(
        key = key,
        text = "原文 $key",
        oldStartAt = null,
        newStartAt = null,
        newHasTime = true,
        meridiemGuessed = false,
        newLocation = null,
        at = at
    )

    @Test
    fun `加入后能按顺序读出来`() {
        ReviseInbox.clear()
        ReviseInbox.add(item("a"), now = 1000L)
        ReviseInbox.add(item("b"), now = 2000L)
        assertEquals(listOf("a", "b"), ReviseInbox.snapshot(2000L).map { it.key })
    }

    @Test
    fun `同一条原文只留最新一份`() {
        ReviseInbox.clear()
        ReviseInbox.add(item("a"), now = 1000L)
        ReviseInbox.add(item("b"), now = 1500L)
        ReviseInbox.add(item("a"), now = 2000L)
        val items = ReviseInbox.snapshot(2000L)
        assertEquals(2, items.size)
        assertEquals("b", items[0].key)
        assertEquals("a", items[1].key)
        assertEquals("最后一条的时间要更新", 2000L, items[1].at)
    }

    @Test
    fun `最多只留五条，超出的挤掉最旧的`() {
        ReviseInbox.clear()
        for (i in 1..8) ReviseInbox.add(item("k$i"), now = i * 1000L)
        val items = ReviseInbox.snapshot(8000L)
        assertEquals(ReviseInbox.MAX, items.size)
        assertEquals(listOf("k4", "k5", "k6", "k7", "k8"), items.map { it.key })
    }

    @Test
    fun `超过三天的自动过期`() {
        ReviseInbox.clear()
        ReviseInbox.add(item("old"), now = 0L)
        ReviseInbox.add(item("new"), now = ReviseInbox.TTL_MS + 1)
        val items = ReviseInbox.snapshot(ReviseInbox.TTL_MS + 1)
        assertEquals(1, items.size)
        assertEquals("new", items[0].key)
    }

    @Test
    fun `处理掉之后要从队列里移除`() {
        ReviseInbox.clear()
        ReviseInbox.add(item("a"), now = 1000L)
        ReviseInbox.remove("a")
        assertTrue(ReviseInbox.snapshot(1000L).isEmpty())
    }
}
