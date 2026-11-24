package com.vi.ump.render

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vi.ump.model.MediaDetail
import com.vi.ump.model.MediaKind
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PageSpec
import com.vi.ump.model.ThemeSpec
import com.vi.ump.model.Track
import com.vi.ump.model.TrackGroup

/**
 * 详情页。配置里只声明"数据源"（`layout.resource`）与展开行数（`layout.expandLines`），
 * 不声明排版——这是 docs/01 §3 的取舍之一。
 *
 * 标题/海报**一律取用户点进去的那一条**（[item]），而不是详情接口返回的 summary：
 * YouTube 没有按 id 取单条的公开接口，详情配置复用 feed，只能拿到最新一条；
 * 若用详情返回的标题，点第 3 条会显示第 1 条的标题。详情数据只用来补描述与演职员。
 */
@Composable
fun UmpDetail(
    page: PageSpec?,
    item: MediaSummary,
    theme: ThemeSpec,
    modifier: Modifier = Modifier,
    detail: MediaDetail? = null,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    onPlay: (() -> Unit)? = null,
    /** 点选某一集/某一图，直接播/看这一项 */
    onSelectTrack: (Track) -> Unit = {},
) {
    val expandLines = (page?.layout?.expandLines ?: 4).coerceAtLeast(1)
    val ratio = parseRatio(page?.layout?.ratio ?: page?.card?.ratio)
    val playUrl = item.playUrl ?: detail?.summary?.playUrl

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Poster(item, ratio)

        Spacer(Modifier.height(16.dp))

        Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            UmpContentInfo(
                item = item,
                page = page,
                detail = detail,
                loading = loading,
                error = error,
                onRetry = onRetry,
                expandLines = expandLines,
                playUrl = playUrl,
                onPlay = onPlay,
                onSelectTrack = onSelectTrack,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 内容页的公共信息区：副标题、元信息、简介、演职员、播放按钮与选集。
 *
 * 详情页和合并后的「视频内容页」共用这一份，避免两边各维护一套简介/选集。
 * 标题与海报由调用方决定：详情页在上方单独画海报；内容页由播放器区域承担。
 */
@Composable
fun UmpContentInfo(
    item: MediaSummary,
    page: PageSpec?,
    detail: MediaDetail?,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    expandLines: Int = (page?.layout?.expandLines ?: 4).coerceAtLeast(1),
    /** 有值时在信息区末尾画「播放」；内容页已经有封面播放键，传 null 即可 */
    playUrl: String? = null,
    onPlay: (() -> Unit)? = null,
    onSelectTrack: (Track) -> Unit = {},
) {
    val subtitle = item.subtitle
    if (!subtitle.isNullOrBlank()) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    val meta = listOfNotNull(
        item.year?.toString(),
        item.area,
        item.type,
        item.durationMs?.let { formatDuration(it) },
        item.score?.let { "评分 $it" },
    ).joinToString(" · ")
    if (meta.isNotBlank()) {
        Text(
            text = meta,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    if (loading) {
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "详情加载失败：$error",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry) { Text("重试") }
    }

    val description = detail?.description
    if (!description.isNullOrBlank()) {
        Spacer(Modifier.height(16.dp))
        DescriptionBlock(description, expandLines)
    }

    val people = listOfNotNull(
        detail?.director?.let { "导演 $it" },
        detail?.actors?.takeIf { it.isNotEmpty() }?.let { "演员 ${it.joinToString("、")}" },
    ).joinToString(" · ")
    if (people.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = people,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }

    // 详情页不再摊"播放地址"（几百字符的 CDN 链接会把简介和按钮全挤下去）：
    // 想看地址就去播放器点「复制播放地址」。这里只保留一个能点的「播放」。
    if (!playUrl.isNullOrBlank() && onPlay != null) {
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPlay) { Text("播放") }
    }

    // 选集 / 图集 / 曲目：分组渲染。**形态跟着 kind 走**——
    // 视频是格子（一眼看集号与状态标），音频是竖排列表（曲目要看标题与时长），
    // 图片是缩略图网格（看画面本身）。多组时先出一排 chip 切换（视频的"线路"就在这里）。
    UmpTrackSections(
        groups = detail?.trackGroups.orEmpty().filter { it.tracks.isNotEmpty() },
        onSelect = onSelectTrack,
    )
}

/**
 * 简介。超过 [expandLines] 行才出现「展开全文」——没被截断的短简介不该多一个点了没反应的按钮。
 *
 * 用 `onTextLayout` 的 `hasVisualOverflow` 判断，而不是按字符数估算：
 * 中文与英文的换行宽度差一倍，估出来必然一半误判。
 */
@Composable
private fun DescriptionBlock(text: String, expandLines: Int) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        maxLines = if (expanded) Int.MAX_VALUE else expandLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow },
    )
    if (overflow || expanded) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(
                text = if (expanded) "收起" else "展开全文",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * 条目区。**形态跟着 group 的 kind 走**，不是跟着整个条目走——
 * 一部剧里挂一轨音频（花絮播客）时，两组能各自用对的形态。
 *
 * 多组时先出一排 chip；视频的"线路"就是这里的多个 group，切 chip 只切视图、不重取数。
 */
@Composable
fun UmpTrackSections(groups: List<TrackGroup>, onSelect: (Track) -> Unit) {
    if (groups.isEmpty()) return
    var selected by remember(groups) { mutableIntStateOf(0) }
    val group = groups[selected.coerceIn(0, groups.lastIndex)]

    Spacer(Modifier.height(20.dp))

    if (groups.size > 1) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEachIndexed { i, g ->
                val active = i == selected
                Surface(
                    onClick = { selected = i },
                    shape = RoundedCornerShape(999.dp),
                    color = if (active) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = if (active) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                ) {
                    Text(
                        text = "${g.name} ${g.tracks.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    Text(
        text = "${group.name} · 共 ${group.tracks.size} 项",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
    )
    Spacer(Modifier.height(10.dp))

    when (group.kind) {
        MediaKind.AUDIO -> AudioList(group, onSelect)
        // 图集：页数多时**不铺缩略图网格**。原因很实在——`Track.url` 是整张原图（e-hentai 一页 300~400KB），
        // 摊 60 格等于进详情页就下 20MB，而且用户的目标是"开始读"，不是"逐格点"。
        // 所以超过 IMAGE_GRID_MAX 页就给一个明确的入口按钮；页数少（图集/壁纸）仍铺网格。
        MediaKind.IMAGE -> if (group.tracks.size > IMAGE_GRID_MAX) {
            ImageEntryBanner(group, onSelect)
        } else {
            ImageGrid(group, onSelect)
        }
        MediaKind.VIDEO -> VideoGrid(group, onSelect)
    }
}

/** 缩略图网格的上限：超过它就只想"开始阅读"了 */
private const val IMAGE_GRID_MAX = 12

/**
 * 图集入口条。一行大字 + 一颗按钮，点进去从第 1 张开始（`trackIndex = 0`）。
 *
 * 为什么必须有它：图片条目此前只能靠网格里某一格进入查看器，页数一多网格就不会渲染，
 * 用户看到"933 页"却无处可点 —— 这是实测反馈的原话。
 */
@Composable
private fun ImageEntryBanner(group: TrackGroup, onSelect: (Track) -> Unit) {
    val first = group.tracks.firstOrNull() ?: return
    Surface(
        onClick = { onSelect(first) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "开始阅读",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "共 ${group.tracks.size} 页 · 上下滑动翻阅",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = "▸",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 视频选集网格。一格一个条目，格内两行：集号 + 状态标。
 *
 * 为什么用 FlowRow 而不是固定列数网格：腾讯一部的"集"里混着正片、预告、花絮，
 * 数量从几集到上百集不等；等宽的固定网格会把"预告片_06"这类长标题截成看不清，
 * 而 FlowRow 让每格按内容自适应、又保持整齐的左对齐。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoGrid(group: TrackGroup, onSelect: (Track) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        group.tracks.forEach { track ->
            val badge = badgeOf(track)
            Surface(
                onClick = { onSelect(track) },
                shape = RoundedCornerShape(8.dp),
                color = if (track.state == "vip") {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = episodeLabel(track),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (track.state == "vip") {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = badge.second,
                        style = MaterialTheme.typography.labelSmall,
                        color = badge.first,
                    )
                }
            }
        }
    }
}

/**
 * 音频播放列表。横排格子在这里没用：曲名要看清、时长要能扫一眼，
 * 所以是"序号 + 标题 + 时长"的竖排行，整行可点。
 */
@Composable
private fun AudioList(group: TrackGroup, onSelect: (Track) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        group.tracks.forEach { track ->
            Surface(
                onClick = { onSelect(track) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = track.index.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.width(30.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        track.durationMs?.let {
                            Text(
                                text = formatDuration(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = "▸",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * 图集网格。缩略图 + 标题，整格可点 → 图片查看器（M4.6）。
 *
 * 点开带的是"第几张"（`track.index - 1`），查看器据此定位起始页，用户不必从头翻。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageGrid(group: TrackGroup, onSelect: (Track) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        group.tracks.forEach { track ->
            Column(
                modifier = Modifier
                    .width(112.dp)
                    .clickable { onSelect(track) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    val url = track.thumb ?: track.url
                    if (url.isNotBlank()) {
                        UmpAsyncImage(
                            model = url,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * 格子里那行字。腾讯的标题长这样：`走马上任_07`、`走马上任_预告07`、`彩蛋1`。
 * 所以这里不看前后缀，只取标题里第一段数字，再按 state 加前缀——
 * "第7集" / "预告7" / "彩蛋1"，比原样贴一整串标题清楚得多。
 */
private val EPISODE_NO = Regex("""第(\d{1,3})(集|期|话|回|季)""")

/** "第1期"后面紧跟的修饰词：`上纯享` / `下` / `加更` / `花絮特辑` */
private val EPISODE_TAG = Regex("""^(上|下)?(纯享|加更|花絮|特辑)?""")

/** 没有集号但有片子类型：综艺的"先导片上/下/纯享" */
private val LEAD_IN = Regex("""先导片(上|下)?(纯享)?""")

/**
 * 格子主标签。**必须能区分同一期的多条视频**——实测"现在就出发4"一期挂 3~4 条
 * （上/下/纯享/加更），只抽第一个数字会得到 9 个一模一样的"第1集"，用户根本点不准。
 *
 * 顺序：`第N期(·上纯享)` → `先导片(·下)` → `预告N / 花絮N` → 序号兜底。
 */
private fun episodeLabel(track: Track): String {
    val title = track.title

    EPISODE_NO.find(title)?.let { m ->
        val num = m.groupValues[1].trimStart('0').ifBlank { "0" }
        val unit = m.groupValues[2]
        val after = title.substring(m.range.last + 1)
        val tag = EPISODE_TAG.find(after)
            ?.let { (it.groupValues[1] + it.groupValues[2]).takeIf { s -> s.isNotBlank() } }
        return "第$num$unit" + (tag?.let { "·$it" } ?: "")
    }

    LEAD_IN.find(title)?.let { m ->
        val tail = (m.groupValues[1] + m.groupValues[2]).takeIf { it.isNotBlank() }
        return "先导片" + (tail?.let { "·$it" } ?: "")
    }

    val n = Regex("""(\d{1,3})""").find(title)?.groupValues?.get(1)?.trimStart('0')
    return when (track.state) {
        "trailer" -> if (n != null) "预告$n" else "预告${track.index}"
        "extra" -> if (n != null) "彩蛋$n" else "花絮${track.index}"
        else -> if (n != null) "第${n}集" else "${track.index}"
    }
}

/** 状态标 → (颜色, 文案)。`free` 也显式标出来，否则用户分不清"这集能看"和"没标记" */
@Composable
private fun badgeOf(track: Track): Pair<androidx.compose.ui.graphics.Color, String> = when (track.state) {
    "vip" -> MaterialTheme.colorScheme.error to "VIP"
    "trailer" -> MaterialTheme.colorScheme.tertiary to "预告"
    "extra" -> MaterialTheme.colorScheme.tertiary to "花絮"
    "free" -> MaterialTheme.colorScheme.primary to "免费"
    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f) to ""
}

@Composable
private fun Poster(item: MediaSummary, ratio: Float) {    val url = item.imageUrl ?: item.poster
    Box(
        modifier = Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            UmpAsyncImage(
                model = url,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
