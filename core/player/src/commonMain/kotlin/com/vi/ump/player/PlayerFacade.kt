package com.vi.ump.player

import com.vi.ump.model.MediaKind
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放契约。`commonMain` 里**不出现任何 Media3 / AVPlayer 符号**，
 * 这样新增一端时不需要动 common 代码（见 `docs/04 §8.1`）。
 *
 * 图片不进这里：图片没有"播放"语义，走 `core:ui` 的 `ImageViewer`。
 */
enum class PlayerStatus { IDLE, PREPARING, READY, PLAYING, PAUSED, ERROR, ENDED }

data class PlayError(
    /** 复用 `docs/03 §13` 的错误码；播放器自己产生的错误用 `P` 前缀 */
    val code: String,
    val message: String,
)

data class PlayerState(
    val status: PlayerStatus = PlayerStatus.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: PlayError? = null,
    /**
     * 视频原始宽高（起播后由解码器解出，`null` = 还不知道）。
     *
     * 为什么必须传到 UI：画面容器固定 16:9 时，竖屏短剧 / 4:3 老片会被上下留边，
     * 而封面图就画在视频下面，留边处会直接露出封面 —— 看起来像"背景图透出来了"。
     * 拿真实宽高把容器比例跟视频对齐，才是根治（留边改由视频自己决定）。
     */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
) {
    val isPlaying: Boolean get() = status == PlayerStatus.PLAYING

    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** 视频宽高比（宽/高）；未知时为 `null`，由 UI 回落到 16:9 */
    val videoAspect: Float?
        get() = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight.toFloat() else null
}

/**
 * 能力自描述：**UI 必须在播放之前就判定"这端能不能放"**。
 * 先放下去再黑屏，用户只会认为是 App 坏了；提前判定 + 明确提示才是可用的多端体验。
 */
data class PlayerCapabilities(
    /** "mp4" / "hls" / "mkv" / "flv"，空集合表示"这端还不能播放" */
    val formats: Set<String> = emptySet(),
    val supportsHeaders: Boolean = false,
    val supportsSpeed: Boolean = false,
    val supportsHls: Boolean = false,
    /** 这一端为何不能播放（`formats` 为空时 UI 直接显示这句话） */
    val unavailableReason: String? = null,
) {
    val canPlayAnything: Boolean get() = formats.isNotEmpty()

    fun canPlay(kind: MediaKind, url: String?): Boolean {
        if (kind == MediaKind.IMAGE) return true // 图片走 ImageViewer，与播放器无关
        if (!canPlayAnything) return false
        val path = url?.substringBefore('?')?.lowercase() ?: return canPlayAnything
        return formats.any { format ->
            when (format) {
                "hls" -> path.endsWith(".m3u8")
                else -> path.endsWith(".$format")
            }
        } || canPlayAnything
    }
}

interface PlayerFacade {
    val state: StateFlow<PlayerState>
    val capabilities: PlayerCapabilities

    /**
     * 画面句柄（**不透明类型**，`Any?`）：
     * Android 返回 ExoPlayer 实例，由 app 层用 `AndroidView + PlayerView` 挂到 Compose 上；
     * 桌面端返回 null（M7 接 VLCJ 之前没画面）。
     *
     * 为什么不做成 `@Composable` 的 `PlayerSurface`：那需要 `core:player` 引入 compose 插件与 UI 依赖，
     * 而"画面怎么摆"本来就属于 app 壳层。common 侧只交出句柄，UI 侧决定挂哪儿。
     */
    val renderToken: Any? get() = null

    fun prepare(
        url: String,
        headers: Map<String, String> = emptyMap(),
        kind: MediaKind = MediaKind.VIDEO,
        startMs: Long = 0L,
    )

    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setSpeed(x: Float)
    fun release()

    /**
     * **实时的**"在不在播"，给离开页面之后仍存活的使用方（音频通知栏）用。
     *
     * `state` 是快照流：页面销毁时心跳与监听器都停了，快照就冻在最后一刻；
     * 通知栏画"暂停"还是"播放"必须问播放器本身，不能信冻结的快照。
     */
    fun isPlayingNow(): Boolean = state.value.isPlaying
}

/** 平台提供实现；Android 用 Media3，桌面端在 M7 接 VLCJ 之前只能给占位 */
expect fun createPlayerFacade(): PlayerFacade
