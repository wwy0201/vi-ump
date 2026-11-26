package com.vi.ump.tv

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private val rows: LinearLayout
    private val items = mutableListOf<TvHistoryStore.WatchItem>()

    // 顶栏第一个按钮「← 返回」：列表为空时焦点落在这里
    private val backButton: TextView

    init {
        with(TvUi) {
            root = ctx.column().apply { setPadding(ctx.dp(40), ctx.dp(20), ctx.dp(40), ctx.dp(16)) }
            val top = ctx.focusRow()
            backButton = ctx.tvButton("← 返回") { host.pop() }
            top.addView(backButton, ctx.rowParams())
            top.addView(ctx.tvTitle("观看历史 · ${session.source.label}", 20f), ctx.rowParams(14))
            top.addView(ctx.hSpacer())
            top.addView(ctx.tvButton("清空") { confirmClear() }, ctx.rowParams(8))
            root.addView(top, ctx.matchWrap())

            status = ctx.statusText()
            root.addView(status, ctx.matchWrap(top = 4))

            rows = ctx.tvScrollRows()
            root.addView(
                ScrollView(ctx).apply {
                    isVerticalScrollBarEnabled = false
                    addView(rows, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                },
                ctx.fill(top = 6),
            )
        }
    }

    override val view: View get() = root

    override fun onShow() {
        refresh()
    }

    override fun requestInitialFocus() {
        val first = if (items.isNotEmpty()) rows.getChildAt(0) else null
        if (first != null && first.requestFocus()) return
        backButton.requestFocus()
    }

    private fun refresh() {
        items.clear()
        items += TvHistoryStore.watch(session.settings, session.source.id)
        rows.removeAllViews()
        items.forEachIndexed { index, h ->
            if (index > 0) {
                rows.addView(with(TvUi) { ctx.tvDivider() }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1),
                ))
            }
            rows.addView(
                with(TvUi) {
                    ctx.tvListRow { open(index) }.apply {
                        addView(ctx.tvText(h.title, 18f, TvUi.TEXT), ctx.matchWrap())
                        addView(
                            ctx.tvText("${h.progressLabel}   ·   ${h.kind}", 13f, TvUi.ACCENT),
                            ctx.matchWrap(top = 3),
                        )
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        status.text = if (items.isEmpty()) {
            "这个源还没有观看记录。看完一张图或一集后会自动出现在这里。"
        } else {
            "共 ${items.size} 条。OK 直接继续阅读/播放。"
        }
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
}
