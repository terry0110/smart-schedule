package com.terry.smartschedule.ui

import android.content.res.ColorStateList
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.terry.smartschedule.R
import com.terry.smartschedule.data.Category
import com.terry.smartschedule.data.Event
import com.terry.smartschedule.databinding.ItemEventBinding
import com.terry.smartschedule.databinding.ItemHeaderBinding
import com.terry.smartschedule.util.TimeFmt
import java.time.LocalDate

/** 列表行：日期分组头 + 日程条目 */
sealed class Row {
    data class Header(val date: LocalDate) : Row()
    data class Item(val event: Event) : Row()
}

class EventAdapter(
    /**
     * 右侧那个框：现在表示「归档」——点一下就把这条丢进「归档日志」。
     * 参数名沿用 onToggle，是为了不在同一次改动里同时动布局、适配器、Activity 三处。
     */
    private val onToggle: (Event) -> Unit,
    /** 点击卡片（非多选模式）：打开编辑面板 */
    private val onClick: (Event) -> Unit,
    /** 长按（非多选模式）：进入多选并选中这一条 */
    private val onEnterSelection: (Event) -> Unit,
    /** 多选模式下的点击 / 长按：切换这条的选中状态 */
    private val onToggleSelection: (Event) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = ArrayList<Row>()

    /** 是否处于多选模式 */
    private var selectionMode = false

    /** 已选中的日程 id。用 Set 而不是位置，列表刷新后位置会变 */
    private var selectedIds: Set<Long> = emptySet()

    /** 当前检索词（已拆好、已归一化）。空 = 没在检索，不做高亮 */
    private var highlight: List<String> = emptyList()

    fun submit(list: List<Row>, highlight: List<String> = emptyList()) {
        // 内容一字没变就别重绑：每次勾选完成、每次 onResume 都会调到这里，
        // 全量重建会让所有卡片（含 5 层亚克力背景）重绘一遍。
        //
        // 但高亮词变了必须重绑 —— 行内容可能一模一样（多打一个字仍然只命中这一条），
        // 而标色位置已经变了。漏掉这一条的表现是"改了关键词但颜色没动"。
        val hlChanged = highlight != this.highlight
        if (rows == list && !hlChanged) return
        rows.clear()
        rows.addAll(list)
        this.highlight = highlight
        notifyDataSetChanged()
    }

    /**
     * 更新多选状态。
     *
     * 分两种改法，是有意的：
     *  - **模式切换**（进/出多选）：所有条目的勾选框可见性都变了，整片刷新
     *  - **只是选中项变了**：只刷新那几条。
     *
     * 每次点击都 `notifyDataSetChanged` 的话，几十张卡片（每张 5 层亚克力背景）
     * 全部重绘，多选时一下一下地点会明显卡顿。
     */
    fun setSelection(mode: Boolean, ids: Set<Long>) {
        val modeChanged = mode != selectionMode
        val oldIds = if (modeChanged) emptySet() else selectedIds
        selectionMode = mode
        selectedIds = ids

        if (modeChanged) {
            notifyItemRangeChanged(0, rows.size)
            return
        }
        // 对称差集：新选中的 + 取消选中的
        val changed = (oldIds - ids) + (ids - oldIds)
        for (id in changed) {
            val pos = indexOfEvent(id)
            if (pos >= 0) notifyItemChanged(pos)
        }
    }

    /** 当前处于多选模式 */
    fun isSelecting(): Boolean = selectionMode

    /** 列表里所有日程的 id（全选用） */
    fun allEventIds(): List<Long> = rows.filterIsInstance<Row.Item>().map { it.event.id }

    private fun indexOfEvent(id: Long): Int =
        rows.indexOfFirst { it is Row.Item && it.event.id == id }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemVH(ItemEventBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(row.date)
            is Row.Item -> (holder as ItemVH).bind(row.event)
        }
    }

    inner class HeaderVH(private val b: ItemHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(date: LocalDate) {
            b.tvHeader.text = TimeFmt.dayHeader(date)
        }
    }

    inner class ItemVH(private val b: ItemEventBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(event: Event) {
            val ctx = b.root.context
            val pending = event.pending

            // 时间列：有具体时间显示时间，否则显示"全天"；
            // 原文写了结束时间就在下面补一行"至21:00"，让用户能直接看出时长
            b.tvTime.text = TimeFmt.timeLabel(event.startAt, event.hasTime)
            val endLabel = TimeFmt.endLabel(event.startAt, event.endAt, event.hasTime)
            b.tvDay.text = endLabel
            b.tvDay.visibility = if (endLabel != null) View.VISIBLE else View.GONE
            // 检索时把命中的词标出来：命中点在原文/地点里时，光看标题根本不知道为什么这条被搜出来
            b.tvTitle.text = SearchHighlight.mark(ctx, event.title, highlight)

            // 第二行：地点 / 来源 / 待确认原因，有哪个显示哪个
            var meta = buildString {
                if (!event.location.isNullOrBlank()) append(event.location)
                if (pending && !event.pendingReason.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(event.pendingReason)
                } else {
                    val srcLabel = when (event.source) {
                        Event.SRC_NOTIFY -> "来自通知"
                        Event.SRC_LLM -> "大模型识别"
                        else -> null
                    }
                    if (srcLabel != null) {
                        if (isNotEmpty()) append(" · ")
                        append(srcLabel)
                    }
                }
            }

            // 命中在原文里、标题和这一行都没中时，补一段上下文 ——
            // 否则这条看起来毫无变化，用户会以为搜错了
            if (highlight.isNotEmpty() && !SearchHighlight.hits(event.title, highlight) &&
                !SearchHighlight.hits(meta, highlight)
            ) {
                SearchHighlight.snippet(event.note.orEmpty(), highlight)?.let {
                    meta = if (meta.isEmpty()) it else "$meta\n$it"
                }
            }
            b.tvMeta.text = SearchHighlight.mark(ctx, meta, highlight)
            b.tvMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
            b.tvMeta.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (pending) R.color.warn else R.color.text_secondary
                )
            )
            b.tvTag.visibility = if (pending) View.VISIBLE else View.GONE

            // 分类标签：一类一个颜色。底色用同色 12% 透明度，文字用本色 ——
            // 五种颜色直接铺满会盖掉卡片本身的玻璃质感
            val cat = Category.of(event.category)
            val catColor = ContextCompat.getColor(ctx, cat.colorRes)
            b.tvCat.text = ctx.getString(cat.labelRes)
            b.tvCat.setTextColor(catColor)
            b.tvCat.backgroundTintList =
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(catColor, 31))
            b.tvCat.visibility = View.VISIBLE

            // 据通知已取消：分类位换成红字「已取消」。
            // 不直接删是因为群里的"取消了"未必指这条，得让用户自己判断。
            if (event.revoked) {
                val cancelColor = ContextCompat.getColor(ctx, R.color.cancel_text)
                b.tvCat.text = "已取消"
                b.tvCat.setTextColor(cancelColor)
                b.tvCat.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.cancel_fill))
            }

            val selected = selectionMode && event.id in selectedIds

            // 最左侧：多选。常驻显示，点它进入多选并选中；已在多选里则切换。
            b.cbSelectLeft.isChecked = selected
            b.cbSelectLeft.setOnClickListener {
                if (selectionMode) onToggleSelection(event) else onEnterSelection(event)
            }

            // 右侧：归档。点一下这条就从主列表消失，进「归档日志」。
            // 多选时隐藏（保留占位，避免标题宽度跳动），防止勾选过程中误归档。
            b.cbArchive.isChecked = false
            b.cbArchive.visibility = if (selectionMode) View.INVISIBLE else View.VISIBLE
            b.cbArchive.setOnClickListener { onToggle(event) }

            // 背景优先级：选中 > 待确认 > 普通。
            // 只在真的变了才换：setBackgroundResource 会重建 Drawable 并触发重绘，
            // 每次 bind 都调一遍，快速滚动时会明显掉帧
            val bgRes = when {
                selected -> R.drawable.bg_acrylic_card_selected
                pending -> R.drawable.bg_acrylic_card_pending
                else -> R.drawable.bg_acrylic_card
            }
            if (b.card.tag != bgRes) {
                b.card.setBackgroundResource(bgRes)
                b.card.tag = bgRes
            }

            // 完成/取消态：置灰 + 删除线
            val flags = b.tvTitle.paintFlags
            if (event.done || event.revoked) {
                b.tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.done_text))
                b.tvTitle.paintFlags = flags or Paint.STRIKE_THRU_TEXT_FLAG
                b.tvTime.setTextColor(ContextCompat.getColor(ctx, R.color.done_text))
            } else {
                b.tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                b.tvTitle.paintFlags = flags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                b.tvTime.setTextColor(
                    ContextCompat.getColor(ctx, if (pending) R.color.warn else R.color.brand)
                )
            }

            // 点卡片：多选时切换选中，平时打开编辑。
            // 长按：多选时也切换（方便反选），平时进入多选。
            b.root.setOnClickListener {
                if (selectionMode) onToggleSelection(event) else onClick(event)
            }
            b.root.setOnLongClickListener {
                if (selectionMode) onToggleSelection(event) else onEnterSelection(event)
                true
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
