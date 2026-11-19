package com.terry.smartschedule

import com.terry.smartschedule.notify.TextFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 营销 / 资费类推送不建日程。
 *
 * 真机样本：「小王同学 🔥 王者荣耀2026周年庆，10月24日登录游戏领好礼！【登录就送】」
 * 里确实有"10月24日"，但这是广告，不是要你去做什么事。
 *
 * 这类判断放在正文上跑 —— 标题已经不参与识别了（见 [TitleNotUsedTest]）。
 */
class PromoFilterTest {

    @Test
    fun `游戏营销推送不算日程`() {
        assertTrue(
            TextFilter.isNonScheduleNotice(
                "小王同学 🔥 王者荣耀2026周年庆，10月24日登录游戏领好礼！🎁【登录就送】10.24登录"
            )
        )
        assertTrue(TextFilter.isNonScheduleNotice("双11 限时抢，点击领取优惠券"))
        assertTrue(TextFilter.isNonScheduleNotice("会员日 满减 折上折"))
    }

    @Test
    fun `含明确时间的营销词仍按日程处理`() {
        // 「明天下午3点参加抽奖活动」是行程，不能因为含促销词就丢
        assertFalse(TextFilter.isNonScheduleNotice("明天下午3点参加抽奖活动"))
        assertFalse(TextFilter.isNonScheduleNotice("周六上午10点去领优惠券"))
    }
}
