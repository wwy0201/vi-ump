package com.vi.ump.tv

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vi.ump.model.MediaKind
import com.vi.ump.model.MediaSummary
import kotlinx.coroutines.runBlocking

/**
 * 搜索页。TV 遥控器没有软键盘，所以这里自带一个方向键可操作的字母键盘；
 * 搜索词按源保存，历史词点一下就能重搜。
 */
class SearchScreen(
    private val host: TvHost,
    private val session: TvSession,
) : TvScreen {

    private val ctx = host as android.content.Context
    private val root: LinearLayout
    private val queryText: TextView
    private val status: TextView
    private val keyboardPanel: LinearLayout
    private val historyBar: LinearLayout
    private val results: ScrollView
    private val resultRows: LinearLayout
    private val resultBar: LinearLayout

    private var query = ""
    private var resultItems = mutableListOf<MediaSummary>()
    private var page = 0
    private var hasMore = false
    private var loading = false
    private var showingResults = false

    init {
        with(TvUi) {
            root = ctx.column().apply { setPadding(ctx.dp(34), ctx.dp(16), ctx.dp(34), ctx.dp(14)) }
            val top = ctx.focusRow()
            top.addView(ctx.tvButton("← 返回") { host.pop() }, ctx.rowParams())
            top.addView(ctx.tvTitle("搜索 · ${session.source.label}", 21f), ctx.rowParams(14))
            top.addView(ctx.hSpacer())
            top.addView(ctx.tvButton("重新输入") { showKeyboard() }, ctx.rowParams())
            root.addView(top, ctx.matchWrap())

            queryText = ctx.tvText("输入：", 18f, ACCENT).apply {
                setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            root.addView(queryText, ctx.matchWrap(top = 8))
            status = ctx.statusText()
            root.addView(status, ctx.matchWrap(top = 2))

            historyBar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            root.addView(historyBar, ctx.matchWrap(top = 4))

            keyboardPanel = ctx.column()
            root.addView(keyboardPanel, ctx.matchWrap(top = 6))
            buildKeyboard()

            // 结果列表用 ScrollView + 可聚焦行（同 HomeScreen），4.4 上 ListView 的高亮不可见
            resultRows = ctx.tvScrollRows()
            results = ScrollView(ctx).apply {
                visibility = View.GONE
                isVerticalScrollBarEnabled = false
                addView(resultRows, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            resultBar = ctx.buttonBar(
                ctx.tvButton("加载更多") { loadMore() },
                ctx.tvButton("换个词") { showKeyboard() },
            ).apply { visibility = View.GONE }
            root.addView(results, ctx.fill(top = 6))
            root.addView(resultBar, ctx.matchWrap(top = 6))
        }
        refreshHistory()
        updateQuery()
    }

    override val view: View get() = root

    override fun onShow() {
        refreshHistory()
    }

    override fun requestInitialFocus() {
        val first = keyboardPanel.getChildAt(0) as? ViewGroup
        (first?.getChildAt(0) ?: keyboardPanel.getChildAt(0))?.requestFocus()
    }

    private fun buildKeyboard() {
        keyboardPanel.removeAllViews()
        val rows = listOf(
            "1234567890",
            "qwertyuiop",
            "asdfghjkl",
            "zxcvbnm",
        )
        rows.forEach { chars ->
            val row = with(TvUi) { ctx.focusRow() }
            row.gravity = Gravity.CENTER
            chars.forEach { c ->
                val key = with(TvUi) { ctx.tvButton(c.toString()) { appendChar(c) } }
                key.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                key.minWidth = with(TvUi) { ctx.dp(46) }
                key.setPadding(with(TvUi) { ctx.dp(7) }, with(TvUi) { ctx.dp(7) },
                    with(TvUi) { ctx.dp(7) }, with(TvUi) { ctx.dp(7) })
                row.addView(key, with(TvUi) { ctx.rowParams(3) })
            }
            keyboardPanel.addView(row, with(TvUi) { ctx.matchWrap(top = 3) })
        }
        val special = with(TvUi) { ctx.focusRow() }.apply { gravity = Gravity.CENTER }
        special.addView(with(TvUi) { ctx.tvButton("空格") { appendChar(' ') } }, with(TvUi) { ctx.rowParams() })
        special.addView(with(TvUi) { ctx.tvButton("删除") { backspace() } }, with(TvUi) { ctx.rowParams(8) })
        special.addView(with(TvUi) { ctx.tvButton("搜索", accent = true) { doSearch() } }, with(TvUi) { ctx.rowParams(8) })
        keyboardPanel.addView(special, with(TvUi) { ctx.matchWrap(top = 6) })
    }

    private fun refreshHistory() {
        historyBar.removeAllViews()
        val words = TvHistoryStore.searchHistory(session.settings, session.source.id)
        if (words.isEmpty()) {
            historyBar.addView(with(TvUi) {
                ctx.tvText("没有搜索历史；输入关键词后会自动记住。", 13f, TEXT_DIM)
            })
            return
        }
        words.forEach { word ->
            val chip = with(TvUi) { ctx.tvButton(word) { useWord(word) } }
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            chip.minHeight = with(TvUi) { ctx.dp(38) }
            historyBar.addView(chip, with(TvUi) { ctx.rowParams(6) })
        }
    }

    private fun useWord(word: String) {
        query = word
        updateQuery()
        doSearch()
    }

    private fun appendChar(c: Char) {
        if (query.length >= 120) return
        query += c
        updateQuery()
    }

    private fun backspace() {
        if (query.isNotEmpty()) query = query.dropLast(1)
        updateQuery()
    }

    private fun updateQuery() {
        queryText.text = "输入：${query.ifBlank { "（空）" }}"
    }

    private fun showKeyboard() {
        showingResults = false
        keyboardPanel.visibility = View.VISIBLE
        historyBar.visibility = View.VISIBLE
        results.visibility = View.GONE
        resultBar.visibility = View.GONE
        requestInitialFocus()
    }

    private fun doSearch() {
        val word = query.trim()
        if (word.isEmpty()) {
            status.text = "先输入关键词。"
            return
        }
        val key = session.schema.search.keys.firstOrNull()
        if (key == null) {
            status.text = "这个源没有配置搜索资源。"
            return
        }
        loading = true
        status.text = "正在搜索「$word」…"
        TvHistoryStore.addSearch(session.settings, session.source.id, word)
        refreshHistory()
        val activity = host as? TvMainActivity
        activity?.runIo {
            val result = runCatching {
                runBlocking { session.pipeline.search(key, word, 1, session.paramsOf(null)) }
            }
            activity.ui {
                loading = false
                result.onSuccess { paged ->
                    resultItems.clear()
                    resultItems += paged.items
                    page = 1
                    hasMore = paged.hasMore
                    showResults()
                    status.text = "搜索「$word」：${resultItems.size} 条" + if (!hasMore) "" else " · 可加载更多"
                }.onFailure { e ->
                    status.text = "搜索失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun loadMore() {
        if (loading || !hasMore) return
        val word = query.trim()
        val key = session.schema.search.keys.firstOrNull() ?: return
        loading = true
        status.text = "正在加载第 ${page + 1} 页…"
        val activity = host as? TvMainActivity
        activity?.runIo {
            val result = runCatching {
                runBlocking { session.pipeline.search(key, word, page + 1, session.paramsOf(null)) }
            }
            activity.ui {
                loading = false
                result.onSuccess { paged ->
                    val seen = resultItems.mapTo(HashSet()) { it.id }
                    val fresh = paged.items.filterNot { it.id in seen }
                    resultItems += fresh
                    page++
                    hasMore = paged.hasMore && fresh.isNotEmpty()
                    rebuildResultRows(keepFocus = true)
                    status.text = "已加载 ${resultItems.size} 条" + if (!hasMore) " · 已到底" else ""
                }.onFailure { e ->
                    status.text = "加载失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun showResults() {
        showingResults = true
        keyboardPanel.visibility = View.GONE
        historyBar.visibility = View.GONE
        results.visibility = View.VISIBLE
        resultBar.visibility = View.VISIBLE
        rebuildResultRows(keepFocus = false)
    }

    /** 重建结果行。[keepFocus] 为 true 时（加载更多）保持焦点在原来那一行上，不打断连续浏览。 */
    private fun rebuildResultRows(keepFocus: Boolean) {
        val focusedIndex = if (keepFocus) resultRows.indexOfChild(results.findFocus()) else -1
        resultRows.removeAllViews()
        resultItems.forEachIndexed { index, item ->
            if (index > 0) {
                resultRows.addView(with(TvUi) { ctx.tvDivider() }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, with(TvUi) { ctx.dp(1) },
                ))
            }
            val row = with(TvUi) {
                ctx.tvListRow { open(index) }.apply {
                    addView(ctx.tvText(item.title, 18f, TvUi.TEXT), ctx.matchWrap())
                    val sub = listOfNotNull(item.subtitle, item.type).joinToString(" · ")
                    if (sub.isNotBlank()) {
                        addView(ctx.tvText(sub, 13f, TvUi.TEXT_DIM), ctx.matchWrap(top = 3))
                    }
                }
            }
            resultRows.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        when {
            focusedIndex >= 0 && focusedIndex < resultRows.childCount ->
                resultRows.getChildAt(focusedIndex)?.requestFocus()
            resultItems.isNotEmpty() -> resultRows.getChildAt(0)?.requestFocus()
            else -> (resultBar.getChildAt(0) as? View)?.requestFocus()
        }
    }

    private fun open(position: Int) {
        val item = resultItems.getOrNull(position) ?: return
        // TV 没有"默认自动播放"设置：视频/音频点开即播，固定自动打开。
        host.openDetail(item, autoOpen = item.kind != MediaKind.IMAGE)
    }
}
