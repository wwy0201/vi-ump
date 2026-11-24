package com.vi.ump.config

import com.vi.ump.model.BodyType
import com.vi.ump.model.CryptoSpec
import com.vi.ump.model.DecryptSpec
import com.vi.ump.model.DomainField
import com.vi.ump.model.EncryptSpec
import com.vi.ump.model.EpisodesSpec
import com.vi.ump.model.FieldSpec
import com.vi.ump.model.PageSpec
import com.vi.ump.model.PagingSpec
import com.vi.ump.model.ResourceSpec
import com.vi.ump.model.ResponseType
import com.vi.ump.model.RequestSpec
import com.vi.ump.model.StripSpec
import com.vi.ump.model.TrackSpec
import com.vi.ump.model.UmpConfig
import com.vi.ump.query.ConstExtractor
import com.vi.ump.query.CryptoCodec
import com.vi.ump.query.CryptoOptions
import com.vi.ump.query.Extractor
import com.vi.ump.query.Extractors
import com.vi.ump.query.JsonPath
import com.vi.ump.query.PathExpr

/**
 * L3 编译产物。
 *
 * 与直接使用 [UmpConfig] 的差别：
 *  - JSONPath 已经 token 化，运行期不再 `split`/`trim` 字符串
 *  - 正则已经 `Regex.compile()`，不再每帧编译
 *  - `fields` 的 key 从 String 变成 [DomainField] 枚举，拼错在加载期就暴露
 */
data class CompiledField(
    val field: DomainField,
    val spec: FieldSpec,
    val extractor: Extractor,
    /** 单字段解密（docs/03 §3.3）：已把 `runtime.crypto` 的缺省值烘焙进来，运行期不再查全局状态 */
    val crypto: CryptoOptions? = null,
)

/**
 * 响应解密（docs/03 §3.3）。[from] 为 `null` 表示整段响应就是密文。
 */
data class CompiledDecrypt(
    val from: PathExpr?,
    val crypto: CryptoOptions,
)

/**
 * 请求参数加密（docs/03 §3.3）。`mode=params` 时只处理 [params] 里列出的参数；
 * `mode=whole` 时整个 body 换成密文。
 */
data class CompiledEncrypt(
    val mode: String,
    val params: Set<String>,
    val crypto: CryptoOptions,
)

data class CompiledRequest(
    val method: String,
    val urlTemplate: String,
    val headers: Map<String, String>,
    val body: String?,
    val bodyType: BodyType,
    val responseType: ResponseType,
    val encrypt: CompiledEncrypt? = null,
    /** 请求签名方案（`douyin`）；null = 不签 */
    val sign: String? = null,
)

data class CompiledResource(
    val key: String,
    val request: CompiledRequest,
    val root: PathExpr?,
    val list: PathExpr?,
    val fields: Map<DomainField, CompiledField>,
    val paging: PagingSpec?,
    /** 文本响应按此正则切成条目；每条一个 `Payload.Text` */
    val listSplit: Regex? = null,
    /** 响应体前后缀剥离 */
    val strip: StripSpec? = null,
    /** 响应解密（整段 / 按路径取一个值），在 `strip` 之后执行 */
    val decrypt: CompiledDecrypt? = null,
    /** 选集：拿 id 表的一次请求 + 每条子项的一次请求 */
    val episodes: CompiledEpisodes? = null,
) {
    val isList: Boolean get() = list != null || listSplit != null

    /** 文本分条模式：条目来自正则命中，而不是 JSONPath 求值 */
    val isTextSplit: Boolean get() = listSplit != null
}

/**
 * 选集编译产物。
 *
 * 与 [CompiledResource] 的差别：它的字段没有固定的域字段集合——
 * 子项只关心"标题 / 时长 / 播放地址"，所以这里把三者单独拎出来，
 * 其余（如 `fvkey` 这种只用来判锁的）走 [lockedPath]。
 */
data class CompiledEpisodes(
    val spec: EpisodesSpec,
    val request: CompiledRequest?,
    val strip: StripSpec?,
    val root: PathExpr?,
    val ids: PathExpr?,
    /** 与 ids 平行的标题表（可选），见 [EpisodesSpec.titles] */
    val titles: PathExpr? = null,
    /** 标题为空时的兜底列，见 [EpisodesSpec.titlesFallback] */
    val titlesFallback: PathExpr? = null,
    /** 文本通路的 id 表（`idsSplit` 的正则，已编译）；与 [ids] 二选一，优先 */
    val idsRegex: Regex? = null,
    val item: CompiledItem?,
)

data class CompiledItem(
    val request: CompiledRequest,
    val root: PathExpr?,
    val strip: StripSpec?,
    val title: CompiledField?,
    val duration: CompiledField?,
    val playUrl: CompiledField?,
    val locked: PathExpr?,
    /** 每集响应解密（取子项的 `ResourceSpec.decrypt`） */
    val decrypt: CompiledDecrypt? = null,
)

/**
 * `resources.tracks` 的编译产物。
 *
 * 三个路径都要预编译：`from`（`array` 模式的数组入口）、`groupNames.from`（线路名）、
 * `items.from`（剧集串）。后者是 `delimited` 模式的主体——一个字符串里塞着
 * "线路1$$$线路2" 和 "第1集$url1#第2集$url2$$$…"，切开顺序 `groupSplit → itemSplit → pairSplit` 不能乱。
 */
data class CompiledTrack(
    val key: String,
    val spec: TrackSpec,
    val from: PathExpr?,
    val groupNames: PathExpr? = null,
    val items: PathExpr? = null,
)

data class CompiledSchema(
    val config: UmpConfig,
    val feeds: Map<String, CompiledResource>,
    val details: Map<String, CompiledResource>,
    val search: Map<String, CompiledResource>,
    val tracks: Map<String, CompiledTrack>,
    val issues: List<ConfigIssue>,
) {
    val pages: List<PageSpec> get() = config.pages

    val hasErrors: Boolean get() = issues.any { it.severity == Severity.ERROR }

    fun listResource(key: String): CompiledResource? = feeds[key] ?: search[key]

    fun detailResource(key: String): CompiledResource? = details[key]

    fun track(key: String): CompiledTrack? = tracks[key]
}

object ConfigCompiler {

    /** 装载 + 压缩成一份 schema。任何一步出错都通过 [CompiledSchema.issues] 汇报，不抛栈 */
    fun loadAndCompile(text: String): CompiledSchema = compile(ConfigLoader.load(text))

    fun compile(config: UmpConfig): CompiledSchema {
        val issues = ConfigValidator.validate(config)
        val crypto = config.runtime.crypto
        return CompiledSchema(
            config = config,
            feeds = config.resources.feeds.mapValues { compileResource(it.key, it.value, crypto) },
            details = config.resources.details.mapValues { compileResource(it.key, it.value, crypto) },
            search = config.resources.search.mapValues { compileResource(it.key, it.value, crypto) },
            tracks = config.resources.tracks.mapValues { (key, spec) ->
                CompiledTrack(
                    key = key,
                    spec = spec,
                    from = spec.from?.let(::safePath),
                    groupNames = spec.groupNames.from?.let(::safePath),
                    items = spec.items.from?.let(::safePath),
                )
            },
            issues = issues,
        )
    }

    private fun compileResource(key: String, res: ResourceSpec, defaults: CryptoSpec?): CompiledResource =
        CompiledResource(
            key = key,
            request = compileRequest(res.request, res.responseType, defaults),
            root = res.root?.let(::safePath),
            list = res.list?.let(::safePath),
            fields = compileFields(res.fields, defaults),
            paging = res.paging,
            listSplit = res.listSplit?.let { runCatching { Regex(it) }.getOrNull() },
            strip = res.strip,
            decrypt = compileDecrypt(res.decrypt, defaults),
            episodes = res.episodes?.let { compileEpisodes(it, defaults) },
        )

    private fun compileFields(
        specs: Map<String, FieldSpec>,
        defaults: CryptoSpec?,
    ): Map<DomainField, CompiledField> = specs.mapNotNull { (rawKey, spec) ->
        val field = DomainField.fromKey(rawKey) ?: return@mapNotNull null
        field to CompiledField(
            field = field,
            spec = spec,
            extractor = safeExtractor(spec),
            crypto = bakeDecryptCrypto(spec.decrypt, defaults),
        )
    }.toMap()

    private fun compileRequest(
        req: RequestSpec,
        responseType: ResponseType,
        defaults: CryptoSpec?,
    ): CompiledRequest = CompiledRequest(
        method = req.method.uppercase(),
        urlTemplate = req.url,
        headers = req.headers,
        body = req.body,
        bodyType = req.bodyType,
        responseType = responseType,
        encrypt = compileEncrypt(req.encrypt, defaults),
        sign = req.sign?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
    )

    /**
     * 响应/字段解密：把 `runtime.crypto` 的缺省值合并进来。
     * `from` 写了但路径编译失败 → 当作"没有这条规则"，避免整段误判成密文。
     */
    private fun compileDecrypt(spec: DecryptSpec?, defaults: CryptoSpec?): CompiledDecrypt? {
        if (spec == null) return null
        val crypto = bakeDecryptCrypto(spec, defaults) ?: return null
        val from = spec.from?.trim()
        val fromPath = if (from.isNullOrEmpty()) null else (safePath(from) ?: return null)
        return CompiledDecrypt(from = fromPath, crypto = crypto)
    }

    private fun bakeDecryptCrypto(spec: DecryptSpec?, defaults: CryptoSpec?): CryptoOptions? {
        if (spec == null) return null
        return bakeCrypto(
            spec.algo, spec.key, spec.iv, spec.padding, spec.output, spec.keyFormat, spec.ivFormat, defaults,
        )
    }

    private fun compileEncrypt(spec: EncryptSpec?, defaults: CryptoSpec?): CompiledEncrypt? {
        if (spec == null) return null
        val mode = spec.mode.trim().lowercase()
        if (mode != "params" && mode != "whole") return null
        if (mode == "params" && spec.params.isEmpty()) return null
        val crypto = bakeCrypto(
            spec.algo, spec.key, spec.iv, spec.padding, spec.output, spec.keyFormat, spec.ivFormat, defaults,
        ) ?: return null
        return CompiledEncrypt(mode = mode, params = spec.params.toSet(), crypto = crypto)
    }

    /**
     * 缺省继承 + 参数合法性检查，产出运行期参数。
     * 任何一处不合法返回 null（= 这条规则不生效）：配置错误已经在 [ConfigValidator] 报过，
     * 运行期再炸一次没有意义。
     */
    private fun bakeCrypto(
        algo: String?,
        key: String?,
        iv: String?,
        padding: String?,
        output: String?,
        keyFormat: String?,
        ivFormat: String?,
        defaults: CryptoSpec?,
    ): CryptoOptions? {
        val a = CryptoCodec.normalizeAlgo(algo ?: defaults?.algo) ?: return null
        val keyRaw = (key ?: defaults?.key).orEmpty()
        if (keyRaw.isEmpty()) return null
        val keyFmt = (keyFormat ?: defaults?.keyFormat ?: "text").lowercase()
        val keyBytes = CryptoCodec.keyBytes(keyRaw, keyFmt) ?: return null
        if (keyBytes.size != CryptoCodec.KEY_SIZES[a]) return null

        val ivRaw = (iv ?: defaults?.iv).orEmpty()
        val ivFmt = (ivFormat ?: defaults?.ivFormat ?: "text").lowercase()
        val ivBytes = if (ivRaw.isEmpty()) ByteArray(0) else (CryptoCodec.keyBytes(ivRaw, ivFmt) ?: return null)
        val ivNeed = CryptoCodec.ivSize(a) ?: return null
        if (ivNeed > 0 && ivBytes.size != ivNeed) return null

        val pad = (padding ?: defaults?.padding ?: "pkcs7").lowercase()
        val out = (output ?: defaults?.output ?: "base64").lowercase()
        if (pad !in CryptoCodec.PADDINGS || out !in CryptoCodec.OUTPUTS) return null
        return CryptoOptions(a, keyBytes, ivBytes, pad, out)
    }

    private fun compileEpisodes(spec: EpisodesSpec, defaults: CryptoSpec?): CompiledEpisodes = CompiledEpisodes(
        spec = spec,
        request = spec.request?.let { compileRequest(it, ResponseType.JSON, defaults) },
        strip = spec.strip,
        root = spec.root?.let(::safePath),
        ids = spec.ids?.let(::safePath),
        titles = spec.titles?.let(::safePath),
        titlesFallback = spec.titlesFallback?.let(::safePath),
        idsRegex = spec.idsSplit?.let { runCatching { Regex(it) }.getOrNull() },
        item = spec.item?.let { res ->
            val byField = compileFields(res.fields, defaults)
            CompiledItem(
                request = compileRequest(res.request, res.responseType, defaults),
                root = res.root?.let(::safePath),
                strip = res.strip,
                title = byField[DomainField.TITLE],
                duration = byField[DomainField.DURATION],
                playUrl = byField[DomainField.PLAY_URL],
                locked = spec.lockedPath?.let(::safePath),
                decrypt = compileDecrypt(res.decrypt, defaults),
            )
        },
    )

    private fun safePath(expr: String): PathExpr? = runCatching { JsonPath.compile(expr) }.getOrNull()

    private fun safeExtractor(spec: FieldSpec): Extractor =
        runCatching { Extractors.of(spec) }.getOrElse { ConstExtractor("") }
}
