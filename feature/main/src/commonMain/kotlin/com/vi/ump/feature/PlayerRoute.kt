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
import com.vi.ump.model.PlayTarget
import com.vi.ump.player.PlayerStatus
import com.vi.ump.player.createPlayerFacade
import com.vi.ump.render.UmpPlayer
import com.vi.ump.storage.copyTextToClipboard
import com.vi.ump.storage.createProgressStore
import com.vi.ump.storage.progressKey
import kotlinx.coroutines.delay

/**
 * 播放路由。这里是**唯一**创建播放器的地方：
 *
 * ```
 * 详情页点播放/点某集 → PlayTarget（这一集 + 全部分组）
 *                    → createPlayerFacade()（平台 actual）
 *                    → prepare(playTarget.url, playback.headers, startMs)
 *                    → PlayerState（StateFlow）→ render.UmpPlayer
 * ```
 *
 * 设计约束：
 * 1. 播放器随页面存在而存在（`remember(summary.id)` + `DisposableEffect` 里 `release()`），
 *    **换集/切线路不重建**——重建会打断缓冲、丢掉倍速与音量，用户看到的是"闪一下重新加载"。
 * 2. `playback.headers` 来自配置（腾讯这类 CDN 校验 Referer，没有就 403）。
 * 3. 能力不足**不 prepare**：由 `UmpPlayer` 直接显示"这端放不了"的原因，而不是先黑屏再报错。
 * 4. 全屏状态由本路由持有（传给 `UmpPlayer` 的 `fullscreenState`），这样系统返回键可以先退出全屏
 *    而不是直接把播放页弹掉——`BackHandler` 是后进先出，本路由比 `AppRoot` 注册得晚，所以先拦到。
 * 5. 进度记忆（M4.7）：进入时读、每 5s 写、离开时写、播完清。换集时键随集号变，
 *    所以"第 3 集看到一半回来看第 5 集"两边互不污染。读和写都走 `ProgressStore`。
 * 6. M4.4 连播与线路：播完自动接**同组下一集**；线路切换只换地址、不换页，按集号对齐
 *    （见 `PlayTarget.switchLine`）。"是否连播"自 2026-11-16 起是**用户的全局开关**（默认关，
 *    落盘在 `SettingsKeys.PLAYER_AUTO_NEXT`），不再读配置里的 `playback.player.autoNext`。
 *    两种切换都通过 `onTargetChange` 回传给宿主，本路由自己不持有"当前是哪一集"。
 */
@Composable
fun PlayerRoute(
    session: UmpSession,
    target: PlayTarget,
    modifier: Modifier = Modifier,
    /** app 壳层注入的画面实现：`(renderToken, modifier) -> Unit`；桌面端不传，就是没有画面 */
    playerSurface: @Composable (Any?, Modifier) -> Unit = { _, _ -> },
    /** 与 `AppRoot` 同一个返回键槽位；传进来是为了在全屏时抢先拦一层 */
    backDispatcher: @Composable (enabled: Boolean, action: () -> Unit) -> Unit = { _, _ -> },
    /**
     * 全屏状态。`AppRoot` 传进来才能真正"全屏"——它要靠这个值把顶栏收起来。
     * 不传则本路由自己 `remember`（独立使用时行为不变）。
     */
    fullscreenState: MutableState<Boolean>? = null,
    /** 桌面端传 true：控制条放画面下方（heavyweight 视频面会盖住叠层），手机端保持叠层+自动隐藏 */
    controlsBelowSurface: Boolean = false,
    /** 自动连播 / 线路切换后回传新目标。宿主必须把它写回自己的导航状态，否则下一次连播会重复同一集 */
    onTargetChange: ((PlayTarget) -> Unit)? = null,
    /**
     * 连续播放（2026-11-16 改口径）：**不再是配置项，而是用户的全局开关，默认关**。
     * 状态存在 `AppRoot`（落盘 `SettingsKeys.PLAYER_AUTO_NEXT`），本路由只读它。
     */
    autoNext: Boolean = false,
    /** 用户拨动播放页那颗开关时回调；由宿主落盘，换源/换集/重启都保持 */
    onAutoNextChange: ((Boolean) -> Unit)? = null,
    /**
     * 页面滚离顶部 / 回到顶部时回调。宿主用它收起顶栏——播放页一屏放不下"画面 + 标题 + 选集"，
     * 往下滑着看选集时顶栏不该继续占着 112px（用户反馈"下滑时最上面的组件没隐藏"）。
     */
    onScrollAwayChange: ((Boolean) -> Unit)? = null,
) {
    val item = target.summary
    // 同一个条目内换集/切线路不重建播放器：key 只取 summary.id
    val facade = remember(item.id) { createPlayerFacade() }
    val state by facade.state.collectAsState()
    val capabilities = facade.capabilities

    val url = target.url
    val headers = session.schema.config.playback.headers
    // 开关可能在播放中途被拨动：`LaunchedEffect` 只在 key 变化时重启，直接捕获会拿到进入时的旧值
    val latestAutoNext by rememberUpdatedState(autoNext)

    // 换条目时全屏状态要归位，否则从上一条的全屏里"继承"进新条目
    val fs = fullscreenState ?: remember(item.id) { mutableStateOf(false) }
    backDispatcher(fs.value) { fs.value = false }

    val store = remember { createProgressStore() }
    val key = remember(session.source.id, item.id, target.episode) {
        progressKey(session.source.id, item.id, target.episode)
    }
    // 起播位置只读一次：播放中再读会跟上当前播放位置，导致 prepare 又被拉回去
    val resumeMs = remember(key) { store.position(key) ?: 0L }
    // 心跳与离场补写要用**最新**的键：换集后键会变，闭包捕获的是进入时的旧值
    val latestKey by rememberUpdatedState(key)

    LaunchedEffect(item.id, url) {
        if (!url.isNullOrBlank() && capabilities.canPlay(item.kind, url)) {
            facade.prepare(url = url, headers = headers, kind = item.kind, startMs = resumeMs)
            // 用户是点了「播放」进来的，就该直接放：只 prepare 不 play 会停在 0 帧，
            // 还得再点一次「播放」——实测就是这么卡住的
            facade.play()
        }
    }

    // 每 5s 心跳落库（`facade.state.value` 读的是最新快照，不能用下面那个 by 代理的 state）
    LaunchedEffect(key) {
        while (true) {
            delay(5_000)
            val s = facade.state.value
            if (s.positionMs > 3_000L) {
                if (s.status == PlayerStatus.ENDED) store.clear(key) else store.save(key, s.positionMs)
            }
        }
    }

    // 播完：清本集进度；自动连播只接同组下一集（正片接预告是错的，跨组必须由用户显式选）
    LaunchedEffect(state.status, url) {
        if (state.status == PlayerStatus.ENDED && !url.isNullOrBlank()) {
            store.clear(key)
            if (latestAutoNext) target.next()?.let { onTargetChange?.invoke(it) }
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            // 离开页面（返回/换条目/退 App）时补一次，避免"最后 5 秒"丢失
            val s = facade.state.value
            if (s.positionMs > 3_000L) store.save(latestKey, s.positionMs)
            facade.release()
        }
    }

    UmpPlayer(
        title = target.title,
        poster = item.imageUrl ?: item.poster,
        url = url,
        state = state,
        capabilities = capabilities,
        modifier = modifier,
        onToggle = { if (state.isPlaying) facade.pause() else facade.play() },
        onSeek = { facade.seekTo(it) },
        onSpeed = { facade.setSpeed(it) },
        seekStepMs = session.schema.config.playback.player.seekStepSec.toLong() * 1000L,
        onRetry = {
            if (!url.isNullOrBlank()) {
                facade.prepare(url = url, headers = headers, kind = item.kind, startMs = state.positionMs)
            }
        },
        surface = { m -> playerSurface(facade.renderToken, m) },
        fullscreenState = fs,
        resumedFromMs = resumeMs.takeIf { it > 0L },
        // 地址只留一个复制入口：平台剪贴板由 `core:storage` 的两端 actual 提供
        onCopyUrl = { copyTextToClipboard(it) },
        controlsBelowSurface = controlsBelowSurface,
        lines = target.lineNames,
        lineIndex = target.groupIndex,
        onPickLine = { onTargetChange?.invoke(target.switchLine(it)) },
        // 选集（M5.10）：数据就是详情页那一份（`PlayTarget.groups` 带着整组），
        // 不重新请求，点哪格就切哪格——换集只改 `trackIndex`，播放器不重建（见上面约束 1）
        episodeGroup = target.group,
        episodeIndex = target.trackIndex,
        onPickEpisode = { index -> onTargetChange?.invoke(target.copy(trackIndex = index)) },
        // 全屏控制条上的「上一集 / 下一集」（2026-11-18）：与连播同一个口径——**只切同组相邻集**，
        // 不跨组（从正片第一集往前会跳进预告的最后一集，那不是用户要的）。边界时传 null，按钮转灰。
        onPrev = if (target.hasPrev()) ({ onTargetChange?.invoke(target.prev()!!) }) else null,
        onNext = if (target.hasNext()) ({ onTargetChange?.invoke(target.next()!!) }) else null,
        // 连续播放开关：画在状态行右侧（"时长 0:33 · 播放中" 的右边），默认关
        autoNext = autoNext,
        onAutoNextChange = onAutoNextChange,
        onScrollAwayChange = onScrollAwayChange,
    )
}
