package com.vi.ump.query

import com.vi.ump.model.ExtractorKind
import com.vi.ump.model.FieldSpec
import com.vi.ump.model.LevelSpec
import com.vi.ump.model.candidateExpressions
import kotlinx.serialization.json.JsonElement

/** 抽取器的输入。JSON 响应走 [Json]，纯文本/HTML 走 [Text] */
sealed interface Payload {
    data class Json(val root: JsonElement) : Payload
    data class Text(val body: String) : Payload

    fun asText(): String = when (this) {
        is Json -> root.toString()
        is Text -> body
    }
}

/**
 * HTML 选择器是平台能力（Jsoup 在 JVM/Android，iOS 需 SwiftSoup），
 * 因此以接口注入，`core:query` 本身不依赖任何 HTML 库。
 */
fun interface HtmlSelector {
    fun select(html: String, selector: String, attr: String?): List<String>
}

object NoHtmlSelector : HtmlSelector {
    override fun select(html: String, selector: String, attr: String?): List<String> = emptyList()
}

interface Extractor {
    /** 抽出候选值，可能多个；返回空表示未命中 */
    fun extract(payload: Payload, scope: Map<String, String> = emptyMap()): List<String?>
}

/** `jsonPath`：多个候选路径按序短路，第一个命中的整组结果胜出 */
class JsonPathExtractor(paths: List<String>) : Extractor {
    private val compiled: List<PathExpr> = paths.map { JsonPath.compile(it) }

    override fun extract(payload: Payload, scope: Map<String, String>): List<String?> {
        val root = (payload as? Payload.Json)?.root ?: return emptyList()
        for (p in compiled) {
            val hit = p.eval(root).mapNotNull { it.asText() }.filter { it.isNotBlank() }
            if (hit.isNotEmpty()) return hit
        }
        return emptyList()
    }
}

/** `regex`：在整段文本上找所有匹配，取第 [group] 组 */
class RegexExtractor(pattern: String, private val group: Int = 0) : Extractor {
    private val regex: Regex = runCatching { Regex(pattern) }
        .getOrElse { badPath("正则语法错误: $pattern (${it.message})") }

    override fun extract(payload: Payload, scope: Map<String, String>): List<String?> {
        val text = payload.asText()
        return regex.findAll(text)
            .mapNotNull { m -> m.groupValues.getOrNull(group)?.takeIf { it.isNotBlank() } }
            .toList()
    }
}

/** `css`：交给注入的 [HtmlSelector]；无实现时返回空（上层按 W003 降级） */
class CssExtractor(
    private val selector: String,
    private val attr: String?,
    private val htmlSelector: HtmlSelector = NoHtmlSelector,
) : Extractor {
    override fun extract(payload: Payload, scope: Map<String, String>): List<String?> {
        val html = when (payload) {
            is Payload.Text -> payload.body
            is Payload.Json -> payload.root.toString()
        }
        return htmlSelector.select(html, selector, attr)
    }
}

/**
 * `delimited`：从 JSON 取到字符串后，按 [levels] 由粗到细逐层切分。
 * `take == null` 时取切分后的第一段。
 */
class DelimitedExtractor(
    paths: List<String>,
    private val levels: List<LevelSpec>,
) : Extractor {
    private val compiled: List<PathExpr> = paths.map { JsonPath.compile(it) }

    override fun extract(payload: Payload, scope: Map<String, String>): List<String?> {
        val raw = when (payload) {
            is Payload.Json -> compiled.firstNotNullOfOrNull { p ->
                p.evalFirstString(payload.root)?.takeIf { it.isNotBlank() }
            }
            is Payload.Text -> payload.body.takeIf { it.isNotBlank() }
        } ?: return emptyList()

        var cursor: String? = raw
        for (level in levels) {
            val parts = cursor!!.split(level.split)
            val take = level.take
            val next = if (take != null) parts.getOrNull(take) else parts.firstOrNull()
            cursor = next ?: return emptyList()
        }
        return listOf(cursor)
    }
}

/**
 * `template`：把 [parts] 拼接，片段里的 `{{name}}` 从 scope 取值，
 * `{{$.path}}` 从 JSON payload 取值。
 */
class TemplateExtractor(
    private val parts: List<String>,
    private val join: String = "",
) : Extractor {
    private val refPattern = Regex("""\{\{\s*([^}]+?)\s*\}\}""")

    override fun extract(payload: Payload, scope: Map<String, String>): List<String?> {
        val sb = StringBuilder()
        parts.forEachIndexed { index, part ->
            if (index > 0) sb.append(join)
            sb.append(
                refPattern.replace(part) { m ->
                    resolve(m.groupValues[1].trim(), payload, scope) ?: ""
                }
            )
        }
        val out = sb.toString()
        return if (out.isBlank()) emptyList() else listOf(out)
    }

    private fun resolve(key: String, payload: Payload, scope: Map<String, String>): String? =
        if (key.startsWith("$")) {
            val root = (payload as? Payload.Json)?.root ?: return null
            runCatching { JsonPath.compile(key).evalFirstString(root) }.getOrNull()
        } else {
            scope[key]
        }
}

/** `const`：`from` 就是常量字面量（`kind` 字段最常用） */
class ConstExtractor(private val value: String) : Extractor {
    override fun extract(payload: Payload, scope: Map<String, String>): List<String?> =
        listOf(value)
}

object Extractors {
    fun of(spec: FieldSpec, htmlSelector: HtmlSelector = NoHtmlSelector): Extractor =
        when (spec.kind) {
            ExtractorKind.JSON_PATH -> JsonPathExtractor(spec.candidateExpressions)
            ExtractorKind.REGEX -> RegexExtractor(
                spec.pattern ?: badPath("kind=regex 缺少 pattern"),
                spec.group,
            )
            ExtractorKind.CSS -> CssExtractor(
                spec.selector ?: badPath("kind=css 缺少 selector"),
                spec.attr,
                htmlSelector,
            )
            ExtractorKind.DELIMITED -> DelimitedExtractor(spec.candidateExpressions, spec.levels)
            ExtractorKind.TEMPLATE -> TemplateExtractor(spec.parts, spec.join)
            ExtractorKind.CONST -> ConstExtractor(spec.candidateExpressions.firstOrNull() ?: "")
        }
}
