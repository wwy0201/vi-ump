package com.vi.ump.tv

import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.vi.ump.model.MediaDetail
import com.vi.ump.model.MediaKind
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PlayTarget
import com.vi.ump.model.TrackGroup
import kotlinx.coroutines.runBlocking

/** 详情页：元信息 + 分组/集列表。图片进查看器，视频/音频进播放器。 */
class DetailScreen(
    private val host: TvHost,
    private val session: TvSession,
    private val item: MediaSummary,
    private val startTrackIndex: Int = -1,
    private val startEpisode: Int = 0,
    private val autoOpen: Boolean = false,
) : TvScreen {

    private val ctx = host as android.content.Context
    private val root: LinearLayout
    private val status: TextView
    private val list: ListView
    private val retry: TextView
    private var detail: MediaDetail? = null
    private var loading = false
    private var opened = false

    private data class Row(
        val groupIndex: Int,
        val trackIndex: Int,
        val title: String,
        val subtitle: String,
        val playable: Boolean,
    )

    private val rows = mutableListOf<Row>()

    init {
        with(TvUi) {
            root = ctx.column().apply { setPadding(ctx.dp(40), ctx.dp(20), ctx.dp(40), ctx.dp(16)) }

            val top = ctx.focusRow()
            top.addView(ctx.tvButton("← 返回") { host.pop() }, ctx.rowParams())
            top.addView(ctx.tvButton("首页") { host.openFeed() }, ctx.rowParams(8))
            top.addView(ctx.tvButton("播放/阅读", accent = true) { openFirst() }, ctx.rowParams(8))
            top.addView(ctx.hSpacer())
            retry = ctx.tvButton("重试") { load() }
            retry.visibility = View.GONE
            top.addView(retry, ctx.rowParams())
            root.addView(top, ctx.matchWrap())

            val title = ctx.tvTitle(item.title, 22f).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            root.addView(title, ctx.matchWrap(top = 10))
            status = ctx.statusText().apply {
                maxLines = 6
                ellipsize = TextUtils.TruncateAt.END
            }
            root.addView(status, ctx.matchWrap(top = 2))

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
        if (detail == null && !loading) load()
    }

    override fun requestInitialFocus() {
        if (rows.isNotEmpty()) list.requestFocus() else retry.requestFocus()
    }

    private fun load() {
        val detailKey = session.detailKeyFor(item)
        if (detailKey == null) {
            // 这个源没有详情页（抖音走的就是这条）：条目自带的直链本身就是全部内容，
            // 造一份最小 detail 顶上去，后面的「播放/阅读」「自动起播」才能照常工作。
            val direct = item.playUrl?.takeIf { it.isNotBlank() }
            if (direct == null) {
                status.text = "配置里没有详情资源（pages[].layout.resource 为空）。"
                retry.visibility = View.VISIBLE
                return
            }
            status.text = "该源不提供详情页，直接播放列表里的地址。"
            retry.visibility = View.GONE
            detail = MediaDetail(summary = item)
            rows.clear()
            val groups = effectiveGroups(detail!!)
            rows += Row(0, 0, item.title.ifBlank { "播放" }, "直接播放", playable = true)
            list.adapter = TrackAdapter()
            list.setSelection(0)
            if (autoOpen && !opened) {
                opened = true
                host.openPlayer(session, PlayTarget(item, groups, 0, 0))
            }
            return
        }
        loading = true
        retry.visibility = View.GONE
        status.text = "正在加载详情…"
        val activity = host as? TvMainActivity
        activity?.runIo {
            val result = runCatching {
                runBlocking {
                    session.pipeline.detail(
                        key = detailKey,
                        id = item.id,
                        extra = session.detailParams(),
                        trackKey = session.trackKey,
                    )
                }
            }
            activity.ui {
                loading = false
                result.onSuccess { d ->
                    if (d == null) {
                        status.text = "详情没有返回有效数据。"
                        retry.visibility = View.VISIBLE
                        return@onSuccess
                    }
                    detail = d
                    bind(d)
                }.onFailure { e ->
                    status.text = "详情加载失败：${e.message ?: e.javaClass.simpleName}"
                    retry.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun bind(d: MediaDetail) {
        val summary = d.summary
        val groups = effectiveGroups(d)
        val info = buildString {
            append(summary.kind.key)
            summary.subtitle?.let { append(" · ").append(it) }
            summary.type?.let { append(" · ").append(it) }
            summary.score?.let { append(" · ★ ").append(it) }
            append(" · 共 ${groups.sumOf { it.tracks.size }} 项")
        }
        status.text = info + (d.description?.let { "\n$it" } ?: "")
        rows.clear()
        groups.forEachIndexed { gi, group ->
            group.tracks.forEachIndexed { ti, track ->
                val prefix = if (group.name.isNotBlank()) "${group.name} · " else ""
                rows += Row(
                    groupIndex = gi,
                    trackIndex = ti,
                    title = track.title.ifBlank { "第 ${track.index} 项" },
                    subtitle = prefix + buildString {
                        if (track.index > 0) append("第 ${track.index} 集")
                        if (track.state != null) append(if (isEmpty()) "" else " · ").append(track.state)
                        if (track.pending != null) append(if (isEmpty()) "" else " · ").append("按需解析")
                    },
                    playable = track.url.isNotBlank() || track.pending != null || item.kind == MediaKind.IMAGE,
                )
            }
        }
        if (rows.isEmpty()) {
            status.text = status.text.toString() + "\n这一条没有可打开的图集/剧集。"
        }
        list.adapter = TrackAdapter()
        list.setSelection(0)
        requestInitialFocus()

        if (autoOpen && !opened) {
            opened = true
            if (summary.kind == MediaKind.IMAGE) {
                val flat = imageFlat(groups)
                val start = if (startTrackIndex in flat.indices) startTrackIndex
                else flat.indexOfFirst { it.second.index == startEpisode }.takeIf { it >= 0 } ?: 0
                if (flat.isNotEmpty()) host.openImage(session, item, session.detailKey.orEmpty(), session.trackKey, groups, start)
            } else {
                val gi = groups.indexOfFirst { g -> g.tracks.any { it.index == startEpisode } }
                if (gi >= 0) {
                    val ti = groups[gi].tracks.indexOfFirst { it.index == startEpisode }
                    val target = PlayTarget(item, groups, gi, ti)
                    if (!target.url.isNullOrBlank()) host.openPlayer(session, target)
                } else if (startTrackIndex >= 0) {
                    val gi2 = groups.indexOfFirst { it.tracks.size > startTrackIndex }
                    if (gi2 >= 0) host.openPlayer(session, PlayTarget(item, groups, gi2, startTrackIndex))
                }
            }
        }
    }

    /** 配置没给 groups 但条目自带直链时，补一条"直接播放"。 */
    private fun effectiveGroups(d: MediaDetail): List<TrackGroup> {
        if (d.trackGroups.isNotEmpty()) return d.trackGroups
        val direct = d.summary.playUrl?.takeIf { it.isNotBlank() } ?: item.playUrl?.takeIf { it.isNotBlank() }
        return if (direct != null) {
            listOf(TrackGroup("播放", d.summary.kind, listOf(
                com.vi.ump.model.Track(index = 0, title = d.summary.title, url = direct)
            )))
        } else emptyList()
    }

    private fun imageFlat(groups: List<TrackGroup>): List<Pair<Int, com.vi.ump.model.Track>> {
        val out = ArrayList<Pair<Int, com.vi.ump.model.Track>>()
        groups.forEachIndexed { gi, group ->
            if (group.kind == MediaKind.IMAGE || item.kind == MediaKind.IMAGE) {
                group.tracks.forEach { out += gi to it }
            }
        }
        return out
    }

    private fun openFirst() {
        val d = detail ?: return load()
        val groups = effectiveGroups(d)
        if (d.summary.kind == MediaKind.IMAGE) {
            if (groups.isNotEmpty()) host.openImage(session, item, session.detailKey.orEmpty(), session.trackKey, groups, 0)
            return
        }
        val gi = groups.indexOfFirst { it.tracks.isNotEmpty() }
        if (gi >= 0) {
            val target = PlayTarget(item, groups, gi, 0)
            if (target.url.isNullOrBlank()) {
                status.text = "这一条没有直链（可能需要选集后按需解析）。"
            } else {
                host.openPlayer(session, target)
            }
        }
    }

    private fun open(position: Int) {
        val row = rows.getOrNull(position) ?: return
        val d = detail ?: return
        val groups = effectiveGroups(d)
        if (d.summary.kind == MediaKind.IMAGE) {
            val flat = imageFlat(groups)
            var global = -1
            var seen = 0
            groups.forEachIndexed { gi, g ->
                if (gi == row.groupIndex) {
                    if (g.kind == MediaKind.IMAGE || item.kind == MediaKind.IMAGE) global = seen + row.trackIndex
                }
                if (g.kind == MediaKind.IMAGE || item.kind == MediaKind.IMAGE) seen += g.tracks.size
            }
            host.openImage(session, item, session.detailKey.orEmpty(), session.trackKey, groups, global.coerceAtLeast(0))
        } else {
            val target = PlayTarget(item, groups, row.groupIndex, row.trackIndex)
            if (target.url.isNullOrBlank()) {
                status.text = "这一集没有可播放地址。"
            } else {
                host.openPlayer(session, target)
            }
        }
    }

    private inner class TrackAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            val view = (convertView as? LinearLayout) ?: with(TvUi) {
                ctx.column().apply {
                    setPadding(ctx.dp(18), ctx.dp(10), ctx.dp(18), ctx.dp(10))
                    isFocusable = false
                }
            }
            view.removeAllViews()
            view.addView(with(TvUi) { ctx.tvText(row.title, 18f, if (row.playable) TvUi.TEXT else TvUi.TEXT_DIM) }, with(TvUi) { ctx.matchWrap() })
            if (row.subtitle.isNotBlank()) {
                view.addView(with(TvUi) { ctx.tvText(row.subtitle, 13f, TvUi.TEXT_DIM) }, with(TvUi) { ctx.matchWrap(top = 3) })
            }
            return view
        }
    }
}
