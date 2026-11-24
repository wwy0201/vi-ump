package com.vi.ump.source

import com.vi.ump.config.CompiledEncrypt
import com.vi.ump.query.CryptoCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 请求参数加密（docs/03 §3.3）。
 *
 * 两处落点，都由 `request.encrypt` 驱动：
 * - **URL query**：`?data=<明文>` 这种表单型接口，把同名参数的值换成密文（并做 percent 编码）；
 * - **JSON body**：`{"data":"<明文>","appId":"1"}` 这种，只替换 `params` 列出的顶层字段。
 *
 * 只替换**字符串值**：数字/布尔/嵌套对象不碰（配置写错就原样发出去，比发出一坨坏密文好排查）。
 * `mode=whole` 时整个 body 换成密文，`bodyType` 应当写 `text`。
 */
internal object RequestCrypto {

    fun transform(url: String, body: String?, enc: CompiledEncrypt?): Pair<String, String?> {
        if (enc == null) return url to body

        if (enc.mode == "whole") {
            val plain = body?.takeIf { it.isNotEmpty() } ?: return url to body
            val cipher = CryptoCodec.encrypt(plain, enc.crypto) ?: return url to body
            return url to cipher
        }

        val newUrl = encryptUrl(url, enc) ?: url
        val newBody = body?.let { encryptBody(it, enc) } ?: body
        return newUrl to newBody
    }

    private fun encryptUrl(url: String, enc: CompiledEncrypt): String? {
        val q = url.indexOf('?')
        if (q < 0) return null
        val base = url.substring(0, q)
        val query = url.substring(q + 1)
        var changed = false
        val rebuilt = query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@joinToString pair
            val name = pair.substring(0, eq)
            val value = pair.substring(eq + 1)
            if (name !in enc.params) return@joinToString pair
            val cipher = CryptoCodec.encrypt(decodeQueryValue(value), enc.crypto) ?: return@joinToString pair
            changed = true
            "$name=${percentEncode(cipher)}"
        }
        return if (changed) "$base?$rebuilt" else null
    }

    private fun encryptBody(body: String, enc: CompiledEncrypt): String {
        val doc = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return body
        val out = buildJsonObject {
            doc.forEach { (k, v) ->
                if (k in enc.params && v is JsonPrimitive && v.isString) {
                    val cipher = CryptoCodec.encrypt(v.content, enc.crypto)
                    put(k, JsonPrimitive(cipher ?: v.content))
                } else {
                    put(k, v)
                }
            }
        }
        return out.toString()
    }

    /** query 里的值可能已经是 `%2B` 形式；先还原成明文再加密，否则会把编码后的串当明文加密 */
    private fun decodeQueryValue(v: String): String {
        if (!v.contains('%')) return v
        val sb = StringBuilder()
        var i = 0
        while (i < v.length) {
            if (v[i] == '%' && i + 2 < v.length) {
                val hi = hexDigit(v[i + 1])
                val lo = hexDigit(v[i + 2])
                if (hi != null && lo != null) {
                    sb.append(((hi shl 4) or lo).toChar())
                    i += 3
                    continue
                }
            }
            sb.append(v[i])
            i++
        }
        return sb.toString()
    }

    private fun hexDigit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    private val HEX = "0123456789ABCDEF"

    /** RFC 3986 的 unreserved 之外一律 percent 编码（密文里的 `+ / =` 必须被编码） */
    private fun percentEncode(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if ((c.code < 128 && c.isLetterOrDigit()) || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c)
            } else {
                for (b in c.toString().encodeToByteArray()) {
                    val v = b.toInt() and 0xFF
                    sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0x0F])
                }
            }
        }
        return sb.toString()
    }
}
