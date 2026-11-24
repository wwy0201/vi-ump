package com.vi.ump.source

import com.vi.ump.model.BodyType
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.delay

class SourceException(
    val code: String,
    message: String,
    cause: Throwable? = null,
    /** 是否值得再试一次：网络异常/超时、5xx 可以，4xx 不行（重试只会再挨一次同样的拒绝） */
    val retriable: Boolean = false,
) : RuntimeException("[$code] $message", cause)

data class FetchResult(
    val url: String,
    val status: Int,
    val body: String,
    /** 响应头（小写前保持原样）。签名类请求要从 `Set-Cookie` 里捡设备令牌 */
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 取数接口。管线只依赖它，不依赖 Ktor——
 * 这样测试、离线演示、Web 端代理都能替换实现。
 */
interface Fetcher {
    suspend fun fetch(
        url: String,
        method: String = "GET",
        requestHeaders: Map<String, String> = emptyMap(),
        body: String? = null,
        bodyType: BodyType = BodyType.JSON,
    ): FetchResult
}

// ── 系统级硬限制（配置只能往小改，不能越过）──────────────────
//
// 为什么要有上限：`runtime.timeoutMs` / `runtime.retry` 是站点配置里写的数字，
// 一份写错的配置（比如 timeoutMs=0 或 retry=99）能把界面卡死或打出几十次请求。
// 三个数字按"默认 + 上限"两段管：默认值保持原来的 15s / 2 次，越界一律夹回来。

/** 单次请求超时上限：30 秒（用户定的系统限制） */
const val MAX_TIMEOUT_MS: Long = 30_000L

/** 重试次数上限：3 次（不含首次请求） */
const val MAX_RETRY: Int = 3

/** 两次重试之间的间隔：固定 3 秒（不做指数退避，行为可预期） */
const val RETRY_DELAY_MS: Long = 3_000L

/** 配置没写或写 0 时的默认超时 */
const val DEFAULT_TIMEOUT_MS: Long = 15_000L

/** 配置没写时的默认重试次数 */
const val DEFAULT_RETRY: Int = 2

/** 超时夹到 `1s..30s`：0/负数没意义，超过 30s 一律按 30s 算 */
fun clampTimeout(ms: Long): Long = ms.coerceIn(1_000L, MAX_TIMEOUT_MS)

/** 重试夹到 `0..3`：负数当 0，超过 3 次一律按 3 次算 */
fun clampRetry(times: Int): Int = times.coerceIn(0, MAX_RETRY)

/**
 * HTTP 阶段的默认实现。`core:source` 是唯一认识 `HttpClient` 的模块，
 * 上层（render / feature）只能拿到 [FetchResult] 或域对象。
 *
 * 超时与重试都在这里收口：`SourcePipeline` 只管"发不发"，怎么重试由 fetcher 决定，
 * 于是首页、详情两条路走的是同一套策略。
 */
class HttpFetcher(
    private val client: HttpClient,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    retry: Int = DEFAULT_RETRY,
) : Fetcher {

    private val timeoutMs: Long = clampTimeout(timeoutMs)
    private val retry: Int = clampRetry(retry)

    override suspend fun fetch(
        url: String,
        method: String,
        requestHeaders: Map<String, String>,
        body: String?,
        bodyType: BodyType,
    ): FetchResult {
        var attempt = 0
        while (true) {
            try {
                return fetchOnce(url, method, requestHeaders, body, bodyType)
            } catch (e: SourceException) {
                // 4xx / 配置错这类"再试也一样"的不重试，否则只是把等待翻倍
                if (attempt >= retry || !e.retriable) throw e
                attempt++
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /**
     * 取**二进制**（图片这类）。与 [fetch] 共用超时/重试/默认头，只是不做字符集解码。
     *
     * 为什么不并进 [Fetcher] 接口：那个接口的契约是"文本响应"（响应体要按 `runtime.encoding` 解码），
     * 把图片塞进去会让所有测试替身都得实现一个它们用不到的方法。
     */
    suspend fun fetchBytes(url: String, requestHeaders: Map<String, String> = emptyMap()): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return fetchBytesOnce(url, requestHeaders)
            } catch (e: SourceException) {
                if (attempt >= retry || !e.retriable) throw e
                attempt++
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun fetchBytesOnce(url: String, requestHeaders: Map<String, String>): ByteArray {
        val response = try {
            client.request(url) {
                method = HttpMethod.Get
                timeout {
                    requestTimeoutMillis = this@HttpFetcher.timeoutMs
                    connectTimeoutMillis = this@HttpFetcher.timeoutMs
                    socketTimeoutMillis = this@HttpFetcher.timeoutMs
                }
                (DEFAULT_HEADERS + requestHeaders).forEach { (k, v) -> header(k, v) }
            }
        } catch (e: Exception) {
            throw SourceException("E010", "请求失败: $url (${e.message})", e, retriable = true)
        }
        val status = response.status.value
        if (status >= 400) {
            throw SourceException("NET", "HTTP $status: $url", retriable = status >= 500)
        }
        return response.bodyAsBytes()
    }

    private suspend fun fetchOnce(
        url: String,
        method: String,
        requestHeaders: Map<String, String>,
        body: String?,
        bodyType: BodyType,
    ): FetchResult {
        val httpMethod = HttpMethod.parse(method.uppercase())
        val response = try {
            client.request(url) {
                this.method = httpMethod
                // 配置里的 timeoutMs 逐请求生效：客户端是全局共享的，不能按源重建
                timeout {
                    requestTimeoutMillis = this@HttpFetcher.timeoutMs
                    connectTimeoutMillis = this@HttpFetcher.timeoutMs
                    socketTimeoutMillis = this@HttpFetcher.timeoutMs
                }
                // 配置里显式给的头优先；没给的用浏览器级默认值兜底
                (DEFAULT_HEADERS + requestHeaders).forEach { (k, v) -> header(k, v) }
                if (!body.isNullOrBlank() && httpMethod != HttpMethod.Get) {
                    setBody(body)
                    contentType(
                        if (bodyType == BodyType.JSON) ContentType.Application.Json
                        else ContentType.Application.FormUrlEncoded
                    )
                }
            }
        } catch (e: Exception) {
            throw SourceException("E010", "请求失败: $url (${e.message})", e, retriable = true)
        }

        val status = response.status.value
        if (status >= 400) {
            throw SourceException("NET", "HTTP $status: $url", retriable = status >= 500)
        }
        return FetchResult(
            url = url,
            status = status,
            body = response.bodyAsText(),
            headers = response.headers.entries().associate { it.key to it.value.joinToString(", ") },
        )
    }
}

/** 平台默认客户端。引擎由各平台源集（androidMain/jvmMain 的 okhttp）自动发现 */
fun createHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    // 兜底超时：即使某条请求忘了带 request 级 timeout，也不会无限挂着
    install(HttpTimeout) {
        requestTimeoutMillis = MAX_TIMEOUT_MS
        connectTimeoutMillis = MAX_TIMEOUT_MS
        socketTimeoutMillis = MAX_TIMEOUT_MS
    }
}

/**
 * 没配置显式头时补的默认值。
 *
 * 不只是礼貌：Google / 腾讯这类站点会对"看不出是浏览器"的请求返回 404 或空响应，
 * 配 `User-Agent` + `Accept-Language` 能解决大半。配置里的 `request.headers` 仍然优先。
 */
private val DEFAULT_HEADERS: Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
)
