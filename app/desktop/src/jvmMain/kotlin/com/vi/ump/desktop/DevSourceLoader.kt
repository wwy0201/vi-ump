package com.vi.ump.desktop

import com.vi.ump.feature.SourceEntry
import com.vi.ump.feature.importEntryOf
import com.vi.ump.storage.UserSourceStore

/**
 * 桌面端的数据源发现：**只读用户导入的那些**（`~/.vi-ump/sources` 目录里的 json）。
 *
 * M5.9 起不再有"开发期预置源"：以前这里扫仓库 `samples` 目录、还认 `UMP_SOURCE` 环境变量，
 * 现在两端口径统一——APK / jar 里都不带站点配置，源全部由用户导入（设置页的文件 / 粘贴 / URL）。
 * 这样"我这边的源和你那边不一样"就只剩一个原因：有没有导入过同一份配置。
 *
 * [bypassHosts] 仍然保留：它汇总配置里的 `runtime.proxyBypass`，`ProxySupport` 要靠它把
 * 国内域名从 Clash 代理里摘出来直连（功能在，跟源从哪来无关）。
 */
object DevSourceLoader {

    /** 读全部用户源；坏一份跳过一份，不能让整页空掉 */
    fun loadAll(): List<SourceEntry> = UserSourceStore.list().mapNotNull { stored ->
        runCatching { importEntryOf(stored.json) }.getOrNull()
    }

    /**
     * 汇总所有源声明的 `runtime.proxyBypass`（域名后缀），交给 [ProxySupport] 装分流。
     *
     * 这里用正则在原始 JSON 上抠，不依赖反序列化——`SourceEntry` 刻意只装原始文本，
     * 网络策略要在 UI 起来之前就定好。
     */
    fun bypassHosts(sources: List<SourceEntry>): List<String> =
        sources.flatMap { entry ->
            BYPASS.findAll(entry.json)
                .flatMap { QUOTED.findAll(it.groupValues[1]).map { m -> m.groupValues[1] } }
                .toList()
        }.distinct()

    private val BYPASS = Regex("\"proxyBypass\"\\s*:\\s*\\[([^\\]]*)]")
    private val QUOTED = Regex("\"([^\"]+)\"")
}
