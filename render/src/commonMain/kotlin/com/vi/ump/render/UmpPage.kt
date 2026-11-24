package com.vi.ump.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PageSpec
import com.vi.ump.model.ThemeSpec

/**
 * 一屏的状态。`loading/error/items` 三态由这里统一承载，
 * 各个布局只管画列表，不关心数据怎么来。
 */
data class FeedUiState(
    val items: List<MediaSummary> = emptyList(),
    val page: Int = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val skipped: Int = 0,
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * 页面渲染入口：标题 + 三态 + 布局 + 分页尾巴。
 *
 * 注意这里**没有**网络调用，也没有 `HttpClient`——
 * 数据由上层（feature）灌进 [FeedUiState]，render 只负责画。
 */
@Composable
fun UmpPage(
    page: PageSpec,
    state: FeedUiState,
    theme: ThemeSpec,
    modifier: Modifier = Modifier,
    /**
     * 是否在顶部渲染 `layout.title ?: page.title`。
     * 首页传 `false`：数据源名已经由顶栏的下拉列表表达（那是唯一"当前在哪"的入口），
     * 栏目名也已经在栏目 chips 里，再顶一行大标题纯属重复占高度。
     * 默认 `true`，留给以后"没有顶栏提示"的场景（比如独立页面）。
     */
    showTitle: Boolean = true,
    /**
     * 空结果的文案。默认是"这里还没有内容 / 检查 list 路径"，给首页兜底用；
     * 搜索页要的是"没有搜到 xxx"，靠这两个参数换掉，而不是再写一套空态渲染。
     */
    emptyTitle: String = "这里还没有内容",
    emptyHint: String = "检查配置里的 list 路径是否命中，或者接口返回是否为空",
    onItemClick: (MediaSummary) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRetry: () -> Unit = {},
    /**
     * 非空时，**加载失败 / 空结果**两屏都多给一颗「切换数据源」：
     * 一个源挂了（或只有这一个源、它自己返回空）不该把用户困在一屏错误里，
     * 顶栏下拉虽然也能换源，但用户此刻的注意力就在这屏中间。
     * 搜索页不传这个参数——那边"没搜到"是正常结果，不该引导换源。
     */
    onSwitchSource: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        val title = page.layout.title ?: page.title
        if (showTitle && title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }

        when {
            state.error != null -> ErrorState(state.error, onRetry, onSwitchSource, Modifier.weight(1f))
            state.items.isEmpty() && state.loading -> LoadingState(Modifier.weight(1f))
            state.items.isEmpty() -> EmptyState(emptyTitle, emptyHint, onRetry, onSwitchSource, Modifier.weight(1f))
            else -> {
                PageLayout(
                    page = page,
                    state = state,
                    theme = theme,
                    modifier = Modifier.weight(1f),
                    onItemClick = onItemClick,
                    onLoadMore = onLoadMore,
                )
                if (state.loadingMore) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (state.skipped > 0) {
            Text(
                text = "已加载 ${state.items.size} 条（${state.skipped} 条因必填字段缺失被跳过）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(
    title: String,
    hint: String,
    onRetry: () -> Unit,
    onSwitchSource: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        // 只有首页会传 onSwitchSource：空结果在那边往往意味着"这个源这会儿没内容"，
        // 给一颗"重新加载"和一颗"换源"，比让用户自己去顶栏找要直接
        if (onSwitchSource != null) {
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onRetry) { Text("重新加载") }
                Button(onClick = onSwitchSource) { Text("切换数据源") }
            }
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    onSwitchSource: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "加载失败",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRetry) { Text("重试") }
            if (onSwitchSource != null) {
                Button(onClick = onSwitchSource) { Text("切换数据源") }
            }
        }
        if (onSwitchSource != null) {
            Text(
                text = "一直这样就换一个数据源，或到设置里检查这份配置。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
