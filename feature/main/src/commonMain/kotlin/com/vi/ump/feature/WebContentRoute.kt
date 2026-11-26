package com.vi.ump.feature

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PlayTarget
import com.vi.ump.render.UmpWebPlayer

/**
 * 网页播放的内容页（配置 `playback.web = true` 时走这里）。
 *
 * 为什么不做成"给 ExoPlayer 换一个地址"：YouTube 这类站的直链要`nsig`签名、
 * 还要 PoToken，裸抓到的地址放几秒就 403 —— 站点只让自己网页里的播放器取流。
 * 所以这一页把 `playUrl`（一个网页地址）交给平台内嵌浏览器，
 * 画面、进度条、清晰度、字幕、弹幕式评论区全由站点自己的前端负责。
 *
 * 本路由不请求详情、不建播放器、不记进度：进度存在站点自己的账号里。
 */
@Composable
fun WebContentRoute(
    session: UmpSession,
    item: MediaSummary,
    modifier: Modifier = Modifier,
    webPlayer: (@Composable (String, Modifier) -> Unit)? = null,
    initialTarget: PlayTarget? = null,
) {
    // 从历史/选集带进来的那一集优先：它才是用户真正点的那一条
    val url = (initialTarget?.url?.takeIf { it.isNotBlank() } ?: item.playUrl)?.trim()

    UmpWebPlayer(
        title = initialTarget?.title ?: item.title,
        subtitle = item.subtitle,
        url = url,
        modifier = modifier,
        unsupportedReason = when {
            url.isNullOrBlank() -> "这条没有网页地址，无法用网页播放"
            webPlayer == null -> "此端不支持网页播放（桌面 / TV 4.4 的内嵌浏览器版本过低）"
            else -> null
        },
        surface = { m -> webPlayer?.invoke(url!!, m) },
    )
}
