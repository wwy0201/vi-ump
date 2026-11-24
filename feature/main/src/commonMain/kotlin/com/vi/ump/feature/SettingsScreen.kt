package com.vi.ump.feature

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vi.ump.render.readableOn
import com.vi.ump.source.exportFileName
import com.vi.ump.source.maskForExport
import com.vi.ump.storage.SettingsKeys
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 设置页（M5 的骨架）。
 *
 * 现阶段只做两件事：
 *  1. 让"当前用哪个数据源"可见、可切；
 *  2. 把当前配置的体检结果（源数量 / 页面数量 / 校验问题）摊开。
 *
 * 导入 JSON、源注册表持久化、主题与导出在 M5 补。
 */
@Composable
fun SettingsScreen(
    /** 当前使用的源。**可以为 null**：没有任何源时用户第一件事就是进这里导入、备份、删除 */
    current: SourceEntry?,
    sources: List<SourceEntry>,
    session: UmpSession?,
    onSelect: (SourceEntry) -> Unit,
    modifier: Modifier = Modifier,
    actions: SourceActions = SourceActions(),
    /** 被停用的源 id：列表里仍然显示（可以再启用），首页下拉里不出现（M5.2） */
    disabledIds: Set<String> = emptySet(),
    onToggle: (id: String) -> Unit = {},
    /**
     * 源标识色（id → Color），与首页下拉共用同一份分配结果。
     * 已选择的源用它上色，其余一律灰——颜色回答的是"哪个在用"，不是装饰。
     */
    sourceColors: Map<String, Color> = emptyMap(),
    /**
     * 图片查看器的预加载张数：**当前张之后**预几张，0..[SettingsKeys.IMAGE_PRELOAD_MAX]，默认 3。
     * 例：设 3 → 看第 1 张时加载 1、预加载 2/3/4；看第 15 张时加载 15、预加载 16/17/18。
     * 值落 `SettingsKeys.IMAGE_PRELOAD`，改完立刻对下一次进图集生效。
     */
    imagePreload: Int = SettingsKeys.IMAGE_PRELOAD_DEFAULT,
    onImagePreloadChange: (Int) -> Unit = {},
    /**
     * 手机版音频是否允许后台播放（默认开，保持既有行为）。
     *
     * 关闭后：App 退到后台（Home / 锁屏 / 最近任务）暂停音乐并撤掉通知栏；
     * 前台播放、页面内切歌不受影响。桌面端没有音频后台服务，不显示这一项。
     */
    audioBackgroundSupported: Boolean = false,
    audioBackground: Boolean = true,
    onAudioBackgroundChange: (Boolean) -> Unit = {},
    /**
     * 进入视频内容页后是否自动起播。默认关；手机/桌面都可改，TV 端没有这项设置（固定自动播放）。
     */
    autoPlay: Boolean = false,
    onAutoPlayChange: (Boolean) -> Unit = {},
) {
    // 待确认的删除对象：弹窗期间源列表可能刷新，所以存 id 而不是整个 entry
    var pendingDelete by remember { mutableStateOf<SourceEntry?>(null) }
    // 正在备份的源 id：备份要走文件选择/写文件，点一次只能有一次动作
    var backupBusyId by remember { mutableStateOf<String?>(null) }
    // 粘贴导入（M5）：文本与错误都留在对话框里，改完能直接重试
    var pasteOpen by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    // URL 拉取导入：网络在壳层挂起执行，这里只管"拉取中"与错误回显
    var urlOpen by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var urlBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle("数据源", modifier = Modifier.weight(1f))
                    TextButton(onClick = { urlOpen = true }) { Text("URL") }
                    TextButton(onClick = { pasteOpen = true }) { Text("粘贴") }
                    TextButton(onClick = actions.onImport) { Text("导入") }
                }
            }
            // 导入结果就地显示在栏目下面：成功/失败都要有一句话，不能"点了没反应"
            actions.message?.let { msg ->
                item {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (actions.messageIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            items(sources, key = { it.id }) { entry ->
                val selected = entry.id == current?.id
                SourceRow(
                    entry = entry,
                    selected = selected,
                    disabled = entry.id in disabledIds,
                    color = sourceColors[entry.id]?.readableOn(MaterialTheme.colorScheme.background),
                    onClick = { if (!selected) onSelect(entry) },
                    // 使用中的源不给停用（停掉自己首页就空了）
                    onToggle = if (!selected) ({ onToggle(entry.id) }) else null,
                    // 使用中的源不给删除（删了当前页就空了，要删先切走）
                    onDelete = if (!selected) {
                        { pendingDelete = entry }
                    } else null,
                    // 备份每一项都给——它和"能不能删"无关，只是把配置另存到卸载也不清的地方。
                    // 结果（含"请先选目录"）由壳层写进 actions.message，界面这里只负责防重复点击
                    onBackup = actions.onBackup?.let { backup ->
                        {
                            if (backupBusyId == null) {
                                backupBusyId = entry.id
                                scope.launch {
                                    runCatching { backup(entry) }
                                    backupBusyId = null
                                }
                            }
                        }
                    },
                    backupBusy = backupBusyId == entry.id,
                )
            }

            // 「图片阅读」（2026-11-18 用户要求）：预加载张数可调。
            // 放这里而不是塞进「当前数据源」：它是用户级全局设置，与具体源无关。
            item {
                Spacer(Modifier.height(20.dp))
                SectionTitle("图片阅读")
            }
            item {
                ImagePreloadRow(value = imagePreload, onChange = onImagePreloadChange)
            }

            // 「播放」：默认自动播放（手机/桌面都显示）；音频后台播放只在有 Service 的端显示。
            item {
                Spacer(Modifier.height(20.dp))
                SectionTitle("播放")
            }
            item {
                AutoPlayRow(value = autoPlay, onChange = onAutoPlayChange)
            }
            if (audioBackgroundSupported) {
                item {
                    AudioBackgroundRow(value = audioBackground, onChange = onAudioBackgroundChange)
                }
            }

            // 「当前数据源」：没有源的时候整段不出现（否则是一排"名称 / 标识"后面跟着空值）
            val shown: SourceEntry? = current
            if (shown != null) {
                item {
                    Spacer(Modifier.height(20.dp))
                    SectionTitle("当前数据源")
                }
                item { InfoRow("名称", shown.label) }
                item { InfoRow("标识", shown.id) }
                if (session != null) {
                    val cfg = session.schema.config
                    item { InfoRow("站点", cfg.runtime.baseUrl) }
                    item { InfoRow("版本", cfg.meta.version?.takeIf { it.isNotBlank() } ?: "-") }
                    item {
                        InfoRow(
                            "资源",
                            "列表 ${cfg.resources.feeds.size} 个 · 详情 ${cfg.resources.details.size} 个",
                        )
                    }
                    item { InfoRow("页面", "${cfg.pages.size} 个") }
                }
                // 导出（M5.5）：走脱敏，导出的文件可以原样再导入回来
                actions.onExport?.let { export ->
                    item {
                        TextButton(
                            onClick = {
                                export(maskForExport(shown.json), exportFileName(shown.id))
                            },
                        ) { Text("导出配置（已脱敏）") }
                    }
                }
            }

            item {
                Spacer(Modifier.height(20.dp))
                SectionTitle("配置体检")
            }
            val issues = session?.schema?.issues.orEmpty()
            if (session == null) {
                item { InfoRow("结果", "还没有数据源，导入一份配置再来体检") }
            } else if (issues.isEmpty()) {
                item { InfoRow("结果", "无问题（0 条）") }
            } else {
                items(issues.size) { index ->
                    val issue = issues[index]
                    Text(
                        text = "[${issue.code.code}] ${issue.path}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                    Text(
                        text = issue.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "导入：选一份站点配置 JSON 文件（系统文件选择器，不要存储权限）；粘贴：直接贴 JSON 文本；URL：填配置地址在线拉取。重复导入同一 id = 覆盖更新。\n" +
                        "备份：每行的「备份」把原始配置另存一份到卸载也不清的地方（首次选一次目录，之后一直写它），可原样再导入。\n" +
                        "删除：非当前源左滑到底、松手确认即可删除（当前源删了首页就没得播，先切走）。\n" +
                        "停用/启用：停用后首页下拉不再出现（配置仍在，随时可再启用）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // 删除确认：破坏性动作必须有一次明确确认，且文案里带源名，避免删错
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除数据源") },
            text = { Text("确定删除「${entry.label}」吗？\n配置文件会一并删除，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(entry.id)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // 粘贴导入：不经过文件管理器，校验失败就地把原因留在对话框里
    if (pasteOpen) {
        AlertDialog(
            onDismissRequest = { pasteOpen = false },
            title = { Text("粘贴配置 JSON") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it; pasteError = null },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        placeholder = { Text("把站点配置 JSON 粘贴到这里") },
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    pasteError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = pasteText.trim()
                    if (text.isEmpty()) {
                        pasteError = "内容为空：先粘贴一段配置 JSON"
                    } else {
                        val error = actions.onImportText(text)
                        if (error == null) {
                            pasteOpen = false
                            pasteText = ""
                            pasteError = null
                        } else {
                            pasteError = error
                        }
                    }
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pasteOpen = false }) { Text("取消") }
            },
        )
    }

    // URL 拉取导入：拉取中禁用按钮，避免重复点出多个并发请求
    if (urlOpen) {
        AlertDialog(
            onDismissRequest = { if (!urlBusy) urlOpen = false },
            title = { Text("从 URL 导入配置") },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it; urlError = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://example.com/source.json") },
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    when {
                        urlBusy -> Text(
                            text = "正在拉取，请稍候…",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        urlError != null -> Text(
                            text = urlError!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !urlBusy,
                    onClick = {
                        val url = urlText.trim()
                        if (url.isEmpty()) {
                            urlError = "先填一个配置地址"
                        } else {
                            scope.launch {
                                urlBusy = true
                                urlError = null
                                val error = actions.onImportUrl(url)
                                urlBusy = false
                                if (error == null) {
                                    urlOpen = false
                                    urlText = ""
                                } else {
                                    urlError = error
                                }
                            }
                        }
                    },
                ) { Text(if (urlBusy) "拉取中…" else "拉取导入") }
            },
            dismissButton = {
                TextButton(enabled = !urlBusy, onClick = { urlOpen = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun SourceRow(
    entry: SourceEntry,
    selected: Boolean,
    disabled: Boolean,
    /** 该源的标识色（已选择时用它，其余灰色）；null 表示没有可用色 */
    color: Color? = null,
    onClick: () -> Unit,
    onToggle: (() -> Unit)? = null,
    /** 非 null 时这一行可以**左滑删除**（滑到位松手弹确认）；当前源与内置源为 null，不可滑 */
    onDelete: (() -> Unit)? = null,
    /** 备份（M5.9）：非 null 才显示「备份」，显示在「停用/启用」左侧 */
    onBackup: (() -> Unit)? = null,
    backupBusy: Boolean = false,
) {
    // 源名 + 操作挤在**一行**里。右侧操作位是**固定列宽**的：源名长短只影响它自己，
    // 不会把按钮推来推去（第一版用权重把按钮挤成锯齿状，一屏里没有一列是对齐的）。
    //
    // 点击范围只给**源名**，不整行 clickable：一行几个热区挨在一起，整行可点时
    // 想点按钮却点到行空白就会直接切源（实测把当前源误切成了腾讯视频），切源会重置整页状态。
    //
    // 删除改成了**左滑**：一行里再塞一个"删除"就要挤到源名只剩四五个字，而且删除是低频、
    // 有风险的动作，藏到手势后面更合理；滑到 112dp 松手才弹确认，滑不够只会回弹。
    val swipeEnabled = onDelete != null
    // 必须用**同步**的状态存位移：`Animatable.snapTo` 是挂起函数，只能在 drag 回调里 launch，
    // 每帧一个协程 → 位移滞后于手指，松手时判到的还是 0（"滑了没反应"就是这么来的）。
    var dragX by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { 112.dp.toPx() }

    Box(modifier = Modifier.fillMaxWidth().height(44.dp)) {
        // 底层：左滑后露出来的红色提示条
        if (swipeEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "左滑删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .offset { IntOffset(dragX.roundToInt(), 0) }
                // 盖住底下的红色提示：整行底色必须和页面一致，否则没滑动时也透出红
                .background(MaterialTheme.colorScheme.background)
                .then(
                    if (swipeEnabled) {
                        Modifier.pointerInput(entry.id) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, drag ->
                                    change.consume()
                                    dragX = (dragX + drag).coerceIn(-thresholdPx * 1.4f, 0f)
                                },
                                onDragEnd = {
                                    // 滑过阈值才认：滑不够只是"看了一眼"，回弹复位
                                    if (dragX <= -thresholdPx) onDelete?.invoke()
                                    val from = dragX
                                    scope.launch {
                                        animate(from, 0f) { value, _ -> dragX = value }
                                    }
                                },
                                onDragCancel = {
                                    val from = dragX
                                    scope.launch {
                                        animate(from, 0f) { value, _ -> dragX = value }
                                    }
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyMedium,
                // 已选择 = 该源自己的标识色（和首页下拉一致）；待选/停用一律灰
                color = when {
                    disabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    selected -> color ?: MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(onClick = onClick)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
            // 状态位固定 48dp：三态都要占位（已选择 / 待选 / 停用），否则整列又歪了
            Box(modifier = Modifier.width(48.dp)) {
                val state = when {
                    selected -> "已选择"
                    disabled -> "停用"
                    else -> "待选"
                }
                Text(
                    text = state,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        selected -> MaterialTheme.colorScheme.primary
                        disabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    },
                )
            }
            // 备份（M5.9）：每一项都有，位置在「停用/启用」左侧
            if (onBackup != null) {
                RowAction(
                    text = if (backupBusy) "备份中" else "备份",
                    width = 48.dp,
                    enabled = !backupBusy,
                    onClick = onBackup,
                )
            } else {
                Spacer(Modifier.width(48.dp))
            }
            // 使用中的源不给停用——**留等宽空位**，让每一列仍然对齐
            if (onToggle != null) {
                RowAction(if (disabled) "启用" else "停用", width = 48.dp, onClick = onToggle)
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
    }
}

/** 行内小按钮：固定宽度、零内边距，只为了让四列在每行都对齐 */
@Composable
private fun RowAction(
    text: String,
    width: Dp,
    enabled: Boolean = true,
    errorColor: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.width(width).fillMaxHeight(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (errorColor) MaterialTheme.colorScheme.error else Color.Unspecified,
            maxLines = 1,
        )
    }
}

/**
 * 「默认自动播放」开关（2026-11-20）。
 *
 * 默认关：进入视频内容页先停在封面/简介/选集，用户点播放或点某一集才起播；
 * 开启后进入内容页自动从第一集起播。TV 端没有设置页，固定自动播放。
 */
@Composable
private fun AutoPlayRow(value: Boolean, onChange: (Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "默认自动播放",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = value, onCheckedChange = onChange)
        }
        Text(
            text = if (value) {
                "开启：进入视频内容页后自动从第一集开始播放"
            } else {
                "关闭：先停在封面和选集，点播放或点某一集再开始"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

/**
 * 手机版「音乐后台播放」开关。
 *
 * 默认开：保持 M4.5 的原有行为（退后台/锁屏继续播 + 常驻通知栏）。
 * 关闭：Activity `onStop` 时暂停共享音频实例并停掉前台服务/通知；回到前台再点播放会恢复。
 * 文案把"只影响退后台"写清楚，避免被理解成"关掉后音频页也不能放"。
 */
@Composable
private fun AudioBackgroundRow(value: Boolean, onChange: (Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "音乐后台播放",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = value, onCheckedChange = onChange)
        }
        Text(
            text = if (value) {
                "开启：按 Home / 锁屏后音乐继续播放，通知栏可控制"
            } else {
                "关闭：退到后台自动暂停音乐并撤掉通知栏；回到 App 后需手动继续"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

/**
 * 图片预加载张数（2026-11-18，口径由用户确认）。
 *
 * 语义：**当前张之后**预加载 N 张。设 3 → 看第 1 张时加载 1、预加载 2/3/4；
 * 打开第 15 张时加载 15、预加载 16/17/18。回看靠已取到的直链 + 图片缓存，不再往前预留。
 *
 * 用 `− / +` 步进而不是滑杆：候选只有 0~8 这几个整数，滑杆在这个区间里既难点准、也看不出"当前是几"。
 * 副文案把"当前 / 之后"写清楚，因为"预加载 3 张"最容易误解成"每边各 3 张"。
 */
@Composable
private fun ImagePreloadRow(value: Int, onChange: (Int) -> Unit) {
    val v = value.coerceIn(0, SettingsKeys.IMAGE_PRELOAD_MAX)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "预加载张数",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onChange((v - 1).coerceAtLeast(0)) }, enabled = v > 0) { Text("−") }
            Text(
                text = "$v",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(28.dp),
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = { onChange((v + 1).coerceAtMost(SettingsKeys.IMAGE_PRELOAD_MAX)) },
                enabled = v < SettingsKeys.IMAGE_PRELOAD_MAX,
            ) { Text("+") }
        }
        Text(
            text = if (v == 0) {
                "当前张之后不预加载：滑到下一张才开始取址（省流量，会先看到加载提示）"
            } else {
                "当前张之后预加载 $v 张：看第 1 张时加载 1、预加载 2–${v + 1}；" +
                    "打开第 15 张时加载 15、预加载 16–${15 + v}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
