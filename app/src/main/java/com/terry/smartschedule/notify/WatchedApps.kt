package com.terry.smartschedule.notify

/**
 * 允许分析的应用**白名单**。
 *
 * 之前是黑名单（只排除 android / systemui / settings / gms），等于所有第三方应用
 * 都在往解析器里灌：新闻推送、电商促销、游戏活动、运营商账单…… 这些文本里
 * "10月24日""8.5""3.15" 之类的数字太密集，光靠解析器的规则挡不完 ——
 * 真机上反复出现的误报，源头都在这里。
 *
 * 改成白名单后逻辑变简单：**不在名单里的一律不看**，连正则都不跑。
 * 代价是短信、邮件、日历里的日程也抓不到了 —— 这是有意的取舍：
 * 少记一条可以手动补，列表被噪音填满就没法用了。
 *
 * 加应用只需往 [APPS] 里加一行。判断逻辑是纯 Kotlin，有单测。
 */
object WatchedApps {

    /**
     * 包名 -> 展示名。用 LinkedHashMap 保证设置页展示顺序稳定。
     *
     * TIM 是 QQ 的办公版（腾讯官方独立包名），通知结构和 QQ 完全一样，
     * 所以一并放行 —— 没装的话不会有任何影响。
     */
    private val APPS: Map<String, String> = linkedMapOf(
        "com.tencent.mm" to "微信",
        "com.tencent.mobileqq" to "QQ",
        "com.tencent.tim" to "TIM"
    )

    /** 这个包名的通知要不要分析 */
    fun isWatched(pkg: String): Boolean = APPS.containsKey(pkg)

    /** 展示名；不在白名单里返回 null */
    fun displayName(pkg: String): String? = APPS[pkg]

    /** 给界面用的一句话，例如「微信、QQ、TIM」 */
    fun summary(): String = APPS.values.joinToString("、")

    /** 全部包名，测试和调试用 */
    fun packages(): Set<String> = APPS.keys
}
