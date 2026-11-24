package com.vi.ump.source

/**
 * query 值的百分号编码（RFC 3986 unreserved 之外一律 `%XX`，大写十六进制）。
 *
 * 为什么不用 `ktor.http.encodeURLParameter()`：
 * TV 壳（API 19）为了能用 OkHttp 3.12 把 `io.ktor` 整组排除了（见 `app/tv/build.gradle.kts`），
 * 运行时一碰 Ktor 的顶层函数就是 `NoSuchMethodError: io.ktor.http.CodecsKt`。
 * 签名与翻页参数都在**共用**的 `core:source` 里，所以这里只保留纯 Kotlin 实现。
 *
 * 与 Ktor 的差异只可能出现在空格（Ktor 可配 `+`，这里固定 `%20`）——签名串和翻页参数里都没有空格。
 * 非 ASCII 连续片段整段按 UTF-8 编码，避免把代理对拆坏。
 */
internal fun String.percentEncode(): String {
    val sb = StringBuilder(length)
    val pending = StringBuilder()
    fun flush() {
        if (pending.isEmpty()) return
        for (b in pending.toString().encodeToByteArray()) {
            sb.append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        pending.setLength(0)
    }
    for (ch in this) {
        when {
            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~" -> {
                flush()
                sb.append(ch)
            }
            else -> pending.append(ch)
        }
    }
    flush()
    return sb.toString()
}

private const val HEX = "0123456789ABCDEF"
