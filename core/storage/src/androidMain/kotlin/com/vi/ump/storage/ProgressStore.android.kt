package com.vi.ump.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Android 端的存储宿主：`expect fun createProgressStore()` 不带参数，
 * 而 SharedPreferences 要 Context —— 所以在进程最早期（`Application.onCreate`）注入，
 * 与 `AndroidPlayerHost.install(this)` 同样的做法，只存 applicationContext，不持有 Activity。
 */
object AndroidStorageHost {

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    internal fun context(): Context? = appContext
}

actual fun createProgressStore(): ProgressStore {
    val ctx = AndroidStorageHost.context() ?: return InMemoryProgressStore()
    return SharedPrefsProgressStore(ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE))
}

private const val NAME = "ump_progress"

/**
 * `commit = false`（apply）：进度是"丢了也无所谓"的数据，
 * 用异步落盘避免在主线程做 IO；真正需要强一致的是配置，不在这条路径上。
 */
private class SharedPrefsProgressStore(private val prefs: SharedPreferences) : ProgressStore {

    override fun position(key: String): Long? {
        val v = prefs.getLong(key, -1L)
        return if (v >= 0L) v else null
    }

    override fun save(key: String, positionMs: Long) {
        if (positionMs <= 0L) return
        prefs.edit().putLong(key, positionMs).apply()
    }

    override fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }
}
