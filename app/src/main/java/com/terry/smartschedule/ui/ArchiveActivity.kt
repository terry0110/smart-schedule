package com.terry.smartschedule.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.terry.smartschedule.R
import com.terry.smartschedule.data.Event
import com.terry.smartschedule.data.Repo
import com.terry.smartschedule.databinding.ActivityArchiveBinding
import com.terry.smartschedule.databinding.ItemArchiveBinding
import com.terry.smartschedule.notify.Notifications
import com.terry.smartschedule.notify.ReminderScheduler
import com.terry.smartschedule.util.Insets
import com.terry.smartschedule.util.TimeFmt
import kotlinx.coroutines.launch

/**
 * 归档日志：从主列表右侧归档框丢进来的日程都在这里。
 *
 * 归档不是删除：数据还在库里，只是主列表不显示、也不再提醒。
 * 每条可以恢复到主列表，或彻底删除；顶部支持多选批量删除和删除全部。
 */
class ArchiveActivity : AppCompatActivity() {

    private lateinit var b: ActivityArchiveBinding
    private lateinit var adapter: ArchiveAdapter
    private lateinit var backCallback: OnBackPressedCallback

    private var selectionMode = false
    private val selectedIds = linkedSetOf<Long>()
    private var hasItems = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.edgeToEdge(this)
        b = ActivityArchiveBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.apply(
            this, b.root,
            statusBarSpacer = b.statusBarSpace,
            bottomTarget = b.rvArchive,
            bottomExtraDp = 20
        )

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = exitSelection()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        b.btnBack.setOnClickListener { finish() }
        b.btnSelect.setOnClickListener { enterSelection(null) }
        b.btnDeleteAll.setOnClickListener { confirmDeleteAll() }
        b.btnSelectClose.setOnClickListener { exitSelection() }
        b.btnSelectAll.setOnClickListener { toggleSelectAll() }
        b.btnSelectRestore.setOnClickListener { restoreSelected() }
        b.btnSelectDelete.setOnClickListener { confirmDeleteSelected() }

        adapter = ArchiveAdapter(
            onRestore = ::restore,
            onDelete = ::confirmDelete,
            onEnterSelection = { enterSelection(it) },
            onToggleSelection = ::toggleSelection
        )
        b.rvArchive.layoutManager = LinearLayoutManager(this)
        b.rvArchive.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val list = Repo.archived()
            hasItems = list.isNotEmpty()
            adapter.submit(list)
            b.tvEmpty.isVisible = list.isEmpty()

            // 列表变了：被恢复/删除的选中项要清掉，选空了就退出多选
            if (selectionMode) {
                val alive = list.mapTo(HashSet()) { it.id }
                selectedIds.retainAll(alive)
                if (selectedIds.isEmpty()) exitSelection() else syncSelectionUi()
            }
            updateHeaderActions()
        }
    }

    private fun updateHeaderActions() {
        b.btnSelect.isVisible = hasItems && !selectionMode
        b.btnDeleteAll.isVisible = hasItems && !selectionMode
    }

    private fun enterSelection(event: Event?) {
        selectionMode = true
        selectedIds.clear()
        event?.let { selectedIds.add(it.id) }
        backCallback.isEnabled = true
        b.selectBar.isVisible = true
        updateHeaderActions()
        syncSelectionUi()
    }

    private fun exitSelection() {
        selectionMode = false
        selectedIds.clear()
        backCallback.isEnabled = false
        b.selectBar.isVisible = false
        updateHeaderActions()
        adapter.setSelection(false, emptySet())
    }

    private fun toggleSelection(event: Event) {
        if (!selectedIds.remove(event.id)) selectedIds.add(event.id)
        if (selectedIds.isEmpty()) {
            exitSelection()
        } else {
            syncSelectionUi()
        }
    }

    private fun toggleSelectAll() {
        val allIds = adapter.allIds()
        if (allIds.isEmpty()) return
        if (selectedIds.size >= allIds.size) {
            selectedIds.clear()
        } else {
            selectedIds.addAll(allIds)
        }
        syncSelectionUi()
    }

    private fun syncSelectionUi() {
        b.tvSelectCount.text = if (selectedIds.isEmpty()) {
            getString(R.string.select_none)
        } else {
            getString(R.string.select_count, selectedIds.size)
        }
        val allIds = adapter.allIds()
        b.btnSelectAll.text = if (allIds.isNotEmpty() && selectedIds.size >= allIds.size) {
            getString(R.string.select_none_all)
        } else {
            getString(R.string.select_all)
        }
        val hasSel = selectedIds.isNotEmpty()
        b.btnSelectRestore.isEnabled = hasSel
        b.btnSelectRestore.alpha = if (hasSel) 1f else 0.4f
        b.btnSelectDelete.isEnabled = hasSel
        b.btnSelectDelete.alpha = if (hasSel) 1f else 0.4f
        adapter.setSelection(true, selectedIds.toSet())
    }

    private fun restore(event: Event) {
        lifecycleScope.launch {
            restoreEvents(listOf(event))
            toast("已恢复：${event.title}")
            reload()
        }
    }

    /** 恢复多选中的条目：先置回未完成/未归档，再按需重排提醒 */
    private fun restoreSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            val events = Repo.archived().filter { it.id in ids }
            restoreEvents(events)
            exitSelection()
            reload()
            toast("已恢复 ${events.size} 条")
        }
    }

    private suspend fun restoreEvents(events: List<Event>) {
        events.forEach { ev ->
            Repo.unarchive(ev.id)
            // 恢复后如果还是未来、未取消、非待确认，把提醒重新排上；
            // unarchive 已把 done 清零（新语义里"结束"=归档，done 是老字段）
            if (!ev.pending && !ev.revoked) {
                ReminderScheduler.schedule(
                    this,
                    ev.copy(archived = false, done = false)
                )
            }
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
                    Notifications.clearRemind(this@ArchiveActivity, event.id)
                    Notifications.clearPending(this@ArchiveActivity, event.id)
                    toast("已删除：${event.title}")
                    reload()
                }
            }
            .show()
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

    private fun deleteSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            cancelFor(ids)
            Repo.deleteMany(ids)
            exitSelection()
            reload()
            toast("已删除 ${ids.size} 条")
        }
    }

    private fun confirmDeleteAll() {
        lifecycleScope.launch {
            val list = Repo.archived()
            if (list.isEmpty()) {
                toast("归档日志是空的")
                return@launch
            }
            MaterialAlertDialogBuilder(this@ArchiveActivity)
                .setTitle(getString(R.string.delete_all_title, list.size))
                .setMessage(R.string.delete_all_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_confirm) { _, _ ->
                    lifecycleScope.launch {
                        val ids = list.map { it.id }
                        cancelFor(ids)
                        Repo.deleteMany(ids)
                        exitSelection()
                        reload()
                        toast("已删除 ${ids.size} 条")
                    }
                }
                .show()
        }
    }

    /** 先撤提醒和通知，再删库；顺序反了会留下继续响的闹钟 */
    private fun cancelFor(ids: List<Long>) {
        ids.forEach {
            ReminderScheduler.cancel(this, it)
            Notifications.clearRemind(this, it)
            Notifications.clearPending(this, it)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

/** 归档列表适配器：一行一条，普通模式右侧「恢复 / 删除」，多选模式左侧勾选 */
private class ArchiveAdapter(
    private val onRestore: (Event) -> Unit,
    private val onDelete: (Event) -> Unit,
    private val onEnterSelection: (Event) -> Unit,
    private val onToggleSelection: (Event) -> Unit
) : RecyclerView.Adapter<ArchiveAdapter.VH>() {

    private val items = ArrayList<Event>()
    private var selectionMode = false
    private var selectedIds: Set<Long> = emptySet()

    fun submit(list: List<Event>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setSelection(mode: Boolean, ids: Set<Long>) {
        selectionMode = mode
        selectedIds = ids
        notifyDataSetChanged()
    }

    fun allIds(): List<Long> = items.map { it.id }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemArchiveBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val b: ItemArchiveBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(event: Event) {
            b.tvTime.text = TimeFmt.timeLabel(event.startAt, event.hasTime)
            b.tvTitle.text = event.title
            b.tvMeta.text = TimeFmt.rangeSummary(
                event.startAt, event.endAt, event.hasTime, event.location
            )

            val selected = selectionMode && event.id in selectedIds
            b.cbSelect.isVisible = selectionMode
            b.cbSelect.isChecked = selected
            b.cbSelect.setOnClickListener {
                if (selectionMode) onToggleSelection(event) else onEnterSelection(event)
            }

            b.btnRestore.isVisible = !selectionMode
            b.btnDelete.isVisible = !selectionMode
            b.btnRestore.setOnClickListener { onRestore(event) }
            b.btnDelete.setOnClickListener { onDelete(event) }
            b.root.setOnClickListener {
                if (selectionMode) onToggleSelection(event)
            }
            b.root.setOnLongClickListener {
                if (!selectionMode) onEnterSelection(event)
                true
            }
        }
    }
}
