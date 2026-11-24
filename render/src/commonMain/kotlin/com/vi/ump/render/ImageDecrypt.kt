package com.vi.ump.render

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.vi.ump.model.ImageDecryptSpec
import com.vi.ump.query.CryptoCodec
import com.vi.ump.query.CryptoOptions
import com.vi.ump.source.HttpFetcher
import com.vi.ump.source.createHttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 密文图片通路（docs/03 §3.2）。
 *
 * 有一类站点把封面/海报以 AES 密文存放，客户端拿到的字节既不是 PNG 也不是 JPEG，
 * 解码器**静默失败** —— 用户看到的是"标题能出、图全是空块"，而且哪里都不报错。
 *
 * 本文件的路线是「自己取字节 → 解密 → 把**明文**交给 Coil」：
 * PNG/GIF/WEBP 明文直接放行，否则解密，解不开就当这张图没有（占位），**不阻断列表**。
 *
 * 为什么不用 Coil 的 `Fetcher`：实测两条路都走不通——
 * ① 往 `ImageLoader` 的 components 里加工厂、② `ImageRequest.fetcherFactory(...)`——
 * 两种写法下密文 URL 都依然先被原生 `NetworkFetcher` 接走（日志里 `create` 一次都没被调），
 * 等于解密代码永远不执行。把字节在**进 Coil 之前**准备好，就绕开了整个优先级问题。
 */
object UmpImageDecrypt {

    /**
     * 当前源的解码规则；`null` = 这个源没有密文图（图片走 Coil 原生网络通路，行为零变化）。
     *
     * 全局单例是安全的：App 同一时刻只有一个"当前源"，切源会重建整个首页（`AppRoot` 的 session 变）。
     * 代价是**不同源的规则不能并存**——真需要并存时再改成按 host 路由。
     */
    var spec: ImageDecryptSpec? = null
        private set

    /** 图片请求要带的头（`runtime.headers`，很多 CDN 校验 Referer/UA）。 */
    var headers: Map<String, String> = emptyMap()
        private set

    /**
     * 规则版本号（Compose state）。
     *
     * 为什么不直接把 [spec] 做成 state：取值的地方既有组合线程也有后台协程，
     * 拆成"值 + 版本号"最省事：版本号变 → 重组 → 重组里重新读一次值。
     */
    private var revision by mutableStateOf(0)
    internal val currentRevision: Int get() = revision

    val enabled: Boolean get() = spec != null

    fun install(spec: ImageDecryptSpec?, headers: Map<String, String>) {
        this.spec = spec
        this.headers = headers
        revision++
    }

    fun clear() {
        install(null, emptyMap())
    }
}

/**
 * 统一的图片入口（render 里 9 处调用全部走这里）。
 *
 * - 源没声明 `imageDecrypt` → 与直接调 `AsyncImage` 完全一致（连 model 都不换）；
 * - 声明了 → 先由 [DecryptedImageStore] 取字节并解密，再把**明文**交给 Coil 解码。
 */
@Composable
fun UmpAsyncImage(
    model: Any?,
    contentDescription: String?,
    contentScale: ContentScale = ContentScale.Fit,
    modifier: Modifier = Modifier,
) {
    val revision = UmpImageDecrypt.currentRevision
    val url = model as? String
    val spec = UmpImageDecrypt.spec
    if (spec == null || url == null || !url.startsWith("http")) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
        return
    }

    // 取字节（解密）放在协程里：Ktor 是挂起调用，不会堵主线程
    val bytes by produceState<ByteArray?>(null, url, revision) {
        value = DecryptedImageStore.load(url, spec, UmpImageDecrypt.headers)
    }
    val plain = bytes
    if (plain == null) {
        // 还没取到 / 解不开：留空位（与"没有封面"时同样的观感），不画破图
        Box(modifier = modifier)
        return
    }
    val context = LocalPlatformContext.current
    AsyncImage(
        // 交给 Coil 的是明文，走它内置的 ByteArray 通路；缓存键带规则指纹，
        // 站点换了 key 之后旧缓存（空图）不会再被命中
        model = ImageRequest.Builder(context)
            .data(plain)
            .memoryCacheKey("$url#${spec.fingerprint()}")
            .build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
    )
}

/** 规则指纹：只进缓存键，不进日志（key/iv 是站点密钥，不该外露） */
private fun ImageDecryptSpec.fingerprint(): String =
    optionsOf(this)?.fingerprint ?: "$algo/${key.hashCode()}/${iv.hashCode()}/$padding"

/**
 * 解密后的字节缓存。
 *
 * 为什么要自己缓存：明文是我们加工出来的，Coil 的磁盘缓存只认它自己的下载结果（密文），
 * 不给一份内存缓存的话，列表每滚一轮就会重新下载+解密一遍。
 * 条目数上限按"一屏半"估：720×1280 的机器一屏 6 张，24 条足够来回滚动。
 */
private object DecryptedImageStore {

    private const val MAX_ENTRIES = 24

    private val cache = object : LinkedHashMap<String, ByteArray>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > MAX_ENTRIES
    }

    private val lock = Mutex()
    private val http: HttpFetcher by lazy { HttpFetcher(createHttpClient()) }

    suspend fun load(url: String, spec: ImageDecryptSpec, headers: Map<String, String>): ByteArray? {
        val key = "${spec.fingerprint()}@$url"
        lock.withLock { cache[key] }?.let { return it }
        val raw = runCatching { http.fetchBytes(url, headers) }.getOrNull() ?: return null
        // 先嗅明文：有的站点只有部分图是密的（转码 CDN 上还留着明文 WebP），
        // 一律解密会把明文图解成垃圾
        val plain = if (looksLikePlainImage(raw)) {
            raw
        } else {
            // 解出来的产物要用「解码后」的口径判（JPEG 的 FF D8 FF 在这一步可以认了：
            // 这不是"猜输入是不是图"，而是"确认刚才那一步解对了"）
            decryptImage(raw, spec)?.takeIf { looksLikeDecodedImage(it) }
        }
        if (plain != null) lock.withLock { cache[key] = plain }
        return plain
    }
}

/**
 * 明文图片嗅探。**只认足够长的魔数**：
 * `\x89PNG` / `GIF8` / `RIFF…WEBP`。
 *
 * 为什么不认 JPEG 的 `FF D8`：两个字节太短，随机密文有概率撞上，
 * 会把密文当图送进解码器（站点自己的 worker 也不这么做，见 docs/03 §3.2 第 2 步）。
 */
internal fun looksLikePlainImage(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    // PNG: 89 50 4E 47 0D 0A 1A 0A
    if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    ) {
        return true
    }
    // GIF: 47 49 46 38 ("GIF8")
    if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
    ) {
        return true
    }
    // WEBP: "RIFF" ???? "WEBP"
    if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
        bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
        bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
    ) {
        return true
    }
    return false
}

/**
 * 解密**产物**的判定：在 [looksLikePlainImage] 的基础上多认 JPEG 的 `FF D8 FF`。
 *
 * 两个函数分开而不是合成一个，是因为它们回答的问题不同：
 * - 输入侧要判断"这坨字节本来是不是图"，2 字节太容易撞，所以不认 JPEG；
 * - 产物侧要判断"刚才解密是不是解对了"，此时已经确知有密文、也确知密钥口径，
 *   三字节的 `FF D8 FF` 足够强，认它才不会把正常的 JPEG 判成失败。
 */
internal fun looksLikeDecodedImage(bytes: ByteArray): Boolean {
    if (looksLikePlainImage(bytes)) return true
    return bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
}

/**
 * `imageDecrypt` → 通用加解密参数（docs/03 §3.2 / §3.3）。
 *
 * 图片通路是字节进字节出，所以 [CryptoOptions.output] 没有意义（仍带上，保持一处口径）。
 * 参数不合法（算法不认识 / key 长度不对）返回 null —— 与"没有解密规则"等价，
 * 结果是这张图留空位，不是崩页面。
 */
internal fun optionsOf(spec: ImageDecryptSpec): CryptoOptions? {
    val algo = CryptoCodec.normalizeAlgo(spec.algo) ?: return null
    val key = CryptoCodec.keyBytes(spec.key, spec.keyFormat) ?: return null
    if (key.size != CryptoCodec.KEY_SIZES[algo]) return null
    val ivNeed = CryptoCodec.ivSize(algo) ?: return null
    val iv = if (ivNeed == 0) {
        ByteArray(0)
    } else {
        CryptoCodec.keyBytes(spec.iv, spec.ivFormat) ?: return null
    }
    if (ivNeed > 0 && iv.size != ivNeed) return null
    if (spec.padding !in CryptoCodec.PADDINGS) return null
    return CryptoOptions(algo, key, iv, spec.padding, spec.output)
}

/** 按配置解一次。规则不合法（长度不对/算法不认识）时直接返回 null，不抛。 */
internal fun decryptImage(raw: ByteArray, spec: ImageDecryptSpec): ByteArray? {
    val o = optionsOf(spec) ?: return null
    val plain = CryptoCodec.decryptBytes(raw, o) ?: return null
    // `padding=none`：站点自己补的块不会被解掉，尾部会多 1–15 个废字节。
    // 按最后一个 JPEG 结束标记截断（站点浏览器侧也是这么做的，解码器本来也容忍尾巴）。
    return if (o.padding == "pkcs7") plain else trimJpegTail(plain)
}

/** 截到最后一个 `FF D9`（JPEG EOI）之后。找不到就原样返回。 */
internal fun trimJpegTail(bytes: ByteArray): ByteArray {
    var i = bytes.size - 2
    while (i >= 0) {
        if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD9.toByte()) {
            return if (i + 2 < bytes.size) bytes.copyOfRange(0, i + 2) else bytes
        }
        i--
    }
    return bytes
}
