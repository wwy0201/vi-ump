package com.vi.ump.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 配置模型 = 配置文件的强类型镜像（L2 层）。
 *
 * 分工：
 *  - L1 Raw   → JsonElement（原始文本解析结果）
 *  - L2 Document → 本文件这些 data class（结构合法性由 kotlinx.serialization 保证）
 *  - L3 Compiled → core:config 产出的 CompiledSchema（路径已 token 化）
 *
 * 这里只做"结构与默认值"，不做语义校验（引用/路径语法在 L3 做）。
 */
@Serializable
data class UmpConfig(
    val schemaVersion: String = "1.0",
    val meta: Meta,
    val runtime: RuntimeSpec = RuntimeSpec(),
    val resources: Resources = Resources(),
    val pages: List<PageSpec> = emptyList(),
    val playback: PlaybackSpec = PlaybackSpec(),
    val theme: ThemeSpec = ThemeSpec(),
    val flags: Flags = Flags(),
)

// ── meta / runtime ────────────────────────────────────────────

@Serializable
data class Meta(
    val id: String,
    val name: String,
    val author: String? = null,
    val version: String? = null,
    val description: String? = null,
    val icon: String? = null,
    /**
     * 源标识色（`#RRGGBB`）：首页顶栏当前源名、下拉项、设置页每一行都用它。
     *
     * 不写时的优先级：`theme.primary`（若作者显式配过）→ 客户端按源 id 自动分配一个
     * **与其它源差异明显**的颜色（见 `feature.main` 的 `SourcePalette`）。
     * 两个源显式写成同一个颜色时就用同一个颜色——去重只作用于自动分配出来的色。
     */
    val color: String? = null,
)

@Serializable
data class RuntimeSpec(
    val baseUrl: String = "",
    val imageBase: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** 响应解码字符集，GBK 站点填 "gbk" */
    val encoding: String = "utf-8",
    val timeoutMs: Long = 15_000L,
    val retry: Int = 2,
    /**
     * 走直连的域名后缀（如 `["qq.com", "gtimg.com"]`）。
     *
     * 桌面端把系统代理当默认通路，但国内站点经常拒绝"经 HTTP 代理隧道"进来的 TLS 握手
     * （腾讯 CDN 表现为 `Remote host terminated the handshake`）。这些域名因此排除在代理之外。
     * 值为域名后缀，匹配 `host == suffix` 或 `host.endsWith(".$suffix")`。
     */
    val proxyBypass: List<String> = emptyList(),
    /**
     * 全局加解密默认值（docs/03 §3.3）。
     *
     * 三个挂点——请求参数加密（`request.encrypt`）、响应解密（`resources.*.decrypt`）、
     * 单字段解密（`fields.*.decrypt`）——都可以只写"缺什么"：
     * 算法、key、iv 在这里写一次，各挂点按需覆盖。不写这一块 = 这个源没有加解密。
     */
    val crypto: CryptoSpec? = null,
    /**
     * 密文图片的解码规则（docs/03 §3.2）。为 `null` 表示这个源没有密文图，图片通路保持原样。
     *
     * 有一类站点把封面/海报以 AES 密文存放，页面里用固定协议 worker 解密再塞进 `<img>`；
     * 客户端拿到的字节既不是 PNG 也不是 JPEG，解码器**静默失败**，表现为"标题能出、图全是灰块"。
     * 这里就是那条"解码规则"——不是解接口，只解图片字节。
     */
    val imageDecrypt: ImageDecryptSpec? = null,
)

/**
 * 全局加解密默认值（docs/03 §3.3）。字段都有默认，`runtime.crypto` 写上去就是"这个源的密钥簿"。
 *
 * 支持的算法（白名单，写别的报 `E003`）：AES 的 CBC/ECB/CTR/GCM 三种密钥长度、DES、3DES。
 * 不支持 RSA / SM4 / 每请求动态密钥——那类要么需要额外依赖，要么配置表达不了。
 */
@Serializable
data class CryptoSpec(
    /** 见 [CryptoSpec] 的算法白名单；`aes-128-cbc` / `aes-256-cbc` / `des3-cbc` 最常见 */
    val algo: String = "aes-128-cbc",
    /** 密钥。按 [keyFormat] 解释 */
    val key: String = "",
    /** 初始向量。ECB 不用填（填了忽略）；CBC/CTR 必填 块长，GCM 填 12 字节 */
    val iv: String = "",
    /** `pkcs7`（标准补块）| `none`（站点自己补块 / 流式算法）| `zero`（老 PHP 的 0 补块） */
    val padding: String = "pkcs7",
    /** 密文/明文的**外部**编码：`base64` | `base64url` | `hex` | `raw`（不解码，字节直接进算法） */
    val output: String = "base64",
    /** key 的写法：`text`（UTF-8 原样，黄果是这种）| `hex` | `base64` */
    val keyFormat: String = "text",
    /** iv 的写法，同上 */
    val ivFormat: String = "text",
)

/**
 * 解密一段数据（docs/03 §3.3）。字段全部可空，缺的从 `runtime.crypto` 继承——
 * 这样一个源只要在 `runtime.crypto` 写一次 key/iv，各挂点写 `{"from": "$.data"}` 就够了。
 *
 * [from]：
 * - `null` → 整段响应体就是密文（`responseType=text` 或解出来是 JSON 都行）；
 * - 非空 → 响应里这个路径的值是密文，**解出来后当成整个新响应体**继续走后面的 list/fields 流程。
 *
 * 为什么是"替换整个响应体"而不是"只替换这个字段"：真实站点里密文通常裹着**整段业务数据**
 * （`{"code":0,"data":"<cipher>"}`，data 解出来就是一坨完整 JSON）。若解出来不是 JSON，
 * 则把它写回该路径、保持文档结构（HTML/纯文本站点走这条）。
 */
@Serializable
data class DecryptSpec(
    val from: String? = null,
    val algo: String? = null,
    val key: String? = null,
    val iv: String? = null,
    val padding: String? = null,
    val output: String? = null,
    val keyFormat: String? = null,
    val ivFormat: String? = null,
)

/**
 * 加密请求参数（docs/03 §3.3）。
 *
 * [mode]：
 * - `params`：只加密 [params] 里列出的参数值。JSON body 走 JSON 对象替换，
 *   URL 走 query 替换（值会被 URL 编码）。
 * - `whole`：整个 body 换成密文（`bodyType` 建议 `text`）。
 *
 * 只做"加密"，**不做签名**（HMAC/MD5 那些是"算摘要"不是"加密"，语义不同，见 docs/03 §3.3 能力边界）。
 */
@Serializable
data class EncryptSpec(
    val mode: String = "params",
    val params: List<String> = emptyList(),
    val algo: String? = null,
    val key: String? = null,
    val iv: String? = null,
    val padding: String? = null,
    val output: String? = null,
    val keyFormat: String? = null,
    val ivFormat: String? = null,
)

/**
 * 密文图片的解码参数（docs/03 §3.2）。**只覆盖"固定 key/iv + CBC"这一档**。
 *
 * 不做的事（能力边界，写清楚免得配置作者误解）：
 * - 每图一 key、key 由接口动态下发、RSA —— 配置表达不了，按 `W003` 可解释降级；
 * - key/iv 会轮换（站点把它写在带版本号的静态文件里），轮换后旧配置只会让图变灰块，不会崩。
 */
@Serializable
data class ImageDecryptSpec(
    /** AES 家族任意一档（`aes-128-cbc` / `aes-256-cbc` / `aes-128-ecb` …），写别的报 `E003` */
    val algo: String = "aes-128-cbc",
    /** 密钥，按 [keyFormat] 解释；默认按 **UTF-8 取字节**（不是 hex 解码） */
    val key: String = "",
    /** 同上 */
    val iv: String = "",
    /** `pkcs7`（标准）| `none`（不解填充，按站点习惯自己截断）| `zero` */
    val padding: String = "pkcs7",
    /** 图片通路输入输出都是字节，默认 `raw` 不解码 */
    val output: String = "raw",
    val keyFormat: String = "text",
    val ivFormat: String = "text",
)

// ── resources ────────────────────────────────────────────────

@Serializable
data class Resources(
    val categories: ResourceSpec? = null,
    val feeds: Map<String, ResourceSpec> = emptyMap(),
    val details: Map<String, ResourceSpec> = emptyMap(),
    val tracks: Map<String, TrackSpec> = emptyMap(),
    val filters: FilterResource? = null,
    val search: Map<String, ResourceSpec> = emptyMap(),
)

@Serializable
data class ResourceSpec(
    val request: RequestSpec = RequestSpec(),
    val responseType: ResponseType = ResponseType.JSON,
    /** 抽取起点，先定位一次，后续 list/fields 都相对它求值 */
    val root: String? = null,
    /** 列表资源必填；单对象资源（详情）不填 */
    val list: String? = null,
    val fields: Map<String, FieldSpec> = emptyMap(),
    val paging: PagingSpec? = null,
    /**
     * 非 JSON 响应（HTML / XML / 纯文本）的条目切分正则。
     *
     * 命中顺序即条目顺序，每一段命中文本作为独立的 `Payload.Text` 交给字段抽取器——
     * 于是 `regex` / `css` / `const` 都能在"单条"范围内工作。
     * 声明了它的资源忽略 [list]（那条路径是 JSON 才有的概念）。
     */
    val listSplit: String? = null,
    /** 响应体前后缀剥离，应对 `QZOutputJson={...};` 这类被包裹的非标准 JSON */
    val strip: StripSpec? = null,
    /**
     * 响应解密（docs/03 §3.3）。在 `strip` 之后、`root`/`list`/`fields` 之前执行一次。
     *
     * 不填 `from` → 整个响应体是密文；填了 → 取该路径的值解密，解出来当成新的响应体。
     */
    val decrypt: DecryptSpec? = null,
    /**
     * 选集（多集/多线路/多图）。声明后详情页会额外取一批子项，
     * 组合成 [TrackGroup] 渲染成"选集"。
     *
     * 之所以是独立一段请求：腾讯这类站点的"剧集 id 表"和"每集详情"在两个接口里，
     * 前者还是 POST + cookie 的 `vinfo_proxy`，靠详情那一次求值拿不到。
     */
    val episodes: EpisodesSpec? = null,
)

/**
 * 选集规格。三级：`request`（拿 id 表）→ `ids`（从响应里取数组）→ `item`（每条一次请求）。
 */
@Serializable
data class EpisodesSpec(
    /** 拿 id 表的请求；不填则从详情响应自身取（`ids` 相对详情 root） */
    val request: RequestSpec? = null,
    /**
     * id 表的分页（**只对 `idsSplit` + `request` 生效**）：按 `paging` 逐页请求 id 表、把各页 id 拼起来。
     *
     * 纯 HTML 站点的"图集页"通常一页只列 20~40 页缩略图（e-hentai 的 `#gdt` 就是 20 个 `?p=N` 分页），
     * 想要 900 页就得翻 47 次 id 表。不填 = 只取 id 表请求一次（第 1 页）。
     */
    val paging: PagingSpec? = null,
    val strip: StripSpec? = null,
    /** 详情资源多一层 `$.data` 时，在这里再定位一次 */
    val root: String? = null,
    /** id 数组的 JSONPath，如 `$.coverInfo.video_ids` */
    val ids: String? = null,
    /**
     * 与 [ids] 平行的一列标题路径（可选）。
     *
     * 有些站点把"集 id"和"集标题"都放在详情响应里，而每集的播放地址接口只回直链、
     * 不回标题（Bilibili PGC 的 `episodes[*].id` + `pgc/player/web/playurl` 就是这样）。
     * 声明后按数组下标把标题配给对应 id；子项响应里的 `title` 仍作为兜底。
     */
    val titles: String? = null,
    /** [titles] 对应位置为空时的兜底列（可选），如 Bilibili PGC 的 `episodes[*].title` */
    val titlesFallback: String? = null,
    /**
     * 文本通路的 id 表：正则的**每个命中 = 一个 id**（取 [idsGroup] 捕获组）。
     *
     * 与 [ids] 二选一，`idsSplit` 优先。用于"整站 HTML、没有 JSON 文档"的源（e-hentai 图集）：
     * 页面上每个 `/s/<hash>/<gid>-<n>` 链接就是一页图，正则直接把它们捞成 id 列表。
     * 有它时 `item.request.url` 里的 `{{episode}}` 可以是**复合 id**（`hash/gid-n`），
     * 模板只做字面替换、不编码 `/`，所以 `/s/{{episode}}/` 能原样拼出来。
     */
    val idsSplit: String? = null,
    /** `idsSplit` 的捕获组，默认 1（写 0 会拿到整段匹配） */
    val idsGroup: Int = 1,
    /** 每条子项的请求。url 里用 `{{episode}}` 表示当前 id，`{{id}}` 仍是条目 id */
    val item: ResourceSpec? = null,
    /** 最多取多少条，防一页几百条把首屏拖死 */
    val maxItems: Int = 48,
    /** 并发数（逐条请求，串行会很慢） */
    val concurrency: Int = 6,
    val groupName: String = "选集",
    /** 标题命中关键词时给标签（预告 / 花絮 …），顺序即优先级 */
    val badges: List<BadgeRule> = emptyList(),
    /**
     * 该 JSONPath（相对子项 root）取不到 → 视为不可播（VIP / 需登录）。
     * 腾讯 `getinfo` 的 `$.fvkey` 就是这样：彩蛋/VIP 集不返回值。
     */
    val lockedPath: String? = null,
    /** 不可播时的标签文案 */
    val lockedLabel: String = "VIP",
    /**
     * 选集/图集缓存的**有效期**（毫秒），缺省 6 小时。
     *
     * 播放地址带签名且短命的源必须调小（黄果短剧 `auth_key` 约 2 小时、e-hentai `keystamp` 约 2 天）：
     * 缓存命中一个已过期地址，用户看到的是"播放器坏了"，而不是"缓存旧了"（docs/03 §5.5.4 / §5.5.5）。
     */
    val cacheTtlMs: Long = 21_600_000L,
    /**
     * 图片懒取（2026-11-17）：只取 id 表，**不逐条请求** [item]。
     *
     * 图集站点一张图一个内页（e-hentai 的 `/s/<hash>/<gid>-<n>` 就是），一次性把 60 张的内页
     * 全请求一遍 = 60 次网络 + 60 份 HTML，既费流量，也最容易被站点当爬虫限流。
     * 打开这个开关后，详情只拿"第 1 张…第 N 张"的标题与 id；用户翻到哪一张，
     * 才由 `SourcePipeline.resolvePendingTrack` 去取那一张的直链。
     *
     * 只对图片（`kind == image`）生效：视频/音频点播放必须立刻有地址，
     * 而且视频的 `item` 请求往往一次拿多集信息，拆懒没有收益。
     */
    val lazyItems: Boolean = false,
)

/**
 * 标签规则：只看子项标题即可判定的类别。
 * 例：标题含"预告" → `预告`；含"彩蛋" → `花絮`。
 */
@Serializable
data class BadgeRule(
    val titleContains: String? = null,
    val label: String,
    /** `trailer` / `extra` / `free`，渲染层据此取色 */
    val state: String,
)

/**
 * 响应体剥离规则。两端都命中时才逐段剥，剥完仍 trim 一次。
 * 例：腾讯 `getinfo` 返回 `QZOutputJson={"vl":…};` → prefix `QZOutputJson=`、suffix `;`
 */
@Serializable
data class StripSpec(
    val prefix: String? = null,
    val suffix: String? = null,
)

@Serializable
data class RequestSpec(
    val method: String = "GET",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val bodyType: BodyType = BodyType.JSON,
    /** 请求参数加密（docs/03 §3.3）：只加密列出的参数，或整个 body */
    val encrypt: EncryptSpec? = null,
    /**
     * 请求签名：站点要求 query 上带一个由 JS 算出来的签名参数（抖音的 `a_bogus`）。
     *
     * 配置只写"用哪种签名"，算法在客户端代码里（`core:source` 的 `DouyinSigner`）：
     * 这类签名要 SM3 + 自定义 Base64 + 设备令牌，配置表达不了，也没法在模板里拼。
     * 目前支持：`"douyin"`（自动补 `a_bogus`、`msToken`、`ttwid`）。
     */
    val sign: String? = null,
)

@Serializable
data class PagingSpec(
    val mode: PagingMode = PagingMode.PAGE,
    val param: String = "page",
    val startAt: Int = 1,
    val step: Int = 1,
    /** 布尔表达式路径，如 "$.has_more" */
    val hasMore: String? = null,
    /** cursor 模式下取下一游标的路径（JSON 通路） */
    val cursorFrom: String? = null,
    /**
     * cursor 模式下取下一游标的**正则**（文本通路，2026-11-17 加）。
     *
     * 站点把"下一页"写成 HTML 链接（e-hentai 首页是 `<a href="https://e-hentai.org/?next=4334918">Next</a>`），
     * JSON 路径抽不到，只能用 regex。组号见 [cursorGroup]。
     */
    val cursorPattern: String? = null,
    val cursorGroup: Int = 1,
    /**
     * 第 2 页起用的 URL 模板（含 `{{cursor}}`，2026-11-17 加）。
     *
     * 第 1 页仍用 `request.url`：站点第一页通常没有游标参数，硬拼一个空 `?next=` 会被当成非法请求。
     * e-hentai 首页就是这种："首页 /" + "之后每页 {{cursor}}（一个完整 URL）"。
     */
    val nextUrl: String? = null,
    val maxPage: Int = 20,
    val emptyStop: Boolean = true,
    /**
     * **总条数**的正则（2026-11-18 加，当前只作用于 `episodes.paging` 的 id 表）。
     *
     * 站点会在列表页写一句"Showing 21 - 40 of 455"，把它抓出来就能反推"一共要翻多少页"，
     * 不必等到翻到空页才停 —— 这样剩下的 id 表页可以**并发**抓（455 张的图集实测 20 页顺序翻要 20 秒以上）。
     * 组号见 [totalGroup]。抓不到就走 [emptyStop] 的逐页兜底。
     */
    val totalPattern: String? = null,
    val totalGroup: Int = 1,
    /** 每页条数；0（缺省）= 用第一页实际抓到的条数 */
    val perPage: Int = 0,
)

/**
 * 字段映射。
 *
 * 简写：`"title": "$.vod_name"` → 编译器会转成 `FieldSpec(from = "$.vod_name")`
 * 全写：见 docs/03 §6.2
 */
@Serializable
data class FieldSpec(
    val kind: ExtractorKind = ExtractorKind.JSON_PATH,
    /** string 或 string[]：数组表示候选路径，按序取第一个非空 */
    val from: JsonElement? = null,
    // regex
    val pattern: String? = null,
    val group: Int = 0,
    // css
    val selector: String? = null,
    val attr: String? = null,
    // delimited
    val levels: List<LevelSpec> = emptyList(),
    // template
    val parts: List<String> = emptyList(),
    val join: String = "",
    // transform
    val split: String? = null,
    val map: Map<String, String> = emptyMap(),
    val prefix: String = "",
    val suffix: String = "",
    val type: FieldType = FieldType.STRING,
    val default: String? = null,
    val required: Boolean = false,
    val trim: Boolean = true,
    val rewrite: List<RewriteSpec> = emptyList(),
    /**
     * 单字段解密（docs/03 §3.3）。抽取拿到原始值后**先解密**，再走 trim/rewrite/prefix…
     *
     * 典型：`"playUrl": { "from": "$.url", "decrypt": { } }`——该字段是 base64 密文，
     * 缺省的算法与 key 从 `runtime.crypto` 继承。这里的 `from` 无意义（值已经在手上）。
     */
    val decrypt: DecryptSpec? = null,
)

@Serializable
data class LevelSpec(
    val split: String,
    val take: Int? = null,
)

@Serializable
data class RewriteSpec(
    val pattern: String,
    val replacement: String,
)

/** 把 `from` 的 string / string[] 两种写法归一成候选列表 */
val FieldSpec.candidateExpressions: List<String>
    get() = when (val f = from) {
        null -> emptyList()
        is JsonPrimitive -> listOf(f.content)
        is JsonArray -> f.mapNotNull { (it as? JsonPrimitive)?.content }
        else -> emptyList()
    }

// ── 媒体条目（视频剧集 / 音频曲目 / 图片集）──────────────────

/**
 * 两种形态，用 [TrackSpec.mode] 切换：
 *  - `delimited`：TVBox 经典多级分隔串（视频剧集、音频曲目最常用）
 *  - `array`：数组（图片集最常用）
 *
 * 分隔符必须按接口字面写完整字符串（常见是 3 个 `$`），
 * 写错不会"宽松匹配"，而是 0 命中并报 E011。
 */
@Serializable
data class TrackSpec(
    val mode: TrackMode = TrackMode.DELIMITED,

    // ── delimited 模式 ──
    val groupNames: GroupNamesSpec = GroupNamesSpec(),
    val items: ItemSplitSpec = ItemSplitSpec(),
    val label: LabelSpec = LabelSpec(),

    // ── array 模式 ──
    /** 数组路径，如 "$.images[*]"；mode=array 时必填 */
    val from: String? = null,
    /**
     * 数组元素 → Track 字段。
     * 键限定为 title / url / thumb / duration；值可以是路径字符串，也可以是
     * [FieldSpec]（需要 prefix 拼 imageBase、或要转数值时用）。
     */
    val fields: Map<String, FieldSpec> = emptyMap(),
)

@Serializable
enum class TrackMode {
    @SerialName("delimited") DELIMITED,
    @SerialName("array") ARRAY,
}

@Serializable
data class GroupNamesSpec(
    val from: String? = null,
    val split: String = "$$$",
)

@Serializable
data class ItemSplitSpec(
    val from: String? = null,
    val groupSplit: String = "$$$",
    val itemSplit: String = "#",
    /** 有它 → 每项是"标题$地址"；无它 → 整串就是地址，标题自动编号 */
    val pairSplit: String? = null,
)

@Serializable
data class LabelSpec(
    val autoIndex: Boolean = true,
    val prefix: String = "第",
    val suffix: String = "集",
)

// ── 筛选 ─────────────────────────────────────────────────────

@Serializable
data class FilterResource(
    val groups: List<FilterGroupSpec> = emptyList(),
)

@Serializable
data class FilterGroupSpec(
    val key: String,
    val title: String,
    val values: List<FilterValueSpec> = emptyList(),
)

@Serializable
data class FilterValueSpec(
    val label: String,
    val value: String = "",
)

// ── pages ────────────────────────────────────────────────────

@Serializable
data class PageSpec(
    val id: String,
    val title: String = "",
    val layout: LayoutSpec,
    /** 引用 resources.feeds / resources.search 的 key */
    val feed: String? = null,
    /** 注入请求的页面参数，可用 {{p.xxx}} 在 URL 中引用 */
    val params: Map<String, String> = emptyMap(),
    val card: CardSpec = CardSpec(),
    val children: List<PageSpec> = emptyList(),
    val sections: List<PageSpec> = emptyList(),
    val showRelated: Boolean = false,
    /** 栏目 tab：非空时首页顶部渲染一排 tab，每个 tab 用自己的 feed/params 取数 */
    val channels: List<ChannelSpec> = emptyList(),
    /**
     * 视图切换候选，如 `["poster_grid","list_linear"]`：非空时首页 tab 行右端出现"网格/列表"切换按钮，
     * 按顺序循环。为空 = 只有 `layout.type` 一种视图。
     */
    val viewModes: List<LayoutType> = emptyList(),
)

/**
 * 首页的一个栏目（tab）。**栏目是"取数入口"的切换，不是新的布局体系**：
 * 它只决定"这一屏去请求哪条 feed、带什么参数"，排版仍由 [PageSpec.layout] 或它自己的 [layout] 决定。
 *
 * 这样同一份 UI 既能表达"一个站点只有一屏"，也能表达"首页 / 电视剧 / 电影"这种多栏目，
 * 而站点差异全部留在 JSON 里。
 */
@Serializable
data class ChannelSpec(
    val id: String,
    val title: String = "",
    /** 必须是 `resources.feeds` 里已存在的 key（校验器查 E005） */
    val feed: String,
    /** 覆盖页面级 params；不填则用页面的 */
    val params: Map<String, String> = emptyMap(),
    /** 覆盖页面级 layout；不填则继承 */
    val layout: LayoutSpec? = null,
    /** 覆盖页面级 card；不填则继承 */
    val card: CardSpec? = null,
)

@Serializable
data class LayoutSpec(
    val type: LayoutType,
    // 通用
    val title: String? = null,
    val columns: Int? = null,
    val ratio: String? = null,
    val gap: Int? = null,
    // tabs
    val position: String? = null,
    // banner_hero
    val heightRatio: Float? = null,
    val autoplay: Boolean = false,
    val intervalMs: Long? = null,
    // list_linear / row_cards
    val showMore: Boolean = false,
    // detail
    val expandLines: Int? = null,
    val resource: String? = null,
    /** 引用 resources.tracks 的键；视频=剧集源，音频=曲目源，图片=图集源 */
    val tracks: String? = null,
    // category_filter
    val inline: Boolean? = null,
    // track_panel / gallery_grid / list_linear
    /** 是否显示序号列（种子页格子里的"第N集"） */
    val showIndex: Boolean = true,
    /** 多组时是否出组切换 chip */
    val groupTabs: Boolean = true,
    /** 列表是否画分隔线 */
    val divider: Boolean = true,
    // image_viewer（M4.6：zoom / swipe 目前是固定能力，字段先接受，未逐项接线）
    val zoom: Boolean = true,
    val swipe: Boolean = true,
    val showCounter: Boolean = true,
    // audio_player（M4.5）
    val showCover: Boolean = true,
    val showPlaylist: Boolean = true,
    // video_player
    val aspect: String? = null,
    val autoNext: Boolean? = null,
    // search
    val debounceMs: Long? = null,
    val historySize: Int? = null,
)

@Serializable
data class CardSpec(
    val style: CardStyle = CardStyle.POSTER,
    /** 取 MediaSummary 的哪个字段作图 */
    val image: String? = "poster",
    val title: String? = "title",
    /** 右上角角标 */
    val badge: String? = null,
    val subtitle: String? = null,
    val score: String? = null,
    val ratio: String? = null,
    val action: ActionSpec = ActionSpec(),
)

@Serializable
data class ActionSpec(
    val type: ActionType = ActionType.DETAIL,
    /** type=link 时的 URL 模板，可用 {{item.xxx}} */
    val url: String? = null,
)

// ── playback ─────────────────────────────────────────────────

@Serializable
data class PlaybackSpec(
    val strategy: PlayStrategy = PlayStrategy.DIRECT,
    val headers: Map<String, String> = emptyMap(),
    val resolver: ResolverSpec? = null,
    val player: PlayerSpec = PlayerSpec(),
    /** 音频播放专属行为（视频不使用） */
    val audio: AudioPlaybackSpec = AudioPlaybackSpec(),
)

@Serializable
data class AudioPlaybackSpec(
    /** 后台/息屏继续播放（Android 走前台服务 + MediaSession） */
    val backgroundPlay: Boolean = true,
    /** sequential | loop | shuffle */
    val mode: String = "sequential",
    /** 通知栏 / 锁屏控制 */
    val showNotification: Boolean = true,
)

@Serializable
data class ResolverSpec(
    val request: RequestSpec = RequestSpec(),
    val extract: FieldSpec = FieldSpec(),
    val required: Boolean = true,
)

@Serializable
data class PlayerSpec(
    val autoNext: Boolean = true,
    /** fit | fill | crop */
    val aspect: String = "fit",
    val seekStepSec: Int = 10,
    val speedLevels: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f),
    val rememberPosition: Boolean = true,
)

// ── theme / flags ────────────────────────────────────────────

@Serializable
data class ThemeSpec(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val primary: String = "#FF6B35",
    val background: String = "#0F0F13",
    val surface: String = "#17171C",
    val onSurface: String = "#E6E6EA",
    val accent: String = "#3DDC97",
    val cornerRadius: Int = 12,
    val density: Density = Density.COMFORTABLE,
    val posterColumns: Int = 3,
    val fontScale: Float = 1.0f,
)

@Serializable
data class Flags(
    /**
     * 首页顶栏是否出「搜索」入口。
     * 真正的显示条件是 `enableSearch && resources.search 非空` —— 只写开关却没有 search 资源，
     * 点进去只会是一屏"搜不到"，那种入口没有意义（v1 不再支持多源搜索，见 docs/03 §12）。
     */
    val enableSearch: Boolean = true,
    val enableHistory: Boolean = true,
    val enableFavorite: Boolean = true,
    /** 是否允许明文 http（默认只放行 https） */
    val allowCleartext: Boolean = false,
    val debugTrace: Boolean = true,
)
