package com.vi.ump.tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.vi.ump.config.ConfigCompiler
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PlayTarget
import com.vi.ump.model.TrackGroup
import com.vi.ump.storage.SettingsKeys
import com.vi.ump.storage.createAppSettings
import java.util.concurrent.Executors

/**
 * TV 的唯一 Activity：内部用 [TvScreen] 做页面栈。
 *
 * 为什么不用多 Activity：4.4 盒子的 Activity 转场慢，而且源/管线是进程级对象；
 * 单 Activity + 页面对象栈更稳，返回时也能保留列表滚动位置。
 */
class TvMainActivity : Activity(), TvHost {

    private lateinit var container: FrameLayout
    private lateinit var emptyHint: TextView

    private var current: TvScreen? = null
    private val stack = ArrayList<TvScreen>()
    private var currentSession: TvSession? = null
    private var currentSource: TvSource? = null

    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        emptyHint = TextView(this).apply {
            setTextColor(TvUi.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        container.addView(emptyHint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(container)

        val home = HomeScreen(this)
        push(home)

        // 有上次选中的源就直接进首页；没有就停在源管理页
        val last = createAppSettings().getString(SettingsKeys.SOURCE_ID)
        val target = TvSourceStore.list(this).firstOrNull { it.id == last }
            ?: TvSourceStore.list(this).firstOrNull()
        if (target != null) {
            openSource(target)
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        current?.onResume()
    }

    override fun onPause() {
        current?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        current?.onHide()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (current?.onBack() == true) return
        pop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (current?.onKey(keyCode, event) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    // ── TvHost ────────────────────────────────────────────────

    override fun session(): TvSession? = currentSession

    override fun source(): TvSource? = currentSource

    override fun push(screen: TvScreen) {
        current?.let {
            it.onHide()
            stack.add(it)
        }
        attach(screen)
    }

    override fun pop() {
        if (stack.isEmpty()) {
            finish()
            return
        }
        current?.onHide()
        val previous = stack.removeAt(stack.lastIndex)
        attach(previous)
    }

    private fun attach(screen: TvScreen) {
        current = screen
        container.removeAllViews()
        container.addView(screen.view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        screen.onShow()
        screen.view.post { screen.requestInitialFocus() }
    }

    override fun openSource(source: TvSource) {
        runIo {
            val result = runCatching {
                val session = TvSession(this, source)
                if (session.schema.hasErrors) {
                    error(
                        "配置校验未通过：" + session.schema.issues.take(2)
                            .joinToString("；") { "[${it.code.code}] ${it.path} ${it.message}" }
                    )
                }
                session
            }
            ui {
                result.onSuccess {
                    currentSession = it
                    currentSource = source
                    it.settings.putString(SettingsKeys.SOURCE_ID, source.id)
                    push(FeedScreen(this, it))
                }.onFailure {
                    toast("打开源失败：${it.message}")
                }
            }
        }
    }

    override fun openFeed(channelId: String?) {
        val s = currentSession ?: return
        val existing = stack.lastOrNull { it is FeedScreen } as? FeedScreen
        if (existing != null) {
            // 从设置/历史回来：回到原来的首页实例，保留频道与滚动位置
            while (current !== existing && stack.isNotEmpty()) {
                current?.onHide()
                stack.removeAt(stack.lastIndex).also { current = it }
            }
            if (current === existing) {
                container.removeAllViews()
                container.addView(existing.view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                existing.requestInitialFocus()
                return
            }
        }
        push(FeedScreen(this, s))
        if (channelId != null) {
            main.post { (current as? FeedScreen)?.selectChannel(channelId) }
        }
    }

    override fun openDetail(
        item: MediaSummary,
        startTrackIndex: Int,
        startEpisode: Int,
        autoOpen: Boolean,
    ) {
        val s = currentSession ?: return
        push(DetailScreen(this, s, item, startTrackIndex, startEpisode, autoOpen))
    }

    override fun openImage(
        session: TvSession,
        item: MediaSummary,
        detailKey: String,
        trackKey: String?,
        groups: List<TrackGroup>,
        startIndex: Int,
    ) {
        push(ImageViewerScreen(this, session, item, detailKey, trackKey, groups, startIndex))
    }

    override fun openPlayer(session: TvSession, target: PlayTarget) {
        push(PlayerScreen(this, session, target))
    }

    override fun openHistory() {
        currentSession?.let { push(HistoryScreen(this, it)) }
    }

    override fun openSettings() {
        currentSession?.let { push(SettingsScreen(this, it)) }
    }

    override fun openSearch() {
        currentSession?.let { push(SearchScreen(this, it)) }
    }

    override fun backToHome() {
        while (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        current?.onHide()
        current = null
        push(HomeScreen(this))
    }

    override fun toast(message: String) {
        ui { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    // ── threading / system UI ─────────────────────────────────

    fun runIo(block: () -> Unit) {
        io.execute(block)
    }

    fun ui(block: () -> Unit) {
        main.post(block)
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
