package com.vi.ump.config

import com.vi.ump.model.UmpConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * L1 → L2 装载。
 *
 * L1（Raw）只做三件事：JSON 语法、体积上限、嵌套深度上限。
 * 版本迁移也只在这一层做（L1 是 JsonElement，不需要维护旧的强类型模型）。
 */
object ConfigLoader {

    /** 1MB：超过这个体积的配置基本是误把数据文件当配置了 */
    const val MAX_BYTES = 1_000_000
    const val MAX_DEPTH = 20

    /**
     * 严格模式：`ignoreUnknownKeys = false`。
     * 配置是手写的，拼错一个键名如果静默忽略，用户会看到"配了但没生效"且毫无提示。
     */
    val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun load(text: String): UmpConfig {
        if (text.encodeToByteArray().size > MAX_BYTES) {
            throw ConfigException(
                ConfigIssue(IssueCode.TOO_BIG, "<root>", "配置超过 ${MAX_BYTES / 1000}KB 上限")
            )
        }

        val raw: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw ConfigException(
                ConfigIssue(IssueCode.JSON_SYNTAX, "<root>", e.message ?: "JSON 解析失败")
            )
        }

        if (depthOf(raw) > MAX_DEPTH) {
            throw ConfigException(
                ConfigIssue(IssueCode.TOO_BIG, "<root>", "嵌套深度超过 $MAX_DEPTH 层")
            )
        }

        return try {
            json.decodeFromString(UmpConfig.serializer(), normalize(raw).toString())
        } catch (e: Exception) {
            throw ConfigException(
                ConfigIssue(IssueCode.MISSING_REQUIRED, "<root>", "结构不符合 Schema: ${e.message}")
            )
        }
    }

    /** 也可以直接吃一个已经解析好的 JsonElement（版本迁移后常用这个入口） */
    fun fromElement(raw: JsonElement): UmpConfig = try {
        json.decodeFromString(UmpConfig.serializer(), normalize(raw).toString())
    } catch (e: Exception) {
        throw ConfigException(
            ConfigIssue(IssueCode.MISSING_REQUIRED, "<root>", "结构不符合 Schema: ${e.message}")
        )
    }

    /**
     * L1 归一化：把字段映射的字符串简写 `"id": "$.id"` 展开成 `{"from": "$.id"}`，
     * 数组简写 `["$.a","$.b"]` 展开成 `{"from": [...]}`（候选路径）。
     *
     * 放在 L1 而不是给 [com.vi.ump.model.FieldSpec] 加自定义序列化器，是因为
     * 归一化只影响"读配置"，不影响编译产物与运行期数据结构。
     */
    private fun normalize(raw: JsonElement): JsonElement {
        val root0 = stripDeprecated(raw as? JsonObject ?: return raw)
        val resources = root0["resources"] as? JsonObject ?: return root0
        val newResources = JsonObject(
            resources.mapValues { (key, value) ->
                when (key) {
                    "feeds", "details", "search", "tracks" -> mapSpecMap(value)
                    "categories", "filters" -> normalizeSpecObject(value)
                    else -> value
                }
            }
        )
        return JsonObject(root0 + ("resources" to newResources))
    }

    /**
     * 废弃键剥离。**这一层是它们唯一的容身处**：
     * `ignoreUnknownKeys = false` 是刻意的（拼错键名要立刻报出来），于是删掉一个字段就意味着
     * 所有老配置都会变成"结构不符合 Schema"、连源都进不了列表。旧配置不该因为升级而失效，
     * 所以在 L1 直接把这批键摘掉——它们本来也不再有任何行为。
     *
     * 当前名单：`flags.multiSourceSearch`（2026-11-16 删除，v1 不做多源搜索）。
     */
    private fun stripDeprecated(root: JsonObject): JsonObject {
        val flags = root["flags"] as? JsonObject ?: return root
        if ("multiSourceSearch" !in flags) return root
        return JsonObject(root + ("flags" to JsonObject(flags - "multiSourceSearch")))
    }

    private fun mapSpecMap(value: JsonElement): JsonElement {
        val obj = value as? JsonObject ?: return value
        return JsonObject(obj.mapValues { (_, v) -> normalizeSpecObject(v) })
    }

    private fun normalizeSpecObject(value: JsonElement): JsonElement {
        val obj = value as? JsonObject ?: return value
        val fields = obj["fields"] as? JsonObject ?: return obj
        val newFields = JsonObject(
            fields.mapValues { (_, v) ->
                when {
                    v is JsonPrimitive && v.isString -> buildJsonObject { put("from", v) }
                    v is JsonArray -> buildJsonObject { put("from", v) }
                    else -> v
                }
            }
        )
        return JsonObject(obj + ("fields" to newFields))
    }

    fun depthOf(el: JsonElement): Int = when (el) {
        is JsonObject -> 1 + (el.values.maxOfOrNull { depthOf(it) } ?: 0)
        is JsonArray -> 1 + (el.maxOfOrNull { depthOf(it) } ?: 0)
        else -> 1
    }
}
