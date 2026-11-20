package com.terry.smartschedule.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.terry.smartschedule.R
import com.terry.smartschedule.data.Event
import com.terry.smartschedule.data.EventConflict
import com.terry.smartschedule.data.EventEdit
import com.terry.smartschedule.data.Repo
import com.terry.smartschedule.databinding.ActivityMainBinding
import com.terry.smartschedule.databinding.SheetPreviewBinding
import com.terry.smartschedule.notify.KeepAliveService
import com.terry.smartschedule.notify.ListenerStatus
import com.terry.smartschedule.notify.NotifyAction
import com.terry.smartschedule.notify.Notifications
import com.terry.smartschedule.notify.ReminderScheduler
import com.terry.smartschedule.notify.RemindStyle
import com.terry.smartschedule.notify.ReviseInbox
import com.terry.smartschedule.notify.ServiceHealth
import com.terry.smartschedule.data.Category
import com.terry.smartschedule.data.EventSearch
import com.terry.smartschedule.parse.CategoryRules
import com.terry.smartschedule.parse.DateTimeParser
import com.terry.smartschedule.parse.ParseService
import com.terry.smartschedule.parse.Prefs
import com.terry.smartschedule.parse.TextNorm
import com.terry.smartschedule.util.Insets
import com.terry.smartschedule.util.TimeFmt
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: EventAdapter

    /** 识别结果确认面板 */
    private var sheet: BottomSheetDialog? = null

    private var previewDate: LocalDate? = null
    private var previewTime: LocalTime? = null
    private var lastRaw: String = ""

    /** 列表当前筛选的分类；null = 全部。只在内存里，重开应用回到"全部" */
    private var filterCat: Category? = null

    /** 是否处于检索态（检索条打开、输入卡收起） */
    private var searchMode = false

    /** 当前生效的检索词。reload 用它过滤，输入框只是它的编辑界面 */
    private var searchQuery = ""

    /** 面板里用户是否亲手选过分类。没选过就按内容自动判断，选过就以他为准 */
    private var sheetCatTouched = false

    /** 程序化设置选中项时置 true，避免被当成"用户选过了" */
    private var catApplying = false

    /** 多选模式。长按任意一条进入，取消或删完退出 */
    private var selectionMode = false

    /** 选中的日程 id。用 id 不用位置 —— 列表随时会因新通知刷新，位置会变 */
    private val selectedIds = linkedSetOf<Long>()

    /**
     * 上次刷新算出的待确认条数。
     * 退出多选时用它把横幅恢复回来，不用再查一次库 —— 查库是异步的，
     * 会有那么一瞬间横幅不显示，看着像丢了。
     */
    private var lastPendingCount = 0

    /** 多选时接管返回键：退出多选，而不是退出 Activity */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitSelection()
    }

    /** 检索时接管返回键：先清空关键词，再按一次才退出检索 */
    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // 一按返回就把整条检索结果丢掉太粗暴了：先只清关键词
            if (!b.etSearch.text.isNullOrEmpty()) b.etSearch.setText("") else closeSearch()
        }
    }

    // ---------------- 检索 ----------------

    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 去抖后的真正过滤。输入法每敲一个字都会回调，见 addTextChangedListener 处 */
    private val searchTick = Runnable { commitSearch(b.etSearch.text?.toString().orEmpty()) }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
        const val KEY_SEARCH_MODE = "search_mode"
        const val KEY_SEARCH_QUERY = "search_query"
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 在 Android 15 上是强制 edge-to-edge，
        // 这里显式打开，让各版本行为一致，再由 Insets 把内容顶下来
        Insets.edgeToEdge(this)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.apply(
            this, b.root,
            statusBarSpacer = b.statusBarSpace,
            bottomTarget = b.rvEvents,
            bottomExtraDp = 26
        )

        adapter = EventAdapter(
            onToggle = ::archiveEvent,
            onClick = ::openEditor,
            onEnterSelection = ::enterSelection,
            onToggleSelection = ::toggleSelection
        )
        b.rvEvents.layoutManager = LinearLayoutManager(this)
        b.rvEvents.adapter = adapter
        // 列表整体刷新时不闪烁，视觉更稳
        (b.rvEvents.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.btnArchive.setOnClickListener {
            startActivity(Intent(this, ArchiveActivity::class.java))
        }

        // 检索
        b.btnSearch.setOnClickListener { openSearch() }
        b.btnSearchCancel.setOnClickListener { closeSearch() }
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // 每敲一个字都过滤一次的话，整张列表要重排、每条卡片（5 层玻璃背景）
                // 要重绘，连打会明显粘手。150ms 足以让"打字"看起来仍是实时的
                searchHandler.removeCallbacks(searchTick)
                searchHandler.postDelayed(searchTick, SEARCH_DEBOUNCE_MS)
            }
        })

        // 分类筛选：只影响显示，不写库
        b.cgFilter.setOnCheckedStateChangeListener { _, ids ->
            filterCat = when (ids.firstOrNull()) {
                R.id.chipFilterStudy -> Category.STUDY
                R.id.chipFilterWork -> Category.WORK
                R.id.chipFilterClub -> Category.CLUB
                R.id.chipFilterLife -> Category.LIFE
                R.id.chipFilterOther -> Category.OTHER
                else -> null
            }
            reload()
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
        onBackPressedDispatcher.addCallback(this, searchBackCallback)
        b.btnSelectClose.setOnClickListener { exitSelection() }
        b.btnSelectAll.setOnClickListener { toggleSelectAll() }
        b.btnSelectDelete.setOnClickListener { confirmDeleteSelected() }
        b.btnParse.setOnClickListener { doParse() }
        b.btnClear.setOnClickListener {
            b.etInput.text?.clear()
            hideKeyboard()
        }
        b.bannerPending.setOnClickListener {
            // 待确认永远排在列表最前面，滚到顶就能看到
            (b.rvEvents.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(0, 0)
        }
        b.bannerRevise.setOnClickListener { openReviseInbox() }

        // 输入框有内容才显示"清空"
        b.etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                b.btnClear.isVisible = !s.isNullOrBlank()
            }
        })

        askNotificationPermission()

        // 转屏 / 分屏重建后把检索态恢复回来。
        // 不自动弹键盘：用户可能只是在看结果，弹键盘会把列表盖掉一半
        if (savedInstanceState?.getBoolean(KEY_SEARCH_MODE) == true) {
            val q = savedInstanceState.getString(KEY_SEARCH_QUERY).orEmpty()
            searchQuery = q.trim()
            applySearchUi(true)
            b.etSearch.setText(q)
            b.etSearch.setSelection(b.etSearch.text?.length ?: 0)
        }

        // 从「改期没认准」通知点进来。转屏重建时不重复弹
        // （savedInstanceState != null 说明是恢复，不是新点进来的）
        if (savedInstanceState == null) handleReviseIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReviseIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_SEARCH_MODE, searchMode)
        outState.putString(KEY_SEARCH_QUERY, searchQuery)
        super.onSaveInstanceState(outState)
    }

    /** 通知栏里点了「确认 / 删除」之后，前台的列表要跟着变 */
    private val dataChanged = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == NotifyAction.ACTION_DATA_CHANGED) reload()
        }
    }

    override fun onStart() {
        super.onStart()
        // RECEIVER_NOT_EXPORTED：这个广播只给本应用用，别让外部应用能触发刷新
        ContextCompat.registerReceiver(
            this,
            dataChanged,
            IntentFilter(NotifyAction.ACTION_DATA_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(dataChanged) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateServiceState()
        ensureKeepAlive()
        ensureListenerBound()
        reload()
        // 补发漏掉的提醒。
        // 会漏的原因都在这条路上：手机重启、系统清闹钟、应用被强杀，
        // 或者建日程时提醒点已经过去了（见 ReminderPlan）。
        // 补发是幂等的（通知 id 固定 + 已响过的不重发），反复进前台不会重复响。
        lifecycleScope.launch {
            runCatching { ReminderScheduler.catchUp(this@MainActivity) }
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(refreshState)
        searchHandler.removeCallbacks(searchTick)
        sheet?.dismiss()
        sheet = null
        super.onDestroy()
    }

    // ---------------- 状态呈现 ----------------

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshState = Runnable { updateServiceState() }

    /** 整个进程只自动请求一次重绑，见 ensureListenerBound 里的说明 */
    private var askedRebindOnce = false

    /**
     * 进程被杀之后，系统要过一会儿才重新绑定通知监听服务。
     * 这段时间 connected 必然是 false，界面就只能显示"没连上"，用户干等。
     * 这里主动催一次系统，并延迟复查一次 —— 这样"退出重进"才真的能恢复。
     */
    private fun ensureListenerBound() {
        if (ServiceHealth.status(this) != ListenerStatus.CONNECTING) return
        // 只请求一次，之后纯观察。
        // 之前每次 onResume 都发一次，和用户在设置页手点的那次互相打断，
        // 表现成"点好几次才连上"。
        if (!askedRebindOnce) {
            askedRebindOnce = true
            ServiceHealth.requestRebind(this)
        }
        ui.removeCallbacks(refreshState)
        ui.postDelayed(refreshState, 1200)
    }

    /**
     * 副标题直接告诉你监听到底在不在工作。
     * "授权了但还没连上"和"根本没授权"是两回事，用户得能区分；
     * 但"还没连上"不等于坏了，别一上来就说"已断开"吓人。
     */
    private fun updateServiceState() {
        // lastCallbackAt 是进程内变量，进程重启就归零，所以文案写"本次运行"
        val cb = ServiceHealth.lastCallbackAt
        b.tvSubtitle.text = when (ServiceHealth.status(this)) {
            ListenerStatus.OFF -> getString(R.string.header_subtitle)
            ListenerStatus.NO_PERMISSION -> "通知监听未授权 · 点右上角去开启"
            ListenerStatus.CONNECTING -> "通知监听已开启 · 正在等待系统连接…"
            ListenerStatus.RUNNING ->
                if (cb == 0L) "监听中 · 本次运行还没收到过通知"
                else "监听中 · 最近收到通知${ago(cb)}"
        }
        // 顺手刷一下通知栏那条常驻提示，保证它和这里说的是同一件事
        KeepAliveService.refresh(this)
    }

    /** "刚刚" / "3 分钟前" / "2 小时前"。前导空格由调用方拼接 */
    private fun ago(at: Long): String {
        val sec = (System.currentTimeMillis() - at) / 1000
        return when {
            sec < 60 -> " 刚刚"
            sec < 3600 -> " ${sec / 60} 分钟前"
            sec < 86_400 -> " ${sec / 3600} 小时前"
            else -> " ${sec / 86_400} 天前"
        }
    }

    private fun ensureKeepAlive() {
        if (Prefs.captureEnabled && Prefs.keepAlive) {
            ServiceHealth.startKeepAlive(this)
        }
    }

    // ---------------- 解析 ----------------

    private fun doParse() {
        val raw = b.etInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            toast("先粘贴或输入一段通知文本")
            return
        }
        hideKeyboard()
        setBusy(true)
        lifecycleScope.launch {
            val result = try {
                ParseService.parse(raw)
            } catch (e: Exception) {
                null
            }
            setBusy(false)
            if (result == null) {
                // 解析不出来要明说，不要让用户以为点了个假按钮
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("没识别出时间")
                    .setMessage(
                        "这段文字里没有我能确定的时间信息。\n\n" +
                            "可以这样写：「明天下午3点半在图书馆三楼开会」\n" +
                            "或者直接手动添加一条日程。"
                    )
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton("手动添加") { _, _ ->
                        showPreview(fallbackResult(raw), raw)
                    }
                    .show()
                return@launch
            }
            showPreview(result, raw)
        }
    }

    /** 解析失败时给一个"今天现在"的空壳，让用户自己填 */
    private fun fallbackResult(raw: String): DateTimeParser.Result {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        return DateTimeParser.Result(
            title = raw.take(20),
            startAt = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            hasTime = true,
            location = null,
            confidence = 0,
            dateSource = DateTimeParser.DateSource.NONE,
            warnings = listOf("这段文字没解析出日期时间，下面是我随手填的，请自己改")
        )
    }

    /** 识别结果确认面板（新建流程） */
    private fun showPreview(r: DateTimeParser.Result, raw: String) {
        lastRaw = raw
        val (dlg, vb) = createSheet()

        val dt = TimeFmt.toDateTime(r.startAt)
        previewDate = dt.toLocalDate()
        previewTime = dt.toLocalTime()

        vb.etTitle.setText(r.title)
        vb.etLocation.setText(r.location.orEmpty())
        vb.cbAllDay.isChecked = !r.hasTime
        vb.btnTime.isEnabled = r.hasTime
        vb.btnTime.alpha = if (r.hasTime) 1f else 0.45f
        updateDateButton(vb)
        updateTimeButton(vb)

        // 新建默认跟随设置
        applyEventStyle(vb, RemindStyle.FOLLOW_DEFAULT)

        // 分类先按规则猜一个，并把"为什么"写在下面 —— 用户一眼能改
        val guessText = "${r.title} ${r.location.orEmpty()} $raw"
        val guess = CategoryRules.classify(guessText)
        val guessWord = CategoryRules.reason(guessText)
        applyCategory(
            vb, guess,
            if (guessWord != null) {
                "看到「$guessWord」，归到${getString(guess.labelRes)}"
            } else {
                "没看出类别，先放这里；你可以直接选"
            }
        )

        vb.tvRaw.isVisible = true
        vb.tvRaw.text = "原文：$raw"

        // 日期依据始终显示：让用户知道这个日期是原文写的，还是我推出来的
        val basis = r.dateHint.ifBlank { null }
        val hintHead = basis ?: "识别完成"

        // 有警告就把每一处都摊开说，这是"日期不对要提示"的落点
        if (r.warnings.isEmpty()) {
            vb.boxWarnings.isVisible = false
            vb.tvHint.text = if (r.confidence >= ParseService.AUTO_THRESHOLD) {
                "$hintHead，确认无误就添加"
            } else {
                "$hintHead · 把握一般，请核对后再添加"
            }
        } else {
            vb.boxWarnings.isVisible = true
            vb.tvWarnings.text = r.warnings.joinToString("\n") { "· $it" }
            vb.tvHint.text = "$hintHead · 有 ${r.warnings.size} 处需要你核对"
        }

        vb.btnCancel.setOnClickListener { dlg.dismiss() }
        vb.btnSave.setOnClickListener {
            vb.btnSave.isEnabled = false
            saveFromSheet(vb, r) { ok ->
                vb.btnSave.isEnabled = true
                if (ok) dlg.dismiss()
            }
        }

        sheet = dlg
        dlg.show()
    }

    /**
     * 编辑已有日程。和识别结果面板是同一个 layout，只是按钮语义不同：
     * 左边从「取消」变「删除」，右边从「添加到日程」变「保存修改」。
     */
    private fun openEditor(event: Event) {
        showEditor(event, null)
    }

    /**
     * @param hint      覆盖面板底部的说明。改期确认流程用 —— 用户是从一条通知跳到
     *                  某条日程上的，得让他知道"为什么打开的是这一条"。
     * @param reviseKey 如果这次编辑来自"改期待办"，保存成功后把那条待办从队列里移除
     */
    private fun showEditor(event: Event, hint: String?, reviseKey: String? = null) {
        sheet?.dismiss()
        val (dlg, vb) = createSheet()

        val dt = TimeFmt.toDateTime(event.startAt)
        previewDate = dt.toLocalDate()
        previewTime = dt.toLocalTime()

        vb.tvSheetTitle.setText(R.string.editor_title)
        vb.etTitle.setText(event.title)
        vb.etLocation.setText(event.location.orEmpty())
        vb.cbAllDay.isChecked = !event.hasTime
        vb.btnTime.isEnabled = event.hasTime
        vb.btnTime.alpha = if (event.hasTime) 1f else 0.45f
        updateDateButton(vb)
        updateTimeButton(vb)

        // 编辑时回显这条自己选的方式；老数据是 -1（跟随设置）
        applyEventStyle(vb, event.remindStyle)

        // 分类回显。没动过就保持原值 —— 编辑标题不该顺手把分类改了
        val curCat = Category.of(event.category)
        applyCategory(vb, curCat, "当前分类：${getString(curCat.labelRes)}")

        // 原文有才显示，否则留一块空白很难看
        val note = event.note?.trim().orEmpty()
        vb.tvRaw.isVisible = note.isNotEmpty()
        vb.tvRaw.text = if (note.isEmpty()) "" else "原文：${note.take(120)}"

        // 待确认的理由还得说清楚，否则用户不知道自己在确认什么
        val reason = event.pendingReason
        if (event.pending && !reason.isNullOrBlank()) {
            vb.boxWarnings.isVisible = true
            vb.tvWarnings.text = reason
            vb.tvHint.text = "这条当时没把握 · 核对无误后保存，就会转正并排提醒"
        } else {
            vb.boxWarnings.isVisible = false
            vb.tvHint.text = "改完点保存，提醒会按新时间重新排"
        }
        if (hint != null) vb.tvHint.text = hint

        // 编辑模式下左按钮变成删除（点下去还会再弹一次确认）
        vb.btnCancel.setText(R.string.delete_confirm)
        vb.btnCancel.setTextColor(ContextCompat.getColor(this, R.color.warn))
        vb.btnCancel.setOnClickListener {
            dlg.dismiss()
            confirmDelete(event)
        }

        vb.btnSave.setText(R.string.action_save_edit)
        vb.btnSave.setOnClickListener {
            vb.btnSave.isEnabled = false
            saveEdit(vb, event) { ok ->
                vb.btnSave.isEnabled = true
                if (ok) {
                    reviseKey?.let(::forgetRevise)
                    dlg.dismiss()
                }
            }
        }

        sheet = dlg
        dlg.show()
    }

    // ---------------- 改期没认准：让用户选 ----------------

    /**
     * 处理「改期需要确认」通知带进来的 intent。
     *
     * 这条链路刻意**不落库**：通知被点开之前一直躺在系统通知栏里，
     * 参数存在 PendingIntent 的 extras 里，进程被杀也不会丢。
     * 应用内卡片（[ReviseInbox]）只是同一条信息的展示层，进程重启后为空。
     */
    private fun handleReviseIntent(intent: Intent?) {
        if (intent?.action != NotifyAction.ACTION_REVISE_CONFIRM) return
        // 消费掉，避免同一个 intent 被重复处理
        intent.action = null

        val text = intent.getStringExtra(NotifyAction.EXTRA_REVISE_TEXT).orEmpty()
        val oldStart = intent.getLongExtra(NotifyAction.EXTRA_REVISE_OLD_START, -1L)
            .takeIf { it > 0 }
        val newStart = intent.getLongExtra(NotifyAction.EXTRA_REVISE_NEW_START, -1L)
            .takeIf { it > 0 }
        val newHasTime = intent.getBooleanExtra(NotifyAction.EXTRA_REVISE_NEW_HAS_TIME, true)
        val guessed = intent.getBooleanExtra(NotifyAction.EXTRA_REVISE_MERIDIEM, false)
        val newLocation = intent.getStringExtra(NotifyAction.EXTRA_REVISE_NEW_LOCATION)

        Notifications.clearReviseAsk(this)
        val item = ReviseInbox.Item(
            key = TextNorm.key(text).ifEmpty { text },
            text = text,
            oldStartAt = oldStart,
            newStartAt = newStart,
            newHasTime = newHasTime,
            meridiemGuessed = guessed,
            newLocation = newLocation
        )
        // 冷启动时服务可能早被杀了，内存队列是空的；补一条，用户在确认框里点
        // "稍后"之后主界面还能看到这张卡片
        ReviseInbox.add(item)
        updateReviseBanner()
        openReviseItem(item)
    }

    /** 主界面横幅：列出内存里所有待处理的改期，选一条 */
    private fun openReviseInbox() {
        val items = ReviseInbox.snapshot()
        if (items.isEmpty()) {
            updateReviseBanner()
            toast("没有待处理的改期")
            return
        }
        if (items.size == 1) {
            openReviseItem(items[0])
            return
        }
        val labels = items.map {
            val t = it.text
            if (t.length > 24) "「${t.take(24)}…」" else "「$t」"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("选择要处理的改期")
            .setItems(labels) { _, i -> openReviseItem(items[i]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openReviseItem(item: ReviseInbox.Item) {
        lifecycleScope.launch {
            // 和自动改期用同一个候选口径，避免"自动说没有、这里又列出来"
            val cands = Repo.reviseCandidates(item.oldStartAt)
            if (cands.isEmpty()) {
                toast("没找到可能被改的日程，可以点开列表手动修改")
                ReviseInbox.remove(item.key)
                updateReviseBanner()
                return@launch
            }
            if (cands.size == 1) {
                askReviseOn(cands[0], item)
                return@launch
            }
            sheet?.dismiss()
            val labels = cands.map {
                "${it.title} · ${TimeFmt.summary(it.startAt, it.hasTime, null)}"
            }.toTypedArray()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("这条改期说的是哪一条？")
                .setItems(labels) { _, i -> askReviseOn(cands[i], item) }
                .setNegativeButton("稍后处理", null)
                .show()
        }
    }

    private fun askReviseOn(target: Event, item: ReviseInbox.Item) {
        val newStart = item.newStartAt
        val newLocation = item.newLocation

        if (newStart == null && newLocation == null) {
            // 原文只说了"推迟/取消"但没给可用信息 —— 送进编辑面板自己补。
            // 复用现有面板：查重、重置完成状态、重排提醒都在 saveEdit 里。
            toast("通知里没说清新时间/地点，请手动改")
            showEditor(
                target,
                "这是通知里提到的改期对象：把时间或地点改成新的后保存",
                item.key
            )
            return
        }

        val oldLabel = TimeFmt.summary(target.startAt, target.hasTime, target.location)
        val msg = buildString {
            append("「").append(target.title).append("」\n")
            if (newStart != null) {
                append(oldLabel).append("  →  ")
                    .append(TimeFmt.summary(newStart, item.newHasTime, newLocation ?: target.location))
                append("\n")
            }
            if (newLocation != null) {
                append("地点：").append(target.location ?: "（未填）")
                    .append("  →  ").append(newLocation).append("\n")
            }
            append("\n")
            if (item.meridiemGuessed) append("上下午是按原时间推的，请核对一眼。\n\n")
            append("通知原文：").append(item.text.take(120))
        }
        val timeChange = newStart != null
        MaterialAlertDialogBuilder(this)
            .setTitle(if (timeChange) "确认改期" else "确认换地点")
            .setMessage(msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (timeChange) "确认改期" else "确认换地点") { _, _ ->
                if (timeChange) applyRevise(target, item) else applyRelocate(target, item)
            }
            .show()
    }

    private fun applyRevise(target: Event, item: ReviseInbox.Item) {
        val newStart = item.newStartAt ?: return
        lifecycleScope.launch {
            val note = buildString {
                append(target.note.orEmpty())
                if (isNotEmpty()) append("\n\n")
                append("【据通知改期】").append(item.text.take(200))
            }
            val updated = Repo.reschedule(
                target.id, newStart, item.newHasTime, note, item.newLocation
            ) ?: run {
                toast("这条日程已经不在了")
                forgetRevise(item.key)
                return@launch
            }
            // 先撤旧闹钟再按新时间排：只 schedule 不 cancel 的话，
            // 若新时间被判定为"已开始"，旧闹钟会留在系统里
            ReminderScheduler.cancel(this@MainActivity, target.id)
            ReminderScheduler.schedule(this@MainActivity, updated)
            Notifications.clearRemind(this@MainActivity, target.id)
            forgetRevise(item.key)
            toast("已改期：${updated.title}")
            reload()
        }
    }

    private fun applyRelocate(target: Event, item: ReviseInbox.Item) {
        val newLocation = item.newLocation ?: return
        lifecycleScope.launch {
            val note = buildString {
                append(target.note.orEmpty())
                if (isNotEmpty()) append("\n\n")
                append("【据通知改地点】").append(item.text.take(200))
            }
            val updated = Repo.relocate(target.id, newLocation, note) ?: run {
                toast("这条日程已经不在了")
                forgetRevise(item.key)
                return@launch
            }
            forgetRevise(item.key)
            toast("已换地点：${updated.location}")
            reload()
        }
    }

    /** 处理完一条从内存队列里移除，并刷新横幅 */
    private fun forgetRevise(key: String) {
        ReviseInbox.remove(key)
        // 通知栏那条询问只有固定一个槽位，处理掉一条就撤掉，别让用户再点进空列表
        Notifications.clearReviseAsk(this)
        updateReviseBanner()
    }

    /** 主界面「改期待确认」横幅 */
    private fun updateReviseBanner() {
        val n = ReviseInbox.snapshot().size
        val show = n > 0 && !selectionMode && !searchMode
        b.bannerRevise.isVisible = show
        if (show) {
            b.tvReviseTitle.text = "有 $n 条改期需要确认"
            b.tvReviseDesc.text = "点我选择是哪一条日程"
        }
    }

    /** 两个入口共用的面板骨架：亚克力底、展开状态、日期时间按钮、全天联动 */
    private fun createSheet(): Pair<BottomSheetDialog, SheetPreviewBinding> {
        val dlg = BottomSheetDialog(this)
        val vb = SheetPreviewBinding.inflate(layoutInflater)
        dlg.setContentView(vb.root)
        dlg.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dlg.setOnShowListener {
            // 一打开就展开，别让用户再拖一下
            dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dlg.behavior.skipCollapsed = true
            // 弹层默认是 Material 的纯白圆角底，换成磨砂玻璃，跟主界面才是一套
            dlg.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundResource(R.drawable.bg_acrylic_sheet)
        }

        // 分类：程序化回显（自动判断/编辑回显）不算"用户选过"，
        // 否则自动判断出来的那一档会被存成用户明确选择，以后规则改了也不会跟着变
        sheetCatTouched = false
        vb.cgCategory.setOnCheckedStateChangeListener { _, _ ->
            if (!catApplying) sheetCatTouched = true
        }

        // 全天开关 -> 时间按钮联动；日期/时间选择器。两个入口行为必须一致
        vb.cbAllDay.setOnCheckedChangeListener { _, checked ->
            vb.btnTime.isEnabled = !checked
            vb.btnTime.alpha = if (checked) 0.45f else 1f
        }
        vb.btnDate.setOnClickListener { pickDate(vb, ::updateDateButton) }
        vb.btnTime.setOnClickListener { pickTime(vb, ::updateTimeButton) }

        // 单条通知方式：勾选框管"跟不跟默认"，四档按钮管"跟不跟、跟哪档"。
        // 点任意一档就等于明确表态要覆盖默认，所以顺手把勾去掉 —— 不然用户点了按钮
        // 却发现没生效（勾还打着），会以为坏了。
        vb.tgEventStyle.addOnButtonCheckedListener { _, _, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (vb.cbFollowStyle.isChecked) vb.cbFollowStyle.isChecked = false
            updateEventStyleDesc(vb)
        }
        vb.cbFollowStyle.setOnCheckedChangeListener { _, checked ->
            vb.tgEventStyle.isEnabled = !checked
            vb.tgEventStyle.alpha = if (checked) 0.45f else 1f
            updateEventStyleDesc(vb)
        }
        return dlg to vb
    }

    // ---------------- 分类 ----------------

    private fun chipIdOf(cat: Category): Int = when (cat) {
        Category.STUDY -> R.id.chipCatStudy
        Category.WORK -> R.id.chipCatWork
        Category.CLUB -> R.id.chipCatClub
        Category.LIFE -> R.id.chipCatLife
        Category.OTHER -> R.id.chipCatOther
    }

    private fun selectedCategory(vb: SheetPreviewBinding): Category =
        Category.of(
            when (vb.cgCategory.checkedChipId) {
                R.id.chipCatStudy -> Category.STUDY.key
                R.id.chipCatWork -> Category.WORK.key
                R.id.chipCatClub -> Category.CLUB.key
                R.id.chipCatLife -> Category.LIFE.key
                else -> Category.OTHER.key
            }
        )

    /** 回显分类。程序化勾选不算"用户选过"（见 catApplying 的注释） */
    private fun applyCategory(vb: SheetPreviewBinding, cat: Category, hint: String? = null) {
        catApplying = true
        vb.cgCategory.check(chipIdOf(cat))
        catApplying = false
        vb.tvCatHint.text = hint ?: getString(R.string.cat_auto_hint)
    }

    // ---------------- 单条通知方式 ----------------

    /** 档位数字 -> 按钮 id。不认识的（含 -1）按全屏定位，只在回显时用 */
    private fun styleButtonId(v: Int): Int = when (RemindStyle.from(v)) {
        RemindStyle.FULLSCREEN -> R.id.btnStyleFull
        RemindStyle.BANNER -> R.id.btnStyleBanner
        RemindStyle.VIBRATE -> R.id.btnStyleVibrate
        RemindStyle.SILENT -> R.id.btnStyleSilent
    }

    /** 按钮 id -> 存下来的数字 */
    private fun styleValueOf(id: Int): Int = when (id) {
        R.id.btnStyleFull -> RemindStyle.FULLSCREEN.value
        R.id.btnStyleBanner -> RemindStyle.BANNER.value
        R.id.btnStyleVibrate -> RemindStyle.VIBRATE.value
        R.id.btnStyleSilent -> RemindStyle.SILENT.value
        else -> RemindStyle.FULLSCREEN.value
    }

    private fun styleNameRes(s: RemindStyle): Int = when (s) {
        RemindStyle.FULLSCREEN -> R.string.remind_style_full
        RemindStyle.BANNER -> R.string.remind_style_banner
        RemindStyle.VIBRATE -> R.string.remind_style_vibrate
        RemindStyle.SILENT -> R.string.remind_style_silent
    }

    private fun styleDescRes(s: RemindStyle): Int = when (s) {
        RemindStyle.FULLSCREEN -> R.string.remind_style_desc_full
        RemindStyle.BANNER -> R.string.remind_style_desc_banner
        RemindStyle.VIBRATE -> R.string.remind_style_desc_vibrate
        RemindStyle.SILENT -> R.string.remind_style_desc_silent
    }

    /** 当前面板上选出来的存库值；-1 表示跟随设置 */
    private fun currentEventStyle(vb: SheetPreviewBinding): Int =
        if (vb.cbFollowStyle.isChecked) RemindStyle.FOLLOW_DEFAULT
        else styleValueOf(vb.tgEventStyle.checkedButtonId)

    /** 把某条日程已存的方式回显到面板上（新建时传 -1） */
    private fun applyEventStyle(vb: SheetPreviewBinding, stored: Int) {
        val follow = stored < 0
        vb.cbFollowStyle.isChecked = follow
        // 跟随的时候按钮组也得停在默认那一档上，否则用户看不出"跟的是哪一档"
        vb.tgEventStyle.check(styleButtonId(if (follow) Prefs.remindStyle else stored))
        vb.tgEventStyle.isEnabled = !follow
        vb.tgEventStyle.alpha = if (follow) 0.45f else 1f
        updateEventStyleDesc(vb)
    }

    /**
     * 弹层里那一小行描述，和设置页共用同一套文案（remind_style_desc_*），
     * 两边说法必须一致。跟随设置时要告诉用户"默认现在是哪档"，不然选了个寂寞。
     */
    private fun updateEventStyleDesc(vb: SheetPreviewBinding) {
        val v = currentEventStyle(vb)
        val global = RemindStyle.from(Prefs.remindStyle)
        vb.tvStyleDesc.text = if (v < 0) {
            getString(R.string.sheet_style_default_desc, getString(styleNameRes(global))) +
                "\n" + getString(styleDescRes(global))
        } else {
            getString(styleDescRes(RemindStyle.from(v)))
        }
    }

    /** 保存编辑结果：更新库、重排提醒、必要时把完成状态复位 */
    private fun saveEdit(vb: SheetPreviewBinding, old: Event, done: (Boolean) -> Unit) {
        val date = previewDate
        if (date == null) {
            toast("请先选择日期")
            done(false)
            return
        }
        val hasTime = !vb.cbAllDay.isChecked
        val time = if (hasTime) (previewTime ?: LocalTime.of(9, 0)) else LocalTime.of(9, 0)
        val title = vb.etTitle.text?.toString()?.trim().orEmpty().ifEmpty { "未命名日程" }
        val location = vb.etLocation.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val startAt = LocalDateTime.of(date, time)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // 时间变了才重置完成状态，道理见 EventEdit.shouldResetDone 的注释
        val timeChanged = EventEdit.shouldResetDone(
            old.startAt, old.hasTime, startAt, hasTime
        )
        // 时长跟着开始时间走：原来有结束时间就保持同样时长；原来没有就保持 0
        val duration = (old.endAt - old.startAt).takeIf { it > 0 } ?: 0L

        val updated = old.copy(
            title = title,
            startAt = startAt,
            hasTime = hasTime,
            endAt = if (hasTime && duration > 0) startAt + duration else 0L,
            location = location,
            // 编辑即确认：用户已经亲手核对过了，不该再挂着"待确认"
            pending = false,
            pendingReason = null,
            // 同理：他亲手改过这条，说明还认它，"据通知已取消"的标记要撤掉
            revoked = false,
            revokeReason = null,
            done = if (timeChanged) false else old.done,
            remindStyle = currentEventStyle(vb),
            // 没亲手改过就保持原分类（老数据可能是手动选的，重新猜一遍等于覆盖用户）
            category = if (sheetCatTouched) selectedCategory(vb).key else old.category
        )

        lifecycleScope.launch {
            try {
                // 排除自己：只改地点/标题时，本来就会查到自己这一条
                val dup = Repo.findSameEvent(title, startAt, updated.location, excludeId = old.id)
                if (dup != null) {
                    toast("那个时间已经有「${dup.title}」了，换个标题或时间")
                    done(false)
                    return@launch
                }
                Repo.update(updated)
                // 原来挂着"待确认"的，用户在面板里改完就等于确认了，通知要撤掉
                if (old.pending) Notifications.clearPending(this@MainActivity, old.id)
                // 先撤旧提醒再按新时间排，否则旧闹钟会留在系统里继续响
                ReminderScheduler.cancel(this@MainActivity, old.id)
                // 通知栏里那条到点提醒是旧时间的，内容已经不对了
                Notifications.clearRemind(this@MainActivity, old.id)
                if (!updated.done) ReminderScheduler.schedule(this@MainActivity, updated)
                toast("已保存：$title")
                reload()
                done(true)
            } catch (e: Exception) {
                toast("保存失败：${e.message}")
                done(false)
            }
        }
    }

    private fun pickDate(vb: SheetPreviewBinding, refresh: (SheetPreviewBinding) -> Unit) {
        val cur = previewDate ?: LocalDate.now()
        DatePickerDialog(
            this,
            { _, y, m, d ->
                previewDate = LocalDate.of(y, m + 1, d)
                refresh(vb)
            },
            cur.year, cur.monthValue - 1, cur.dayOfMonth
        ).show()
    }

    private fun pickTime(vb: SheetPreviewBinding, refresh: (SheetPreviewBinding) -> Unit) {
        val cur = previewTime ?: LocalTime.of(9, 0)
        TimePickerDialog(
            this,
            { _, h, mi ->
                previewTime = LocalTime.of(h, mi)
                vb.cbAllDay.isChecked = false
                refresh(vb)
            },
            cur.hour, cur.minute, true
        ).show()
    }

    private fun updateDateButton(vb: SheetPreviewBinding) {
        vb.btnDate.text = TimeFmt.dateLabel(previewDate ?: LocalDate.now())
    }

    private fun updateTimeButton(vb: SheetPreviewBinding) {
        val t = previewTime ?: LocalTime.of(9, 0)
        vb.btnTime.text = String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
    }

    // ---------------- 保存 ----------------

    private fun saveFromSheet(
        vb: SheetPreviewBinding,
        r: DateTimeParser.Result,
        done: (Boolean) -> Unit
    ) {
        val date = previewDate
        if (date == null) {
            toast("请先选择日期")
            done(false)
            return
        }
        val hasTime = !vb.cbAllDay.isChecked
        val time = if (hasTime) (previewTime ?: LocalTime.of(9, 0)) else LocalTime.of(9, 0)
        val title = vb.etTitle.text?.toString()?.trim().orEmpty().ifEmpty { "未命名日程" }
        val location = vb.etLocation.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val startAt = LocalDateTime.of(date, time)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val event = Event(
            title = title,
            startAt = startAt,
            hasTime = hasTime,
            location = location,
            note = lastRaw.takeIf { it.isNotBlank() }?.take(500),
            source = Event.SRC_MANUAL,
            remindMinutes = Prefs.defaultRemind,
            remindStyle = currentEventStyle(vb),
            pending = false,
            pendingReason = null,
            // 没选就按内容判断：手输"明天高数作业"也能自动落到学习
            category = if (sheetCatTouched) {
                selectedCategory(vb).key
            } else {
                CategoryRules.classify("$title ${location.orEmpty()}").key
            }
        )

        lifecycleScope.launch {
            // 手动添加也要去重。以前只有通知抓取会查，走一遍"识别 → 添加"
            // 就能把同一条日程加出两份。
            val dup = Repo.findSameEvent(title, startAt, event.location)
            if (dup != null) {
                toast("已经有了：${TimeFmt.summary(dup.startAt, dup.hasTime, dup.location)}")
                done(false)
                return@launch
            }

            // 和通知抓取同一套冲突判断：同一时间、或名称+地点高度重合时，
            // 先让用户确认。手动添加不标 pending —— 用户已经在面板里确认过，
            // 这里只需要确认"知道还有另一条相似的"。
            val conflict = EventConflict.check(event, Repo.active(), System.currentTimeMillis())
            if (conflict != null) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("可能有冲突")
                    .setMessage(EventConflict.reason(conflict) + "\n\n仍要添加吗？")
                    .setNegativeButton(R.string.cancel) { _, _ -> done(false) }
                    .setPositiveButton("仍要添加") { _, _ ->
                        insertManual(event, title, done)
                    }
                    .show()
                return@launch
            }
            insertManual(event, title, done)
        }
    }

    /** 手动添加的最后一步。冲突弹窗确认后也走这里，避免两份插入代码走岔 */
    private fun insertManual(event: Event, title: String, done: (Boolean) -> Unit) {
        lifecycleScope.launch {
            try {
                val id = Repo.insert(event)
                ReminderScheduler.schedule(this@MainActivity, event.copy(id = id))
                b.etInput.text?.clear()
                toast("已添加：$title")
                reload()
                done(true)
            } catch (e: Exception) {
                toast("保存失败：${e.message}")
                done(false)
            }
        }
    }

    // ---------------- 检索 ----------------

    /**
     * 打开检索。
     *
     * 检索条占据标题行的位置，同时把输入卡收起来 —— 两个都是输入框，
     * 同屏会抢焦点，键盘一弹列表就只剩一条缝。
     */
    private fun openSearch() {
        if (searchMode) {
            focusSearch()
            return
        }
        applySearchUi(true)
        focusSearch()
        // 不进 reload：查询还是空的，列表内容一个字都没变，只是界面换了形态
    }

    private fun closeSearch() {
        if (!searchMode) return
        // 顺序有讲究：先收键盘（这时 searchMode 还是 true，才找得到检索框的窗口），
        // 再切界面。反过来的话键盘会挂在已经隐藏的输入框上，屏幕下半截一直是空的
        hideKeyboard()
        applySearchUi(false)
        reload()
    }

    /** 只切界面形态和状态位，不动列表。进/出检索共用 */
    private fun applySearchUi(on: Boolean) {
        searchMode = on
        b.searchBar.isVisible = on
        b.titleBar.isVisible = !on
        b.inputCard.isVisible = !on
        searchBackCallback.isEnabled = on
        b.tvSearchScope.isVisible = false
        if (!on) {
            // 退检索就清空关键词。先撤掉去抖任务，否则刚敲的那个字会在
            // 退出之后再触发一次带旧词的 reload，列表闪一下才回来
            searchHandler.removeCallbacks(searchTick)
            if (!b.etSearch.text.isNullOrEmpty()) b.etSearch.setText("")
            searchQuery = ""
        }
        // 待确认横幅在检索时是噪声：它是"去处理数据"的入口，不是结果
        updatePendingBanner()
    }

    /** 去抖到点后真正生效。词没变就不重查 —— 光标移动也会触发文本回调 */
    private fun commitSearch(raw: String) {
        val q = raw.trim()
        if (q == searchQuery) return
        searchQuery = q
        reload()
    }

    private fun focusSearch() {
        b.etSearch.requestFocus()
        // 等一帧再弹键盘：刚 setVisibility 的控件在这一帧还没布局好，
        // 立刻 showSoftInput 有时会被系统忽略
        b.etSearch.post {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.showSoftInput(b.etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---------------- 列表 ----------------

    private fun reload() {
        lifecycleScope.launch {
            // 主列表不含归档；归档的条目在「归档日志」里看
            val all = Repo.active()
            // 筛选只影响列表显示；待确认计数和选中态都按当前列表全量算 ——
            // 不然"筛到学习"时横幅数字会变，看着像数据丢了
            var list = filterCat?.let { c -> all.filter { Category.of(it.category) == c } } ?: all

            // 检索叠在分类筛选之上（先分类、再关键词）：chip 是"看哪一类"，
            // 关键词是"在哪一类里找"，两个一起用才有意义
            val tokens = EventSearch.tokens(searchQuery)
            if (tokens.isNotEmpty()) {
                // 分类名也参与匹配：输"社团"能把社团类全捞出来，不用先点 chip
                val catLabels = Category.entries.associate { it.key to getString(it.labelRes) }
                list = list.filter { e ->
                    EventSearch.matches(e, tokens, catLabels[Category.of(e.category).key])
                }
            }

            lastPendingCount = all.count { it.pending }
            adapter.submit(buildRows(list), tokens)
            b.emptyView.isVisible = list.isEmpty()
            applyEmptyText(all, list, tokens)
            updatePendingBanner()

            // 列表变了（新通知进来、别处删了、通知栏点了确认），选中的条目可能已经不在。
            // 不清理的话，删除时会拿一堆失效 id 去查库。
            if (selectionMode) {
                val alive = all.mapTo(HashSet()) { it.id }
                selectedIds.retainAll(alive)
                if (selectedIds.isEmpty()) exitSelection() else syncSelectionUi()
            }
        }
    }

    /**
     * 空态与结果条数。
     *
     * 四种"没东西"必须说四种不同的话，否则用户不知道该干什么：
     * 搜不到（换词）、筛空了（换分类）、两个都开着（退一个）、真的没数据（去建一条）。
     */
    private fun applyEmptyText(all: List<Event>, list: List<Event>, tokens: List<String>) {
        val searching = tokens.isNotEmpty()
        val cat = filterCat

        // 条数只在有结果时显示。0 条时下面那块空态已经说了，再来一行"找到 0 条"是废话
        val showScope = searching && list.isNotEmpty()
        b.tvSearchScope.isVisible = showScope
        if (showScope) {
            b.tvSearchScope.text = if (cat != null) {
                getString(R.string.search_count_scoped, list.size, getString(cat.labelRes))
            } else {
                getString(R.string.search_count, list.size)
            }
        }

        b.tvEmptyTitle.text = when {
            list.isEmpty() && searching && cat != null ->
                getString(R.string.search_empty_scoped, getString(cat.labelRes), searchQuery)
            list.isEmpty() && searching -> getString(R.string.search_empty, searchQuery)
            list.isEmpty() && all.isNotEmpty() ->
                "「${getString(cat?.labelRes ?: R.string.filter_all)}」里还没有日程"
            else -> getString(R.string.empty_title)
        }
        b.tvEmptyHint.text = if (list.isEmpty() && searching && all.isNotEmpty()) {
            getString(R.string.search_empty_hint)
        } else {
            getString(R.string.empty_hint)
        }
    }

    /**
     * 待确认横幅的显隐。
     * 多选和检索时一律隐藏 —— 那条横幅是"去处理数据"的入口，这两种状态下它是干扰。
     */
    private fun updatePendingBanner() {
        val show = lastPendingCount > 0 && !selectionMode && !searchMode
        b.bannerPending.isVisible = show
        if (show) {
            b.tvBannerTitle.text = "有 $lastPendingCount 条识别结果待确认"
            b.tvBannerDesc.text = "日期是我推算的，或者原文有对不上的地方，点开核对"
        }
        updateReviseBanner()
    }

    private fun buildRows(list: List<Event>): List<Row> {
        val rows = ArrayList<Row>(list.size + 8)
        var lastDate: LocalDate? = null
        for (e in list) {
            val d = TimeFmt.toDate(e.startAt)
            if (d != lastDate) {
                rows.add(Row.Header(d))
                lastDate = d
            }
            rows.add(Row.Item(e))
        }
        return rows
    }

    /**
     * 右侧框：归档。
     *
     * 不是"标记完成" —— 点一下这条就从主列表消失，进「归档日志」，
     * 在那边可以恢复或彻底删除。归档会停掉闹钟和通知栏里的提醒/待确认。
     */
    private fun archiveEvent(event: Event) {
        lifecycleScope.launch {
            Repo.archive(event.id)
            ReminderScheduler.cancel(this@MainActivity, event.id)
            Notifications.clearRemind(this@MainActivity, event.id)
            Notifications.clearPending(this@MainActivity, event.id)
            toast("已归档：${event.title}")
            reload()
        }
    }

    private fun toggleDone(event: Event) {
        val nowDone = !event.done
        lifecycleScope.launch {
            Repo.setDone(event.id, nowDone)
            if (nowDone) {
                ReminderScheduler.cancel(this@MainActivity, event.id)
                // 手动勾完成，通知栏里那条到点提醒也该消失
                Notifications.clearRemind(this@MainActivity, event.id)
            } else {
                ReminderScheduler.schedule(this@MainActivity, event.copy(done = false))
            }
            reload()
        }
    }

    private fun confirmDelete(event: Event) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(event.title)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                lifecycleScope.launch {
                    Repo.delete(event.id)
                    ReminderScheduler.cancel(this@MainActivity, event.id)
                    // 删了就把通知栏里那两条都撤掉，否则点了通知发现日程已经没了
                    Notifications.clearPending(this@MainActivity, event.id)
                    Notifications.clearRemind(this@MainActivity, event.id)
                    reload()
                }
            }
            .show()
    }

    // ---------------- 多选删除 ----------------

    /**
     * 长按任意一条进入多选，并把它选上。
     *
     * 之前长按是"删除这一条（二次确认）"。改成多选入口是因为：
     * 单条删除在编辑面板里已经有一个「删除」按钮，而批量删没有别的路径 ——
     * 而且长按进多选是 Android 上的通行做法（相册、邮箱都这样）。
     */
    private fun enterSelection(event: Event) {
        selectionMode = true
        selectedIds.clear()
        selectedIds.add(event.id)
        backCallback.isEnabled = true
        b.titleBar.isVisible = false
        b.selectBar.isVisible = true
        // 检索条也收起来：多选时它占的那一行会把列表挤下去，而且搜到一半
        // 进多选删东西，删完列表跟着查询变，容易误删
        b.searchBar.isVisible = false
        // 输入卡和待确认横幅一起收起来：多选时还能"识别"会叠出一个面板，
        // 状态就乱了；横幅是"去处理数据"的入口，这时候也是干扰
        b.inputCard.isVisible = false
        updatePendingBanner()
        syncSelectionUi()
    }

    /** 多选模式下点 / 长按一条：切换它的选中状态。取消到空就退出多选 */
    private fun toggleSelection(event: Event) {
        if (!selectedIds.remove(event.id)) selectedIds.add(event.id)
        if (selectedIds.isEmpty()) {
            exitSelection()
        } else {
            syncSelectionUi()
        }
    }

    private fun exitSelection() {
        selectionMode = false
        selectedIds.clear()
        backCallback.isEnabled = false
        // 从检索态进的多选，退出来要回到检索态，而不是把标题栏顶回来
        b.titleBar.isVisible = !searchMode
        b.selectBar.isVisible = false
        b.searchBar.isVisible = searchMode
        b.inputCard.isVisible = !searchMode
        updatePendingBanner()
        // 必须通知适配器：所有条目的勾选框可见性和背景都要回到普通状态
        adapter.setSelection(false, emptySet())
    }

    /** 把选中数量、全选按钮文案、删除按钮可用性刷成当前状态 */
    private fun syncSelectionUi() {
        b.tvSelectCount.text = if (selectedIds.isEmpty()) {
            getString(R.string.select_none)
        } else {
            getString(R.string.select_count, selectedIds.size)
        }

        val allIds = adapter.allEventIds()
        // 全选按钮的文案跟着状态走：全选中时它是"取消全选"
        b.btnSelectAll.text = if (allIds.isNotEmpty() && selectedIds.size >= allIds.size) {
            getString(R.string.select_none_all)
        } else {
            getString(R.string.select_all)
        }
        // TextView 设 isEnabled 不会有任何视觉变化，得自己给个提示
        val hasSel = selectedIds.isNotEmpty()
        b.btnSelectDelete.isEnabled = hasSel
        b.btnSelectDelete.alpha = if (hasSel) 1f else 0.4f

        adapter.setSelection(true, selectedIds.toSet())
    }

    private fun toggleSelectAll() {
        val allIds = adapter.allEventIds()
        if (allIds.isEmpty()) return
        if (selectedIds.size >= allIds.size) {
            // 全不选 = 取消全部选中，但停留在多选模式里（用户可能想反选几条）
            selectedIds.clear()
        } else {
            selectedIds.addAll(allIds)
        }
        syncSelectionUi()
    }

    private fun confirmDeleteSelected() {
        if (selectedIds.isEmpty()) return
        val n = selectedIds.size
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_selected_title, n))
            .setMessage(R.string.delete_selected_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_confirm) { _, _ -> deleteSelected() }
            .show()
    }

    /**
     * 真正的删除。顺序很重要：**先撤提醒和通知，再删库**。
     * 反过来的话库里已经查不到这些 id，闹钟会留在系统里继续响。
     */
    private fun deleteSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            val ok = runCatching {
                ids.forEach { id ->
                    ReminderScheduler.cancel(this@MainActivity, id)
                    // 待确认和到点提醒是两条不同的通知，都要撤
                    Notifications.clearPending(this@MainActivity, id)
                    Notifications.clearRemind(this@MainActivity, id)
                }
                Repo.deleteMany(ids)
            }.isSuccess

            exitSelection()
            reload()
            toast(if (ok) "已删除 ${ids.size} 条" else "删除失败")
        }
    }

    // ---------------- 杂项 ----------------

    private fun setBusy(busy: Boolean) {
        b.btnParse.isEnabled = !busy
        b.btnParse.text = if (busy) "识别中…" else getString(R.string.action_parse)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        // 检索时焦点在检索框上，光收输入卡的键盘没用
        val target = if (searchMode) b.etSearch else b.etInput
        imm?.hideSoftInputFromWindow(target.windowToken, 0)
        target.clearFocus()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Notifications.hasPermission(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
