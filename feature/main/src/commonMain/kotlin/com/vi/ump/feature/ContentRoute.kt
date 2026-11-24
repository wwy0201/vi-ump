package com.vi.ump.feature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vi.ump.model.LayoutType
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PlayTarget
import com.vi.ump.model.Track
import com.vi.ump.player.PlayerStatus
import com.vi.ump.player.createPlayerFacade
import com.vi.ump.render.UmpContentInfo
import com.vi.ump.render.UmpPlayer
import com.vi.ump.storage.copyTextToClipboard
import com.vi.ump.storage.createProgressStore
import com.vi.ump.storage.progressKey
import kotlinx.coroutines.delay

/**
 * 合并后的视频内容页：**详情 + 播放器在同一个页面**。
 *
 * ```
 * 点卡片 → pipeline.detail() → 封面/简介/选集
 *        → 点封面播放键或某一集 → PlayTarget → prepare/play
 * ```
 *
 * 关键口径（2026-11-20 用户确认）：
 * 1. 默认**不自动播放**：先停在封面与选集，用户点播放或点某一集才起播；
 *    `autoPlay = true` 时才在详情返回后自动从第一组第一条起播。
 * 2. 返回不再经过"播放页 → 详情页"两层，直接回栏目。
 * 3. 同一个条目内换集/切线路不重建播放器，进度键仍按 `源|条目|集号` 走。
 * 4. 简介、演职员、选集由 [UmpContentInfo] 统一渲染，播放器只负责画面与控件。
 *
 * 音频/图片仍走原来的详情页分支：音频有自己的播放列表页，图片看完即阅读器。
 */
@Composable
fun ContentRoute(
    session: UmpSession,
    item: MediaSummary,
    modifier: Modifier = Modifier,
    /** 从历史打开时预选的集；只预选，不因它自动起播 */
    initialTarget: PlayTarget? = null,
    playerSurface: @Composable (Any?, Modifier) -> Unit = { _, _ -> },
    backDispatcher: @Composable (enabled: Boolean, action: () -> Unit) -> Unit = { _, _ -> },
    fullscreenState: MutableState<Boolean>? = null,
    controlsBelowSurface: Boolean = false,
    /** 全局设置：进入内容页是否自动从第一集起播；默认关 */
    autoPlay: Boolean = false,
    autoNext: Boolean = false,
    onAutoNextChange: ((Boolean) -> Unit)? = null,
    onScrollAwayChange: ((Boolean) -> Unit)? = null,
    /** 真正起播时回调，供宿主写观看历史；预选但没播不算 */
    onWatch: (PlayTarget) -> Unit = {},
) {
    val page = remember(session, item.detailResource) {
        session.schema.pages
            .firstOrNull { it.layout.type == LayoutType.DETAIL && it.layout.resource == item.detailResource }
            ?: session.schema.pages.firstOrNull { it.layout.type == LayoutType.DETAIL }
    }
    // 条目自带详情资源（Bilibili 电影/电视剧走 PGC 赛季，普通视频走 UGC）时以条目为准
    val resourceKey = item.detailResource ?: page?.layout?.resource

    // 详情页没声明 params 时复用列表页的站点级参数（channelId / vid 这类）。
    val params = remember(session, page) {
        page?.params?.takeIf { it.isNotEmpty() }
            ?: session.schema.pages
                .firstOrNull { it.layout.type != LayoutType.DETAIL }
                ?.params
                .orEmpty()
    }

    var detail by remember(item.id) { mutableStateOf<com.vi.ump.model.MediaDetail?>(null) }
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
            session.pipeline.detail(resourceKey, item.id, params, page?.layout?.tracks)
        }
            .onSuccess { detail = it }
            .onFailure { error = it.message ?: "未知错误" }
        loading = false
    }

    // 详情返回的 summary 可能带播放地址（列表条目没有）；标题仍用用户点进来的那一条。
    val playableSummary = if (item.playUrl.isNullOrBlank() && !detail?.summary?.playUrl.isNullOrBlank()) {
        item.copy(playUrl = detail?.summary?.playUrl)
    } else {
        item
    }

    val groups = detail?.trackGroups.orEmpty().filter { it.tracks.isNotEmpty() }

    // 预选集（历史打开）与"是否真的起播"分开：预选不违背默认不自动播。
    var selectedTarget by remember(item.id) { mutableStateOf(initialTarget) }
    var started by remember(item.id) { mutableStateOf(false) }

    fun targetForTrack(track: Track): PlayTarget {
        val gi = groups.indexOfFirst { g -> g.tracks.any { it === track } }.coerceAtLeast(0)
        val ti = groups.getOrNull(gi)?.tracks?.indexOfFirst { it === track }?.coerceAtLeast(0) ?: 0
        return PlayTarget(playableSummary, groups, gi, ti)
    }

    fun firstTarget(): PlayTarget? {
        groups.firstOrNull { it.tracks.any { t -> t.url.isNotBlank() } }?.let { group ->
            val gi = groups.indexOf(group)
            val ti = group.tracks.indexOfFirst { it.url.isNotBlank() }.coerceAtLeast(0)
            return PlayTarget(playableSummary, groups, gi, ti)
        }
        return if (!playableSummary.playUrl.isNullOrBlank()) PlayTarget(playableSummary, groups) else null
    }

    val start: () -> Unit = {
        val t = selectedTarget ?: firstTarget()
        if (t != null) {
            selectedTarget = t
            started = true
            onWatch(t)
        }
    }

    // 自动播放：等详情回来后再选第一条；历史预选的那一条优先。
    LaunchedEffect(item.id, detail, autoPlay) {
        if (autoPlay && !started && (detail != null || !playableSummary.playUrl.isNullOrBlank())) {
            val t = selectedTarget ?: firstTarget()
            if (t != null) {
                selectedTarget = t
                started = true
                onWatch(t)
            }
        }
    }

    val facade = remember(item.id) { createPlayerFacade() }
    val state by facade.state.collectAsState()
    val capabilities = facade.capabilities

    val target = selectedTarget
    val url = if (started) target?.url else null
    val headers = session.schema.config.playback.headers
    val latestAutoNext by rememberUpdatedState(autoNext)

    val fs = fullscreenState ?: remember(item.id) { mutableStateOf(false) }
    backDispatcher(fs.value) { fs.value = false }

    val store = remember { createProgressStore() }
    val key = target?.let { progressKey(session.source.id, item.id, it.episode) }
    val resumeMs = remember(key) { key?.let { store.position(it) } ?: 0L }
    val latestKey by rememberUpdatedState(key)

    LaunchedEffect(item.id, started, target?.groupIndex, target?.trackIndex, url) {
        if (started && !url.isNullOrBlank() && capabilities.canPlay(item.kind, url)) {
            facade.prepare(url = url, headers = headers, kind = item.kind, startMs = resumeMs)
            facade.play()
        }
    }

    // 每 5s 心跳落库；未起播不写。
    LaunchedEffect(key, started) {
        if (!started || key == null) return@LaunchedEffect
        while (true) {
            delay(5_000)
            val s = facade.state.value
            if (s.positionMs > 3_000L) {
                if (s.status == PlayerStatus.ENDED) store.clear(key) else store.save(key, s.positionMs)
            }
        }
    }

    // 播完：清本集进度；连播只接同组下一集。
    LaunchedEffect(state.status, url) {
        val current = target ?: return@LaunchedEffect
        if (state.status == PlayerStatus.ENDED && !current.url.isNullOrBlank()) {
            key?.let(store::clear)
            if (latestAutoNext) {
                current.next()?.let {
                    selectedTarget = it
                    started = true
                    onWatch(it)
                }
            }
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            val s = facade.state.value
            val k = latestKey
            if (k != null && s.positionMs > 3_000L) store.save(k, s.positionMs)
            facade.release()
        }
    }

    LaunchedEffect(item.id) { fs.value = false }

    UmpPlayer(
        title = target?.title ?: item.title,
        poster = item.imageUrl ?: item.poster,
        url = url,
        state = state,
        capabilities = capabilities,
        modifier = modifier,
        onToggle = {
            if (!started) {
                start()
            } else if (state.isPlaying) {
                facade.pause()
            } else {
                facade.play()
            }
        },
        onSeek = { facade.seekTo(it) },
        onSpeed = { facade.setSpeed(it) },
        seekStepMs = session.schema.config.playback.player.seekStepSec.toLong() * 1000L,
        onRetry = {
            if (started && !url.isNullOrBlank()) {
                facade.prepare(url = url, headers = headers, kind = item.kind, startMs = state.positionMs)
                facade.play()
            }
        },
        surface = { m -> playerSurface(facade.renderToken, m) },
        fullscreenState = fs,
        resumedFromMs = resumeMs.takeIf { it > 0L },
        onCopyUrl = { copyTextToClipboard(it) },
        controlsBelowSurface = controlsBelowSurface,
        lines = target?.lineNames.orEmpty(),
        lineIndex = target?.groupIndex ?: 0,
        onPickLine = { index ->
            target?.let { selectedTarget = it.switchLine(index) }
        },
        // 合并页的选集放在 info 里统一画，播放器不再重复一套网格
        episodeGroup = null,
        onPrev = target?.takeIf { it.hasPrev() }?.let { ({ selectedTarget = it.prev()!! }) },
        onNext = target?.takeIf { it.hasNext() }?.let { ({ selectedTarget = it.next()!! }) },
        autoNext = autoNext,
        onAutoNextChange = if (started && target != null) onAutoNextChange else null,
        onScrollAwayChange = if (started && target != null) onScrollAwayChange else null,
        onStart = if (!started && (selectedTarget != null || detail != null || !playableSummary.playUrl.isNullOrBlank())) {
            start
        } else {
            null
        },
        info = {
            UmpContentInfo(
                item = item,
                page = page,
                detail = detail,
                loading = loading,
                error = error,
                onRetry = { retry++ },
                onSelectTrack = { track ->
                    val next = targetForTrack(track)
                    selectedTarget = next
                    started = true
                    onWatch(next)
                },
            )
        },
    )
}
