package com.vi.ump.player

import com.vi.ump.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.awt.Canvas

/**
 * 桌面端真实播放器（M7）：**vlcj 驱动本机已装的 VLC**。
 *
 * 为什么不是自己解封装：
 *  - 桌面端只有 Windows 这一个目标，而 VLC 是绝大多数机器上已有的播放器；
 *  - Media3/ExoPlayer 没有桌面版；JavaFX Media 对 m3u8 / mkv 支持很差；
 *  - vlcj 只是 libvlc 的 JNA 封装，解封装、解码、字幕、倍速全部由 VLC 负责，行为与"用 VLC 打开这个 URL"一致。
 *
 * 代价（写进 docs/04）：
 *  - 运行期依赖本机 VLC（找不到就回退 [UnsupportedPlayer]，播放前就提示，不黑屏）；
 *  - 画面是 heavyweight 的 AWT `Canvas`，由壳层用 Compose 的 `SwingPanel` 挂载，
 *    它**盖不住** Compose 画的控件层 —— 反过来，控件层也盖不住它（见 docs/04 的叠层说明）。
 */
private class VlcPlayerFacade(
    private val factory: MediaPlayerFactory,
    private val canvas: Canvas,
) : PlayerFacade {

    private val mediaPlayer: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    /**
     * 能力如实声明：VLC 通过 media option 支持 Referer / User-Agent 两枚最常用的防盗链头，
     * 因此声明 supportsHeaders=true。腾讯只用 Referer，Bilibili 还要桌面 UA；
     * 其他自定义头暂不映射（VLC 没有通用 `:http-header` 选项，遇到再扩）。
     */
    override val capabilities = PlayerCapabilities(
        formats = setOf("mp4", "mkv", "flv", "ts", "hls"),
        supportsHeaders = true,
        supportsSpeed = true,
        supportsHls = true,
    )

    /** 画面句柄 = AWT Canvas；`app:desktop` 用 `SwingPanel(factory = { canvas })` 挂上去 */
    override val renderToken: Any? get() = canvas

    /** prepare 时记下续播点，等真正起播后再 seek（起播前 seek 会被 VLC 丢掉） */
    @Volatile
    private var pendingStartMs = 0L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var ticker: Job? = null

    /**
     * 250ms 轮询一次（与 Android 端同一节奏），**在独立协程里读 libvlc**。
     *
     * 为什么不用事件回调：vlcj 的事件回调在 libvlc 的事件线程上执行，
     * 在该线程里调用 libvlc API（尤其 `video().videoDimension()`）会阻塞事件线程，
     * 表现就是本项目实测到的"点播放后永远卡在「准备中…」、画面全黑"。
     * 这里顺带把续播 seek 也放到安全上下文执行。
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                runCatching {
                    val cur = _state.value
                    val vlcState = mediaPlayer.status().state()
                    val len = mediaPlayer.status().length().coerceAtLeast(0L)
                    val t = mediaPlayer.status().time().coerceAtLeast(0L)
                    val live = vlcState == State.PLAYING || vlcState == State.PAUSED

                    val status = when {
                        vlcState == State.PLAYING -> PlayerStatus.PLAYING
                        vlcState == State.PAUSED -> PlayerStatus.PAUSED
                        vlcState.name == "ENDED" -> PlayerStatus.ENDED
                        else -> cur.status
                    }

                    // 续播点：等真有画面再 seek
                    if (live && pendingStartMs > 0L) {
                        mediaPlayer.controls().setTime(pendingStartMs)
                        pendingStartMs = 0L
                    }

                    // 画面真实尺寸：起播后才有，用来让容器比例贴合视频
                    val dim = if (live) mediaPlayer.video().videoDimension() else null

                    _state.value = cur.copy(
                        status = status,
                        positionMs = if (t > 0L) t else cur.positionMs,
                        durationMs = if (len > 0L) len else cur.durationMs,
                        videoWidth = dim?.width ?: cur.videoWidth,
                        videoHeight = dim?.height ?: cur.videoHeight,
                    )
                }
                delay(250)
            }
        }
    }

    init {
        mediaPlayer.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas))
        startTicker()
        mediaPlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {

            override fun opening(mediaPlayer: MediaPlayer) {
                _state.value = _state.value.copy(status = PlayerStatus.PREPARING, error = null)
            }

            override fun playing(mediaPlayer: MediaPlayer) {
                // ⚠️ 这里只做纯状态标记，**不调用任何 libvlc API**。
                // 这个回调跑在 libvlc 的事件线程上，在里面读 vout 尺寸 / length 会卡住事件线程，
                // UI 就永远停在「准备中…」（实测踩过：加了 videoDimension() 之后续播直接黑屏卡死）。
                // 所有读数与 seek 都交给下面的 ticker，在一个独立的协程里做。
                _state.value = _state.value.copy(status = PlayerStatus.PLAYING, error = null)
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                _state.value = _state.value.copy(status = PlayerStatus.PAUSED)
            }

            override fun stopped(mediaPlayer: MediaPlayer) {
                _state.value = _state.value.copy(status = PlayerStatus.PAUSED)
            }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                // 同样只做状态更新：`newTime` 是事件给的值，读我们自己人算，不去调 libvlc
                _state.value = _state.value.copy(positionMs = newTime)
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                _state.value = _state.value.copy(durationMs = newLength)
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                _state.value = _state.value.copy(status = PlayerStatus.ENDED)
            }

            override fun error(mediaPlayer: MediaPlayer) {
                // libvlc 的错误事件不给错误码，只能给一个统一的 P 码 + 人话
                _state.value = _state.value.copy(
                    status = PlayerStatus.ERROR,
                    error = PlayError("P2007", "VLC 无法播放这个地址（可能是地址过期或需要登录态）"),
                )
            }
        })
    }

    override fun prepare(url: String, headers: Map<String, String>, kind: MediaKind, startMs: Long) {
        pendingStartMs = startMs
        _state.value = PlayerState(status = PlayerStatus.PREPARING)

        // VLC 的防盗链请求头要作为 media option 传入（`:http-referrer=` / `:http-user-agent=`）。
        // 之前这里直接 prepare(url) 丢掉 headers，腾讯/Bilibili 的 CDN 会 403，桌面端表现为
        // "准备中 → 黑屏/报 P2007"。键名大小写不敏感，配置里写 Referer / User-Agent 均可。
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        val mediaOptions = buildList {
            referer?.takeIf { it.isNotBlank() }?.let { add(":http-referrer=$it") }
            userAgent?.takeIf { it.isNotBlank() }?.let { add(":http-user-agent=$it") }
        }
        mediaPlayer.media().prepare(url, *mediaOptions.toTypedArray())
    }

    override fun play() {
        // vlcj 的 play() 在 prepare 之后调用；若已 ENDED/ERROR 需要重新起播由上层重新 prepare
        mediaPlayer.controls().play()
    }

    override fun pause() {
        mediaPlayer.controls().pause()
    }

    override fun seekTo(ms: Long) {
        mediaPlayer.controls().setTime(ms.coerceAtLeast(0L))
        _state.value = _state.value.copy(positionMs = ms)
    }

    override fun setSpeed(x: Float) {
        // VLC 的 rate 就是倍速；不支持 0 或负值
        if (x > 0f) mediaPlayer.controls().setRate(x)
    }

    override fun release() {
        ticker?.cancel()
        scope.cancel()
        runCatching { mediaPlayer.controls().stop() }
        runCatching { mediaPlayer.release() }
        runCatching { factory.release() }
    }
}

/**
 * 桌面端的兜底实现：**不是空壳**。`formats` 为空 + `unavailableReason` 有值，
 * UI 因此能在播放前就判定"这端放不了"并显示明确文案，而不是黑屏或按钮点了没反应。
 */
private class UnsupportedPlayer(private val reason: String) : PlayerFacade {

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    override val capabilities = PlayerCapabilities(unavailableReason = reason)

    override fun prepare(url: String, headers: Map<String, String>, kind: MediaKind, startMs: Long) {
        _state.value = PlayerState(status = PlayerStatus.ERROR, error = PlayError("W003", reason))
    }

    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(ms: Long) = Unit
    override fun setSpeed(x: Float) = Unit
    override fun release() = Unit
}

/**
 * 创建桌面播放器。
 *
 * `NativeDiscovery().discover()` 会自动找 `C:\Program Files\VideoLAN\VLC`、`PATH`、以及 `VLC_HOME`，
 * 找到后把 `jna.library.path` 指过去 —— 所以用户机器上装了 VLC 就能直接用，不需要我们打包 libvlc。
 * 找不到时不抛异常：回退占位实现，让 UI 明确提示"装个 VLC"。
 */
actual fun createPlayerFacade(): PlayerFacade {
    val discovery = NativeDiscovery()
    val found = runCatching { discovery.discover() }.getOrDefault(false)
    if (!found) {
        println("[player] native discovery failed -> unsupported")
        return UnsupportedPlayer("未找到本机 VLC（libvlc）。安装 VLC 后重启应用即可播放；当前只展示播放地址与封面")
    }
    return try {
        // --no-video-title-show：不在画面上叠 VLC 自己的标题条（我们有自己的控制层）
        val factory = MediaPlayerFactory("--no-video-title-show", "--quiet")
        println("[player] vlcj ready")
        VlcPlayerFacade(factory, Canvas().apply { background = java.awt.Color.BLACK })
    } catch (t: Throwable) {
        println("[player] vlcj init failed: ${t.message}")
        UnsupportedPlayer("桌面播放器初始化失败：${t.message ?: t::class.simpleName}")
    }
}
