package com.vi.ump.source

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 系统级网络限制的口径：超时最多 30s、重试最多 3 次、间隔固定 3s。
 *
 * 这些断言盯的是"配置写错也伤不到界面"：越界的数在 fetcher 里就被夹住，
 * 而不是等上层每处调用各判一次。
 */
class HttpPolicyTest {

    @Test
    fun timeoutIsClampedToRange() {
        assertEquals(1_000L, clampTimeout(0), "0 或负数按 1s 算")
        assertEquals(1_000L, clampTimeout(-5), "负数按 1s 算")
        assertEquals(15_000L, clampTimeout(15_000), "区间内的值原样保留")
        assertEquals(MAX_TIMEOUT_MS, clampTimeout(60_000), "超过 30s 一律 30s")
    }

    @Test
    fun retryIsClampedToRange() {
        assertEquals(0, clampRetry(-1), "负数按 0 次")
        assertEquals(2, clampRetry(2), "区间内的值原样保留")
        assertEquals(MAX_RETRY, clampRetry(99), "超过 3 次一律 3 次")
    }

    @Test
    fun serverErrorIsRetriedUntilSuccess() = runTest {
        var calls = 0
        val client = HttpClient(
            MockEngine {
                calls++
                if (calls < 3) {
                    respond("boom", HttpStatusCode.InternalServerError)
                } else {
                    respond(
                        """{"ok":1}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        )
        val res = HttpFetcher(client, timeoutMs = 15_000, retry = 2).fetch("https://example.com/x")
        assertEquals(200, res.status)
        assertEquals(3, calls, "500 应重试，第三次成功后不再发")
    }

    @Test
    fun clientErrorIsNotRetried() = runTest {
        var calls = 0
        val client = HttpClient(
            MockEngine {
                calls++
                respond("nope", HttpStatusCode.NotFound)
            }
        )
        val e = assertFailsWith<SourceException> {
            HttpFetcher(client, timeoutMs = 15_000, retry = 3).fetch("https://example.com/x")
        }
        assertEquals("NET", e.code)
        assertEquals(1, calls, "4xx 是确定的拒绝，重试只会把等待翻倍")
    }

    @Test
    fun networkFailureRetriesThenGivesUp() = runTest {
        var calls = 0
        val client = HttpClient(
            MockEngine {
                calls++
                throw IllegalStateException("coaxation reset")
            }
        )
        val e = assertFailsWith<SourceException> {
            HttpFetcher(client, timeoutMs = 15_000, retry = 3).fetch("https://example.com/x")
        }
        assertEquals("E010", e.code)
        assertEquals(4, calls, "重试 3 次 = 首次 + 3 次重发")
    }
}
