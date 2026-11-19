package com.terry.smartschedule.data

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.terry.smartschedule.R

/**
 * 日程分类。
 *
 * 为什么是这五类：这个应用的用户场景（学生 / 刚工作）里，日程几乎都落在
 * "课业、工作、生活、社团活动"四块，剩下归"其他"。再细分（比如把考试从学习里
 * 拆出来）筛选项会多到没人用，而分类的价值在**一眼扫出来这周哪类事最多**。
 *
 * `key` 是存进数据库/跨版本稳定标识，**只能新增、不能改名**（改名等于把
 * 老用户的分类洗掉）；展示名走资源，随时能改。
 */
enum class Category(
    val key: String,
    @StringRes val labelRes: Int,
    @ColorRes val colorRes: Int
) {
    STUDY("study", R.string.cat_study, R.color.cat_study),
    WORK("work", R.string.cat_work, R.color.cat_work),
    LIFE("life", R.string.cat_life, R.color.cat_life),
    CLUB("club", R.string.cat_club, R.color.cat_club),
    OTHER("other", R.string.cat_other, R.color.cat_other);

    companion object {
        /** 未知值一律退到"其他"：旧数据、脏数据都不该让界面空一块 */
        fun of(key: String?): Category = entries.firstOrNull { it.key == key } ?: OTHER
    }
}
