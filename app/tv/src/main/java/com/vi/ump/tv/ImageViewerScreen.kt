package com.vi.ump.tv

import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vi.ump.model.MediaKind
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.Track
import com.vi.ump.model.TrackGroup
import com.vi.ump.storage.SettingsKeys
import kotlinx.coroutines.runBlocking

/**
 * 图片阅读器（TV）。
 *
 * 预加载口径与手机版一致：设置 N = **当前张之后**再取 N 张（1..N 向后），不向前。
 * 懒取图集先只拿 id 表，翻到哪张才 `resolvePendingTrack` 取直链；取到直链后立即预下载字节。
 */
class ImageViewerScreen(
    private val host: TvHost,
    private val session: TvSession,
    private val item: MediaSummary,
    private val detailKey: String,
    private val trackKey: String?,
    groups: List<TrackGroup>,
    startIndex: Int,
) : TvScreen {

    private val ctx = host as android.content.Context
    private val root: FrameLayout
    private val image: ImageView
    private val title: TextView
    private val counter: TextView
    private val hint: TextView
    private val overlay: LinearLayout

    private val entries = mutableListOf<Track>()
    private val urls = mutableMapOf<Int, String>()
    private val resolving = mutableSetOf<Int>()
    private val preloading = mutableSetOf<Int>()
    private var index = 0
    private var overlayVisible = true
    private var loadingDetail = false
    private var started = false
    private var lastSaved = -1

    init {
        entries += flatten(groups)
        index = startIndex.coerceAtLeast(0)
        root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        image = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        overlay = with(TvUi) { ctx.column() }.apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(ctx.dp(28), ctx.dp(12), ctx.dp(28), ctx.dp(12))
        }
        title = with(TvUi) { ctx.tvText(item.title, 18f, TvUi.TEXT) }
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        counter = with(TvUi) { ctx.tvText("", 14f, TvUi.ACCENT) }
        hint = with(TvUi) {
            ctx.tvText("遥控器：← 上一张   → 下一张   OK 显示/隐藏信息   返回键退出", 13f, TvUi.TEXT_DIM)
        }
        overlay.addView(title, with(TvUi) { ctx.matchWrap() })
        overlay.addView(counter, with(TvUi) { ctx.matchWrap(top = 4) })
        overlay.addView(hint, with(TvUi) { ctx.matchWrap(top = 4) })

        val topParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        )
        topParams.setMargins(ctx.dp(32), ctx.dp(18), ctx.dp(32), 0)
        root.addView(overlay, topParams)

        val bottom = with(TvUi) {
            ctx.tvText("◀   上一张         下一张   ▶", 17f, TvUi.TEXT)
        }.apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(0, ctx.dp(12), 0, ctx.dp(12))
        }
        val bottomParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        root.addView(bottom, bottomParams)
        overlay.tag = "overlay"
        bottom.tag = "bottom"
    }

    override val view: View get() = root

    override fun onShow() {
        if (!started) {
            started = true
            if (entries.isEmpty()) {
                loadDetailThenStart()
            } else {
                restoreAndShow()
            }
        }
    }

    override fun requestInitialFocus() {
        root.requestFocus()
    }

    override fun onHide() {
        persist()
    }

    override fun onBack(): Boolean {
        persist()
        host.pop()
        return true
    }

    override fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                move(-1); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                move(1); true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                overlayVisible = !overlayVisible
                overlay.visibility = if (overlayVisible) View.VISIBLE else View.GONE
                (root.getChildAt(root.childCount - 1))?.visibility =
                    if (overlayVisible) View.VISIBLE else View.GONE
                true
            }
            else -> false
        }
    }

    private fun loadDetailThenStart() {
        if (loadingDetail) return
        loadingDetail = true
        val activity = host as? TvMainActivity
        activity?.runIo {
            val result = runCatching {
                runBlocking { session.pipeline.detail(detailKey, item.id, session.detailParams(), trackKey) }
            }
            activity.ui {
                loadingDetail = false
                result.onSuccess { d ->
                    if (d != null) entries += flatten(d.trackGroups)
                    if (entries.isEmpty()) {
                        title.text = "这一条没有图片"
                        counter.text = "返回键退出"
                    } else {
                        restoreAndShow()
                    }
                }.onFailure {
                    title.text = "图片集加载失败：${it.message}"
                    counter.text = "返回键退出"
                }
            }
        }
    }

    private fun restoreAndShow() {
        if (entries.isEmpty()) return
        val saved = session.settings.getString(progressKey())?.toIntOrNull() ?: 0
        val start = if (index in entries.indices && index > 0) index else saved.coerceIn(0, entries.lastIndex)
        index = start
        persist()
        showCurrent()
    }

    private fun move(delta: Int) {
        if (entries.isEmpty()) return
        val next = (index + delta).coerceIn(0, entries.lastIndex)
        if (next == index) return
        index = next
        showCurrent()
    }

    private fun showCurrent() {
        if (entries.isEmpty()) return
        index = index.coerceIn(0, entries.lastIndex)
        val current = entries[index]
        title.text = item.title
        counter.text = "第 ${index + 1} / ${entries.size} 张" + if (current.title.isNotBlank()) " · ${current.title}" else ""
        persist()
        load(currentIndex = index)

        val preload = session.imagePreload()
        for (offset in 1..preload) {
            val i = index + offset
            if (i !in entries.indices) break
            ensureUrl(i, thenPreload = true)
        }
    }

    private fun persist() {
        if (entries.isEmpty() || index == lastSaved) return
        lastSaved = index
        session.settings.putString(progressKey(), index.toString())
        val track = entries.getOrNull(index) ?: return
        TvHistoryStore.record(
            session.settings,
            TvHistoryStore.WatchItem(
                sourceId = session.source.id,
                itemId = item.id,
                title = item.title,
                poster = item.poster ?: item.imageUrl,
                kind = "image",
                groupName = null,
                episode = track.index,
                trackIndex = index,
            ),
        )
    }

    private fun progressKey(): String =
        "${SettingsKeys.IMAGE_PROGRESS_PREFIX}.${session.source.id}.${item.id}"

    private fun load(currentIndex: Int) {
        ensureUrl(currentIndex, thenPreload = true)
        val activity = host as? TvMainActivity
        activity?.ui {
            val url = urls[currentIndex]
            if (url == null) {
                // 直链还没回来：先留一个可读的占位，失败会由 ensureUrl 的提示覆盖
                return@ui
            }
            val w = ctx.resources.displayMetrics.widthPixels
            val h = ctx.resources.displayMetrics.heightPixels
            TvImageLoader.load(
                image, url, session.schema.config.runtime.headers,
                session.schema.config.runtime.imageDecrypt, session.fetcher,
                w, h,
            ) { ok ->
                if (!ok && currentIndex == index) {
                    counter.text = "第 ${index + 1} / ${entries.size} 张 · 加载失败（←/→ 重试）"
                }
            }
        }
    }

    /**
     * 保证第 i 张有直链；`thenPreload=true` 时直链一到就下载字节。
     * 同一个下标只发一次请求；失败后清掉标志，下一次翻到它时还能重试。
     */
    private fun ensureUrl(i: Int, thenPreload: Boolean) {
        if (i !in entries.indices) return
        urls[i]?.let {
            if (thenPreload) preloadBytes(i, it)
            return
        }
        if (!resolving.add(i)) return
        val track = entries[i]
        if (track.url.isNotBlank()) {
            resolving.remove(i)
            urls[i] = track.url
            if (thenPreload) preloadBytes(i, track.url)
            if (i == index) load(i)
            return
        }
        val pending = track.pending
        if (pending == null) {
            resolving.remove(i)
            return
        }
        val activity = host as? TvMainActivity ?: return
        activity.runIo {
            val resolved = runCatching {
                runBlocking { session.pipeline.resolvePendingTrack(detailKey, item.id, track.index, pending) }
            }.getOrNull()
            activity.ui {
                resolving.remove(i)
                val url = resolved?.url?.takeIf { it.isNotBlank() }
                if (url != null) {
                    urls[i] = url
                    if (thenPreload) preloadBytes(i, url)
                    if (i == index) load(i)
                } else if (i == index) {
                    counter.text = "第 ${index + 1} / ${entries.size} 张 · 直链解析失败"
                }
            }
        }
    }

    private fun preloadBytes(i: Int, url: String) {
        if (!preloading.add(i)) return
        TvImageLoader.preload(
            url,
            session.schema.config.runtime.headers,
            session.schema.config.runtime.imageDecrypt,
            session.fetcher,
        ) { ok ->
            if (!ok) preloading.remove(i) else preloading.remove(i)
        }
    }

    private fun flatten(groups: List<TrackGroup>): List<Track> {
        if (groups.isEmpty()) return emptyList()
        val imageGroups = groups.filter { it.kind == MediaKind.IMAGE || item.kind == MediaKind.IMAGE }
        return imageGroups.flatMap { it.tracks }
    }
}
