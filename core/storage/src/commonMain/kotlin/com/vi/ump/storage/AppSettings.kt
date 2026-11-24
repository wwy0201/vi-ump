package com.vi.ump.storage

/**
 * 应用级的小配置（**不是**站点配置，站点配置在 `core:config` 里）。
 *
 * 目前只有一个真实使用者：首页"上次选的是哪个数据源"。
 * 需求是"卸载才失效"，所以不能用 `UmpSession.feedCache` 那种会话内缓存，
 * 得落盘到一个进程重启后仍然在的地方。
 */
interface AppSettings {

    fun getString(key: String, def: String? = null): String?

    fun putString(key: String, value: String)
}

/** 兜底实现，语义同 [InMemoryProgressStore]：记不住可以，崩不行 */
class InMemoryAppSettings : AppSettings {
    private val map = mutableMapOf<String, String>()

    override fun getString(key: String, def: String?): String? = map[key] ?: def

    override fun putString(key: String, value: String) {
        map[key] = value
    }
}

/** 键都集中在这里，避免字符串散落在各 feature 里 */
object SettingsKeys {
    /** 上次选中的数据源 id（`meta.id`），卸载才清 */
    const val SOURCE_ID = "source.lastId"

    /** 被停用的数据源（id 用 `\n` 分隔）：不出现在首页下拉里 */
    const val SOURCE_DISABLED = "source.disabled"

    /**
     * 连续播放（2026-11-16）：`"1"` 开 / 未存或其它 = 关。
     *
     * 这是**用户级全局开关**，与站点配置无关：默认关，用户在播放页打开后对所有源、所有条目生效，
     * 落盘所以杀进程重开也保持。配置里的 `playback.player.autoNext` 因此降级为"作者建议"，
     * 运行期不再读它（见 `docs/04 §8.0.3`）。
     */
    const val PLAYER_AUTO_NEXT = "player.autoNext"

    /**
     * 进入视频内容页是否自动起播（2026-11-20 用户口径）：`"1"` 开 / 未存或其它 = 关。
     *
     * 默认关：点开条目先停在封面 + 简介 + 选集，用户点播放或点某一集才起播。
     * 这是手机/桌面共用的**用户级全局设置**；TV 端不走这项设置，固定自动播放。
     */
    const val PLAYER_AUTO_PLAY = "player.autoPlay"

    /**
     * 手机版音频后台播放（2026-11-18）：`"1"` 开 / `"0"` 关，未存默认开。
     *
     * 关闭只影响"App 退到后台"这一种场景：音频暂停并撤掉通知栏；
     * 前台播放、页面内切歌、通知栏控制都照常。
     */
    const val PLAYER_AUDIO_BACKGROUND = "player.audioBackground"

    /**
     * 图片阅读进度（2026-11-17）：完整键是 `image.progress.<sourceId>.<itemId>`，值是"看到第几张"。
     *
     * 为什么按条目存而不是存一份"最近看的图集"：图集动辄 100+ 张，用户会几本来回翻，
     * 只记最后一次等于把别的那几本都清空。key 里带源 id，换源不会串到别的站的同 id 条目上。
     */
    const val IMAGE_PROGRESS_PREFIX = "image.progress"

    /**
     * 图片预加载张数（2026-11-18，口径由用户确认）：值是"**当前张之后**预加载几张"，默认 3。
     * 例：设 3 → 看第 1 张时加载 1，预加载 2、3、4；看第 15 张时加载 15，预加载 16、17、18。
     *
     * 只往后不留前面：回看是靠已取到的直链（`urls`）+ 图片缓存，翻回去不会重新发取址请求；
     * 往前多留 N 张等于每次都多打一倍的请求，而"往回翻"本来就是少数动作。
     */
    const val IMAGE_PRELOAD = "image.preload"

    /** 预加载张数的默认值（= 往后 3 张） */
    const val IMAGE_PRELOAD_DEFAULT = 3

    /** 预加载张数上限；设置页的步进器也只到这里（再多等于进图集就把一大片内页请求打出去） */
    const val IMAGE_PRELOAD_MAX = 8

    /**
     * 搜索历史（2026-11-18）：完整键 `search.history.<sourceId>`，值是关键词，`\n` 分隔、新的在前。
     *
     * 按源分开存：同一个词在不同站点的含义与结果都不一样，"这个源搜过的词"不该出现在别的源里。
     */
    const val SEARCH_HISTORY_PREFIX = "search.history"

    /**
     * 观看历史（2026-11-18）：完整键 `history.source.<sourceId>`，值是若干条记录（`\u001e` 分隔）。
     *
     * 按源分开存 + 另存一份"有历史的源 id 列表"（[HISTORY_SOURCES]）：
     * `AppSettings` 只有 get/put、无法枚举键，没有这份索引就只能靠猜源 id 去读。
     */
    const val HISTORY_PREFIX = "history.source"

    /** 哪些源有历史（`\n` 分隔），只用来遍历，真数据仍在 `history.source.<id>` 里 */
    const val HISTORY_SOURCES = "history.sources"

    /**
     * 抖音设备令牌 `ttwid`（2026-11-24）。
     *
     * 与"上次选的源"同性质：一台设备一份、有效期一年，冷启动不必重新注册。
     * 抖音判定 `blocked` 时清掉这个键就会重新注册。
     */
    const val DOUYIN_TTWID = "douyin.ttwid"
}

expect fun createAppSettings(): AppSettings

/**
 * 复制到系统剪贴板。播放页只留一个「复制播放地址」按钮，
 * 用户想看地址时自己取——比长期在页面上摊着一条几百字符的 CDN 链接干净得多。
 *
 * 放在 `core:storage` 是因为它和存储一样属于"平台小能力"，
 * 两边都要：Android 用 ClipboardManager，桌面用 AWT 剪贴板。
 */
expect fun copyTextToClipboard(text: String): Boolean
