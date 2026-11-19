package com.terry.smartschedule.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.terry.smartschedule.parse.CategoryRules
import com.terry.smartschedule.parse.TextNorm

/**
 * 单表存储，故意不用 Room：
 * 只有一个实体，SQLiteOpenHelper 足够，还省掉 kapt/ksp 的版本对齐问题（构建更快更不容易炸）。
 */
class EventDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                title_key TEXT NOT NULL DEFAULT '',
                start_at INTEGER NOT NULL,
                has_time INTEGER NOT NULL DEFAULT 1,
                location TEXT,
                note TEXT,
                source TEXT NOT NULL DEFAULT 'manual',
                done INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                remind_minutes INTEGER NOT NULL DEFAULT 10,
                remind_style INTEGER NOT NULL DEFAULT -1,
                pending INTEGER NOT NULL DEFAULT 0,
                pending_reason TEXT,
                category TEXT NOT NULL DEFAULT 'other',
                cal_event_id INTEGER NOT NULL DEFAULT 0,
                revoked INTEGER NOT NULL DEFAULT 0,
                revoke_reason TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_event_start ON $TABLE(start_at)")
        db.execSQL("CREATE INDEX idx_event_title_key ON $TABLE(title_key, start_at)")
        // 抓取流水（通知处理轨迹）。和日程同一个库文件，省一次连接和一套迁移
        db.execSQL(CREATE_CAPTURE_LOG)
        db.execSQL("CREATE INDEX idx_capture_at ON $C_TABLE(at)")
        // 已处理过的通知内容指纹，用于跨进程重启的重复消息判重
        db.execSQL(CREATE_SEEN)
        db.execSQL("CREATE INDEX idx_seen_at ON $S_TABLE(at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2：加入"待确认"状态。老数据都是用户手点或早先自动建的，视为已确认。
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN pending INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN pending_reason TEXT")
        }
        // v2 -> v3：每条日程可以单独选通知方式。
        // 老数据一律 -1（跟随设置）—— 它们从来没单独选过，行为保持和升级前一致。
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN remind_style INTEGER NOT NULL DEFAULT -1")
        }
        // v3 -> v4：抓取流水从 SharedPreferences 搬进库。
        // SP 是整个文件一次性重写：日志攒多后每次写入都是全量序列化 + 重写，
        // 应用自己会变成卡顿源。进库后有索引、有 TTL（见 CaptureLog），写入是常量成本。
        if (oldVersion < 4) {
            db.execSQL(CREATE_CAPTURE_LOG)
            db.execSQL("CREATE INDEX idx_capture_at ON $C_TABLE(at)")
        }
        // v4 -> v5：消息去重。
        //  a) events 加 title_key：存归一化后的标题（去空白/标点/发送者前缀）。
        //     之前判重用 `title = ?` 精确相等，微信重发时标题差个句号就漏，列表里出现两条。
        //  b) msg_seen：已处理内容指纹的台账，持久化 —— 之前的内存表在进程重启后清空，
        //     同一条通知会被重新解析、重新建日程。
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN title_key TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX idx_event_title_key ON $TABLE(title_key, start_at)")
            db.execSQL(CREATE_SEEN)
            db.execSQL("CREATE INDEX idx_seen_at ON $S_TABLE(at)")
            backfillTitleKey(db)
        }
        // v5 -> v6：日程分类。
        // 老数据按标题+原文跑一遍分类规则，比全部丢进"其他"有用；
        // 规则判不出的仍落在"其他"，不会硬凑一类。
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN category TEXT NOT NULL DEFAULT 'other'")
            backfillCategory(db)
        }
        // v6 -> v7：系统日历同步。
        // 只存"系统日历里那一行的 id"，0 表示没同步过。
        // 不放进 Event.toValues()：那是整行覆盖写，编辑日程时内存里的 Event 未必带着
        // 最新的系统 id，顺手覆盖会把映射冲掉，之后每次编辑都会在日历里新建一条重复的。
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN cal_event_id INTEGER NOT NULL DEFAULT 0")
        }
        // v7 -> v8：标记「来源通知说这条取消了」。
        // 只标记不删除：群里的“取消”可能是另一件事，也可能是有人开玩笑。
        // 删错了用户找不回来，标错了删一下就行 —— 两边的代价不对称，所以选标记。
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN revoked INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN revoke_reason TEXT")
        }
    }

    /** 给老数据补分类。用的是纯规则分类器（[CategoryRules]），不需要网络 */
    private fun backfillCategory(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            data class Row(val id: Long, val cat: String)
            val rows = ArrayList<Row>()
            db.query(TABLE, arrayOf(C_ID, C_TITLE, C_NOTE), null, null, null, null, null).use { c ->
                while (c.moveToNext()) {
                    val text = (c.getString(1) ?: "") + " " + (c.getString(2) ?: "")
                    rows.add(Row(c.getLong(0), CategoryRules.classify(text).key))
                }
            }
            for (r in rows) {
                db.execSQL("UPDATE $TABLE SET category = ? WHERE $C_ID = ?", arrayOf(r.cat, r.id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 给老数据补 title_key。SQL 做不了这种归一化（要去标点、剥前缀），
     * 只能读出来在 Kotlin 里算。日程量级几百条，一次升级几百次 UPDATE，可接受。
     */
    private fun backfillTitleKey(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            data class Row(val id: Long, val key: String)
            val rows = ArrayList<Row>()
            db.query(TABLE, arrayOf(C_ID, C_TITLE), null, null, null, null, null).use { c ->
                while (c.moveToNext()) {
                    rows.add(Row(c.getLong(0), TextNorm.key(c.getString(1) ?: "")))
                }
            }
            for (r in rows) {
                db.execSQL(
                    "UPDATE $TABLE SET $C_TITLE_KEY = ? WHERE $C_ID = ?",
                    arrayOf(r.key, r.id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val DB_NAME = "schedule.db"
        const val DB_VERSION = 8
        const val TABLE = "events"
        const val C_TABLE = "capture_log"
        const val S_TABLE = "msg_seen"

        /**
         * 已处理过的通知内容指纹。
         *
         * `sig` 直接用主键：INSERT 冲突即"见过"，一次写操作完成判重 + 记账，
         * 不需要先 SELECT 再 INSERT（那中间有竞态，两条并发的相同通知都会被放过去）。
         */
        val CREATE_SEEN = """
            CREATE TABLE $S_TABLE (
                sig TEXT PRIMARY KEY,
                app TEXT NOT NULL,
                at INTEGER NOT NULL
            )
        """.trimIndent()

        val CREATE_CAPTURE_LOG = """
            CREATE TABLE $C_TABLE (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                app TEXT NOT NULL,
                outcome TEXT NOT NULL,
                detail TEXT NOT NULL,
                raw TEXT
            )
        """.trimIndent()
        const val C_ID = "_id"
        const val C_TITLE = "title"
        const val C_TITLE_KEY = "title_key"
        const val C_START = "start_at"
        const val C_HAS_TIME = "has_time"
        const val C_LOCATION = "location"
        const val C_NOTE = "note"
        const val C_SOURCE = "source"
        const val C_DONE = "done"
        const val C_CREATED = "created_at"
        const val C_REMIND = "remind_minutes"
        const val C_REMIND_STYLE = "remind_style"
        const val C_PENDING = "pending"
        const val C_PENDING_REASON = "pending_reason"
        const val C_CATEGORY = "category"
        const val C_CAL_ID = "cal_event_id"
        const val C_REVOKED = "revoked"
        const val C_REVOKE_REASON = "revoke_reason"
    }
}

/** 把 Event 转成 ContentValues */
fun Event.toValues(): ContentValues = ContentValues().apply {
    put(EventDb.C_TITLE, title)
    put(EventDb.C_TITLE_KEY, com.terry.smartschedule.parse.TextNorm.key(title))
    put(EventDb.C_START, startAt)
    put(EventDb.C_HAS_TIME, if (hasTime) 1 else 0)
    put(EventDb.C_LOCATION, location)
    put(EventDb.C_NOTE, note)
    put(EventDb.C_SOURCE, source)
    put(EventDb.C_DONE, if (done) 1 else 0)
    put(EventDb.C_CREATED, createdAt)
    put(EventDb.C_REMIND, remindMinutes)
    put(EventDb.C_REMIND_STYLE, remindStyle)
    put(EventDb.C_PENDING, if (pending) 1 else 0)
    put(EventDb.C_PENDING_REASON, pendingReason)
    put(EventDb.C_CATEGORY, category)
    put(EventDb.C_REVOKED, if (revoked) 1 else 0)
    put(EventDb.C_REVOKE_REASON, revokeReason)
}
