package com.vi.ump.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vi.ump.model.TrackGroup
import com.vi.ump.player.PlayerCapabilities
import com.vi.ump.player.PlayerState
import com.vi.ump.player.PlayerStatus
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * 播放页的"画"。**不含任何播放器实现**——它只吃 `PlayerState` 与 `PlayerCapabilities`，
 * 所以同一份 UI 在 Android（Media3）、桌面（M7 的 VLCJ）、iOS（AVPlayer）上都能用。
 *
 * 关键一条：能力不足时**不假装能播**。`capabilities.canPlayAnything == false` 时直接显示原因，
 * 而不是给一个点了没反应的按钮（那是"App 坏了"的第一印象来源）。
 *
 * 控件版式（2026-11-24 对齐 TV 版）：**底部叠层 = 一行「当前时间 · 细进度条 · 总时长」+
 * 一行居中的 `<< −10s 播放/暂停 +10s >>`**；全屏时左上角加标题和状态。播放中 5 秒无操作
 * 自动淡出，点画面唤出并切换播放/暂停，暂停时控制层常显。
 *
 * 倍速/线路/全屏这类"设置入口"不进这套五键版式：手机宽度放不下（五键 240dp + 三个下拉 180dp
 * 必然溢出），窗口态放在画面正下方一行，全屏时放在底栏最上面一行（右对齐）。
 * 画面中央不再放常驻按钮，只有一个"还没选播放目标"时才出现的封面播放键。
 *
 * 全屏状态默认由本函数自己持有；路由层想接管（例如"全屏时按返回应先退出全屏"）
 * 就把 `fullscreenState` 传进来——状态提升后本函数只读不写自己的副本。
 */
@Composable
fun UmpPlayer(
    title: String,
    poster: String?,
    url: String?,
    state: PlayerState,
    capabilities: PlayerCapabilities,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onRetry: () -> Unit = {},
    onSpeed: (Float) -> Unit = {},
    /** 「复制播放地址」，由路由层注入平台剪贴板实现 */
    onCopyUrl: (String) -> Unit = {},
    /** 快进/快退步长，来自配置 `playback.player.seekStepSec` */
    seekStepMs: Long = 10_000L,
    /**
     * 画面槽位：由 app 壳层注入（Android 传 `AndroidView + PlayerView`，桌面端不传）。
     * render 这一层**依旧不出现播放器符号**，只是把画面画在自己那块 Box 里。
     */
    surface: @Composable (Modifier) -> Unit = {},
    /**
     * 可选的"状态提升"入口：传了就由调用方持有全屏状态（可读写），
     * 不传则是本函数内部的 `remember`。见顶部注释。
     */
    fullscreenState: MutableState<Boolean>? = null,
    /**
     * 有值时表示"这次是从上次的位置续播"，界面上标一句 `上次看到 12:34`。
     * 只做提示，不做逻辑：真正的 seek 已经在 `prepare(startMs)` 里做过。
     */
    resumedFromMs: Long? = null,
    /** 自动隐藏的等待时长（对齐 TV 版的 5 秒）；改小便于自动化验证 */
    autoHideMs: Long = 5_000L,
    /**
     * 控制条摆放：`true` = 放在画面**下方**常驻，`false`（默认）= 叠在画面内、静置自动隐藏。
     *
     * 桌面端必须传 `true`。原因不是审美：桌面端的视频面是 vlcj 交出的 heavyweight AWT `Canvas`，
     * 由 Compose 的 `SwingPanel` 承载，它绘制在 Compose 画布**之上** —— 叠在画面里的控件会被视频整块盖住。
     * 手机端相反：Media3 的 `PlayerView` 与 Compose 同在一个 ViewGroup 下，叠层能正常浮在上面。
     */
    controlsBelowSurface: Boolean = false,
    /**
     * 多线路 / 多播放列表的分组名。多于一个才在控制条上出"线路N ▾"下拉。
     *
     * 线路切换**只换地址、不换页**：同一个条目在另一条线路上还是同一集，
     * 所以这里只上报"选了第几组"，取哪一条由路由层按集号对齐。
     */
    lines: List<String> = emptyList(),
    lineIndex: Int = 0,
    onPickLine: (Int) -> Unit = {},
    /**
     * 当前这一组里的条目（选集/曲目）。多于一条时在标题下方画**与详情页同一套**的格子，
     * 点哪格就切到哪一集——数据早就在 `PlayTarget.groups` 里，这里不重新请求。
     *
     * 全屏时不画：全屏是"看画面的"，套一层网格会把画面挤走；退出全屏再选。
     */
    episodeGroup: TrackGroup? = null,
    /** 当前条目在 [episodeGroup] 里的下标（`TrackGroup.tracks` 下标，用来高亮） */
    episodeIndex: Int = 0,
    onPickEpisode: (Int) -> Unit = {},
    /**
     * 全屏控制条上的「上一集 / 下一集」（2026-11-18 用户要求：全屏时 `<< ‹10s 10s› >>`）。
     *
     * 只在**全屏**且当前组多于一条时出现：非全屏的画面下方本来就有选集格子，再放两个按钮是重复入口；
     * 而窗口态控制条已经很挤，加两个按钮会把「全屏」挤出屏幕。
     * 传 null = 已经在第一集 / 最后一集：按钮仍占位（只是禁用），避免换集时播放键左右跳。
     */
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    /**
     * 连续播放：播完自动接**同组下一集 / 下一首**。默认 **关**——是否连播由用户在界面上决定，
     * 状态由宿主（`AppRoot`）从全局设置里读，所以换源、换集、杀进程重开都保持。
     */
    autoNext: Boolean = false,
    /**
     * 非空才在状态行右侧画「连续播放」开关。传 null（例如只想显示状态、不给改）就不画。
     * 放在状态行而不是控制条上：控制条会自动淡出，而"要不要连播"是设置类操作，
     * 不该跟着视频一起消失。
     */
    onAutoNextChange: ((Boolean) -> Unit)? = null,
    /**
     * 页面往下滚（内容离开顶部）时回调 `true`，滚回顶部回调 `false`。
     *
     * 为什么要这个：手机上一屏放不下"画面 + 标题 + 选集 + 连播开关"，往下滑着看选集时，
     * 顶上那条 112px 的顶栏还占着地方（用户反馈"下滑时最上面的组件没隐藏"）。
     * 宿主收到 `true` 就把顶栏收起来，滚回顶部再放出来。传 null 就不通知（音频页用不到）。
     */
    onScrollAwayChange: ((Boolean) -> Unit)? = null,
    /**
     * 标题/状态行下面的附加信息区。合并后的内容页把简介、演职员、选集放进来；
     * 音频页和旧播放页不传，保持原样。
     */
    info: @Composable ColumnScope.() -> Unit = {},
    /**
     * 尚未选播放目标时的封面播放键。非空表示"这个内容页还没起播"，
     * 点一下从第一集开始；为 null 时才是真正的"这端不能播"。
     */
    onStart: (() -> Unit)? = null,
) {
    val playable = capabilities.canPlayAnything && !url.isNullOrBlank()
    val waitingStart = url.isNullOrBlank() && onStart != null
    val fs = fullscreenState ?: remember { mutableStateOf(false) }
    val fullscreen by fs
    // 控制条上的上下集开关（TV 版 `<< … >>` 那两颗）：
    // 当前组多于一条就画（窗口态下方有选集格子，但用户要的是同一套五键版式），
    // 或者全屏且宿主给了切集回调（合并内容页不传 episodeGroup，靠 onPrev/onNext 切集）。
    val episodeNav = (episodeGroup?.tracks?.size ?: 0) > 1 ||
        (fullscreen && (onPrev != null || onNext != null))
    var speed by remember { mutableStateOf(1.0f) }

    // 控制层可见性：每次交互 `bump()`，播放中静置 autoHideMs 后自动淡出
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }

    // 这一页的滚动位置：宿主拿它决定顶栏收不收（见 onScrollAwayChange）。
    // 阈值 12px 是为了滤掉触摸抖动——真正的"往下滚"一次就是几十像素起。
    val scrollState = rememberScrollState()
    if (onScrollAwayChange != null) {
        LaunchedEffect(scrollState.value) {
            onScrollAwayChange.invoke(scrollState.value > 12)
        }
    }
    val bump: () -> Unit = {
        controlsVisible = true
        interaction++
    }

    LaunchedEffect(controlsVisible, interaction, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(autoHideMs)
            controlsVisible = false
        }
    }
    // 暂停/就绪/出错时一定要把控制层亮出来，否则用户找不到"继续"
    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) controlsVisible = true
    }

    val overlay: @Composable BoxScope.() -> Unit = {
        // 点击画面 = 播放/暂停（用户点名要的），同时把控制层亮出来：
        // 暂停时要看得见「继续」，播放时也能顺手调倍速/全屏。5s 无操作仍会自动淡出。
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        bump()
                        onToggle()
                        controlsVisible = true
                    },
                ),
        )

        // 顶部：标题 + 状态（仅全屏；窗口态画面下方本来就有标题，不再叠一份）。
        // 版式照 TV：标题居左上，跟控制层同进同退。这里只放**展示**内容，
        // 倍速/线路/全屏这些要点的入口不进这一层（见下面窗口态画面下方那排）。
        if (fullscreen) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.66f), Color.Transparent),
                            )
                        )
                        .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 16.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusLabel(state.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }

        // 底部：一行「当前时间 · 细进度条 · 总时长」，一行居中 `<< −10s 播放 +10s >>`。
        // 这是 TV 版控制层的原样搬法：秒数与进度同一行，按钮只留这五个。
        // 全屏时上面再加一行次要入口（倍速/线路/退出），全屏没有页面可放它们。
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                if (fullscreen) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ExtraControls(
                            fullscreen = true,
                            speed = speed,
                            showSpeed = capabilities.supportsSpeed,
                            onSpeedPick = {
                                bump()
                                speed = it
                                onSpeed(it)
                            },
                            lines = lines,
                            lineIndex = lineIndex,
                            onPickLine = { bump(); onPickLine(it) },
                            onFullscreen = { bump(); fs.value = false },
                        )
                    }
                }
                ProgressRow(
                    state = state,
                    onSeekRatio = {
                        if (state.durationMs > 0L) {
                            bump()
                            onSeek((it * state.durationMs).toLong())
                        }
                    },
                )
                TransportRow(
                    state = state,
                    seekStepMs = seekStepMs,
                    onToggle = { bump(); onToggle() },
                    onSeek = { bump(); onSeek(it) },
                    episodeNav = episodeNav,
                    onPrev = onPrev,
                    onNext = onNext,
                )
            }
        }
    }

    if (fullscreen && controlsBelowSurface) {
        // 桌面端全屏：画面撑满剩余空间，控制条仍留在下方（同样因为 Canvas 会盖住叠层）
        Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (playable) surface(Modifier.matchParentSize())
                if (!playable && !poster.isNullOrBlank()) {
                    UmpAsyncImage(
                        model = poster,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF151515))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                // 桌面端不叠控件，所以这条进度线直接放在常驻控制条的上沿（同一个 ProgressLine）
                ProgressRow(
                    state = state,
                    onSeekRatio = { radius -> onSeek((radius * state.durationMs).toLong()) },
                )
                TransportRow(
                    state = state,
                    seekStepMs = seekStepMs,
                    onToggle = onToggle,
                    onSeek = onSeek,
                    episodeNav = episodeNav,
                    onPrev = onPrev,
                    onNext = onNext,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ExtraControls(
                        fullscreen = fullscreen,
                        speed = speed,
                        showSpeed = capabilities.supportsSpeed,
                        onSpeedPick = {
                            speed = it
                            onSpeed(it)
                        },
                        lines = lines,
                        lineIndex = lineIndex,
                        onPickLine = onPickLine,
                        onFullscreen = { fs.value = !fullscreen },
                    )
                }
            }
        }
        return
    }

    if (fullscreen) {
        // 全屏：画面铺满，控件仍是同一套叠层（自动隐藏在这里更有意义）
        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            if (playable) surface(Modifier.matchParentSize())
            if (!playable && !poster.isNullOrBlank()) {
                UmpAsyncImage(
                    model = poster,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            overlay()
        }
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 画面最高占可视高度的 62%（M6.5）：竖屏短剧按 9:16 铺满宽度时高 1166px，
        // 一屏只有 1280px，叠在画面底部的控制条被顶到屏幕外——点画面也看不见控件，
        // 顺带把"细进度条"也顶出去了。主流短剧 App 的做法就是给画面一个高度上限：
        // 高度到了上限就按比例缩窄、两侧留黑，控件始终在屏幕里。
        val videoAspect = state.videoAspect ?: (16f / 9f)
        val surfaceW = minOf(maxWidth - 32.dp, 720.dp)
        val surfaceH = minOf(surfaceW / videoAspect, maxHeight * 0.62f)

        // 画面本体（含封面 / 起播键 / 叠层）。单独抽成 lambda 是因为桌面端不能把它放进滚动容器：
        // 视频面是 heavyweight 的 AWT Canvas（由 SwingPanel 承载），不参与 Compose 的图层变换；
        // 一旦跟着 verticalScroll 一起滚，Compose 每帧改写它的位置与尺寸，画面就会出现轻微拉伸
        // （桌面端实测"上下滚动时视频画面轻微变形"）。
        val surfaceBlock: @Composable () -> Unit = {
            // 外层这一层**不做圆角裁剪**：控制层贴在这里，弹出来时不受 14dp 圆角影响。
            Box(modifier = Modifier.width(surfaceW)) {
                Box(
                    modifier = Modifier
                        .width(surfaceW)
                        .height(surfaceH)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    // 封面只在"还没起播"时当底图；一旦有进度就撤掉，避免从视频留边处透出来
                    if (!poster.isNullOrBlank() && state.positionMs <= 0L) {
                        UmpAsyncImage(
                            model = poster,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // 画面在封面之上、控件层之下
                    if (playable) surface(Modifier.matchParentSize())
                    // 还没选播放目标：封面中央给一颗明确的「播放」，而不是让用户找下方控制条。
                    // 这里的点击只负责"选第一集并起播"，不负责暂停/继续。
                    if (waitingStart) {
                        Surface(
                            onClick = onStart!!,
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(64.dp),
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("▶", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                    // 桌面端不叠控件（会被视频面盖住），改为下方的常驻控制条
                    if (playable && !controlsBelowSurface) overlay()
                } // 画面 Box（圆角裁剪层）
            } // Box(width = surfaceW)：只量宽度，不再挂进度条
        }
        // 桌面端常驻控制条：贴在画面正下方。叠在画面里会被 Canvas 整块盖住，所以只能放外面。
        val controlsBlock: @Composable () -> Unit = {
            if (playable && controlsBelowSurface) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .background(Color(0xFF151515))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    // 桌面端不叠控件，所以这条进度线直接放在常驻控制条的上沿（同一个 ProgressLine）
                    ProgressRow(
                        state = state,
                        onSeekRatio = { radius -> onSeek((radius * state.durationMs).toLong()) },
                    )
                    TransportRow(
                        state = state,
                        seekStepMs = seekStepMs,
                        onToggle = onToggle,
                        onSeek = onSeek,
                        episodeNav = episodeNav,
                        onPrev = onPrev,
                        onNext = onNext,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ExtraControls(
                            fullscreen = fullscreen,
                            speed = speed,
                            showSpeed = capabilities.supportsSpeed,
                            onSpeedPick = {
                                speed = it
                                onSpeed(it)
                            },
                            lines = lines,
                            lineIndex = lineIndex,
                            onPickLine = onPickLine,
                            onFullscreen = { fs.value = !fullscreen },
                        )
                    }
                }
            }
        }
        // 标题 / 状态 / 简介 / 选集 / 错误 / 复制地址。手机放在整页滚动里，桌面放在独立滚动区。
        val infoBlock: @Composable ColumnScope.() -> Unit = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val meta = buildList {
                // 原来"两端时间"是挂在控制条那条滑块上的；滑块去掉之后进度只靠底沿色条，
                // 精确到秒的读数就挪到这一行（左已播、右总长），信息一项没丢。
                if (state.durationMs > 0L) add("${formatClock(state.positionMs)} / ${formatClock(state.durationMs)}")
                when (state.status) {
                    PlayerStatus.PLAYING -> add("播放中")
                    PlayerStatus.PAUSED -> add("已暂停")
                    PlayerStatus.PREPARING -> add("缓冲中")
                    PlayerStatus.READY -> add("就绪")
                    PlayerStatus.ENDED -> add("已结束")
                    PlayerStatus.ERROR -> add("出错")
                    PlayerStatus.IDLE -> Unit
                }
                if (resumedFromMs != null && resumedFromMs > 0L) {
                    // 这行还要给右侧的「连续播放」开关留位置，所以这里只写"上次 00:26"——
                    // 全称"上次看到 00:26"在 720×360dp 上会和开关贴到一起
                    add("上次 ${formatClock(resumedFromMs)}")
                }
            }.joinToString(" · ")
            // 状态行右侧带「连续播放」开关：一行里同时给出"现在什么状态"和"播完要不要接下一集"。
            // 只在有开关或有状态文本时才画这一行——两者都没有时不留一条空行。
            if (meta.isNotBlank() || onAutoNextChange != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (onAutoNextChange != null) {
                        Text(
                            text = "连续播放",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                        Switch(
                            checked = autoNext,
                            onCheckedChange = onAutoNextChange,
                            modifier = Modifier.padding(start = 6.dp).scale(0.82f),
                        )
                    }
                }
            }
            if (waitingStart) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "点封面上的播放键，或点下面某一集开始",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
            // 合并后的内容页：简介、演职员、选集都挂在这里，播放器本身不再重复一套
            info()
            // 选集：与详情页同一套格子。只有一条时不显示（"一集"没有可选的东西），
            // 全屏里也不显示（上面两个全屏分支都 return 了，走不到这里）
            if (episodeGroup != null && episodeGroup.tracks.size > 1) {
                Spacer(Modifier.height(18.dp))
                EpisodeGrid(group = episodeGroup, current = episodeIndex, onPick = onPickEpisode)
            }
            state.error?.let { err ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "[${err.code}] ${err.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRetry) { Text("重试") }
            }
            if (!playable && !waitingStart) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = capabilities.unavailableReason ?: "这一端暂不支持播放该地址",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
                if (state.status == PlayerStatus.PREPARING) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            // 地址不再摊在页面上（几百个字符会挤走真正的操作），只留一个复制入口
            if (!url.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { onCopyUrl(url) }) {
                    Text("复制播放地址")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        if (controlsBelowSurface) {
            // 桌面端：画面 + 控制条钉在顶部不动，只有下面的信息区滚动。
            // 这样滚动事件不会穿过 SwingPanel，heavyweight 画面不会被反复改写位置/尺寸。
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    surfaceBlock()
                    controlsBlock()
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(), content = infoBlock)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                surfaceBlock()
                // 窗口态：倍速/线路/全屏放在画面正下方一行（右对齐）。
                // 为什么不再叠在画面里：这三颗是"设置类"入口，放在叠层顶部时和画面边角挤在一起、
                // 手指也容易点空；窗口态画面下面本来就有空位，放这儿常驻可见，叠层只留 TV 那套五键。
                if (playable && !controlsBelowSurface) {
                    Row(
                        modifier = Modifier.width(surfaceW),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ExtraControls(
                            fullscreen = false,
                            speed = speed,
                            showSpeed = capabilities.supportsSpeed,
                            onSpeedPick = {
                                speed = it
                                onSpeed(it)
                            },
                            lines = lines,
                            lineIndex = lineIndex,
                            onPickLine = onPickLine,
                            onFullscreen = { fs.value = true },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(), content = infoBlock)
            }
        }
    } // BoxWithConstraints
}

/**
 * 底部第一行：`当前时间 · 细进度条 · 总时长`。版式照 TV 版控制层——秒数和进度必须同在一行，
 * 用户看一眼就知道"现在到哪了、还剩多少"，不用再去桌面/详情页找读数。
 */
@Composable
private fun ProgressRow(
    state: PlayerState,
    onSeekRatio: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatClock(state.positionMs),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.9f),
        )
        ProgressLine(
            progress = state.progress,
            onSeekRatio = onSeekRatio,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(
            text = if (state.durationMs > 0L) formatClock(state.durationMs) else "--:--",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}

/**
 * 底部第二行（TV 版同款五键）：`<< −10s 播放/暂停 +10s >>`，**整行居中**。
 *
 * 与旧版最大的区别是"播放键从最左挪到正中"：TV 版就是这么排的，遥控器只有上下左右，
 * 居中才不用横跨整屏；手机沿用同一套位置，两端留给快退/快进与切集。
 * 上一集/下一集传 null 时按钮仍占位（只是点不动），换集时播放键不会左右跳。
 */
@Composable
private fun TransportRow(
    state: PlayerState,
    seekStepMs: Long,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    episodeNav: Boolean = false,
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (episodeNav) {
            TextButton(
                onClick = { onPrev?.invoke() },
                enabled = onPrev != null,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text = "<<",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = if (onPrev != null) 0.9f else 0.28f),
                )
            }
        }
        TextButton(
            onClick = { onSeek((state.positionMs - seekStepMs).coerceAtLeast(0L)) },
            enabled = state.durationMs > 0L,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text("−${seekStepMs / 1000}s", color = Color.White.copy(alpha = 0.9f))
        }
        // 播放/暂停：正中的圆钮。用字符而不是图标，避免为一个按钮引一套 icon 依赖
        Surface(
            onClick = onToggle,
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.18f),
            contentColor = Color.White,
            modifier = Modifier.padding(horizontal = 10.dp).size(44.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = when (state.status) {
                        PlayerStatus.PLAYING -> "Ⅱ"
                        PlayerStatus.PREPARING -> "…"
                        else -> "▶"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        TextButton(
            onClick = { onSeek(state.positionMs + seekStepMs) },
            enabled = state.durationMs > 0L,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text("+${seekStepMs / 1000}s", color = Color.White.copy(alpha = 0.9f))
        }
        if (episodeNav) {
            TextButton(
                onClick = { onNext?.invoke() },
                enabled = onNext != null,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text = ">>",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = if (onNext != null) 0.9f else 0.28f),
                )
            }
        }
    }
}

/**
 * 顶部（或桌面常驻条）右侧的次要入口：倍速 / 线路 / 全屏。
 *
 * 从底部五键里挪走的原因：TV 版的底行只放"播放相关"，这些设置类入口挤进去会破坏居中版式，
 * 手机宽度也不够（五键 240dp + 三个下拉 180dp 必然溢出）。放顶部右侧，跟标题同进同退。
 */
@Composable
private fun ExtraControls(
    fullscreen: Boolean = false,
    speed: Float = 1.0f,
    showSpeed: Boolean = false,
    onSpeedPick: (Float) -> Unit = {},
    lines: List<String> = emptyList(),
    lineIndex: Int = 0,
    onPickLine: (Int) -> Unit = {},
    onFullscreen: () -> Unit = {},
) {
    if (showSpeed) {
        SpeedMenu(current = speed, onPick = onSpeedPick)
    }
    // 线路下拉只在多线路 / 多播放列表时出现：单组时它是个点了必然会关掉自己的空菜单
    if (lines.size > 1) {
        LineMenu(lines = lines, current = lineIndex, onPick = onPickLine)
    }
    TextButton(onClick = onFullscreen, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
        Text(if (fullscreen) "退出" else "全屏", color = Color.White.copy(alpha = 0.9f))
    }
}

/** 状态行的短文案；顶部标题下面和页面状态行共用一套说法 */
private fun statusLabel(status: PlayerStatus): String = when (status) {
    PlayerStatus.PLAYING -> "播放中"
    PlayerStatus.PAUSED -> "已暂停"
    PlayerStatus.PREPARING -> "缓冲中"
    PlayerStatus.READY -> "就绪"
    PlayerStatus.ENDED -> "已结束"
    PlayerStatus.ERROR -> "出错"
    PlayerStatus.IDLE -> ""
}

/** 倍速下拉：0.5 / 1.0 / 1.5 / 2.0。只改播放速度，不改 UI 节奏，所以不碰任何其它 state */
@Composable
private fun SpeedMenu(current: Float, onPick: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("${current}x ▾", color = Color.White.copy(alpha = 0.9f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SPEED_STEPS.forEach { value ->
                val selected = abs(current - value) < 0.01f
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (selected) "${value}x ✓" else "${value}x",
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        onPick(value)
                        open = false
                    },
                )
            }
        }
    }
}

/** 与配置 `playback.player` 的可用档位保持一致，写死在这里是因为播放器档位本来就是产品决策 */
private val SPEED_STEPS = listOf(0.5f, 1.0f, 1.5f, 2.0f)

/**
 * 线路下拉：`线路1 / 线路2 …`，菜单里显示分组名（"线路二""高清"这类）。
 *
 * 为什么用"线路N"而不是直接显示组名：控制条上空间是共享的，长组名会把快进按钮挤出去；
 * 组名放在菜单里，点开就看全。
 */
@Composable
private fun LineMenu(lines: List<String>, current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("线路${current + 1} ▾", color = Color.White.copy(alpha = 0.9f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            lines.forEachIndexed { i, name ->
                val selected = i == current
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (selected) "$name ✓" else name,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        onPick(i)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * 播放页的选集格子：与详情页 `VideoGrid` 同一个形态（FlowRow、按内容自适应宽度、集名一行）。
 *
 * 只改了一处口径：这里**当前这一集必须一眼看得出来**（主色边框 + 主色底 + 主色字），
 * 详情页是"点进去才知道是哪集"，播放页是"我正看着哪集"。点的是当前集则不回调
 * （重复 prepare 会打断缓冲，用户看到的是画面闪一下）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeGrid(group: TrackGroup, current: Int, onPick: (Int) -> Unit) {
    Text(
        text = "${group.name} · 共 ${group.tracks.size} 项",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
    )
    Spacer(Modifier.height(10.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        group.tracks.forEachIndexed { i, track ->
            val active = i == current
            Surface(
                onClick = { if (!active) onPick(i) },
                shape = RoundedCornerShape(8.dp),
                color = if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Text(
                    text = track.title.ifBlank { "第${track.index}集" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 一条**只有颜色长度**的进度线（M6.5）：没有滑块、没有两端时间、没有刻度或竖线分隔，
 *  播得越多主色段越长。用户的说法是"不用加 |，直接控制颜色条长度就行"。
 *
 *  触摸区做成 14dp 高、视觉仍是 3dp —— 3dp 的线手指点不中。点哪跳哪，这是去掉控制条滑块之后
 *  位移动进度的主要入口（比只能 ±10s 实用）。 */
@Composable
private fun ProgressLine(
    progress: Float,
    onSeekRatio: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.Black.copy(alpha = 0.28f),
) {
    val ratio = progress.coerceIn(0f, 1f)
    // 手势检测只挂一次（key = Unit）：progress 每 250ms 刷一次、回调也是每次重组新建的 lambda，
    // 若拿它当 pointerInput 的 key，手指刚按下就可能因为 key 变化把这一次点击cancel掉。
    // 需要最新的回调值时用 rememberUpdatedState 取。
    val latestSeek by rememberUpdatedState(onSeekRatio)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        latestSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                    }
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(trackColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
            )
        }
    }
}

private fun formatClock(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }
}
