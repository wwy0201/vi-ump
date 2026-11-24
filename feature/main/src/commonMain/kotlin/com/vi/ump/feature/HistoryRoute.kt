package com.vi.ump.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vi.ump.render.UmpAsyncImage
import com.vi.ump.render.readableOn

/**
 * 历史页（2026-11-18）。
 *
 * 用户的要求只有一句："历史记录就是帮助用户快速进入未看完的"。
 * 所以这一页**只做一件事**：把"看到一半的东西"按源列出来，点一下直接回到那一张/那一集。
 *
 * 三条刻意的取舍：
 * 1. **按源分组**：多条源的历史混在一起时，"这是哪个站的东西"比"什么时候看的"更重要；
 *    组头和条目都带该源的标识色，一眼能分开。
 * 2. **不换源**：点条目只打开那一本/那一集，顶栏的数据源仍是用户自己选的那个（由 `AppRoot` 保证），
 *    要换源必须回到首页手动点下拉 —— 与用户的明确要求一致。
 * 3. **没有空态装饰**：没有历史时只给一句话 + "回首页"，不放插图/引导，避免这一页在没内容时反而更醒目。
 *
 * 2026-11-24 追加：**每个源默认只列最近 5 条**（首页这张表是"快速接着看"，不是档案库），
 * 组头里「清空」后面跟一颗「更多」，点开才列出这个源的全部记录——记录多起来之后
 * 一屏被一个源铺满，其他源就看不见了。
 */
@Composable
fun HistoryRoute(
    groups: List<HistoryGroup>,
    sourceColors: Map<String, Color>,
    modifier: Modifier = Modifier,
    /** 打开失败时的提示（取详情失败、源已不存在等），平时为 null */
    message: String? = null,
    /** 正在打开历史记录：跨源要先建 session、取详情，必须给用户一个明确的等待态 */
    busy: Boolean = false,
    /** 正在打开的那一条标题（遮罩文案用） */
    busyTitle: String? = null,
    /** 打开的是否为其他源的记录（决定遮罩里是否提示"跨源"） */
    busyForeign: Boolean = true,
    onOpen: (HistoryStore.WatchItem) -> Unit = {},
    onClear: (sourceId: String) -> Unit = {},
) {
    // 待清空的源（确认框期间列表可能变化，所以存 id）
    var pendingClear by remember { mutableStateOf<String?>(null) }
    // 正在「查看某个源的全部记录」的源 id
    var pendingMore by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (groups.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(40.dp))
                    Text(
                        text = "还没有观看记录",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "打开过视频、音频或图集之后，这里会按源列出「看到第几集 / 第几张」，点一下直接接着看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    groups.forEach { group ->
                        item(key = "header-${group.sourceId}") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = group.sourceLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    // 源的标识色可能是深色（抖音 #161823），当字色会看不见，兜底一次
                                    color = sourceColors[group.sourceId]
                                        ?.readableOn(MaterialTheme.colorScheme.background)
                                        ?: MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(
                                    onClick = { pendingClear = group.sourceId },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    ),
                                ) {
                                    Text("清空", style = MaterialTheme.typography.labelSmall)
                                }
                                // 「更多」紧跟「清空」：这一页只列最近 5 条，完整记录在弹层里看。
                                // 一律显示（不按条数隐藏）——同一个组头永远长这样，用户不用猜"为什么这次没有"。
                                TextButton(
                                    onClick = { pendingMore = group.sourceId },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    ),
                                ) {
                                    Text("更多", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        val visible = group.items.take(MAX_HISTORY_PER_SOURCE)
                        items(visible, key = { "${it.sourceId}:${it.itemId}" }) { item ->
                            HistoryRow(item = item, onClick = { onOpen(item) })
                        }
                        // 被折叠的条数得说清楚，否则用户不知道"是不是丢记录了"
                        if (group.items.size > visible.size) {
                            item(key = "more-hint-${group.sourceId}") {
                                Text(
                                    text = "共 ${group.items.size} 条，仅显示最近 ${visible.size} 条 · 点「更多」查看全部",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
                                )
                            }
                        }
                    }
                    item(key = "tail") { Spacer(Modifier.height(16.dp)) }
                }
            }
        }

        if (busy) {
            HistoryLoadingOverlay(title = busyTitle, foreign = busyForeign)
        }
    }

    pendingClear?.let { sourceId ->
        val label = groups.firstOrNull { it.sourceId == sourceId }?.sourceLabel ?: sourceId
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text("清空这个源的历史？") },
            text = { Text("只清「$label」的观看记录，其他源的历史与阅读进度不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    onClear(sourceId)
                    pendingClear = null
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) { Text("取消") }
            },
        )
    }

    // 「更多」：列出这个源的全部记录。列表直接复用 `HistoryRow`（缩略图、进度、继续都一致），
    // 点一条就打开它并把弹层关掉——记录很多时不用滚回顶部找那一行。
    pendingMore?.let { sourceId ->
        val group = groups.firstOrNull { it.sourceId == sourceId }
        if (group != null) {
            AlertDialog(
                onDismissRequest = { pendingMore = null },
                title = { Text("${group.sourceLabel} · 共 ${group.items.size} 条") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(group.items, key = { "${it.sourceId}:${it.itemId}" }) { item ->
                            HistoryRow(
                                item = item,
                                onClick = {
                                    pendingMore = null
                                    onOpen(item)
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { pendingMore = null }) { Text("关闭") }
                },
            )
        }
    }
}

/** 每个源在历史首页上默认最多列这么多条，其余进「更多」弹层（2026-11-24 用户要求） */
private const val MAX_HISTORY_PER_SOURCE = 5

/**
 * 打开历史记录时的全屏等待遮罩。
 *
 * 跨源条目不是"点一下就有"：要先 `UmpSession.of()` 建临时会话，再请求详情，
 * 网络慢时 2~5 秒都有可能。没有遮罩用户会以为没点上而连续点，
 * 所以这里既给文案也**吃掉所有点击**，直到成功/失败/取消。
 */
@Composable
private fun HistoryLoadingOverlay(title: String?, foreign: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.34f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "正在打开",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!title.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (foreign) {
                        "跨源记录需要先加载源和详情，请稍候"
                    } else {
                        "正在加载详情和选集，请稍候"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 一个源的历史分组（`AppRoot` 组装，因为只有它知道源列表与当前会话） */
data class HistoryGroup(
    val sourceId: String,
    val sourceLabel: String,
    val items: List<HistoryStore.WatchItem>,
)

@Composable
private fun HistoryRow(item: HistoryStore.WatchItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onClick,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            // 不写死颜色的话，标题会取 TextButton 的默认内容色 = primary，抖音那种深品牌色直接隐形
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // 缩略图：44×60 足够判断"是哪一本"，又不至于把列表撑成画廊
                val poster = item.poster
                if (!poster.isNullOrBlank()) {
                    UmpAsyncImage(
                        model = poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 44.dp, height = 60.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                } else {
                    Spacer(Modifier.size(width = 44.dp, height = 60.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append(item.progressLabel)
                            item.groupName?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        // 进度行要"次要但不隐形"：明确用 onSurface 降透明度，别依赖 primary
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "继续 ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}
