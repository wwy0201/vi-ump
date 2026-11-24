package com.vi.ump.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.vi.ump.feature.SourceEntry
import com.vi.ump.storage.sourceFileName

/**
 * "备份源"的落点：用户自己选的一个**公共目录**（SAF），不是 App 私有目录。
 *
 * 为什么不用 `filesDir`：卸载 App 时私有目录跟着删，而用户明确要求
 * "卸载也不会删除，由用户手动删除"。所以走 `OpenDocumentTree` 让用户选一个目录
 * （通常是 Download 下的某个文件夹），拿一次持久化授权（`takePersistableUriPermission`）
 * 记住它；之后每次备份都写这个目录：`vi-ump-<源 id>.json`（同名覆盖，不堆副本）。
 *
 * 备份内容是**原始 JSON、不脱敏**：脱敏过的配置再导回来会丢请求头/凭据，
 * 等于备份了一个用不起来的壳。要分享出去的那份走"导出（已脱敏）"，两条通道各管一件事。
 */
class BackupStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前有没有一个**授权仍然有效**的备份目录（目录被用户在文件管理器里删掉时这里会变成 false） */
    fun hasDir(): Boolean = dir() != null

    /** 用户上次选的目录名，只用于文案（"已备份到「Download」"） */
    fun savedLabel(): String? = prefs.getString(KEY_LABEL, null)

    /**
     * 记住用户选的目录。
     *
     * `takePersistableUriPermission` 是关键：不拿这个长期授权，SAF 给的 uri 只在本次进程有效，
     * 下次启动 App 再备份就写不进去了（表现为"备份按钮没反应"）。
     */
    fun setDir(uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val granted = runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
            true
        }.getOrDefault(false)
        val label = DocumentFile.fromTreeUri(appContext, uri)?.name ?: uri.lastPathSegment ?: "备份目录"
        prefs.edit().putString(KEY_URI, uri.toString()).putString(KEY_LABEL, label).apply()
        return granted
    }

    /** 每次都重新解析出 DocumentFile：用户可能在系统里撤了授权，缓存的实例会骗人 */
    private fun dir(): DocumentFile? {
        val raw = prefs.getString(KEY_URI, null) ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return runCatching { DocumentFile.fromTreeUri(appContext, uri) }.getOrNull()
            ?.takeIf { it.isDirectory && it.canWrite() }
    }

    /**
     * 写一份备份。
     *
     * 同名文件先删再建：`DocumentFile` 没有"覆盖写"，直接 createFile 会得到
     * `vi-ump-x (1).json`、`vi-ump-x (2).json` 这样一串副本，用两次目录就乱了。
     */
    fun save(entry: SourceEntry): Pair<String, Boolean> {
        val target = dir() ?: return "备份目录不可用：请重新选一次备份目录" to true
        val name = "vi-ump-" + sourceFileName(entry.id)
        val where = target.name ?: "备份目录"

        runCatching {
            target.findFile(name)?.delete()
            val file = target.createFile("application/json", name)
                ?: error("无法在「$where」创建 $name")
            appContext.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(entry.json.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: error("无法写入 $name")
        }.onFailure { e ->
            return "备份失败：${e.message?.lineSequence()?.firstOrNull()?.take(120) ?: "未知原因"}" to true
        }

        return "已备份到「$where」/$name（原样保存，可再导入）" to false
    }

    private companion object {
        const val PREFS = "ump_backup"
        const val KEY_URI = "dir.uri"
        const val KEY_LABEL = "dir.label"
    }
}
