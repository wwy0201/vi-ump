package com.vi.ump.player

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android 侧建 ExoPlayer 需要 Context，而 `expect fun createPlayerFacade()` 不带参数，
 * 所以由应用入口把 applicationContext 注入这里。
 *
 * 只存 **applicationContext**，不持有 Activity —— 否则旋转屏幕就泄漏一个 Activity。
 */
object AndroidPlayerHost {
    private var appContext: Context? = null
    private var lifecycleRegistered = false

    /** 当前正在播放/可能播放的视频页面。音频不进这里——它要按 M4.5 继续后台播放 */
    private val videoPlayers = mutableSetOf<Media3Player>()

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) {
            // 退后台（Home / 锁屏 / 最近任务）时只暂停视频。
            // 旋转屏幕会走 onStop 但 isChangingConfigurations=true，不能误暂停。
            if (!activity.isChangingConfigurations) {
                videoPlayers.toList().forEach { it.pauseForBackground() }
                // 手机音乐默认允许后台播放；用户关闭后，退后台要暂停并撤掉通知栏。
                if (!AudioControlBus.backgroundPlay) {
                    AudioSession.pauseShared()
                    AudioSession.requestServiceStop(appContext ?: activity.applicationContext)
                }
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        // 同一进程只注册一次；PlayerFacade 可能在测试/多入口里重复 install
        if (!lifecycleRegistered && app is Application) {
            app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
            lifecycleRegistered = true
        }
    }

    internal fun registerVideo(player: Media3Player) {
        videoPlayers.add(player)
    }

    internal fun unregisterVideo(player: Media3Player) {
        videoPlayers.remove(player)
    }

    internal fun requireContext(): Context =
        appContext ?: error("PlayerFacade 未初始化：请在 Application.onCreate 里调用 AndroidPlayerHost.install(this)")
}

internal class Media3Player : PlayerFacade {

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    override val capabilities = PlayerCapabilities(
        formats = setOf("mp4", "hls", "mkv"),
        supportsHeaders = true,
        supportsSpeed = true,
        supportsHls = true,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    /**
     * 播放器实例**延迟到 prepare 才建**：kind 是 prepare 的参数，而音频要走 `AudioSession` 共享实例。
     *
     * | kind | 归属 | `release()` 时 |
     * |---|---|---|
     * | VIDEO | 本 facade 私有 | 真 release（页面关了就不该出声） |
     * | AUDIO | `AudioSession` 共享 | **不 release**，只停心跳；由通知栏"退出"统一释放 |
     */
    private var player: ExoPlayer? = null
    private var audioShared = false

    /** 当前 facade 服务的是视频还是音频；退后台暂停只对 VIDEO 生效 */
    private var mediaKind: MediaKind = MediaKind.VIDEO

    /** 交给 app 层挂画面；prepare 之前为 null，页面在 prepare 后才读取 */
    override val renderToken: Any? get() = player

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = syncStatus()
        override fun onIsPlayingChanged(isPlaying: Boolean) = syncStatus()
        override fun onPlayerError(error: PlaybackException) {
            _state.update {
                it.copy(
                    status = PlayerStatus.ERROR,
                    error = PlayError("P${error.errorCode}", error.message ?: "播放失败"),
                )
            }
        }
    }

    private fun ensurePlayer(kind: MediaKind): ExoPlayer {
        val context = AndroidPlayerHost.requireContext()
        if (kind == MediaKind.AUDIO) {
            audioShared = true
            // 共享实例可能被"退出"释放过；引用对不上就重拿，并把监听器挪过去
            val shared = AudioSession.obtain(context)
            if (player !== shared) {
                player?.removeListener(listener)
                player = shared
                shared.addListener(listener)
            }
            return shared
        }
        if (player == null) {
            player = ExoPlayer.Builder(context).build().also { it.addListener(listener) }
        }
        return player!!
    }

    /**
     * headers 每次 prepare 都可能变（不同站点给不同 Referer），
     * 所以数据源工厂在这里重建，而不是建在构造函数里。
     */
    override fun prepare(url: String, headers: Map<String, String>, kind: MediaKind, startMs: Long) {
        mediaKind = kind
        if (kind == MediaKind.VIDEO) {
            AndroidPlayerHost.registerVideo(this)
        } else {
            AndroidPlayerHost.unregisterVideo(this)
        }
        val p = ensurePlayer(kind)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(AndroidPlayerHost.requireContext(), httpFactory)
        val mediaSource = DefaultMediaSourceFactory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(url))

        p.setMediaSource(mediaSource)
        p.prepare()
        if (startMs > 0L) p.seekTo(startMs)
        _state.update { it.copy(status = PlayerStatus.PREPARING, positionMs = startMs, error = null) }
        startTicker()
        if (kind == MediaKind.AUDIO) {
            // 归属已经挪到 AudioSession，这里拉起前台服务（通知栏两层 + MediaSession）。
            // 用户关掉「音乐后台播放」时不拉起：没有后台播放，也就不需要常驻通知。
            if (AudioControlBus.backgroundPlay) {
                AudioSession.requestServiceStart(AndroidPlayerHost.requireContext())
            }
        }
    }

    override fun play() {
        player?.play()
        // 音频服务可能在"关闭后台播放"退后台时被停掉；回到前台再点播放要把它拉回来。
        // 关闭后台播放时不拉服务（也就没有通知栏）；视频不涉及服务，audioShared=false 时不会执行。
        if (audioShared && AudioControlBus.backgroundPlay) {
            AudioSession.requestServiceStart(AndroidPlayerHost.requireContext())
        }
        syncStatus()
    }

    override fun pause() {
        player?.pause()
        syncStatus()
    }

    /**
     * 应用退到后台（Home / 锁屏 / 最近任务）时由 [AndroidPlayerHost] 调用。
     *
     * 只处理视频：音频共享 ExoPlayer 不在这里，M4.5 的后台播放与通知栏必须保持。
     * 暂停后不自动恢复——用户回到前台看到的是暂停态，要再点一次播放。
     */
    internal fun pauseForBackground() {
        if (mediaKind != MediaKind.VIDEO) return
        player?.pause()
        syncStatus()
    }

    override fun seekTo(ms: Long) {
        player?.seekTo(ms)
        _state.update { it.copy(positionMs = ms) }
    }

    override fun setSpeed(x: Float) {
        player?.setPlaybackSpeed(x)
    }

    /** 直接问 ExoPlayer：页面已销毁、心跳已停时，通知栏仍要拿到真实播放态 */
    override fun isPlayingNow(): Boolean =
        player?.let { it.playWhenReady && it.playbackState == Player.STATE_READY } ?: false

    override fun release() {
        ticker?.cancel()
        ticker = null
        AndroidPlayerHost.unregisterVideo(this)
        if (audioShared) {
            // 音频：只摘掉自己的监听器，播放器归 AudioSession，退后台/回详情继续播
            player?.removeListener(listener)
        } else {
            player?.release()
            player = null
        }
        scope.cancel()
        _state.update { it.copy(status = PlayerStatus.IDLE) }
    }

    private fun syncStatus() {
        if (player == null) return
        _state.update {
            it.copy(
                status = computeStatus(),
                durationMs = durationOf(),
                positionMs = positionOf(),
            )
        }
    }

    /**
     * 状态判定**不用 `player.isPlaying`**。
     *
     * Media3 的 `isPlaying` 会因音频焦点被抑制等情形返回 false，而时间码照旧在走
     * （模拟器上实测：位置从 00:36 涨到 00:49，`isPlaying` 仍是 false，界面于是把"正在播"显示成"就绪"，
     * 连带播放页的"3 秒自动隐藏"永远不触发）。
     * 用户看的语义是"在不在放"，所以判 `playWhenReady && STATE_READY`；
     * 暂停后才落到 `PAUSED`，还没按播放才是 `READY`。
     */
    private fun computeStatus(): PlayerStatus {
        val p = player ?: return PlayerStatus.IDLE
        return when {
            p.playerError != null -> PlayerStatus.ERROR
            p.playbackState == Player.STATE_ENDED -> PlayerStatus.ENDED
            p.playbackState == Player.STATE_BUFFERING -> PlayerStatus.PREPARING
            p.playbackState == Player.STATE_READY && p.playWhenReady -> PlayerStatus.PLAYING
            p.playbackState == Player.STATE_READY && positionOf() > 0L -> PlayerStatus.PAUSED
            p.playbackState == Player.STATE_READY -> PlayerStatus.READY
            else -> PlayerStatus.IDLE
        }
    }

    private fun durationOf(): Long = player?.duration?.takeIf { it > 0L } ?: 0L

    private fun positionOf(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L

    /** 解码出来的真实画面尺寸；`onVideoSizeChanged` 与 ticker 都会用到 */
    private fun videoSizeOf(): Pair<Int, Int> {
        val s = player?.videoSize ?: return 0 to 0
        return if (s.width > 0 && s.height > 0) s.width to s.height else 0 to 0
    }

    /** 250ms 一次足够驱动进度条，也不会把主线程拖慢；状态一起刷，免得事件漏触发后界面卡在旧态 */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val (vw, vh) = videoSizeOf()
                _state.update {
                    it.copy(
                        positionMs = positionOf(),
                        durationMs = durationOf(),
                        status = computeStatus(),
                        videoWidth = vw,
                        videoHeight = vh,
                    )
                }
                delay(250)
            }
        }
    }
}

actual fun createPlayerFacade(): PlayerFacade = Media3Player()
