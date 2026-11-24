package com.vi.ump.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vi.ump.feature.AppRoot
import com.vi.ump.feature.SourceActions
import com.vi.ump.feature.SourceEntry
import com.vi.ump.feature.fetchConfigText
import com.vi.ump.feature.importEntryOf
import com.vi.ump.storage.UserSourceStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 数据源 = **只有用户导入的那些**（filesDir/sources）。
            // M5.9 起 APK 里不再打包任何预置源：装完是空列表，设置页会自动打开，第一条配置由用户导入。
            // 用 state 持有：导入/删除之后要能就地刷新，而不是重启 App 才看到新源
            var sources by remember { mutableStateOf(UserSourceLoader.load()) }
            // 导入结果文案（第二元素 = 是否错误），显示在设置页"数据源"栏下方
            var importResult by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

            // 备份（M5.9）：写进用户选的一个公共目录，卸载 App 也不删，由用户自己去文件管理器删
            val backup = remember { BackupStore(applicationContext) }
            // 首次备份要先选目录：先把"要备份哪一份"记住，选完目录立刻写它（不让用户点两次）
            var pendingBackup by remember { mutableStateOf<SourceEntry?>(null) }
            val backupDirLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                val entry = pendingBackup
                pendingBackup = null
                when {
                    uri == null -> importResult = "已取消选择备份目录" to false
                    entry == null -> {
                        val granted = backup.setDir(uri)
                        importResult = if (granted) {
                            "备份目录已设置：${backup.savedLabel()}" to false
                        } else {
                            "已记住「${backup.savedLabel()}」，但系统没给长期授权（下次备份可能要重选）" to true
                        }
                    }
                    else -> {
                        backup.setDir(uri)
                        importResult = backup.save(entry)
                    }
                }
            }

            // SAF 选文件：不要任何存储权限，卸载后由系统回收授权
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                importResult = importFromUri(uri) { sources = UserSourceLoader.load() }
            }

            // 导出（M5.5）：另存为一份已脱敏的配置。待写入的文本先存着，等系统给回 uri 再写
            var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                val pending = pendingExport
                pendingExport = null
                if (uri == null || pending == null) {
                    importResult = "已取消导出" to false
                } else {
                    importResult = writeExport(uri, pending.first)
                }
            }

            // URL 拉取导入专用 client：只拉配置文本，不带任何站点请求头，20 秒超时
            val importClient = remember {
                HttpClient(OkHttp) {
                    // 内网/回环的配置地址必须直连：系统代理（Clash 等）够不到 10.0.2.2、192.168.x.x，
                    // 不 bypass 的话用户会看到莫名其妙的 502
                    engine { config { proxySelector(LocalFirstProxySelector) } }
                    install(HttpTimeout) {
                        requestTimeoutMillis = 20_000
                        connectTimeoutMillis = 10_000
                        socketTimeoutMillis = 20_000
                    }
                }
            }

            AppRoot(
                sources = sources,
                // 手机版有 Media3 前台服务：设置页显示「音乐后台播放」开关
                audioBackgroundSupported = true,
                sourceActions = SourceActions(
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onDelete = { id ->
                        UserSourceStore.delete(id)
                        sources = UserSourceLoader.load()
                        importResult = "已删除数据源 $id" to false
                    },
                    // 粘贴导入：与文件导入共用 importEntryOf 校验与 UserSourceStore 落盘。
                    // 返回 null = 成功（对话框自动关）；非 null = 原因，留在对话框里让用户改完重试
                    onImportText = label@{ text ->
                        val entry = runCatching { importEntryOf(text) }.getOrElse {
                            return@label (it.message ?: "配置校验未通过")
                        }
                        UserSourceStore.save(entry.id, text)
                        sources = UserSourceLoader.load()
                        importResult = "已导入「${entry.label}」（${entry.id}），在数据源列表末尾" to false
                        null
                    },
                    // URL 拉取导入：网络在 IO 线程，回到主线程再刷新 State
                    onImportUrl = label@{ url ->
                        val text = withContext(Dispatchers.IO) {
                            runCatching { fetchConfigText(importClient, url) }
                        }.getOrElse {
                            return@label "拉取失败：${it.message?.lineSequence()?.firstOrNull()?.take(140) ?: "网络错误"}"
                        }
                        val entry = runCatching { importEntryOf(text) }.getOrElse {
                            return@label (it.message ?: "配置校验未通过")
                        }
                        UserSourceStore.save(entry.id, text)
                        sources = UserSourceLoader.load()
                        importResult = "已从 URL 导入「${entry.label}」（${entry.id}）" to false
                        null
                    },
                    // 备份（M5.9）：目录没设置过时先弹一次目录选择器，选完自动把这一份写进去
                    onBackup = { entry ->
                        if (backup.hasDir()) {
                            // SAF 的 openOutputStream 是阻塞调用，挪到 IO 线程
                            importResult = withContext(Dispatchers.IO) { backup.save(entry) }
                        } else {
                            pendingBackup = entry
                            backupDirLauncher.launch(null)
                            importResult = "正在选备份目录…（选一次之后一直用它）" to false
                        }
                    },
                    message = importResult?.first,
                    messageIsError = importResult?.second == true,
                    // 导出：文本已脱敏（feature 层 maskForExport），这里只管写文件
                    onExport = { json, fileName ->
                        pendingExport = json to fileName
                        exportLauncher.launch(fileName)
                    },
                ),
                playerSurface = { token, modifier -> Media3Surface(token, modifier) },
                // 系统返回键接进 AppRoot 的返回栈：设置/详情/播放页时回退，首页时让系统正常退出
                backDispatcher = { enabled, action ->
                    BackHandler(enabled = enabled) { action() }
                },
                // 全屏要把系统状态栏 + 导航栏一起藏起来，否则"全屏"只是画面变大一点
                onFullscreenChange = ::applyImmersive,
            )
        }
    }

    /**
     * 导入一份站点配置：读文本 → `importEntryOf` 校验（不过就带原因返回）→ 落盘 → 刷新列表。
     * 任何一步失败都要有一句话回显，不能出现"选了文件但什么都没发生"。
     */
    private fun importFromUri(uri: android.net.Uri?, onSaved: () -> Unit): Pair<String, Boolean> {
        if (uri == null) return "已取消导入" to false
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) return "读取文件失败或文件为空" to true

        val entry = runCatching { importEntryOf(text) }.getOrElse {
            return (it.message ?: "配置校验未通过") to true
        }
        UserSourceStore.save(entry.id, text)
        onSaved()
        return "已导入「${entry.label}」（${entry.id}），在数据源列表末尾" to false
    }

    /**
     * 导出写文件（M5.5）：CreateDocument 给回来的 uri 已经是"可写的空文档"，
     * 直接写字节即可。成功/失败都回一句话——导出这种动作没有回显等于没有发生。
     */
    private fun writeExport(uri: android.net.Uri, text: String): Pair<String, Boolean> = runCatching {
        contentResolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: error("无法打开目标文件")
    }.fold(
        onSuccess = {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
            "已导出（已脱敏）：$name" to false
        },
        onFailure = {
            "导出失败：${it.message?.lineSequence()?.firstOrNull()?.take(120) ?: "未知原因"}" to true
        },
    )

    /**
     * 沉浸式全屏：藏掉状态栏与导航栏，让画面真正铺满整屏。
     *
     * 两件事必须一起做，只做其一都会露馅：
     *  - `hide(systemBars())`：把两条系统栏收走
     *  - `setDecorFitsSystemWindows(false)`：允许内容延伸到系统栏原来的位置；
     *    只在全屏期间这么设，退出时恢复 true，否则首页顶栏会被状态栏盖住
     *
     * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`：全屏下从边缘上滑能临时唤出系统栏，
     * 看完自动消失——这是视频类 App 的通行做法，避免用户在全屏里彻底失去系统手势提示。
     */
    private fun applyImmersive(enabled: Boolean) {
        val window = this.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }
}

/**
 * 把 `core:player` 交出来的 ExoPlayer 句柄挂到 Compose 上。
 *
 * 为什么放在壳层：`render.PlayerView` 用的是一个 `@Composable (Modifier) -> Unit` 槽位，
 * 播放器符号（Media3）只出现在 app 模块，桌面端该槽位为空实现 —— 模块边界保持单向。
 *
 * `useController = false`：控制器（播放/进度/全屏按钮）由 Compose 侧自己画，
 * 免得和 `UmpPlayer` 的播放按钮、进度条出现两套控件互相抢状态。
 */
@Composable
private fun Media3Surface(token: Any?, modifier: Modifier) {
    val player = token as? Player ?: return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { view -> view.player = player },
    )
}

/**
 * 只给"URL 拉取导入"用的代理选择器：内网/回环地址直连，其余交给系统默认。
 *
 * 实测踩坑：模拟器带着系统代理时，`http://10.0.2.2:8765/...` 会被送进代理隧道，
 * 代理够不到宿主机，回 502——配置明明就在本机却拉不到。站点请求的 client 不走这里。
 */
private object LocalFirstProxySelector : java.net.ProxySelector() {
    override fun select(uri: java.net.URI): List<java.net.Proxy> {
        val host = uri.host?.lowercase() ?: ""
        val secondOctet = host.split(".").getOrNull(1)?.toIntOrNull()
        val local = host == "localhost" || host == "::1" ||
            host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
            (host.startsWith("172.") && (secondOctet ?: -1) in 16..31)
        return if (local) {
            listOf(java.net.Proxy.NO_PROXY)
        } else {
            java.net.ProxySelector.getDefault().select(uri)
        }
    }

    override fun connectFailed(uri: java.net.URI, sa: java.net.SocketAddress, ioe: java.io.IOException) {
        java.net.ProxySelector.getDefault().connectFailed(uri, sa, ioe)
    }
}
