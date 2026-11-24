package com.vi.ump.tv

import android.view.KeyEvent
import android.view.View
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PlayTarget
import com.vi.ump.model.TrackGroup

/** 一屏。所有状态保存在对象里，返回时复用同一实例，避免翻页位置丢失。 */
interface TvScreen {
    val view: View
    fun onShow()
    fun onHide() {}
    fun onPause() {}
    fun onResume() {}
    fun onBack(): Boolean = false
    fun onKey(keyCode: Int, event: KeyEvent): Boolean = false

    /** 焦点落点；默认让根视图抢焦点 */
    fun requestInitialFocus() {
        view.requestFocus()
    }
}

interface TvHost {
    fun session(): TvSession?
    fun source(): TvSource?
    fun push(screen: TvScreen)
    fun pop()
    fun openSource(source: TvSource)
    fun openFeed(channelId: String? = null)
    fun openDetail(
        item: MediaSummary,
        startTrackIndex: Int = -1,
        startEpisode: Int = 0,
        autoOpen: Boolean = false,
    )
    fun openImage(
        session: TvSession,
        item: MediaSummary,
        detailKey: String,
        trackKey: String?,
        groups: List<TrackGroup>,
        startIndex: Int,
    )
    fun openPlayer(session: TvSession, target: PlayTarget)
    fun openHistory()
    fun openSettings()
    fun openSearch()
    fun backToHome()
    fun toast(message: String)
}
