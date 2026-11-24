package com.vi.ump.storage

import android.content.Context
import android.content.SharedPreferences

actual fun createEpisodesCache(): EpisodesCache {
    val ctx = AndroidStorageHost.context() ?: return InMemoryEpisodesCache()
    return SharedPrefsEpisodesCache(ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE))
}

private const val NAME = "ump_episodes"

/**
 * 一部剧一条记录（`源|条目` → 整块 JSON），外加一个写入时间戳 `源|条目#ts`（见 `EPISODES_CACHE_TTL_MS`）。
 *
 * 选集走 `commit()` 还是 `apply()`：这里用 `apply()` —— 写一次、读很多次，
 * 掉电丢一份缓存只是下次重拉 2 秒，没必要为了它在主线程等落盘。
 */
private class SharedPrefsEpisodesCache(private val prefs: SharedPreferences) : EpisodesCache {

    override fun read(key: String, ttlMs: Long): String? {
        val payload = prefs.getString(key, null) ?: return null
        val writtenAt = prefs.getLong(tsKey(key), 0L)
        // 没有时间戳 = 旧版本写的，或 TTL 已过：都当过期，删掉重拉
        val expired = writtenAt <= 0L || (nowMs() - writtenAt).let { it > 0L && it > ttlMs }
        if (expired) {
            prefs.edit().remove(key).remove(tsKey(key)).apply()
            return null
        }
        return payload
    }

    override fun write(key: String, value: String) {
        if (value.isBlank()) return
        prefs.edit()
            .putString(key, value)
            .putLong(tsKey(key), nowMs())
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun tsKey(key: String) = key + EPISODES_CACHE_TS_SUFFIX

    private fun nowMs(): Long = System.currentTimeMillis()
}
