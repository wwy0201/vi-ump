package com.vi.ump.source

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `percentEncode()` 的行为基线。
 *
 * 存在意义不止"编码对不对"：TV 壳排除了 `io.ktor`，签名和翻页参数一旦回退到 Ktor 的
 * `encodeURLParameter()` 就是运行时 `NoSuchMethodError`（真机上见到的就是它），
 * 所以这里把分界点钉住——`/ + =` 必须编码，unreserved 原样保留。
 */
class UrlCodecTest {

    @Test
    fun keepsUnreserved() {
        assertEquals("abcXYZ019-._~", "abcXYZ019-._~".percentEncode())
    }

    @Test
    fun encodesA_bogusSpecialChars() {
        // 抖音自定义 Base64 表里有 '/' 和 '+'，它们在 query 里必须转义
        assertEquals("%2F", "/".percentEncode())
        assertEquals("%2B", "+".percentEncode())
        assertEquals("%3D", "=".percentEncode())
        assertEquals("a%2Fb%2Bc%3D", "a/b+c=".percentEncode())
    }

    @Test
    fun encodesUtf8WithoutSplittingSurrogates() {
        assertEquals("%E9%93%83", "铃".percentEncode())
        // 4 字节字符（emoji）走代理对，不能拆成两个 3 字节片段
        assertEquals("%F0%9F%98%80", "\uD83D\uDE00".percentEncode())
    }

    @Test
    fun spaceBecomesPercent20() {
        assertEquals("%20", " ".percentEncode())
    }
}
