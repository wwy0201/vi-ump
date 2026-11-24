package com.vi.ump.storage

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.prefs.Preferences

actual fun createAppSettings(): AppSettings =
    PrefsAppSettings(Preferences.userRoot().node("com/vi/ump/settings"))

private class PrefsAppSettings(private val prefs: Preferences) : AppSettings {

    override fun getString(key: String, def: String?): String? = prefs.get(key, def)

    override fun putString(key: String, value: String) {
        prefs.put(key, value)
    }
}

actual fun copyTextToClipboard(text: String): Boolean = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    true
}.getOrDefault(false)
