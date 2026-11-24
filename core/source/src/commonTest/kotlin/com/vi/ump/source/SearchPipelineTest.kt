package com.vi.ump.source

import com.vi.ump.config.CompiledSchema
import com.vi.ump.config.ConfigCompiler
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 搜索通路的契约（不是 UI 测试）：`search()` = `list()` + keyword 变量。
 *
 * 重点在**编码**：模板渲染是字符串替换，关键词里的空格 / 中文 / `&` / `#` 会把 URL 拼坏，
 * `&` 还会切断后面的参数。所以 `{{keyword}}` 放的是 URL 编码后的值，`{{keywordRaw}}` 才是原样。
 */
class SearchPipelineTest {

    private fun fields() = mapOf(
        "id" to FieldSpec(from = JsonPrimitive("\$.id")),
        "title" to FieldSpec(from = JsonPrimitive("\$.title")),
    )

    private fun schema(searchRequest: RequestSpec): CompiledSchema = ConfigCompiler.compile(
        UmpConfig(
            meta = Meta(id = "search-test", name = "search"),
            runtime = RuntimeSpec(baseUrl = "https://example.com"),
            resources = Resources(
                feeds = mapOf(
                    "home" to ResourceSpec(
                        request = RequestSpec(url = "{{baseUrl}}/api/home"),
                        root = "\$.data",
                        list = "\$.items[*]",
                        fields = fields(),
                    ),
                ),
                search = mapOf(
                    "byKeyword" to ResourceSpec(
                        request = searchRequest,
                        root = "\$.data",
                        list = "\$.items[*]",
                        fields = fields(),
                    ),
                ),
            ),
            pages = listOf(PageSpec(id = "home", layout = LayoutSpec(type = LayoutType.LIST_LINEAR))),
        ),
    )

    private fun body(pages: String = ""): String =
        """{"data":{"items":[{"id":"1","title":"t"}]$pages}}"""

    private fun client(onRequest: (HttpRequestData) -> Unit): HttpClient = HttpClient(
        MockEngine { request ->
            onRequest(request)
            respond(
                content = body(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    @Test
    fun keywordIsUrlEncodedIntoOneParameter() = runTest {
        var url: Url? = null
        val pipeline = SourcePipeline(
            schema = schema(
                RequestSpec(url = "{{baseUrl}}/api/search?q={{keyword}}&page={{page}}"),
            ),
            fetcher = HttpFetcher(client { url = it.url }),
        )

        val result = pipeline.search("byKeyword", "你好 & 世界 #1")

        // 原样词被 `&` 切成两个参数的话，这条断言会直接挂掉
        assertEquals("你好 & 世界 #1", url!!.parameters["q"])
        assertEquals("1", url!!.parameters["page"])
        assertEquals(1, result.items.size)
        // 请求走的是 search 资源，不是 feeds 里的 home
        assertEquals("https://example.com/api/search", "${url!!.protocol.name}://${url!!.host}${url!!.encodedPath}")
    }

    @Test
    fun keywordRawStaysOriginalInBody() = runTest {
        var raw: String? = null
        val pipeline = SourcePipeline(
            schema = schema(
                RequestSpec(
                    method = "POST",
                    url = "{{baseUrl}}/api/search?q={{keyword}}",
                    body = """{"kw":"{{keywordRaw}}"}""",
                ),
            ),
            fetcher = HttpFetcher(client { request ->
                raw = (request.body as? TextContent)?.text
            }),
        )

        pipeline.search("byKeyword", "a b")

        assertTrue(raw!!.contains("\"kw\":\"a b\""), "body 里应当是原值而非编码值: $raw")
    }
}
