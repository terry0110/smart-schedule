package com.terry.smartschedule.notify

import com.terry.smartschedule.parse.DateTimeParser

/**
 * 通知抓取的最后一道门：什么样的解析结果**根本不该建**。
 *
 * 跟 `TextFilter`（拦营销、资费、文件类通知）不是一回事：那是看**原文**，
 * 这是看**解析结果**。有些文本本身很正常，是解析出来的东西不可靠。
 *
 * 单独抽出来的理由和 `ReminderPlan` 一样：判断逻辑纯计算才能测，
 * 而这类"该不该建"的规则一旦漏，表现是列表里多一条垃圾，没人会去查代码。
 */
object CapturePolicy {

    /**
     * @return 非空表示不该建，内容是给处理日志看的原因
     */
    fun rejectReason(r: DateTimeParser.Result): String? = when (r.dateSource) {
        // 只有时间、没有日期。典型：群里一句「五点去吃饭」「三点开会」。
        //
        // 这类**必须排除**，因为日期纯靠猜（今天还是明天？过了就明天），
        // 猜中的概率很低，而建错的日程比没建更糟 —— 用户会信任列表里的时间。
        // 真要知道哪天，原文一定会给线索（今天/明天/周三/10.25），给了就不是 TIME_ONLY。
        DateTimeParser.DateSource.TIME_ONLY -> "只有时间没有日期，不确定是哪天，不建"

        else -> null
    }
}
