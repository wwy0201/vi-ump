package com.vi.ump.player

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 音频"常驻通知栏"的**跨层总线**（M4.5）。
 *
 * 为什么放 commonMain：注册方是 `AudioRoute`（commonMain 的 Compose 路由），
 * 消费方是 Android 壳层的 `AudioPlaybackService`（app:android），两者都够得到 commonMain，
 * 而 commonMain 够不到 androidMain —— 所以这里**只放纯 Kotlin 的回调与事件**，不含任何 Android 类型。
 *
 * 三个用途：
 * - `title` / `isPlaying`：通知栏第一层显示什么、第二层中间那颗键画"暂停"还是"播放"；
 * - `onPrev` / `onToggle` / `onNext`：通知栏三颗键按下时做什么（实现仍在路由里，因为播放列表只有路由知道）；
 * - `exitEvents` / `refreshEvents`：退出与刷新的事件计数（StateFlow 当事件总线用，收一次涨一次）。
 */
object AudioControlBus {

    /** 当前曲目名；没有音频在播时给空串 */
    var title: () -> String = { "" }

    /** 当前是否处于"在播"语义（与播放页那颗键同口径） */
    var isPlaying: () -> Boolean = { false }

    var onPrev: () -> Unit = {}
    var onToggle: () -> Unit = {}
    var onNext: () -> Unit = {}

    /** 通知栏"退出"按钮：服务自增，AppRoot 收到后退出音频页 */
    val exitEvents = MutableStateFlow(0L)

    /** 路由侧元数据/播放态变化时自增，服务收到后重发通知 */
    val refreshEvents = MutableStateFlow(0L)

    /**
     * 手机版是否允许音频后台播放（用户设置，默认开）。
     *
     * `AppRoot` 启动/切换设置时写入；Android 的 Activity 生命周期回调读它：
     * 关闭时退后台暂停共享音频实例并撤通知，开启时保持原行为。
     */
    var backgroundPlay: Boolean = true

    /**
     * 开关变化时通知壳层启停前台服务（由 Android `UmpApplication` 注入）。
     * 关：立刻撤通知；开：若音频正在播，重新拉起服务。
     * 桌面端没有服务，保持 null。
     */
    var onBackgroundPlayChanged: ((Boolean) -> Unit)? = null

    /**
     * "彻底停掉音频"（释放共享播放器 + 停前台服务 + 撤通知）。
     * 由 app 壳层注入实现（core:player 的 commonMain 不知道服务类），
     * 换源这类"必须立刻闭嘴"的动作调它；默认空实现 = 这一端没有音频服务。
     */
    var requestStop: () -> Unit = {}

    /** 退出/切换源时清干净，避免旧回调在新源里被误触发 */
    fun resetCallbacks() {
        title = { "" }
        isPlaying = { false }
        onPrev = {}
        onToggle = {}
        onNext = {}
    }
}
