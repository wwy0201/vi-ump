package com.vi.ump.config

import com.vi.ump.model.DomainField
import com.vi.ump.model.ExtractorKind
import com.vi.ump.query.JsonPathExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JSONPath 的根符号，避免在 raw string 里写裸 `$` 触发 Kotlin 插值 */
private const val D = "\$"

private fun minimal(
    feedRef: String = "main",
    detailResource: String = "main",
    listPath: String = "$D.items[*]",
    idKey: String = "id",
    titleKey: String = "title",
    kindLine: String = "",
): String = """
{
  "schemaVersion": "1.0",
  "meta": { "id": "minimal", "name": "最小示例" },
  "runtime": { "baseUrl": "https://example.com", "imageBase": "https://img.example.com" },
  "resources": {
    "feeds": {
      "main": {
        "request": { "method": "GET", "url": "{{baseUrl}}/api/list?page={{page}}" },
        "root": "$D.data",
        "list": "$listPath",
        "fields": {
          $kindLine
          "$idKey": "$D.id",
          "$titleKey": "$D.title",
          "poster": { "from": "$D.cover", "prefix": "{{imageBase}}" }
        },
        "paging": { "mode": "page", "param": "page", "startAt": 1, "emptyStop": true }
      }
    },
    "details": {
      "main": {
        "request": { "url": "{{baseUrl}}/api/detail?id={{id}}" },
        "root": "$D.data",
        "fields": { "id": "$D.id", "title": "$D.title", "description": "$D.desc" }
      }
    }
  },
  "pages": [
    {
      "id": "home",
      "title": "首页",
      "layout": { "type": "poster_grid", "columns": 3 },
      "feed": "$feedRef",
      "card": { "image": "poster", "title": "title", "action": { "type": "detail" } }
    },
    { "id": "detail", "layout": { "type": "detail", "resource": "$detailResource" } }
  ],
  "theme": { "mode": "dark" }
}
"""

/** 在 [minimal] 的 `runtime` 里塞一条 `imageDecrypt`，用于加密校验的几条用例 */
private fun withImageDecrypt(
    key: String = "f5d965df75336270",
    iv: String = "97b60394abc2fbe1",
    algo: String = "aes-128-cbc",
    padding: String = "none",
): String = minimal().replace(
    """{ "baseUrl": "https://example.com", "imageBase": "https://img.example.com" }""",
    """{
        |  "baseUrl": "https://example.com",
        |  "imageBase": "https://img.example.com",
        |  "imageDecrypt": { "algo": "$algo", "key": "$key", "iv": "$iv", "padding": "$padding" }
        |}""".trimMargin(),
)

class ConfigLoaderTest {
    @Test
    fun loadsMinimalConfig() {
        val config = ConfigLoader.load(minimal())
        assertEquals("minimal", config.meta.id)
        assertEquals(1, config.resources.feeds.size)
        assertEquals(1, config.resources.details.size)
        assertEquals(2, config.pages.size)
    }

    @Test
    fun expandsShorthandFields() {
        val config = ConfigLoader.load(minimal())
        val idField = config.resources.feeds.getValue("main").fields.getValue("id")
        assertTrue(idField.from.toString().contains("id"))
        assertEquals(ExtractorKind.JSON_PATH, idField.kind)
    }

    @Test
    fun detectsBrokenJson() {
        val e = assertFailsWith<ConfigException> { ConfigLoader.load("{ \"meta\": ") }
        assertEquals(IssueCode.JSON_SYNTAX, e.issue.code)
    }

    @Test
    fun rejectsTooDeepDocument() {
        var body = "{}"
        repeat(25) { body = """{"a":$body}""" }
        val e = assertFailsWith<ConfigException> { ConfigLoader.load(body) }
        assertEquals(IssueCode.TOO_BIG, e.issue.code)
    }
}

class ConfigValidatorTest {

    @Test
    fun minimalHasNoError() {
        val issues = ConfigValidator.validate(ConfigLoader.load(minimal()))
        assertTrue(issues.none { it.severity == Severity.ERROR }, "不应有错误: $issues")
    }

    @Test
    fun unknownDomainFieldIsReported() {
        val issues = ConfigValidator.validate(ConfigLoader.load(minimal(titleKey = "titel")))
        assertTrue(issues.any { it.code == IssueCode.UNDEFINED_FIELD && it.path.contains("titel") }, "$issues")
    }

    @Test
    fun missingFeedReferenceIsReported() {
        val issues = ConfigValidator.validate(ConfigLoader.load(minimal(feedRef = "nope")))
        assertTrue(issues.any { it.code == IssueCode.MISSING_REF }, "$issues")
    }

    @Test
    fun badJsonPathIsReported() {
        val issues = ConfigValidator.validate(ConfigLoader.load(minimal(listPath = "items[0]")))
        assertTrue(issues.any { it.code == IssueCode.BAD_PATH }, "$issues")
    }

    @Test
    fun detailWithoutResourceIsReported() {
        val issues = ConfigValidator.validate(ConfigLoader.load(minimal(detailResource = "")))
        assertTrue(issues.any { it.code == IssueCode.MISSING_REQUIRED && it.path.contains("resource") }, "$issues")
    }

    @Test
    fun illegalKindConstIsReported() {
        val kindLine = "\"kind\": { \"kind\": \"const\", \"from\": \"mv\" },"
        val issues = ConfigValidator.validate(ConfigLoader.load(minimal(kindLine = kindLine)))
        assertTrue(issues.any { it.code == IssueCode.BAD_ENUM }, "应报 kind 枚举错误: $issues")
    }

    // ── runtime.imageDecrypt（docs/03 §3.2）────────────────────────
    // 为什么这几条必须有：密文图写错的唯一表现是"图全是灰块、哪里都不报错"，
    // 校验器是导入期唯一能提前发现"key 少一个字符"的地方。

    @Test
    fun validImageDecryptPasses() {
        val issues = ConfigValidator.validate(ConfigLoader.load(withImageDecrypt()))
        assertTrue(issues.none { it.severity == Severity.ERROR }, "不应有错误: $issues")
    }

    @Test
    fun shortImageDecryptKeyIsReported() {
        // 15 个字符：少一位是这类配置最常见的笔误
        val issues = ConfigValidator.validate(ConfigLoader.load(withImageDecrypt(key = "f5d965df7533627")))
        assertTrue(
            issues.any { it.code == IssueCode.MISSING_REQUIRED && it.path == "runtime.imageDecrypt.key" },
            "$issues",
        )
    }

    @Test
    fun nonUtf16ImageDecryptIvIsReported() {
        // 中文按 UTF-8 算 3 字节/字：4 个汉字 ≠ 16 字节，这条专门防"看起来像 16 个字符"
        val issues = ConfigValidator.validate(ConfigLoader.load(withImageDecrypt(iv = "一二三四五六")))
        assertTrue(
            issues.any { it.code == IssueCode.MISSING_REQUIRED && it.path == "runtime.imageDecrypt.iv" },
            "$issues",
        )
    }

    @Test
    fun unknownImageDecryptAlgoIsReported() {
        // v1 起支持 AES 家族 + DES/3DES，所以这里必须拿真正不支持的算法试
        val issues = ConfigValidator.validate(ConfigLoader.load(withImageDecrypt(algo = "sm4-cbc")))
        assertTrue(
            issues.any { it.code == IssueCode.BAD_ENUM && it.path == "runtime.imageDecrypt.algo" },
            "$issues",
        )
    }

    @Test
    fun unknownImageDecryptPaddingIsReported() {
        val issues = ConfigValidator.validate(ConfigLoader.load(withImageDecrypt(padding = "zlib")))
        assertTrue(
            issues.any { it.code == IssueCode.BAD_ENUM && it.path == "runtime.imageDecrypt.padding" },
            "$issues",
        )
    }

    @Test
    fun zeroPaddingIsAccepted() {
        val issues = ConfigValidator.validate(ConfigLoader.load(withImageDecrypt(padding = "zero")))
        assertTrue(issues.none { it.severity == Severity.ERROR }, "zero 补块应合法: $issues")
    }

    @Test
    fun aes256ImageDecryptKeyIsReported() {
        // 32 字节密钥配 aes-256-cbc 合法；配 aes-128-cbc 就少 16 字节
        val issues = ConfigValidator.validate(
            ConfigLoader.load(withImageDecrypt(algo = "aes-256-cbc", padding = "pkcs7"))
        )
        assertTrue(
            issues.any { it.code == IssueCode.MISSING_REQUIRED && it.path == "runtime.imageDecrypt.key" },
            "$issues",
        )
    }
}

/**
 * 通用加解密（docs/03 §3.3）在配置层的用例：
 * ① 三个挂点能从 `runtime.crypto` 继承到 key/iv；② 缺 key 必须报错；③ 参数加密写错要拦。
 */
class CryptoConfigTest {

    private fun withCrypto(): String = minimal()
        .replace(
            """{ "baseUrl": "https://example.com", "imageBase": "https://img.example.com" }""",
            """{
        |  "baseUrl": "https://example.com",
        |  "imageBase": "https://img.example.com",
        |  "crypto": { "algo": "aes-128-cbc", "key": "f5d965df75336270", "iv": "97b60394abc2fbe1", "padding": "none" }
        |}""".trimMargin(),
        )
        // 响应解密（取一个字段的值解出来当整段响应）
        .replace(
            "\"root\": \"$D.data\",",
            "\"root\": \"$D.data\", \"decrypt\": { \"from\": \"$D.payload\" },",
        )
        // 单字段解密（空对象 = 全部继承 runtime.crypto）
        .replace(
            "\"poster\": { \"from\": \"$D.cover\", \"prefix\": \"{{imageBase}}\" }",
            "\"poster\": { \"from\": \"$D.cover\", \"prefix\": \"{{imageBase}}\", \"decrypt\": {} }",
        )
        // 请求参数加密
        .replace(
            "\"url\": \"{{baseUrl}}/api/detail?id={{id}}\"",
            "\"url\": \"{{baseUrl}}/api/detail?id={{id}}\", \"encrypt\": { \"mode\": \"params\", \"params\": [\"data\"] }",
        )

    @Test
    fun bakesCryptoIntoCompiledArtifacts() {
        val schema = ConfigCompiler.loadAndCompile(withCrypto())
        assertTrue(schema.issues.none { it.severity == Severity.ERROR }, "${schema.issues}")

        val feed = schema.feeds.getValue("main")
        val decrypt = assertNotNull(feed.decrypt)
        assertEquals("aes-128-cbc", decrypt.crypto.algo)
        assertEquals("none", decrypt.crypto.padding)
        assertNotNull(decrypt.from, "from=$D.payload 应编译成路径")

        val poster = assertNotNull(feed.fields[DomainField.POSTER])
        val posterCrypto = assertNotNull(poster.crypto, "单字段解密应烘焙成 crypto")
        assertEquals("aes-128-cbc", posterCrypto.algo)

        val encrypt = assertNotNull(schema.details.getValue("main").request.encrypt)
        assertEquals("params", encrypt.mode)
        assertTrue("data" in encrypt.params)
    }

    @Test
    fun missingKeyIsReported() {
        // 没有任何 key 来源，却声明了解密 → 导入期必须拦（否则运行期只会静默解不开）
        val text = minimal().replace(
            "\"root\": \"$D.data\",",
            "\"root\": \"$D.data\", \"decrypt\": { \"from\": \"$D.payload\" },",
        )
        val issues = ConfigValidator.validate(ConfigLoader.load(text))
        assertTrue(
            issues.any { it.code == IssueCode.MISSING_REQUIRED && it.path.endsWith("decrypt.key") },
            "$issues",
        )
    }

    @Test
    fun encryptWithoutParamsIsReported() {
        val text = minimal().replace(
            "\"url\": \"{{baseUrl}}/api/detail?id={{id}}\"",
            "\"url\": \"{{baseUrl}}/api/detail?id={{id}}\", \"encrypt\": { \"mode\": \"params\" }",
        )
        val issues = ConfigValidator.validate(ConfigLoader.load(text))
        assertTrue(
            issues.any { it.code == IssueCode.MISSING_REQUIRED && it.path.endsWith("request.encrypt.params") },
            "$issues",
        )
    }

    @Test
    fun badDecryptPathIsReported() {
        val text = minimal().replace(
            "\"root\": \"$D.data\",",
            "\"root\": \"$D.data\", \"decrypt\": { \"from\": \"data[bad\", \"key\": \"f5d965df75336270\", \"iv\": \"97b60394abc2fbe1\" },",
        )
        val issues = ConfigValidator.validate(ConfigLoader.load(text))
        assertTrue(issues.any { it.code == IssueCode.BAD_PATH }, "$issues")
    }
}

class ConfigCompilerTest {
    private val schema: CompiledSchema by lazy { ConfigCompiler.loadAndCompile(minimal()) }

    @Test
    fun compilesFeedResource() {
        val feed = schema.feeds.getValue("main")
        assertNotNull(feed.root)
        assertNotNull(feed.list)
        assertEquals(
            listOf(DomainField.ID, DomainField.TITLE, DomainField.POSTER),
            feed.fields.keys.toList(),
        )
        assertTrue(feed.fields.getValue(DomainField.ID).extractor is JsonPathExtractor)
    }

    @Test
    fun compiledPathsAreTokens() {
        val list = schema.feeds.getValue("main").list!!
        assertEquals("$D.items[*]", list.raw)
        assertEquals(2, list.tokens.size)
    }

    @Test
    fun unknownFieldIsDroppedFromCompiledMap() {
        val s = ConfigCompiler.loadAndCompile(minimal(titleKey = "titel"))
        assertNull(s.feeds.getValue("main").fields[DomainField.TITLE])
        assertTrue(s.hasErrors)
    }

    @Test
    fun pagesSurviveCompilation() {
        assertEquals(listOf("home", "detail"), schema.pages.map { it.id })
    }
}

/**
 * `channels` / `viewModes` 的校验（与 `tools/validate_config.py` 同一口径）。
 *
 * 这两个规则只写在文档里没有意义——配置写错了必须能在加载阶段就报出来，
 * 否则用户看到的是"栏目条不显示"这种无提示的空白。
 */
class ConfigValidatorChannelTest {

    /** 往首页 JSON 里塞字段：card 之后追加，保证逗号合法 */
    private fun withHome(extra: String): String = minimal().replace(
        "\"card\": { \"image\": \"poster\", \"title\": \"title\", \"action\": { \"type\": \"detail\" } }",
        "\"card\": { \"image\": \"poster\", \"title\": \"title\", \"action\": { \"type\": \"detail\" } },\n      $extra",
    )

    @Test
    fun channelsAndChildrenAreMutuallyExclusive() {
        val cfg = withHome(
            """
            "channels": [ { "id": "a", "title": "A", "feed": "main" } ],
            "children": [ { "id": "sub", "layout": { "type": "list_linear" }, "feed": "main" } ]
            """.trimIndent()
        )
        val issues = ConfigValidator.validate(ConfigLoader.load(cfg))
        assertTrue(
            issues.any { it.code == IssueCode.BAD_ENUM && it.path.contains("channels") },
            "channels 与 children 同写应报 E003: $issues",
        )
    }

    @Test
    fun duplicateChannelIdIsReported() {
        val cfg = withHome(
            """
            "channels": [
              { "id": "a", "title": "A1", "feed": "main" },
              { "id": "a", "title": "A2", "feed": "main" }
            ]
            """.trimIndent()
        )
        val issues = ConfigValidator.validate(ConfigLoader.load(cfg))
        assertTrue(
            issues.any { it.path.contains("channels") && it.message.contains("重复") },
            "栏目 id 重复应报错: $issues",
        )
    }

    @Test
    fun channelWithUnknownFeedIsReported() {
        val cfg = withHome(""" "channels": [ { "id": "a", "title": "A", "feed": "nope" } ] """.trimIndent())
        val issues = ConfigValidator.validate(ConfigLoader.load(cfg))
        assertTrue(
            issues.any { it.code == IssueCode.MISSING_REF && it.path.contains("channels") },
            "栏目引用不存在的 feed 应报 E005: $issues",
        )
    }

    @Test
    fun viewModesCannotContainDetail() {
        val cfg = withHome(""" "viewModes": [ "poster_grid", "detail" ] """.trimIndent())
        val issues = ConfigValidator.validate(ConfigLoader.load(cfg))
        assertTrue(
            issues.any { it.code == IssueCode.BAD_ENUM && it.path.contains("viewModes") },
            "viewModes 含 detail 应报 E003: $issues",
        )
    }

    @Test
    fun wellFormedChannelsHaveNoError() {
        val cfg = withHome(
            """
            "viewModes": [ "poster_grid", "list_linear" ],
            "channels": [
              { "id": "a", "title": "A", "feed": "main" },
              { "id": "b", "title": "B", "feed": "main", "params": { "page": "2" } }
            ]
            """.trimIndent()
        )
        val issues = ConfigValidator.validate(ConfigLoader.load(cfg))
        assertTrue(issues.none { it.severity == Severity.ERROR }, "合法栏目不应报错: $issues")
    }
}