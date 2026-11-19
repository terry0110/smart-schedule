package com.terry.smartschedule.notify

/**
 * 从通知的正文里挑出候选段。
 *
 * ## 一条硬规则：**标题不参与识别**
 *
 * 微信 / QQ 的 `EXTRA_TITLE` 和 `EXTRA_CONVERSATION_TITLE` 语义固定是**会话名**
 * （群名、联系人昵称、有时干脆是 QQ 号）。它从来不是内容，所以这个函数**连收都不收**。
 *
 * 以前是"分辨哪些标题算内容、哪些不算"，为此叠了三轮规则：纯账号 → 会话名 →
 * 纯日期标签（群名 `12.31`）。每轮都是判完再扔，判错的代价很实在：
 *
 * ```
 * 标题「12.31」（群名） + 正文「明天下午3点开会」
 *   → 建成 2026-12-31 15:00，置信度 90，直接自动入库
 * ```
 *
 * 正文明明写着"明天下午3点"，日期被群名抢走。日程建出来了、时分也合法，
 * 只有用户自己记得原文才看得出错。
 *
 * 现在改成**不收标题** —— 判断再错也不会错到这一步，因为那个字段根本进不来。
 *
 * 为什么敢这么砍：能走到这里的通知全部来自微信 / QQ / TIM（见 [WatchedApps]），
 * 这几个应用的标题永远是会话名。别人家的通知标题可能是内容，但我们不看别人家的。
 *
 * ## 代价
 *
 * 只有标题有内容、正文全空的通知会解析不出来。微信/QQ 不存在这种通知 ——
 * 真正的消息一定在 `EXTRA_MESSAGES` 或 `EXTRA_TEXT` 里。
 */
internal object SegmentPicker {

    /**
     * @param bodies   `EXTRA_TEXT` / `EXTRA_BIG_TEXT` / `EXTRA_SUB_TEXT` / `EXTRA_SUMMARY_TEXT`
     * @param lines    `EXTRA_TEXT_LINES`（多行展开）
     * @param messages MessagingStyle 的消息，已拼成 `发送者：内容`
     */
    fun pick(
        bodies: List<String?>,
        lines: List<String?>,
        messages: List<String>
    ): List<String> {
        val out = LinkedHashSet<String>()

        // 1) MessagingStyle 的消息体 —— 这才是内容本体
        messages.forEach { m -> m.trim().takeIf { it.isNotEmpty() }?.let { out.add(it) } }

        // 2) 普通正文段
        bodies.forEach { b -> b?.trim()?.takeIf { isUsableBody(it) }?.let { out.add(it) } }

        // 3) 多行文本
        lines.forEach { l -> l?.trim()?.takeIf { isUsableBody(it) }?.let { out.add(it) } }

        return out.toList()
    }

    /**
     * 正文段能不能用。
     *
     * 只排除**纯账号**（`123456789`、`wxid_xxx`）—— 微信有时把对方微信号放进正文位置。
     *
     * **不**排除纯日期/纯时间。理由：正文是用户自己敲的字，「明天下午3点」单独发出来
     * 虽然只有时间、没写要做什么，但它就是个待办（省略了动词）。按"只有时间"过滤掉
     * 会漏真实日程。
     */
    private fun isUsableBody(text: String): Boolean =
        text.isNotEmpty() && !TextFilter.looksLikeSenderId(text)
}
