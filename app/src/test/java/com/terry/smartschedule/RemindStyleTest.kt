package com.terry.smartschedule

import com.terry.smartschedule.notify.RemindStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒方式的三档选择。
 *
 * 这组测试守的是两件事：
 *  1. **只有"全屏"这一档会打断当前应用**。用户选横幅的动机就是"别盖我游戏"，
 *     这个契约不能因为以后谁顺手改一行就破了。
 *  2. **存下来的数字不能因为脏数据把提醒弄哑**。落到不认识的值时退回全屏，
 *     而不是变成"什么都不提醒"——后者用户根本发现不了。
 */
class RemindStyleTest {

    @Test
    fun `各档的存储值必须稳定`() {
        // 存进 SharedPreferences / 数据库的是这个数字，改动等于把老用户的设置洗掉
        assertEquals(0, RemindStyle.FULLSCREEN.value)
        assertEquals(1, RemindStyle.BANNER.value)
        assertEquals(2, RemindStyle.SILENT.value)
        // 3 是后来加的「振动」。只能往后加，不能插在中间 —— 插进去就把旧的静音变成振动
        assertEquals(3, RemindStyle.VIBRATE.value)
    }

    @Test
    fun `按存储值还原`() {
        assertEquals(RemindStyle.FULLSCREEN, RemindStyle.from(0))
        assertEquals(RemindStyle.BANNER, RemindStyle.from(1))
        assertEquals(RemindStyle.SILENT, RemindStyle.from(2))
        assertEquals(RemindStyle.VIBRATE, RemindStyle.from(3))
    }

    @Test
    fun `脏数据退回全屏而不是静默`() {
        // 退回静默的话，用户会以为应用坏了（到点一声不响）；退回全屏至少还叫得醒
        assertEquals(RemindStyle.FULLSCREEN, RemindStyle.from(-1))
        assertEquals(RemindStyle.FULLSCREEN, RemindStyle.from(99))
    }

    @Test
    fun `只有全屏会盖住当前应用`() {
        assertTrue(RemindStyle.FULLSCREEN.popsFullScreen)
        // 这几条是关键：选横幅 / 振动的人就是在躲这个
        assertFalse(RemindStyle.BANNER.popsFullScreen)
        assertFalse(RemindStyle.VIBRATE.popsFullScreen)
        assertFalse(RemindStyle.SILENT.popsFullScreen)
    }

    @Test
    fun `振动档不静音但也不响铃`() {
        // 它既不是"静音"（还得震）也不是"响铃"（不能出声），是第三态：
        // 落到 quiet 就会被塞进无声渠道，震也一起没了
        assertFalse(RemindStyle.VIBRATE.quiet)
        assertTrue(RemindStyle.VIBRATE.vibrateOnly)
        // 其它三档都不是它
        assertFalse(RemindStyle.FULLSCREEN.vibrateOnly)
        assertFalse(RemindStyle.BANNER.vibrateOnly)
        assertFalse(RemindStyle.SILENT.vibrateOnly)
    }

    @Test
    fun `只有静音不响不震`() {
        assertFalse(RemindStyle.FULLSCREEN.quiet)
        // 横幅照样要响要震，只是不盖屏幕
        assertFalse(RemindStyle.BANNER.quiet)
        assertTrue(RemindStyle.SILENT.quiet)
    }

    @Test
    fun `单条优先于全局`() {
        // 日程自己选了就用它选的，设置里的默认值是啥都不看
        assertEquals(RemindStyle.SILENT, RemindStyle.resolve(2, 0))
        assertEquals(RemindStyle.FULLSCREEN, RemindStyle.resolve(0, 2))
        assertEquals(RemindStyle.VIBRATE, RemindStyle.resolve(3, 0))
    }

    @Test
    fun `没选就退回全局默认`() {
        assertEquals(RemindStyle.BANNER, RemindStyle.resolve(RemindStyle.FOLLOW_DEFAULT, 1))
    }

    @Test
    fun `单条脏数据退回全局而不是全屏`() {
        // 单条的值只在 >= 0 时才算"选了"；-2、99 这种脏值和没选一样走默认，
        // 否则一条坏数据能把提醒的档位整个甩开
        assertEquals(RemindStyle.BANNER, RemindStyle.resolve(-2, 1))
        assertEquals(RemindStyle.BANNER, RemindStyle.resolve(99, 1))
    }
}
