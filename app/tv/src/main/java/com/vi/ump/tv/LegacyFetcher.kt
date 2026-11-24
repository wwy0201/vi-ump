package com.vi.ump.tv

import com.vi.ump.model.BodyType
import com.vi.ump.source.DEFAULT_RETRY
import com.vi.ump.source.DEFAULT_TIMEOUT_MS
import com.vi.ump.source.FetchResult
import com.vi.ump.source.Fetcher
import com.vi.ump.source.RETRY_DELAY_MS
import com.vi.ump.source.SourceException
import com.vi.ump.source.clampRetry
import com.vi.ump.source.clampTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.Handshake
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

/**
 * TV 4.4 的取数实现。
 *
 * `core:source` 的管线只依赖 `Fetcher` 接口，所以这里用 OkHttp 3.12（官方支持 API 9+）
 * 替换手机版的 Ktor/OkHttp 4.12（后者官方下限是 API 21）。
 * 超时/重试沿用 core 的上限与默认值，行为与手机版一致。
 */
class LegacyFetcher(
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    retry: Int = DEFAULT_RETRY,
) : Fetcher {

    private val timeoutMs: Long = clampTimeout(timeoutMs)
    private val retry: Int = clampRetry(retry)

    /** 最近一次请求的 TLS 握手信息，只用于自检诊断 */
    @Volatile
    var lastHandshake: Handshake? = null
        private set

    private val client: OkHttpClient by lazy {
        val (sslSocketFactory, trustManager) = TlsSupport.socketFactory()
        OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .sslSocketFactory(sslSocketFactory, trustManager)
            // MODERN_TLS 含 TLS 1.2；4.4 需要 Conscrypt 才稳
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .build()
    }

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
                if (attempt >= retry || !e.retriable) throw e
                attempt++
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /** 图片等二进制响应；与文本共用超时/重试/默认头 */
    suspend fun fetchBytes(url: String, requestHeaders: Map<String, String> = emptyMap()): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return withContext(Dispatchers.IO) {
                    val response = client.newCall(request(url, "GET", requestHeaders, null, BodyType.JSON)).execute()
                    response.use {
                        if (it.code() >= 400) {
                            throw SourceException("NET", "HTTP ${it.code()}: $url", retriable = it.code() >= 500)
                        }
                        it.body()?.bytes() ?: ByteArray(0)
                    }
                }
            } catch (e: SourceException) {
                if (attempt >= retry || !e.retriable) throw e
                attempt++
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun fetchOnce(
        url: String,
        method: String,
        requestHeaders: Map<String, String>,
        body: String?,
        bodyType: BodyType,
    ): FetchResult = withContext(Dispatchers.IO) {
        val request = request(url, method, requestHeaders, body, bodyType)
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw SourceException("E010", "请求失败: $url (${e.message})", e, retriable = true)
        }
        response.use {
            lastHandshake = it.handshake()
            val status = it.code()
            if (status >= 400) {
                throw SourceException("NET", "HTTP $status: $url", retriable = status >= 500)
            }
            FetchResult(
                url = url,
                status = status,
                body = it.body()?.string() ?: "",
                // 响应头必须回传：抖音的 ttwid 只在 Set-Cookie 里，读不到就只能用兜底常量
                headers = it.headers().names().associateWith { name ->
                    it.headers().values(name).joinToString(", ")
                },
            )
        }
    }

    private fun request(
        url: String,
        method: String,
        requestHeaders: Map<String, String>,
        body: String?,
        bodyType: BodyType,
    ): Request {
        val builder = Request.Builder().url(url)
        (DEFAULT_HEADERS + requestHeaders).forEach { (k, v) -> builder.header(k, v) }
        val httpMethod = method.uppercase()
        if (!body.isNullOrBlank() && httpMethod != "GET") {
            val contentType = if (bodyType == BodyType.JSON) {
                MediaType.parse("application/json; charset=utf-8")
            } else {
                MediaType.parse("application/x-www-form-urlencoded; charset=utf-8")
            }
            builder.method(httpMethod, RequestBody.create(contentType, body))
        } else {
            builder.method(httpMethod, null)
        }
        return builder.build()
    }
}

private val DEFAULT_HEADERS: Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
)
