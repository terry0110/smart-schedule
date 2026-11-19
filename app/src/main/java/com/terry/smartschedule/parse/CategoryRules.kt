package com.terry.smartschedule.parse

import com.terry.smartschedule.data.Category

/**
 * 本地规则分类器：从日程文字判断它属于哪一类。
 *
 * 为什么不上模型：这是 5 选 1 的分类，关键词命中的准确率已经够用，
 * 而且必然离线、耗时微秒级、结果可解释（能告诉用户"因为出现'答辩'"）。
 * 拿几 MB 的模型换这点提升不值得，还会让"提醒到点"依赖推理速度。
 *
 * 打分规则：命中一个词得 **词长** 分。词越长越具体 —— "宣传部"（3 分）
 * 比 "活动"（2 分）更能说明这是社团的事，"高数"（2 分）比 "课"（1 分）更像学业。
 * 同分时按 [RULES] 顺序取先出现的（学习 > 社团 > 工作 > 生活）：
 * "社团例会"这种两边都沾的，归社团更符合当事人的直觉 ——
 * 对他来说这是"社团的事"，不是"工作"。
 *
 * 注意：只用于**猜测**。用户在面板里手动选过的，一律以用户为准。
 */
object CategoryRules {

    private val RULES: List<Pair<Category, List<String>>> = listOf(
        Category.STUDY to listOf(
            "作业", "考试", "补考", "期中", "期末", "上课", "课程", "网课",
            "讲座", "论文", "答辩", "实验", "复习", "自习", "学分", "选课",
            "高数", "线代", "四级", "六级", "毕设", "课题", "课设", "培训"
        ),
        Category.CLUB to listOf(
            "社团", "协会", "学生会", "团委", "宣传部", "招新", "晚会",
            "排练", "演出", "志愿者", "社联", "青协", "训练", "彩排"
        ),
        Category.WORK to listOf(
            "开会", "会议", "例会", "汇报", "项目", "客户", "报销", "面试",
            "实习", "加班", "值班", "领导", "述职", "周报", "月报", "甲方",
            "上班", "打卡", "需求评审", "方案评审", "工期"
        ),
        Category.LIFE to listOf(
            "快递", "取件", "医院", "体检", "挂号", "看病", "缴费", "交费",
            "房租", "水电", "聚餐", "吃饭", "生日", "旅行", "车票", "机票",
            "航班", "银行", "理发", "搬家", "超市", "健身", "运动", "跑步"
        )
    )

    /**
     * @return 最像的那一类；完全没有命中时返回 [Category.OTHER]（"其他"）
     */
    fun classify(text: String): Category {
        if (text.isBlank()) return Category.OTHER
        var best = Category.OTHER
        var bestScore = 0
        for ((cat, words) in RULES) {
            var score = 0
            for (w in words) if (text.contains(w)) score += w.length
            // 严格大于：同分保留先出现的规则（顺序即优先级）
            if (score > bestScore) {
                bestScore = score
                best = cat
            }
        }
        return best
    }

    /**
     * 给界面用：命中这个词所以分到了这类。没命中返回 null（= "没看出类别"）。
     * 用户问"为什么这是学习"时要答得出来，不能只说"算法判的"。
     */
    fun reason(text: String): String? {
        val cat = classify(text)
        if (cat == Category.OTHER) return null
        val words = RULES.firstOrNull { it.first == cat }?.second ?: return null
        return words.filter { text.contains(it) }.maxByOrNull { it.length }
    }
}
