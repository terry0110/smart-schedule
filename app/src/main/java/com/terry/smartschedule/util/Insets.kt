package com.terry.smartschedule.util

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 状态栏 / 导航栏避让。
 *
 * 背景：targetSdk 35 在 Android 15 上强制 edge-to-edge，`android:statusBarColor` 已经失效，
 * 内容会被画到状态栏底下——这就是界面顶部和状态栏重叠的原因。
 * 正确做法不是往主题里塞颜色，而是主动读 WindowInsets 把内容顶下来。
 */
object Insets {

    /** 打开全屏绘制模式：让渐变头部真正铺到屏幕最顶端，再由各页面自己避让 */
    fun edgeToEdge(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    }

    /**
     * 把状态栏高度写进一个占位 View 的高度。
     * 用占位而不是直接改头部 padding：不会覆盖布局里已经写好的其它 padding。
     */
    fun statusBarSpacer(activity: Activity, root: View, spacer: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (spacer.layoutParams.height != top) {
                spacer.layoutParams = spacer.layoutParams.also { it.height = top }
            }
            insets
        }
        requestApply(activity, root)
    }

    /** 给可滚动列表底部加上导航栏高度，避免最后一条被手势条盖住 */
    fun bottomInsetPadding(activity: Activity, root: View, target: View, extraBottomDp: Int) {
        val density = activity.resources.displayMetrics.density
        val extra = (extraBottomDp * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            target.updatePadding(bottom = extra + bottom)
            insets
        }
        requestApply(activity, root)
    }

    /** 一次性把状态栏占位和底部避让都挂到同一个 root 上，避免两个 listener 互相覆盖 */
    fun apply(
        activity: Activity,
        root: View,
        statusBarSpacer: View?,
        bottomTarget: View?,
        bottomExtraDp: Int = 0
    ) {
        val density = activity.resources.displayMetrics.density
        val extra = (bottomExtraDp * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarSpacer?.let { sp ->
                if (sp.layoutParams.height != bars.top) {
                    sp.layoutParams = (sp.layoutParams as ViewGroup.MarginLayoutParams).also {
                        it.height = bars.top
                    }
                }
            }
            bottomTarget?.let { t ->
                t.updatePadding(bottom = extra + bars.bottom)
            }
            insets
        }
        requestApply(activity, root)
    }

    private fun requestApply(activity: Activity, root: View) {
        // 主题切换 / 从设置返回时 insets 不会自动重发，主动要一次
        root.requestApplyInsets()
        activity.window.decorView.requestApplyInsets()
    }
}
