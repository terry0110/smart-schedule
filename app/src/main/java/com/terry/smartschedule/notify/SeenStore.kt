package com.terry.smartschedule.notify

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.terry.smartschedule.data.EventDb
import com.terry.smartschedule.data.Repo

/**
 * 「这条内容处理过没有」的持久化台账，落在 schedule.db 的 `msg_seen` 表。
 *
 * 为什么必须持久化：
 * 旧实现在服务进程内存里放一张 `LinkedHashMap` 做 10 分钟窗口。问题是
 * `NotificationListenerService` 会被系统随时回收 —— 息屏久了、内存紧张、
 * 重启手机，进程一死表就空了。系统重连时会**重新回调通知栏里已有的通知**，
 * 于是同一条消息被重新解析、重新建了一条日程。用户看到的就是"莫名其妙多了
 * 一条一模一样的"。
 *
 * 为什么用"插入即判重"：
 * 先 SELECT 再 INSERT 中间有窗口，两条并发的相同通知会双双通过检查。
 * SQLite 的写入是串行的，主键冲突就是天然的互斥：插入成功 = 我第一个到，
 * 插入返回 -1 = 别人已经处理过。一次写操作同时完成"判重 + 记账"。
 *
 * 保留期 12 小时：同一条通知的重复回调集中在几分钟内，12 小时足够；
 * 留太长会把"下周同一时间"的正当重发也误杀。行数 2000 兜底，
 * 防止被通知轰炸时表无界增长。
 */
object SeenStore {

    private const val TTL_MS = 12 * 60 * 60 * 1000L
    private const val MAX_ROWS = 2000

    /** 一条通知最多带 4 段消息，留点余量 */
    private const val MAX_SIGS = 6

    /**
     * 认领这批指纹。
     *
     * @return null = 全新内容（已记账，可以继续处理）；
     *         非 null = 命中旧记录，值是**上次处理的时间**（毫秒），调用方据此告诉用户"多久以前见过"
     */
    fun claim(
        app: String,
        sigs: Collection<String>,
        now: Long = System.currentTimeMillis()
    ): Long? {
        val list = sigs.filter { it.isNotBlank() }.distinct().take(MAX_SIGS)
        if (list.isEmpty()) return null

        val db = runCatching { Repo.writable() }.getOrNull() ?: return null
        var hitAt: Long? = null

        runCatching {
            db.beginTransaction()
            try {
                for (sig in list) {
                    val cv = ContentValues().apply {
                        put("sig", sig)
                        put("app", app.take(20))
                        put("at", now)
                    }
                    val row = db.insertWithOnConflict(
                        EventDb.S_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE
                    )
                    if (row != -1L) continue

                    // 冲突：看看旧记录多老。过期的直接续期当作新的
                    val old = db.query(
                        EventDb.S_TABLE, arrayOf("at"), "sig=?", arrayOf(sig),
                        null, null, null
                    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: continue

                    if (now - old < TTL_MS) {
                        hitAt = minOf(hitAt ?: Long.MAX_VALUE, old)
                    } else {
                        db.update(EventDb.S_TABLE, cv, "sig=?", arrayOf(sig))
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        if (hitAt == null) runCatching { trim(db, now) }
        return hitAt
    }

    /**
     * 只查不写：这个指纹在保留期内处理过吗？
     *
     * @return 上次处理时间；没处理过或已过期返回 null
     */
    fun seenAt(sig: String, now: Long = System.currentTimeMillis()): Long? {
        if (sig.isBlank()) return null
        val db = runCatching { Repo.writable() }.getOrNull() ?: return null
        return runCatching {
            db.query(
                EventDb.S_TABLE, arrayOf("at"), "sig=?", arrayOf(sig),
                null, null, null
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()?.takeIf { now - it < TTL_MS }
    }

    /** 清空台账。「删除所有本地数据」时调用，否则清完库再抓同一条会判成重复 */
    fun clear() {
        runCatching { Repo.writable().delete(EventDb.S_TABLE, null, null) }
    }

    /** 过期清理 + 行数兜底。有 at 索引，两条都是毫秒级 */
    private fun trim(db: SQLiteDatabase, now: Long) {
        db.delete(EventDb.S_TABLE, "at < ?", arrayOf((now - TTL_MS).toString()))
        db.execSQL(
            "DELETE FROM ${EventDb.S_TABLE} WHERE sig NOT IN " +
                "(SELECT sig FROM ${EventDb.S_TABLE} ORDER BY at DESC LIMIT $MAX_ROWS)"
        )
    }

    /** 给日志用："3 分钟前" / "2 小时前" */
    fun agoLabel(at: Long, now: Long = System.currentTimeMillis()): String {
        val d = (now - at).coerceAtLeast(0)
        return when {
            d < 60_000 -> "${d / 1000} 秒前"
            d < 3_600_000 -> "${d / 60_000} 分钟前"
            else -> "${d / 3_600_000} 小时前"
        }
    }
}
