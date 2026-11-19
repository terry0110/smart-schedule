package com.terry.smartschedule.notify

import android.app.Notification
import android.content.ComponentName
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.terry.smartschedule.data.Event
import com.terry.smartschedule.data.Repo
import com.terry.smartschedule.parse.CategoryRules
import com.terry.smartschedule.parse.DateTimeParser
import com.terry.smartschedule.parse.ParseService
import com.terry.smartschedule.parse.SegmentParser
import com.terry.smartschedule.parse.Prefs
import com.terry.smartschedule.parse.Revise
import com.terry.smartschedule.util.TimeFmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 通知监听：**只从微信 / QQ 的通知里**捞日程。
 *
 * 范围是有意收窄的。聊天通知里的日程密度最高，其他应用（新闻、电商、游戏、
 * 运营商短信）虽然偶尔也有日程，但数字密集、误报率高，得不偿失。白名单见
 * [WatchedApps]，改那里一个常量就能加应用。
 *
 * 处理顺序：
 *  0. 应用白名单：不是微信/QQ 的直接返回，连正则都不跑
 *  1. 过滤自己的通知、常驻通知、过短文本
 *  2. 粗筛：不像日程的直接放过
 *  3. 本地规则解析（纯离线，没有云端兜底）
 *  4. 分三档落地：直接建 / 进待确认 / 记日志说明为什么没建
 *
 * 关于"诊断模式"（设置 → 通知诊断）：
 * 平时只记"像日程却没建出来"的那部分，闲聊直接放过，否则日志会被刷满。
 * 但这样一来"回调根本没进来"和"进来了被筛掉"在界面上长得一模一样 —— 这正是
 * "服务显示已连接、通知却毫无反应"最难查的地方。打开诊断模式后，**每一个**回调
 * 都会留一条记录（仍然不写正文），能直接看出卡在哪一步。
 */
class NotificationCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 内存去重表：签名 -> 时间。比每次读写 SharedPreferences 快得多 */
    private val recentSigs = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > 64
    }

    override fun onCreate() {
        super.onCreate()
        // 进程可能是被系统单独为这个服务拉起的，App.onCreate 不一定走过
        Prefs.init(this)
        Repo.init(this)
        CaptureLog.init(this)
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        ServiceHealth.connected = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        ServiceHealth.connected = true
        instance = this
        Prefs.init(this)
        // 通知栏那条常驻提示要跟着变，否则会出现
        // "应用显示已连接、通知栏显示未连接"
        KeepAliveService.refresh(this)
        // 服务活着的时候是最适合拉起前台服务的时机
        if (Prefs.captureEnabled && Prefs.keepAlive) {
            ServiceHealth.startKeepAlive(this)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        ServiceHealth.connected = false
        KeepAliveService.refresh(this)
        // 有些 ROM 断开后不会自己重连，主动要求系统重新绑定
        runCatching {
            requestRebind(ComponentName(this, NotificationCaptureService::class.java))
        }
        CaptureLog.record(
            "系统", "", CaptureRecord.OUT_SKIPPED,
            "通知监听被系统断开，已请求重新连接"
        )
    }

    /**
     * 最外层必须兜住一切异常。
     *
     * 这里的输入来自任意第三方应用的通知正文（畸形字符、超长文本、奇怪组合都有可能），
     * 一旦异常抛出去，系统会把整个 NotificationListenerService 判定为异常，
     * 之后可能**再也不回调** —— 用户看到的现象正是"有时候识别不到"。
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            handleNotification(sbn)
        } catch (t: Throwable) {
            runCatching {
                CaptureLog.record(
                    "系统", "", CaptureRecord.OUT_SKIPPED,
                    "处理通知时出错：${t::class.java.simpleName} ${t.message.orEmpty().take(60)}"
                )
            }
        }
    }

    /**
     * @param force 手动扫描时传 true：跳过内存去重，也不受总开关影响
     *              （用户主动点"检测"就是想立刻看到结果）
     */
    private fun handleNotification(sbn: StatusBarNotification?, force: Boolean = false) {
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        // 只要回调进来就记一笔。这个值才是"服务到底在不在收通知"的唯一证据，
        // 之前的 lastEventAt 要等整条链路成功才会更新，用户看到的永远是"从没收到过"。
        ServiceHealth.lastCallbackAt = System.currentTimeMillis()

        if (!force && !Prefs.captureEnabled) return
        if (pkg == packageName) return

        // **只分析微信和 QQ 系的通知**。
        //
        // 这里以前是黑名单（只挡 android/systemui/settings/gms），等于其他第三方应用
        // 全都在往解析器里灌内容 —— 新闻推送、电商促销、游戏活动、运营商账单，
        // 这些文本里日期和数字太密集，靠解析器规则挡不干净，误报都从这儿来。
        //
        // 白名单之后连正则都不跑。代价是短信/邮件/日历里的日程也抓不到了，
        // 这是有意的取舍：少记一条能手动补，列表全是噪音就没法用。
        if (!WatchedApps.isWatched(pkg)) {
            verboseLog(pkg, "不是微信/QQ，不分析（当前只处理 ${WatchedApps.summary()}）")
            return
        }

        val n = sbn.notification ?: return
        // 会话名（群名/昵称）只用来划定判重范围，**永远不进解析**：
        // 群名叫 12.31 时它的日期会污染解析（见 extractSegments 注释），
        // 但判重必须带会话维度 —— 两个群发同一条通知是两件事，不能吞。
        // 白名单里已经有中文名了，不需要再查 PackageManager（省一次跨进程调用，
        // 而且比 getApplicationLabel 稳定 —— 有些 ROM 上 QQ 的 label 是"手机QQ"）
        val app = WatchedApps.displayName(pkg) ?: pkg

        // 会话名只用于判重范围，拿不到就退化为"整个应用"（和旧版一致）
        val convo = runCatching {
            n.extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
        }.getOrNull()?.toString().orEmpty()

        val segments = extractSegments(n)
        val joined = segments.joinToString(" ").trim()
        if (joined.length < MIN_TEXT) {
            verboseLog(app, "文本太短（${joined.length} 字）：$joined")
            return
        }

        val flags = n.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) {
            // 常驻通知：音乐播放器、下载进度、导航。极少数情况日程也在里面，但噪声太大
            verboseLog(app, "常驻通知（FLAG_ONGOING_EVENT）")
            return
        }
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0 && RE_SUMMARY_ONLY.containsMatchIn(joined)) {
            // 折叠的群通知。**不能一律丢掉**：不少 ROM 会把内容放进 summary 的文本里，
            // 只有"3 条新消息"这种纯计数摘要才真的没信息量。
            verboseLog(app, "群通知折叠摘要，无具体内容：$joined")
            return
        }

        // 文件/系统状态类通知：下载完成、文件已保存、安装包、电量提醒…
        // 这类通知里到处是日期（文件名、版本号），靠解析器拦不住 —— 真机上就是这么
        // 把 "smart-schedule-v1.8-debug.apk.1 已保存至…" 建成了一条日程。
        if (TextFilter.isNonScheduleNotice(joined)) {
            verboseLog(app, "文件 / 系统 / 资费类通知：$joined")
            return
        }

        // 不像日程的闲聊直接放过，日志留给真正"该识别却没识别"的
        if (!looksLikeSchedule(joined)) {
            verboseLog(app, "不含任何时间线索：$joined")
            return
        }

        // 先问一句「这是不是改期/取消」。
        //
        // 必须在粗筛和闲聊判断**之前**：
        // - 「原定明天下午三点的会在教学楼不开了」没有任何"具体安排"的措辞，
        //   会被 isSmallTalk 当成聊天丢掉 —— 实测就是这样，取消通知完全没反应；
        // - 「会不开了」这种连时间词都没有的，会被 looksLikeSchedule 先拦掉。
        // 两种情况用户看到的都是"日程照旧留着"，比不做还糟。
        val revise = Revise.detect(joined)
        val isRevision = revise is Revise.Verdict.Move || revise is Revise.Verdict.Cancel

        // 不像日程的闲聊直接放过，日志留给真正"该识别却没识别"的
        if (!isRevision && !looksLikeSchedule(joined)) {
            verboseLog(app, "不含任何时间线索：$joined")
            return
        }

        // 只有"今天/明天"这种模糊日期，又没有任何事件或动作 —— 是在聊天，不是在约事。
        // 典型：「阿伟：今天天气不错」会被建成一条"今天 全天：天气不错"的日程。
        if (!isRevision && TextFilter.isSmallTalk(joined)) {
            verboseLog(app, "像闲聊，没有具体安排：$joined")
            return
        }

        // 一级去重（进程内热表）：命中就省掉后面的解析。
        //
        // 签名用**归一化指纹**，不是原文哈希 —— 微信群通知每来一条新消息就整体重渲染，
        // 前缀、空格、标点都会变，原文哈希每次都不一样，等于没去重。
        // 持久化的二级判重在 persist() 里做（进程重启后这一层就没了，靠那层兜）。
        val sigs = segments.map { MsgSig.of(it, convo) }.filter { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        if (!force) {
            val hit = sigs.firstOrNull { s ->
                recentSigs["$pkg|$s"]?.let { now - it < DEDUP_WINDOW_MS } == true
            }
            if (hit != null) {
                verboseLog(app, "10 分钟内处理过同样内容")
                return
            }
        }
        sigs.forEach { recentSigs["$pkg|$it"] = now }

        // ---- 改期 / 取消：先看这句是不是在动一条已经存在的日程 ----
        //
        // 必须发生在 SegmentParser 之前。通用解析器的前提是「一段话 = 一条新日程」，
        // 拿它去解「原定明天四点的会提前至3点」会建出第二条 15:00 的日程，
        // 而原来那条 16:00 还在 —— 列表里两条互相矛盾，用户还得自己判断哪条算数。
        when (val v = revise) {
            is Revise.Verdict.Move -> {
                scope.launch { applyMove(app, joined, v) }
                return
            }
            is Revise.Verdict.Cancel -> {
                scope.launch { applyCancel(app, joined, v) }
                return
            }
            is Revise.Verdict.Unclear -> {
                // 看着像改期但信息不全：宁可什么都不做，也不建一条可能错的
                CaptureLog.record(
                    app, joined, CaptureRecord.OUT_SKIPPED,
                    "像改期但${v.why}，没动"
                )
                return
            }
            Revise.Verdict.None -> Unit
        }

        // 先整体、再逐段。策略本身在 SegmentParser 里，那边有单元测试；
        // 理由是群聊里往往只有一条消息是真日程，拼成一段会互相干扰。
        val quick = SegmentParser.pick(segments) { ParseService.quickParse(it) }

        if (quick == null) {
            CaptureLog.record(
                app, joined, CaptureRecord.OUT_SKIPPED,
                "看懂了是条日程，但没找到可用的日期时间"
            )
            return
        }

        val localEnough = quick.confidence >= ParseService.AUTO_THRESHOLD && !quick.needsConfirm
        if (localEnough) {
            val result = quick
            scope.launch { persist(app, joined, result, Event.SRC_NOTIFY, sigs = sigs, force = force) }
            return
        }

        // 本地解析没到"无人值守"的把握线，但仍然解析出了日期：直接入库、标成待确认。
        // 这里没有"再问云端一次"的分支 —— 应用没有 INTERNET 权限，原文不离开这台设备。
        val result = quick
        scope.launch { persist(app, joined, result, Event.SRC_NOTIFY, sigs = sigs, force = force) }
    }

    /**
     * 把一条已有日程改到新时间。
     *
     * 只改时间，**不改标题、不改地点、不改分类** —— 改期通知里说的只是"时间变了"，
     * 顺手把别的字段也覆盖掉，等于拿一句可能很脏的通知去替换用户确认过的内容。
     *
     * 找不到对应日程时什么都不做，但会留一条日志：用户能在处理日志里看到
     * "我认出这是改期，但没敢动" —— 比默默建一条新日程诚实。
     */
    private suspend fun applyMove(app: String, text: String, v: Revise.Verdict.Move) {
        val target = Repo.findReviseTarget(v.oldStartAt, text)
        if (target == null) {
            CaptureLog.record(
                app, text, CaptureRecord.OUT_SKIPPED,
                "像改期，但没找到对应的日程，没动数据"
            )
            return
        }

        if (target.startAt == v.newStartAt) {
            CaptureLog.record(app, text, CaptureRecord.OUT_SKIPPED, "改期后的时间和原日程一样，不用动")
            return
        }

        val note = buildString {
            append(target.note.orEmpty())
            if (isNotEmpty()) append("\n\n")
            append("【据通知改期】").append(text)
        }

        val updated = Repo.reschedule(target.id, v.newStartAt, v.newHasTime, note) ?: return
        // 改期后必须重排：原来的闹钟还挂在旧时间上，不重排就会在原定时刻响一次
        ReminderScheduler.schedule(this, updated)

        val oldLabel = TimeFmt.summary(target.startAt, target.hasTime, null)
        val newLabel = TimeFmt.summary(v.newStartAt, v.newHasTime, null)
        val tail = if (v.meridiemGuessed) "（上下午是按原时间推的，你核一眼）" else ""
        CaptureLog.record(
            app, text, CaptureRecord.OUT_CREATED,
            "已把「${target.title}」从 $oldLabel 改到 $newLabel$tail"
        )
    }

    /**
     * 标记「这条取消了」。
     *
     * **不删**。群里的"取消了"可能是另一件事、可能是玩笑、也可能说的根本不是你这条。
     * 删错了用户找不回来，标错了点一下就没 —— 两边代价不对称。
     */
    private suspend fun applyCancel(app: String, text: String, v: Revise.Verdict.Cancel) {
        val target = Repo.findReviseTarget(v.oldStartAt, text)
        if (target == null) {
            CaptureLog.record(
                app, text, CaptureRecord.OUT_SKIPPED,
                "像取消，但没找到对应的日程，没动数据"
            )
            return
        }

        Repo.revoke(target.id, "据通知：${text.take(80)}")
        CaptureLog.record(
            app, text, CaptureRecord.OUT_PENDING,
            "「${target.title}」已标为取消并停掉提醒，去列表里确认要不要删"
        )
    }

    /** 按可信度分档入库。返回落库的行 id（被去重/丢弃时返回 null） */
    private suspend fun persist(
        app: String,
        text: String,
        r: DateTimeParser.Result,
        source: String,
        /** 本条通知各段的内容指纹，用于跨进程重启的重复消息判重 */
        sigs: List<String> = emptyList(),
        /** 手动扫描时跳过判重：用户点"检测"就是想立刻看到结果 */
        force: Boolean = false
    ): Long? {
        val ctx = applicationContext

        // 二级去重：持久化台账。
        //
        // 一级（内存表）只能挡住"同一个服务进程内 10 分钟内"的重复。真实场景里
        // 进程被系统回收再拉起、手机重启，内存表都是空的，系统还会把通知栏里
        // 已有的通知重新回调一遍 —— 用户看到的就是"已经建过的那条又出现一次"。
        // 这一层落库，跨进程、跨重启都有效。
        if (!force) {
            SeenStore.claim(app, sigs)?.let { seenAt ->
                CaptureLog.record(
                    app, text, CaptureRecord.OUT_SKIPPED,
                    "同一条消息 ${SeenStore.agoLabel(seenAt)}处理过，不重复建"
                )
                return null
            }
        }

        // **只有时间没有日期的一律不建**（见 CapturePolicy）。
        //
        // 放在去重和置信度判断之前：这类结果根本不该出现在列表里，
        // 连"待确认"都不给 —— 一个"五点去吃饭"没法让人确认出哪天来，
        // 丢给用户等于把猜的责任转嫁给他。
        CapturePolicy.rejectReason(r)?.let { reason ->
            CaptureLog.record(app, text, CaptureRecord.OUT_SKIPPED, reason)
            return null
        }

        val unsure = r.confidence < ParseService.AUTO_THRESHOLD || r.needsConfirm

        if (Repo.existsSimilar(r.title, r.startAt, r.location)) {
            CaptureLog.record(
                app, text, CaptureRecord.OUT_SKIPPED,
                "已经有一条一样的日程了（标题、时间、地点都相同）"
            )
            return null
        }

        if (unsure && !Prefs.pendingWhenUnsure) {
            CaptureLog.record(
                app, text, CaptureRecord.OUT_SKIPPED,
                "识别把握不足（${r.confidence} 分），已按设置丢弃"
            )
            return null
        }

        val reason = if (r.warnings.isNotEmpty()) {
            r.warnings.joinToString("；")
        } else {
            val hint = r.dateHint.ifBlank { "日期是推出来的" }
            // 这段是给用户看"我为什么没把握"，所以每句话都必须属实。
            // 之前 DAY_ONLY 落到 else 分支，明明解析出了 23:59，却说"没看到具体钟点"。
            val why = when (r.dateSource) {
                DateTimeParser.DateSource.WEEK -> "只看到星期几，日期是我按周X推的"
                DateTimeParser.DateSource.TIME_ONLY -> "只看到时间，日期是我猜的"
                DateTimeParser.DateSource.DAY_ONLY ->
                    if (r.hasTime) "月份是我推的" else "没看到具体钟点，先按全天记"
                else -> if (r.hasTime) "" else "没看到具体钟点，先按全天记"
            }
            if (why.isEmpty()) "$hint，请核对" else "$hint（$why），请核对"
        }

        val event = Event(
            title = r.title,
            startAt = r.startAt,
            hasTime = r.hasTime,
            location = r.location,
            // 原文只留 500 字：够用户核对来源，又不至于把整段聊天记录写进库
            note = text.take(500),
            source = source,
            remindMinutes = Prefs.defaultRemind,
            pending = unsure,
            pendingReason = if (unsure) reason else null,
            // 分类用标题 + 地点 + 正文前 40 字：标题太短时（如"开会"）靠正文补信号。
            // 只取前 40 字是因为分类看的是"这件事是什么"，长通告后半段多是流程说明
            category = CategoryRules.classify(
                "${r.title} ${r.location.orEmpty()} ${text.take(40)}"
            ).key
        )

        val id = Repo.insert(event)
        val saved = event.copy(id = id)

        if (unsure) {
            // 待确认的不排提醒，等用户确认后再排
            Notifications.postPending(ctx, saved)
            CaptureLog.record(app, text, CaptureRecord.OUT_PENDING, reason)
        } else {
            ReminderScheduler.schedule(ctx, saved)
            Notifications.postCaptured(ctx, saved)
            CaptureLog.record(app, text, CaptureRecord.OUT_CREATED, "已建：${r.title}")
        }
        return id
    }

    /**
     * 把通知拆成若干**独立**的文本段：标题、每条聊天消息、bigText 等。
     *
     * 为什么要分段而不是拼成一段：
     * 群聊里常见"张三说明天开会 / 李四说哈哈哈"，拼起来会变成一句话，
     * 解析器只能取第一个日期，甚至因为前后文互相干扰啥都取不到。
     * 分段之后可以逐段试，命中哪段用哪段。
     *
     * 为什么要读 EXTRA_MESSAGES：
     * 微信、QQ、短信这类聊天通知用的是 MessagingStyle，真正的消息体存在
     * EXTRA_MESSAGES（Bundle 数组）里，EXTRA_TEXT 往往只是"3 条新消息"这种摘要。
     * 之前完全没读这里 —— 群消息基本全漏，这是"服务连着但什么都没发生"的主因。
     */
    /**
     * 从通知的**正文**里读出候选段，交给 [SegmentPicker] 过滤。
     *
     * 刻意不读 `EXTRA_TITLE` / `EXTRA_CONVERSATION_TITLE`：微信/QQ 里这两个字段
     * 永远是会话名（群名、昵称、QQ 号），不是内容。群名叫 `12.31` 时，
     * 它的日期会盖掉正文明明写着的"明天下午3点" —— 干脆不读，从结构上断掉这条路。
     */
    @Suppress("DEPRECATION")
    private fun extractSegments(n: Notification): List<String> {
        val ex = n.extras ?: return emptyList()

        // MessagingStyle 的消息体：真正的消息正文在这里，EXTRA_TEXT 往往只是"3 条新消息"
        val messages = mutableListOf<String>()
        for (key in MESSAGES_KEYS) {
            val arr = runCatching { ex.getParcelableArray(key) }.getOrNull() ?: continue
            for (item in arr.takeLast(MAX_MESSAGES)) {
                val b = item as? Bundle ?: continue
                val text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val sender = b.getCharSequence("sender")?.toString()?.trim().orEmpty()
                // 带发送者一起解析：正则会把"张三："当说话人前缀剥掉，不影响取时间
                messages.add(if (sender.isNotEmpty()) "$sender：$text" else text)
            }
        }

        // 显式标注类型：getCharSequenceArray 的泛型在这里推不出来
        val lines: List<String?>? = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { item -> item?.toString() }

        return SegmentPicker.pick(
            bodies = BODY_KEYS.map { key -> ex.getCharSequence(key)?.toString() },
            lines = lines.orEmpty(),
            messages = messages
        )
    }

    /**
     * 前置粗筛。这里**故意放宽**——之前要求必须含"点时日月周分号"，
     * 结果"明天开会""后天答辩"这类纯中文相对时间全被挡住，
     * 这是"有时候识别不出通知"的主要原因之一。
     */
    private fun looksLikeSchedule(text: String): Boolean {
        if (text.any { it.isDigit() }) return true
        if (text.any { it in TIME_SIGNAL }) return true
        if (CN_DIGITS.any { text.contains(it) }) return true
        return SCHEDULE_WORDS.any { text.contains(it) }
    }

    /**
     * 诊断模式下记录"为什么没处理这条"。
     * 默认不记 —— 否则每条闲聊都会占一格日志，真正该看的记录会被挤掉。
     * 始终不写正文以外的信息（正文由 CaptureLog 按 logRawText 决定要不要留）。
     */
    private fun verboseLog(app: String, reason: String) {
        if (!runCatching { Prefs.captureVerbose }.getOrDefault(false)) return
        runCatching {
            CaptureLog.record(app, "", CaptureRecord.OUT_SKIPPED, "未处理：$reason")
        }
    }

    companion object {
        private const val MIN_TEXT = 4
        private const val DEDUP_WINDOW_MS = 10 * 60_000L
        private const val MAX_MESSAGES = 4

        /** 服务实例。手动扫描要用它拿 activeNotifications */
        @Volatile
        private var instance: NotificationCaptureService? = null

        /** MessagingStyle 的消息数组。"android.messages.historic" 用字面量避免 API 28 常量内联问题 */
        private val MESSAGES_KEYS = listOf(
            Notification.EXTRA_MESSAGES,
            "android.messages.historic"
        )

        /** 普通正文类字段。标题单独取，避免和正文重复 */
        /**
         * 正文候选字段。
         *
         * **不含 `EXTRA_CONVERSATION_TITLE`** —— 那是会话名，由 SegmentPicker 的
         * sessionName 参数单独处理（并且会被丢弃）。放在这里就等于给群名开了一条
         * 混进正文的后路，`12.31` 那种群名就是这么被抓成日程的。
         */
        private val BODY_KEYS = listOf(
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT
        )

        /** "3 条新消息""5 个会话"这类纯计数摘要，不含任何内容 */
        private val RE_SUMMARY_ONLY = Regex(
            """^\s*\d+\s*[条个]\s*(新)?\s*(消息|会话|通知|人)\s*$"""
        )

        private const val CN_DIGITS = "一二三四五六七八九十两半"
        private const val TIME_SIGNAL = "点时日月周分号"

        /** 没有数字也没有时间符号时，靠这些词判断"这句话可能在说一件事" */
        private val SCHEDULE_WORDS = listOf(
            "明天", "后天", "今日", "今晚", "下周", "周末", "星期", "礼拜",
            "开会", "会议", "集合", "上课", "培训", "讲座", "活动", "面试",
            "考试", "交作业", "提交", "截止", "报名", "签到", "体检", "聚餐",
            "答辩", "值班", "例会", "班会", "领取", "典礼", "比赛"
        )

        /**
         * 手动把当前通知栏里已有的通知过一遍。
         *
         * 为什么要这个：用户想验证"到底通不通"时，不该只能干等新通知。
         * 点一下就能立刻知道管道是通的还是断的。
         *
         * @return 扫过的条数；-1 表示服务没连上，拿不到通知列表
         */
        /**
         * 把通知栏里已有的通知过一遍。
         *
         * @return null 表示拿不到列表（服务没连上，不等于"没有通知"）。
         *         分 total / watched 两个数：通知栏里大部分不是微信/QQ，
         *         只报总数会让人以为"扫了 5 条却只处理了 1 条"是 bug。
         */
        fun scanActive(): ScanResult? {
            val s = instance ?: return null
            val list = runCatching { s.activeNotifications }.getOrNull() ?: return null
            var watched = 0
            for (sbn in list) {
                if (sbn == null) continue
                if (WatchedApps.isWatched(sbn.packageName.orEmpty())) watched++
                runCatching { s.handleNotification(sbn, force = true) }
            }
            return ScanResult(total = list.count { it != null }, watched = watched)
        }
    }
}

/**
 * 手动扫描的结果。
 *
 * @param total   通知栏里总共有多少条
 * @param watched 其中属于微信/QQ 的有多少（只有这些会被真正分析）
 */
data class ScanResult(val total: Int, val watched: Int)
