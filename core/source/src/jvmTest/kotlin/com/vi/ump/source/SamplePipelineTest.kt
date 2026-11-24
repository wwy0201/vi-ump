package com.vi.ump.source

import com.vi.ump.config.ConfigCompiler
import com.vi.ump.model.MediaKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 用 `samples` 里的**真实站点配置** + `samples/fixtures` 里的**真实响应快照**跑整条管线。
 *
 * 这两组数据覆盖了 JSON 之外的两个真实形态：
 *  - YouTube：Atom 1.0 XML → `listSplit` 文本分条
 *  - 腾讯视频：`QZOutputJson={...};` 非标准 JSON → `strip` 剥壳后再走 JSONPath
 */
class SamplePipelineTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "samples").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 samples 目录，cwd=${System.getProperty("user.dir")}")
    }

    private fun sample(relative: String): File = File(repoRoot(), "samples/$relative")

    private fun pipelineOf(
        configName: String,
        body: String,
        contentType: String,
        onRequest: (String) -> Unit = {},
    ): SourcePipeline {
        val schema = ConfigCompiler.loadAndCompile(sample(configName).readText())
        val client = HttpClient(
            MockEngine { request ->
                onRequest(request.url.toString())
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, contentType),
                )
            }
        )
        return SourcePipeline(schema, HttpFetcher(client))
    }

    @Test
    fun youtubeAtomFeedIsSplitIntEntries() = runTest {
        var seenUrl = ""
        val pipeline = pipelineOf(
            configName = "youtube.json",
            body = sample("fixtures/youtube-feed.xml").readText(),
            contentType = "application/atom+xml",
        ) { seenUrl = it }

        val page = pipeline.list("channelUploads", 1, mapOf("channelId" to "UC_x5XG1OV2P6uZZ5FSM9Ttw"))

        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?channel_id=UC_x5XG1OV2P6uZZ5FSM9Ttw",
            seenUrl,
            "页面参数没有渲染进 URL",
        )
        assertEquals(3, page.items.size, "三条 <entry> 应切成三条条目")
        assertEquals(0, page.skipped, "不应有条目因缺必填字段被跳过")

        val first = page.items[0]
        assertEquals("dQw4w9WgXcQ", first.id)
        assertEquals("Building with the Gemini API", first.title)
        assertEquals("https://i4.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", first.poster)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", first.playUrl)
        assertEquals("Google for Developers", first.subtitle)
        assertEquals(2026, first.year)
        assertEquals(MediaKind.VIDEO, first.kind)
        // Shorts 的链接形态不同，说明条目是各自独立解析的
        assertEquals("https://www.youtube.com/shorts/aBcDeF12345", page.items[2].playUrl)
    }

    @Test
    fun qqEnvelopeIsStrippedAndPlayUrlIsAssembled() = runTest {
        val pipeline = pipelineOf(
            configName = "qq-video.json",
            body = sample("fixtures/qq-getinfo.txt").readText(),
            contentType = "text/plain",
        )

        val page = pipeline.list("episodeQuality", 1, mapOf("vid" to "b0136et5ztz"))

        assertEquals(1, page.items.size, "vl.vi 只有一条，应解析出一条")
        val item = page.items[0]
        assertEquals("b0136et5ztz", item.id)
        assertEquals("解密潮汕多神信仰", item.title)
        assertEquals("b0136et5ztz.m701.mp4", item.subtitle)
        // 官方拼接规则：ul.ui[0].url + fn + ?vkey= + fvkey
        // 注意 ul.ui[0].url 里原本带 CDN 的 IP 段（快照是 http://113.105.167.156/vhot2.qqvideo.tc.qq.com/...），
        // 配置里的 rewrite 刻意把它去掉 —— IP 绑机房、换网络环境就会 403，域名让 CDN 自己调度。
        // 所以断言的是去 IP 之后的形态，不是快照里的原样。
        val playUrl = assertNotNull(item.playUrl)
        assertTrue(
            playUrl.startsWith("http://vhot2.qqvideo.tc.qq.com/"),
            "播放地址必须以 ul.ui[0].url 去 IP 后的域名起头，实际是 $playUrl",
        )
        assertTrue(
            playUrl.contains("b0136et5ztz.m701.mp4?vkey="),
            "fn 与 vkey 的拼接顺序不对，实际是 $playUrl",
        )
        // td = "1186.92" 秒 → rewrite 取整 → int 按秒 ×1000
        assertEquals(1_186_000L, item.durationMs)
    }

    @Test
    fun qqDetailReadsTheSingleObjectAfterStrip() = runTest {
        val pipeline = pipelineOf(
            configName = "qq-video.json",
            body = sample("fixtures/qq-getinfo.txt").readText(),
            contentType = "text/plain",
        )

        val detail = pipeline.detail("episode", "b0136et5ztz")
        assertEquals("b0136et5ztz", detail?.summary?.id)
        assertEquals("解密潮汕多神信仰", detail?.summary?.title)
    }

    /**
     * 分页参数翻译（`paging.param / startAt / step / mode` → 请求变量）。
     *
     * 这里**不造响应数据**：请求体仍然是 `samples/fixtures/youtube-feed.xml` 的真实快照，
     * 只把配置换成一个带分页声明的探针，断言的是"第几页最终变成了 URL 里的什么值"。
     * 之所以需要独立探针：两个内置源本身都不支持翻页（YouTube Atom 无分页参数、腾讯频道页 offset 实测无效），
     * 拿它们当证据只能证明"没翻页"，证明不了"配置了就能翻页"。
     */
    @Test
    fun pagingSpecIsTranslatedIntoRequestVariables() = runTest {
        val seen = mutableListOf<String>()
        val pipeline = pipelineOfSpec(
            spec = PAGING_PROBE_SPEC,
            body = sample("fixtures/youtube-feed.xml").readText(),
            contentType = "application/atom+xml",
        ) { seen += it }
        val params = mapOf("channelId" to "UCsXVk37bltHxD1rDPwtNM8Q")

        // offset 模式：第 N 页 = (N-1) * pageSize（管线默认 pageSize=20），另留一个 {{page}} = 逻辑页码
        pipeline.list("probeOffset", page = 3, extra = params)
        val offsetUrl = seen.last()
        assertTrue(offsetUrl.contains("offset=40"), "offset 模式第 3 页应传 40，实际：$offsetUrl")
        assertTrue(offsetUrl.contains("page=3"), "{{page}} 仍是逻辑页码，实际：$offsetUrl")

        // page 模式 + startAt=0 + step=10：第 N 页 = 0 + (N-1)*10，且同名参数直接覆盖逻辑页码
        pipeline.list("probePage", page = 3, extra = params)
        val pageUrl = seen.last()
        assertTrue(pageUrl.contains("pageIndex=20"), "page 模式 startAt=0/step=10 第 3 页应是 20，实际：$pageUrl")
        assertTrue(!pageUrl.contains("page=3"), "参数名不是 page 时不该再注入 page=3，实际：$pageUrl")

        // 第 1 页：startAt 决定起点，不能永远是 1
        pipeline.list("probePage", page = 1, extra = params)
        assertTrue(seen.last().contains("pageIndex=0"), "startAt=0 时第 1 页应传 0，实际：${seen.last()}")

        // 终止判定：maxPage=5 时第 5 页之后必须停
        val last = pipeline.list("probeOffset", page = 1, extra = params)
        assertTrue(last.hasMore, "第 1 页还有下一页，实际 hasMore=${last.hasMore}")
        val bound = pipeline.list("probeOffset", page = 5, extra = params)
        assertTrue(!bound.hasMore, "page >= maxPage(5) 必须判到底，实际 hasMore=${bound.hasMore}")
    }

    private fun pipelineOfSpec(
        spec: String,
        body: String,
        contentType: String,
        onRequest: (String) -> Unit = {},
    ): SourcePipeline {
        val schema = ConfigCompiler.loadAndCompile(spec)
        val client = HttpClient(
            MockEngine { request ->
                onRequest(request.url.toString())
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, contentType),
                )
            }
        )
        return SourcePipeline(schema, HttpFetcher(client))
    }

    private companion object {
        /** 只用于断言 URL 构建；响应体来自真实快照，字段抽取规则与 samples/youtube.json 保持一致 */
        val PAGING_PROBE_SPEC = """
        {
          "schemaVersion": "1.0",
          "meta": { "id": "paging-probe", "name": "分页探针", "author": "SamplePipelineTest", "version": "0.0.0" },
          "runtime": { "baseUrl": "https://www.youtube.com", "encoding": "utf-8", "timeoutMs": 15000, "retry": 0 },
          "resources": {
            "feeds": {
              "probeOffset": {
                "request": {
                  "method": "GET",
                  "url": "{{baseUrl}}/feeds/videos.xml?channel_id={{p.channelId}}&offset={{offset}}&page={{page}}"
                },
                "responseType": "text",
                "listSplit": "(?s)<entry>(.*?)</entry>",
                "fields": {
                  "id": { "kind": "regex", "pattern": "<yt:videoId>(.*?)</yt:videoId>", "group": 1 },
                  "title": { "kind": "regex", "pattern": "<title>(.*?)</title>", "group": 1 },
                  "kind": { "kind": "const", "from": "video" }
                },
                "paging": { "mode": "offset", "param": "offset", "startAt": 0, "maxPage": 5, "emptyStop": true }
              },
              "probePage": {
                "request": {
                  "method": "GET",
                  "url": "{{baseUrl}}/feeds/videos.xml?channel_id={{p.channelId}}&pageIndex={{pageIndex}}"
                },
                "responseType": "text",
                "listSplit": "(?s)<entry>(.*?)</entry>",
                "fields": {
                  "id": { "kind": "regex", "pattern": "<yt:videoId>(.*?)</yt:videoId>", "group": 1 },
                  "title": { "kind": "regex", "pattern": "<title>(.*?)</title>", "group": 1 },
                  "kind": { "kind": "const", "from": "video" }
                },
                "paging": { "mode": "page", "param": "pageIndex", "startAt": 0, "step": 10, "maxPage": 20, "emptyStop": true }
              }
            }
          },
          "pages": [
            {
              "id": "home",
              "title": "分页探针",
              "layout": { "type": "poster_grid", "columns": 3, "ratio": "16:9", "gap": 12 },
              "feed": "probeOffset",
              "params": { "channelId": "UCsXVk37bltHxD1rDPwtNM8Q" }
            }
          ]
        }
        """.trimIndent()
    }
}
