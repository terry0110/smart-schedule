package com.terry.smartschedule.notify

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.terry.smartschedule.data.EventDb
import com.terry.smartschedule.data.Repo
import com.terry.smartschedule.parse.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条通知的处理结果，给用户看"刚才到底发生了什么" */
data class CaptureRecord(
    /** 处理时间 */
    val at: Long,
    /** 来源应用，如 微信 */
    val app: String,
    /** 通知原文（已截断；默认不存，见 [CaptureLog.record]） */
    val text: String,
    /**
     * created  = 已自动建日程
     * pending  = 识别出来了但没把握，进待确认
     * skipped  = 看过但没建（没时间/重复/太短）
     */
    val outcome: String,
    /** 一句话说明为什么是这个结果 */
    val detail: String
) {
    val timeLabel: String
        get() = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(at))

    val outcomeLabel: String
        get() = when (outcome) {
            OUT_CREATED -> "已建日程"
            OUT_PENDING -> "待确认"
            else -> "已跳过"
        }

    companion object {
        const val OUT_CREATED = "created"
        const val OUT_PENDING = "pending"
        const val OUT_SKIPPED = "skipped"
    }
}

/**
 * 最近的通知处理轨迹，落在 schedule.db 的 capture_log 表里。
 *
 * 存在的意义：之前"识别不到"最大的问题是**静默丢弃**——通知来了，应用什么都没做，
 * 用户完全不知道是没收到、没识别出时间、还是被当成重复丢了。
 * 现在每条通知都会留一条记录，主界面能直接看到。
 *
 * **为什么不是 SharedPreferences**（第一版踩的设计坑）：
 * SP 是"整个 XML 一次性重写"，没有事务没有索引。日志才几 KB 时没感觉，
 * 攒到几 MB 后每次写入都是全量序列化 + 重写文件，主线程提交会掉帧——
 * 应用自己成为卡顿源。进 SQLite 后：带索引的单行 INSERT，写入是常量成本，
 * 清理用一条 DELETE，不再需要把整个文件读进内存。
 */
object CaptureLog {

    private const val TABLE = EventDb.C_TABLE
    private const val COL_ID = "_id"
    private const val COL_AT = "at"
    private const val COL_APP = "app"
    private const val COL_OUTCOME = "outcome"
    private const val COL_DETAIL = "detail"
    private const val COL_RAW = "raw"

    /** 旧版 SP 存储的文件名/键名，只用于一次性搬迁 */
    private const val LEGACY_FILE = "capture_log"
    private const val LEGACY_KEY = "records"

    /** 流水只留 24 小时：它是"刚才发生了什么"的排障工具，不是存档 */
    private const val TTL_MS = 24 * 60 * 60 * 1000L

    /** 行数兜底：就算 24 小时内被通知轰炸，库也不会无限涨 */
    private const val MAX_ROWS = 500

    /** 页面一次最多显示多少条（和旧版 MAX=60 保持一致） */
    private const val SHOW_LIMIT = 60

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // capture_log 和日程共用一个库、一份连接。服务进程路径可能比 App.onCreate 先到，
            // 这里补一次 Repo.init（幂等）
            Repo.init(context)
            migrateLegacySp(context.applicationContext)
            initialized = true
        }
    }

    /**
     * 记一条。只存元数据（谁、何时、结果、为什么）；正文默认不存——
     * 那是聊天内容、验证码、订单号，属于用户隐私。排查问题时可在设置里临时
     * 打开"记录通知原文"，且只留 80 字。
     */
    fun record(app: String, text: String, outcome: String, detail: String) {
        if (!initialized) return
        val keepRaw = runCatching { Prefs.logRawText }.getOrDefault(false)
        runCatching {
            val cv = ContentValues().apply {
                put(COL_AT, System.currentTimeMillis())
                put(COL_APP, app.take(40))
                put(COL_OUTCOME, outcome)
                put(COL_DETAIL, detail.take(120))
                put(COL_RAW, if (keepRaw) text.take(80) else null)
            }
            Repo.writable().insert(TABLE, null, cv)
            trim()
        }
    }

    fun recent(): List<CaptureRecord> {
        if (!initialized) return emptyList()
        return runCatching {
            Repo.writable().query(
                TABLE, null, null, null, null, null,
                "$COL_AT DESC", SHOW_LIMIT.toString()
            ).use { c -> c.toRecords() }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        if (!initialized) return
        runCatching { Repo.writable().delete(TABLE, null, null) }
    }

    /** 过期清理 + 行数兜底。有 at 索引，两条都是毫秒级，每次写入顺带做掉 */
    private fun trim() {
        val db = Repo.writable()
        db.delete(TABLE, "$COL_AT < ?", arrayOf((System.currentTimeMillis() - TTL_MS).toString()))
        db.execSQL(
            "DELETE FROM $TABLE WHERE $COL_ID NOT IN " +
                "(SELECT $COL_ID FROM $TABLE ORDER BY $COL_ID DESC LIMIT $MAX_ROWS)"
        )
    }

    private fun Cursor.toRecords(): List<CaptureRecord> {
        val iAt = getColumnIndexOrThrow(COL_AT)
        val iApp = getColumnIndexOrThrow(COL_APP)
        val iOutcome = getColumnIndexOrThrow(COL_OUTCOME)
        val iDetail = getColumnIndexOrThrow(COL_DETAIL)
        val iRaw = getColumnIndexOrThrow(COL_RAW)
        val list = ArrayList<CaptureRecord>(count)
        while (moveToNext()) {
            list.add(
                CaptureRecord(
                    at = getLong(iAt),
                    app = getString(iApp) ?: "",
                    text = getString(iRaw) ?: "",
                    outcome = getString(iOutcome) ?: "",
                    detail = getString(iDetail) ?: ""
                )
            )
        }
        return list
    }

    /**
     * 旧版流水存在 SP 里（单 key、控制字符分隔），搬进库后删掉 SP，只跑一次。
     * 搬的是老格式，见 git 历史；解析不了的行直接丢，不阻塞升级。
     */
    private fun migrateLegacySp(context: Context) {
        val sp = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        val raw = sp.getString(LEGACY_KEY, null) ?: return
        val fs = "\u0001"
        val rs = "\u0002"
        val rows = ArrayList<ContentValues>()
        for (line in raw.split(rs)) {
            val f = line.split(fs)
            if (f.size < 5) continue
            val at = f[0].toLongOrNull() ?: continue
            rows.add(ContentValues().apply {
                put(COL_AT, at)
                put(COL_APP, f[1])
                put(COL_RAW, f[2])
                put(COL_OUTCOME, f[3])
                put(COL_DETAIL, f[4])
            })
        }
        if (rows.isNotEmpty()) {
            val db = Repo.writable()
            db.beginTransaction()
            try {
                rows.forEach { db.insert(TABLE, null, it) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        sp.edit().remove(LEGACY_KEY).apply()
    }

    /** 通知监听服务是否在 Prefs 初始化前就被系统拉起了（极少见，兜一层） */
    fun ensureInit(context: Context) {
        Prefs.init(context)
        init(context)
    }
}
