package com.terry.smartschedule

import com.terry.smartschedule.notify.WatchedApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知来源白名单。
 *
 * 这里固定的是**取舍**，不是实现细节：
 * 少收几个应用可以后面再加，多收了会把误报放进来。
 */
class WatchedAppsTest {

    @Test
    fun `微信和QQ 的通知会被分析`() {
        assertTrue(WatchedApps.isWatched("com.tencent.mm"))
        assertTrue(WatchedApps.isWatched("com.tencent.mobileqq"))
    }

    @Test
    fun `TIM 是 QQ 办公版，一并放行`() {
        // 腾讯官方独立包名，通知结构跟 QQ 完全一样
        assertTrue(WatchedApps.isWatched("com.tencent.tim"))
    }

    @Test
    fun `系统包一律不看`() {
        // 之前是黑名单，只挡这四个；现在白名单让它们天然被排除
        assertFalse(WatchedApps.isWatched("android"))
        assertFalse(WatchedApps.isWatched("com.android.systemui"))
        assertFalse(WatchedApps.isWatched("com.android.settings"))
        assertFalse(WatchedApps.isWatched("com.google.android.gms"))
    }

    @Test
    fun `短信邮件日历不再分析`() {
        // 这是收窄范围的直接代价，写进测试是为了以后不会"顺手"放宽
        assertFalse(WatchedApps.isWatched("com.android.mms"))
        assertFalse(WatchedApps.isWatched("com.google.android.gm"))
        assertFalse(WatchedApps.isWatched("com.android.calendar"))
    }

    @Test
    fun `其他腾讯系应用不自动放行`() {
        // 企业微信、QQ音乐、腾讯视频、QQ邮箱 —— 名字里都有 tencent，但都不是聊天通知源
        assertFalse(WatchedApps.isWatched("com.tencent.wework"))
        assertFalse(WatchedApps.isWatched("com.tencent.qqmusic"))
        assertFalse(WatchedApps.isWatched("com.tencent.qqlive"))
        assertFalse(WatchedApps.isWatched("com.tencent.androidqqmail"))
    }

    @Test
    fun `必须是精确匹配，前缀相同不算`() {
        // 如果有人把 containsKey 改成 startsWith，任何一个
        // com.tencent.mm.xxx 的包都能过关 —— 这条路必须堵死
        assertFalse(WatchedApps.isWatched("com.tencent.mm.fake"))
        assertFalse(WatchedApps.isWatched("com.tencent.mobileqq.test"))
        assertFalse(WatchedApps.isWatched("com.tencent.mm "))
        assertFalse(WatchedApps.isWatched(""))
    }

    @Test
    fun `展示名是中文，可直接进日志`() {
        assertEquals("微信", WatchedApps.displayName("com.tencent.mm"))
        assertEquals("QQ", WatchedApps.displayName("com.tencent.mobileqq"))
        assertNull("不在白名单里没有展示名", WatchedApps.displayName("com.android.mms"))
    }

    @Test
    fun `summary 覆盖全部放行应用`() {
        val s = WatchedApps.summary()
        assertTrue(s.contains("微信"))
        assertTrue(s.contains("QQ"))
        // 数量对得上 —— 加了新包名忘了改 summary 的话这里会挂
        assertEquals(WatchedApps.packages().size, s.split("、").size)
    }
}
