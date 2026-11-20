package com.terry.smartschedule.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
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
 * 每条可以恢复到主列表，或彻底删除。
 */
class ArchiveActivity : AppCompatActivity() {

    private lateinit var b: ActivityArchiveBinding
    private lateinit var adapter: ArchiveAdapter

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

        b.btnBack.setOnClickListener { finish() }
        adapter = ArchiveAdapter(
            onRestore = ::restore,
            onDelete = ::confirmDelete
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
            adapter.submit(list)
            b.tvEmpty.isVisible = list.isEmpty()
        }
    }

    private fun restore(event: Event) {
        lifecycleScope.launch {
            Repo.unarchive(event.id)
            // 恢复后如果还是未来、未取消、非待确认，把提醒重新排上；
            // unarchive 已把 done 清零（新语义里"结束"=归档，done 是老字段）
            if (!event.pending && !event.revoked) {
                ReminderScheduler.schedule(
                    this@ArchiveActivity,
                    event.copy(archived = false, done = false)
                )
            }
            toast("已恢复：${event.title}")
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
                    Notifications.clearRemind(this@ArchiveActivity, event.id)
                    Notifications.clearPending(this@ArchiveActivity, event.id)
                    toast("已删除：${event.title}")
                    reload()
                }
            }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

/** 归档列表适配器：一行一条，右侧「恢复 / 删除」 */
private class ArchiveAdapter(
    private val onRestore: (Event) -> Unit,
    private val onDelete: (Event) -> Unit
) : RecyclerView.Adapter<ArchiveAdapter.VH>() {

    private val items = ArrayList<Event>()

    fun submit(list: List<Event>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

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
            b.tvMeta.text = TimeFmt.summary(event.startAt, event.hasTime, event.location)
            b.btnRestore.setOnClickListener { onRestore(event) }
            b.btnDelete.setOnClickListener { onDelete(event) }
        }
    }
}
