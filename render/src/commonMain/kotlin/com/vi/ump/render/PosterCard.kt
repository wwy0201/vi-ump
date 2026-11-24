package com.vi.ump.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vi.ump.model.CardSpec
import com.vi.ump.model.MediaKind
import com.vi.ump.model.MediaSummary

/** `"2:3"` → 0.66（宽/高）。解析失败用海报默认比例 */
fun parseRatio(ratio: String?, fallback: Float = 2f / 3f): Float {
    val parts = ratio?.split(":") ?: return fallback
    if (parts.size != 2) return fallback
    val w = parts[0].trim().toFloatOrNull() ?: return fallback
    val h = parts[1].trim().toFloatOrNull() ?: return fallback
    return if (h <= 0f) fallback else w / h
}

/**
 * 最小卡片单元：图 + 标题 + 一行副信息。
 *
 * 卡片能取哪些字段由 [CardSpec] 决定，但**排版固定**——
 * 配置只能选"用哪个字段"，不能改布局，这是 Layout Palette 的边界。
 */
@Composable
fun PosterCard(
    item: MediaSummary,
    card: CardSpec = CardSpec(),
    ratio: Float = 2f / 3f,
    showKindBadge: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val imageUrl = when (card.image) {
        "imageUrl" -> item.imageUrl ?: item.poster
        else -> item.poster ?: item.imageUrl
    }

    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (!imageUrl.isNullOrBlank()) {
                UmpAsyncImage(
                    model = imageUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (showKindBadge && item.kind != MediaKind.VIDEO) {
                KindBadge(item.kind, Modifier.align(Alignment.TopStart).padding(6.dp))
            }

            val badge = card.badge?.let { fieldValue(item, it) }
            if (!badge.isNullOrBlank()) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        val secondary = card.subtitle?.let { fieldValue(item, it) } ?: defaultSecondary(item)
        if (!secondary.isNullOrBlank()) {
            Text(
                text = secondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun KindBadge(kind: MediaKind, modifier: Modifier = Modifier) {
    val label = when (kind) {
        MediaKind.AUDIO -> "音频"
        MediaKind.IMAGE -> "图集"
        MediaKind.VIDEO -> "视频"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/** 卡片副标题：只支持域字段白名单里的几个，写错就是空（不崩） */
private fun defaultSecondary(item: MediaSummary): String? = when {
    item.artist != null -> item.artist
    item.score != null -> "${item.score} 分"
    item.year != null -> "${item.year}"
    item.trackCount != null -> "${item.trackCount} 项"
    else -> null
}

private fun fieldValue(item: MediaSummary, field: String): String? = when (field) {
    "title" -> item.title
    "subtitle" -> item.subtitle
    "year" -> item.year?.toString()
    "area" -> item.area
    "type" -> item.type
    "score" -> item.score?.toString()
    "artist" -> item.artist
    "album" -> item.album
    "trackCount" -> item.trackCount?.toString()
    "duration" -> item.durationMs?.let { "${it / 1000 / 60} 分钟" }
    else -> null
}
