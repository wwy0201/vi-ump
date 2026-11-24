package com.vi.ump.config

import com.vi.ump.model.CryptoSpec
import com.vi.ump.model.DecryptSpec
import com.vi.ump.model.DomainField
import com.vi.ump.model.EncryptSpec
import com.vi.ump.model.EpisodesSpec
import com.vi.ump.model.ExtractorKind
import com.vi.ump.model.FieldSpec
import com.vi.ump.model.LayoutType
import com.vi.ump.model.PageSpec
import com.vi.ump.model.PagingMode
import com.vi.ump.model.PlayStrategy
import com.vi.ump.model.ResourceSpec
import com.vi.ump.model.ResponseType
import com.vi.ump.model.TrackMode
import com.vi.ump.model.TrackSpec
import com.vi.ump.model.UmpConfig
import com.vi.ump.model.candidateExpressions
import com.vi.ump.query.CryptoCodec
import com.vi.ump.query.JsonPath
import com.vi.ump.query.QueryException

/**
 * L3 语义校验。规则与 `tools/validate_config.py` 保持一致——
 * 两份实现互为交叉验证，改一边必须改另一边，否则示例会"假通过"。
 */
object ConfigValidator {

    private val ALLOWED_SCHEMES = setOf("http://", "https://", "{{")

    /** `resources.categories` 是导航特化结构，不用媒体域字段表（docs/03 §6.4） */
    private val CATEGORY_FIELDS = setOf("id", "name", "icon")

    fun validate(config: UmpConfig): List<ConfigIssue> {
        val col = IssueCollector()
        validateMeta(config, col)
        validateRuntime(config, col)
        validateResources(config, col)
        validateTracks(config, col)
        validatePages(config, col)
        validatePlayback(config, col)
        return col.result()
    }

    private fun validateMeta(c: UmpConfig, col: IssueCollector) {
        if (c.meta.id.isBlank()) col.error(IssueCode.MISSING_REQUIRED, "meta.id", "缺少 meta.id")
        if (c.meta.name.isBlank()) col.error(IssueCode.MISSING_REQUIRED, "meta.name", "缺少 meta.name")
        if (c.runtime.baseUrl.isBlank()) {
            col.error(IssueCode.MISSING_REQUIRED, "runtime.baseUrl", "缺少 runtime.baseUrl")
        }
        if (c.schemaVersion != "1.0") {
            col.warn(
                IssueCode.NO_MIGRATION, "schemaVersion",
                "当前实现只认 1.0，遇到 ${c.schemaVersion} 需要先迁移"
            )
        }
    }

    /**
     * 加解密相关字段（docs/03 §3.2 图片、§3.3 通用）。
     *
     * 为什么必须在导入期拦：加密写错（key 少一个字符、algo 拼错、padding 配错）的唯一表现是
     * "图全是灰块 / 列表永远空 / 播放地址是乱码，而且哪里都不报错"——解密失败是静默的。
     * 这类错误只能在导入期暴露。
     *
     * 注意：`runtime.crypto` 只是**默认值**，允许 key 为空（全部挂点各自写全）；但一旦声明了，
     * 缺省值本身也要自洽，否则各挂点继承到的是一组坏参数。
     */
    private fun validateRuntime(c: UmpConfig, col: IssueCollector) {
        c.runtime.crypto?.let { checkCrypto(col, "runtime.crypto", it, requireKey = false) }
        c.runtime.imageDecrypt?.let {
            checkCrypto(
                col, "runtime.imageDecrypt",
                CryptoSpec(
                    algo = it.algo, key = it.key, iv = it.iv, padding = it.padding,
                    output = it.output, keyFormat = it.keyFormat, ivFormat = it.ivFormat,
                ),
                requireKey = true,
            )
        }
    }

    /**
     * 一组加解密参数的合法性。
     *
     * @param requireKey `runtime.crypto` 传 false（它只是默认值，挂点会自带 key）；其余传 true。
     */
    private fun checkCrypto(col: IssueCollector, path: String, spec: CryptoSpec, requireKey: Boolean) {
        val algo = CryptoCodec.normalizeAlgo(spec.algo)
        if (algo == null) {
            col.error(
                IssueCode.BAD_ENUM, "$path.algo",
                "不支持的算法「${spec.algo}」；可用: ${CryptoCodec.KEY_SIZES.keys.joinToString(" / ")}"
            )
            return
        }
        if (spec.padding !in CryptoCodec.PADDINGS) {
            col.error(
                IssueCode.BAD_ENUM, "$path.padding",
                "只允许 pkcs7 / none / zero，收到「${spec.padding}」"
            )
        }
        if (spec.output !in CryptoCodec.OUTPUTS) {
            col.error(
                IssueCode.BAD_ENUM, "$path.output",
                "只允许 base64 / base64url / hex / raw，收到「${spec.output}」"
            )
        }
        if (spec.keyFormat !in CryptoCodec.KEY_FORMATS) {
            col.error(IssueCode.BAD_ENUM, "$path.keyFormat", "只允许 text / hex / base64，收到「${spec.keyFormat}」")
        }
        if (spec.ivFormat !in CryptoCodec.KEY_FORMATS) {
            col.error(IssueCode.BAD_ENUM, "$path.ivFormat", "只允许 text / hex / base64，收到「${spec.ivFormat}」")
        }

        if (requireKey && spec.key.isEmpty()) {
            col.error(IssueCode.MISSING_REQUIRED, "$path.key", "缺少 key（也没有从 runtime.crypto 继承到）")
            return
        }
        if (spec.key.isNotEmpty()) {
            val want = CryptoCodec.KEY_SIZES.getValue(algo)
            val bytes = CryptoCodec.keyBytes(spec.key, spec.keyFormat)
            if (bytes == null || bytes.size != want) {
                col.error(
                    IssueCode.MISSING_REQUIRED, "$path.key",
                    "$algo 需要 $want 字节密钥（keyFormat=${spec.keyFormat}），" +
                        "当前 ${bytes?.size ?: "无法解码"} 字节 —— 长度不对等于每次解密都失败"
                )
            }
        }

        val ivNeed = CryptoCodec.ivSize(algo) ?: 0
        if (ivNeed > 0) {
            val bytes = if (spec.iv.isEmpty()) null else CryptoCodec.keyBytes(spec.iv, spec.ivFormat)
            if (bytes == null || bytes.size != ivNeed) {
                col.error(
                    IssueCode.MISSING_REQUIRED, "$path.iv",
                    "$algo 需要 $ivNeed 字节 iv（ivFormat=${spec.ivFormat}），" +
                        "当前 ${bytes?.size ?: "未填或无法解码"}"
                )
            }
        }

        if ((algo.endsWith("-ctr") || algo.endsWith("-gcm")) && spec.padding != "none") {
            col.error(
                IssueCode.BAD_ENUM, "$path.padding",
                "$algo 是流式/认证模式，padding 必须 none（当前「${spec.padding}」）"
            )
        }
    }

    /** 挂点缺省继承 `runtime.crypto`，拼出一份"实际会用到的"参数 */
    private fun mergeDecrypt(base: CryptoSpec?, d: DecryptSpec?): CryptoSpec? {
        if (d == null) return null
        return CryptoSpec(
            algo = d.algo ?: base?.algo ?: "aes-128-cbc",
            key = d.key ?: base?.key.orEmpty(),
            iv = d.iv ?: base?.iv.orEmpty(),
            padding = d.padding ?: base?.padding ?: "pkcs7",
            output = d.output ?: base?.output ?: "base64",
            keyFormat = d.keyFormat ?: base?.keyFormat ?: "text",
            ivFormat = d.ivFormat ?: base?.ivFormat ?: "text",
        )
    }

    private fun mergeEncrypt(base: CryptoSpec?, e: EncryptSpec?): CryptoSpec? {
        if (e == null) return null
        return CryptoSpec(
            algo = e.algo ?: base?.algo ?: "aes-128-cbc",
            key = e.key ?: base?.key.orEmpty(),
            iv = e.iv ?: base?.iv.orEmpty(),
            padding = e.padding ?: base?.padding ?: "pkcs7",
            output = e.output ?: base?.output ?: "base64",
            keyFormat = e.keyFormat ?: base?.keyFormat ?: "text",
            ivFormat = e.ivFormat ?: base?.ivFormat ?: "text",
        )
    }

    private fun checkEncrypt(col: IssueCollector, path: String, e: EncryptSpec, base: CryptoSpec?) {
        val mode = e.mode.trim().lowercase()
        if (mode != "params" && mode != "whole") {
            col.error(IssueCode.BAD_ENUM, "$path.mode", "只允许 params / whole，收到「${e.mode}」")
        }
        if (mode == "params" && e.params.isEmpty()) {
            col.error(IssueCode.MISSING_REQUIRED, "$path.params", "mode=params 必须列出要加密的参数名")
        }
        mergeEncrypt(base, e)?.let { checkCrypto(col, path, it, requireKey = true) }
    }

    private fun validateResources(c: UmpConfig, col: IssueCollector) {
        if (c.resources.feeds.isEmpty()) {
            col.error(IssueCode.MISSING_REQUIRED, "resources.feeds", "至少需要 1 个列表资源")
        }
        if (c.resources.details.isEmpty() && c.pages.any { it.layout.type == LayoutType.DETAIL }) {
            // 有详情页就必须有详情资源；没有详情页的源（抖音：单条详情接口要 uifid）直接用列表里的直链播放
            col.error(IssueCode.MISSING_REQUIRED, "resources.details", "声明了详情页，但至少需要 1 个详情资源")
        }

        val defaults = c.runtime.crypto
        c.resources.feeds.forEach { (k, r) ->
            checkResource(col, "resources.feeds.$k", r, isList = true, allowCleartext = c.flags.allowCleartext, crypto = defaults)
        }
        c.resources.search.forEach { (k, r) ->
            checkResource(col, "resources.search.$k", r, isList = true, allowCleartext = c.flags.allowCleartext, crypto = defaults)
        }
        c.resources.details.forEach { (k, r) ->
            checkResource(col, "resources.details.$k", r, isList = false, allowCleartext = c.flags.allowCleartext, crypto = defaults)
            if (r.fields["id"] == null) {
                col.error(IssueCode.MISSING_REQUIRED, "resources.details.$k.fields.id", "详情资源必须映射 id")
            }
            if (r.fields["title"] == null) {
                col.error(IssueCode.MISSING_REQUIRED, "resources.details.$k.fields.title", "详情资源必须映射 title")
            }
            r.episodes?.let { checkEpisodes(col, "resources.details.$k.episodes", it, c) }
        }
        c.resources.categories?.let {
            checkResource(
                col, "resources.categories", it,
                isList = true,
                allowCleartext = c.flags.allowCleartext,
                isCategory = true,
                crypto = defaults,
            )
        }
    }


    /**
     * `episodes` 的校验（过去整块没有校验，于是 `ids` 写成对象路径只会在运行期得到 0 组，
     * 页面静默没有选集 —— e-hentai 的 933 页"没有入口"就是这么来的）。
     */
    private fun checkEpisodes(col: IssueCollector, path: String, ep: EpisodesSpec, c: UmpConfig) {
        val hasIds = !ep.ids.isNullOrBlank()
        val hasSplit = !ep.idsSplit.isNullOrBlank()
        if (!hasIds && !hasSplit) {
            col.error(
                IssueCode.MISSING_REQUIRED, path,
                "episodes 必须声明 ids（JSONPath）或 idsSplit（正则 id 表）"
            )
        }
        if (hasIds && hasSplit) {
            col.warn(IssueCode.WARN_REQUIRED, path, "ids 与 idsSplit 同时存在：按 idsSplit 走，ids 不生效")
        }
        if (hasIds) checkPath(col, "$path.ids", ep.ids!!)
        if (hasSplit) {
            val regex = runCatching { Regex(ep.idsSplit!!) }.onFailure {
                col.error(IssueCode.BAD_PATH, "$path.idsSplit", "id 表正则语法错误: ${it.message}")
            }.getOrNull()
            // 没有捕获组时 group=1 会永远取不到值 → 每条都失败、整组消失。
            // Regex 没有跨平台的 groupCount，所以按括号粗数（跳过 `(?:` / `(?<` / 转义括号）：
            // 只用来拦住"忘了写括号"这种最常见的错，不求精确。
            if (regex != null && countGroups(ep.idsSplit!!) < ep.idsGroup) {
                col.error(
                    IssueCode.BAD_PATH, "$path.idsGroup",
                    "idsSplit 只有 ${countGroups(ep.idsSplit!!)} 个捕获组，取不到第 ${ep.idsGroup} 组"
                )
            }
        }

        val item = ep.item
        if (item == null) {
            col.error(IssueCode.MISSING_REQUIRED, "$path.item", "episodes 必须声明 item（每条子项怎么取）")
        } else {
            if (item.request.url.isBlank()) {
                col.error(IssueCode.MISSING_REQUIRED, "$path.item.request.url", "子项请求地址为空")
            }
            // 子项可以是 HTML（图集内页），但那时字段只能用 regex/const——这里只在"配了 jsonPath
            // 却声明成 text/html"时提示一句，免得作者以为是自己写错了选择器
            if (item.responseType != ResponseType.JSON && item.responseType != ResponseType.AUTO) {
                val jsonLike = item.fields.filterValues { it.kind == ExtractorKind.JSON_PATH }
                if (jsonLike.isNotEmpty()) {
                    col.warn(
                        IssueCode.WARN_PLATFORM, "$path.item.fields",
                        "子项是 ${item.responseType} 响应，jsonPath 字段（${jsonLike.keys}）取不到值；改用 regex"
                    )
                }
            }
            if (item.fields[DomainField.TITLE.key] == null) {
                // 子项接口不一定回标题（红果 fallback-play-info 只有 main_url）。引擎会退回
                // 「第 N 集」，所以这里降级为提示而不是硬错误。
                col.warn(IssueCode.WARN_REQUIRED, "$path.item.fields.title", "子项未映射 title，将按集号生成「第 N 集」")
            }
        }

        ep.request?.let {
            if (it.url.isBlank()) {
                col.error(IssueCode.MISSING_REQUIRED, "$path.request.url", "id 表请求地址为空")
            } else {
                checkUrl(col, "$path.request.url", it.url, c.flags.allowCleartext)
            }
        }
        // 分页只在"单独请求 id 表"时有意义
        if (ep.paging != null && ep.request == null) {
            col.warn(IssueCode.WARN_REQUIRED, path, "episodes.paging 需要配合 episodes.request 才生效（详情响应只有一份）")
        }
        if (ep.maxItems < 1) {
            col.error(IssueCode.BAD_ENUM, "$path.maxItems", "maxItems 至少为 1")
        }
        // 懒取图（2026-11-17）：只拿 id 表、逐张按需取直链。没有 item 就没有"怎么取那一张"，
        // 打开开关只会得到一堆空 url 的占位 track
        if (ep.lazyItems && ep.item == null) {
            col.error(IssueCode.MISSING_REQUIRED, "$path.lazyItems", "lazyItems 需要 episodes.item 才能逐张取直链")
        }
    }

    /** 粗数正则里的捕获组个数（跳过 `(?:`、`(?=`、`(?<`、字符类内与转义括号） */
    private fun countGroups(pattern: String): Int {
        var count = 0
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i++                       // 跳过被转义的字符
                c == '[' && !inClass -> inClass = true
                c == ']' && inClass -> inClass = false
                inClass -> Unit
                c == '(' -> {
                    val next = pattern.getOrNull(i + 1)
                    if (next != '?') count++           // `(?:` `(?=` `(?<!` 都不计入
                }
            }
            i++
        }
        return count
    }

    private fun checkResource(
        col: IssueCollector,
        path: String,
        res: ResourceSpec,
        isList: Boolean,
        allowCleartext: Boolean,
        isCategory: Boolean = false,
        crypto: CryptoSpec? = null,
    ) {
        val url = res.request.url
        if (url.isBlank()) {
            col.error(IssueCode.MISSING_REQUIRED, "$path.request.url", "请求地址为空")
        } else {
            checkUrl(col, "$path.request.url", url, allowCleartext)
        }

        val split = res.listSplit
        if (!split.isNullOrBlank()) {
            runCatching { Regex(split) }.onFailure {
                col.error(IssueCode.BAD_PATH, "$path.listSplit", "分条正则语法错误: ${it.message}")
            }
        } else if (isList && res.responseType != ResponseType.JSON && res.responseType != ResponseType.AUTO) {
            col.error(
                IssueCode.MISSING_REQUIRED, "$path.listSplit",
                "${res.responseType} 响应的列表资源必须声明 listSplit：JSONPath 无法作用在纯文本上"
            )
        }

        // 文本分条模式下没有 JSON 文档，root 只在 JSON 模式有意义
        if (split.isNullOrBlank()) {
            res.root?.let { checkPath(col, "$path.root", it) }
        }
        if (isList) {
            val list = res.list
            if (list.isNullOrBlank()) {
                if (split.isNullOrBlank()) {
                    col.error(IssueCode.MISSING_REQUIRED, "$path.list", "列表资源必须声明 list 或 listSplit")
                }
            } else {
                checkPath(col, "$path.list", list)
            }
        }

        res.strip?.let {
            if (it.prefix.isNullOrBlank() && it.suffix.isNullOrBlank()) {
                col.error(IssueCode.MISSING_REQUIRED, "$path.strip", "strip 至少需要 prefix 或 suffix 之一")
            }
        }

        // 响应解密（docs/03 §3.3）：from 为空=整段密文；非空则必须是合法路径
        res.decrypt?.let { d ->
            d.from?.takeIf { it.isNotBlank() }?.let { checkPath(col, "$path.decrypt.from", it) }
            mergeDecrypt(crypto, d)?.let { checkCrypto(col, "$path.decrypt", it, requireKey = true) }
        }
        // 请求参数加密
        res.request.encrypt?.let { checkEncrypt(col, "$path.request.encrypt", it, crypto) }

        if (res.fields.isEmpty()) {
            col.warn(IssueCode.MISSING_REQUIRED, "$path.fields", "没有任何字段映射，条目将全部为空")
        }

        res.fields.forEach { (key, spec) ->
            val field = DomainField.fromKey(key)
            // categories 是导航特化结构（id/name/icon），不套用媒体域字段表，见 docs/03 §6.4
            if (isCategory) {
                if (key !in CATEGORY_FIELDS) {
                    col.error(
                        IssueCode.UNDEFINED_FIELD, "$path.fields.$key",
                        "categories 只允许 id/name/icon，实际是 `$key`"
                    )
                    return@forEach
                }
            } else if (field == null) {
                col.error(
                    IssueCode.UNDEFINED_FIELD, "$path.fields.$key",
                    "未定义的域字段 `$key`（合法: ${DomainField.entries.map { it.key }}）"
                )
                return@forEach
            }
            checkFieldSpec(col, "$path.fields.$key", field, spec, crypto)
        }

        res.paging?.hasMore?.let { checkPath(col, "$path.paging.hasMore", it) }
        res.paging?.cursorFrom?.let { checkPath(col, "$path.paging.cursorFrom", it) }
        // cursor 模式必须有"怎么拿下一页游标"的规则，否则第 1 页就判到底（拉到底不加载，用户看不到原因）
        res.paging?.let { paging ->
            if (paging.mode == PagingMode.CURSOR && paging.cursorPattern.isNullOrBlank() &&
                paging.cursorFrom.isNullOrBlank()
            ) {
                col.warn(
                    IssueCode.WARN_REQUIRED, "$path.paging",
                    "cursor 模式需要 cursorPattern（文本通路）或 cursorFrom（JSON 通路），否则永远只有第 1 页"
                )
            }
            paging.cursorPattern?.takeIf { it.isNotBlank() }?.let { pattern ->
                if (runCatching { Regex(pattern) }.isFailure) {
                    col.error(IssueCode.BAD_PATH, "$path.paging.cursorPattern", "正则语法错误: $pattern")
                }
            }
            paging.nextUrl?.takeIf { it.isNotBlank() && !it.contains("{{cursor}}") }?.let {
                col.warn(
                    IssueCode.WARN_REQUIRED, "$path.paging.nextUrl",
                    "nextUrl 里没有 {{cursor}} 占位，第 2 页会与第 1 页请求同一个地址"
                )
            }
            // totalPattern：抓"共 N 条"用来算页数（只在 id 表分页里生效）
            paging.totalPattern?.takeIf { it.isNotBlank() }?.let { pattern ->
                if (runCatching { Regex(pattern) }.isFailure) {
                    col.error(IssueCode.BAD_PATH, "$path.paging.totalPattern", "正则语法错误: $pattern")
                }
            }
            if (paging.perPage < 0) {
                col.warn(
                    IssueCode.WARN_REQUIRED, "$path.paging.perPage",
                    "perPage 为负数，按 0（用第一页实际抓到的条数）处理"
                )
            }
        }
    }

    private fun checkFieldSpec(
        col: IssueCollector,
        path: String,
        field: DomainField?,
        spec: FieldSpec,
        crypto: CryptoSpec? = null,
    ) {
        // const 的 `from` 是字面量本身（kind 字段最常用），不是路径，不能按路径校验
        if (spec.kind != ExtractorKind.CONST) {
            spec.candidateExpressions.forEach { checkPath(col, "$path.from", it) }
        }

        when (spec.kind) {
            ExtractorKind.JSON_PATH -> if (spec.candidateExpressions.isEmpty()) {
                col.error(IssueCode.MISSING_REQUIRED, "$path.from", "jsonPath 抽取器缺少 from")
            }
            ExtractorKind.REGEX -> {
                val pattern = spec.pattern
                if (pattern.isNullOrBlank()) {
                    col.error(IssueCode.MISSING_REQUIRED, "$path.pattern", "regex 抽取器缺少 pattern")
                } else {
                    runCatching { Regex(pattern) }.onFailure {
                        col.error(IssueCode.BAD_PATH, "$path.pattern", "正则语法错误: ${it.message}")
                    }
                }
            }
            ExtractorKind.CSS -> if (spec.selector.isNullOrBlank()) {
                col.error(IssueCode.MISSING_REQUIRED, "$path.selector", "css 抽取器缺少 selector")
            }
            ExtractorKind.DELIMITED -> if (spec.levels.isEmpty()) {
                col.error(IssueCode.MISSING_REQUIRED, "$path.levels", "delimited 抽取器缺少 levels")
            }
            ExtractorKind.TEMPLATE -> if (spec.parts.isEmpty()) {
                col.error(IssueCode.MISSING_REQUIRED, "$path.parts", "template 抽取器缺少 parts")
            }
            ExtractorKind.CONST -> Unit
        }

        if (field == DomainField.KIND) {
            val const = spec.candidateExpressions.firstOrNull()
            if (spec.kind == ExtractorKind.CONST && const !in setOf("video", "audio", "image")) {
                col.error(
                    IssueCode.BAD_ENUM, path,
                    "kind 常量必须是 video/audio/image，实际是 `$const`"
                )
            }
        }

        if (spec.required && field != DomainField.ID && field != DomainField.TITLE) {
            col.warn(
                IssueCode.WARN_REQUIRED, path,
                "required=true 会让任何缺少该字段的条目被跳过，确认是有意为之"
            )
        }

        // 单字段解密（docs/03 §3.3）。这里的 `from` 无语义（值已经在手上），不校验
        spec.decrypt?.let { mergeDecrypt(crypto, it)?.let { m -> checkCrypto(col, "$path.decrypt", m, requireKey = true) } }
    }

    private fun validateTracks(c: UmpConfig, col: IssueCollector) {
        c.resources.tracks.forEach { (key, spec) ->
            checkTrackSpec(col, "resources.tracks.$key", spec)
        }
    }

    private fun checkTrackSpec(col: IssueCollector, path: String, spec: TrackSpec) {
        when (spec.mode) {
            TrackMode.DELIMITED -> {
                val from = spec.items.from
                if (from.isNullOrBlank()) {
                    col.error(IssueCode.MISSING_REQUIRED, "$path.items.from", "delimited 模式必须声明 items.from")
                } else {
                    checkPath(col, "$path.items.from", from)
                }
                spec.groupNames.from?.let { checkPath(col, "$path.groupNames.from", it) }

                if (spec.items.groupSplit.isEmpty()) {
                    col.error(IssueCode.MISSING_REQUIRED, "$path.items.groupSplit", "groupSplit 不能为空")
                }
                if (spec.items.itemSplit.isEmpty()) {
                    col.error(IssueCode.MISSING_REQUIRED, "$path.items.itemSplit", "itemSplit 不能为空")
                }
                val pair = spec.items.pairSplit
                if (pair != null && pair.startsWith(spec.items.groupSplit)) {
                    col.warn(
                        IssueCode.WARN_REQUIRED, path,
                        "pairSplit 与 groupSplit 前缀相同：按粗到细切分后可能错位"
                    )
                }
            }
            TrackMode.ARRAY -> {
                val from = spec.from
                if (from.isNullOrBlank()) {
                    col.error(IssueCode.MISSING_REQUIRED, "$path.from", "array 模式必须声明 from")
                } else {
                    checkPath(col, "$path.from", from)
                }
                if (spec.fields["url"] == null) {
                    col.error(IssueCode.MISSING_REQUIRED, "$path.fields.url", "array 模式必须映射 url")
                }
                spec.fields.forEach { (k, f) ->
                    if (k !in setOf("title", "url", "thumb", "duration")) {
                        col.error(IssueCode.MISSING_REQUIRED, "$path.fields.$k", "array 模式不支持字段 $k")
                    }
                    f.candidateExpressions.forEach { checkPath(col, "$path.fields.$k", it) }
                    // 条目串/数组的字段解密暂未接线（字段级解密只对资源的 `fields` 生效）
                    if (f.decrypt != null) {
                        col.warn(
                            IssueCode.WARN_PLATFORM, "$path.fields.$k",
                            "resources.tracks 的字段解密暂未生效（v1 只支持 resources.*.fields），该字段会按明文处理"
                        )
                    }
                }
            }
        }
    }

    private fun validatePages(c: UmpConfig, col: IssueCollector) {
        val feedKeys = c.resources.feeds.keys + c.resources.search.keys
        val detailKeys = c.resources.details.keys
        val trackKeys = c.resources.tracks.keys

        fun walk(pages: List<PageSpec>, prefix: String) {
            pages.forEachIndexed { i, page ->
                val path = "$prefix[$i]"
                if (page.id.isBlank()) {
                    col.error(IssueCode.MISSING_REQUIRED, "$path.id", "缺少页面 id")
                }

                when (page.layout.type) {
                    LayoutType.DETAIL -> {
                        val res = page.layout.resource
                        if (res.isNullOrBlank()) {
                            col.error(IssueCode.MISSING_REQUIRED, "$path.layout.resource", "detail 布局必须指定 resource")
                        } else if (res !in detailKeys) {
                            col.error(
                                IssueCode.MISSING_REF, "$path.layout.resource",
                                "详情资源不存在: $res（已定义: $detailKeys）"
                            )
                        }
                        page.layout.tracks?.let { t ->
                            if (t !in trackKeys) {
                                col.error(
                                    IssueCode.MISSING_REF, "$path.layout.tracks",
                                    "条目资源不存在: $t（已定义: $trackKeys）"
                                )
                            }
                        }
                    }
                    else -> {
                        page.feed?.let { f ->
                            if (f !in feedKeys) {
                                col.error(
                                    IssueCode.MISSING_REF, "$path.feed",
                                    "引用不存在的资源: $f（已定义: $feedKeys）"
                                )
                            }
                        }
                    }
                }

                walk(page.children, "$path.children")
                walk(page.sections, "$path.sections")

                // 栏目 tab：只切"取数入口"，所以 feed 必须存在，且不允许拿 detail 当栏目布局
                if (page.channels.isNotEmpty() && page.children.isNotEmpty()) {
                    // 两种 tab 语义互斥：channels 切的是"同一套布局下的数据"，children 切的是布局本身。
                    // 同时写会出现"切了栏目但布局也跟着变"的歧义，用户无法预期。
                    col.error(
                        IssueCode.BAD_ENUM, "$path.channels",
                        "channels 与 children 互斥：栏目切数据、children 切布局，二者只能选一种"
                    )
                }
                val seenChannelIds = mutableSetOf<String>()
                page.channels.forEachIndexed { ci, ch ->
                    val cPath = "$path.channels[$ci]"
                    if (ch.id.isBlank()) {
                        col.error(IssueCode.MISSING_REQUIRED, "$cPath.id", "栏缺少 id")
                    } else if (!seenChannelIds.add(ch.id)) {
                        col.error(IssueCode.MISSING_REQUIRED, "$cPath.id", "栏目 id 重复: ${ch.id}")
                    }
                    if (ch.feed !in feedKeys) {
                        col.error(
                            IssueCode.MISSING_REF, "$cPath.feed",
                            "栏目引用的资源不存在: ${ch.feed}（已定义: $feedKeys）"
                        )
                    }
                    if (ch.layout?.type == LayoutType.DETAIL) {
                        col.error(IssueCode.BAD_ENUM, "$cPath.layout.type", "栏目不能用 detail 布局")
                    }
                }
                page.viewModes.forEachIndexed { vi, mode ->
                    if (mode == LayoutType.DETAIL) {
                        col.error(IssueCode.BAD_ENUM, "$path.viewModes[$vi]", "视图切换不能包含 detail")
                    }
                }
            }
        }

        walk(c.pages, "pages")
    }

    private fun validatePlayback(c: UmpConfig, col: IssueCollector) {
        val pb = c.playback
        if (pb.strategy == PlayStrategy.RESOLVER && pb.resolver == null) {
            col.error(IssueCode.MISSING_REQUIRED, "playback.resolver", "strategy=resolver 时必须提供 resolver")
        }
        if (pb.audio.mode !in setOf("sequential", "loop", "shuffle")) {
            col.error(
                IssueCode.BAD_ENUM, "playback.audio.mode",
                "未知音频模式 ${pb.audio.mode}（sequential | loop | shuffle）"
            )
        }
    }

    private fun checkUrl(col: IssueCollector, path: String, url: String, allowCleartext: Boolean) {
        val lower = url.lowercase()
        when {
            lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("javascript:") ->
                col.error(IssueCode.BAD_SCHEME, path, "非法 scheme: ${url.take(40)}")
            lower.startsWith("http://") && !allowCleartext ->
                col.warn(IssueCode.WARN_PLATFORM, path, "明文 HTTP，需要 flags.allowCleartext=true")
            !ALLOWED_SCHEMES.any { lower.startsWith(it) } ->
                col.error(IssueCode.MISSING_REQUIRED, path, "URL 既不是 http(s) 也不是模板: ${url.take(40)}")
        }
    }

    private fun checkPath(col: IssueCollector, path: String, expr: String) {
        try {
            JsonPath.compile(expr)
        } catch (e: QueryException) {
            col.error(e.error.code.let { if (it == "E004") IssueCode.BAD_PATH else IssueCode.MISSING_REQUIRED }, path, e.message ?: "路径非法")
        }
    }
}
