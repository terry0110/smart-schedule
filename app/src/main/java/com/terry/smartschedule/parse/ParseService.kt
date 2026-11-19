package com.terry.smartschedule.parse

/**
 * 解析调度：**纯本地**，不联网、不上传。
 *
 * 这里曾经有一个"本地规则不够就调云端大模型"的兜底分支，已经整段删掉了。
 * 删的理由不是"做不出来"，而是它把产品最硬的卖点（数据不出手机）变成了
 * 一句需要加限定条件的空话 —— 只要留着一个可选的联网出口，
 * "纯本地"就只能写成"默认纯本地"。现在应用连 INTERNET 权限都没有，
 * 这一点可以直接验证（见 AndroidManifest）。
 *
 * 本地解析的能力边界说清楚：它认时间、地点、标题这几类要素，
 * 认不出的会进"待确认"让用户补一句，而不是硬编一条错的日程出来。
 */
object ParseService {

    /** 达到这个分才允许"无人值守"自动入库（通知监听用） */
    const val AUTO_THRESHOLD = 85

    /** 低于这个分基本是噪声，界面上也不建议保存 */
    const val MIN_THRESHOLD = 40

    /** 识别一段文本。识别不出返回 null，界面上要明确告诉用户"没找到时间" */
    fun parse(raw: String): DateTimeParser.Result? = DateTimeParser.parse(raw)

    /** 通知来了先做一次"值不值得处理"的筛查（和 [parse] 同一套规则，留个名字给调用点读） */
    fun quickParse(raw: String): DateTimeParser.Result? = DateTimeParser.parse(raw)
}
