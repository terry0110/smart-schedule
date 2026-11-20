package com.terry.smartschedule.data

/** 一条日程 */
data class Event(
    val id: Long = 0L,
    val title: String,
    /** 开始时间戳（毫秒） */
    val startAt: Long,
    /** false 表示全天（只有日期没有具体时间） */
    val hasTime: Boolean = true,
    val location: String? = null,
    /** 原始文本，方便回溯"这条是怎么来的" */
    val note: String? = null,
    /** manual / notify / llm */
    val source: String = SRC_MANUAL,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** 提前多少分钟提醒；-1 表示不提醒 */
    val remindMinutes: Int = 10,
    /**
     * 这条日程自己的通知方式；-1 表示"跟随设置里的默认通知方式"。
     * 取值含义见 [com.terry.smartschedule.notify.RemindStyle]：0 全屏 / 1 横幅 / 2 静音。
     * 每条独立选是因为打扰程度是按"这件事重不重要"定的，不该被全局设置一刀切。
     */
    val remindStyle: Int = -1,
    /**
     * true 表示"自动识别出来但没把握，等用户确认"。
     * 这类日程不排提醒、以提示样式显示，用户点确认后才算数。
     */
    val pending: Boolean = false,
    /** 待确认的原因，直接展示给用户看（为什么我觉得这条不靠谱） */
    val pendingReason: String? = null,
    /**
     * 分类，存 [Category.key]。默认“其他”——旧数据、手建的、看不出类别的都落这里。
     * 存字符串而不是序号：将来插新分类不会把老数据读错（和 RemindStyle 相反，
     * 那是个位数组，只能往后追加）。
     */
    val category: String = Category.OTHER.key,
    /**
     * 系统日历里对应那一行的 `_id`；0 表示还没同步过去（或同步开关关着）。
     *
     * 单向映射，只由 [Repo] 写。放在这里是为了编辑/删除时知道该改哪一行 ——
     * 靠标题去日历里反查是不行的，同名日程很常见。
     */
    val calEventId: Long = 0L,
    /**
     * true 表示「后来的通知说这条取消了」。
     *
     * 只标记、不删除：群里那句"取消了"可能是另一件事，也可能是玩笑。
     * 删错了找不回来，标错了点一下就没 —— 代价不对称，所以选标记。
     * 这类日程不再排提醒（见 [com.terry.smartschedule.notify.ReminderPlan]）。
     */
    val revoked: Boolean = false,
    /** 取消理由，含原文片段，直接展示给用户判断 */
    val revokeReason: String? = null,
    /**
     * true = 已归档：主列表不再显示、不再排提醒，在「归档日志」里可以恢复。
     *
     * 和 done 的区别：done 是"这件事做完了"，归档是"把它从眼前收起来"。
     * 老数据里勾过"已结束"的，升级时统一搬成归档。
     */
    val archived: Boolean = false,
    /**
     * 结束时间；0 表示原文没写结束时间。
     *
     * 冲突检测看的是"整段时间"，所以带区间的通知（"下午3点-5点"）要把它存下来。
     * 0 不是"全天"——全天看 [hasTime]。带钟点但没有结束时，冲突检测按"开始到当天结束"兜底。
     */
    val endAt: Long = 0L
) {
    companion object {
        const val SRC_MANUAL = "manual"
        const val SRC_NOTIFY = "notify"
        /** 早期版本走云端大模型建的日程。现在已无此路径，仅用于把老数据显示成人话 */
        const val SRC_LLM = "llm"
    }
}
