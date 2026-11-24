package com.vi.ump.query

import com.vi.ump.model.ExtractorKind
import com.vi.ump.model.FieldSpec
import com.vi.ump.model.FieldType
import com.vi.ump.model.LevelSpec
import com.vi.ump.model.MediaKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
private fun p(path: String) = JsonPrimitive(path)
private fun spec(
    kind: ExtractorKind = ExtractorKind.JSON_PATH,
    from: JsonElement? = null,
    pattern: String? = null,
    group: Int = 0,
    levels: List<LevelSpec> = emptyList(),
    parts: List<String> = emptyList(),
    join: String = "",
): FieldSpec = FieldSpec(kind = kind, from = from, pattern = pattern, group = group, levels = levels, parts = parts, join = join)

class JsonPathTest {

    private fun eval(path: String, body: String) = JsonPath.compile(path).eval(json(body))

    @Test
    fun rootIsWholeDocument() {
        val r = eval("$", """{"a":1}""")
        assertEquals(1, r.size)
    }

    @Test
    fun simpleField() = assertEquals(listOf("1"), eval("$.a", """{"a":1}""").map { it.asText() })

    @Test
    fun nestedField() = assertEquals(listOf("x"), eval("$.a.b", """{"a":{"b":"x"}}""").map { it.asText() })

    @Test
    fun arrayIndex() = assertEquals(listOf("b"), eval("$.list[1]", """{"list":["a","b"]}""").map { it.asText() })

    @Test
    fun negativeIndex() = assertEquals(listOf("c"), eval("$.list[-1]", """{"list":["a","b","c"]}""").map { it.asText() })

    @Test
    fun wildcardProducesMany() =
        assertEquals(listOf("1", "2", "3"), eval("$.items[*].id", """{"items":[{"id":1},{"id":2},{"id":3}]}""").map { it.asText() })

    @Test
    fun recursiveDescend() =
        assertEquals(listOf("a", "b"), eval("$..name", """{"x":{"name":"a"},"y":[{"name":"b"}]}""").map { it.asText() })

    @Test
    fun bracketKeySyntax() = assertEquals(listOf("v"), eval("$['k']", """{"k":"v"}""").map { it.asText() })

    @Test
    fun missingKeyReturnsEmpty() = assertTrue(eval("$.nope", """{"a":1}""").isEmpty())

    @Test
    fun indexOnNonArrayReturnsEmpty() = assertTrue(eval("$.a[0]", """{"a":1}""").isEmpty())

    @Test
    fun outOfRangeReturnsEmpty() = assertTrue(eval("$.list[5]", """{"list":["a"]}""").isEmpty())

    @Test
    fun nullValueIsPreservedButTextIsNull() {
        val r = eval("$.a", """{"a":null}""")
        assertEquals(1, r.size)
        assertNull(r.first().asText())
    }

    @Test
    fun notStartingWithRootFails() {
        val e = assertFailsWith<QueryException> { JsonPath.compile("a.b") }
        assertEquals(QueryError.BAD_PATH, e.error)
    }

    @Test
    fun unclosedBracketFails() {
        assertFailsWith<QueryException> { JsonPath.compile("$.a[0") }
    }

    @Test
    fun unsupportedFilterFails() {
        assertFailsWith<QueryException> { JsonPath.compile("$.items[?(@.id>1)]") }
    }

    @Test
    fun emptyFieldAfterDotFails() {
        assertFailsWith<QueryException> { JsonPath.compile("$.") }
    }

    @Test
    fun tokensAreRecorded() {
        val expr = JsonPath.compile("$.a[0][*]..b")
        assertEquals(4, expr.tokens.size)
        assertTrue(expr.tokens[0] is PathToken.Key)
        assertTrue(expr.tokens[2] is PathToken.ArrayWildcard)
        assertTrue(expr.tokens[3] is PathToken.Descend)
    }
}

class ExtractorTest {

    @Test
    fun jsonPathFirstCandidateWins() {
        val s = spec(from = JsonArray(listOf(p("$.missing"), p("$.a"))))
        val hit = Extractors.of(s).extract(Payload.Json(json("""{"a":"ok"}""")))
        assertEquals(listOf("ok"), hit)
    }

    @Test
    fun jsonPathReturnsWholeGroupOnWildcard() {
        val s = spec(from = p("$.tags[*]"))
        val hit = Extractors.of(s).extract(Payload.Json(json("""{"tags":["a","b"]}""")))
        assertEquals(listOf("a", "b"), hit)
    }

    @Test
    fun jsonPathEmptyWhenNoMatch() {
        val hit = Extractors.of(spec(from = p("$.none"))).extract(Payload.Json(json("""{"a":1}""")))
        assertTrue(hit.isEmpty())
    }

    @Test
    fun regexTakesGroup() {
        val s = spec(kind = ExtractorKind.REGEX, pattern = """id=(\d+)""", group = 1)
        val hit = Extractors.of(s).extract(Payload.Text("x id=42 y"))
        assertEquals(listOf("42"), hit)
    }

    @Test
    fun regexInvalidPatternFails() {
        assertFailsWith<QueryException> {
            RegexExtractor("([", 1)
        }
    }

    @Test
    fun delimitedWalksLevelsTopDown() {
        val s = spec(
            kind = ExtractorKind.DELIMITED,
            from = p("$.urls"),
            levels = listOf(
                LevelSpec(split = "$$$", take = 1),
                LevelSpec(split = "#", take = 0),
                LevelSpec(split = "$", take = 1),
            ),
        )
        val body = "{\"urls\":\"a1\$u1#a2\$u2\$\$\$b1\$v1#b2\$v2\"}"
        val hit = Extractors.of(s).extract(Payload.Json(json(body)))
        assertEquals(listOf("v1"), hit)
    }

    @Test
    fun delimitedMissReturnsEmpty() {
        val s = spec(kind = ExtractorKind.DELIMITED, from = p("$.urls"), levels = listOf(LevelSpec(split = "###", take = 1)))
        val hit = Extractors.of(s).extract(Payload.Json(json("""{"urls":"a"}""")))
        assertTrue(hit.isEmpty())
    }

    @Test
    fun templateJoinsPartsWithScopeAndJson() {
        val s = spec(
            kind = ExtractorKind.TEMPLATE,
            parts = listOf("https://cdn/", "{{$.id}}", ".mp4?from=", "{{base}}"),
            join = "",
        )
        val hit = Extractors.of(s).extract(
            Payload.Json(json("""{"id":"9"}""")),
            scope = mapOf("base" to "app"),
        )
        assertEquals(listOf("https://cdn/9.mp4?from=app"), hit)
    }

    @Test
    fun constIgnoresPayload() {
        val s = spec(kind = ExtractorKind.CONST, from = JsonPrimitive("audio"))
        val hit = Extractors.of(s).extract(Payload.Json(json("""{"a":1}""")))
        assertEquals(listOf("audio"), hit)
    }

    @Test
    fun cssWithoutPlatformImplDegradesToEmpty() {
        // selector 合法，但当前平台没有注入 HtmlSelector → 返回空（上层按 W003 降级）
        val s = FieldSpec(kind = ExtractorKind.CSS, selector = "div.title", attr = "text")
        val hit = Extractors.of(s).extract(Payload.Text("<div class='title'>x</div>"))
        assertTrue(hit.isEmpty())
    }

    @Test
    fun cssMissingSelectorFails() {
        assertFailsWith<QueryException> {
            Extractors.of(FieldSpec(kind = ExtractorKind.CSS))
        }
    }

    @Test
    fun cssWithInjectedSelectorWorks() {
        val selector = HtmlSelector { _, _, attr -> listOf("<$attr>") }
        val s = FieldSpec(kind = ExtractorKind.CSS, selector = "div.title", attr = "href")
        val hit = Extractors.of(s, selector).extract(Payload.Text("<div class='title'>x</div>"))
        assertEquals(listOf("<href>"), hit)
    }
}

class TransformTest {

    private fun fp(
        prefix: String = "",
        suffix: String = "",
        map: Map<String, String> = emptyMap(),
        default: String? = null,
        rewrite: List<com.vi.ump.model.RewriteSpec> = emptyList(),
        trim: Boolean = true,
        type: FieldType = FieldType.STRING,
    ) = FieldSpec(prefix = prefix, suffix = suffix, map = map, default = default, rewrite = rewrite, trim = trim, type = type)

    @Test
    fun trimApplies() = assertEquals("x", TransformChain.apply("  x  ", fp()))

    @Test
    fun rewriteApplies() = assertEquals("a1", TransformChain.apply("aX", fp(rewrite = listOf(com.vi.ump.model.RewriteSpec("X", "1")), trim = false)))

    @Test
    fun mapHitsReplace() = assertEquals("电影", TransformChain.apply("movie", fp(map = mapOf("movie" to "电影"))), "命中即替换")

    @Test
    fun mapMissKeepsOriginal() = assertEquals("tv", TransformChain.apply("tv", fp(map = mapOf("movie" to "电影"))))

    @Test
    fun prefixAndSuffixWrapMappedValue() {
        val v = TransformChain.apply("12", fp(prefix = "更新至", suffix = "集"))
        assertEquals("更新至12集", v)
    }

    @Test
    fun mapRunsBeforePrefix() {
        // 若顺序反转（先拼接再 map），'movie' 被包成 '第movie集' 后就不再命中 map
        val v = TransformChain.apply("movie", fp(prefix = "第", suffix = "集", map = mapOf("movie" to "电影")))
        assertEquals("第电影集", v)
    }

    @Test
    fun blankFallsBackToDefault() = assertEquals("0", TransformChain.apply("   ", fp(default = "0")))

    @Test
    fun castIntAndFloat() {
        assertEquals(12, Cast.to("12", FieldType.INT))
        assertEquals(9.5f, Cast.to("9.5", FieldType.FLOAT))
        assertNull(Cast.to("abc", FieldType.INT))
    }

    @Test
    fun castBool() {
        assertEquals(true, Cast.to("1", FieldType.BOOL))
        assertEquals(false, Cast.to("false", FieldType.BOOL))
    }

    @Test
    fun durationSecondsBecomeMillis() {
        assertEquals(195_000L, Cast.durationToMillis(195, FieldType.INT))
        assertEquals(195_000L, Cast.durationToMillis(195_000, FieldType.LONG))
    }
}

class DomainMapperTest {

    private fun fields(vararg pairs: Pair<String, FieldSpec>) = pairs.toMap()

    private val sample = """
        {
          "song_id": "s1",
          "song_name": " 夜曲 ",
          "cover": "/img/1.jpg",
          "singer": "周杰伦",
          "duration_sec": 195,
          "tags": ["流行", "经典"]
        }
    """.trimIndent()

    @Test
    fun mapsFullAudioItem() {
        val f = fields(
            "id" to spec(from = p("$.song_id")),
            "title" to spec(from = p("$.song_name")),
            "kind" to spec(kind = ExtractorKind.CONST, from = JsonPrimitive("audio")),
            "poster" to spec(
                from = p("$.cover"),
                pattern = null,
            ).copy(prefix = "https://img/"),
            "artist" to spec(from = p("$.singer")),
            "duration" to spec(from = p("$.duration_sec"), ).copy(type = FieldType.INT),
            "tags" to spec(from = p("$.tags[*]")).copy(type = FieldType.STRING_LIST),
        )
        val summary = DomainMapper.mapItem(f, Payload.Json(json(sample)))
        assertNotNull(summary)
        assertEquals("s1", summary.id)
        assertEquals("夜曲", summary.title)
        assertEquals(MediaKind.AUDIO, summary.kind)
        assertEquals("https://img//img/1.jpg", summary.poster)
        assertEquals("周杰伦", summary.artist)
        assertEquals(195_000L, summary.durationMs)
        assertEquals(listOf("流行", "经典"), summary.tags)
    }

    @Test
    fun missingTitleReturnsNull() {
        val f = fields("id" to spec(from = p("$.song_id")))
        assertNull(DomainMapper.mapItem(f, Payload.Json(json(sample))))
    }

    @Test
    fun missingRequiredReturnsNull() {
        val f = fields(
            "id" to spec(from = p("$.song_id")),
            "title" to spec(from = p("$.song_name")),
            "playUrl" to spec(from = p("$.not_exists")).copy(required = true),
        )
        assertNull(DomainMapper.mapItem(f, Payload.Json(json(sample))))
    }

    @Test
    fun unknownFieldThrowsE009() {
        val e = assertFailsWith<QueryException> {
            DomainMapper.mapFields(
                fields("titel" to spec(from = p("$.song_name"))),
                Payload.Json(json(sample)),
            )
        }
        assertEquals(QueryError.UNDEFINED_FIELD, e.error)
    }

    @Test
    fun defaultKindIsVideo() {
        val f = fields(
            "id" to spec(from = p("$.song_id")),
            "title" to spec(from = p("$.song_name")),
        )
        val summary = DomainMapper.mapItem(f, Payload.Json(json(sample)))
        assertEquals(MediaKind.VIDEO, summary?.kind)
    }
}
