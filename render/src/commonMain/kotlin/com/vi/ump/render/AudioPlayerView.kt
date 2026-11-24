package com.vi.ump.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vi.ump.model.Track
import com.vi.ump.player.PlayerState
import com.vi.ump.player.PlayerStatus
import kotlin.math.abs

/**
 * 音频播放页（M4.5）。与视频播放页刻意分开：
 *
 * - 视频是"画面为主、控件叠在画面上"；音频没有画面，改成"封面为主 + 条状控制区"，控件常驻不隐藏
 *   （音频场景用户常常把手机放在一边，没有"点一下唤出控件"的机会）。
 * - 音频的进度语义是**单曲**，不是"这个条目的第几集"：切歌就是换一首的进度（见 docs/04 §8.8）。
 */
@Composable
fun AudioPlayerView(
    title: String,
    subtitle: String,
    coverUrl: String?,
    tracks: List<Track>,
    currentIndex: Int,
    state: PlayerState,
    seekStepMs: Long,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onSpeed: (Float) -> Unit,
    onPickTrack: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showPlaylist: Boolean = true,
    showCover: Boolean = true,
) {
    var speed by remember { mutableStateOf(1.0f) }
    var listOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 切歌后把列表滚到当前项：否则连播到第 12 首，用户看到的还是第 1 首
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && tracks.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(currentIndex) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showCover) {
            Box(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth(0.62f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                UmpAsyncImage(
                    model = coverUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Box(modifier = Modifier.height(16.dp))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 20.dp),
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // 进度：拖动即 seek。`durationMs` 为 0 时禁用，避免"看起来能拖但拖不动"
        val duration = state.durationMs
        Slider(
            value = state.progress,
            onValueChange = { f -> if (duration > 0L) onSeek((duration * f).toLong()) },
            enabled = duration > 0L,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = clockOf(state.positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Text(
                text = clockOf(duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onSeek((state.positionMs - seekStepMs).coerceAtLeast(0L)) },
                enabled = duration > 0L,
            ) {
                Text("\u2039 ${seekStepMs / 1000}s", color = MaterialTheme.colorScheme.onBackground)
            }
            Surface(
                onClick = onTogglePlay,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = when (state.status) {
                            PlayerStatus.PLAYING -> "\u23F8"
                            PlayerStatus.PREPARING -> "\u22EF"
                            PlayerStatus.ERROR -> "!"
                            else -> "\u25B6"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            TextButton(
                onClick = { onSeek(state.positionMs + seekStepMs) },
                enabled = duration > 0L,
            ) {
                Text("${seekStepMs / 1000}s \u203A", color = MaterialTheme.colorScheme.onBackground)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 倍速下拉：与视频页同一套档位，切歌不重置
            var speedOpen by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { speedOpen = true }) {
                    Text("${speed}x \u25BE", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
                }
                DropdownMenu(expanded = speedOpen, onDismissRequest = { speedOpen = false }) {
                    SPEED_OPTIONS.forEach { value ->
                        val selected = abs(speed - value) < 0.01f
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (selected) "${value}x \u2713" else "${value}x",
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                            onClick = {
                                speed = value
                                onSpeed(value)
                                speedOpen = false
                            },
                        )
                    }
                }
            }
            if (showPlaylist && tracks.size > 1) {
                TextButton(onClick = { listOpen = !listOpen }) {
                    Text(
                        text = if (listOpen) "收起列表" else "播放列表 · ${tracks.size}",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    )
                }
            }
        }

        if (state.error != null) {
            Text(
                text = "${state.error?.code}: ${state.error?.message}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (listOpen && tracks.isNotEmpty()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                itemsIndexed(tracks) { i, track ->
                    val current = i == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = (i + 1).toString().padStart(2, '0'),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                            modifier = Modifier.width(28.dp),
                        )
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onBackground
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (current) {
                            Text(
                                text = "\u25B6",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        TextButton(onClick = { onPickTrack(i) }) {
                            Text(
                                text = "播放",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                                },
                            )
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.height(12.dp))
        }
    }
}

/** 与视频页同一套档位（`docs/02` F6.6）。分开定义是因为两页互不引用，重复两个常量比跨文件耦合便宜 */
private val SPEED_OPTIONS = listOf(0.5f, 1.0f, 1.5f, 2.0f)

/** 音频时长普遍短（预览片段 30s），用 `mm:ss` 就够 */
private fun clockOf(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}
