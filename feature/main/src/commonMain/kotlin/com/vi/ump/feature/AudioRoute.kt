package com.vi.ump.feature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.vi.ump.model.PlayTarget
import com.vi.ump.player.AudioControlBus
import com.vi.ump.player.PlayerStatus
import com.vi.ump.player.createPlayerFacade
import com.vi.ump.render.AudioPlayerView
import com.vi.ump.storage.createProgressStore
import com.vi.ump.storage.progressKey
import kotlinx.coroutines.delay

/**
 * 音频路由（M4.5）。与 `PlayerRoute` 的分工：
 *
 * | | 视频 | 音频 |
 * |---|---|---|
 * | 页面 | `UmpPlayer`（画面 + 叠层控件 + 全屏） | `AudioPlayerView`（封面 + 常驻控制区 + 播放列表） |
 * | 进度键 | `源\|条目\|集号` | 同构，但"集号"这里= 曲目在组内的序号（每首歌各自续播） |
 * | 播完 | 同组下一集（连续剧语义，受全局「连续播放」开关控制，默认关） | 同组下一首；最后一首播完就清进度停下 |
 * | 后台 | 随页面暂停 | 播放器在，退到后台继续出声（Android 侧 `AudioPlaybackService` 前台化） |
 *
 * 复用 `PlayerFacade` 契约，不新建播放器抽象：音频与视频在"准备一个 URL、播、暂停、seek、倍速"
 * 这五件事上完全同构，差异只在 UI 与后台策略。
 */
@Composable
fun AudioRoute(
    session: UmpSession,
    target: PlayTarget,
    modifier: Modifier = Modifier,
    onTargetChange: ((PlayTarget) -> Unit)? = null,
    showCover: Boolean = true,
    showPlaylist: Boolean = true,
    /**
     * 连续播放（与视频页**同一个**全局开关，默认关）：一首播完接下一首。
     * 开关本身画在视频播放页的状态行上，音频页只读取同一个值——"全局有效"就靠这一处。
     */
    autoNext: Boolean = false,
) {
    val item = target.summary
    // 同一个条目内切歌不重建播放器：key 只取 summary.id（重建会打断缓冲）
    val facade = remember(item.id) { createPlayerFacade() }
    val state by facade.state.collectAsState()

    val url = target.url
    val headers = session.schema.config.playback.headers
    // 开关可能在播放中途被拨动，effect 里要读最新值而不是进入时的快照
    val latestAutoNext by rememberUpdatedState(autoNext)

    val tracks = target.group?.tracks.orEmpty()
    val indexInGroup = target.trackIndex

    val store = remember { createProgressStore() }
    val key = remember(session.source.id, item.id, target.episode) {
        progressKey(session.source.id, item.id, target.episode)
    }
    val resumeMs = remember(key) { store.position(key) ?: 0L }
    val latestKey by rememberUpdatedState(key)

    LaunchedEffect(item.id, url) {
        if (!url.isNullOrBlank()) {
            facade.prepare(url = url, headers = headers, kind = item.kind, startMs = resumeMs)
            facade.play()
        }
    }

    // ── 常驻通知栏（M4.5）──────────────────────────────────────────────
    // 回调只认"纯 Kotlin + 无参数"，服务在 app 壳层、播放列表在这里，两边靠 AudioControlBus 接上。
    // 用 rememberUpdatedState 包一层：切歌后回调里读到的是最新的 target，而不是注册那一刻的旧值
    val latestTarget by rememberUpdatedState(target)
    val latestOnTargetChange by rememberUpdatedState(onTargetChange)
    LaunchedEffect(item.id) {
        AudioControlBus.title = { latestTarget.title }
        // 读实时播放态：离开页面后 facade.state 快照会冻结，通知栏的暂停/播放图标不能信它
        AudioControlBus.isPlaying = { facade.isPlayingNow() }
        AudioControlBus.onToggle = { if (facade.isPlayingNow()) facade.pause() else facade.play() }
        AudioControlBus.onPrev = {
            val t = latestTarget
            if (t.trackIndex > 0) latestOnTargetChange?.invoke(t.copy(trackIndex = t.trackIndex - 1))
        }
        AudioControlBus.onNext = {
            latestTarget.next()?.let { next -> latestOnTargetChange?.invoke(next) }
        }
    }
    // 标题或播放态一变就让服务重发通知（服务里 collect refreshEvents）
    LaunchedEffect(target.title, state.isPlaying) {
        AudioControlBus.refreshEvents.value += 1
    }

    // 心跳落库：音频常常整首听完，5s 粒度与视频一致
    LaunchedEffect(key) {
        while (true) {
            delay(5_000)
            val s = facade.state.value
            if (s.positionMs > 3_000L) {
                if (s.status == PlayerStatus.ENDED) store.clear(key) else store.save(key, s.positionMs)
            }
        }
    }

    // 播完：一首结束就接下一首（顺序播放）。最后一首不再往前挤，清掉进度自然停下
    LaunchedEffect(state.status, url) {
        if (state.status == PlayerStatus.ENDED && !url.isNullOrBlank()) {
            store.clear(key)
            if (latestAutoNext && target.hasNext()) {
                target.next()?.let { onTargetChange?.invoke(it) }
            }
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            val s = facade.state.value
            if (s.positionMs > 3_000L) store.save(latestKey, s.positionMs)
            // 音频不在这里释放播放器：实例归 `AudioSession`（Android 侧），
            // 离开页面/退后台继续播，唯一释放入口是通知栏"退出"或换源。
            // facade.release() 对音频只停心跳与监听器，不碰 ExoPlayer 本身
            facade.release()
        }
    }

    AudioPlayerView(
        title = target.title,
        subtitle = item.subtitle ?: "",
        coverUrl = target.track?.thumb ?: item.imageUrl ?: item.poster,
        tracks = tracks,
        currentIndex = indexInGroup,
        state = state,
        seekStepMs = session.schema.config.playback.player.seekStepSec.toLong() * 1000L,
        onSeek = { facade.seekTo(it) },
        onTogglePlay = { if (state.isPlaying) facade.pause() else facade.play() },
        onSpeed = { facade.setSpeed(it) },
        onPickTrack = { i -> onTargetChange?.invoke(target.copy(trackIndex = i)) },
        modifier = modifier,
        showPlaylist = showPlaylist,
        showCover = showCover,
    )
}
