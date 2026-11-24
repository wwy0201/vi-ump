package com.vi.ump.query

import com.vi.ump.model.FieldSpec
import com.vi.ump.model.FieldType

/**
 * 变换链。**顺序固定，不可配置**（见 docs/04 §4.3）：
 *
 * ```
 * raw → decrypt → trim → rewrite[] → map → prefix+value+suffix → cast(见 Cast) → default
 * ```
 *
 * `decrypt` 是唯一可选的**前置**步骤（docs/03 §3.3 单字段解密）：密文必须先解成明文，
 * 后面的 trim/rewrite 才有意义。`crypto` 为 null 时这一步整体跳过。
 *
 * 顺序可变会导致"先拼前缀再 map"和"先 map 再拼前缀"结果不同，用户无法预期。
 */
object TransformChain {

    fun apply(raw: String?, spec: FieldSpec, crypto: CryptoOptions? = null): String? {
        var v: String? = raw

        if (crypto != null) v = CryptoCodec.decrypt(v.orEmpty(), crypto)

        if (spec.trim) v = v?.trim()

        if (v != null) {
            for (rule in spec.rewrite) {
                v = runCatching { Regex(rule.pattern).replace(v!!, rule.replacement) }
                    .getOrDefault(v!!)
            }
        }

        // 枚举映射：命中即替换，未命中保留原值
        if (v != null && spec.map.isNotEmpty()) {
            v = spec.map[v] ?: v
        }

        if (v != null && (spec.prefix.isNotEmpty() || spec.suffix.isNotEmpty())) {
            v = spec.prefix + v + spec.suffix
        }

        // 全空兜底
        if (v.isNullOrBlank()) v = spec.default

        return v?.takeIf { it.isNotBlank() }
    }
}

/** 类型转换：只在标量字段使用；`stringList` 由调用方按多值处理 */
object Cast {

    fun to(v: String?, type: FieldType): Any? {
        if (v == null) return null
        return when (type) {
            FieldType.STRING -> v
            FieldType.INT -> v.trim().toIntOrNull()
            FieldType.LONG -> v.trim().toLongOrNull()
            FieldType.FLOAT -> v.trim().toFloatOrNull()
            FieldType.BOOL -> when (v.trim().lowercase()) {
                "1", "true", "yes", "是" -> true
                "0", "false", "no", "否" -> false
                else -> null
            }
            FieldType.STRING_LIST -> listOf(v)
        }
    }

    /**
     * `duration` 域字段的统一口径：
     * - `type=int` 按**秒** → 乘 1000
     * - `type=long` 按**毫秒**
     */
    fun durationToMillis(v: Any?, type: FieldType): Long? {
        val num = when (v) {
            is Int -> v.toLong()
            is Long -> v
            is Float -> v.toLong()
            is Double -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        } ?: return null
        return if (type == FieldType.INT) num * 1000 else num
    }
}
