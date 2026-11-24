package com.vi.ump.feature

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vi.ump.model.LayoutType
import com.vi.ump.model.MediaKind
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PlayTarget
import com.vi.ump.model.ThemeSpec
import com.vi.ump.player.AudioControlBus
import com.vi.ump.render.ImageViewer
import com.vi.ump.render.UmpImageDecrypt
import com.vi.ump.render.UmpTheme
import com.vi.ump.source.createHttpClient
import com.vi.ump.storage.SettingsKeys
import com.vi.ump.storage.createAppSettings
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App 根节点。**全程在线取数**，没有任何离线假数据：
 *
 * ```
 * 数据源列表 → ConfigCompiler（L1→L3）→ SourcePipeline（HttpFetcher）→ render
 * ```
 *
 * 四个视图共用一个顶栏（首页 / 详情 / 播放 / 设置），数据源由壳层注入：
 * 桌面端扫仓库 `samples` 目录里的站点配置，Android 端等 M5 的导入入口。
 */
@Composable
fun AppRoot(
    sources: List<SourceEntry> = emptyList(),
    /**
     * 画面实现由 app 壳层注入（`(renderToken, modifier) -> Unit`）。
     * 默认空实现：桌面端在 M7 接 VLCJ 之前就是没有画面，但播放页本身照常可用。
     */
    playerSurface: @Composable (Any?, Modifier) -> Unit = { _, _ -> },
    /**
     * 控制条摆放。桌面端传 true（VLC 的画面是 heavyweight `Canvas`，会盖住叠在画面里的控件），
     * 手机端保持默认 false（叠层 + 静置 3 秒自动隐藏）。
     */
    controlsBelowSurface: Boolean = false,
    /**
     * 系统返回键接管（Android 壳层注入 `BackHandler`）。桌面端留空实现，Esc 以后再接。
     *
     * 参数含义：`enabled` = 当前有没有可回退的层级（首页时为 false，此时让系统把 App 退到后台/退出）；
     * `action` = 回退动作，顺序固定为 播放 → 设置 → 详情 → 首页。
     */
    backDispatcher: @Composable (enabled: Boolean, action: () -> Unit) -> Unit = { _, _ -> },
    /**
     * 全屏状态外抛（Android 壳层据此隐藏/恢复系统状态栏与导航栏）。
     *
     * 为什么必须由壳层做：隐藏系统栏是 Window 级 API，`render`/`feature` 都是 commonMain，
     * 拿不到 Activity 的 window。桌面端留空实现即可——那边的"全屏"只是窗口内铺满。
     */
    onFullscreenChange: (Boolean) -> Unit = {},
    /** 设置页的导入/删除（M5）；壳层负责文件选择器与落盘 */
    sourceActions: SourceActions = SourceActions(),
    /**
     * 这一端是否有"音频后台播放"能力（手机版 true）。
     * 桌面端没有前台服务，设置页不显示这个开关；默认 false 避免摆一个无效项。
     */
    audioBackgroundSupported: Boolean = false,
) {
    val catalog = sources
    // "上次选的源"要跨进程活着：卸载才清（SharedPreferences / java.util.prefs）。
    // 若记下来的 id 不在当前 catalog 里（配置被换掉），下面的 `current` 会回退到第一个。
    val settings = remember { createAppSettings() }
    // ---- 搜索历史 / 观看历史（2026-11-18）----
    // 按源存、按源展示；点历史只"打开那一条"，顶栏的源不动（见下方 `foreignSession`）。
    var searchHistory by remember { mutableStateOf(emptyList<String>()) }
    var historyOpen by remember { mutableStateOf(false) }
    var historyGroups by remember { mutableStateOf(emptyList<HistoryGroup>()) }
    var historyBusy by remember { mutableStateOf(false) }
    // 正在打开哪一条（标题给遮罩文案用）。historyBusy 只能回答"忙不忙"，
    // 用户点的是哪一条历史、正在等什么，需要单独留一份。
    var historyOpening by remember { mutableStateOf<HistoryStore.WatchItem?>(null) }
    var historyError by remember { mutableStateOf<String?>(null) }
    // 历史条目可能属于别的源：给它单独建一个 session，只服务详情/播放/查看器。
    // 用"有没有这个 session"来判断当前处在跨源视图，而不是靠 id 比较——两者必须同时成立，
    // 否则会出现"foreignId 指过去了、session 却没建起来"的半截状态。
    var foreignId by remember { mutableStateOf<String?>(null) }
    var foreignSession by remember { mutableStateOf<UmpSession?>(null) }
    // 停用（M5.2）：只影响"首页下拉列不列它"，源本身还是壳层那一份。
    // 顺序不再有手动排序（设置页的 ↑/↓ 已按用户要求删除）：显示顺序 = 导入顺序（壳层目录里的顺序），
    // 设置页再把它们按状态分组。
    var disabledIds by remember { mutableStateOf(settings.getString(SettingsKeys.SOURCE_DISABLED).idsOrEmpty().toSet()) }
    // 连续播放（2026-11-16）：**用户的全局设置，默认关**。以前是读配置 `playback.player.autoNext`
    // （默认开），用户要求"默认不自动连播、由用户设置、全局有效"，所以状态提到这一层并落盘：
    // 换源/换集/杀进程重开都保持，视频页与音频页共用同一个值。
    var autoNext by remember { mutableStateOf(settings.getString(SettingsKeys.PLAYER_AUTO_NEXT) == "1") }
    val setAutoNext: (Boolean) -> Unit = { on ->
        autoNext = on
        settings.putString(SettingsKeys.PLAYER_AUTO_NEXT, if (on) "1" else "0")
    }
    // 默认自动播放（2026-11-20）：进入视频内容页是否直接起播，默认关。
    // 与连播一样是用户级全局设置；TV 端不走 AppRoot，固定自动播放。
    var autoPlay by remember { mutableStateOf(settings.getString(SettingsKeys.PLAYER_AUTO_PLAY) == "1") }
    val setAutoPlay: (Boolean) -> Unit = { on ->
        autoPlay = on
        settings.putString(SettingsKeys.PLAYER_AUTO_PLAY, if (on) "1" else "0")
    }
    // 音乐后台播放（2026-11-18）：用户级全局开关，默认开（保持 M4.5 的原行为）。
    // 关闭后 Android 的 Activity onStop 会暂停音频并撤通知；这里只负责读/存与把策略写给
    // core:player 的纯 Kotlin 总线，不直接碰 Android 生命周期。
    var audioBackground by remember {
        mutableStateOf(settings.getString(SettingsKeys.PLAYER_AUDIO_BACKGROUND) != "0")
    }
    val setAudioBackground: (Boolean) -> Unit = { on ->
        audioBackground = on
        settings.putString(SettingsKeys.PLAYER_AUDIO_BACKGROUND, if (on) "1" else "0")
        // 立刻生效：关掉时若正在后台播，壳层会撤通知；打开时若音频正在播，重新拉起服务。
        AudioControlBus.backgroundPlay = on
        AudioControlBus.onBackgroundPlayChanged?.invoke(on)
    }
    LaunchedEffect(audioBackground) {
        AudioControlBus.backgroundPlay = audioBackground
        // 冷启动时也让壳层按当前设置整理一次服务（关闭状态不应残留上次的通知）。
        AudioControlBus.onBackgroundPlayChanged?.invoke(audioBackground)
    }
    // 图片预加载张数（2026-11-18，口径由用户确认）：`image.preload` = **当前张之后**预加载几张，默认 3。
    // 例：3 → 看第 1 张时加载 1、预加载 2/3/4；看第 15 张时加载 15、预加载 16/17/18。与其他全局设置一样落盘。
    var imagePreload by remember {
        mutableStateOf(
            (settings.getString(SettingsKeys.IMAGE_PRELOAD)?.toIntOrNull() ?: SettingsKeys.IMAGE_PRELOAD_DEFAULT)
                .coerceIn(0, SettingsKeys.IMAGE_PRELOAD_MAX)
        )
    }
    val setImagePreload: (Int) -> Unit = { n ->
        val v = n.coerceIn(0, SettingsKeys.IMAGE_PRELOAD_MAX)
        imagePreload = v
        settings.putString(SettingsKeys.IMAGE_PRELOAD, v.toString())
    }
    val enabled: List<SourceEntry> = remember(catalog, disabledIds) { catalog.filter { it.id !in disabledIds } }
    var currentId by remember {
        mutableStateOf(
            settings.getString(SettingsKeys.SOURCE_ID)?.takeIf { id -> catalog.any { it.id == id } }
                ?: catalog.firstOrNull()?.id,
        )
    }
    // 设置页按**状态**分组显示：已选择 > 待选 > 停用；组内保持导入顺序（sortedBy 是稳定排序）。
    // 首页下拉仍用 enabled，不受这个分组影响——下拉里本来就只有启用的源。
    val settingsSources: List<SourceEntry> = remember(catalog, currentId, disabledIds) {
        catalog.sortedBy { entry ->
            when {
                entry.id == currentId -> 0
                entry.id in disabledIds -> 2
                else -> 1
            }
        }
    }
    // 一个源都没有时直接打开设置页：导入是唯一出路，留在首页只能看到一句"还没有数据源"
    var settingsOpen by remember { mutableStateOf(catalog.isEmpty()) }
    // 「切换数据源」面板（2026-11-24）：首页加载失败/空结果时给的就地出口，
    // 也是"配置坏到建不出 session"那一屏的唯一出路（没有面板的话只能靠顶栏下拉，源架不住时更难找）
    var sourcePickerOpen by remember { mutableStateOf(false) }
    // 搜索页（当前源内搜索）：入口在顶栏，条件是 flags.enableSearch && 有 resources.search
    var searchOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MediaSummary?>(null) }
    // 视频内容页的"预选集"：从历史点进来时定位到那一集，但默认不自动起播。
    // 普通点卡片为 null；换源/返回时一起清。
    var selectedInitialTarget by remember { mutableStateOf<PlayTarget?>(null) }
    // 播放目标带着"这一集 + 全部分组"：自动连播与线路切换都靠它，见 `PlayTarget`
    var playing by remember { mutableStateOf<PlayTarget?>(null) }
    // 图片查看器（M4.6）也用同一个结构：当前第几张 + 整组（左右翻页要靠整组）
    var viewing by remember { mutableStateOf<PlayTarget?>(null) }
    // 全屏要由这一层持有：只有这里能决定要不要收起顶栏（真正"铺满"）。
    // 播放路由仍负责"全屏时先拦返回键"，两边读写的是同一个状态。
    val fullscreenState = remember { mutableStateOf(false) }
    // 播放页往下滚过一段（内容离开顶部）时把顶栏收起来：一屏放不下"画面 + 标题 + 选集"，
    // 下滑着看选集时顶栏还占 112px 就是白占（用户反馈"下滑时最上面的组件没隐藏"）。
    // 只在播放页生效：首页/详情的顶栏是页面标题与返回，滚走反而找不到路。
    var playerScrolled by remember { mutableStateOf(false) }
    // 离开播放页（返回 / 点开别的条目）时把顶栏恢复：否则下次进播放页会停在"收了顶栏"的样子
    LaunchedEffect(selected?.id, playing?.summary?.id) {
        fullscreenState.value = false
        playerScrolled = false
    }
    // 全屏不只收顶栏，还要把系统栏一起收起来：Activity 层藏状态栏/导航栏
    LaunchedEffect(fullscreenState.value) { onFullscreenChange(fullscreenState.value) }

    // 通知栏"退出"（M4.5）：服务够不到 Compose 状态，只发自增事件，由这一层统一退出音频页。
    // 用计数而不是 Boolean：连续两次退出也能各收到一次
    LaunchedEffect(Unit) {
        AudioControlBus.exitEvents.collect { if (it > 0L) playing = null }
    }

    val current = enabled.firstOrNull { it.id == currentId } ?: enabled.firstOrNull()

    // 源标识色：显式配置的照用，没配的自动分配且互不相同（色相至少差 40°）。
    // 一次算全表，因为"不与已有色重复"是全局约束
    val sourceColors: Map<String, Color> = remember(catalog) { SourcePalette.assign(catalog) }

    // 一个 HttpClient 复用给所有数据源：切源不该重建连接池
    val client: HttpClient = remember { createHttpClient() }
    val loaded = remember(current, client) {
        current?.let { runCatching { UmpSession.of(it, client) } }
    }
    val session = loaded?.getOrNull()

    // 当前**实际在用的**会话：平时就是顶栏选的那个源；点开别源的历史时临时换成那条源的会话，
    // 顶栏的 `current` 与 `currentId` 一概不动（用户的硬要求：换源必须自己动手）。
    val activeSession = foreignSession ?: session
    val activeSourceId = if (foreignSession != null) foreignId ?: currentId else currentId

    val baseTheme = session?.schema?.config?.theme ?: ThemeSpec()
    // 整页主色也跟着源走：作者没显式配 theme.primary（还是默认色）时，用该源的标识色兜底，
    // 这样"切源"在配色上也是看得见的；显式配过就尊重作者的，不覆盖
    val theme = current?.let { sourceColors[it.id] }?.let { color ->
        if (baseTheme.primary.equals(ThemeSpec().primary, ignoreCase = true)) {
            baseTheme.copy(primary = color.toHex())
        } else {
            baseTheme
        }
    } ?: baseTheme

    // 图片解密（docs/03 §3.2）：把**当前在用的源**的解码规则交给图片通路。
    // 用 activeSession 而不是 session：跨源看历史图片时，规则必须跟着那条源走。
    LaunchedEffect(activeSession) {
        val runtime = activeSession?.schema?.config?.runtime
        if (runtime?.imageDecrypt != null) {
            UmpImageDecrypt.install(runtime.imageDecrypt, runtime.headers)
        } else {
            UmpImageDecrypt.clear()
        }
    }

    fun selectSource(entry: SourceEntry) {
        if (entry.id != currentId) {
            // 换源必须立刻闭嘴：通知栏常驻的是上一个源的音频，回调也指着旧路由
            AudioControlBus.requestStop()
            currentId = entry.id
            settings.putString(SettingsKeys.SOURCE_ID, entry.id)
            selected = null
            selectedInitialTarget = null
            playing = null
            viewing = null
            // 手动换源 = 结束跨源视图，历史打开的那份临时会话要丢掉
            foreignId = null
            foreignSession = null
        }
    }

    /**
     * 启用/停用（M5.2）。停用的源只是从首页下拉里消失，配置还在，随时能再启用。
     * 兜底：万一停掉的正好是当前源（UI 已禁止，但状态可能来自旧版本的存储），切到第一个还启用的。
     */
    fun toggleSource(id: String) {
        val next = if (id in disabledIds) disabledIds - id else disabledIds + id
        disabledIds = next
        settings.putString(SettingsKeys.SOURCE_DISABLED, next.joinToString("\n"))
        if (id == currentId && id in next) {
            catalog.firstOrNull { it.id !in next }?.let { fallback ->
                currentId = fallback.id
                settings.putString(SettingsKeys.SOURCE_ID, fallback.id)
                selected = null
                selectedInitialTarget = null
                playing = null
                viewing = null
            }
        }
    }

    /**
     * 搜索入口是否可用：`flags.enableSearch` 开关 + `resources.search` 至少一条。
     *
     * 只看开关是危险的：写了 `"enableSearch": true` 却没有任何 search 资源，点进去只会
     * 一屏"这个源没有配置搜索"。入口是否存在由数据决定，而不是由愿望决定。
     */
    val canSearch: Boolean = session?.let {
        it.schema.config.flags.enableSearch && it.schema.search.isNotEmpty()
    } ?: false

    // 搜索页的状态机：**由这一层持有**，否则从结果点进详情再返回，关键词与结果全丢
    val searchController = remember(session) { session?.let { SearchController(it) } }
    val scope = rememberCoroutineScope()

    // ---- 历史记录的读取与写入 ----

    fun refreshSearchHistory() {
        searchHistory = activeSourceId?.let { HistoryStore.searchHistory(settings, it) }.orEmpty()
    }

    /** 组装历史页：一个有记录的源一组，组内新的在前、组间按最近一次更新排 */
    fun refreshHistory() {
        historyGroups = HistoryStore.sourcesWithHistory(settings).mapNotNull { sid ->
            // 源被删掉后配置读不到，旧记录没法打开——不显示，避免点了报错
            val entry = catalog.firstOrNull { it.id == sid } ?: return@mapNotNull null
            val items = HistoryStore.watch(settings, sid).sortedByDescending { it.updatedAt }
            if (items.isEmpty()) null else HistoryGroup(sid, entry.label, items)
        }.sortedByDescending { group -> group.items.firstOrNull()?.updatedAt ?: 0L }
    }

    /** 进入/切换条目时记一条：只看"看到第几张/第几集"，播放位置仍由 ProgressStore 负责 */
    fun recordWatch(
        summary: MediaSummary,
        groupName: String?,
        episode: Int,
        trackIndex: Int,
        sourceId: String? = activeSourceId,
    ) {
        val sid = sourceId ?: return
        HistoryStore.record(
            settings,
            HistoryStore.WatchItem(
                sourceId = sid,
                itemId = summary.id,
                title = summary.title,
                poster = summary.poster,
                kind = summary.kind.key,
                groupName = groupName,
                episode = episode,
                trackIndex = trackIndex,
                detailResource = summary.detailResource,
            ),
        )
        if (historyOpen) refreshHistory()
    }

    /**
     * 点历史条目：直接进"看到的那一张/那一集"，**不改顶栏的源**。
     *
     * 需要先取一次详情：查看器与播放器都要分组（图集要整串 id、视频要选集），
     * 这些只在详情响应里。取不到就退回详情页，让详情页自己报错，而不是把用户留在列表里没反应。
     */
    fun openHistory(item: HistoryStore.WatchItem) {
        if (historyBusy) return
        val entry = catalog.firstOrNull { it.id == item.sourceId }
        if (entry == null) {
            historyError = "这个源已经不在配置里了"
            return
        }
        historyBusy = true
        historyOpening = item
        historyError = null
        scope.launch {
            try {
                // 会话只建一次：详情与后续的查看器/播放器必须用**同一个** session，
                // 建两次会出现"详情取到了、临时会话却没建起来"的半截状态（查看器会用错源的配置）
                val s = runCatching {
                    if (entry.id == currentId) session else UmpSession.of(entry, client)
                }.getOrNull()
                if (s == null) {
                    historyError = "这个源加载不起来"
                    return@launch
                }

                val result = runCatching {
                    val page = s.schema.pages
                        .firstOrNull { it.layout.type == LayoutType.DETAIL && it.layout.resource == item.detailResource }
                        ?: s.schema.pages.firstOrNull { it.layout.type == LayoutType.DETAIL }
                    val key = item.detailResource ?: page?.layout?.resource ?: error("这个源没有详情页")
                    // 与 DetailRoute 同一套参数兜底，否则 {{p.channelId}} 这类会渲染成空串
                    val params = page?.params?.takeIf { it.isNotEmpty() }
                        ?: s.schema.pages.firstOrNull { it.layout.type != LayoutType.DETAIL }?.params.orEmpty()
                    s.pipeline.detail(key, item.itemId, params, page?.layout?.tracks)
                        ?: error("详情取不到")
                }

                val detail = result.getOrNull()
                if (detail == null) {
                    historyError = result.exceptionOrNull()?.message ?: "打不开这条记录"
                    return@launch
                }

                // 返回键在加载期间仍可用。用户已经离开历史页时，结果不应再把他拽进内容页。
                if (!historyOpen) return@launch

                // 只有真的取到内容才切到"跨源视图"：失败时顶栏与首页保持原样
                foreignId = if (entry.id != currentId) entry.id else null
                foreignSession = if (entry.id != currentId) s else null
                selected = detail.summary
                val groupIndex = detail.trackGroups.indexOfFirst { it.name == item.groupName }.coerceAtLeast(0)
                val group = detail.trackGroups.getOrNull(groupIndex)
                val trackIndex = group?.tracks?.indexOfFirst { it.index == item.episode }
                    ?.takeIf { it >= 0 } ?: item.trackIndex
                val target = PlayTarget(
                    summary = detail.summary,
                    groups = detail.trackGroups,
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                )
                if (detail.summary.kind == MediaKind.IMAGE) {
                    viewing = target
                } else if (detail.summary.kind == MediaKind.VIDEO) {
                    // 视频走合并后的内容页：预选看到的那一集，但默认不自动起播。
                    selectedInitialTarget = target
                } else {
                    playing = target
                }
                // 这里必须显式传条目自己的 sourceId：`activeSourceId` 是本次组合的旧值，跨源时还没更新
                recordWatch(detail.summary, group?.name, target.episode, target.trackIndex, entry.id)
                historyOpen = false
            } finally {
                // 无论成功、失败、取消，都撤掉遮罩；否则一次异常会把历史页永久卡在"正在打开"。
                historyBusy = false
                historyOpening = null
            }
        }
    }

    // 切源后搜索页要退回首页：新源的关键词不一样、甚至根本没有搜索资源
    LaunchedEffect(current?.id) {
        searchOpen = false
        // 搜索历史按源存，切源必须换一列词
        refreshSearchHistory()
        if (historyOpen) refreshHistory()
    }

    // 首屏/重搜：key 是"提交的词 + 第几次提交"，同一个词再按一次搜索也会重跑
    LaunchedEffect(searchController, searchController?.submitted, searchController?.requestId) {
        searchController?.load()
    }
    // 输入停顿自动搜（只有配置声明 debounceMs 才生效）
    LaunchedEffect(searchController?.query, searchController?.debounceMs) {
        val c = searchController ?: return@LaunchedEffect
        if (c.debounceMs <= 0L || c.query.isBlank()) return@LaunchedEffect
        delay(c.debounceMs)
        c.submit()
    }

    // 搜索历史：真搜过一次才记（输入过程中的半截词不记），按当前源存
    LaunchedEffect(searchController?.submitted) {
        val word = searchController?.submitted.orEmpty()
        if (word.isNotBlank() && currentId != null) {
            searchHistory = HistoryStore.addSearch(settings, currentId!!, word)
        }
    }
    LaunchedEffect(searchOpen) { if (searchOpen) refreshSearchHistory() }

    // 观看历史：进查看器/播放器、以及每次换张换集都更新"看到第几张/第几集"。
    // 只记位置，不记播放秒数——续播秒数仍由 ProgressStore 按 `源|条目|集号` 读写。
    LaunchedEffect(viewing?.summary?.id, viewing?.trackIndex) {
        val t = viewing ?: return@LaunchedEffect
        recordWatch(t.summary, t.group?.name, t.episode, t.trackIndex)
    }
    LaunchedEffect(playing?.summary?.id, playing?.groupIndex, playing?.trackIndex) {
        val t = playing ?: return@LaunchedEffect
        recordWatch(t.summary, t.group?.name, t.episode, t.trackIndex)
    }

    val onBack: (() -> Unit)? = when {
        viewing != null -> ({ viewing = null })
        playing != null -> ({ playing = null })
        // 历史是从首页进来的：返回就回首页，源保持原样
        historyOpen -> ({ historyOpen = false })
        settingsOpen -> ({ settingsOpen = false })
        // 详情在搜索之上：从搜索结果点进详情，返回应该回到那一次搜索（而不是直接回首页）
        // 离开详情即离开"跨源视图"（历史打开的成功路径会给它建会话，这里收尾）
        selected != null -> ({
            selected = null
            selectedInitialTarget = null
            foreignId = null
            foreignSession = null
        })
        searchOpen -> ({ searchOpen = false })
        else -> null
    }
    val topTitle: String? = when {
        playing != null -> playing?.summary?.title ?: "播放"
        historyOpen -> "历史"
        settingsOpen -> "设置"
        selected != null -> selected?.title
        searchOpen -> "搜索"
        else -> null
    }

    // 首页进入详情/播放/设置后，HomeRoute 会整体离开组合；只靠 remember 的滚动位置会一起丢。
    // SaveableStateHolder 把首页这一层子树的可保存状态（LazyGrid/LazyList 的 index+offset、
    // 当前栏目、网格/列表模式）按源 id 存住，返回首页时原样恢复。
    val homeStateHolder = rememberSaveableStateHolder()
    UmpTheme(theme = theme) {
        // 系统返回键接进同一套返回栈：首页时 enabled=false，交给系统退出 App
        backDispatcher(onBack != null) { onBack?.invoke() }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 全屏时把顶栏收起来：上面还挂一条 128px 的栏，"全屏"就不成立
                // 图片查看器同理：黑底看图不该有顶栏（它自己带一行返回 + 计数）
                // 播放页滚离顶部时也收：用户要的是"往下滑看内容时别被顶栏压着"
                val showTopBar = !fullscreenState.value && viewing == null &&
                    !((playing != null || selected?.kind == MediaKind.VIDEO) && playerScrolled)
                AnimatedVisibility(
                    visible = showTopBar,
                    enter = expandVertically(expandFrom = Alignment.Top),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    AppTopBar(
                        title = topTitle,
                        onBack = onBack,
                        // 下拉与设置只在首页出现；进了详情/设置就只剩返回
                        current = if (onBack == null) current else null,
                        // 下拉里只列启用的源：停用 = 不出现在这里（M5.2）
                        sources = enabled,
                        onSelect = ::selectSource,
                        // 搜索按钮在源下拉左侧；源没配搜索能力（或没有 search 资源）时整块不出现
                        onSearch = if (onBack == null && canSearch) ({ searchOpen = true }) else null,
                        // 历史入口：只在首页出现；点开时重算一遍，保证看到的是最新写入
                        onHistory = if (onBack == null) ({
                            refreshHistory()
                            historyError = null
                            historyOpen = true
                        }) else null,
                        sourceColors = sourceColors,
                        // 齿轮就是入口：没有任何源时它也必须显示，否则"去设置导入"没有路
                        onSettings = if (onBack == null) ({ settingsOpen = true }) else null,
                    )
                }

                val contentModifier = Modifier.weight(1f)

                when {
                    // 设置页要排在最前：**一个源都没有**时它是唯一出口（导入/粘贴/URL/备份都在里面），
                    // 以前这里先判 catalog.isEmpty()，于是空源时点齿轮也进不来，用户直接走进死路
                    settingsOpen -> SettingsScreen(
                        current = current,
                        // 设置页要看到全部源（含停用的），这样才能再启用；
                        // 顺序按状态分组：已选择 > 待选 > 停用
                        sources = settingsSources,
                        session = session,
                        onSelect = ::selectSource,
                        actions = sourceActions,
                        modifier = contentModifier,
                        disabledIds = disabledIds,
                        onToggle = ::toggleSource,
                        sourceColors = sourceColors,
                        imagePreload = imagePreload,
                        onImagePreloadChange = setImagePreload,
                        audioBackgroundSupported = audioBackgroundSupported,
                        audioBackground = audioBackground,
                        onAudioBackgroundChange = setAudioBackground,
                        autoPlay = autoPlay,
                        onAutoPlayChange = setAutoPlay,
                    )

                    catalog.isEmpty() -> ConfigBroken(
                        title = "还没有数据源",
                        message = "点「去设置」用「导入 / 粘贴 / URL」加一份配置。",
                        actionLabel = "去设置导入",
                        onAction = { settingsOpen = true },
                        modifier = contentModifier,
                    )

                    // 全部停用时不能落到空首页：给出"去启用一个"的出口，而不是一屏黑
                    enabled.isEmpty() -> ConfigBroken(
                        title = "所有数据源都已停用",
                        message = "到设置里启用一个再回来；也可以导入一份新配置。",
                        actionLabel = "去设置启用",
                        onAction = { settingsOpen = true },
                        modifier = contentModifier,
                    )

                    // 配置编译失败（唯一源时就是"打开 App 一屏错误"）：把原因摊出来，
                    // 并给「切换数据源」——面板里没有别的源时会引导去设置，不会把用户困在这屏
                    session == null -> ConfigBroken(
                        title = "这个源加载失败",
                        message = throwableMessage(
                            loaded?.exceptionOrNull() ?: IllegalStateException("配置加载失败"),
                        ) + "\n\n换一个数据源，或到设置里重新导入这份配置。",
                        actionLabel = "切换数据源",
                        onAction = { sourcePickerOpen = true },
                        modifier = contentModifier,
                    )

                    session.schema.hasErrors -> ConfigIssues(
                        issues = session.schema.issues,
                        onSwitchSource = { sourcePickerOpen = true },
                        modifier = contentModifier,
                    )

                    // 图片查看器：翻到第几张要落盘（`image.progress.<源>.<条目>`），下次点进来接着看。
                    // 用户在 2026-11-17 反馈"已阅读的图片进度没有保存和恢复"，这里补的就是这一段。
                    viewing != null -> {
                        // 跨源看历史图片时，这里的 session 必须是**那条源**的：
                        // 进度键、内页懒取、解密规则都要落到实际在看的源上
                        val s = activeSession!!
                        val progressKey =
                            "${SettingsKeys.IMAGE_PROGRESS_PREFIX}.$activeSourceId.${viewing!!.summary.id}"
                        // 只在本页第一次组合时读一次盘：之后以 `viewing.trackIndex` 为准，
                        // 否则每翻一页都会把刚写进去的值再读回来（虽然结果相同，但没必要）
                        val restoredIndex = remember(progressKey) {
                            settings.getString(progressKey)?.toIntOrNull() ?: 0
                        }
                        // 图集内页的直链是懒取的（`episodes.lazyItems`）：查看器翻到第 N 张才请求第 N 张，
                        // 这里给出"详情资源键 + 条目 id"就够它自己去取
                        val detailKey = s.schema.pages
                            .firstOrNull { it.layout.type == LayoutType.DETAIL }?.layout?.resource
                        ImageViewer(
                            title = viewing!!.summary.title,
                            tracks = viewing!!.group?.tracks.orEmpty(),
                            // 会话内已经翻过就以它为准；没翻过（trackIndex 还是 0）才用磁盘上的进度。
                            // 注意 0 也合法，所以不能靠"有没有值"判断——直接用 trackIndex>0 兜底
                            startIndex = if (viewing!!.trackIndex > 0) viewing!!.trackIndex else restoredIndex,
                            onBack = { viewing = null },
                            modifier = contentModifier,
                            // 设置里的"预加载张数"：当前张之后预加载 N 张（默认 3）
                            preload = imagePreload,
                            onIndexChange = { i ->
                                viewing = viewing?.copy(trackIndex = i)
                                settings.putString(progressKey, i.toString())
                            },
                            loadPending = if (detailKey == null) null else { index, pending ->
                                s.pipeline.resolvePendingTrack(
                                    key = detailKey,
                                    itemId = viewing!!.summary.id,
                                    index = index + 1,
                                    pendingId = pending,
                                )
                            },
                        )
                    }

                    // 音频走另一条页面（M4.5）：没有画面，控件常驻，播放列表可展开。
                    // 判定放在路由层而不是 render 里：同一个 "playing" 状态有两种呈现，是导航决策
                    playing != null && playing!!.summary.kind == MediaKind.AUDIO -> {
                        // 音频页的可选能力来自配置（`pages` 里 `audio_player` 那一条的 layout）
                        val audioLayout = activeSession!!.schema.config.pages
                            .firstOrNull { it.layout.type == LayoutType.AUDIO_PLAYER }?.layout
                        AudioRoute(
                            session = activeSession!!,
                            target = playing!!,
                            modifier = contentModifier,
                            onTargetChange = { playing = it },
                            showCover = audioLayout?.showCover ?: true,
                            showPlaylist = audioLayout?.showPlaylist ?: true,
                            // 与视频页共用同一个全局开关（音频页不画开关，只读值）
                            autoNext = autoNext,
                        )
                    }

                    // 视频已合并进上面的 ContentRoute；这里只保留音频/图片的旧详情入口。
                    // 视频：详情与播放器合并成一页（2026-11-20）。默认停在封面/选集，
                    // 点播放或选集才起播；返回直接回栏目，不再经过"播放页 → 详情页"。
                    selected != null && selected!!.kind == MediaKind.VIDEO -> ContentRoute(
                        session = activeSession!!,
                        item = selected!!,
                        initialTarget = selectedInitialTarget,
                        modifier = contentModifier,
                        playerSurface = playerSurface,
                        backDispatcher = backDispatcher,
                        fullscreenState = fullscreenState,
                        controlsBelowSurface = controlsBelowSurface,
                        autoPlay = autoPlay,
                        autoNext = autoNext,
                        onAutoNextChange = setAutoNext,
                        onScrollAwayChange = { playerScrolled = it },
                        onWatch = { target ->
                            recordWatch(
                                target.summary,
                                target.group?.name,
                                target.episode,
                                target.trackIndex,
                            )
                        },
                    )

                    selected != null -> DetailRoute(
                        // 跨源打开的历史条目，详情页也要用那条源的会话，否则 `id` 会在错误的站点上查
                        session = activeSession!!,
                        item = selected!!,
                        modifier = contentModifier,
                        onPlay = { target -> playing = target },
                        onView = { target -> viewing = target },
                    )

                    // 历史页：从首页进，返回回首页；顶栏的源仍是用户自己选的那个
                    historyOpen -> HistoryRoute(
                        groups = historyGroups,
                        sourceColors = sourceColors,
                        modifier = contentModifier,
                        message = historyError,
                        busy = historyBusy,
                        busyTitle = historyOpening?.title,
                        busyForeign = historyOpening?.sourceId != (session?.source?.id ?: ""),
                        onOpen = ::openHistory,
                        onClear = { sourceId ->
                            HistoryStore.clear(settings, sourceId)
                            refreshHistory()
                        },
                    )

                    // 搜索页排在详情之后：从结果点进详情是「叠一层」，返回回到搜索结果
                    searchOpen && searchController != null -> SearchRoute(
                        controller = searchController,
                        modifier = contentModifier,
                        onItemClick = {
                            selectedInitialTarget = null
                            selected = it
                        },
                        onLoadMore = { scope.launch { searchController.loadMore() } },
                        onRetry = { searchController.retry() },
                        history = searchHistory,
                        onClearHistory = {
                            currentId?.let { HistoryStore.clearSearch(settings, it) }
                            searchHistory = emptyList()
                        },
                    )

                    else -> homeStateHolder.SaveableStateProvider("home:${session.source.id}") {
                        HomeRoute(
                            session = session,
                            modifier = contentModifier,
                            onItemClick = {
                                selectedInitialTarget = null
                                selected = it
                            },
                            onSwitchSource = { sourcePickerOpen = true },
                        )
                    }
                }
            }
        }

        // 「切换数据源」面板：首页加载失败/空结果、以及"源加载不起来"那一屏共用。
        // 放在 Surface 之外、UmpTheme 之内——它要跟随当前主题，但不占内容区。
        if (sourcePickerOpen) {
            SwitchSourceDialog(
                current = current,
                sources = enabled,
                onSelect = { entry ->
                    selectSource(entry)
                    sourcePickerOpen = false
                },
                onSettings = {
                    sourcePickerOpen = false
                    settingsOpen = true
                },
                onDismiss = { sourcePickerOpen = false },
            )
        }
    }
}
