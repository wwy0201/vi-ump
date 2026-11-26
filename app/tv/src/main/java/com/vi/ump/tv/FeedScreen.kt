package com.vi.ump.tv

import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vi.ump.model.ChannelSpec
import com.vi.ump.model.MediaKind
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PagedResult
import kotlinx.coroutines.runBlocking

/** 首页：栏目切换 + 海报网格 + 分页加载。 */
class FeedScreen(
    private val host: TvHost,
    private val session: TvSession,
) : TvScreen {

    private val ctx = host as android.content.Context
    private val root: LinearLayout
    private val status: TextView
    private val grid: GridView
    private val channelBar: LinearLayout
    private val title: TextView

    private val channels: List<ChannelSpec> = session.channels()
    private var activeChannelId: String? = channels.firstOrNull()?.id
    private val items = mutableListOf<MediaSummary>()
    private var pageNo = 0
    private var hasMore = false
    private var loading = false
    private var loaded = false

    init {
        with(TvUi) {
            root = ctx.column().apply { setPadding(ctx.dp(36), ctx.dp(20), ctx.dp(36), ctx.dp(16)) }

            val top = ctx.focusRow()
            title = ctx.tvTitle(session.source.label, 22f)
            top.addView(title, ctx.weight())
            top.addView(ctx.tvButton("搜索") { host.openSearch() }, ctx.rowParams(8))
            top.addView(ctx.tvButton("历史") { host.openHistory() }, ctx.rowParams(8))
            top.addView(ctx.tvButton("设置") { host.openSettings() }, ctx.rowParams(8))
            top.addView(ctx.tvButton("切源") { host.backToHome() }, ctx.rowParams(8))
            root.addView(top, ctx.matchWrap())

            channelBar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val scroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(channelBar)
            }
            root.addView(scroll, ctx.matchWrap(top = 8))

            status = ctx.statusText()
            root.addView(status, ctx.matchWrap(top = 2))

            grid = GridView(ctx).apply {
                numColumns = (session.homePage?.layout?.columns ?: session.schema.config.theme.posterColumns.coerceAtLeast(1))
                    .coerceIn(1, 6)
                horizontalSpacing = ctx.dp(12)
                verticalSpacing = ctx.dp(12)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                setBackgroundColor(TvUi.BG)
                // 卡片自带不透明底色，GridView 的 selector 画在卡片下面会被完全盖住（4.4 上就是这样，
                // 焦点在网格里却看不到任何高亮）。改成只描边的 selector 并画在最上层。
                setSelector(with(TvUi) {
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = ctx.dp(10).toFloat()
                        setStroke(ctx.dp(2), TvUi.ACCENT)
                    }
                })
                setDrawSelectorOnTop(true)
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(0, ctx.dp(4), 0, ctx.dp(8))
                clipToPadding = false
            }
            grid.adapter = FeedAdapter()
            grid.setOnItemClickListener { _, _, position, _ -> open(position) }
            grid.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    open(grid.selectedItemPosition)
                    true
                } else false
            }
            root.addView(grid, ctx.fill(top = 6))
        }
    }

    override val view: View get() = root

    override fun onShow() {
        rebuildChannels()
        if (!loaded) {
            loaded = true
            loadPage(1, reset = true)
        }
    }

    override fun requestInitialFocus() {
        if (channels.isNotEmpty()) channelBar.getChildAt(0)?.requestFocus() else grid.requestFocus()
    }

    fun selectChannel(id: String) {
        if (id == activeChannelId) return
        val channel = channels.firstOrNull { it.id == id } ?: return
        activeChannelId = channel.id
        rebuildChannels()
        loadPage(1, reset = true)
    }

    private fun rebuildChannels() {
        channelBar.removeAllViews()
        channels.forEach { channel ->
            val selected = channel.id == activeChannelId
            val chip = with(TvUi) {
                ctx.tvButton(channel.title.ifBlank { channel.id }, accent = selected) { selectChannel(channel.id) }
            }
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            chip.minHeight = with(TvUi) { ctx.dp(40) }
            channelBar.addView(chip, with(TvUi) { ctx.rowParams(if (channelBar.childCount == 0) 0 else 8) })
        }
    }

    private fun loadPage(next: Int, reset: Boolean) {
        if (loading) return
        val feed = session.feedKeyOf(channels.firstOrNull { it.id == activeChannelId })
        if (feed == null) {
            status.text = "配置里没有可用的首页栏目。"
            return
        }
        val params = session.paramsOf(channels.firstOrNull { it.id == activeChannelId })
        val key = session.cacheKey(feed, params)
        if (reset && next == 1) {
            session.cached(key)?.let { hit ->
                items.clear()
                items += hit.items
                pageNo = hit.page
                hasMore = hit.hasMore
                status.text = "已加载 ${items.size} 条"
                (grid.adapter as? FeedAdapter)?.notifyDataSetChanged()
                return
            }
        }
        loading = true
        status.text = if (reset) "正在加载…" else "正在加载第 $next 页…"
        val activity = host as? TvMainActivity
        activity?.runIo {
            val result = runCatching { runBlocking { session.pipeline.list(feed, next, params) } }
            activity.ui {
                loading = false
                result.onSuccess { paged ->
                    if (reset) items.clear()
                    val seen = items.mapTo(HashSet()) { it.id }
                    val fresh = paged.items.filterNot { it.id in seen }
                    items += fresh
                    pageNo = next
                    val exhausted = paged.items.isNotEmpty() && fresh.isEmpty()
                    hasMore = paged.hasMore && !exhausted
                    status.text = buildString {
                        if (items.isEmpty()) {
                            // 空结果单独说清楚：TV 上这是"这个源现在没内容"，不是"还在加载"，
                            // 并给两个能按的键（OK 重试 / 返回换源），别让遥控器无处发力
                            append("没有取到内容 · 按 OK 重试，或按返回键换个源")
                        } else {
                            append("第 $pageNo 页 · ${items.size} 条")
                            if (paged.skipped > 0) append(" · 跳过 ${paged.skipped}")
                            if (!hasMore) append(" · 已到底")
                        }
                    }
                    session.remember(
                        key,
                        PagedResult(items = items.toList(), page = pageNo, hasMore = hasMore, skipped = paged.skipped),
                    )
                    (grid.adapter as? FeedAdapter)?.notifyDataSetChanged()
                }.onFailure { e ->
                    status.text = "加载失败：${e.message ?: e.javaClass.simpleName}（按 OK 或返回重试）"
                }
            }
        }
    }

    private fun open(position: Int) {
        // 空列表时 OK 键没有条目可开：用同一颗键重试第 1 页（状态行也是这么写的），
        // 否则"按 OK 重试"成了一句做不到的提示
        if (items.isEmpty()) {
            loadPage(1, reset = true)
            return
        }
        if (position == items.size) {
            if (hasMore) loadPage(pageNo + 1, reset = false)
            return
        }
        val item = items.getOrNull(position) ?: return
        // TV 没有"默认自动播放"设置：视频/音频点开即播，固定自动打开。
        host.openDetail(item, autoOpen = item.kind != MediaKind.IMAGE)
    }

    private inner class FeedAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size + if (hasMore || loading) 1 else 0
        override fun getItem(position: Int): Any? = items.getOrNull(position)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            if (position >= items.size) {
                val more = with(TvUi) {
                    ctx.tvText(if (loading) "正在加载…" else "加载更多…", 17f, TvUi.ACCENT)
                }
                more.gravity = Gravity.CENTER
                more.setPadding(0, with(TvUi) { ctx.dp(18) }, 0, with(TvUi) { ctx.dp(18) })
                return more
            }
            val item = items[position]
            // GridView 会把「加载更多」的 TextView 也回收进来；它没有 Holder，
            // 不能当卡片复用，否则 getChildAt(0) 为 null，直接 NPE。
            val card = (convertView as? LinearLayout)?.takeIf { it.tag is Holder }
                ?: with(TvUi) {
                    ctx.column().apply {
                        setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(8))
                        background = ctx.focusBackground(TvUi.SURFACE, TvUi.SURFACE_FOCUS, 10)
                        isFocusable = false
                    }
                }
            val reused = card.tag is Holder
            val holder = (card.tag as? Holder) ?: Holder()
            if (!reused) {
                holder.image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                holder.title = with(TvUi) { ctx.tvText("", 15f, TvUi.TEXT) }
                holder.subtitle = with(TvUi) { ctx.tvText("", 12f, TvUi.TEXT_DIM) }
                card.addView(holder.image, with(TvUi) {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(190))
                })
                card.addView(holder.title, with(TvUi) { ctx.matchWrap(top = 6) })
                card.addView(holder.subtitle, with(TvUi) { ctx.matchWrap(top = 2) })
                card.tag = holder
            } else {
                holder.image = card.getChildAt(0) as ImageView
                holder.title = card.getChildAt(1) as TextView
                holder.subtitle = card.getChildAt(2) as TextView
            }

            holder.title.text = item.title
            holder.title.setTextColor(TvUi.TEXT)
            holder.title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            holder.title.maxLines = 2
            holder.title.ellipsize = TextUtils.TruncateAt.END
            holder.subtitle.text = listOfNotNull(item.subtitle, item.type).joinToString(" · ")
            holder.subtitle.setTextColor(TvUi.TEXT_DIM)
            holder.subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            holder.subtitle.maxLines = 1

            holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.image.setBackgroundColor(Color.rgb(38, 38, 45))
            TvImageLoader.load(
                holder.image,
                item.poster ?: item.imageUrl,
                session.schema.config.runtime.headers,
                session.schema.config.runtime.imageDecrypt,
                session.fetcher,
                with(TvUi) { ctx.dp(260) },
                with(TvUi) { ctx.dp(360) },
            )
            return card
        }
    }

    private class Holder {
        lateinit var image: ImageView
        lateinit var title: TextView
        lateinit var subtitle: TextView
    }
}
