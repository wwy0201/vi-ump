package com.vi.ump.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vi.ump.model.LayoutType
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PageSpec
import com.vi.ump.model.ThemeSpec

/**
 * 布局分派。配置只能选 [LayoutType] + 填 slot，这里不接受任何"自定义排版"。
 * M3 实现 4 种列表布局，其余类型给明确的占位说明而不是白屏。
 */
@Composable
fun PageLayout(
    page: PageSpec,
    state: FeedUiState,
    theme: ThemeSpec,
    modifier: Modifier = Modifier,
    onItemClick: (MediaSummary) -> Unit = {},
    onLoadMore: () -> Unit = {},
) {
    val ratio = parseRatio(page.layout.ratio ?: page.card.ratio)

    when (page.layout.type) {
        LayoutType.POSTER_GRID -> PosterGrid(page, state, theme, ratio, modifier, onItemClick, onLoadMore)
        LayoutType.ROW_CARDS -> CardRow(page, state, ratio, modifier, 148.dp, onItemClick, onLoadMore)
        LayoutType.BANNER_HERO -> CardRow(page, state, 16f / 9f, modifier, 300.dp, onItemClick, onLoadMore)
        LayoutType.LIST_LINEAR, LayoutType.SEARCH -> LinearList(page, state, modifier, onItemClick, onLoadMore)
        else -> NotImplementedView(page.layout.type, modifier)
    }
}

@Composable
private fun PosterGrid(
    page: PageSpec,
    state: FeedUiState,
    theme: ThemeSpec,
    ratio: Float,
    modifier: Modifier,
    onItemClick: (MediaSummary) -> Unit,
    onLoadMore: () -> Unit,
) {
    val columns = (page.layout.columns ?: theme.posterColumns).coerceAtLeast(1)
    val gridState = rememberLazyGridState()
    GridPagingEffect(gridState, state, onLoadMore)

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        gridItems(items = state.items, key = { it.id }) { item ->
            PosterCard(
                item = item,
                card = page.card,
                ratio = ratio,
                onClick = { onItemClick(item) },
            )
        }
    }
}

@Composable
private fun CardRow(
    page: PageSpec,
    state: FeedUiState,
    ratio: Float,
    modifier: Modifier,
    cardWidth: Dp,
    onItemClick: (MediaSummary) -> Unit,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    RowPagingEffect(listState, state, onLoadMore)

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listItems(items = state.items, key = { it.id }) { item ->
            PosterCard(
                item = item,
                card = page.card,
                ratio = ratio,
                modifier = Modifier.width(cardWidth),
                onClick = { onItemClick(item) },
            )
        }
    }
}

@Composable
private fun LinearList(
    page: PageSpec,
    state: FeedUiState,
    modifier: Modifier,
    onItemClick: (MediaSummary) -> Unit,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    RowPagingEffect(listState, state, onLoadMore)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listItems(items = state.items, key = { it.id }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onItemClick(item) }
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(92.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    val url = item.poster ?: item.imageUrl
                    if (!url.isNullOrBlank()) {
                        UmpAsyncImage(
                            model = url,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val secondary = item.subtitle
                        ?: listOfNotNull(item.year?.toString(), item.area, item.type).joinToString(" · ")
                    if (secondary.isNotBlank()) {
                        Text(
                            text = secondary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotImplementedView(type: LayoutType, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "布局 ${type.name.lowercase()} 尚未接入",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "配置本身是合法的，只是这个布局要在后续里程碑实现",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

// ── 分页触发：滚到距末尾 3 个以内就请求下一页 ─────────────────

@Composable
private fun GridPagingEffect(state: LazyGridState, ui: FeedUiState, onLoadMore: () -> Unit) {
    LaunchedEffect(state, ui.items.size, ui.hasMore, ui.loadingMore) {
        if (!ui.hasMore || ui.loadingMore) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= ui.items.size - 3) onLoadMore()
            }
    }
}

@Composable
private fun RowPagingEffect(state: androidx.compose.foundation.lazy.LazyListState, ui: FeedUiState, onLoadMore: () -> Unit) {
    LaunchedEffect(state, ui.items.size, ui.hasMore, ui.loadingMore) {
        if (!ui.hasMore || ui.loadingMore) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= ui.items.size - 3) onLoadMore()
            }
    }
}
