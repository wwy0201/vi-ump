package com.vi.ump.android

import com.vi.ump.feature.SourceEntry
import com.vi.ump.feature.importEntryOf
import com.vi.ump.storage.UserSourceStore

/**
 * 数据源的**唯一**来源：用户自己导入的那些（`filesDir/sources` 目录里的 json，见 `UserSourceStore`）。
 *
 * M5.9 起不再有"预置源"：APK 里不打包任何站点配置
 * （`app/android/build.gradle.kts` 已去掉 `assets.srcDir(samples)`），源全部由用户在设置页导入
 * （文件 / 粘贴 / URL）。原因很实际：预置源是没人维护的样例，装完 App 看到一堆点开没数据的源
 * 反而像"App 坏了"；而站点配置一直在演化，固定在 APK 里等于一装就过期。
 *
 * 顺序 = 目录里的顺序（导入顺序）。设置页只做**状态分组**（已选择 > 待选 > 停用），
 * 手动排序（M5.2 的 ↑/↓）已按用户要求删除，`source.order` 也不再读写。
 */
object UserSourceLoader {

    /**
     * 读全部用户源。坏一个不能整页空掉：目录里的文件可能是"写了一半"或手工改坏的，
     * 逐个解析、跳过解析不了的（`importEntryOf` 会抛异常 = 这份配置不可用）。
     */
    fun load(): List<SourceEntry> = UserSourceStore.list().mapNotNull { stored ->
        runCatching { importEntryOf(stored.json) }.getOrNull()
    }
}
