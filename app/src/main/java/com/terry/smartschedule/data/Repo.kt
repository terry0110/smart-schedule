package com.terry.smartschedule.data

import android.content.Context
import android.database.Cursor
import com.terry.smartschedule.notify.ReminderScheduler
import com.terry.smartschedule.parse.Prefs
import com.terry.smartschedule.parse.Revise
import com.terry.smartschedule.parse.TextNorm
import com.terry.smartschedule.sync.CalendarSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 数据仓库。所有方法都是 suspend 且切到 IO 线程，UI 层直接 await 即可。
 * 日程量级很小（几百条），不做分页、不做缓存。
 */
object Repo {

    private lateinit var helper: EventDb

    /** 系统日历同步要用，只取 applicationContext，不持有 Activity */
    private lateinit var app: Context

    fun init(context: Context) {
        if (!this::helper.isInitialized) {
            app = context.applicationContext
            helper = EventDb(app)
        }
    }

    /**
     * 给 [CaptureLog] 复用同一个数据库连接。
     * 必须同一份 helper：同一个库文件开两个 helper，版本迁移会各跑各的，谁后开谁覆盖。
     * 调用前先 [init]。
     */
    fun writable(): android.database.sqlite.SQLiteDatabase = helper.writableDatabase

    suspend fun insert(event: Event): Long = withContext(Dispatchers.IO) {
        val id = helper.writableDatabase.insert(EventDb.TABLE, null, event.toValues())
        if (id > 0) mirrorToCalendar(event.copy(id = id))
        id
    }

    suspend fun update(event: Event) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            EventDb.TABLE, event.toValues(), "${EventDb.C_ID}=?",
            arrayOf(event.id.toString())
        )
        mirrorToCalendar(event)
        Unit
    }

    // ---------------- 系统日历同步 ----------------

    /**
     * 把一条日程同步到系统日历。
     *
     * 挂在 [insert] / [update] 里而不是各个界面调用点，是因为写库的入口有八处
     * （手动新增、识别、通知抓取、通知栏按钮、编辑、标完成、删除……），
     * 逐处加一遍必然漏一个 —— 漏掉的那条就会在日历里永远停在旧内容。
     *
     * 失败一律静默：日历写不进去不该让"建日程"失败。用户要的是日程，日历是附加价值。
     */
    private fun mirrorToCalendar(event: Event) {
        if (!Prefs.calendarSync || !CalendarSync.hasPermission(app)) return
        runCatching {
            if (event.pending) return  // 待确认的先别污染用户日历，确认后再推
            if (event.calEventId > 0 && CalendarSync.update(app, event, event.calEventId)) return
            // 没同步过，或系统那边那行已经被删了 —— 重新推一条并记下新 id
            val calId = CalendarSync.push(app, event) ?: return
            setCalId(event.id, calId)
        }
    }

    private fun setCalId(id: Long, calId: Long) {
        val cv = android.content.ContentValues().apply { put(EventDb.C_CAL_ID, calId) }
        helper.writableDatabase.update(
            EventDb.TABLE, cv, "${EventDb.C_ID}=?", arrayOf(id.toString())
        )
    }

    /** 这个日程在系统日历里的 id（0 = 没同步过） */
    fun calIdOf(id: Long): Long {
        return helper.readableDatabase.query(
            EventDb.TABLE, arrayOf(EventDb.C_CAL_ID), "${EventDb.C_ID}=?",
            arrayOf(id.toString()), null, null, null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    /**
     * 一次性把已有日程全部补进系统日历（用户刚打开同步开关时用）。
     *
     * 逐条推，不做事务：日历是另一个进程的 Provider，没法跟本库放同一个事务里。
     * 中途失败就停在失败的计数上，下次再点一次会跳过已同步的（cal_event_id > 0）。
     *
     * @return 新推了多少条
     */
    suspend fun syncAllToCalendar(): Int = withContext(Dispatchers.IO) {
        if (!CalendarSync.hasPermission(app)) return@withContext 0
        var pushed = 0
        for (event in all()) {
            if (event.pending || event.done) continue
            if (event.calEventId > 0 && CalendarSync.exists(app, event.calEventId)) continue
            val calId = CalendarSync.push(app, event) ?: continue
            setCalId(event.id, calId)
            pushed++
        }
        pushed
    }

    /** 删除时把系统日历里那条一起删掉。日历那头崩了不影响本地删除 */
    private fun removeFromCalendar(ids: Collection<Long>) {
        if (!Prefs.calendarSync) return
        for (id in ids) {
            val calId = calIdOf(id)
            if (calId > 0) CalendarSync.remove(app, calId)
        }
    }

    /** 删除全部日程。设置页的"删除所有本地数据"用 */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        // 开着同步时连系统日历里的一起清掉 —— 否则"删除所有本地数据"之后，
        // 用户日历里会留一堆点开没有来源的条目
        if (Prefs.calendarSync) removeFromCalendar(all().map { it.id })
        helper.writableDatabase.delete(EventDb.TABLE, null, null)
        Unit
    }

    /**
     * 批量删除。多选删除用。
     *
     * 放在一个事务里、用 `IN (...)` 一次删掉，而不是循环调 [delete]：
     * 循环会产生 N 次事务提交，几十条时有肉眼可见的延迟，中途失败还会删一半。
     *
     * 按 500 个一批切分 —— SQLite 的绑定变量上限是 999。
     */
    suspend fun deleteMany(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val db = helper.writableDatabase
        removeFromCalendar(ids)
        db.beginTransaction()
        try {
            ids.chunked(500).forEach { chunk ->
                val holders = chunk.joinToString(",") { "?" }
                db.delete(
                    EventDb.TABLE,
                    "${EventDb.C_ID} IN ($holders)",
                    chunk.map { it.toString() }.toTypedArray()
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        removeFromCalendar(listOf(id))
        helper.writableDatabase.delete(EventDb.TABLE, "${EventDb.C_ID}=?", arrayOf(id.toString()))
        Unit
    }

    suspend fun setDone(id: Long, done: Boolean) = withContext(Dispatchers.IO) {
        val cv = android.content.ContentValues().apply {
            put(EventDb.C_DONE, if (done) 1 else 0)
        }
        helper.writableDatabase.update(EventDb.TABLE, cv, "${EventDb.C_ID}=?", arrayOf(id.toString()))
        Unit
    }

    /** 确认一条待确认日程：转正，并把提醒排上 */
    suspend fun confirmPending(id: Long) = withContext(Dispatchers.IO) {
        val cv = android.content.ContentValues().apply {
            put(EventDb.C_PENDING, 0)
            put(EventDb.C_PENDING_REASON, null as String?)
        }
        helper.writableDatabase.update(EventDb.TABLE, cv, "${EventDb.C_ID}=?", arrayOf(id.toString()))
        Unit
    }

    /**
     * 全部日程排序：待确认永远置顶（用户得先处理它们），
     * 其次未完成在前，最后按时间正序。
     */
    suspend fun all(): List<Event> = withContext(Dispatchers.IO) {
        helper.readableDatabase.query(
            EventDb.TABLE, null, null, null, null, null,
            "${EventDb.C_PENDING} DESC, ${EventDb.C_DONE} ASC, ${EventDb.C_START} ASC"
        ).use { c -> c.toEvents() }
    }

    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${EventDb.TABLE} WHERE ${EventDb.C_PENDING}=1", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    suspend fun upcoming(from: Long = System.currentTimeMillis()): List<Event> =
        withContext(Dispatchers.IO) {
            helper.readableDatabase.query(
                EventDb.TABLE, null,
                "${EventDb.C_START}>=? AND ${EventDb.C_DONE}=0 AND ${EventDb.C_PENDING}=0",
                arrayOf(from.toString()), null, null, "${EventDb.C_START} ASC"
            ).use { c -> c.toEvents() }
        }

    suspend fun findById(id: Long): Event? = withContext(Dispatchers.IO) {
        helper.readableDatabase.query(
            EventDb.TABLE, null, "${EventDb.C_ID}=?", arrayOf(id.toString()),
            null, null, null
        ).use { c -> if (c.moveToFirst()) c.toEvent() else null }
    }

    /** 去重：同标题且开始时间相差不超过 5 分钟，视为同一条（防止重复通知重复建） */
    /**
     * 找"已经存在的那一条"：**归一化后**标题相同，开始时间落在窗口内。
     *
     * 为什么比归一化后的键而不是 title 原文：
     * 之前用 `title = ?` 精确相等，微信重发同一条通知时标题常常差一个句号、
     * 多一个空格、或者发送者前缀被剥掉的位置不同 —— 全部漏过，列表里出现两条
     * 内容一样的日程。归一化（[com.terry.smartschedule.parse.TextNorm]）之后
     * 这些差异都不影响判定。
     *
     * 返回 Event 而不只是 Boolean，是为了能把"撞的是哪一条"告诉用户 ——
     * 只说"已存在"，他并不知道自己重复添加的是什么。
     *
     * @param excludeId 编辑时传自己的 id，否则改个地点就会撞到自己
     * @param windowMs  时间容差。手动添加用 1 分钟（选择器只能选到整分）；
     *                  通知抓取用 5 分钟（不同来源推出来的时间常差几分钟）
     */
    suspend fun findSameEvent(
        title: String,
        startAt: Long,
        location: String? = null,
        excludeId: Long = -1L,
        windowMs: Long = 60_000L
    ): Event? = withContext(Dispatchers.IO) {
        val key = TextNorm.key(title)
        // 纯符号标题归一化后是空串，退回原文比较（否则所有空键会互相撞）
        val (col, arg) = if (key.isEmpty()) {
            EventDb.C_TITLE to title
        } else {
            EventDb.C_TITLE_KEY to key
        }
        val extra = if (excludeId > 0) " AND ${EventDb.C_ID}<>?" else ""
        val args = buildList {
            add(arg)
            add((startAt - windowMs).toString())
            add((startAt + windowMs).toString())
            add(location.orEmpty())
            if (excludeId > 0) add(excludeId.toString())
        }.toTypedArray()
        helper.readableDatabase.query(
            EventDb.TABLE, null,
            // 地点也算要素：同标题同时间但地点不同，是两件事，不能吞
            // （IFNULL 统一空值口径，null 和没填都算"没有地点"）
            "$col=? AND ${EventDb.C_START} BETWEEN ? AND ? " +
                "AND IFNULL(${EventDb.C_LOCATION},'')=?$extra",
            args, null, null, null, "1"
        ).use { c -> if (c.moveToFirst()) c.toEvent() else null }
    }

    /** 通知抓取时的去重：窗口放宽到 5 分钟，因为推算出来的时间常差几分钟 */
    suspend fun existsSimilar(
        title: String,
        startAt: Long,
        location: String? = null
    ): Boolean = findSameEvent(title, startAt, location, windowMs = 5 * 60_000L) != null

    /**
     * 找「改期 / 取消通知」指向的那条日程。
     *
     * 匹配策略，从紧到松：
     * 1. 有旧时间：只在旧时间 ±30 分钟里找。原文自己写了「原定四点」，这是最强的锚点。
     * 2. 没旧时间：在今天和明天里找（改期通知几乎不会指向更远的事）。
     * 3. 范围里只有一条 → 就用它。
     * 4. 多条 → 用文字重叠过滤，剩下唯一才用；否则返回 null，**不猜**。
     *
     * 为什么最后一步宁可返回 null：改错一条日程用户很难发现（时间被悄悄挪了），
     * 而漏改最多是"没生效"，用户看得见。两边代价不对称。
     *
     * @param titleHint 传整句通知原文，不要传解析出来的标题 —— 改期句式的标题通常很脏
     */
    suspend fun findReviseTarget(oldStartAt: Long?, titleHint: String): Event? = withContext(Dispatchers.IO) {
        val (from, to) = if (oldStartAt != null) {
            (oldStartAt - 30 * 60_000L) to (oldStartAt + 30 * 60_000L)
        } else {
            val now = System.currentTimeMillis()
            now to (now + 2 * 24 * 3600_000L)
        }
        val cands = helper.readableDatabase.query(
            EventDb.TABLE, null,
            "${EventDb.C_START} BETWEEN ? AND ?",
            arrayOf(from.toString(), to.toString()),
            null, null, "${EventDb.C_START} ASC"
        ).use { it.toEvents() }
            // 已完成的不改（已经发生过的事）、已标记取消的不重复标、待确认的还没算数
            .filter { !it.done && !it.revoked && !it.pending }

        // 选哪一条的逻辑在 Revise.pick 里，纯函数，逐条钉过单测
        Revise.pick(cands, titleHint)
    }

    /** 标记「来源通知说取消了」。不删数据，只是打个标 + 记下理由 */
    suspend fun revoke(id: Long, reason: String) = withContext(Dispatchers.IO) {
        val cv = android.content.ContentValues().apply {
            put(EventDb.C_REVOKED, 1)
            put(EventDb.C_REVOKE_REASON, reason)
        }
        helper.writableDatabase.update(EventDb.TABLE, cv, "${EventDb.C_ID}=?", arrayOf(id.toString()))
        // 不再提醒：已经取消的事再响一次是纯噪音
        ReminderScheduler.cancel(app, id)
    }

    /**
     * 改期：只动时间和"哪天有没有具体钟点"，标题、地点、分类一律不碰。
     * @return 更新后的日程；id 不存在返回 null
     */
    suspend fun reschedule(id: Long, newStartAt: Long, newHasTime: Boolean, note: String?): Event? {
        val old = findById(id) ?: return null
        val next = old.copy(startAt = newStartAt, hasTime = newHasTime, note = note ?: old.note)
        update(next)
        return next
    }

    private fun Cursor.toEvents(): List<Event> {
        val list = ArrayList<Event>(count)
        while (moveToNext()) list.add(toEvent())
        return list
    }

    private fun Cursor.toEvent(): Event = Event(
        id = getLong(getColumnIndexOrThrow(EventDb.C_ID)),
        title = getString(getColumnIndexOrThrow(EventDb.C_TITLE)),
        startAt = getLong(getColumnIndexOrThrow(EventDb.C_START)),
        hasTime = getInt(getColumnIndexOrThrow(EventDb.C_HAS_TIME)) == 1,
        location = getString(getColumnIndexOrThrow(EventDb.C_LOCATION)),
        note = getString(getColumnIndexOrThrow(EventDb.C_NOTE)),
        source = getString(getColumnIndexOrThrow(EventDb.C_SOURCE)) ?: Event.SRC_MANUAL,
        done = getInt(getColumnIndexOrThrow(EventDb.C_DONE)) == 1,
        createdAt = getLong(getColumnIndexOrThrow(EventDb.C_CREATED)),
        remindMinutes = getInt(getColumnIndexOrThrow(EventDb.C_REMIND)),
        remindStyle = getInt(getColumnIndexOrThrow(EventDb.C_REMIND_STYLE)),
        pending = getInt(getColumnIndexOrThrow(EventDb.C_PENDING)) == 1,
        pendingReason = getString(getColumnIndexOrThrow(EventDb.C_PENDING_REASON)),
        category = getString(getColumnIndexOrThrow(EventDb.C_CATEGORY)) ?: Category.OTHER.key,
        calEventId = getLong(getColumnIndexOrThrow(EventDb.C_CAL_ID)),
        revoked = getInt(getColumnIndexOrThrow(EventDb.C_REVOKED)) == 1,
        revokeReason = getString(getColumnIndexOrThrow(EventDb.C_REVOKE_REASON))
    )
}
