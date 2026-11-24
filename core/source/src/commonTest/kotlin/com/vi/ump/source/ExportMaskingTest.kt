package com.vi.ump.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F8.7 脱敏的回归测试。
 *
 * 最重要的一条是 `author` 不被误伤：早期用 `contains("auth")` 判定，
 * 实测把 `"author": "vi-ump-samples"` 脱成了 `<已脱敏>`，
 * 把一份配置的作者信息抹掉了（也是"文件能回环但内容被改坏"的例子）。
 */
class ExportMaskingTest {

    @Test
    fun segmentsSplitSnakeCamelAndDash() {
        assertEquals(listOf("authorization"), keySegments("Authorization"))
        assertEquals(listOf("access", "token"), keySegments("access_token"))
        assertEquals(listOf("x", "auth", "token"), keySegments("x-auth-token"))
        assertEquals(listOf("x", "api", "key"), keySegments("X-Api-Key"))
        assertEquals(listOf("author"), keySegments("author"))
        assertEquals(listOf("access", "token"), keySegments("ACCESS_TOKEN"))
    }

    @Test
    fun sensitiveKeysMatchButOrdinaryWordsDoNot() {
        listOf(
            "token", "access_token", "Authorization", "api_key", "X-Api-Key",
            "accessKey", "session_id", "clientSecret", "password", "passwd", "signature", "cookie",
        ).forEach { assertTrue(isSensitiveKey(it), "should be sensitive: $it") }

        listOf(
            "author", "authorName", "authors", "title", "design", "sign", "description", "signCount",
        ).forEach { assertFalse(isSensitiveKey(it), "should NOT be sensitive: $it") }
    }

    @Test
    fun authorSurvivesExport() {
        val src = """{"meta":{"author":"vi-ump-samples","version":"1.0.0"}}"""
        val out = maskForExport(src)
        assertEquals(src, out)
    }

    @Test
    fun tokenAndBearerAndPhoneGetMasked() {
        val src = """
            {
              "meta": {"author": "vi-ump-samples"},
              "runtime": {"headers": {"Authorization": "Bearer abcdefghijklmnop", "X-Api-Key": "sk-live-1234567890"}},
              "contact": {"phone": "13812345678"},
              "resource": {"id": 18765432109}
            }
        """.trimIndent()
        val out = maskForExport(src)
        assertTrue(out.contains("\"author\": \"vi-ump-samples\""))
        assertTrue(out.contains("\"Authorization\": \"<已脱敏>\""))
        assertTrue(out.contains("\"X-Api-Key\": \"<已脱敏>\""))
        assertTrue(out.contains("\"phone\": \"138****5678\""))
        // 11 位的数字 id 不是手机号，不动
        assertTrue(out.contains("\"id\": 18765432109"))
    }

    @Test
    fun bearerInsideNonSensitiveValueKeepsPrefix() {
        val out = maskForExport("""{"note":"Bearer abcdefghijklmnop"}""")
        assertTrue(out.contains("\"note\": \"Bearer <已脱敏>\""))
    }

    @Test
    fun jsonStructureStaysValid() {
        val src = """{"a":{"token":"t1"},"b":["x","y"],"c":123}"""
        val out = maskForExport(src)
        val parsed = Json.parseToJsonElement(out).jsonObject
        assertEquals(setOf("a", "b", "c"), parsed.keys)
        assertEquals("""{"token":"<已脱敏>"}""", parsed["a"].toString())
        assertEquals("""["x","y"]""", parsed["b"].toString())
    }

    @Test
    fun exportFileNameUsesSourceId() {
        assertEquals("vi-ump-huangguo-drama.json", exportFileName("huangguo-drama"))
    }
}
