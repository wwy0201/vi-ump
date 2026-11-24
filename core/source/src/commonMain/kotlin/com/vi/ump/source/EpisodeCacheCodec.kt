package com.vi.ump.source

import com.vi.ump.model.MediaKind
import com.vi.ump.model.Track
import com.vi.ump.model.TrackGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * 选集缓存里的 JSON 编解码。
 *
 * 为什么不直接 `@Serializable` 标在 `Track`/`TrackGroup` 上：那两个是域模型，
 * 加注解等于让核心契约依赖序列化框架（桌面/Android 都要跟着引 kotlinx）。
 * 缓存是"实现细节"，用一个**短字段名**的私有格式更合适：
 *  - 短名省体积（48 集的缓存约 3~5KB）
 *  - 格式坏了只影响缓存命中，[decode] `getOrDefault(emptyList())` 后走在线重拉
 */
internal object EpisodeCacheCodec {

    /**
     * 格式版本：将来字段变了就升这个号，旧缓存解不出内容自然失效。
     *
     * v2（2026-11-17）：加了 `p`（懒取图的 pending id）。v1 缓存里没有它，
     * 命中后查看器会以为"这条没有地址可取"——所以必须让旧缓存整体失效。
     */
    private const val VERSION = 2

    fun encode(groups: List<TrackGroup>): String = buildJsonObject {
        put("v", JsonPrimitive(VERSION))
        put(
            "g",
            buildJsonArray {
                groups.forEach { g ->
                    add(
                        buildJsonObject {
                            put("n", JsonPrimitive(g.name))
                            put("k", JsonPrimitive(g.kind.key))
                            put(
                                "t",
                                buildJsonArray {
                                    g.tracks.forEach { t -> add(encodeTrack(t)) }
                                },
                            )
                        },
                    )
                }
            },
        )
    }.toString()

    private fun encodeTrack(t: Track): JsonObject = buildJsonObject {
        put("i", JsonPrimitive(t.index))
        put("ti", JsonPrimitive(t.title))
        put("u", JsonPrimitive(t.url))
        t.durationMs?.let { put("d", JsonPrimitive(it)) }
        t.state?.let { put("s", JsonPrimitive(it)) }
        // 懒取占位：不带它，命中缓存后就再也没法把直链取回来
        t.pending?.let { put("p", JsonPrimitive(it)) }
    }

    /** 解不出来（被手改坏 / 旧版本格式）就返回空列表 —— 调用方会当成"没缓存"去重拉 */
    fun decode(raw: String): List<TrackGroup> = runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject
        if (root["v"]?.toString()?.toIntOrNull() != VERSION) return emptyList()
        root["g"]?.jsonArray?.mapNotNull { decodeGroup(it as? JsonObject ?: return@mapNotNull null) }.orEmpty()
    }.getOrDefault(emptyList())

    private fun decodeGroup(o: JsonObject): TrackGroup? {
        val name = (o["n"] as? JsonPrimitive)?.content ?: return null
        val kindKey = (o["k"] as? JsonPrimitive)?.content
        val kind = MediaKind.entries.firstOrNull { it.key == kindKey } ?: MediaKind.VIDEO
        val tracks = (o["t"] as? JsonArray)?.mapNotNull { decodeTrack(it as? JsonObject ?: return@mapNotNull null) }.orEmpty()
        if (tracks.isEmpty()) return null
        return TrackGroup(name = name, kind = kind, tracks = tracks)
    }

    private fun decodeTrack(o: JsonObject): Track? {
        val index = (o["i"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val title = (o["ti"] as? JsonPrimitive)?.content ?: return null
        val url = (o["u"] as? JsonPrimitive)?.content ?: return null
        return Track(
            index = index,
            title = title,
            url = url,
            durationMs = (o["d"] as? JsonPrimitive)?.longOrNull,
            state = (o["s"] as? JsonPrimitive)?.content,
            pending = (o["p"] as? JsonPrimitive)?.content,
        )
    }
}
