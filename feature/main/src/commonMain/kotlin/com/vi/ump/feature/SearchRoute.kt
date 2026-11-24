package com.vi.ump.feature

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vi.ump.model.CardSpec
import com.vi.ump.model.LayoutSpec
import com.vi.ump.model.LayoutType
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PageSpec
import com.vi.ump.model.ThemeSpec
import com.vi.ump.render.FeedUiState
import com.vi.ump.render.UmpPage

/**
 * 搜索页的状态机。
 *
 * 为什么状态不在 [SearchRoute] 里面：从结果点进详情会把 SearchRoute 移出组合，
 * 页面内的 `remember` 随之销毁——返回时关键词和整页结果全丢，用户得重搜一遍。
 * 所以"搜什么、搜到了什么"由这一层持有，SearchRoute 只负责画和转发事件。
 *
 * 一次只搜**当前源**：`flags.multiSourceSearch` 已删除（v1 不做多源搜索），
 * 因此这里只有一个 `searchKey`，不存在合并多站点结果与跨源排序。
 */
class SearchController(session: UmpSession) {

    private val session: UmpSession = session

    /** `resources.search` 的第一个键；为 null 时不发任何请求 */
    val searchKey: String? = session.schema.search.keys.firstOrNull()

    /** 页面外形：配置里声明了 `layout.type = "search"` 就用它，否则"线性列表 + 首页卡片" */
    val pageSpec: PageSpec = session.schema.pages.firstOrNull { it.layout.type == LayoutType.SEARCH }
        ?: run {
            val home = session.schema.pages.firstOrNull { it.layout.type != LayoutType.DETAIL }
            PageSpec(
                id = "search",
                title = "搜索",
                // 搜索结果默认线性列表：找"某一条"时，一行能看更多标题
                layout = LayoutSpec(type = LayoutType.LIST_LINEAR),
                card = home?.card ?: CardSpec(),
            )
        }

    /** 输入停顿后自动搜的毫秒数；配置不写就是 0 = 只按回车/点按钮才搜 */
    val debounceMs: Long = pageSpec.layout.debounceMs ?: 0L

    /** 列表渲染要的主题：跟当前源一致，和首页同一个对象 */
    val theme = session.schema.config.theme

    var query by mutableStateOf("")
        private set

    var submitted by mutableStateOf("")
        private set

    var state by mutableStateOf(FeedUiState())
        private set

    /**
     * 每次提交自增。**同一个词再按一次「搜索」也要重搜**（站点内容会变、上一次可能失败），
     * 只靠 `submitted` 做 key 是区分不出"两次都是关键词 a"的。
     */
    var requestId by mutableStateOf(0)
        private set

    fun updateQuery(value: String) {
        query = value
    }

    fun submit(text: String = query) {
        val q = text.trim()
        if (q.isEmpty()) return
        submitted = q
        requestId++
    }

    /** 重试当前关键词 */
    fun retry() {
        if (submitted.isNotEmpty()) requestId++
    }

    /** 首屏/重搜：整页替换 */
    suspend fun load() {
        val key = searchKey
        if (key == null || submitted.isEmpty()) {
            state = FeedUiState()
            return
        }
        state = FeedUiState(loading = true)
        state = runCatching { session.pipeline.search(key, submitted, 1) }
            .fold(
                onSuccess = {
                    FeedUiState(items = it.items, page = 1, hasMore = it.hasMore, skipped = it.skipped)
                },
                onFailure = { FeedUiState(error = it.message ?: "未知错误") },
            )
    }

    /** 下一页：追加去重（分页参数配错时第 2 页会原样返回第 1 页，那种情况判到底） */
    suspend fun loadMore() {
        val key = searchKey ?: return
        val current = state
        if (current.loading || current.loadingMore || !current.hasMore) return
        val next = current.page + 1
        state = current.copy(loadingMore = true)
        state = runCatching { session.pipeline.search(key, submitted, next) }
            .fold(
                onSuccess = { result ->
                    val latest = state
                    val seen = latest.items.mapTo(HashSet()) { it.id }
                    val fresh = result.items.filterNot { it.id in seen }
                    val exhausted = result.items.isNotEmpty() && fresh.isEmpty()
                    latest.copy(
                        items = latest.items + fresh,
                        page = next,
                        hasMore = result.hasMore && !exhausted,
                        loadingMore = false,
                        skipped = latest.skipped + result.skipped,
                    )
                },
                onFailure = {
                    state.copy(loadingMore = false, hasMore = false, error = it.message ?: "未知错误")
                },
            )
    }
}

/**
 * 搜索页：输入框 + 复用首页那套列表渲染。状态全部来自 [controller]。
 */
@Composable
fun SearchRoute(
    controller: SearchController,
    modifier: Modifier = Modifier,
    onItemClick: (MediaSummary) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRetry: () -> Unit = {},
    /** 当前源的搜索历史（按源存，由 `AppRoot` 从 `HistoryStore` 读出） */
    history: List<String> = emptyList(),
    onClearHistory: () -> Unit = {},
) {
    val query = controller.query
    val submitted = controller.submitted
    val state = controller.state

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = controller::updateQuery,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("搜索标题、演员…", style = MaterialTheme.typography.bodyMedium) },
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { controller.submit() }),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        TextButton(onClick = { controller.updateQuery("") }) {
                            Text("清空", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
            )
            TextButton(onClick = { controller.submit() }, enabled = query.isNotBlank()) {
                Text("搜索")
            }
        }

        // 最近搜索（2026-11-18）：按源存，只在"还没搜/还没输入"时展示，不占结果区的空间。
        // 点一下就填进输入框并立刻搜——历史的作用是少打字，不是让用户再点一次「搜索」。
        if (history.isNotEmpty() && (submitted.isEmpty() || query.isBlank())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "最近：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
                history.forEach { word ->
                    TextButton(
                        onClick = {
                            controller.updateQuery(word)
                            controller.submit()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(word, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
                TextButton(onClick = onClearHistory) {
                    Text(
                        text = "清空",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        }

        if (controller.searchKey == null) {
            CenteredHint("这个源没有配置搜索", "配置里没有 resources.search，顶栏也就不会出现搜索入口")
            return@Column
        }

        if (submitted.isEmpty()) {
            CenteredHint("搜点什么", "输入关键词后按回车，或点右侧「搜索」")
            return@Column
        }

        UmpPage(
            page = controller.pageSpec,
            state = state,
            theme = controller.theme,
            showTitle = false,
            emptyTitle = "没有搜到「$submitted」",
            emptyHint = "换个关键词试试；如果站点用的不是 keyword 参数，检查 resources.search 的 url 模板",
            modifier = Modifier.weight(1f),
            onItemClick = onItemClick,
            onRetry = onRetry,
            onLoadMore = onLoadMore,
        )
    }
}

@Composable
private fun CenteredHint(title: String, hint: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
