package com.vi.ump.render

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.vi.ump.model.Track
import kotlinx.coroutines.launch

/**
 * 图片查看器（M4.6 → M6.6 重做）。
 *
 * 硬约束（前三条是实测出来的，不是设计偏好）：
 *
 * 1. **默认上下翻页**：竖屏漫画/图集是"往下读"的，横向翻页在这里是错的手感。
 * 2. **放大时禁翻页**（`userScrollEnabled = !zoomed`）：否则双指拖动会被判成翻页。
 * 3. **工具条单行**：早期版本是"页标签 / 按钮 / 提示"三行，实占 110dp 以上，
 *    用户明确反馈"上下滑动切换 双指缩放 占用高度太高"。现在只留一行，
 *    缩放提示缩成行尾一个小字，不再单独占一行。
 * 4. **懒取地址（2026-11-17）**：图集站点一张图一个内页，进详情时把 60 张的内页全请求一遍
 *    既费流量又容易被限流。`episodes.lazyItems` 打开后，详情只给"第 N 张"的 id 表，
 *    查看器翻到哪一张才去取哪一张的直链（[loadPending]）。
 * 5. **往后预加载 N 张（2026-11-18，口径由用户确认）**：`preload` = 当前张**之后**预取几张，默认 3。
 *    例：设 3 → 看第 1 张时加载 1、预加载 2/3/4；看第 15 张时加载 15、预加载 16/17/18。
 *    只往后不留前面：回看靠已经取到的直链 + 图片缓存，不会重新发取址请求。
 *    只靠 `beyondViewportPageCount` 挡不住（Pager 自己会多组合），所以另有一道
 *    `page in current..(current + preload)` 的硬限窗——窗口外只画空 Box，不取址、不解码。
 */
@Composable
fun ImageViewer(
    title: String,
    tracks: List<Track>,
    startIndex: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onIndexChange: (Int) -> Unit = {},
    /**
     * 懒取一张图：入参是"第几页（0 起）+ id 表里的占位 id"，返回带直链的 Track。
     * 为 null 表示这个源不走懒取（`url` 已在 tracks 里）。
     */
    loadPending: (suspend (Int, String) -> Track?)? = null,
    /**
     * 当前张**之后**预加载几张（0..8，默认 3）。值来自设置 `SettingsKeys.IMAGE_PRELOAD`，
     * 越界在这里再夹一次，避免坏配置把一大片内页请求打出去。
     */
    preload: Int = 3,
) {
    if (tracks.isEmpty()) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("没有可看的图片", color = Color.White.copy(alpha = 0.7f))
        }
        return
    }

    val safeStart = startIndex.coerceIn(0, tracks.lastIndex)
    // 设置里的"预加载张数"：只往后数。越界再夹一次（0..8）
    val preloadAhead = preload.coerceIn(0, 8)
    val pagerState = rememberPagerState(initialPage = safeStart) { tracks.size }
    val scope = rememberCoroutineScope()
    // 任一页放大 → 禁掉翻页；回到 1 倍再放开
    var zoomed by remember { mutableStateOf(false) }
    // 只重挂当前页的变换，翻页后新页从 1x 开始（否则上一页的 2x 会被带过去）
    var resetToken by remember { mutableStateOf(0) }
    // 已取到直链的页 → URL；取失败的页单独记，避免一直转圈
    val urls = remember { mutableStateMapOf<Int, String>() }
    val failed = remember { mutableStateMapOf<Int, Boolean>() }
    // 跳转对话框的输入（null = 弹窗关闭）
    var jumpInput by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect {
            onIndexChange(it)
            resetToken++
            zoomed = false
        }
    }

    fun goTo(page: Int) {
        val target = page.coerceIn(0, tracks.lastIndex)
        scope.launch { pagerState.animateScrollToPage(target) }
    }

    Column(modifier.fillMaxSize().background(Color.Black)) {
        // 顶栏：返回 + 标题 + 计数。黑底上看图不该被大标题压住，所以只有一行
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                Text("←", color = Color.White)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            Text(
                text = "${pagerState.currentPage + 1} / ${tracks.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        // 横向翻页的跟手位移（-1..1，1 = 已滑满一整屏向"下一张"）；以及视口宽度，用来换算
        var hProgress by remember { mutableStateOf(0f) }
        var viewportW by remember { mutableStateOf(1f) }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onSizeChanged { viewportW = it.width.toFloat().coerceAtLeast(1f) }
                // 横向手势放在 Pager 外层：纵向拖动交给 VerticalPager（这个检测器等到纵向 slop
                // 先超限就会自己取消），只有横向占优才接管并 consume，所以两者不打架。
                .pointerInput(pagerState.currentPage, tracks.size, viewportW, zoomed) {
                    if (zoomed) return@pointerInput
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            hProgress = (hProgress + dragAmount / viewportW).coerceIn(-1f, 1f)
                        },
                        onDragEnd = {
                            val cur = pagerState.currentPage
                            // 过 28% 就算切换，否则回弹（和主流图片浏览器的阈值一致）
                            val next = when {
                                hProgress >= 0.28f && cur < tracks.lastIndex -> cur + 1
                                hProgress <= -0.28f && cur > 0 -> cur - 1
                                else -> cur
                            }
                            val target = when {
                                next > cur -> 1f
                                next < cur -> -1f
                                else -> 0f
                            }
                            scope.launch {
                                animate(hProgress, target, animationSpec = tween(durationMillis = 180)) { v, _ ->
                                    hProgress = v
                                }
                                if (target != 0f) {
                                    // 先瞬间落到目标页、再把位移归零：新页正好停在邻居层刚滑到的位置，
                                    // 所以视觉上是一次连续的横向切换，而不是"跳一下"
                                    pagerState.scrollToPage(next)
                                    hProgress = 0f
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                animate(hProgress, 0f, animationSpec = tween(durationMillis = 180)) { v, _ ->
                                    hProgress = v
                                }
                            }
                        },
                    )
                },
        ) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = hProgress * viewportW },
                userScrollEnabled = !zoomed,
                // 当前张 + 之后 preloadAhead 张 = 预加载窗口（默认 3 → 当前 1、预加载 2/3/4）
                beyondViewportPageCount = preloadAhead,
            ) { page ->
                val track = tracks[page]
                val direct = track.url.takeIf { it.isNotBlank() }
                // 跨模块的属性不能智能转换，先捞成局部值
                val pendingId = track.pending

                // **硬限窗**：当前张以及它之后的 preloadAhead 张才取地址与下图片（只往后，不回留）。
                // 只靠 `beyondViewportPageCount` 实测挡不住：一次进图集，日志里连续取了 13 张的内页
                // （Pager 在自己判断要不要预组合，和我们的预期不是一个口径）。
                // 回看（上一张）靠已取到的直链 + 图片缓存，不会重新发取址请求，所以不需要往前预留。
                val current = pagerState.currentPage
                val near = page in current..(current + preloadAhead)

                // 取址失败后允许用户点一下重试（站点偶发限流/超时会解析失败，翻走再翻回不一定重来）
                var retryTick by remember { mutableStateOf(0) }
                LaunchedEffect(page, pendingId, near, retryTick) {
                    if (!near) return@LaunchedEffect
                    if (direct == null && pendingId != null && loadPending != null &&
                        urls[page] == null && failed[page] != true
                    ) {
                        val loaded = loadPending(page, pendingId)
                        val url = loaded?.url?.takeIf { it.isNotBlank() }
                        if (url != null) urls[page] = url else failed[page] = true
                    }
                }
                if (near) {
                    ZoomableImage(
                        url = direct ?: urls[page],
                        failed = failed[page] == true,
                        hint = "第 ${page + 1} / ${tracks.size} 张",
                        resetToken = resetToken,
                        onZoomChange = { zoomed = it },
                        onRetry = {
                            failed[page] = false
                            retryTick++
                        },
                    )
                } else {
                    // 窗口外的页：纯占位，不发请求、不解码
                    Box(Modifier.fillMaxSize())
                }
            }

            // 横向拖动时把目标页盖在边上：否则只有当前页在挪，看不到"下一张滑进来"
            if (hProgress != 0f) {
                val dir = if (hProgress > 0f) 1 else -1
                val neighbor = pagerState.currentPage + dir
                if (neighbor in tracks.indices) {
                    val neighborUrl = tracks[neighbor].url.takeIf { it.isNotBlank() } ?: urls[neighbor]
                    if (neighborUrl != null) {
                        AsyncImage(
                            model = neighborUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationX = hProgress * viewportW - dir * viewportW },
                        )
                    }
                }
            }
        }

        ViewerToolbar(
            page = pagerState.currentPage,
            total = tracks.size,
            zoomed = zoomed,
            onPrev = { goTo(pagerState.currentPage - 1) },
            onNext = { goTo(pagerState.currentPage + 1) },
            onFirst = { goTo(0) },
            onLast = { goTo(tracks.lastIndex) },
            onJump = { jumpInput = "" },
            onResetZoom = { resetToken++ },
        )
    }

    // 页码跳转（用户反馈"图片浏览不能跳转"）：点工具条上的页码 → 输入框 → 直达
    jumpInput?.let { text ->
        AlertDialog(
            onDismissRequest = { jumpInput = null },
            title = { Text("跳到第几张") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> jumpInput = v.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    label = { Text("1 - ${tracks.size}") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.let { goTo(it - 1) }
                    jumpInput = null
                }) { Text("跳转") }
            },
            dismissButton = {
                TextButton(onClick = { jumpInput = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 底部工具条：**一行**。刻意只有"能操作"的东西：
 * 首页 / 上一张 / 页码（可点跳转）/ 下一张 / 末页 + 缩放状态。
 *
 * 为什么页码可点：900 页的图集靠甩动翻不现实，用户需要"跳到第 N 张 / 到底 / 回开头"。
 * 为什么缩放的复位按钮只在放大时出现：不放大时它没有意义，常驻只会多占一格。
 */
@Composable
private fun ViewerToolbar(
    page: Int,
    total: Int,
    zoomed: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFirst: () -> Unit,
    onLast: () -> Unit,
    onJump: () -> Unit,
    onResetZoom: () -> Unit,
) {
    Surface(color = Color(0xFF141414)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolButton("⇤", enabled = page > 0, onClick = onFirst)
            ToolButton("▲", enabled = page > 0, onClick = onPrev)
            TextButton(
                onClick = onJump,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "${page + 1} / $total",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
            ToolButton("▼", enabled = page < total - 1, onClick = onNext)
            ToolButton("⇥", enabled = page < total - 1, onClick = onLast)
            Spacer(Modifier.weight(1f))
            if (zoomed) {
                ToolButton("复位", enabled = true, onClick = onResetZoom, small = true)
            } else {
                Text(
                    text = "双指缩放",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val color = if (enabled) Color.White else Color.White.copy(alpha = 0.25f)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.minTouch(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}

/** 触摸区至少 44dp 宽，避免"看得见点不准"（工具条压成一行后按钮变窄，这里补回来） */
private fun Modifier.minTouch(): Modifier = this.width(44.dp)

/**
 * 一页图：`ContentScale.Fit` 先整张放进屏幕，再由用户自己放大。
 * 放大后允许拖动，但把位移限制在"图片边缘不出画面"的范围内——
 * 否则能一路拖到看不见图，比不能拖更让人迷惑。
 *
 * 三种占位状态各有各的话：
 * - `url == null`：直链还没取到（懒取），显示"正在获取地址"；
 * - Loading：图在下载，显示"加载中"（用户要的"加载动画"）；
 * - failed：地址取不到 / 解码失败，给出可读的结论而不是一片黑。
 */
@Composable
private fun ZoomableImage(
    url: String?,
    failed: Boolean,
    hint: String,
    resetToken: Int,
    onZoomChange: (Boolean) -> Unit,
    onRetry: () -> Unit = {},
) {
    var scale by remember(resetToken) { mutableStateOf(1f) }
    var offsetX by remember(resetToken) { mutableStateOf(0f) }
    var offsetY by remember(resetToken) { mutableStateOf(0f) }
    var size by remember(resetToken) { mutableStateOf(1000f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it.width.toFloat().coerceAtLeast(1f) }
            // 不能用 `detectTransformGestures`：它在**单指**时也会消费事件，
            // Pager 就永远收不到拖动 —— 实测表现是"上下滑不动，只能看到第一张"。
            // 所以手写：只有多指、或已经放大（此时翻页已被禁用）才消费，否则把事件让给 Pager。
            .pointerInput(resetToken) {
                // 这里只管缩放/平移。横向翻页由外层 Pager 的检测器负责（它要拿到跟手位移才能做滑动动画），
                // 所以单指事件一律不消费，纵向交给 VerticalPager、横向交给外层。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) multiTouch = true
                        if (multiTouch || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val next = (scale * zoom).coerceIn(1f, 4f)
                            if (next <= 1.01f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                                onZoomChange(false)
                            } else {
                                scale = next
                                // 可平移余量 = 放大后多出来的那一半；不收窄就会被拖出画面
                                val max = (size * (next - 1f)) / 2f
                                offsetX = (offsetX + pan.x).coerceIn(-max, max)
                                offsetY = (offsetY + pan.y).coerceIn(-max, max)
                                onZoomChange(true)
                            }
                            event.changes.forEach { it.consume() }
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> Box(
                modifier = Modifier.fillMaxSize().clickable(onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) {
                LoadingHint("图片地址获取失败 · 点按重试", hint, showSpinner = false)
            }
            url == null -> LoadingHint("正在获取图片地址", hint, showSpinner = true)
            else -> {
                // Coil 3 的 AsyncImage 用 `onState` 回调（不是 painter 重载）：
                // 状态是自己的 state，首次组合还不知道结果 → null 也按"加载中"处理
                // 重载计数只影响这一张图：改 memoryCacheKey 才会真的重新发起请求，
                // 已经加载成功的其它页完全不受影响（它们的 model 没变，命中缓存直接画）
                var reloadTick by remember(url) { mutableStateOf(0) }
                val context = LocalPlatformContext.current
                val model = remember(url, reloadTick) {
                    ImageRequest.Builder(context)
                        .data(url)
                        .memoryCacheKey("$url#r$reloadTick")
                        .build()
                }
                var state by remember(url, reloadTick) { mutableStateOf<AsyncImagePainter.State?>(null) }
                AsyncImage(
                    model = model,
                    contentDescription = hint,
                    contentScale = ContentScale.Fit,
                    onState = { state = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        },
                )
                when (val s = state) {
                    null, is AsyncImagePainter.State.Loading ->
                        LoadingHint("加载中", hint, showSpinner = true)
                    is AsyncImagePainter.State.Error -> Box(
                        modifier = Modifier.fillMaxSize().clickable { reloadTick++ },
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingHint("这张图加载失败 · 点按重试", "$hint · 也可上下滑动略过", showSpinner = false)
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** 居中的加载/失败提示：转圈 + 一句话 + 第几张。不占固定高度，叠在图上 */
@Composable
private fun LoadingHint(text: String, hint: String, showSpinner: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (showSpinner) {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.8f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.size(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.45f),
            textAlign = TextAlign.Center,
        )
    }
}
