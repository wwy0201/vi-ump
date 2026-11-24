package com.vi.ump.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.vi.ump.model.PlayTarget
import com.vi.ump.storage.createProgressStore
import com.vi.ump.storage.progressKey
import java.util.Locale

/**
 * TV 播放器：ExoPlayer2 + 原生 PlayerView。
 *
 * TV 明确不做后台音频、不发通知：Activity `onPause` 到这里就暂停并落进度，
 * 整个 TV 壳没有 Service / FOREGROUND_SERVICE / POST_NOTIFICATIONS。
 *
 * 控制层按电视盒子的习惯做，不走焦点导航（按钮全部 focusable=false，
 * 按键由 [KeyFrame] 在视图层级最外层统一拦截，不会再出现"用方向键一路挪到按钮"）：
 *  - 任意按键唤起控制层；播放中 5s 自动淡出，暂停时保持显示
 *  - 左右 = 快退/快进（长按按档位加速），OK = 播放/暂停，上下 = 上下集
 *  - 返回 = 控制层可见时先收起，不可见时退出播放
 *  - 底部进度条 + 当前时间/总时长，快进时给一句「+Ns · 目标时间」
 *
 * 上下集只在同一组内切换，口径与手机版 `PlayTarget` 一致，不跨组。
 */
class PlayerScreen(
    private val host: TvHost,
    private val session: TvSession,
    target: PlayTarget,
) : TvScreen {

    private val ctx = host as android.content.Context
    private val root: KeyFrame
    private val playerView: PlayerView
    private val topBar: LinearLayout
    private val title: TextView
    private val state: TextView
    private val bottomBar: LinearLayout
    private val progress: PlaybackBar
    private val timeText: TextView
    private val durText: TextView
    private val seekHint: TextView
    private val prevButton: TextView
    private val nextButton: TextView
    private val playButton: TextView

    private var player: ExoPlayer? = null
    private var current: PlayTarget = target
    private var preparedUrl: String? = null
    private var released = false
    private var controlsVisible = false
    private val store = createProgressStore()
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5000)
        }
    }

    /** 控制层可见时才画进度，隐藏时不做多余计算 */
    private val progressTicker = object : Runnable {
        override fun run() {
            if (controlsVisible) updateProgress()
            handler.postDelayed(this, 1000)
        }
    }

    private val hideControls = Runnable { setControlsVisible(false) }

    private val hideSeekHint = Runnable {
        seekHint.animate().cancel()
        seekHint.animate().alpha(0f).setDuration(180).withEndAction {
            if (!controlsVisible) seekHint.visibility = View.GONE
        }.start()
    }

    init {
        root = KeyFrame(ctx).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            onKeyEvent = { keyCode, event -> handleKey(keyCode, event) }
        }
        playerView = PlayerView(ctx).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
            // 焦点固定留在 root，避免 PlayerView / SurfaceView 抢键
            isFocusable = false
            resizeMode = when (session.schema.config.playback.player.aspect.lowercase()) {
                "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                "crop" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
        root.addView(playerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        topBar = with(TvUi) { ctx.column() }.apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(ctx.dp(26), ctx.dp(10), ctx.dp(26), ctx.dp(10))
        }
        title = with(TvUi) { ctx.tvText(target.title, 20f, TvUi.TEXT) }
        state = with(TvUi) { ctx.tvText("", 14f, TvUi.TEXT_DIM) }
        topBar.addView(title, with(TvUi) { ctx.matchWrap() })
        topBar.addView(state, with(TvUi) { ctx.matchWrap(top = 3) })
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        bottomBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(175, 0, 0, 0))
            setPadding(ctx.dp(30), ctx.dp(12), ctx.dp(30), ctx.dp(12))
        }

        val progressRow = with(TvUi) { ctx.focusRow() }
        timeText = with(TvUi) { ctx.tvText("0:00", 14f, TvUi.TEXT_DIM) }
        durText = with(TvUi) { ctx.tvText("--:--", 14f, TvUi.TEXT_DIM) }
        progress = PlaybackBar(ctx)
        progressRow.addView(timeText)
        progressRow.addView(progress, LinearLayout.LayoutParams(0, ctx.dp(6), 1f).apply {
            marginStart = ctx.dp(14)
            marginEnd = ctx.dp(14)
            gravity = Gravity.CENTER_VERTICAL
        })
        progressRow.addView(durText)
        bottomBar.addView(progressRow, with(TvUi) { ctx.matchWrap() })

        val bar = with(TvUi) { ctx.focusRow() }.apply { gravity = Gravity.CENTER }
        // 按钮只做视觉提示：不参与焦点，键全部由 handleKey 统一处理
        prevButton = with(TvUi) { ctx.tvButton("<<") { switchEpisode(-1) } }.apply { isFocusable = false }
        nextButton = with(TvUi) { ctx.tvButton(">>") { switchEpisode(1) } }.apply { isFocusable = false }
        playButton = with(TvUi) { ctx.tvButton("暂停") { togglePlay() } }.apply { isFocusable = false }
        bar.addView(prevButton, with(TvUi) { ctx.rowParams() })
        bar.addView(
            with(TvUi) { ctx.tvButton("−10s") { seek(-stepMs(0)) } }.apply { isFocusable = false },
            with(TvUi) { ctx.rowParams(8) },
        )
        bar.addView(playButton, with(TvUi) { ctx.rowParams(8) })
        bar.addView(
            with(TvUi) { ctx.tvButton("+10s") { seek(stepMs(0)) } }.apply { isFocusable = false },
            with(TvUi) { ctx.rowParams(8) },
        )
        bar.addView(nextButton, with(TvUi) { ctx.rowParams(8) })
        bottomBar.addView(bar, with(TvUi) { ctx.matchWrap(top = 10) })

        root.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))

        seekHint = with(TvUi) { ctx.tvText("", 16f, TvUi.ACCENT) }.apply {
            visibility = View.GONE
            alpha = 0f
        }
        root.addView(seekHint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = ctx.dp(140) })

        setControlsVisible(false, animate = false)
        updateButtons()
    }

    override val view: View get() = root

    override fun onShow() {
        prepare(current)
        handler.postDelayed(ticker, 5000)
        handler.postDelayed(progressTicker, 1000)
    }

    override fun requestInitialFocus() {
        // 焦点落在播放器根容器上，所有按键先经过 KeyFrame.dispatchKeyEvent
        root.requestFocus()
    }

    override fun onHide() {
        savePosition()
        release()
    }

    override fun onPause() {
        player?.pause()
        savePosition()
        playButton.text = "播放"
        setControlsVisible(true)
    }

    override fun onResume() {
        // 退后台已暂停；回来保持暂停，由用户决定继续，避免"进后台偷偷播"
        playButton.text = "播放"
        setControlsVisible(true)
    }

    override fun onBack(): Boolean {
        // 控制层可见时，返回键先收起控制层
        if (controlsVisible) {
            setControlsVisible(false)
            return true
        }
        savePosition()
        release()
        host.pop()
        return true
    }

    override fun onKey(keyCode: Int, event: KeyEvent): Boolean = handleKey(keyCode, event)

    /**
     * 电视盒子口径的按键路由。返回 true 表示已消费。
     *
     * 音量键、媒体快进/快退键一律不拦，交回系统处理。
     */
    private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    setControlsVisible(true)
                    seek(-stepMs(event.repeatCount))
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    setControlsVisible(true)
                    seek(stepMs(event.repeatCount))
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    setControlsVisible(true)
                    switchEpisode(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    setControlsVisible(true)
                    switchEpisode(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    setControlsVisible(true)
                    togglePlay()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    setControlsVisible(!controlsVisible)
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // 松手才落进度，避免长按时每一帧都写存储
                savePosition()
                return true
            }
        }
        return false
    }

    private fun prepare(target: PlayTarget) {
        val url = target.url?.takeIf { it.isNotBlank() }
        if (url == null) {
            title.text = target.title
            state.text = "没有可播放地址（这一集可能未解析）"
            return
        }
        released = false
        val old = player
        if (old != null) {
            old.release()
        }
        val headers = session.schema.config.runtime.headers + session.schema.config.playback.headers
        val factory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        val p = ExoPlayer.Builder(ctx).build()
        player = p
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton.text = if (isPlaying) "暂停" else "播放"
                // 播放中才自动收起；暂停时保持控制层可见
                handler.removeCallbacks(hideControls)
                if (isPlaying && controlsVisible) handler.postDelayed(hideControls, HIDE_DELAY_MS)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    savePosition(clear = true)
                    val auto = session.settings.getString(com.vi.ump.storage.SettingsKeys.PLAYER_AUTO_NEXT)
                        ?.let { it == "1" }
                        ?: session.schema.config.playback.player.autoNext
                    if (auto && current.hasNext()) {
                        switchEpisode(1)
                    } else {
                        playButton.text = "播放"
                        setControlsVisible(true)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                state.text = "播放失败：${error.errorCodeName}"
                setControlsVisible(true)
            }
        })
        val item = MediaItem.fromUri(url)
        p.setMediaSource(DefaultMediaSourceFactory(factory).createMediaSource(item))
        val resume = store.position(keyOf(target)) ?: 0L
        if (resume > 3000L) p.seekTo(resume)
        p.prepare()
        p.playWhenReady = true
        preparedUrl = url
        title.text = target.title
        state.text = buildString {
            append(target.summary.title)
            if (target.lineNames.size > 1) append(" · ").append(target.lineNames.getOrNull(target.groupIndex).orEmpty())
            append(" · 第 ${target.episode.coerceAtLeast(1)} 集")
        }
        playButton.text = "暂停"
        progress.fraction = 0f
        timeText.text = fmt(if (resume > 3000L) resume else 0L)
        durText.text = "--:--"
        updateButtons()
        setControlsVisible(true)
        recordHistory(target)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            savePosition()
            playButton.text = "播放"
            handler.removeCallbacks(hideControls)
        } else {
            p.play()
            playButton.text = "暂停"
            if (controlsVisible) handler.postDelayed(hideControls, HIDE_DELAY_MS)
        }
    }

    /** [deltaMs] 由调用方给出，长按时按 repeatCount 放大步长 */
    private fun seek(deltaMs: Long) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0L)
        p.seekTo(target)
        updateProgress()
        showSeekHint(deltaMs, target)
    }

    private fun showSeekHint(deltaMs: Long, target: Long) {
        val sec = deltaMs / 1000
        val sign = if (sec >= 0) "+" else ""
        seekHint.text = "$sign${sec}s · ${fmt(target)}"
        seekHint.animate().cancel()
        seekHint.visibility = View.VISIBLE
        seekHint.alpha = 1f
        handler.removeCallbacks(hideSeekHint)
        handler.postDelayed(hideSeekHint, 900)
    }

    private fun switchEpisode(delta: Int) {
        val target = if (delta < 0) current.prev() else current.next()
        if (target == null) {
            state.text = if (delta < 0) "已经是第一集" else "已经是最后一集"
            setControlsVisible(true)
            return
        }
        savePosition()
        current = target
        prepare(target)
    }

    private fun updateButtons() {
        prevButton.setTextColor(if (current.hasPrev()) TvUi.TEXT else TvUi.TEXT_DIM)
        nextButton.setTextColor(if (current.hasNext()) TvUi.TEXT else TvUi.TEXT_DIM)
    }

    private fun updateProgress() {
        val p = player ?: return
        val pos = p.currentPosition.coerceAtLeast(0L)
        val dur = p.duration
        timeText.text = fmt(pos)
        if (dur != C.TIME_UNSET && dur > 0) {
            durText.text = fmt(dur)
            progress.fraction = (pos.toFloat() / dur).coerceIn(0f, 1f)
        } else {
            durText.text = "--:--"
            progress.fraction = 0f
        }
    }

    /**
     * 控制层显隐。播放中才排自动收起；暂停/出错时保持可见。
     * [animate] 为 false 只用于构造函数里的首次布局，避免进场闪一下。
     */
    private fun setControlsVisible(show: Boolean, animate: Boolean = true) {
        controlsVisible = show
        for (v in arrayOf<View>(topBar, bottomBar)) {
            v.animate().cancel()
            if (show) {
                v.visibility = View.VISIBLE
                if (animate) v.animate().alpha(1f).setDuration(160).start() else v.alpha = 1f
            } else {
                if (animate) {
                    v.animate().alpha(0f).setDuration(160).withEndAction {
                        if (!controlsVisible) v.visibility = View.GONE
                    }.start()
                } else {
                    v.alpha = 0f
                    v.visibility = View.GONE
                }
            }
        }
        handler.removeCallbacks(hideControls)
        if (show) {
            updateProgress()
            if (player?.isPlaying == true) handler.postDelayed(hideControls, HIDE_DELAY_MS)
        } else {
            handler.removeCallbacks(hideSeekHint)
            seekHint.visibility = View.GONE
        }
    }

    private fun stepMs(repeat: Int): Long {
        val base = session.schema.config.playback.player.seekStepSec.toLong() * 1000L
        val factor = when {
            repeat >= 6 -> 6L
            repeat >= 3 -> 3L
            else -> 1L
        }
        return base * factor
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun keyOf(t: PlayTarget): String = progressKey(session.source.id, t.summary.id, t.episode)

    private fun savePosition(clear: Boolean = false) {
        val p = player ?: return
        val key = keyOf(current)
        val pos = p.currentPosition
        if (clear) {
            store.clear(key)
        } else if (pos > 3000L) {
            store.save(key, pos)
        }
    }

    private fun recordHistory(t: PlayTarget) {
        TvHistoryStore.record(
            session.settings,
            TvHistoryStore.WatchItem(
                sourceId = session.source.id,
                itemId = t.summary.id,
                title = t.summary.title,
                poster = t.summary.poster,
                kind = t.summary.kind.key,
                groupName = t.group?.name,
                episode = t.episode,
                trackIndex = t.trackIndex,
                detailResource = t.summary.detailResource,
            ),
        )
    }

    private fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(progressTicker)
        handler.removeCallbacks(hideControls)
        handler.removeCallbacks(hideSeekHint)
        playerView.player = null
        player?.release()
        player = null
    }

    private companion object {
        const val HIDE_DELAY_MS = 5000L
    }
}

/**
 * 自绘进度条：API 19 的 ProgressBar 只有一个 progressDrawable，setColorFilter 会把
 * 轨道和已播放段一起染成同一个颜色（实测整条都是金色，看不出进度），所以直接画。
 */
private class PlaybackBar(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** API 19 没有 drawRoundRect(float…) 这个重载，只能用 RectF 版本 */
    private val rect = RectF()

    var fraction: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field == v) return
            field = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, width.toFloat(), h)
        paint.color = Color.argb(64, 255, 255, 255)
        canvas.drawRoundRect(rect, r, r, paint)
        if (fraction > 0f) {
            rect.set(0f, 0f, (width * fraction).coerceAtLeast(h), h)
            paint.color = TvUi.ACCENT
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}

/**
 * 播放器根容器：在视图层级最外层先拿按键。
 *
 * 为什么需要它：Activity.onKeyDown 只在"没人消费"时才被调用，焦点在按钮上时
 * 方向键会先被焦点导航吃掉——这正是老版控制条"要一路挪到按钮"的根因。
 * 这里让 root 持有焦点，并覆盖 [dispatchKeyEvent]，按键先走播放器自己的路由。
 */
private class KeyFrame(context: Context) : FrameLayout(context) {

    var onKeyEvent: ((Int, KeyEvent) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = onKeyEvent
        if (handler != null && handler(event.keyCode, event)) return true
        return super.dispatchKeyEvent(event)
    }
}
