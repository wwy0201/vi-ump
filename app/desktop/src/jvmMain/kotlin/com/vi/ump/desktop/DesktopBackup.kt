package com.vi.ump.desktop

import com.vi.ump.feature.SourceEntry
import com.vi.ump.storage.sourceFileName
import java.io.File

/**
 * 桌面端的"备份源"（M5.9）：写 `~/.vi-ump/backup/vi-ump-<id>.json`。
 *
 * 内容与导入源一样是**原始 JSON**，不脱敏——脱敏的那份走"导出（已脱敏）"，两条通道各管一件事：
 * 备份是为了**能再导回来**（丢了请求头/凭据的配置导回来等于废的），导出是为了分享。
 *
 * 桌面没有"卸载"这回事，但备份仍然放在**家目录**而不是仓库或安装目录：
 * 换一次构建、删掉源码树之后，这一份还在。
 */
fun backupDesktopSource(entry: SourceEntry): Pair<String, Boolean> = runCatching {
    val dir = File(System.getProperty("user.home"), ".vi-ump/backup").apply { mkdirs() }
    val file = File(dir, "vi-ump-" + sourceFileName(entry.id))
    file.writeText(entry.json, Charsets.UTF_8)
    "已备份到 ${file.absolutePath}（原样保存，可再导入）" to false
}.getOrElse { e ->
    "备份失败：${e.message?.lineSequence()?.firstOrNull()?.take(120) ?: "未知原因"}" to true
}
