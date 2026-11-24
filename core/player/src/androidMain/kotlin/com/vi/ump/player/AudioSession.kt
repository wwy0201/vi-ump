package com.vi.ump.player

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer

/**
 * 音频播放器的**持有者**（M4.5：先把归属挪出页面，再谈前台服务）。
 *
 * 之前的形态是 `AudioRoute` 的 `DisposableEffect` 里 `release()` —— 页面一走播放器就没了，
 * 前台服务根本拿不到"还在播的那个实例"。现在音频的 ExoPlayer 由本对象持有：
 *
 * - `Media3Player.prepare(kind = AUDIO)` 通过 `obtain()` 拿**同一个**实例；
 * - `Media3Player.release()` 对音频**不 release**（只停自己的心跳与协程），播放器活着，退后台/回详情都继续出声；
 * - 唯一的释放入口是 `releaseShared()`：通知栏"退出"按钮、或换源时调用。
 *
 * 服务如何被拉起不由这里决定：`serviceStarter` 由 app 壳层注入（core:player 不依赖 app:android）。
 */
object AudioSession {

    private var sharedPlayer: ExoPlayer? = null

    /** 由壳层（UmpApplication）注入：如何启动音频前台服务 */
    var serviceStarter: ((Context) -> Unit)? = null

    /** 由壳层注入：如何停止音频前台服务并撤通知（不释放共享播放器） */
    var serviceStopper: ((Context) -> Unit)? = null

    fun obtain(context: Context): ExoPlayer =
        sharedPlayer ?: ExoPlayer.Builder(context.applicationContext).build().also { sharedPlayer = it }

    fun peek(): ExoPlayer? = sharedPlayer

    fun requestServiceStart(context: Context) {
        serviceStarter?.invoke(context.applicationContext)
    }

    /** 只停服务/通知，不碰播放器：设置关闭后台播放后回到前台仍可继续用同一个实例 */
    fun requestServiceStop(context: Context) {
        serviceStopper?.invoke(context.applicationContext)
    }

    /** "后台播放"关闭时由 Activity 生命周期回调调用；仅暂停，不销毁共享实例 */
    fun pauseShared() {
        sharedPlayer?.pause()
    }

    /** 唯一真正释放音频播放器的地方 */
    fun releaseShared() {
        sharedPlayer?.release()
        sharedPlayer = null
        AudioControlBus.resetCallbacks()
    }
}
