package com.vi.ump.tv

import android.app.AlertDialog
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.vi.ump.model.MediaKind
import com.vi.ump.model.MediaSummary

/** 当前源的观看历史：点一条直接回到上次那张图/那一集，不改顶栏源。 */
class HistoryScreen(
    private val host: TvHost,
    private val session: TvSession,
) : TvScreen {

    private val ctx = host as android.content.Context
    private val root: LinearLayout
    private val status: TextView
    private val list: ListView
    private val items = mutableListOf<TvHistoryStore.WatchItem>()

    init {
        with(TvUi) {
            root = ctx.column().apply { setPadding(ctx.dp(40), ctx.dp(20), ctx.dp(40), ctx.dp(16)) }
            val top = ctx.focusRow()
            top.addView(ctx.tvButton("← 返回") { host.pop() }, ctx.rowParams())
            top.addView(ctx.tvTitle("观看历史 · ${session.source.label}", 20f), ctx.rowParams(14))
            top.addView(ctx.hSpacer())
            top.addView(ctx.tvButton("清空") { confirmClear() }, ctx.rowParams(8))
            root.addView(top, ctx.matchWrap())

            status = ctx.statusText()
            root.addView(status, ctx.matchWrap(top = 4))

            list = ctx.tvListView().apply {
                setOnItemClickListener { _, _, position, _ -> open(position) }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_UP &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    ) {
                        open(selectedItemPosition)
                        true
                    } else false
                }
            }
            root.addView(list, ctx.fill(top = 6))
        }
    }

    override val view: View get() = root

    override fun onShow() {
        refresh()
    }

    override fun requestInitialFocus() {
        if (items.isNotEmpty()) list.requestFocus()
    }

    private fun refresh() {
        items.clear()
        items += TvHistoryStore.watch(session.settings, session.source.id)
        list.adapter = HistoryAdapter()
        status.text = if (items.isEmpty()) {
            "这个源还没有观看记录。看完一张图或一集后会自动出现在这里。"
        } else {
            "共 ${items.size} 条。OK 直接继续阅读/播放。"
        }
        if (items.isNotEmpty()) list.setSelection(0)
    }

    private fun open(position: Int) {
        val h = items.getOrNull(position) ?: return
        val kind = MediaKind.fromKey(h.kind)
        val item = MediaSummary(
            id = h.itemId,
            title = h.title,
            kind = kind,
            poster = h.poster,
            detailResource = h.detailResource,
        )
        val startTrack = if (kind == MediaKind.IMAGE) h.trackIndex else -1
        val startEpisode = if (kind != MediaKind.IMAGE) h.episode else 0
        host.openDetail(item, startTrackIndex = startTrack, startEpisode = startEpisode, autoOpen = true)
    }

    private fun confirmClear() {
        AlertDialog.Builder(ctx)
            .setTitle("清空观看历史")
            .setMessage("只清空「${session.source.label}」这一份，其他源不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                TvHistoryStore.clear(session.settings, session.source.id)
                refresh()
            }
            .show()
    }

    private inner class HistoryAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h = items[position]
            val row = (convertView as? LinearLayout) ?: with(TvUi) {
                ctx.column().apply {
                    setPadding(ctx.dp(18), ctx.dp(10), ctx.dp(18), ctx.dp(10))
                    isFocusable = false
                }
            }
            row.removeAllViews()
            row.addView(with(TvUi) { ctx.tvText(h.title, 18f, TvUi.TEXT) }, with(TvUi) { ctx.matchWrap() })
            row.addView(
                with(TvUi) { ctx.tvText("${h.progressLabel}   ·   ${h.kind}", 13f, TvUi.ACCENT) },
                with(TvUi) { ctx.matchWrap(top = 3) },
            )
            return row
        }
    }
}
