package com.vi.ump.storage

import java.io.File

/** 导入源放在应用私有目录：不要存储权限，卸载即清 */
actual object UserSourceStore {

    private fun dir(): File? =
        AndroidStorageHost.context()?.let { ctx -> File(ctx.filesDir, "sources").apply { mkdirs() } }

    actual fun list(): List<StoredSource> {
        val d = dir() ?: return emptyList()
        return d.listFiles { f -> f.isFile && f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { f ->
                runCatching { StoredSource(f.nameWithoutExtension, f.readText(Charsets.UTF_8)) }.getOrNull()
            }
    }

    actual fun save(id: String, json: String) {
        val d = dir() ?: return
        File(d, sourceFileName(id)).writeText(json, Charsets.UTF_8)
    }

    actual fun delete(id: String) {
        val d = dir() ?: return
        File(d, sourceFileName(id)).delete()
    }
}
