package com.vi.ump.tv

import android.content.Context
import com.vi.ump.config.CompiledSchema
import com.vi.ump.config.ConfigCompiler
import com.vi.ump.model.ChannelSpec
import com.vi.ump.model.LayoutType
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PageSpec
import com.vi.ump.model.PagedResult
import com.vi.ump.source.SourcePipeline
import com.vi.ump.storage.AppSettings
import com.vi.ump.storage.SettingsKeys
import com.vi.ump.storage.createAppSettings
import com.vi.ump.storage.createEpisodesCache

/**
 * TV 的一次源会话。
 *
 * 与手机版的 `UmpSession` 同一口径：一个源 = 编译后的配置 + 一条取数管线 + 进程内列表缓存。
 * 区别只是这里不依赖 Compose，所有页面共用同一个 [SourcePipeline]。
 */
class TvSession(
    val context: Context,
    val source: TvSource,
) {
    val schema: CompiledSchema = ConfigCompiler.loadAndCompile(source.json)
    val fetcher: LegacyFetcher = LegacyFetcher(
        timeoutMs = schema.config.runtime.timeoutMs,
        retry = schema.config.runtime.retry,
    )
    val pipeline: SourcePipeline = SourcePipeline(
        schema = schema,
        fetcher = fetcher,
        episodesCache = createEpisodesCache(),
    )
    val settings: AppSettings = createAppSettings()

    private val feedCache = mutableMapOf<String, PagedResult<MediaSummary>>()

    /** 首页：配置里第一个非详情页 */
    val homePage: PageSpec? = schema.pages.firstOrNull { it.layout.type != LayoutType.DETAIL }

    /** 详情页：配置里第一个 detail 页 */
    val detailPage: PageSpec? = schema.pages.firstOrNull { it.layout.type == LayoutType.DETAIL }

    val detailKey: String? = detailPage?.layout?.resource

    /** 条目指定了详情资源就用它（Bilibili 电影/电视剧走 PGC 赛季），否则用默认详情页 */
    fun detailKeyFor(item: MediaSummary): String? = item.detailResource ?: detailKey

    val trackKey: String? = detailPage?.layout?.tracks

    fun channels(): List<ChannelSpec> = homePage?.channels.orEmpty()

    fun feedKeyOf(channel: ChannelSpec?): String? =
        channel?.feed?.takeIf { it.isNotBlank() } ?: homePage?.feed?.takeIf { it.isNotBlank() }

    fun paramsOf(channel: ChannelSpec?): Map<String, String> =
        channel?.params?.takeIf { it.isNotEmpty() } ?: homePage?.params.orEmpty()

    fun detailParams(): Map<String, String> =
        detailPage?.params?.takeIf { it.isNotEmpty() } ?: homePage?.params.orEmpty()

    fun cacheKey(feedKey: String, params: Map<String, String>): String = buildString {
        append(feedKey)
        append('|')
        params.entries.sortedBy { it.key }.forEach { append(it.key).append('=').append(it.value).append('&') }
    }

    fun cached(key: String): PagedResult<MediaSummary>? = feedCache[key]

    fun remember(key: String, value: PagedResult<MediaSummary>) {
        feedCache[key] = value
    }

    fun imagePreload(): Int =
        (settings.getString(SettingsKeys.IMAGE_PRELOAD)?.toIntOrNull() ?: SettingsKeys.IMAGE_PRELOAD_DEFAULT)
            .coerceIn(0, SettingsKeys.IMAGE_PRELOAD_MAX)

    fun setImagePreload(value: Int) {
        settings.putString(SettingsKeys.IMAGE_PRELOAD, value.coerceIn(0, SettingsKeys.IMAGE_PRELOAD_MAX).toString())
    }
}
