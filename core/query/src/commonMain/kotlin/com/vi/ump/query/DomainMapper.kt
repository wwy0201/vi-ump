package com.vi.ump.query

import com.vi.ump.model.DomainField
import com.vi.ump.model.FieldSpec
import com.vi.ump.model.FieldType
import com.vi.ump.model.MediaKind
import com.vi.ump.model.MediaSummary

/**
 * 域模型映射：配置的 `fields` → [MediaSummary]。
 *
 * 两条失败规则（与 docs/04 §4.4 一致）：
 * 1. `id` / `title` 为空 → 整条丢弃（返回 null）
 * 2. 任何 `required: true` 的字段为空 → 整条丢弃
 */
object DomainMapper {

    /**
     * 单条记录 → 字段表。未知字段名直接报 E009（防拼错）。
     *
     * [cryptoByField]：字段级解密规则（键是配置里的字段名，如 `playUrl`）。
     * 由 `core:config` 在编译期烘焙好，这里只按 key 查一次，不碰全局 runtime。
     */
    fun mapFields(
        fields: Map<String, FieldSpec>,
        payload: Payload,
        htmlSelector: HtmlSelector = NoHtmlSelector,
        cryptoByField: Map<String, CryptoOptions> = emptyMap(),
        scope: Map<String, String> = emptyMap(),
    ): Map<DomainField, Any?> {
        val out = linkedMapOf<DomainField, Any?>()
        for ((key, spec) in fields) {
            val field = DomainField.fromKey(key)
                ?: throw QueryException(QueryError.UNDEFINED_FIELD, "未定义的域字段 `$key`")

            val raws = Extractors.of(spec, htmlSelector).extract(payload, scope)
            val transformed = raws.mapNotNull { TransformChain.apply(it, spec, cryptoByField[key]) }

            var value: Any? = when {
                spec.type == FieldType.STRING_LIST ->
                    transformed.ifEmpty { null }
                else ->
                    Cast.to(transformed.firstOrNull(), spec.type)
            }

            if (field == DomainField.DURATION) {
                value = Cast.durationToMillis(value, spec.type)
            }

            out[field] = value
        }
        return out
    }

    /** 字段表 → 域模型。`id` / `title` 缺失返回 null */
    fun toSummary(values: Map<DomainField, Any?>): MediaSummary? {
        val id = values[DomainField.ID]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val title = values[DomainField.TITLE]?.toString()?.takeIf { it.isNotBlank() } ?: return null

        return MediaSummary(
            id = id,
            title = title,
            kind = MediaKind.fromKey(values[DomainField.KIND]?.toString()),
            poster = values[DomainField.POSTER]?.toString()?.takeIf { it.isNotBlank() },
            imageUrl = values[DomainField.IMAGE_URL]?.toString()?.takeIf { it.isNotBlank() },
            subtitle = values[DomainField.SUBTITLE]?.toString()?.takeIf { it.isNotBlank() },
            year = values[DomainField.YEAR].asInt(),
            area = values[DomainField.AREA]?.toString()?.takeIf { it.isNotBlank() },
            type = values[DomainField.TYPE]?.toString()?.takeIf { it.isNotBlank() },
            score = values[DomainField.SCORE].asFloat(),
            durationMs = values[DomainField.DURATION].asLong(),
            tags = when (val t = values[DomainField.TAGS]) {
                is List<*> -> t.map { it.toString() }
                null -> emptyList()
                else -> listOf(t.toString())
            },
            playUrl = values[DomainField.PLAY_URL]?.toString()?.takeIf { it.isNotBlank() },
            trackCount = values[DomainField.TRACK_COUNT].asInt(),
            artist = values[DomainField.ARTIST]?.toString()?.takeIf { it.isNotBlank() },
            album = values[DomainField.ALBUM]?.toString()?.takeIf { it.isNotBlank() },
            detailResource = values[DomainField.DETAIL_RESOURCE]?.toString()?.takeIf { it.isNotBlank() },
        )
    }

    /** 组合：任一 required 字段缺失 → 返回 null（计入 skipped） */
    fun mapItem(
        fields: Map<String, FieldSpec>,
        payload: Payload,
        htmlSelector: HtmlSelector = NoHtmlSelector,
        cryptoByField: Map<String, CryptoOptions> = emptyMap(),
        scope: Map<String, String> = emptyMap(),
    ): MediaSummary? {
        val values = mapFields(fields, payload, htmlSelector, cryptoByField, scope)
        for ((key, spec) in fields) {
            if (!spec.required) continue
            val field = DomainField.fromKey(key) ?: continue
            if (values[field] == null) return null
        }
        return toSummary(values)
    }
}

private fun Any?.asInt(): Int? = when (this) {
    is Int -> this
    is Long -> this.toInt()
    is Float -> this.toInt()
    is Double -> this.toInt()
    is String -> this.trim().toIntOrNull()
    else -> null
}

private fun Any?.asFloat(): Float? = when (this) {
    is Int -> this.toFloat()
    is Long -> this.toFloat()
    is Float -> this
    is Double -> this.toFloat()
    is String -> this.trim().toFloatOrNull()
    else -> null
}

private fun Any?.asLong(): Long? = when (this) {
    is Int -> this.toLong()
    is Long -> this
    is Float -> this.toLong()
    is Double -> this.toLong()
    is String -> this.trim().toLongOrNull()
    else -> null
}
