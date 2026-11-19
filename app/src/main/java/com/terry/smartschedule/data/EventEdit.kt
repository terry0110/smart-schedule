package com.terry.smartschedule.data

/**
 * 编辑日程时那些"用户看不见、但会决定结果对不对"的判断。
 *
 * 单独抽出来纯粹是为了能写单元测试 —— 这些逻辑一旦错了，表现是
 * "我明明只改了个错别字，它怎么变回未完成了"，在真机上很难第一时间发现。
 */
object EventEdit {

    /**
     * 编辑后要不要把「已完成」重置成未完成。
     *
     * 只在**时间真的变了**时重置：一条日程被推迟到另一个时刻，说明这件事还没发生，
     * 原来那个勾已经失效了。
     * 只改标题或地点时保持原状态 —— 否则改个错别字就把它变回未完成，很反直觉。
     */
    fun shouldResetDone(
        oldStartAt: Long,
        oldHasTime: Boolean,
        newStartAt: Long,
        newHasTime: Boolean
    ): Boolean = oldStartAt != newStartAt || oldHasTime != newHasTime
}
