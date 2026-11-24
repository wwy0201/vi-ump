package com.vi.ump.storage

import java.util.prefs.Preferences

actual fun createEpisodesCache(): EpisodesCache =
    PrefsEpisodesCache(Preferences.userRoot().node("com/vi/ump/episodes"))

/**
 * `Preferences` 单个值默认限长 8192 字符（Windows 注册表上限），
 * 而 48 集的 Track 列表 JSON 很容易超 —— 所以按 8K 切片存成 `key#N`，
 * 读的时候按同一规则拼回来。切片数记在 `key#n` 里，写入时刻记在 `key#ts` 里。
 */
private class PrefsEpisodesCache(private val prefs: Preferences) : EpisodesCache {

    override fun read(key: String, ttlMs: Long): String? {
        val writtenAt = prefs.getLong("$key$EPISODES_CACHE_TS_SUFFIX", 0L)
        // 没有时间戳 = 旧版本写的，或 TTL 已过：都当过期，删掉重拉
        val expired = writtenAt <= 0L || (nowMs() - writtenAt).let { it > 0L && it > ttlMs }
        if (expired) {
            removeEntry(key)
            return null
        }

        val count = prefs.getInt("$key#n", 0)
        if (count <= 0) {
            // 兼容单片的短缓存（不足 8K 时早期写入的形态）
            return prefs.get(key, null)
        }
        val sb = StringBuilder()
        for (i in 0 until count) {
            val part = prefs.get("$key#$i", null) ?: return null
            sb.append(part)
        }
        return sb.toString()
    }

    override fun write(key: String, value: String) {
        if (value.isBlank()) return
        // 先清掉上一版切片，避免新版更短时残留尾巴被拼进来
        removeEntry(key)

        val chunk = 8000
        var i = 0
        var pos = 0
        while (pos < value.length) {
            val end = minOf(pos + chunk, value.length)
            prefs.put("$key#$i", value.substring(pos, end))
            pos = end
            i++
        }
        prefs.putInt("$key#n", i)
        prefs.putLong("$key$EPISODES_CACHE_TS_SUFFIX", nowMs())
        // Preferences 写入是异步落到注册表的：不 flush 的话，进程被 run 任务结束/强杀时
        // 可能只落了切片而丢了 `#ts` —— 下次读不到时间戳就按过期处理，等于白缓存一次
        runCatching { prefs.flush() }
    }

    override fun clear() {
        prefs.keys().forEach { prefs.remove(it) }
    }

    /** 整条记录（正文 + 切片 + 片数 + 时间戳）一起删，避免残留半条 */
    private fun removeEntry(key: String) {
        val old = prefs.getInt("$key#n", 0)
        for (i in 0 until old) prefs.remove("$key#$i")
        prefs.remove(key)
        prefs.remove("$key#n")
        prefs.remove("$key$EPISODES_CACHE_TS_SUFFIX")
    }

    private fun nowMs(): Long = System.currentTimeMillis()
}
