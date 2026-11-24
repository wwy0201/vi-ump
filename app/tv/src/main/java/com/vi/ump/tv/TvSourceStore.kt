package com.vi.ump.tv

import android.content.Context
import com.vi.ump.config.ConfigCompiler
import java.io.File

/** TV 壳自己的源列表：与手机版一样只读 filesDir/sources，不打包任何预置源。 */
data class TvSource(
    val id: String,
    val label: String,
    val version: String?,
    val json: String,
    val file: File,
)

object TvSourceStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "sources").apply { if (!exists()) mkdirs() }

    fun list(context: Context): List<TvSource> {
        val files = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            val meta = runCatching { ConfigCompiler.loadAndCompile(text).config.meta }.getOrNull()
            TvSource(
                id = meta?.id?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
                label = meta?.name?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
                version = meta?.version?.takeIf { it.isNotBlank() },
                json = text,
                file = file,
            )
        }
    }

    /** 导入：先校验再落盘，校验不过抛异常由界面显示原因 */
    fun import(context: Context, json: String): TvSource {
        val compiled = runCatching { ConfigCompiler.loadAndCompile(json) }.getOrElse {
            throw IllegalArgumentException("配置解析失败：${it.message?.lineSequence()?.firstOrNull() ?: "JSON 格式错误"}")
        }
        if (compiled.hasErrors) {
            val detail = compiled.issues.take(2).joinToString("；") { "[${it.code.code}] ${it.path} ${it.message}" }
            throw IllegalArgumentException("配置校验未通过：$detail")
        }
        val meta = compiled.config.meta
        require(meta.id.isNotBlank()) { "meta.id 为空，不能作为源标识" }
        val file = File(dir(context), "${meta.id}.json")
        file.writeText(json)
        return TvSource(
            id = meta.id,
            label = meta.name.ifBlank { meta.id },
            version = meta.version,
            json = json,
            file = file,
        )
    }

    fun readPath(path: String): String {
        val clean = path.trim().removePrefix("file://")
        require(clean.isNotBlank()) { "路径为空" }
        val file = File(clean)
        require(file.isFile) { "文件不存在：$clean" }
        return file.readText()
    }
}
