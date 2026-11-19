package com.terry.smartschedule.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat
import com.terry.smartschedule.R
import com.terry.smartschedule.data.EventSearch

/**
 * 把命中的词在结果里标出来。
 *
 * 为什么值得做：只做过滤的话，用户看到一条"开会"却不知道它为什么被搜出来
 * —— 可能命中的是原文里一个不显眼的词。标出来就一眼看明白，尤其是
 * 原文命中（标题里根本没这个词）的情况。
 *
 * 用品牌蓝 + 加粗，不再加底色：列表卡片本身是四层玻璃，再加一层色块会脏。
 */
object SearchHighlight {

    fun mark(context: Context, text: CharSequence, tokens: List<String>): CharSequence {
        if (tokens.isEmpty() || text.isEmpty()) return text
        val ranges = EventSearch.ranges(text.toString(), tokens)
        if (ranges.isEmpty()) return text

        val sp = SpannableString(text)
        val color = ContextCompat.getColor(context, R.color.brand_dark)
        for (r in ranges) {
            // 区间可能因为 emoji 之类的代理对落在字符中间，夹一下更安全
            val start = r.first.coerceIn(0, sp.length)
            val end = (r.last + 1).coerceIn(start, sp.length)
            if (start == end) continue
            sp.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(
                StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sp
    }

    /** 这段文字里有没有命中。用来判断"标题/地点都没中，命中在原文里" */
    fun hits(text: CharSequence?, tokens: List<String>): Boolean =
        !text.isNullOrEmpty() && tokens.isNotEmpty() &&
            EventSearch.ranges(text.toString(), tokens).isNotEmpty()

    /**
     * 从原文里裁一段命中附近的文字，给列表当"为什么这条被搜出来"的证据。
     *
     * 没有它的话，命中在原文里的日程在列表上看起来毫无变化（标题地点都是灰的），
     * 用户会怀疑是不是搜错了。
     *
     * @return 带省略号的片段；没命中返回 null
     */
    fun snippet(text: String, tokens: List<String>, before: Int = 8, after: Int = 22): String? {
        val ranges = EventSearch.ranges(text, tokens)
        if (ranges.isEmpty()) return null
        val r = ranges.first()
        val start = (r.first - before).coerceAtLeast(0)
        val end = (r.last + 1 + after).coerceAtMost(text.length)
        // 原文里常有换行，摊平成一行再接进 meta，否则卡片高度会突然变
        val body = text.substring(start, end).replace('\n', ' ').trim()
        return (if (start > 0) "…" else "") + body + (if (end < text.length) "…" else "")
    }
}
