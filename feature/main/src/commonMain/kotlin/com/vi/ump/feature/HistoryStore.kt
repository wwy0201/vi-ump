package com.vi.ump.feature

import com.vi.ump.storage.AppSettings
import com.vi.ump.storage.SettingsKeys

/**
 * 搜索历史 + 观看历史（2026-11-18）。
 *
 * 两条硬约束（都来自用户口径）：
 *
 * 1. **按源保存**：搜索词与观看记录都挂 `sourceId`。同一个词在 e-hentai 与其他源的结果不同，
 *    "在 A 站搜过的词"不该出现在 B 站的搜索页里；历史条目也各自带 `sourceId`。
 * 2. **点历史不换源**：历史条目只是"打开某一本/某一集"，不改用户在下拉里选的源。
 *    这一条在 `AppRoot` 里靠 `foreignId` 实现（临时用条目所属源的 session），这里只负责存/取。
 *
 * 存储格式（没有 JSON 依赖，纯分隔符）：
 * - 搜索：一行一个关键词，`\n` 分隔，新的在前；关键词里的换行被压成空格。
 * - 历史：一条记录一行，`\u001e` 分隔记录；记录内 `\u001f` 分隔字段。
 *
 * 为什么不用 JSON：这里只有"读一串字符串"的需求，分隔符方案零依赖、坏数据也不会整体解析失败
 * （某条字段数不对就跳过这一条，其余照常显示）。
 */
object HistoryStore {
    private const val FIELD_SEP = '\u001f'
    private const val ROW_SEP = '\u001e'

    /** 单源最多留多少条观看记录；再多对"继续看"没有意义，只会让页面变长 */
    const val WATCH_LIMIT = 30

    /** 单源最多留多少个搜索词 */
    const val SEARCH_LIMIT = 12

    /**
     * 一条观看记录。`trackIndex` 是**数组下标**（查看器直接用它定位第几张），
     * `episode` 是 `Track.index`（视频/音频的集号，进度键用的就是它）。
     */
    data class WatchItem(
        val sourceId: String,
        val itemId: String,
        val title: String,
        val poster: String?,
        /** 域模型 `MediaKind` 的 key：`video` / `audio` / `image` */
        val kind: String,
        val groupName: String? = null,
        val episode: Int = 0,
        val trackIndex: Int = 0,
        /** 条目专属详情资源（Bilibili 电影/电视剧走 PGC）；空 = 用源的默认详情资源 */
        val detailResource: String? = null,
        val updatedAt: Long = 0L,
    ) {
        /** "继续看到第几张 / 第几集"的副标题 */
        val progressLabel: String
            get() = when (kind) {
                "image" -> "看到第 ${trackIndex + 1} 张"
                else -> if (episode > 0) "看到第 $episode 集" else "看过"
            }
    }

    // ---------------------------------------------------------------- 搜索历史

    fun searchHistory(settings: AppSettings, sourceId: String): List<String> =
        settings.getString(searchKey(sourceId))
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** 记一条搜索词：去重后置顶，超出上限砍掉最旧的 */
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

    // ---------------------------------------------------------------- 观看历史

    fun watch(settings: AppSettings, sourceId: String): List<WatchItem> =
        settings.getString(historyKey(sourceId))
            ?.split(ROW_SEP)
            ?.mapNotNull { decode(it, sourceId) }
            .orEmpty()

    /** 所有有历史的源 id，顺序按最近写入（新的在前由调用方按 updatedAt 排序） */
    fun sourcesWithHistory(settings: AppSettings): List<String> =
        settings.getString(SettingsKeys.HISTORY_SOURCES)
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()

    /**
     * 记一条/更新一条：同一条目（同 itemId）只保留最新一条并置顶。
     * 不按"第几集"拆成多条——用户的诉求是"帮我快速回到没看完的那一条"，
     * 同一部剧看第 5 集时把第 3 集也列出来只会让列表变脏。
     */
    fun record(settings: AppSettings, item: WatchItem): List<WatchItem> {
        // 排序用"序号"而不是墙上时钟：commonMain 里没有可用的当前时间，
        // 而这里只需要"谁更新"，序号足够且跨平台稳定
        val stamp = (sourcesWithHistory(settings).maxOfOrNull { sid ->
            watch(settings, sid).maxOfOrNull { it.updatedAt } ?: 0L
        } ?: 0L) + 1
        val newItem = item.copy(updatedAt = stamp)
        val old = watch(settings, item.sourceId).filter { it.itemId != item.itemId }
        val next = (listOf(newItem) + old).take(WATCH_LIMIT)
        settings.putString(historyKey(item.sourceId), next.joinToString(ROW_SEP.toString()) { encode(it) })

        // 维护"有历史的源"索引（这份索引只是遍历入口，重复写入要幂等）
        val ids = (listOf(item.sourceId) + (sourcesWithHistory(settings) - item.sourceId))
            .distinct()
        settings.putString(SettingsKeys.HISTORY_SOURCES, ids.joinToString("\n"))
        return next
    }

    fun remove(settings: AppSettings, sourceId: String, itemId: String): List<WatchItem> {
        val next = watch(settings, sourceId).filter { it.itemId != itemId }
        settings.putString(historyKey(sourceId), next.joinToString(ROW_SEP.toString()) { encode(it) })
        if (next.isEmpty()) dropSource(settings, sourceId)
        return next
    }

    fun clear(settings: AppSettings, sourceId: String) {
        settings.putString(historyKey(sourceId), "")
        dropSource(settings, sourceId)
    }

    /** 这个源还有没有历史（清空最后一条时要把索引里的 id 一起去掉） */
    private fun dropSource(settings: AppSettings, sourceId: String) {
        val ids = sourcesWithHistory(settings).filter { it != sourceId }
        settings.putString(SettingsKeys.HISTORY_SOURCES, ids.joinToString("\n"))
    }

    // ---------------------------------------------------------------- 编解码

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
        if (row.isBlank()) return null
        val f = row.split(FIELD_SEP)
        if (f.size < 8) return null // 字段数不对 = 坏记录，丢掉这一条，不连累整页
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
                // 第 9 个字段是后加的；旧记录只有 8 个字段，按空处理即可
                detailResource = f.getOrNull(8)?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    /** 分隔符与换行都可能出现在标题/关键词里，写入前统一压掉（比转义表简单且不会漏） */
    private fun escape(s: String): String =
        s.replace(FIELD_SEP, ' ').replace(ROW_SEP, ' ').replace('\n', ' ').replace('\r', ' ')

    private fun unescape(s: String): String = s
}
