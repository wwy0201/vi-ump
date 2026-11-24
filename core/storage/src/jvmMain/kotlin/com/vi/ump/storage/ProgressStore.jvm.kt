package com.vi.ump.storage

import java.util.prefs.Preferences

/**
 * 桌面端的实际实现。用 `java.util.prefs` 而不是自己写文件：
 * 它是 JDK 自带的用户级键值存储（Windows 落在注册表 `HKCU\Software\JavaSoft\Prefs`），
 * 不需要额外依赖、不需要选路径、也不会有"文件被占用"的问题。
 */
actual fun createProgressStore(): ProgressStore =
    PrefsProgressStore(Preferences.userRoot().node("com/vi/ump/progress"))

private class PrefsProgressStore(private val prefs: Preferences) : ProgressStore {

    override fun position(key: String): Long? {
        val v = prefs.getLong(key, -1L)
        return if (v >= 0L) v else null
    }

    override fun save(key: String, positionMs: Long) {
        if (positionMs <= 0L) return
        prefs.putLong(key, positionMs)
    }

    override fun clear(key: String) {
        prefs.remove(key)
    }
}
