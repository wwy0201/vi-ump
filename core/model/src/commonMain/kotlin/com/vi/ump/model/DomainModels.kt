package com.vi.ump.model

/**
 * 域模型 = App 唯一认识的数据契约。
 *
 * 支持三种媒体：**视频 / 音频 / 图片**。
 * 三者共用同一个外壳（MediaSummary），差异通过 [MediaKind] 分派到不同的
 * 详情渲染、播放器与查看器 —— 这样配置作者不需要学三套结构。
 */
data class NavCategory(
    val id: String,
    val name: String,
    val icon: String? = null,
)

/** 媒体类型：决定详情页分支、播放器类型、卡片角标 */
enum class MediaKind(val key: String) {
    VIDEO("video"),
    AUDIO("audio"),
    IMAGE("image");

    companion object {
        fun fromKey(key: String?): MediaKind =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: VIDEO
    }
}

/** 列表条目（卡片）。视频/音频/图片共用。 */
data class MediaSummary(
    val id: String,
    val title: String,
    val kind: MediaKind = MediaKind.VIDEO,
    /** 缩略图：视频海报、音频封面、图片缩略 */
    val poster: String? = null,
    /** 图片类的大图地址（查看器加载用）；poster 此时是缩略 */
    val imageUrl: String? = null,
    val subtitle: String? = null,
    val year: Int? = null,
    val area: String? = null,
    val type: String? = null,
    val score: Float? = null,
    /** 视频时长 / 音频时长（毫秒） */
    val durationMs: Long? = null,
    val tags: List<String> = emptyList(),
    /** 视频/音频直链（用于 action=play 的快捷播放入口） */
    val playUrl: String? = null,
    /** 集数 / 曲数 / 图片张数，用于卡片角标 */
    val trackCount: Int? = null,
    /** 音频：歌手（列表卡片副标题常用） */
    val artist: String? = null,
    /** 音频：专辑 / 播放列表名 */
    val album: String? = null,
    /**
     * 该条目应使用的详情资源键（`resources.details` 里的 key）。
     *
     * 不填 = 用详情页 `layout.resource` 的默认资源。一个源里同时混 UGC 与 PGC
     * （Bilibili 的普通视频 + 番剧/电影正片）时，两类条目的详情接口不同，必须由
     * 条目自己带路，不能只靠页面级那一个键。
     */
    val detailResource: String? = null,
)

/**
 * 单条媒体条目。
 * - 视频：一集
 * - 音频：一首曲目（或一章有声书）
 * - 图片：一张图（url 为大图，thumb 为缩略）
 */
data class Track(
    val index: Int,
    val title: String,
    val url: String,
    val thumb: String? = null,
    val durationMs: Long? = null,
    /**
     * 状态标签：`free` / `vip` / `trailer` / `extra`。
     *
     * 为什么是字符串而不是枚举：站点口径随时会变（"限免""会员""超前点播"），
     * 而这是配置驱动的客户端——多一种状态不该改一次代码。渲染层做兜底配色。
     */
    val state: String? = null,
    /**
     * 懒取占位（2026-11-17）：本条的直链还没取，需要拿这个 id 再请求一次
     * （`SourcePipeline.resolvePendingTrack`）。非空时 [url] 一定是空串。
     *
     * 图集站点一张图一个内页，进详情时一次性把 60 张全取完太费流量也容易被限流，
     * 所以由 `episodes.lazyItems` 改成"翻到哪张取哪张"。
     */
    val pending: String? = null,
)

/**
 * 条目分组。视频的"线路"、音频的"专辑/播放列表"、图片的"图集"都归到这里。
 * 组级带 kind，便于一个详情里出现混合形态（如带音频轨的视频）。
 */
data class TrackGroup(
    val name: String,
    val kind: MediaKind,
    val tracks: List<Track>,
)

data class MediaDetail(
    val summary: MediaSummary,
    val description: String? = null,
    val actors: List<String> = emptyList(),
    val director: String? = null,
    val trackGroups: List<TrackGroup> = emptyList(),
    val related: List<MediaSummary> = emptyList(),
)

/**
 * 播放目标：**一条**（当前这一集）+ 它所在的全部分组。
 *
 * 为什么不能只传一条 `Track`：自动连播要知道"下一集是谁"，线路切换要知道
 * "同一集在另一条线路上是哪一条"——两件事都要求把整组一起带进播放页。
 *
 * `summary` 仍是最初点进来的条目（标题、封面、进度键都用它），
 * 不随换集变化：进度键是 `源|条目|集号`，条目不变、集号跟着 `track.index` 走。
 */
data class PlayTarget(
    val summary: MediaSummary,
    val groups: List<TrackGroup> = emptyList(),
    val groupIndex: Int = 0,
    val trackIndex: Int = 0,
) {
    val group: TrackGroup? get() = groups.getOrNull(groupIndex)
    val track: Track? get() = group?.tracks?.getOrNull(trackIndex)
    /** 集号；0 = 整条一个进度（没有分组时） */
    val episode: Int get() = track?.index ?: 0
    /** 播放页标题：有分组就用这一集的标题，没有就用条目名 */
    val title: String get() = track?.title ?: summary.title
    /** 地址：优先当前这一集，退回条目自带的直链 */
    val url: String? get() = track?.url?.takeIf { it.isNotBlank() } ?: summary.playUrl
    val lineNames: List<String> get() = groups.map { it.name }

    fun hasNext(): Boolean = (group?.tracks?.size ?: 0) > trackIndex + 1

    /** 同组下一集；已经没有下一集返回 null（不跨组，跨组会把"预告"接在"正片"后面） */
    fun next(): PlayTarget? = if (hasNext()) copy(trackIndex = trackIndex + 1) else null

    fun hasPrev(): Boolean = trackIndex > 0

    /** 同组上一集；第一集返回 null（与 [next] 对称，同样不跨组，避免从正片第一集跳进预告最后一集） */
    fun prev(): PlayTarget? = if (hasPrev()) copy(trackIndex = trackIndex - 1) else null

    /**
     * 切线路：在新组里按**同一个集号**对齐，找不到就回到第一条。
     *
     * 为什么按集号而不是按下标：不同线路的组不一定等长（一条线路缺第 5 集、
     * 或者第一组是正片第二组是预告），按下标对齐会"跳到别的集"。
     */
    fun switchLine(index: Int): PlayTarget {
        val target = groups.getOrNull(index) ?: return this
        if (index == groupIndex) return this
        val same = target.tracks.indexOfFirst { it.index == episode }
        return copy(groupIndex = index, trackIndex = same.takeIf { it >= 0 } ?: 0)
    }
}

data class PagedResult<T>(
    val items: List<T>,
    val page: Int,
    val hasMore: Boolean,
    val total: Int? = null,
    /** 因必填字段缺失而被跳过的条数，用于 UI 上"已加载 N 条（M 条跳过）" */
    val skipped: Int = 0,
)

data class FilterGroup(
    val key: String,
    val title: String,
    val values: List<FilterValue>,
)

data class FilterValue(
    val label: String,
    val value: String,
)

data class PlayRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val kind: MediaKind = MediaKind.VIDEO,
)

data class ResolvedPlay(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeHint: String? = null,
    val kind: MediaKind = MediaKind.VIDEO,
)

/**
 * `fields` 键的白名单。
 * 编译期用它拦掉拼错的字段名（比如写成 `titel` 直接报 E009，而不是静默空白）。
 */
enum class DomainField(val key: String) {
    ID("id"),
    TITLE("title"),
    KIND("kind"),
    POSTER("poster"),
    IMAGE_URL("imageUrl"),
    SUBTITLE("subtitle"),
    YEAR("year"),
    AREA("area"),
    TYPE("type"),
    SCORE("score"),
    DURATION("duration"),
    TAGS("tags"),
    PLAY_URL("playUrl"),
    TRACK_COUNT("trackCount"),
    DESCRIPTION("description"),
    ACTORS("actors"),
    DIRECTOR("director"),
    ARTIST("artist"),
    ALBUM("album"),
    TRACK_GROUPS("trackGroups"),
    RELATED("related"),
    DETAIL_RESOURCE("detailResource");

    companion object {
        fun fromKey(key: String): DomainField? = entries.firstOrNull { it.key == key }
    }
}
