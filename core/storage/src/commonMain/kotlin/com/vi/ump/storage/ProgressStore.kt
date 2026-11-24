package com.vi.ump.storage

/**
 * 播放进度存储。**只管"看到哪儿了"，不管"看过什么"** ——
 * 历史列表、收藏在 M6 才做（那时换成 SQLDelight，见 `docs/04 §10`）。
 *
 * 为什么先做成这一个小接口而不是直接上数据库：
 * M4 只需要一个 `key -> positionMs` 的读写，SharedPreferences / java.util.prefs 两行就能写完；
 * 提早引入 SQLDelight codegen 会让 M4 的验证成本翻倍，而接口形状不变，M6 换实现不用改调用方。
 */
interface ProgressStore {

    /** 没有记录返回 null（而不是 0）——「没看过」和「看到 0 秒」在续播语义上不一样 */
    fun position(key: String): Long?

    fun save(key: String, positionMs: Long)

    fun clear(key: String)
}

/**
 * 兜底实现：平台注入失败时退回进程内存。
 * 宁可"进度记不住"也不能"点开播放就崩"——存储不是关键路径。
 */
class InMemoryProgressStore : ProgressStore {
    private val map = mutableMapOf<String, Long>()
    override fun position(key: String): Long? = map[key]
    override fun save(key: String, positionMs: Long) {
        map[key] = positionMs
    }

    override fun clear(key: String) {
        map.remove(key)
    }
}

/**
 * 进度键 = 源 + 条目 + 集号。
 *
 * - 带源：YouTube 与腾讯的 id 形状不同，但不同源仍可能撞 id（比如都是纯数字），撞了就是"看了 A 源却从 B 源的进度续播"。
 * - 带集号：视频的 `(episodeIndex, positionMs)` 是三媒体里唯一带"第几集"的语义（见 `docs/02 F6.3`）。
 * - 用 `|` 分隔而不是拼接：调试时一眼能看出是哪一段。
 */
fun progressKey(sourceId: String, itemId: String, episode: Int = 0): String {
    val src = sourceId.ifBlank { "?" }
    return "$src|$itemId|$episode"
}

/** 平台提供实现：Android 用 SharedPreferences，桌面端用 java.util.prefs */
expect fun createProgressStore(): ProgressStore
