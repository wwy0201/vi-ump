package com.vi.ump.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 配置文件中所有字符串枚举都显式标注 SerialName，
// 保证 JSON 里写 "poster_grid" 与代码里 LayoutType.POSTER_GRID 的对应关系一目了然。

@Serializable
enum class LayoutType {
    @SerialName("tabs") TABS,
    @SerialName("tabs_grid") TABS_GRID,
    @SerialName("sections") SECTIONS,
    @SerialName("banner_hero") BANNER_HERO,
    @SerialName("poster_grid") POSTER_GRID,
    @SerialName("row_cards") ROW_CARDS,
    @SerialName("list_linear") LIST_LINEAR,
    @SerialName("category_filter") CATEGORY_FILTER,
    @SerialName("detail") DETAIL,
    /** 多组条目面板：视频=线路+选集，音频=播放列表 */
    @SerialName("track_panel") TRACK_PANEL,
    /** 图集网格 */
    @SerialName("gallery_grid") GALLERY_GRID,
    /** 图片查看器（全屏 / 缩放 / 左右滑动） */
    @SerialName("image_viewer") IMAGE_VIEWER,
    @SerialName("video_player") VIDEO_PLAYER,
    @SerialName("audio_player") AUDIO_PLAYER,
    @SerialName("search") SEARCH,
}

@Serializable
enum class ResponseType {
    @SerialName("json") JSON,
    @SerialName("text") TEXT,
    @SerialName("html") HTML,
    @SerialName("auto") AUTO,
}

@Serializable
enum class BodyType {
    @SerialName("json") JSON,
    @SerialName("form") FORM,
    @SerialName("raw") RAW,
}

@Serializable
enum class ExtractorKind {
    @SerialName("jsonPath") JSON_PATH,
    @SerialName("regex") REGEX,
    @SerialName("css") CSS,
    @SerialName("delimited") DELIMITED,
    @SerialName("template") TEMPLATE,
    @SerialName("const") CONST,
}

@Serializable
enum class FieldType {
    @SerialName("string") STRING,
    @SerialName("int") INT,
    @SerialName("float") FLOAT,
    @SerialName("bool") BOOL,
    @SerialName("stringList") STRING_LIST,
    @SerialName("long") LONG,
}

@Serializable
enum class ActionType {
    @SerialName("detail") DETAIL,
    @SerialName("play") PLAY,
    @SerialName("link") LINK,
    @SerialName("none") NONE,
}

@Serializable
enum class CardStyle {
    @SerialName("poster") POSTER,
    @SerialName("wide") WIDE,
    @SerialName("compact") COMPACT,
    @SerialName("text") TEXT,
}

@Serializable
enum class PlayStrategy {
    @SerialName("direct") DIRECT,
    @SerialName("resolver") RESOLVER,
}

@Serializable
enum class PagingMode {
    @SerialName("page") PAGE,
    @SerialName("offset") OFFSET,
    @SerialName("cursor") CURSOR,
}

@Serializable
enum class ThemeMode {
    @SerialName("dark") DARK,
    @SerialName("light") LIGHT,
    @SerialName("system") SYSTEM,
}

@Serializable
enum class Density {
    @SerialName("compact") COMPACT,
    @SerialName("comfortable") COMFORTABLE,
}
