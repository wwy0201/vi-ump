package com.vi.ump.feature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vi.ump.model.LayoutType
import com.vi.ump.model.MediaDetail
import com.vi.ump.model.MediaKind
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PlayTarget
import com.vi.ump.render.UmpDetail

/**
 * 详情页取数。数据流：
 *
 * ```
 * 点击卡片 → pipeline.detail(resource, id, page.params) → MediaDetail → render.UmpDetail
 * ```
 *
 * 详情资源由配置指定（`pages[].layout.resource`），这里不硬编码任何站点。
 */
@Composable
fun DetailRoute(
    session: UmpSession,
    item: MediaSummary,
    modifier: Modifier = Modifier,
    onPlay: ((PlayTarget) -> Unit)? = null,
    /** 图片条目点开进查看器（M4.6）。视频/音频仍走 [onPlay] */
    onView: ((PlayTarget) -> Unit)? = null,
) {
    val page = remember(session, item.detailResource) {
        session.schema.pages
            .firstOrNull { it.layout.type == LayoutType.DETAIL && it.layout.resource == item.detailResource }
            ?: session.schema.pages.firstOrNull { it.layout.type == LayoutType.DETAIL }
    }
    val resourceKey = item.detailResource ?: page?.layout?.resource

    // 详情页自己没声明 params 时，复用列表页的站点级参数（channelId / vid 这类）。
    // 否则 {{p.channelId}} 会渲染成空串，请求直接打到 channel_id= 拿到 404。
    val params = remember(session, page) {
        page?.params?.takeIf { it.isNotEmpty() }
            ?: session.schema.pages
                .firstOrNull { it.layout.type != LayoutType.DETAIL }
                ?.params
                .orEmpty()
    }

    var detail by remember(item.id) { mutableStateOf<MediaDetail?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var retry by remember(item.id) { mutableStateOf(0) }

    LaunchedEffect(item.id, retry) {
        detail = null
        error = null
        if (resourceKey == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        runCatching {
            // `layout.tracks` 指向 `resources.tracks`：视频线路/曲目/图集的分组来源。
            // 不填它，详情页就只会有 `episodes` 那条通路（腾讯那种 id 表 + 逐条）的结果。
            session.pipeline.detail(resourceKey, item.id, params, page?.layout?.tracks)
        }
            .onSuccess { detail = it }
            .onFailure { error = it.message ?: "未知错误" }
        loading = false
    }

    // 播放按钮取决于"是否真拿到了播放地址"：
    // 有的源（如腾讯频道页）列表条目只有一个专辑 id，直链要等详情解析完才有，
    // 所以先看详情结果、再看列表条目，两者都没有才不给按钮（纯图文源不显示点不动的按钮）。
    val playable = remember(detail, item) {
        val fromDetail = detail?.summary?.playUrl?.takeIf { it.isNotBlank() }
        val fromItem = item.playUrl?.takeIf { it.isNotBlank() }
        if (fromDetail != null) detail?.summary else if (fromItem != null) item else null
    }

    UmpDetail(
        page = page,
        item = item,
        theme = session.schema.config.theme,
        modifier = modifier,
        detail = detail,
        loading = loading,
        error = error,
        onRetry = { retry++ },
        onPlay = playable?.let { s ->
            {
                // 主"播放"按钮一律从第一组第一条起播，但把整组一起带过去：
                // 播放页要靠它们做自动连播与线路切换
                onPlay?.invoke(PlayTarget(s, detail?.trackGroups.orEmpty()))
                Unit
            }
        },
        // 点某集：把这一集所在的分组与下标一起带上——进度记忆按 `源|条目|集号` 存，
        // 集号取 `track.index`；同时播放页要知道"下一集是谁"，所以整组一起传
        onSelectTrack = { track ->
            // 图片懒取（`episodes.lazyItems`）时 track.url 还是空的：不能因此判"点了没反应"，
            // 查看器会拿 `track.pending` 自己把直链取回来
            if (track.url.isNotBlank() || item.kind == MediaKind.IMAGE) {
                val groups = detail?.trackGroups.orEmpty()
                val gi = groups.indexOfFirst { g -> g.tracks.any { it === track } }.coerceAtLeast(0)
                val ti = groups.getOrNull(gi)?.tracks?.indexOfFirst { it === track }?.coerceAtLeast(0) ?: 0
                val target = PlayTarget(item, groups, gi, ti)
                // 图片没有"播放"，点开是查看器：同一个 PlayTarget 结构（当前 + 全组），
                // 查看器用它做"从第几张开始 + 左右翻页"
                if (item.kind == MediaKind.IMAGE) onView?.invoke(target) else onPlay?.invoke(target)
            }
        },
    )
}
