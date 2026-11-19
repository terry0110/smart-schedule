package com.terry.smartschedule.notify

import com.terry.smartschedule.parse.TextNorm
import java.security.MessageDigest

/**
 * 通知内容的指纹，用于「同一条消息不重复处理」。
 *
 * 为什么不直接拿 [TextNorm.key] 当键：
 * 一条长通告归一化后能有 200 个汉字，存进 SQLite 的主键会让索引变大、
 * 比对变慢；而判重根本用不到原文。SHA-256 取前 16 个十六进制字符（64 bit）
 * 已经足够 —— 按每天 200 条算，撞车概率在宇宙寿命内可以忽略。
 *
 * 为什么不用 `String.hashCode()`：32 bit、且已有大量已知碰撞样本，
 * 两条不同的通知被判成同一条，用户会"丢一条日程"，这比重复建一条更严重。
 */
object MsgSig {

    private const val HEX = "0123456789abcdef"

    /** 取 16 个 hex 字符 = 8 字节 */
    private const val CHARS = 16

    /**
     * 内容指纹。
     *
     * @param scope 判重的范围维度，正常传**会话名**（群名/昵称）。判重只在同一会话内比对：
     *              班级群和社团群发同一条"明天下午3点开会"，是两件事，
     *              不能因为文字一样就吞掉一条 —— 会话要素不一样。
     *              会话名拿不到时传空串（退化为"整个应用范围内"判重，和旧版行为一致）。
     * @return 16 字符指纹；文本里没有任何有效字符（纯 emoji / 纯标点）时返回空串，
     *         调用方应把空串当作"无法判重"，不要入库
     */
    fun of(text: String, scope: String = ""): String {
        val key = TextNorm.key(text)
        if (key.isEmpty()) return ""
        // 会话名也归一化：同一群名有时带表情有时不带，不该变成两个范围
        val scopeKey = TextNorm.key(scope)
        val material = if (scopeKey.isEmpty()) key else "$scopeKey\u0000$key"
        val d = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(CHARS)
        for (i in 0 until CHARS / 2) {
            val b = d[i].toInt()
            sb.append(HEX[(b shr 4) and 0xF]).append(HEX[b and 0xF])
        }
        return sb.toString()
    }
}
