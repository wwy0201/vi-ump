package com.vi.ump.source

/**
 * 导出前的脱敏（M5.5 / F8.7）。
 *
 * 原则：**只做高置信度替换**，宁可少改也不把配置改坏。
 *  - 键名命中敏感词 → 字符串值整段换成 `<已脱敏>`；
 *  - 字符串里出现 `Bearer xxx` → 只留 `Bearer ` 前缀；
 *  - `phone/mobile/tel/contact` 之类的键，值形如 11 位手机号 → 中间四位打码。
 *
 * 替换只发生在**字符串值内部**，JSON 结构（键、括号、逗号）一字不动，
 * 所以脱敏后的文件仍然可以通过"导入"通道回环进来（`validate_config.py` PASS）。
 *
 * 键名匹配用**分段**而不是无脑 `contains`：早期版本把 `author` 判成了 `auth`
 * （实测把 `"author": "vi-ump-samples"` 脱成 `<已脱敏>`），分段后 `author` 是一个
 * 独立词、与 `auth` 不同，不再误伤。
 */
fun maskForExport(json: String): String {
    val pair = Regex("\"([^\"]{1,60})\"\\s*:\\s*\"([^\"]{0,4000})\"")
    return pair.replace(json) { m ->
        val key = m.groupValues[1]
        val raw = m.groupValues[2]
        if (isSensitiveKey(key) && raw.isNotBlank()) {
            return@replace "\"$key\": \"<已脱敏>\""
        }
        var value = BEARER.replace(raw) { "${it.groupValues[1]}<已脱敏>" }
        if (isPhoneKey(key)) value = PHONE.replace(value) { "${it.groupValues[1]}****${it.groupValues[2]}" }
        if (value == raw) m.value else "\"$key\": \"$value\""
    }
}

/** 导出文件名：`vi-ump-<源 id>.json`（不含"脱敏"字样，避免用户以为源被改过） */
fun exportFileName(sourceId: String): String = "vi-ump-$sourceId.json"

private val BEARER = Regex("(Bearer\\s+)([A-Za-z0-9._\\-]{8,})")

/** 前后不许贴数字，长 id 中间一段长得像手机号时不误改 */
private val PHONE = Regex("(?<![0-9])(1[3-9]\\d)\\d{4}(\\d{4})(?![0-9])")

private val SENSITIVE_WORDS = setOf(
    "token", "cookie", "authorization", "auth", "secret", "password", "passwd",
    "signature", "credential", "credentials", "sessionid",
)

/** 连写形式（`api_key` / `access_key` / `session_id` 分段后靠这个兜住） */
private val SENSITIVE_JOINED_SUFFIX = listOf(
    "apikey", "accesskey", "secretkey", "privatekey", "sessionid",
    "accesstoken", "refreshtoken", "idtoken", "authtoken", "csrftoken",
)

private val PHONE_WORDS = setOf("phone", "mobile", "tel", "telephone", "contact", "msisdn")

internal fun isSensitiveKey(key: String): Boolean {
    val segments = keySegments(key)
    if (segments.any { it in SENSITIVE_WORDS }) return true
    val joined = segments.joinToString("")
    return SENSITIVE_JOINED_SUFFIX.any { joined.endsWith(it) }
}

internal fun isPhoneKey(key: String): Boolean {
    val lower = key.lowercase()
    return keySegments(key).any { it in PHONE_WORDS } || lower == "phonenumber"
}

/**
 * 按键名切词：`Authorization` → [authorization]、`access_token` → [access, token]、
 * `x-auth-token` → [x, auth, token]、`X-Api-Key` → [x, api, key]。
 * 分隔符（`_` `-` `.` 空格…）按边界处理，驼峰按大小写切换处理。
 */
internal fun keySegments(key: String): List<String> {
    val parts = ArrayList<String>()
    val buf = StringBuilder()
    var prevLower = false
    fun flush() {
        if (buf.isNotEmpty()) {
            parts.add(buf.toString())
            buf.clear()
        }
    }
    for (ch in key) {
        when {
            !ch.isLetterOrDigit() -> flush()
            ch.isUpperCase() -> {
                if (prevLower) flush()
                buf.append(ch.lowercaseChar())
            }
            else -> buf.append(ch.lowercaseChar())
        }
        prevLower = ch.isLowerCase()
    }
    flush()
    return parts
}
