package com.vi.ump.tv

import com.vi.ump.storage.AppSettings
import com.vi.ump.storage.SettingsKeys

/**
 * TV 端观看历史 / 搜索历史。
 *
 * 与手机版 `HistoryStore` 的口径一致：**按源保存**、点历史只打开这条内容而不换顶栏源。
 * TV 当前只在"当前源"内展示历史，因此点任何一条都不会改变源。
 */
object TvHistoryStore {
    private const val FIELD_SEP = '\u001f'
    private const val ROW_SEP = '\u001e'
    const val WATCH_LIMIT = 30
    const val SEARCH_LIMIT = 12

    data class WatchItem(
        val sourceId: String,
        val itemId: String,
        val title: String,
        val poster: String?,
        val kind: String,
        val groupName: String? = null,
        val episode: Int = 0,
        /** 图集里的数组下标（0 起）；视频不用 */
        val trackIndex: Int = 0,
        val detailResource: String? = null,
        val updatedAt: Long = 0L,
    ) {
        val progressLabel: String
            get() = when (kind) {
                "image" -> "看到第 ${trackIndex + 1} 张"
                else -> if (episode > 0) "看到第 $episode 集" else "看过"
            }
    }

    fun searchHistory(settings: AppSettings, sourceId: String): List<String> =
        settings.getString(searchKey(sourceId))
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    fun addSearch(settings: AppSettings, sourceId: String, keyword: String): List<String> {
        val word = keyword.replace('\n', ' ').replace('\r', ' ').trim()
        if (word.isEmpty()) return searchHistory(settings, sourceId)
        val next = (listOf(word) + searchHistory(settings, sourceId).filter { it != word })
            .take(SEARCH_LIMIT)
        settings.putString(searchKey(sourceId), next.joinToString("\n"))
        return next
    }

    fun clearSearch(settings: AppSettings, sourceId: String) {
        settings.putString(searchKey(sourceId), "")
    }

    fun watch(settings: AppSettings, sourceId: String): List<WatchItem> =
        settings.getString(historyKey(sourceId))
            ?.split(ROW_SEP)
            ?.mapNotNull { decode(it, sourceId) }
            .orEmpty()

    fun record(settings: AppSettings, item: WatchItem): List<WatchItem> {
        // 只用于排序的单调序号；不需要墙上时钟，也避免 commonMain 的日期依赖
        val stamp = (watch(settings, item.sourceId).maxOfOrNull { it.updatedAt } ?: 0L) + 1
        val newItem = item.copy(updatedAt = stamp)
        val old = watch(settings, item.sourceId).filter { it.itemId != item.itemId }
        val next = (listOf(newItem) + old).take(WATCH_LIMIT)
        settings.putString(historyKey(item.sourceId), next.joinToString(ROW_SEP.toString()) { encode(it) })
        return next
    }

    fun clear(settings: AppSettings, sourceId: String) {
        settings.putString(historyKey(sourceId), "")
    }

    fun remove(settings: AppSettings, sourceId: String, itemId: String): List<WatchItem> {
        val next = watch(settings, sourceId).filter { it.itemId != itemId }
        settings.putString(historyKey(sourceId), next.joinToString(ROW_SEP.toString()) { encode(it) })
        return next
    }

    private fun searchKey(sourceId: String) = "${SettingsKeys.SEARCH_HISTORY_PREFIX}.$sourceId"

    private fun historyKey(sourceId: String) = "${SettingsKeys.HISTORY_PREFIX}.$sourceId"

    private fun encode(item: WatchItem): String = listOf(
        item.itemId,
        item.title,
        item.poster.orEmpty(),
        item.kind,
        item.groupName.orEmpty(),
        item.episode.toString(),
        item.trackIndex.toString(),
        item.updatedAt.toString(),
        item.detailResource.orEmpty(),
    ).joinToString(FIELD_SEP.toString(), transform = ::escape)

    private fun decode(row: String, sourceId: String): WatchItem? {
        val f = row.split(FIELD_SEP)
        if (f.size < 8) return null
        return runCatching {
            WatchItem(
                sourceId = sourceId,
                itemId = unescape(f[0]),
                title = unescape(f[1]),
                poster = unescape(f[2]).takeIf { it.isNotBlank() },
                kind = unescape(f[3]).ifBlank { "video" },
                groupName = unescape(f[4]).takeIf { it.isNotBlank() },
                episode = f[5].toInt(),
                trackIndex = f[6].toInt(),
                updatedAt = f[7].toLong(),
                detailResource = f.getOrNull(8)?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    private fun escape(s: String): String =
        s.replace(FIELD_SEP, ' ').replace(ROW_SEP, ' ').replace('\n', ' ').replace('\r', ' ')

    private fun unescape(s: String): String = s
}
