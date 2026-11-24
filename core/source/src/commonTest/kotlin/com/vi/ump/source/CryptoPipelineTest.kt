package com.vi.ump.source

import com.vi.ump.config.ConfigCompiler
import com.vi.ump.model.BodyType
import com.vi.ump.model.CryptoSpec
import com.vi.ump.model.DecryptSpec
import com.vi.ump.model.EncryptSpec
import com.vi.ump.model.FieldSpec
import com.vi.ump.model.LayoutSpec
import com.vi.ump.model.LayoutType
import com.vi.ump.model.Meta
import com.vi.ump.model.PageSpec
import com.vi.ump.model.RequestSpec
import com.vi.ump.model.ResourceSpec
import com.vi.ump.model.Resources
import com.vi.ump.model.RuntimeSpec
import com.vi.ump.model.UmpConfig
import com.vi.ump.query.CryptoCodec
import com.vi.ump.query.CryptoOptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val CKEY = "f5d965df75336270"
private const val CIV = "97b60394abc2fbe1"

/** 与 `runtime.crypto` 同口径的运行期参数，用来在测试侧**造密文**（引擎本身另有单测） */
private fun cryptoOpts() = CryptoOptions(
    "aes-128-cbc", CKEY.encodeToByteArray(), CIV.encodeToByteArray(), "pkcs7", "base64",
)

private fun cryptoSchema(
    feedRequest: RequestSpec,
    feedDecrypt: DecryptSpec? = null,
    posterDecrypt: DecryptSpec? = null,
): com.vi.ump.config.CompiledSchema = ConfigCompiler.compile(
    UmpConfig(
        meta = Meta(id = "c", name = "c"),
        runtime = RuntimeSpec(
            baseUrl = "https://example.com",
            imageBase = "https://img.example.com",
            // 三个挂点都只写"要做什么"，key/iv 从这里继承
            crypto = CryptoSpec(algo = "aes-128-cbc", key = CKEY, iv = CIV, padding = "pkcs7", output = "base64"),
        ),
        resources = Resources(
            feeds = mapOf(
                "main" to ResourceSpec(
                    request = feedRequest,
                    root = "\$.data",
                    list = "\$.items[*]",
                    fields = mapOf(
                        "id" to FieldSpec(from = JsonPrimitive("\$.id")),
                        "title" to FieldSpec(from = JsonPrimitive("\$.title")),
                        "poster" to FieldSpec(
                            from = JsonPrimitive("\$.cover"),
                            prefix = "{{imageBase}}",
                            decrypt = posterDecrypt,
                        ),
                    ),
                    decrypt = feedDecrypt,
                )
            ),
            details = mapOf(
                "d" to ResourceSpec(
                    request = RequestSpec(url = "{{baseUrl}}/api/detail?id={{id}}"),
                    root = "\$.data",
                    fields = mapOf(
                        "id" to FieldSpec(from = JsonPrimitive("\$.id")),
                        "title" to FieldSpec(from = JsonPrimitive("\$.title")),
                    ),
                )
            ),
        ),
        pages = listOf(PageSpec(id = "home", layout = LayoutSpec(type = LayoutType.POSTER_GRID), feed = "main")),
    )
)

private fun clientOf(handler: suspend (HttpRequestData) -> String): HttpClient = HttpClient(
    MockEngine { request ->
        respond(
            content = handler(request),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
)

/**
 * 通用加密在**管线**里的接线（docs/03 §3.3）。引擎本身在 core:query 有单测，
 * 这里只证明三件事：解密发生在解析之前、按路径解密会把明文当新响应体、字段解密接在抽取之后。
 */
class CryptoPipelineTest {

    @Test
    fun decryptsWholeResponseBody() = runTest {
        val plain = """{"data":{"items":[{"id":1,"title":"A"}],"has_more":0}}"""
        val cipher = CryptoCodec.encrypt(plain, cryptoOpts())
        assertNotNull(cipher)
        val client = clientOf { cipher }
        val schema = cryptoSchema(
            feedRequest = RequestSpec(url = "{{baseUrl}}/api/list?page={{page}}"),
            feedDecrypt = DecryptSpec(),
        )
        val result = SourcePipeline(schema, HttpFetcher(client)).list("main")
        assertEquals(1, result.items.size)
        assertEquals("A", result.items[0].title)
    }

    @Test
    fun decryptsFieldFromEnvelopeThenParsesIt() = runTest {
        // 真实站点最常见的形态：外层是明文壳，`data` 才是密文，解出来是一坨完整 JSON
        val plain = """{"data":{"items":[{"id":7,"title":"B"}],"has_more":0}}"""
        val cipher = CryptoCodec.encrypt(plain, cryptoOpts())
        assertNotNull(cipher)
        val client = clientOf { """{"code":0,"data":"$cipher"}""" }
        val schema = cryptoSchema(
            feedRequest = RequestSpec(url = "{{baseUrl}}/api/list?page={{page}}"),
            feedDecrypt = DecryptSpec(from = "\$.data"),
        )
        val result = SourcePipeline(schema, HttpFetcher(client)).list("main")
        assertEquals(1, result.items.size)
        assertEquals("B", result.items[0].title)
    }

    @Test
    fun decryptsSingleField() = runTest {
        val cover = CryptoCodec.encrypt("/a.jpg", cryptoOpts())
        assertNotNull(cover)
        val client = clientOf {
            """{"data":{"items":[{"id":1,"title":"A","cover":"$cover"}],"has_more":0}}"""
        }
        val schema = cryptoSchema(
            feedRequest = RequestSpec(url = "{{baseUrl}}/api/list?page={{page}}"),
            posterDecrypt = DecryptSpec(),
        )
        val result = SourcePipeline(schema, HttpFetcher(client)).list("main")
        // 解密发生在 trim/prefix 之前，所以 imageBase 还能正常拼上
        assertEquals("https://img.example.com/a.jpg", result.items[0].poster)
    }

    @Test
    fun encryptsRequestParamsBeforeSending() = runTest {
        var sentBody = ""
        val client = clientOf { request ->
            sentBody = (request.body as TextContent).text
            """{"data":{"items":[],"has_more":0}}"""
        }
        val schema = cryptoSchema(
            feedRequest = RequestSpec(
                method = "POST",
                url = "{{baseUrl}}/api/list",
                body = """{"data":"hello","appId":"1"}""",
                bodyType = BodyType.JSON,
                encrypt = EncryptSpec(mode = "params", params = listOf("data")),
            ),
        )
        SourcePipeline(schema, HttpFetcher(client)).list("main")

        val doc = Json.parseToJsonElement(sentBody).jsonObject
        // 站点收到的必须是密文，且未被解出明文
        assertEquals("1", doc["appId"]?.jsonPrimitive?.content)
        val cipher = doc["data"]?.jsonPrimitive?.content.orEmpty()
        assertEquals("hello", CryptoCodec.decrypt(cipher, cryptoOpts()))
    }
}
