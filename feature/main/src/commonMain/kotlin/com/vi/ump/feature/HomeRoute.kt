package com.vi.ump.feature

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vi.ump.config.CompiledSchema
import com.vi.ump.config.ConfigCompiler
import com.vi.ump.config.ConfigException
import com.vi.ump.config.ConfigIssue
import com.vi.ump.config.Severity
import com.vi.ump.model.ChannelSpec
import com.vi.ump.model.LayoutType
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PagedResult
import com.vi.ump.render.FeedUiState
import com.vi.ump.render.UmpPage
import com.vi.ump.source.HttpFetcher
import com.vi.ump.source.SourcePipeline
import com.vi.ump.source.createHttpClient
import com.vi.ump.storage.createEpisodesCache
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * 一次运行一个数据源 = 一份编译好的配置 + 一条取数管线 + 一份**会话级缓存**。
 *
 * 缓存的生命周期刻意挂在 [UmpSession] 上：`AppRoot` 里 `remember(current, client)` 会在切数据源时
 * 重建 session，进程退出自然整体消失。所以它天然满足"只在本次打开 App 期间有效，切源/退出即失效"，
 * 而且不需要落盘、不需要失效策略。
 */
class UmpSession(
    val schema: CompiledSchema,
    val pipeline: SourcePipeline,
    /** 当前数据源，用于顶栏与设置页展示 */
    val source: SourceEntry,
) {
    private val feedCache = mutableMapOf<String, PagedResult<MediaSummary>>()

    /** 命中即不再发请求：同一次打开 App 内切栏目来回翻不重复打站点 */
    fun cached(cacheKey: String): PagedResult<MediaSummary>? = feedCache[cacheKey]

    fun remember(cacheKey: String, value: PagedResult<MediaSummary>) {
        feedCache[cacheKey] = value
    }

    companion object {
        /** 编译配置 + 装配取数管线。请求一律真发出去，不做任何本地假数据回放 */
        fun of(entry: SourceEntry, client: HttpClient = createHttpClient()): UmpSession {
            val schema = ConfigCompiler.loadAndCompile(entry.json)
            return UmpSession(
                schema = schema,
                // 选集缓存走平台持久化存储，有效期 6 小时（过期自动重拉）；
                // 列表的进程内缓存（feedCache）保持"退 App 即失效"不变
                pipeline = SourcePipeline(
                    schema = schema,
                    // 超时与重试取配置里的值，但 fetcher 内部会夹到系统上限（30s / 3 次 / 间隔 3s）
                    fetcher = HttpFetcher(
                        client,
                        timeoutMs = schema.config.runtime.timeoutMs,
                        retry = schema.config.runtime.retry,
                    ),
                    episodesCache = createEpisodesCache(),
                ),
                source = entry,
            )
        }
    }
}

/**
 * 首页。数据流：
 *
 * ```
 * 选栏目 → 拼 cacheKey → 命中会话缓存则直接用，否则 pipeline.list(feed, 1, params) → FeedUiState → render.UmpPage
 * ```
 *
 * 栏目（[ChannelSpec]）只换"取数入口"，排版仍走 `layout`；视图切换只换 `layout.type`。
 * 这一层不碰绘制细节，绘制全在 `render`。
 */
@Composable
fun HomeRoute(
    session: UmpSession,
    modifier: Modifier = Modifier,
    onItemClick: (MediaSummary) -> Unit = {},
    /** 取数失败/空结果时那颗「切换数据源」：由壳层弹切换面板（AppRoot 提供） */
    onSwitchSource: (() -> Unit)? = null,
) {
    val page = remember(session) {
        session.schema.pages.firstOrNull { it.layout.type != LayoutType.DETAIL }
    }
    if (page == null) {
        // 一个可渲染的列表页都没有：这是"配置不可用"，不是"没有内容"，
        // 所以直接摊问题码，并留一颗「切换数据源」——否则用户只能靠顶栏下拉自救
        ConfigIssues(
            issues = listOf("配置里没有可渲染的列表页（layout.type 不能只有 detail）"),
            onSwitchSource = onSwitchSource,
        )
        return
    }

    val channels = page.channels
    var activeChannelId by rememberSaveable(session.source.id) {
        mutableStateOf<String?>(channels.firstOrNull()?.id)
    }
    val activeChannel = channels.firstOrNull { it.id == activeChannelId }

    // 栏目没写 params/layout/card 就继承页面级：栏目只是"多一个取数入口"，不是新的一套排版
    val feedKey = activeChannel?.feed ?: page.feed
    val params = activeChannel?.params?.takeIf { it.isNotEmpty() } ?: page.params
    val baseLayout = activeChannel?.layout ?: page.layout

    // 视图模式也要跟着首页子树一起保存：否则切到列表模式后进详情，返回会退回网格。
    // 枚举本身不保证能进 SaveableStateRegistry，所以只存 name。
    var viewModeName by rememberSaveable(session.source.id, activeChannelId) {
        mutableStateOf((page.viewModes.firstOrNull() ?: baseLayout.type).name)
    }
    val viewMode = LayoutType.values().firstOrNull { it.name == viewModeName } ?: baseLayout.type

    val cacheKey = remember(feedKey, params) {
        buildString {
            append(feedKey ?: "")
            append('|')
            params.entries.sortedBy { it.key }.forEach { append(it.key).append('=').append(it.value).append('&') }
        }
    }

    // 返回首页时先从会话缓存同步恢复列表：如果第一帧是“加载中”，网格/列表还没进入组合，
    // SaveableStateHolder 里的滚动位置就会晚一拍才被消费。这里直接给缓存结果，滚动位置当帧归位。
    var state by remember(session, cacheKey) {
        val hit = session.cached(cacheKey)
        mutableStateOf(
            if (hit == null) {
                FeedUiState(loading = true)
            } else {
                FeedUiState(
                    items = hit.items,
                    page = hit.page,
                    hasMore = hit.hasMore,
                    skipped = hit.skipped,
                )
            },
        )
    }
    var retry by remember(session, cacheKey) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cacheKey, retry) {
        // 栏目没声明 feed（页面级也没有）= 这个源没有首页取数入口。
        // 以前这里直接 return，界面会停在"加载中"永转圈——现在给一条明确的失败原因 + 两个出路
        if (feedKey == null) {
            state = FeedUiState(error = "这个源没有配置首页取数入口（feed），没法加载列表")
            return@LaunchedEffect
        }
        // retry > 0 = 用户主动重试，或双击当前栏目要"刷新"：这时必须真的重新打站点，
        // 不能拿会话缓存顶上去（否则看起来像"点了没反应"）。
        if (retry == 0) {
            session.cached(cacheKey)?.let { hit ->
                state = FeedUiState(
                    items = hit.items,
                    // 页码跟着缓存走：缓存里可能是"已翻到第 3 页"的合并结果，
                    // 这里写死 1 会让下一次 loadMore 重新请求第 2 页并把条目重复追加一遍
                    page = hit.page,
                    hasMore = hit.hasMore,
                    skipped = hit.skipped,
                )
                return@LaunchedEffect
            }
        }
        state = FeedUiState(loading = true)
        state = runCatching { session.pipeline.list(feedKey, 1, params) }
            .fold(
                onSuccess = { result ->
                    session.remember(cacheKey, result)
                    FeedUiState(
                        items = result.items,
                        page = 1,
                        hasMore = result.hasMore,
                        skipped = result.skipped,
                    )
                },
                onFailure = { FeedUiState(error = it.message ?: "未知错误") },
            )
    }

    val renderPage = page.copy(
        layout = baseLayout.copy(type = viewMode),
        card = activeChannel?.card ?: page.card,
    )

    Column(modifier = modifier.fillMaxSize()) {
        if (channels.size > 1 || page.viewModes.size > 1) {
            ChannelBar(
                channels = channels,
                activeId = activeChannelId,
                onSelect = { activeChannelId = it },
                // 双击当前栏目 = 重新拉这一栏的第一页（走 retry 分支，绕开会话缓存）
                onRefresh = { retry++ },
                viewModes = page.viewModes,
                viewMode = viewMode,
                onToggleView = {
                    val order = page.viewModes
                    val index = order.indexOf(viewMode).let { if (it < 0) 0 else it }
                    viewModeName = order[(index + 1) % order.size].name
                },
            )
        }

        UmpPage(
            page = renderPage,
            state = state,
            theme = session.schema.config.theme,
            // 源名在顶栏下拉里、栏目名在 chips 里，首页不再重复一行大标题（省掉竖向空间）
            showTitle = false,
            // 空结果在首页往往不是"配置写错"，而是"这个源这会儿没内容/挂了"：
            // 文案先说这层，再把"换源"按钮摆在正中间，用户不用去顶栏找
            emptyTitle = "没有取到数据",
            emptyHint = "接口通了但列表是空的。换一个数据源看看，或者检查配置里的 list 路径。",
            modifier = Modifier.weight(1f),
            onItemClick = onItemClick,
            onRetry = { retry++ },
            onSwitchSource = onSwitchSource,
            onLoadMore = {
                if (!state.loading && !state.loadingMore && state.hasMore && feedKey != null) {
                    val next = state.page + 1
                    println("[feed] loadMore $feedKey page=$next 已有=${state.items.size}")
                    state = state.copy(loadingMore = true)
                    scope.launch {
                        state = runCatching { session.pipeline.list(feedKey, next, params) }
                            .fold(
                                onSuccess = { result ->
                                    val latest = state
                                    // 按 id 去重：分页参数配错、或站点根本不支持该参数时，
                                    // 第 2 页会原样返回第一页那一批 —— 无脑追加就是同一屏内容复制 N 遍
                                    val seen = latest.items.mapTo(HashSet()) { it.id }
                                    val fresh = result.items.filterNot { it.id in seen }
                                    val exhausted = result.items.isNotEmpty() && fresh.isEmpty()
                                    println(
                                        "[feed] page=$next 返回=${result.items.size} 新增=${fresh.size} " +
                                            "站点hasMore=${result.hasMore} 判到底=$exhausted"
                                    )
                                    val merged = latest.copy(
                                        items = latest.items + fresh,
                                        page = next,
                                        // 整页都是旧条目 = 这个站点的分页到这里就没有新东西了，
                                        // 直接判到底，别一路翻到 maxPage 空转请求
                                        hasMore = result.hasMore && !exhausted,
                                        loadingMore = false,
                                        skipped = latest.skipped + result.skipped,
                                    )
                                    // 已合并的多页也回写会话缓存：切到别的栏目再切回来要接着往下翻，
                                    // 而不是从第 1 页重来（页码一并带上，见上面的 hit.page）
                                    session.remember(
                                        cacheKey,
                                        PagedResult(
                                            items = merged.items,
                                            page = merged.page,
                                            hasMore = merged.hasMore,
                                            skipped = merged.skipped,
                                        ),
                                    )
                                    merged
                                },
                                onFailure = { error ->
                                    state.copy(loadingMore = false, hasMore = false, error = error.message)
                                },
                            )
                    }
                }
            },
        )
    }
}

/**
 * 栏目条：左边是可横向滚动的栏目 chips，右边是网格/列表切换。
 * 两者都是"数据入口/排版"的开关，所以放在首页而不是顶栏——顶栏留给数据源与设置。
 *
 * 交互（2026-11-17）：单击 chip = 切栏目；**双击当前选中的 chip** = 重新拉这一栏的数据
 * （用户口径："当前选中的栏目双击触发刷新"）。两边都在 chips 上，是因为刷新是"这一栏的事"，
 * 不占用下拉菜单或另一颗按钮。
 */
@Composable
private fun ChannelBar(
    channels: List<ChannelSpec>,
    activeId: String?,
    onSelect: (String) -> Unit,
    /** 双击**当前选中**栏目：绕开会话缓存重新打第一页 */
    onRefresh: () -> Unit,
    viewModes: List<LayoutType>,
    viewMode: LayoutType,
    onToggleView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            channels.forEach { channel ->
                val selected = channel.id == activeId
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    },
                    // 单击 = 选中；双击**当前这一栏** = 刷新它的数据。
                    // 双击别的 chip 就退化成"选中它"（不给"双击就换栏目又刷新"这种含混行为）。
                    modifier = Modifier.combinedClickable(
                        onClick = { onSelect(channel.id) },
                        onDoubleClick = {
                            if (channel.id == activeId) onRefresh() else onSelect(channel.id)
                        },
                    ),
                ) {
                    Text(
                        text = channel.title.ifBlank { channel.id },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }

        if (viewModes.size > 1) {
            TextButton(onClick = onToggleView) {
                Text(
                    text = if (viewMode == LayoutType.LIST_LINEAR) "▦ 网格" else "☰ 列表",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** 配置问题面板：把 W/E 码直接摊在屏幕上，别让"白屏"成为唯一反馈 */
private class ConfigIssueHint(message: String) : RuntimeException(message)

@Composable
internal fun ConfigIssues(
    issues: List<*>,
    modifier: Modifier = Modifier,
    /** 非空才出「切换数据源」：这一屏代表"这个源用不了"，换源是最直接的出路 */
    onSwitchSource: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        Text("配置有问题", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "以下条目导致页面无法渲染：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(issues.size) { index ->
                val issue = issues[index]
                Text(
                    text = when (issue) {
                        is ConfigIssue -> "[${issue.code.code}] ${issue.path} ${issue.message}"
                        else -> issue.toString()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
        if (onSwitchSource != null) {
            Button(onClick = onSwitchSource, modifier = Modifier.padding(top = 12.dp)) {
                Text("切换数据源")
            }
        }
    }
}

/** 装载失败时的兜底渲染（JSON 都解析不了、或所有源都停用这类"没有内容可放"的状态） */
@Composable
fun ConfigBroken(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "配置加载失败",
    /** 非空才出按钮：给用户一条出路，而不是留一屏字让他自己猜 */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) {
                Text(actionLabel)
            }
        }
    }
}

internal fun throwableMessage(t: Throwable): String = when (t) {
    is ConfigException -> t.issue.toString()
    else -> t.message ?: t.toString()
}

internal fun hasBlockingError(issues: List<ConfigIssue>): Boolean =
    issues.any { it.severity == Severity.ERROR }
