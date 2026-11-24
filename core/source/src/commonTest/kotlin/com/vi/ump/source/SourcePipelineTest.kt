package com.vi.ump.source

import com.vi.ump.config.CompiledSchema
import com.vi.ump.config.ConfigCompiler
import com.vi.ump.model.EpisodesSpec
import com.vi.ump.model.ExtractorKind
import com.vi.ump.model.FieldSpec
import com.vi.ump.model.FieldType
import com.vi.ump.model.GroupNamesSpec
import com.vi.ump.model.ItemSplitSpec
import com.vi.ump.model.LayoutSpec
import com.vi.ump.model.LayoutType
import com.vi.ump.model.MediaKind
import com.vi.ump.model.Meta
import com.vi.ump.model.PageSpec
import com.vi.ump.model.PagingMode
import com.vi.ump.model.PagingSpec
import com.vi.ump.model.RequestSpec
import com.vi.ump.model.ResourceSpec
import com.vi.ump.model.Resources
import com.vi.ump.model.ResponseType
import com.vi.ump.model.RuntimeSpec
import com.vi.ump.model.TrackMode
import com.vi.ump.model.TrackSpec
import com.vi.ump.model.UmpConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val D = "\$"

private fun schema(hasMoreFlag: Boolean = true): CompiledSchema {
    val config = UmpConfig(
        meta = Meta(id = "t", name = "t"),
        runtime = RuntimeSpec(baseUrl = "https://example.com", imageBase = "https://img.example.com"),
        resources = Resources(
            feeds = mapOf(
                "main" to ResourceSpec(
                    request = RequestSpec(url = "{{baseUrl}}/api/list?page={{page}}&size={{pageSize}}"),
                    root = "$D.data",
                    list = "$D.items[*]",
                    fields = mapOf(
                        "id" to FieldSpec(from = JsonPrimitive("$D.id")),
                        "title" to FieldSpec(from = JsonPrimitive("$D.title")),
                        "poster" to FieldSpec(from = JsonPrimitive("$D.cover"), prefix = "{{imageBase}}"),
                    ),
                    paging = PagingSpec(
                        mode = PagingMode.PAGE,
                        hasMore = "$D.has_more",
                        emptyStop = hasMoreFlag,
                    ),
                )
            ),
            details = mapOf(
                "d" to ResourceSpec(
                    request = RequestSpec(url = "{{baseUrl}}/api/detail?id={{id}}"),
                    root = "$D.data",
                    fields = mapOf(
                        "id" to FieldSpec(from = JsonPrimitive("$D.id")),
                        "title" to FieldSpec(from = JsonPrimitive("$D.title")),
                        "description" to FieldSpec(from = JsonPrimitive("$D.desc")),
                        "actors" to FieldSpec(from = JsonPrimitive("$D.actors[*]"), type = FieldType.STRING_LIST),
                    ),
                )
            ),
        ),
        pages = listOf(
            PageSpec(id = "home", layout = LayoutSpec(type = LayoutType.POSTER_GRID), feed = "main")
        ),
    )
    return ConfigCompiler.compile(config)
}

private fun clientOf(
    handler: suspend (HttpRequestData) -> String,
): HttpClient = HttpClient(
    MockEngine { request ->
        respond(
            content = handler(request),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
)

class SourcePipelineTest {

    @Test
    fun listMapsItemsAndRendersImagePrefix() = runTest {
        val client = clientOf {
            """{"data":{"items":[{"id":1,"title":"A","cover":"/a.jpg"},{"id":2,"title":"B","cover":"/b.jpg"}],"has_more":1}}"""
        }
        val result = SourcePipeline(schema(), HttpFetcher(client)).list("main")
        assertEquals(2, result.items.size)
        assertEquals("A", result.items[0].title)
        assertEquals("https://img.example.com/a.jpg", result.items[0].poster)
        assertTrue(result.hasMore)
        assertEquals(0, result.skipped)
    }

    @Test
    fun urlTemplateIsRendered() = runTest {
        var seen = ""
        val client = clientOf { request ->
            seen = request.url.toString()
            """{"data":{"items":[],"has_more":0}}"""
        }
        SourcePipeline(schema(), HttpFetcher(client)).list("main", page = 3)
        assertEquals("https://example.com/api/list?page=3&size=20", seen)
    }

    @Test
    fun brokenItemIsSkippedNotFatal() = runTest {
        val client = clientOf {
            """{"data":{"items":[{"id":1,"title":"A"},{"id":2,"title":""}],"has_more":0}}"""
        }
        val result = SourcePipeline(schema(), HttpFetcher(client)).list("main")
        assertEquals(1, result.items.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun hasMoreFollowsFlag() = runTest {
        val client = clientOf { """{"data":{"items":[],"has_more":0}}""" }
        val result = SourcePipeline(schema(), HttpFetcher(client)).list("main")
        assertTrue(!result.hasMore)
    }

    @Test
    fun detailMapsScalarsAndList() = runTest {
        val client = clientOf {
            """{"data":{"id":"9","title":"X","desc":"D","actors":["p1","p2"]}}"""
        }
        val detail = SourcePipeline(schema(), HttpFetcher(client)).detail("d", "9")
        assertEquals("9", detail?.summary?.id)
        assertEquals("D", detail?.description)
        assertEquals(listOf("p1", "p2"), detail?.actors)
    }

    @Test
    fun templateEngineLeavesUnknownVarEmpty() {
        val engine = TemplateEngine()
        assertEquals("https://a/x?p=", engine.render("https://a/x?p={{nope}}", emptyMap()))
        assertEquals(listOf("baseUrl", "page"), engine.variablesOf("{{baseUrl}}/{{page}}"))
    }

    /**
     * 游标分页（M6.6，2026-11-17）：首页"Next"是 HTML 链接的站点（e-hentai）必须能顺着翻。
     *
     * 这里用文本通路 + [PagingSpec.cursorPattern] 复现真实形态：第 1 页用 `request.url`、
     * 之后的页用 `nextUrl`（`{{cursor}}` 就是上一页抽到的完整 URL），抽不到游标就判到底。
     */
    @Test
    fun cursorPagingFollowsHtmlNextLink() = runTest {
        val seen = mutableListOf<String>()
        val client = clientOf { request ->
            seen += request.url.toString()
            when (request.url.toString()) {
                "https://example.com/" ->
                    """<item>id="1"<t>A</t></item><item>id="2"<t>B</t></item><next>https://example.com/?next=99</next>"""
                "https://example.com/?next=99" -> """<item>id="3"<t>C</t></item>"""
                else -> """<item>id="9"<t>Z</t></item>"""
            }
        }
        val pipeline = SourcePipeline(cursorHtmlSchema(), HttpFetcher(client))

        val p1 = pipeline.list("main", 1)
        assertEquals(2, p1.items.size)
        assertTrue(p1.hasMore, "第 1 页给了 next 链接，必须还有下一页")

        val p2 = pipeline.list("main", 2)
        assertEquals("https://example.com/?next=99", seen[1], "第 2 页必须发 nextUrl 展开后的地址")
        assertEquals("C", p2.items[0].title)
        assertTrue(!p2.hasMore, "第 2 页没有 next 链接 = 到底")

        // 链在第 2 页断了，第 3 页不该退回第 1 页重发一遍（那会把首页内容重复追加）
        val p3 = pipeline.list("main", 3)
        assertEquals(0, p3.items.size)
        assertTrue(!p3.hasMore)
        assertEquals(2, seen.size)
    }
}

/**
 * 文本通路 + 游标的 schema：`listSplit` 切条、字段全 regex、paging 走 cursor 模式。
 * 与 e-hentai 首页的形态一致（只是换成了更小的假站点）。
 */
private fun cursorHtmlSchema(): CompiledSchema {
    val config = UmpConfig(
        meta = Meta(id = "c", name = "c"),
        runtime = RuntimeSpec(baseUrl = "https://example.com"),
        resources = Resources(
            feeds = mapOf(
                "main" to ResourceSpec(
                    request = RequestSpec(url = "{{baseUrl}}/"),
                    responseType = ResponseType.HTML,
                    listSplit = "(?s)<item>(.*?)</item>",
                    fields = mapOf(
                        "id" to FieldSpec(kind = ExtractorKind.REGEX, pattern = "id=\"(\\d+)\"", group = 1),
                        "title" to FieldSpec(kind = ExtractorKind.REGEX, pattern = "<t>([^<]*)</t>", group = 1),
                    ),
                    paging = PagingSpec(
                        mode = PagingMode.CURSOR,
                        cursorPattern = "<next>(https://example\\.com/\\?next=\\d+)</next>",
                        cursorGroup = 1,
                        nextUrl = "{{cursor}}",
                        maxPage = 3,
                    ),
                )
            ),
            details = emptyMap(),
        ),
        pages = listOf(
            PageSpec(id = "home", layout = LayoutSpec(type = LayoutType.POSTER_GRID), feed = "main")
        ),
    )
    return ConfigCompiler.compile(config)
}

/**
 * 图集 id 表的"总条数 → 页数"（2026-11-18 加）。
 *
 * 真实故障：e-hentai 一本 455 张的图集，页面写着 `Showing 21 - 40 of 455`，
 * 而配置里 `paging.maxPage` 只写了 3、`maxItems` 只写了 60，结果标题说 455 页、图集只有 60 项。
 * 这里复现同一形态：每页 20 条、总数写在列表页正文里、id 表页并发抓。
 */
class EpisodeCountTest {

    private fun gallerySchema(
        withTotal: Boolean = true,
        maxItems: Int = 1000,
        perPage: Int = 20,
    ): CompiledSchema {
        val config = UmpConfig(
            meta = Meta(id = "g", name = "g"),
            runtime = RuntimeSpec(baseUrl = "https://example.com"),
            resources = Resources(
                feeds = mapOf(
                    "main" to ResourceSpec(
                        request = RequestSpec(url = "{{baseUrl}}/"),
                        responseType = ResponseType.HTML,
                        listSplit = "(?s)<item>(.*?)</item>",
                        fields = mapOf(
                            "id" to FieldSpec(kind = ExtractorKind.REGEX, pattern = "id=\"(\\d+)\"", group = 1),
                            "title" to FieldSpec(kind = ExtractorKind.REGEX, pattern = "<t>([^<]*)</t>", group = 1),
                        ),
                    )
                ),
                details = mapOf(
                    "d" to ResourceSpec(
                        request = RequestSpec(url = "{{baseUrl}}/g/{{id}}/"),
                        responseType = ResponseType.HTML,
                        fields = mapOf(
                            // toSummary 要求 id + title 都在，否则 detail 直接返回 null（与真实配置一致）
                            "id" to FieldSpec(kind = ExtractorKind.REGEX, pattern = "data-id=\"(\\d+)\"", group = 1),
                            "title" to FieldSpec(kind = ExtractorKind.REGEX, pattern = "<h1>([^<]*)</h1>", group = 1),
                            // kind=image 才会走 lazyItems 分支（视频源的图集是另一条路）
                            "kind" to FieldSpec(kind = ExtractorKind.CONST, from = JsonPrimitive("image")),
                        ),
                        episodes = EpisodesSpec(
                            request = RequestSpec(url = "{{baseUrl}}/g/{{id}}/?p={{p}}"),
                            paging = PagingSpec(
                                mode = PagingMode.PAGE,
                                param = "p",
                                startAt = 0,
                                step = 1,
                                maxPage = 60,
                                totalPattern = if (withTotal) {
                                    "Showing\\s+[\\d,]+\\s*-\\s*[\\d,]+\\s+of\\s+(\\d+)"
                                } else {
                                    null
                                },
                                totalGroup = 1,
                                perPage = perPage,
                            ),
                            idsSplit = "href=\"https://example\\.com/(s/[0-9a-f]+/\\d+-\\d+)\"",
                            idsGroup = 1,
                            item = ResourceSpec(
                                request = RequestSpec(url = "{{baseUrl}}/{{episode}}"),
                                responseType = ResponseType.HTML,
                                fields = mapOf(
                                    "playUrl" to FieldSpec(
                                        kind = ExtractorKind.REGEX,
                                        pattern = "<img id=\"img\" src=\"([^\"]+)\"",
                                        group = 1,
                                    ),
                                ),
                            ),
                            groupName = "图集",
                            maxItems = maxItems,
                            concurrency = 6,
                            lazyItems = true,
                        ),
                    )
                ),
            ),
            pages = listOf(PageSpec(id = "home", layout = LayoutSpec(type = LayoutType.POSTER_GRID), feed = "main")),
        )
        return ConfigCompiler.compile(config)
    }

    /** 455 张 = 23 个 id 表页（20×22 + 15） */
    private fun galleryPage(p: Int, total: Int = 455): String {
        val start = p * 20 + 1
        val count = ((total - p * 20)).coerceIn(0, 20)
        val rows = (0 until count).joinToString("") { k ->
            // id 形态与真实站点一致：`<hash>/<gid>-<n>`（只有一处连字符）
            val n = p * 20 + k
            "<a href=\"https://example.com/s/aa${p}${k}/9-${n}\">t</a>"
        }
        return "<div>Showing $start - ${start + count - 1} of $total</div>$rows"
    }

    @Test
    fun idPagesFollowStatedTotalAndFetchConcurrently() = runTest {
        // 并发抓页 → 记录请求的容器必须是线程安全的：普通 `mutableListOf` 在 6 路并发下会丢写，
        // 本用例曾因此偶发 "expected 23 but was 22"（不是产品缺陷，是夹具的竞态）
        val seen = Mutex()
        val seenUrls = mutableListOf<String>()
        val client = clientOf { request ->
            val url = request.url.toString()
            seen.withLock { seenUrls += url }
            // 详情页（地址里没有 ?p=）
            if (!url.contains("?p=")) {
                "<h1>G</h1><div data-id=\"9\"></div>"
            } else {
                val p = Regex("\\?p=(\\d+)").find(url)!!.groupValues[1].toInt()
                galleryPage(p)
            }
        }
        val pipeline = SourcePipeline(gallerySchema(), HttpFetcher(client))
        val detail = pipeline.detail("d", "9")
        val tracks = detail!!.trackGroups.first().tracks

        assertEquals(455, tracks.size, "标题写着 455 页，图集条目就必须是 455")
        // 23 个 id 表页（p=0..22）；懒取模式下不该有任何 /s/ 内页请求
        val idPageUrls = seenUrls.filter { it.contains("?p=") }
        assertEquals(23, idPageUrls.size)
        assertTrue(idPageUrls.all { it.contains("example.com/g/9/?p=") })
        assertTrue(seenUrls.none { it.startsWith("https://example.com/s/") }, "lazyItems 下不该请求内页")
    }

    @Test
    fun maxItemsIsSafetyValveNotPaginationLimit() = runTest {
        // 老配置常见的两个"手调值"：maxItems=60（当年为了省流量）、perPage 不写。
        // 总数已经写在页面上（455），这两个值都不该再把条目数砍回去。
        val client = clientOf { request ->
            val url = request.url.toString()
            if (!url.contains("?p=")) {
                "<h1>G</h1><div data-id=\"9\"></div>"
            } else {
                val p = Regex("\\?p=(\\d+)").find(url)!!.groupValues[1].toInt()
                galleryPage(p)
            }
        }
        val pipeline = SourcePipeline(
            gallerySchema(withTotal = true, maxItems = 60, perPage = 0),
            HttpFetcher(client),
        )
        val tracks = pipeline.detail("d", "9")!!.trackGroups.first().tracks
        assertEquals(455, tracks.size, "maxItems 只是保险丝；perPage 不写时应按第 1 页命中数（20）算页数")
    }

    @Test
    fun withoutTotalFallsBackToEmptyStop() = runTest {
        // 同 schema 但去掉 totalPattern：回到逐页翻、翻到空页就停
        val schema = gallerySchema(withTotal = false)
        val client = clientOf { request ->
            val url = request.url.toString()
            if (!url.contains("?p=")) "<h1>G</h1><div data-id=\"9\"></div>"
            else {
                val p = Regex("\\?p=(\\d+)").find(url)!!.groupValues[1].toInt()
                if (p <= 3) galleryPage(p, total = 80) else ""
            }
        }
        val pipeline = SourcePipeline(schema, HttpFetcher(client))
        val detail = pipeline.detail("d", "9")
        val tracks = detail!!.trackGroups.firstOrNull()?.tracks ?: emptyList()
        assertEquals(80, tracks.size)
    }
}

class CacheTest {

    @Test
    fun lruEvictsLeastRecentlyUsed() = runTest {
        val cache = LruCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.get("a")
        cache.put("c", 3)
        assertEquals(2, cache.size())
        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a"))
    }

    @Test
    fun inFlightGateRunsBlockOncePerKey() = runTest {
        val gate = InFlightGate<String, Int>()
        var calls = 0
        val first = async { gate.run("k") { kotlinx.coroutines.delay(10); calls++; 42 } }
        val second = async { gate.run("k") { kotlinx.coroutines.delay(10); calls++; 42 } }
        assertEquals(42, first.await())
        assertEquals(42, second.await())
        assertEquals(1, calls)
    }
}

/**
 * `resources.tracks` 两种切分形态的算法探针。
 *
 * 载荷不是 App 的数据源，而是"一个字段里塞着线路与集数"这一结构本身：
 * 验证切分顺序（`groupSplit → itemSplit → pairSplit`）与边界（地址里含 `$` 时不能被截断），
 * 与任何站点的真实响应无关。
 */
class TrackSplitTest {

    private val gs = "\$".repeat(3)

    private fun schema(mode: TrackMode, summaryKind: String, trackKey: String): CompiledSchema {
        val track = when (mode) {
            TrackMode.ARRAY -> TrackSpec(
                mode = TrackMode.ARRAY,
                from = "$D.photos[*]",
                fields = mapOf(
                    "title" to FieldSpec(from = JsonPrimitive("$D.t")),
                    "url" to FieldSpec(from = JsonPrimitive("$D.u"), prefix = "{{imageBase}}"),
                    "thumb" to FieldSpec(from = JsonPrimitive("$D.th"), prefix = "{{imageBase}}"),
                ),
            )
            TrackMode.DELIMITED -> TrackSpec(
                mode = TrackMode.DELIMITED,
                groupNames = GroupNamesSpec(from = "$D.from_list"),
                // pairSplit 用 `$`：每项是"标题$地址"（`ItemSplitSpec` 的默认值是 null）
                items = ItemSplitSpec(from = "$D.play_url", pairSplit = "$D"),
            )
        }
        val config = UmpConfig(
            meta = Meta(id = "t", name = "t"),
            runtime = RuntimeSpec(baseUrl = "https://example.com", imageBase = "https://img.example.com"),
            resources = Resources(
                details = mapOf(
                    "d" to ResourceSpec(
                        request = RequestSpec(url = "{{baseUrl}}/api/detail?id={{id}}"),
                        root = "$D.data",
                        fields = mapOf(
                            "id" to FieldSpec(from = JsonPrimitive("$D.id")),
                            "title" to FieldSpec(from = JsonPrimitive("$D.title")),
                            "kind" to FieldSpec(
                                kind = ExtractorKind.CONST,
                                from = JsonPrimitive(summaryKind),
                            ),
                        ),
                    )
                ),
                tracks = mapOf(trackKey to track),
            ),
            pages = listOf(
                PageSpec(
                    id = "detail",
                    layout = LayoutSpec(
                        type = LayoutType.DETAIL,
                        resource = "d",
                        tracks = trackKey,
                    ),
                )
            ),
        )
        return ConfigCompiler.compile(config)
    }

    @Test
    fun delimitedSplitsLinesThenEpisodesAndKeepsUrlDollarSigns() = runTest {
        // 第二集的地址里故意带一个 `$`：只切第一次 pairSplit，地址不能被截成前半段
        val body = """{"data":{"id":"9","title":"X","from_list":"线路一${gs}线路二","play_url":"第1集${D}http://a/1.m3u8#第2集${D}http://a/2.m3u8?k=${D}v${gs}第1集${D}http://b/1.m3u8"}}"""
        val client = clientOf { body }

        val sch = schema(TrackMode.DELIMITED, "video", "cloud")
        val detail = SourcePipeline(sch, HttpFetcher(client))
            .detail("d", "9", emptyMap(), "cloud")

        val groups = detail!!.trackGroups
        assertEquals(2, groups.size)
        assertEquals("线路一", groups[0].name)
        assertEquals("线路二", groups[1].name)
        assertEquals(2, groups[0].tracks.size)
        assertEquals("第1集", groups[0].tracks[0].title)
        assertEquals("http://a/1.m3u8", groups[0].tracks[0].url)
        assertEquals("http://a/2.m3u8?k=\$v", groups[0].tracks[1].url)
        assertEquals("http://b/1.m3u8", groups[1].tracks[0].url)
    }

    @Test
    fun arrayMapsPhotosAndPrefixesImageBase() = runTest {
        val body = """{"data":{"id":"9","title":"X","photos":[{"t":"P1","u":"/p1.jpg","th":"/t1.jpg"},{"t":"P2","u":"/p2.jpg","th":"/t2.jpg"}]}}"""
        val client = clientOf { body }

        val detail = SourcePipeline(schema(TrackMode.ARRAY, "image", "photos"), HttpFetcher(client))
            .detail("d", "9", emptyMap(), "photos")

        val groups = detail!!.trackGroups
        assertEquals(1, groups.size)
        assertEquals("图集", groups[0].name)
        assertEquals(MediaKind.IMAGE, groups[0].kind)
        assertEquals(2, groups[0].tracks.size)
        assertEquals("P1", groups[0].tracks[0].title)
        assertEquals("https://img.example.com/p1.jpg", groups[0].tracks[0].url)
        assertEquals("https://img.example.com/t1.jpg", groups[0].tracks[0].thumb)
    }

    @Test
    fun noTrackKeyMeansNoSpecGroups() = runTest {
        val body = """{"data":{"id":"9","title":"X","from_list":"线路一","play_url":"第1集${D}http://a/1.m3u8"}}"""
        val client = clientOf { body }

        val detail = SourcePipeline(schema(TrackMode.DELIMITED, "video", "cloud"), HttpFetcher(client))
            .detail("d", "9")   // 不传 trackKey：只有 episodes 通路
        assertTrue(detail!!.trackGroups.isEmpty())
    }
}
