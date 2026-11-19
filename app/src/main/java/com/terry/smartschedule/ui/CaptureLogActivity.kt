package com.terry.smartschedule.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.terry.smartschedule.R
import com.terry.smartschedule.databinding.ActivityCaptureLogBinding
import com.terry.smartschedule.databinding.ItemCaptureLogBinding
import com.terry.smartschedule.notify.CaptureLog
import com.terry.smartschedule.notify.CaptureRecord
import com.terry.smartschedule.util.Insets

/**
 * 最近的通知处理记录。
 *
 * 这是"自动添加没反应"的排查入口：每条被处理的通知都写在这里，
 * 包括"为什么没建日程"。之前这块是空的，用户只能靠猜。
 */
class CaptureLogActivity : AppCompatActivity() {

    private lateinit var b: ActivityCaptureLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.edgeToEdge(this)
        b = ActivityCaptureLogBinding.inflate(layoutInflater)
        setContentView(b.root)
        Insets.apply(
            this, b.root,
            statusBarSpacer = b.statusBarSpace,
            bottomTarget = b.scroll,
            bottomExtraDp = 20
        )

        b.btnBack.setOnClickListener { finish() }
        b.btnClearLog.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.capture_log_clear)
                .setMessage("清空后就没法回溯刚才处理过哪些通知了。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_confirm) { _, _ ->
                    CaptureLog.clear()
                    render()
                }
                .show()
        }
        render()
    }

    private fun render() {
        val list = CaptureLog.recent()
        b.llLog.removeAllViews()
        b.tvEmpty.isVisible = list.isEmpty()

        if (list.isEmpty()) return
        val inflater = LayoutInflater.from(this)
        for (rec in list) {
            val item = ItemCaptureLogBinding.inflate(inflater, b.llLog, false)
            bind(item, rec)
            b.llLog.addView(item.root)
        }
    }

    private fun bind(item: ItemCaptureLogBinding, rec: CaptureRecord) {
        item.tvOutcome.text = rec.outcomeLabel
        val color = when (rec.outcome) {
            CaptureRecord.OUT_CREATED -> R.color.brand_dark
            CaptureRecord.OUT_PENDING -> R.color.warn
            else -> R.color.text_secondary
        }
        item.tvOutcome.setTextColor(ContextCompat.getColor(this, color))

        item.tvMeta.text = buildString {
            append(rec.timeLabel)
            if (rec.app.isNotBlank()) append(" · ").append(rec.app)
        }
        item.tvDetail.text = rec.detail
        item.tvText.text = rec.text
        item.tvText.isVisible = rec.text.isNotBlank()
    }
}
