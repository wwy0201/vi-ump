package com.vi.ump.storage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences

actual fun createAppSettings(): AppSettings {
    val ctx = AndroidStorageHost.context() ?: return InMemoryAppSettings()
    return SharedPrefsAppSettings(ctx.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE))
}

/**
 * `commit = true`：这里是"用户的选择"，切源后立刻杀进程也要保住，
 * 所以宁可占用一次主线程 IO 也要同步落盘（一次点击一次写，代价可忽略）。
 */
private class SharedPrefsAppSettings(private val prefs: SharedPreferences) : AppSettings {

    override fun getString(key: String, def: String?): String? = prefs.getString(key, def)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

actual fun copyTextToClipboard(text: String): Boolean {
    val ctx = AndroidStorageHost.context() ?: return false
    val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    return runCatching {
        manager.setPrimaryClip(ClipData.newPlainText("ump-play-url", text))
        true
    }.getOrDefault(false)
}

private const val SETTINGS_NAME = "ump_settings"
