package com.vi.ump.source

import com.vi.ump.config.CompiledDecrypt
import com.vi.ump.config.CompiledEpisodes
import com.vi.ump.config.CompiledField
import com.vi.ump.config.CompiledItem
import com.vi.ump.config.CompiledRequest
import com.vi.ump.config.CompiledResource
import com.vi.ump.config.CompiledSchema
import com.vi.ump.config.CompiledTrack
import com.vi.ump.model.DomainField
import com.vi.ump.model.FieldSpec
import com.vi.ump.model.LabelSpec
import com.vi.ump.model.MediaDetail
import com.vi.ump.model.MediaKind
import com.vi.ump.model.MediaSummary
import com.vi.ump.model.PagedResult
import com.vi.ump.model.PagingMode
import com.vi.ump.model.PagingSpec
import com.vi.ump.model.StripSpec
import com.vi.ump.model.Track
import com.vi.ump.model.TrackGroup
import com.vi.ump.model.TrackMode
import com.vi.ump.query.CryptoCodec
import com.vi.ump.query.CryptoOptions
import com.vi.ump.query.DomainMapper
import com.vi.ump.query.Extractors
import com.vi.ump.query.HtmlSelector
import com.vi.ump.query.JsonPath
import com.vi.ump.query.NoHtmlSelector
import com.vi.ump.query.PathExpr
import com.vi.ump.query.Payload
import com.vi.ump.query.TransformChain
import com.vi.ump.storage.EpisodesCache
import com.vi.ump.storage.episodesCacheKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * 五阶段管线：
 *
 * ```
 * ① 变量表   runtime + 页面参数 + 分页
 * ② 请求     渲染 URL/body/headers → HttpClient
 * ③ 解析     剥前后缀 → JSON 取列表 / 文本按 listSplit 切条
 * ④ 抽取     root 定位 → list 求值（或逐条文本）→ 每个元素做字段映射
 * ⑤ 组装     MediaSummary 列表 / MediaDetail，算 hasMore 与 skipped
 * ```
 *
 * 两条数据通路并行存在：
 *  - **JSON 通路**：`root` + `list` 走 JSONPath，字段多用 `jsonPath` 抽取器
 *  - **文本通路**：`responseType` 非 JSON 时用 `listSplit` 正则分条，每条一个 [Payload.Text]，
 *    字段只能用 `regex` / `css` / `const`（YouTube Atom、腾讯 getinfo 都走这条）
 *
 * 失败隔离：单条记录缺必填字段只计入 `skipped`，不影响整页。
 */
class SourcePipeline(
    private val schema: CompiledSchema,
    private val fetcher: Fetcher,
    private val templates: TemplateEngine = TemplateEngine(),
    private val htmlSelector: HtmlSelector = NoHtmlSelector,
    private val pageSize: Int = 20,
    /**
     * 选集缓存。null = 不缓存（测试用 MockFetcher 时不想要跨用例的残留数据）。
     * 持久化，但**带 6 小时 TTL**（`EPISODES_CACHE_TTL_MS`，由平台实现按 `#ts` 判定），
     * 见 `core:storage` 里的 `EpisodesCache`。
     */
    private val episodesCache: EpisodesCache? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 游标链（2026-11-17）：`feedKey → (页码 → 该页响应里抽到的"下一页游标")`。
     *
     * 有了它，`cursor` 模式才能"顺着翻"：第 N 页要知道第 N-1 页给出的游标，而这只能从上一次响应里拿到。
     * 缓存在 pipeline 实例上（一个源一个实例、随进程消失），刷新时第 1 页会覆盖自己那一格，
     * 后续页自然跟着新链走。
     */
    private val cursorChain = mutableMapOf<String, MutableMap<Int, String>>()

    suspend fun list(
        key: String,
        page: Int = 1,
        extra: Map<String, String> = emptyMap(),
    ): PagedResult<MediaSummary> {
        val res = schema.listResource(key)
            ?: throw SourceException("E005", "列表资源不存在: $key")

        val paging = res.paging
        // 游标模式：第 1 页不带游标；第 2 页起必须拿上一页抽到的 CR。链断了就按"到底"处理，
        // 而不是退回第 1 页（那会把第一页内容重复追加一遍）
        var nextCursor: String? = null
        val cursorExtra = if (paging?.mode == PagingMode.CURSOR) {
            val cursor = if (page <= 1) {
                ""
            } else {
                cursorChain[key]?.get(page - 1)?.takeIf { it.isNotBlank() }
                    ?: return PagedResult(items = emptyList(), page = page, hasMore = false)
            }
            mapOf("cursor" to cursor)
        } else {
            emptyMap()
        }

        // 游标必须同时进 `variables`：`nextUrl` 模板里的 `{{cursor}}` 靠它渲染，
        // 只塞进 `pagingVars` 的话模板会拿到空串（拼出一个空 URL）
        val effectiveExtra = extra + cursorExtra
        val vars = variables(effectiveExtra) + pagingVars(paging, page, effectiveExtra)
        // 第 2 页起改用 paging.nextUrl（里面是 {{cursor}}）；没配就沿用原 URL（配置自己拼 {{cursor}}）
        val urlOverride = if (paging?.mode == PagingMode.CURSOR && page > 1) {
            paging.nextUrl?.let { templates.render(it, vars) }
        } else {
            null
        }
        val fetched = execute(res, vars, urlOverride)

        val body = decryptBody(stripBody(fetched.body, res.strip), res.decrypt)

        if (paging?.mode == PagingMode.CURSOR) {
            nextCursor = extractCursor(paging, body)
            cursorChain.getOrPut(key) { mutableMapOf() }[page] = nextCursor.orEmpty()
        }

        val parsed = parseList(res, body)
        val specs = renderedSpecs(res, vars)
        val cryptos = cryptoByField(res)

        val items = ArrayList<MediaSummary>(parsed.payloads.size)
        // 真实站点的一页里经常有重复卡片：首页多个推荐位指向同一部剧、榜单同时出现
        // "总榜"与"分类榜"。UI 用 id 作为 LazyGrid 的 key，重复 id 会直接抛
        // IllegalArgumentException 把页面打崩，所以在进 UI 之前按 id 去重。
        val seenIds = HashSet<String>(parsed.payloads.size)
        var skipped = 0
        for (p in parsed.payloads) {
            val item = DomainMapper.mapItem(specs, p, htmlSelector, cryptos, vars)
            if (item == null) {
                skipped++
            } else if (item.id.isNotBlank() && !seenIds.add(item.id)) {
                // 重复卡片不算"坏条目"：UI 的 skipped 文案是"必填字段缺失"，
                // 把推荐位重复计进去会误导用户，这里静默丢掉即可。
            } else {
                items += item
            }
        }

        val hasMore = resolveHasMore(paging, page, parsed, items, nextCursor)
        // 一行调试输出：分页/双击刷新这类"画面看不出差别"的行为，只能靠 logcat 判定
        // （app 本身不打请求日志，而刷新后数据往往一模一样）
        println("[list] $key page=$page items=${items.size} skipped=$skipped hasMore=$hasMore")
        return PagedResult(items = items, page = page, hasMore = hasMore, skipped = skipped)
    }

    /**
     * 从响应原文里抽出"下一页游标"。JSON 通路用 `cursorFrom`（路径），文本通路用 `cursorPattern`（正则）。
     * 抽不到 = 没有下一页（`emptyStop` 的语义落在调用方）。
     */
    private fun extractCursor(paging: PagingSpec, body: String): String? {
        paging.cursorPattern?.takeIf { it.isNotBlank() }?.let { pattern ->
            val m = runCatching { Regex(pattern).find(body) }.getOrNull() ?: return null
            val g = paging.cursorGroup.coerceIn(0, m.groupValues.lastIndex)
            return m.groupValues[g].takeIf { it.isNotBlank() }
        }
        // JSON 通路：先在解密后的 body 上解析一次，失败就放弃（游标多出现在 HTML 里）
        paging.cursorFrom?.takeIf { it.isNotBlank() }?.let { expr ->
            val doc = parseJson(body) ?: return null
            val path = runCatching { JsonPath.compile(expr) }.getOrNull() ?: return null
            return path.evalFirstString(doc)?.takeIf { it.isNotBlank() }
        }
        return null
    }

    /**
     * 取一张"懒取图"的真实直链（2026-11-17，配合 `episodes.lazyItems`）。
     *
     * 详情阶段只交了 id 表（`Track.pending` 非空、`url` 为空），翻到第 N 张时由这里补一次请求。
     * 失败返回 null —— 查看器把这一页画成"加载失败，可重试"，而不是整本图集打不开。
     *
     * @param key 详情资源键（`pages[].layout.resource`）
     * @param itemId 条目 id（详情 URL 里的那一段，模板里是 `{{id}}`）
     * @param index 第几条（1 起）；标题兜底与 Track.index 用
     * @param pendingId id 表里那一项，充当 `{{episode}}`
     */
    suspend fun resolvePendingTrack(
        key: String,
        itemId: String,
        index: Int,
        pendingId: String,
    ): Track? {
        val res = schema.detailResource(key) ?: return null
        val ep = res.episodes ?: return null
        val item = ep.item ?: return null
        return runCatching { fetchTrack(item, ep, index, pendingId, variables(mapOf("id" to itemId))) }
            .getOrNull()
            .also { println("[img] resolve index=$index pending=$pendingId ok=${!it?.url.isNullOrBlank()}") }
    }

    /**
     * 详情取数。
     *
     * @param trackKey `pages[].layout.tracks` 指向的 `resources.tracks` 键。声明了它，
     *   详情响应里"整串塞着剧集/曲目/图集"的字段就会被切成分组；`episodes`（两级取数）
     *   与它**可以并存**：前者出选集，后者出线路/曲目/图集。
     */
    suspend fun detail(
        key: String,
        id: String,
        extra: Map<String, String> = emptyMap(),
        trackKey: String? = null,
    ): MediaDetail? {
        val res = schema.detailResource(key)
            ?: throw SourceException("E005", "详情资源不存在: $key")

        val vars = variables(extra) + ("id" to id)
        val fetched = execute(res, vars)

        val body = decryptBody(stripBody(fetched.body, res.strip), res.decrypt)

        val payload = detailPayload(res, body) ?: return null

        val specs = renderedSpecs(res, vars)
        val values = DomainMapper.mapFields(specs, payload, htmlSelector, cryptoByField(res), vars)
        val summary = DomainMapper.toSummary(values) ?: return null

        // 两条分组通路：`episodes`（id 表 + 逐条，腾讯那种）在前，`tracks`（响应内直接切）在后。
        // 前者失败只丢那一组，不影响详情主体；后者是纯本地切分，不会失败。
        val groups = loadEpisodeGroups(res, payload, vars, id, summary.kind, body) +
            buildSpecTrackGroups(trackKey, payload, summary.kind, vars)

        println(
            "[detail] $key id=$id groups=${groups.size} " +
                "tracks=${groups.sumOf { it.tracks.size }} lazy=${groups.sumOf { g -> g.tracks.count { it.pending != null } }}"
        )
        return MediaDetail(
            summary = summary,
            description = values[DomainField.DESCRIPTION]?.toString()?.takeIf { it.isNotBlank() },
            actors = when (val a = values[DomainField.ACTORS]) {
                is List<*> -> a.map { it.toString() }
                null -> emptyList()
                else -> listOf(a.toString())
            },
            director = values[DomainField.DIRECTOR]?.toString()?.takeIf { it.isNotBlank() },
            trackGroups = groups,
        )
    }

    /**
     * 选集：一级拿 id 表，二级逐条取详情。
     *
     * 腾讯这套必须两级——`vinfo_proxy` 只给 `video_ids`，每集的标题/时长/是否可播
     * 要单独打 `getinfo`（该接口不支持批量，一次只认一个 vid）。所以这里按
     * `concurrency` 分批并发，48 条实测约 1.5~2 秒。
     *
     * 返回**多个**组：`video_ids` 里正片、预告、彩蛋混着且乱序（第 6 条可能是预告、
     * 第 23 条才是第 6 集），全部塞进一个网格会出现两个"第6集"。所以按 state 拆组，
     * 正片按标题里的集号排序。
     */
    private suspend fun loadEpisodeGroups(
        res: CompiledResource,
        detailPayload: Payload,
        vars: Map<String, String>,
        itemId: String,
        kind: MediaKind,
        detailBody: String,
    ): List<TrackGroup> {
        val episodesSpec = res.episodes ?: return emptyList()
        val cache = episodesCache ?: return fetchEpisodeGroups(res, detailPayload, vars, kind, detailBody)
        val cacheKey = episodesCacheKey(schema.config.meta.id, itemId)

        // TTL 按源配置（缺省 6h）：签名地址短命的源必须调小，否则"缓存命中 = 必然播放失败"
        val ttlMs = episodesSpec.spec.cacheTtlMs.coerceIn(60_000L, 7L * 24 * 3600 * 1000)
        // 命中就直接返回：这是"再进一次详情页不用等 2 秒"的地方
        cache.read(cacheKey, ttlMs)?.let { raw ->
            val cached = EpisodeCacheCodec.decode(raw)
            if (cached.isNotEmpty()) return cached
        }

        val fresh = fetchEpisodeGroups(res, detailPayload, vars, kind, detailBody)
        // 只有真拿到东西才落盘：空结果写进去会把上一次的好缓存顶掉
        if (fresh.isNotEmpty()) {
            runCatching { cache.write(cacheKey, EpisodeCacheCodec.encode(fresh)) }
        }
        return fresh
    }

    // ── 通用条目切分（`resources.tracks`）──────────────────────

    /**
     * 把详情响应里的"条目串/数组"切成分组。
     *
     * 与 [loadEpisodeGroups] 的分工：那条是"id 表 + 逐条二次请求"（腾讯的 `vinfo_proxy` + `getinfo`），
     * 这条是"响应里本来就有整串或数组"，纯本地切分、不发请求。一个详情可以两条都用。
     *
     * 两种形态（`TrackSpec.mode`）：
     * | mode | 来源 | 典型用法 |
     * |---|---|---|
     * | `delimited` | `items.from` 拿到一个字符串 | 视频剧集 / 音频曲目串，如 `"第1集$url1#第2集$url2$$$…"` |
     * | `array` | `from` 拿到一个数组 | 图片集，如 `"$.photos[*]"` |
     */
    private fun buildSpecTrackGroups(
        trackKey: String?,
        payload: Payload,
        kind: MediaKind,
        vars: Map<String, String>,
    ): List<TrackGroup> {
        val track = trackKey?.let { schema.track(it) } ?: return emptyList()
        val root = (payload as? Payload.Json)?.root ?: return emptyList()
        return when (track.spec.mode) {
            TrackMode.ARRAY -> arrayGroups(track, root, kind, vars)
            TrackMode.DELIMITED -> delimitedGroups(track, root, kind)
        }
    }

    /**
     * `array` 形态：数组 → 每个元素一张图/一首曲。
     *
     * `fields` 的键限定 `title / url / thumb / duration`，`url` 必填（没地址的条目没有意义，
     * 直接跳过而不是留一格空白）。值既可以是路径简写，也可以是完整 `FieldSpec`——
     * 后者用来拼 `imageBase`：`{ "kind": "jsonPath", "from": "$.url", "prefix": "{{imageBase}}" }`。
     * 注意 `prefix` 里的 `{{imageBase}}` 必须在这里渲染：它不走资源 `fields` 那条渲染通路，
     * 漏了就会把字面量 `{{imageBase}}` 当域名拼进地址（图片全 404）。
     */
    private fun arrayGroups(
        track: CompiledTrack,
        root: JsonElement,
        kind: MediaKind,
        vars: Map<String, String>,
    ): List<TrackGroup> {
        val spec = track.spec
        // 路径可能指到数组本身，也可能指到元素（`$.photos[*]`）；两种写法都展平一次
        val elements = track.from?.eval(root)
            ?.flatMap { el -> if (el is JsonArray) el.toList() else listOf(el) }
            ?.filter { it != JsonNull }
            .orEmpty()
        if (elements.isEmpty()) return emptyList()

        fun pick(el: JsonElement, key: String): String? {
            val raw = spec.fields[key] ?: return null
            val field = raw.copy(
                prefix = templates.render(raw.prefix, vars),
                suffix = templates.render(raw.suffix, vars),
            )
            val raws = runCatching { Extractors.of(field).extract(Payload.Json(el)) }.getOrNull()
                ?: return null
            return raws.mapNotNull { TransformChain.apply(it, field) }
                .firstOrNull { it.isNotBlank() }
        }

        val tracks = elements.mapIndexedNotNull { i, el ->
            val url = pick(el, "url") ?: return@mapIndexedNotNull null
            Track(
                index = i + 1,
                title = pick(el, "title") ?: autoLabel(spec.label, i + 1, kind),
                url = url,
                thumb = pick(el, "thumb"),
                durationMs = pick(el, "duration")?.toDoubleOrNull()
                    ?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
            )
        }
        if (tracks.isEmpty()) return emptyList()

        return listOf(TrackGroup(name = defaultGroupName(kind), kind = kind, tracks = tracks))
    }

    /**
     * `delimited` 形态：一个字符串里塞着多条线路、每条线路里塞着多集。
     *
     * 切分顺序固定 `groupSplit → itemSplit → pairSplit`（由粗到细）。
     * 顺序不能颠倒：`pairSplit` 通常是 `$`，先按它切会把 `$$$` 切成三段空串，线路结构直接崩掉。
     */
    private fun delimitedGroups(
        track: CompiledTrack,
        root: JsonElement,
        kind: MediaKind,
    ): List<TrackGroup> {
        val spec = track.spec
        val itemsSpec = spec.items

        val raw = track.items?.eval(root)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.firstOrNull { it.isNotBlank() }
            ?: return emptyList()

        val chunks = raw.split(itemsSpec.groupSplit).filter { it.isNotBlank() }
        val names = track.groupNames?.eval(root)
            ?.flatMap { el ->
                if (el is JsonArray) {
                    el.mapNotNull { (it as? JsonPrimitive)?.content }
                } else {
                    (el as? JsonPrimitive)?.content?.split(spec.groupNames.split).orEmpty()
                }
            }
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        return chunks.mapIndexed { gi, chunk ->
            val tracks = chunk.split(itemsSpec.itemSplit)
                .filter { it.isNotBlank() }
                .mapIndexedNotNull { i, item -> parseTrack(item, i + 1, itemsSpec.pairSplit, spec.label, kind) }
            TrackGroup(
                name = names.getOrNull(gi) ?: defaultGroupName(kind, gi + 1, chunks.size),
                kind = kind,
                tracks = tracks,
            )
        }.filter { it.tracks.isNotEmpty() }
    }

    /**
     * 一小段 `标题$地址` 或 `地址`。
     *
     * 只按 `pairSplit` **切第一次**：地址里可能还有 `$`（`/path?a=$b`），
     * 用 split 全切会把地址截断成前半段，播放器拿到一个不存在的 URL。
     */
    private fun parseTrack(
        raw: String,
        index: Int,
        pairSplit: String?,
        label: LabelSpec,
        kind: MediaKind,
    ): Track? {
        val sep = pairSplit?.takeIf { it.isNotEmpty() }
        var title: String? = null
        var url = raw.trim()

        if (sep != null) {
            val at = raw.indexOf(sep)
            if (at in 1 until raw.length) {
                title = raw.substring(0, at).trim().takeIf { it.isNotBlank() }
                url = raw.substring(at + sep.length).trim()
            }
        }
        if (url.isBlank()) return null

        return Track(
            index = index,
            title = title ?: autoLabel(label, index, kind),
            url = url,
        )
    }

    /** 没标题时的兜底文案：视频"第N集"、音频"第N首"、图片"第N张" */
    private fun autoLabel(label: LabelSpec, index: Int, kind: MediaKind): String {
        if (!label.autoIndex) return index.toString()
        val suffix = label.suffix.ifBlank {
            when (kind) {
                MediaKind.AUDIO -> "首"
                MediaKind.IMAGE -> "张"
                else -> "集"
            }
        }
        return "${label.prefix}$index$suffix"
    }

    /** 分组名：没配 `groupNames` 时按媒体类型给一个人话名字 */
    private fun defaultGroupName(kind: MediaKind, index: Int = 1, total: Int = 1): String = when (kind) {
        MediaKind.AUDIO -> if (total == 1) "播放列表" else "播放列表$index"
        MediaKind.IMAGE -> if (total == 1) "图集" else "图集$index"
        else -> if (total == 1) "线路" else "线路$index"
    }

    /**
     * 真正的取数：id 表 → 逐条 `getinfo`。
     * 失败只丢这一组，不影响详情主体；但"用户离开详情页导致的协程取消"要原样抛出，
     * 它不是错误，吞掉会打出假的失败日志。
     */
    private suspend fun fetchEpisodeGroups(
        res: CompiledResource,
        detailPayload: Payload,
        vars: Map<String, String>,
        kind: MediaKind,
        detailBody: String,
    ): List<TrackGroup> = try {
        buildEpisodeGroups(res, detailPayload, vars, kind, detailBody)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("[ep] group failed: ${e::class.simpleName} ${e.message}")
        emptyList()
    }

    private suspend fun buildEpisodeGroups(
        res: CompiledResource,
        detailPayload: Payload,
        vars: Map<String, String>,
        kind: MediaKind,
        detailBody: String,
    ): List<TrackGroup> {
        val ep = res.episodes ?: return emptyList()
        val item = ep.item ?: return emptyList()
        if (ep.idsRegex == null && ep.ids == null) return emptyList()

        // ① id 表。两条通路：
        //    - `ids`（JSONPath）：详情响应 / 单独请求里的数组（腾讯 video_ids）
        //    - `idsSplit`（正则）：整站 HTML 没有 JSON 文档，直接按链接正则在文本上捞（e-hentai 图集）
        val idsRegexLocal = ep.idsRegex
        val idRequestLocal = ep.request
        // 正则通路顺手留下第 1 页原文：下面要再读一次总数，用来决定 maxItems 是否该放宽
        var idPageTexts: List<String>? = null
        val idsRaw: List<String> = if (idsRegexLocal != null) {
            val texts: List<String> = if (idRequestLocal != null) fetchIdPages(ep, vars) else listOf(detailBody)
            idPageTexts = texts
            texts.flatMap { text ->
                idsRegexLocal.findAll(text)
                    .mapNotNull { m -> m.groupValues.getOrNull(ep.spec.idsGroup)?.takeIf { it.isNotBlank() } }
                    .toList()
            }
        } else {
            val idsPath = ep.ids!!
            val base: JsonElement = if (idRequestLocal != null) {
                val fetched = fetchWith(idRequestLocal, ep.strip, vars)
                parseJson(fetched)?.let { doc -> ep.root?.eval(doc)?.firstOrNull() ?: doc } ?: return emptyList()
            } else {
                (detailPayload as? Payload.Json)?.root ?: return emptyList()
            }
            // 路径可能指到数组本身（`$.coverInfo.video_ids` 只回一个 JsonArray），
            // 也可能指到数组元素（`...video_ids[*]`）；两种写法都要能用，所以这里展平一次
            idsPath.eval(base)
                .flatMap { el -> if (el is JsonArray) el.toList() else listOf(el) }
                .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
        }

        // `maxItems` 只是"防配错把成千上万条全灌进内存"的保险丝，**不是分页上限**。
        // 以前它会把抓全的 id 表再截一刀：总数 455、maxItems 60（老配置留下的值）→ 又回到"标题 455、格子 60"。
        // 既然页面上已经自报了总数，就把保险丝放宽到总数（2026-11-18）。
        val knownTotal = idPageTexts?.let { texts ->
            ep.spec.paging?.let { paging -> extractTotal(paging, texts.firstOrNull().orEmpty()) }
        } ?: 0
        val cap = maxOf(ep.spec.maxItems, knownTotal)
        val ids = idsRaw.distinct().take(cap)
        println("[ep] ids raw=${idsRaw.size} unique=${ids.size} cap=$cap")
        if (ids.isEmpty()) return emptyList()

        // 有些接口的"集 id 表"和"集标题表"在同一份详情里（Bilibili PGC），
        // 但每集的 playurl 子响应不带标题。这里按原下标取标题，避免标题错位。
        fun column(path: PathExpr?): List<String> = path
            ?.let { p -> (detailPayload as? Payload.Json)?.root?.let { p.eval(it) } }
            ?.flatMap { el -> if (el is JsonArray) el.toList() else listOf(el) }
            ?.map { (it as? JsonPrimitive)?.content.orEmpty() }
            .orEmpty()

        val titleColumn = column(ep.titles)
        val titleFallback = column(ep.titlesFallback)
        val titleHints = ids.indices.map { i ->
            titleColumn.getOrNull(i)?.takeIf { it.isNotBlank() }
                ?: titleFallback.getOrNull(i).orEmpty()
        }

        // ①' 图片懒取（2026-11-17）：图集站点一张图一个内页，一次性把 N 张全请求一遍
        //    既费流量又容易触发站点限流。这里只交"占位 track"（标题 + pending id），
        //    直链等用户翻到那一张时再由 resolvePendingTrack 取。
        if (kind == MediaKind.IMAGE && ep.spec.lazyItems) {
            val lazyTracks = ids.mapIndexed { i, id ->
                Track(index = i + 1, title = "第 ${i + 1} 张", url = "", pending = id)
            }
            return listOf(TrackGroup(name = ep.spec.groupName, kind = MediaKind.IMAGE, tracks = lazyTracks))
        }

        // ② 逐条：并发分批，任何一条失败都跳过而不是整组失败
        val tracks = ids.withIndex()
            .chunked(ep.spec.concurrency.coerceIn(1, 16))
            .flatMap { chunk ->
                coroutineScope {
                    chunk.map { (i, id) ->
                        async { fetchTrack(item, ep, i + 1, id, vars, titleHints.getOrNull(i)) }
                    }.awaitAll()
                }
            }
            .filterNotNull()
        if (tracks.isEmpty()) return emptyList()

        // 图片（图集）不需要按"预告/花絮"拆组，也不按标题里的 `_07` 排序：
        // 顺序就是 id 表里的顺序（第 1 张…第 N 张），拆组会把读者打散
        if (kind == MediaKind.IMAGE) {
            return listOf(TrackGroup(name = ep.spec.groupName, kind = MediaKind.IMAGE, tracks = tracks))
        }

        // ③ 拆组：正片（免费/VIP）一组并按集号排，预告与花絮各自成组
        val episodes = tracks.filter { it.state == "free" || it.state == "vip" }
            .sortedBy { episodeOrder(it) }
        val trailers = tracks.filter { it.state == "trailer" }.sortedBy { episodeOrder(it) }
        val extras = tracks.filter { it.state == "extra" }

        return listOfNotNull(
            episodes.takeIf { it.isNotEmpty() }?.let {
                TrackGroup(name = ep.spec.groupName, kind = MediaKind.VIDEO, tracks = it)
            },
            trailers.takeIf { it.isNotEmpty() }?.let {
                TrackGroup(name = "预告", kind = MediaKind.VIDEO, tracks = it)
            },
            extras.takeIf { it.isNotEmpty() }?.let {
                TrackGroup(name = "花絮", kind = MediaKind.VIDEO, tracks = it)
            },
        )
    }

    /**
     * id 表分页抓取（`episodes.paging`）：一页 id 表只列 20~40 个缩略图，图集想要 N 页就得翻 N/20 次。
     *
     * 两条路：
     * - **知道总数**（`paging.totalPattern` 抓到了 "of 455"）：页数直接算出来，剩下的页按 `concurrency`
     *   并发抓。455 张的图集 = 23 页，顺序翻实测 20 秒以上，并发后 4 批 ≈ 3 秒（2026-11-18）。
     * - **不知道总数**：退回原来的逐页 `emptyStop`——某页一个 id 都没捞到就当到底。
     *
     * 其余细节不变：`{{idspage}}` 永远可用（当前 id 表页的逻辑页码），`paging.param` 那个名字也会被注入；
     * 单页失败不抛，把已拿到的 id 交出去（图集少几页比整组消失好）。
     */
    private suspend fun fetchIdPages(ep: CompiledEpisodes, vars: Map<String, String>): List<String> {
        val request = ep.request ?: return emptyList()
        val paging = ep.spec.paging ?: return listOf(fetchWith(request, ep.strip, vars))

        // 第 1 页单独抓：它同时承担"抓 id"和"读总数"两个职责
        val first = runCatching { fetchWith(request, ep.strip, vars + idPageVars(paging, 1)) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val firstHits = ep.idsRegex?.findAll(first)?.count() ?: 0
        if (paging.emptyStop && firstHits == 0) return emptyList()

        val texts = mutableListOf(first)
        val total = extractTotal(paging, first)
        val perPage = paging.perPage.takeIf { it > 0 } ?: firstHits
        if (total != null && perPage > 0 && total > perPage) {
            val pages = ((total + perPage - 1) / perPage).coerceIn(2, paging.maxPage)
            println("[ids] total=$total perPage=$perPage pages=$pages concurrency=${ep.spec.concurrency}")
            val rest = (2..pages).toList()
            rest.chunked(ep.spec.concurrency.coerceIn(1, 16)).forEach { chunk ->
                val got = coroutineScope {
                    chunk.map { p ->
                        async {
                            runCatching { fetchWith(request, ep.strip, vars + idPageVars(paging, p)) }.getOrNull()
                        }
                    }.awaitAll()
                }
                // 空页 / 被抓坏了的页直接丢掉，不让它把其余页的 id 顶掉
                texts += got.filterNotNull().filter { t ->
                    t.isNotBlank() && (ep.idsRegex?.findAll(t)?.count() ?: 0) > 0
                }
            }
            return texts
        }

        // 兜底：逐页翻，某页一个 id 都没捞到就当到底
        var page = 2
        while (page <= paging.maxPage) {
            val text = runCatching { fetchWith(request, ep.strip, vars + idPageVars(paging, page)) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: break
            val hits = ep.idsRegex?.findAll(text)?.count() ?: 0
            if (hits == 0) break
            texts += text
            page++
        }
        println("[ids] total=unknown pages=${texts.size} (emptyStop 兜底)")
        return texts
    }

    /** 从列表页正文里读"总条数"（`of 455` / `共 455 条` 都由配置的正则决定） */
    private fun extractTotal(paging: PagingSpec, body: String): Int? {
        val pattern = paging.totalPattern?.takeIf { it.isNotBlank() } ?: return null
        val m = runCatching { Regex(pattern).find(body) }.getOrNull() ?: return null
        val g = paging.totalGroup.coerceIn(0, m.groupValues.lastIndex)
        return m.groupValues[g].replace(",", "").trim().toIntOrNull()
    }

    private fun idPageVars(paging: PagingSpec, page: Int): Map<String, String> {
        val value = when (paging.mode) {
            PagingMode.PAGE -> paging.startAt + (page - 1) * paging.step
            PagingMode.OFFSET -> (page - 1) * pageSize
            PagingMode.CURSOR -> ""
        }
        val name = paging.param.ifBlank { "page" }
        return mapOf(
            "idspage" to page.toString(),
            name to value.toString(),
            "page" to value.toString(),
        )
    }

    /** 标题里的 `_07` 就是集号；没有集号的（彩蛋）排在后面 */
    private fun episodeOrder(track: Track): Int =
        Regex("""_(\d{2,3})""").find(track.title)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private suspend fun fetchTrack(
        item: CompiledItem,
        ep: CompiledEpisodes,
        index: Int,
        episodeId: String,
        vars: Map<String, String>,
        titleHint: String? = null,
    ): Track? = runCatching {
        val url = templates.render(item.request.urlTemplate, vars + ("episode" to episodeId))
        val headers = schema.config.runtime.headers +
            item.request.headers.mapValues { (_, v) -> templates.render(v, vars + ("episode" to episodeId)) }
        val (signedUrl, signedHeaders) = signIfNeeded(item.request, url, headers)
        val fetched = fetcher.fetch(
            signedUrl, item.request.method, signedHeaders,
            item.request.body?.let { templates.render(it, vars + ("episode" to episodeId)) },
            item.request.bodyType,
        )
        val body = decryptBody(stripBody(fetched.body, item.strip), item.decrypt)
        // 子项响应可能是 **HTML**（e-hentai 的 `/s/<hash>/<gid>-n` 页面就是），所以 JSON 解析失败
        // 不等于"这条坏了"：退回文本通路，让 `regex` 抽取器在整页上干活。
        // 这是"图集内页"唯一可行的取法——页面里没有 JSON，图地址写在 `<img id="img" src=…>`。
        val doc = parseJson(body)
        val jsonBase = doc?.let { d -> item.root?.eval(d)?.firstOrNull() ?: d }
        val payload: Payload = jsonBase?.let { Payload.Json(it) } ?: Payload.Text(body)
        if (doc == null && body.isBlank()) {
            println("[ep] $episodeId empty body")
            return null
        }

        // 走完整变换链（trim → rewrite → prefix/suffix）：
        // 腾讯的 CDN 地址是 `http://<IP>/vlive.qqvideo...`，靠 rewrite 把 IP 段去掉，
        // 只取 extractor 的原始值会把 IP 留着，Media3 直接报 P2007
        fun pick(field: CompiledField?): String? {
            if (field == null) return null
            val raws = field.extractor.extract(payload)
            val transformed = raws.mapNotNull { TransformChain.apply(it, field.spec, field.crypto) }
            return transformed.firstOrNull()?.takeIf { it.isNotBlank() }
        }

        // 子项接口不一定回标题（Bilibili PGC playurl 只回直链）。详情里的平行标题表是
        // 作者的显式声明，优先级高于子项里的标题。两者都没有时退回集号，
        // 让"胶囊标题"至少是「第 1 集」而不是把整条丢掉（红果 fallback-play-info 即此情形）。
        val title = titleHint?.takeIf { it.isNotBlank() }
            ?: pick(item.title)?.takeIf { it.isNotBlank() }
            ?: "第 $index 集"
        val playUrl = pick(item.playUrl).orEmpty()
        val durationSec = pick(item.duration)?.toDoubleOrNull()
        // 只有 JSON 通路才有"取不到某路径"这个概念；文本通路判不了锁，按可看处理
        val lockedPath = item.locked
        val locked = jsonBase != null && lockedPath != null &&
            lockedPath.evalFirstString(jsonBase).isNullOrBlank()

        // 标签优先级：标题里的类别词（预告/花絮）> 不可播（VIP）> 免费
        val badge = ep.spec.badges.firstOrNull { rule ->
            rule.titleContains?.let { title.contains(it, ignoreCase = true) } == true
        }
        val state = when {
            badge != null -> badge.state
            locked -> "vip"
            else -> "free"
        }
        val displayTitle = when {
            badge != null -> title
            locked -> "$title · ${ep.spec.lockedLabel}"
            else -> title
        }

        Track(
            index = index,
            title = displayTitle,
            url = playUrl,
            durationMs = durationSec?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
            state = state,
        )
    }.getOrNull()

    private suspend fun fetchWith(
        request: CompiledRequest,
        strip: StripSpec?,
        vars: Map<String, String>,
    ): String {
        val url = templates.render(request.urlTemplate, vars)
        val headers = schema.config.runtime.headers +
            request.headers.mapValues { (_, v) -> templates.render(v, vars) }
        val body = request.body?.let { templates.render(it, vars) }
        val (finalUrl, finalBody) = RequestCrypto.transform(url, body, request.encrypt)
        val (signedUrl, signedHeaders) = signIfNeeded(request, finalUrl, headers)
        val fetched = fetcher.fetch(signedUrl, request.method, signedHeaders, finalBody, request.bodyType)
        return stripBody(fetched.body, strip)
    }

    private fun parseJson(body: String): JsonElement? =
        runCatching { json.parseToJsonElement(body) }.getOrNull()

    /**
     * 搜索资源与列表资源同一套管线，只是多一个 keyword 变量。
     *
     * `{{keyword}}` 存的是**URL 编码后**的值：关键词是用户随手输入的，带空格、中文、`&`、`#`
     * 都会把 URL 拼坏（`&` 甚至会把后面的参数切断）。模板渲染只是字符串替换，不做编码，
     * 所以编码在这里做一次。真需要原样值（放进 body / JSON 字段）的场景用 `{{keywordRaw}}`。
     */
    suspend fun search(
        key: String,
        keyword: String,
        page: Int = 1,
        extra: Map<String, String> = emptyMap(),
    ): PagedResult<MediaSummary> = list(
        key,
        page,
        extra + mapOf(
            "keyword" to encodeQueryValue(keyword),
            "keywordRaw" to keyword,
        ),
    )

    private fun encodeQueryValue(value: String): String = value.percentEncode()

    // ── 内部阶段 ────────────────────────────────────────────────

    /**
     * 把**逻辑页码**（UI 的"第几页"）翻译成站点真正认的那个参数名与值。
     *
     * 这是 `paging.param / startAt / step / mode` 的唯一落点。此前这里只注入了 `page`，
     * 于是配置里写 `"param": "offset"` / `"startAt": 0` 全部被静默忽略 ——
     * 表现就是"栏目配了分页却永远只请求第一页"。三种模式的口径：
     *
     * | mode | 参数名 | 值 |
     * |---|---|---|
     * | `page` | `param`（默认 `page`） | `startAt + (page-1)*step` |
     * | `offset` | `param`（默认 `offset`） | `(page-1)*pageSize`（按条数偏移） |
     * | `cursor` | 调用方经 `extra["cursor"]` 传入 | 原样透传（**不自动串联**，见 docs/03 §9.6） |
     *
     * 另外：无论哪种模式，`{{page}}` 始终可用 —— 若站点参数名就是 `page`，它拿到的是翻译后的值
     * （`startAt=0` 的站点第一页就该是 0）；若参数名是别的，`{{page}}` 仍是逻辑页码。
     */
    private fun pagingVars(
        paging: PagingSpec?,
        page: Int,
        extra: Map<String, String>,
    ): Map<String, String> {
        val out = mutableMapOf("page" to page.toString())
        val spec = paging ?: return out
        val value = when (spec.mode) {
            PagingMode.PAGE -> spec.startAt + (page - 1) * spec.step
            PagingMode.OFFSET -> (page - 1) * pageSize
            PagingMode.CURSOR -> extra["cursor"].orEmpty()
        }
        val name = spec.param.ifBlank { if (spec.mode == PagingMode.OFFSET) "offset" else "page" }
        out[name] = value.toString()
        // offset 模式但没指定 param：再补一个 offset 变量，免得配置里无从引用
        if (spec.mode == PagingMode.OFFSET && name == "page") {
            out["offset"] = value.toString()
        }
        return out
    }

    private fun variables(extra: Map<String, String>): Map<String, String> {
        val runtime = schema.config.runtime
        val base = buildMap {
            put("baseUrl", runtime.baseUrl.trimEnd('/'))
            runtime.imageBase?.let { put("imageBase", it.trimEnd('/')) }
            put("pageSize", pageSize.toString())
        }
        // 页面参数同时以 `p.xxx` 暴露：配置里写 `{{p.channelId}}` 或 `{{channelId}}` 都成立
        return base + extra + extra.mapKeys { (k, _) -> "p.$k" }
    }

    private suspend fun execute(
        res: CompiledResource,
        vars: Map<String, String>,
        urlOverride: String? = null,
    ): FetchResult {
        val url = urlOverride ?: templates.render(res.request.urlTemplate, vars)
        val headers = schema.config.runtime.headers +
            res.request.headers.mapValues { (_, v) -> templates.render(v, vars) }
        val body = res.request.body?.let { templates.render(it, vars) }
        // 请求参数加密（docs/03 §3.3）：URL query 与 JSON body 两处都在发出去之前替换
        val (finalUrl, finalBody) = RequestCrypto.transform(url, body, res.request.encrypt)
        val (signedUrl, signedHeaders) = signIfNeeded(res.request, finalUrl, headers)
        return fetcher.fetch(signedUrl, res.request.method, signedHeaders, finalBody, res.request.bodyType)
    }

    /**
     * 站点签名（目前只有抖音）：请求发出前的最后一步，在 query 上补签名参数、在 Cookie 上补设备令牌。
     *
     * 放在 `RequestCrypto.transform` 之后：签名算的是**真正发出去的那串参数**，
     * 先签再加密的话，服务端校验的和实际请求的对不上。
     */
    private suspend fun signIfNeeded(
        request: CompiledRequest,
        url: String,
        headers: Map<String, String>,
    ): Pair<String, Map<String, String>> =
        if (request.sign == "douyin") DouyinSigner.sign(url, headers, fetcher) else url to headers

    /**
     * 响应解密（docs/03 §3.3）。在 `strip` 之后、解析之前做一次：
     *
     * - `from == null` → 整个响应体就是密文，解出来直接替换；
     * - `from != null` → 取该路径的值解密，**解出来的明文当作整个新响应体**——
     *   真实站点的密文多裹着完整业务数据（`{"code":0,"data":"<cipher>"}`），
     *   解出来就是一坨 JSON，直接交给后面的 list/fields 最自然。
     *
     * 解不出来（key 轮换、算法不对）就原样返回，让下游按"没有数据"降级，
     * 而不是抛异常把整个页面打成错误页。
     */
    private fun decryptBody(body: String, decrypt: CompiledDecrypt?): String {
        if (decrypt == null) return body
        val inner = decrypt.from?.let { path ->
            parseJson(body)?.let { path.evalFirstString(it) }
        } ?: body
        return CryptoCodec.decrypt(inner, decrypt.crypto) ?: body
    }

    /** 字段级解密规则：配置里的字段名 → 已烘焙的运行期参数（没声明的字段不进表） */
    private fun cryptoByField(res: CompiledResource): Map<String, CryptoOptions> =
        res.fields.entries.mapNotNull { (domain, compiled) ->
            compiled.crypto?.let { domain.key to it }
        }.toMap()

    /**
     * 剥掉响应体的外壳，取 `prefix … suffix` 之间的那一段。
     *
     * 两种线上形态都要吃：
     *  - 整段就是一个 `VAR={…};`（外壳贴着首尾）；
     *  - 页面里内嵌一坨 JSON（`var ytInitialData = {…};</script>`），前后还跟着几 MB 的 HTML。
     *
     * 所以 prefix 用「首次出现」定位而不是 `removePrefix`。suffix 则不能盲取首次出现——
     * `VAR={…};` 这种外壳里，JSON 字符串内部常常也带分号（实测某个详情接口第一个 `;`
     * 出现在第 106 个字符）。判据是「切到这里之后能不能解析成 JSON」：先试首个命中，
     * 再试末个命中，都解析不了才退回首个命中（密文/纯文本响应本来就不是 JSON）。
     */
    private fun stripBody(body: String, strip: StripSpec?): String {
        if (strip == null) return body
        var s = body.trim()
        strip.prefix?.takeIf { it.isNotEmpty() }?.let { p ->
            val i = s.indexOf(p)
            if (i >= 0) s = s.substring(i + p.length).trim()
        }
        strip.suffix?.takeIf { it.isNotEmpty() }?.let { suf ->
            val first = s.indexOf(suf)
            val last = s.lastIndexOf(suf)
            val candidates = if (last > first) listOf(first, last) else listOf(first)
            var chosen = first
            for (c in candidates) {
                if (c < 0) continue
                val cut = s.substring(0, c).trim()
                if (runCatching { json.parseToJsonElement(cut) }.isSuccess) {
                    chosen = c
                    break
                }
            }
            if (chosen >= 0) s = s.substring(0, chosen).trim()
        }
        return s
    }

    /** 一次响应的解析结果：条目列表 + 原文档与 root 后的锚点（只有 JSON 通路才有） */
    private data class ParsedBody(
        val payloads: List<Payload>,
        val base: JsonElement? = null,
        val doc: JsonElement? = null,
    )

    private fun parseList(res: CompiledResource, body: String): ParsedBody {
        res.listSplit?.let { split ->
            return ParsedBody(split.findAll(body).map { Payload.Text(it.value) }.toList())
        }

        val doc = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return ParsedBody(emptyList())
        val base = res.root?.eval(doc)?.firstOrNull() ?: doc
        val listExpr = res.list ?: return ParsedBody(emptyList(), base, doc)
        return ParsedBody(listExpr.eval(base).map { Payload.Json(it) }, base, doc)
    }

    /** 详情：文本分条资源取第一条命中；JSON 解析失败时整页当文本（HTML 详情页）；否则按 root 定位到单对象 */
    private fun detailPayload(res: CompiledResource, body: String): Payload? {
        res.listSplit?.let { split ->
            val hit = split.findAll(body).firstOrNull()?.value ?: body
            return Payload.Text(hit)
        }
        val doc = runCatching { json.parseToJsonElement(body) }.getOrNull()
        // 整站 HTML 的源（e-hentai）详情页没有 JSON 文档：不能返回 null，否则
        // "详情页能打开但什么都没有"。整页交给 regex 抽取器即可（`css` 抽取器另需 HtmlSelector）。
        if (doc == null) return Payload.Text(body).takeIf { body.isNotBlank() }
        val base = res.root?.eval(doc)?.firstOrNull() ?: doc
        return Payload.Json(base)
    }

    /**
     * 字段的 prefix/suffix 里也可能写 `{{imageBase}}`，
     * 所以交给 mapper 之前先把它们渲染成真实值。
     */
    private fun renderedSpecs(res: CompiledResource, vars: Map<String, String>): Map<String, FieldSpec> =
        res.fields.entries.associate { (field, compiled) ->
            val spec = compiled.spec
            field.key to spec.copy(
                prefix = templates.render(spec.prefix, vars),
                suffix = templates.render(spec.suffix, vars),
            )
        }

    private fun resolveHasMore(
        paging: PagingSpec?,
        page: Int,
        parsed: ParsedBody,
        items: List<MediaSummary>,
        nextCursor: String? = null,
    ): Boolean {
        // 游标模式：判断依据只有"这一页有没有给出下一页的游标"。站点给的游标抽不到 = 真的到底了，
        // 这时候再靠 items 非空兜底会导致无限翻页（每页都有内容，但每页都重复第一页）
        if (paging?.mode == PagingMode.CURSOR) {
            if (nextCursor.isNullOrBlank()) return false
            if (page >= paging.maxPage) return false
            return true
        }
        // 文本通路没有 JSON 文档，`hasMore` 路径无法求值，只能靠 maxPage + 非空兜底
        if (parsed.doc != null) {
            paging?.hasMore?.let { expr ->
                val path = runCatching { JsonPath.compile(expr) }.getOrNull()
                val raw = path?.evalFirstString(parsed.base ?: parsed.doc)
                    ?: path?.evalFirstString(parsed.doc)
                if (raw != null) {
                    return raw == "1" || raw.equals("true", ignoreCase = true)
                }
            }
        }
        if (paging != null && page >= paging.maxPage) return false
        return items.isNotEmpty()
    }
}
