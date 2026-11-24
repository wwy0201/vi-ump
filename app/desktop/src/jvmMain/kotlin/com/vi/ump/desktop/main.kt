package com.vi.ump.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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
import java.awt.Component
import java.io.File

/**
 * 桌面端入口。与 Android 共用 [AppRoot]（同一份 Compose 代码）。
 *
 * 桌面端多出来的只有"开发期数据源发现"：从仓库 `samples` 目录加载站点配置（YouTube / 腾讯视频），
 * 全部走在线请求。
 *
 * 桌面端特有、后续要补的部分（见 docs/06）：
 *  - PlayerFacade 的 JVM actual（VLCJ 或 JavaFX Media）
 *  - 文件导入改用 java.awt.FileDialog
 *  - 断点：窗口宽度 > 840dp 时按 Expanded 布局，海报列数 5–7
 */
fun main() {
    // 1) Windows 上 JVM 默认不看系统代理，而 Clash 这类工具只改 IE/系统代理设置。
    //    不开这个开关，OkHttp 会直连 youtube.com 然后超时。
    System.setProperty("java.net.useSystemProxies", "true")

    // 2) 但开了系统代理之后，国内站点（如腾讯 CDN）会拒绝经代理隧道进来的 TLS 握手，
    //    所以按配置里的 runtime.proxyBypass 把它们的域名改走直连。
    val sources = DevSourceLoader.loadAll()
    ProxySupport.installFor(sources)

    launchApp(sources)
}

private fun launchApp(initial: List<SourceEntry>) = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "vi-ump",
        state = rememberWindowState(width = 1100.dp, height = 720.dp),
    ) {
        // 任务栏/标题栏图标：与安装包图标同一份 vi-ump.png（classpath 资源）
        LaunchedEffect(Unit) {
            runCatching {
                Thread.currentThread().contextClassLoader
                    ?.getResourceAsStream("vi-ump.png")
                    ?.use { javax.imageio.ImageIO.read(it) }
                    ?.let { window.iconImage = it }
            }
        }
        var sources by remember { mutableStateOf(initial) }
        var importResult by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
        val importClient = remember {
            HttpClient(OkHttp) {
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
            sourceActions = SourceActions(
                // 桌面端没有 SAF：AWT FileDialog 选 .json（M7.3 的正式文件对话框先以这个顶上）
                onImport = { importResult = importDesktopConfig { sources = DevSourceLoader.loadAll() } },
                onDelete = { id ->
                    UserSourceStore.delete(id)
                    sources = DevSourceLoader.loadAll()
                    importResult = "已删除数据源 $id" to false
                },
                onImportText = label@{ text ->
                    val entry = runCatching { importEntryOf(text) }.getOrElse {
                        return@label (it.message ?: "配置校验未通过")
                    }
                    UserSourceStore.save(entry.id, text)
                    sources = DevSourceLoader.loadAll()
                    importResult = "已导入「${entry.label}」（${entry.id}），在数据源列表末尾" to false
                    null
                },
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
                    sources = DevSourceLoader.loadAll()
                    importResult = "已从 URL 导入「${entry.label}」（${entry.id}）" to false
                    null
                },
                message = importResult?.first,
                messageIsError = importResult?.second == true,
                // 备份（M5.9）：写 ~/.vi-ump/backup，与 Android 同一个语义（原样保存、可再导入）
                onBackup = { entry ->
                    importResult = withContext(Dispatchers.IO) { backupDesktopSource(entry) }
                },
                // 导出（M5.5）：AWT 保存对话框，文本已在 feature 层脱敏
                onExport = { json, fileName ->
                    importResult = exportDesktopConfig(json, fileName)
                },
            ),
            // 桌面端的画面：`core:player` 的 JVM actual 交出一个 AWT Canvas（vlcj 的视频面），
            // 这里用 Compose 的 SwingPanel 把它嵌进来。拿到 null 说明这端没有画面（占位实现），
            // 此时铺一块黑底，UI 的文案已经解释了"为什么放不了"。
            playerSurface = { token, modifier ->
                val component = token as? Component
                if (component == null) {
                    Box(modifier = modifier.fillMaxSize().background(Color.Black))
                } else {
                    SwingPanel(
                        background = Color.Black,
                        modifier = modifier,
                        factory = { component },
                    )
                }
            },
            // vlcj 的视频面是 heavyweight AWT Canvas，会盖住 Compose 叠在画面里的控件，
            // 所以桌面端的控制条改放在画面下方常驻
            controlsBelowSurface = true,
        )
    }
}

/**
 * 桌面端导入：AWT `FileDialog` 选 JSON → 校验 → 落盘 `~/.vi-ump/sources` → 刷新列表。
 * 返回（文案, 是否错误），与 Android 的 SAF 版本同一套判定与提示口径。
 */
private fun importDesktopConfig(onSaved: () -> Unit): Pair<String, Boolean> {
    val dialog = java.awt.FileDialog(null as? java.awt.Frame, "选择站点配置 JSON", java.awt.FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
    dialog.isVisible = true
    val picked = dialog.file?.let { File(dialog.directory, it) } ?: return "已取消导入" to false

    val text = runCatching { picked.readText(Charsets.UTF_8) }.getOrNull()
    if (text.isNullOrBlank()) return "读取文件失败或文件为空" to true

    val entry = runCatching { importEntryOf(text) }.getOrElse {
        return (it.message ?: "配置校验未通过") to true
    }
    UserSourceStore.save(entry.id, text)
    onSaved()
    return "已导入「${entry.label}」（${entry.id}），在数据源列表末尾" to false
}

/**
 * 导出（M5.5）：保存对话框 + 写文件。文本已由 feature 层 `maskForExport` 脱敏，
 * 这里只负责"存到用户选的位置"，并回一句话（成功带路径，失败带原因）。
 */
private fun exportDesktopConfig(json: String, fileName: String): Pair<String, Boolean> {
    val dialog = java.awt.FileDialog(null as? java.awt.Frame, "导出配置（已脱敏）", java.awt.FileDialog.SAVE)
    dialog.file = fileName
    dialog.isVisible = true
    val dir = dialog.directory ?: return "已取消导出" to false
    val name = dialog.file ?: return "已取消导出" to false
    return runCatching {
        val target = File(dir, if (name.endsWith(".json", ignoreCase = true)) name else "$name.json")
        target.writeText(json, Charsets.UTF_8)
        "已导出（已脱敏）：${target.absolutePath}" to false
    }.getOrElse {
        "导出失败：${it.message?.lineSequence()?.firstOrNull()?.take(120) ?: "未知原因"}" to true
    }
}

/** 与 Android 壳层同一规则：URL 导入遇到内网/回环地址直连，系统代理够不到这些地址 */
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
