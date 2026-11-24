package com.vi.ump.storage

/** 一条用户导入的源配置（原始 JSON 文本，编译留给选中之后） */
data class StoredSource(val id: String, val json: String)

/**
 * 用户导入源的落盘（M5）。
 *
 * 一条源只有"用户导入"这一种来源（M5.1a 之后 APK 不再打预置源），所以这里就是全部源的库：
 * 导入进来能删、能备份。卸载 App 即清空（Android 在 `filesDir/sources`）——
 * 想要卸载也不丢，用设置页每行的「备份」另存一份。
 *
 * 这里**不做配置校验**：校验归 `ConfigCompiler`，存储只管"读出来 / 写进去 / 删掉"。
 */
expect object UserSourceStore {
    fun list(): List<StoredSource>

    /** 同 id 覆盖（重复导入同一份配置 = 更新，而不是复制出第二份） */
    fun save(id: String, json: String)

    fun delete(id: String)
}

/** 文件名只保留安全字符：meta.id 是配置作者写的，可能带斜杠/空格/中文 */
fun sourceFileName(id: String): String =
    id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "source" } + ".json"
