package com.vi.ump.storage

import java.io.File

/** 桌面端导入源放 `~/.vi-ump/sources`：与选集缓存一样，属于"用户自己的数据" */
actual object UserSourceStore {

    private fun dir(): File = File(System.getProperty("user.home"), ".vi-ump/sources").apply { mkdirs() }

    actual fun list(): List<StoredSource> =
        dir().listFiles { f -> f.isFile && f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { f ->
                runCatching { StoredSource(f.nameWithoutExtension, f.readText(Charsets.UTF_8)) }.getOrNull()
            }

    actual fun save(id: String, json: String) {
        File(dir(), sourceFileName(id)).writeText(json, Charsets.UTF_8)
    }

    actual fun delete(id: String) {
        File(dir(), sourceFileName(id)).delete()
    }
}
