package com.vi.ump.query

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSONPath 支持子集（刻意不做过滤器与函数，见 docs/04 §2）：
 *
 * ```
 * $              根
 * .name          取字段
 * ['name']       取字段（引号写法也支持）
 * [0] / [-1]     数组下标（负数从尾部算）
 * [*]            数组所有元素（多结果）
 * ..name         递归查找所有同名字段（多结果）
 * ```
 */
sealed interface PathToken {
    data class Key(val name: String) : PathToken
    data class Index(val index: Int) : PathToken
    data object ArrayWildcard : PathToken
    data class Descend(val name: String) : PathToken
}

/** 编译后的路径：token 列表已解析好，求值时不再做字符串扫描 */
class PathExpr(
    val raw: String,
    val tokens: List<PathToken>,
) {
    /** 求值。返回**多个**结果（`[*]` / `..name` 天然多结果，其余最多 1 个） */
    fun eval(root: JsonElement): List<JsonElement> {
        var current: List<JsonElement> = listOf(root)
        for (token in tokens) {
            current = when (token) {
                is PathToken.Key -> current.mapNotNull { (it as? JsonObject)?.get(token.name) }
                is PathToken.Index -> current.mapNotNull { el ->
                    val arr = el as? JsonArray ?: return@mapNotNull null
                    val idx = if (token.index < 0) arr.size + token.index else token.index
                    arr.getOrNull(idx)
                }
                PathToken.ArrayWildcard -> current.flatMap { el ->
                    (el as? JsonArray)?.toList() ?: emptyList()
                }
                is PathToken.Descend -> current.flatMap { descend(it, token.name) }
            }
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    /** 便捷：取第一个非空标量 */
    fun evalFirstString(root: JsonElement): String? =
        eval(root).firstOrNull { it !is JsonNull }?.asText()

    override fun toString(): String = raw

    private fun descend(el: JsonElement, name: String): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    e[name]?.let { out.add(it) }
                    e.values.forEach { walk(it) }
                }
                is JsonArray -> e.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(el)
        return out
    }
}

object JsonPath {

    fun compile(raw: String): PathExpr {
        val text = raw.trim()
        if (!text.startsWith("$")) badPath("JSONPath 必须以 $ 开头: $raw")
        val tokens = mutableListOf<PathToken>()
        var i = 1
        while (i < text.length) {
            when (val c = text[i]) {
                '.' -> {
                    if (i + 1 < text.length && text[i + 1] == '.') {
                        val start = i + 2
                        val end = readNameEnd(text, start)
                        val name = text.substring(start, end)
                        if (name.isEmpty()) badPath(".. 后缺少字段名: $raw")
                        tokens += PathToken.Descend(name)
                        i = end
                    } else {
                        val start = i + 1
                        val end = readNameEnd(text, start)
                        val name = text.substring(start, end)
                        if (name.isEmpty()) badPath(". 后缺少字段名: $raw")
                        tokens += PathToken.Key(name)
                        i = end
                    }
                }
                '[' -> {
                    val close = text.indexOf(']', i + 1)
                    if (close < 0) badPath("方括号未闭合: $raw")
                    val inside = text.substring(i + 1, close).trim()
                    when {
                        inside == "*" -> tokens += PathToken.ArrayWildcard
                        inside.startsWith("'") && inside.endsWith("'") && inside.length >= 2 ->
                            tokens += PathToken.Key(inside.substring(1, inside.length - 1))
                        inside.toIntOrNull() != null -> tokens += PathToken.Index(inside.toInt())
                        else -> badPath("不支持的下标语法 [$inside]: $raw")
                    }
                    i = close + 1
                }
                else -> badPath("非法字符 '$c' @ $i: $raw")
            }
        }
        return PathExpr(text, tokens)
    }

    /** 读到下一个 `.` 或 `[` 为止 */
    private fun readNameEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i] != '.' && text[i] != '[') i++
        return i
    }
}

/** JsonPrimitive 的文本形式；非标量返回 null */
fun JsonElement.asText(): String? = when (this) {
    is JsonPrimitive -> if (this is JsonNull) null else content
    else -> null
}
