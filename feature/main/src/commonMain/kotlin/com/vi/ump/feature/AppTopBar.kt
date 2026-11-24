package com.vi.ump.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vi.ump.render.readableOn

/**
 * 应用顶栏。三种形态共用一条：
 *
 * - 首页：左侧 App 标识，右侧「搜索」+「当前数据源」下拉 + 设置
 * - 详情：左侧返回，中间标题，右侧只剩设置
 * - 设置：左侧返回，中间"设置"
 *
 * 搜索按钮放在数据源下拉的**左侧**：下拉是"换源"，搜索是"在当前源里找"，先定源再找东西，
 * 顺序与人的操作习惯一致（且它只在首页出现，进详情/设置自动消失）。
 */
@Composable
fun AppTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    current: SourceEntry? = null,
    sources: List<SourceEntry> = emptyList(),
    onSelect: (SourceEntry) -> Unit = {},
    /** 非空才出搜索入口：`flags.enableSearch` 与 `resources.search` 缺一不可（由 AppRoot 判定） */
    onSearch: (() -> Unit)? = null,
    /**
     * 历史入口（2026-11-18）：只在首页出现。位置在搜索按钮与源下拉之间——
     * 先"看过的"再"要搜的"再"换源"，三个入口从左到右按"回到内容"的距离排。
     */
    onHistory: (() -> Unit)? = null,
    /**
     * 源标识色（id → Color）。当前源的名字与下拉里的当前项都用它；
     * 其它源不给色 —— 未选中的一律灰色，颜色只用来回答"现在我用的哪个源"。
     */
    sourceColors: Map<String, Color> = emptyMap(),
    onSettings: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Text(
                    text = "\u2190",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Text(
            text = title ?: "vi-ump",
            style = if (title == null) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (title == null) 0.35f else 1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (onBack == null) 8.dp else 4.dp),
        )

        Spacer(Modifier.weight(1f))

        if (onSearch != null) {
            IconButton(onClick = onSearch, modifier = Modifier.size(40.dp)) {
                Text(
                    text = "\uD83D\uDD0D",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }

        if (onHistory != null) {
            IconButton(onClick = onHistory, modifier = Modifier.size(40.dp)) {
                // 表盘字符（U+25F7）：不引 icon 依赖，又比"历史"两个字更省宽度
                Text(
                    text = "\u25F7",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }

        if (current != null) {
            SourceDropdown(
                current = current,
                sources = sources,
                sourceColors = sourceColors,
                onSelect = onSelect,
            )
        }

        if (onSettings != null) {
            IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                // 齿轮图标：暂用字符，等引入 compose.materialIcons 后换成 Icons.Default.Settings
                Text(
                    text = "\u2699",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun SourceDropdown(
    current: SourceEntry,
    sources: List<SourceEntry>,
    sourceColors: Map<String, Color>,
    onSelect: (SourceEntry) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    // 下拉高度随屏幕走：一项单行 = 48dp（Material 菜单项的最小触摸目标，再压会裁掉最后一项），
    // 菜单最多占屏高的 55%，再夹到 4..8 项，超出的部分靠滚动。
    val maxVisible = rememberMaxVisibleSourceItems()
    val visibleCount = sources.size.coerceAtMost(maxVisible).coerceAtLeast(1)
    val itemHeight = 48.dp
    val currentColor = sourceColors[current.id]?.readableOn(MaterialTheme.colorScheme.background)

    Column {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = current.label,
                style = MaterialTheme.typography.labelLarge,
                // 当前源用自己的标识色：一屏只看得到"现在用哪个源"，颜色比文字更快
                color = currentColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (expanded) "\u25B4" else "\u25BE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        // 限高放在 DropdownMenu 自己的 modifier 上：Material3 的菜单内容本来就是
        // 一个带 verticalScroll 的 Column，给它一个 maxHeight，多出来的项自然可滚。
        // （不要在里头再套 LazyColumn：外层 verticalScroll 会以无界高度量它，直接崩。）
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 220.dp, max = 340.dp)
                .heightIn(max = itemHeight * visibleCount),
        ) {
            sources.forEach { entry ->
                val selected = entry.id == current.id
                DropdownMenuItem(
                    modifier = Modifier.heightIn(min = itemHeight),
                    text = {
                        // 单行：源名 + 版本号。**不再打勾**——选中与否由颜色回答：
                        // 当前源 = 它自己的标识色，其它源 = 灰。勾和名字抢同一行宽度，还容易被
                        // 误读成"已看过/已收藏"。
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) {
                                    sourceColors[entry.id]
                                        ?.readableOn(MaterialTheme.colorScheme.background)
                                        ?: MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (!entry.version.isNullOrBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "v${entry.version}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(entry)
                    },
                )
            }
        }
    }
}

/**
 * 下拉里最多放多少个源：屏高的 55% ÷ 单项 48dp，夹到 4..8。
 * 720×1280（≈852dp 高）的小屏算出 9→夹到 8；桌面 800dp 窗口算出 9→8。
 */
@Composable
private fun rememberMaxVisibleSourceItems(): Int {
    val density = LocalDensity.current
    val windowHeightDp = with(density) { LocalWindowInfo.current.containerSize.height.toDp().value }
    return (windowHeightDp * 0.55f / 48f).toInt().coerceIn(4, 8)
}
