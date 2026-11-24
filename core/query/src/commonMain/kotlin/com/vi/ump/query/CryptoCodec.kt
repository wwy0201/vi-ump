package com.vi.ump.query

/**
 * 通用加解密引擎（docs/03 §3.3）。
 *
 * 分工：
 * - **本文件**（commonMain）：算法白名单、编码层（base64 / base64url / hex / raw）、
 *   解密/加密的编排、`zero` 补块的尾部清理——纯 Kotlin，可在任何平台跑、可单测；
 * - **`CryptoBlock.kt`**（androidMain / jvmMain）：真正的块加密调用，两端都用 `javax.crypto`
 *   （docs/03 §16.1：不引入第三方加密依赖）。
 *
 * 不做的事（写清楚免得配置作者以为"配了就能用"）：
 * - **签名**（HMAC / MD5 / SHA 摘要）：那是"算摘要"不是"加密"，语义与用途都不同，本轮没做；
 * - **RSA / SM4 / 国密**：RSA 需要 PEM/ASN.1 解析与额外依赖，SM4 不在 JCE 里；
 * - **每请求动态密钥**（key 由接口下发）：配置表达不了，按 `W003` 可解释降级。
 */
object CryptoCodec {

    /** 算法白名单 → 密钥字节数。AES 三档 × 四种模式 + DES / 3DES 各两种模式。 */
    val KEY_SIZES: Map<String, Int> = mapOf(
        "aes-128-cbc" to 16, "aes-192-cbc" to 24, "aes-256-cbc" to 32,
        "aes-128-ecb" to 16, "aes-192-ecb" to 24, "aes-256-ecb" to 32,
        "aes-128-ctr" to 16, "aes-192-ctr" to 24, "aes-256-ctr" to 32,
        "aes-128-gcm" to 16, "aes-192-gcm" to 24, "aes-256-gcm" to 32,
        "des-cbc" to 8, "des-ecb" to 8,
        "des3-cbc" to 24, "des3-ecb" to 24,
    )

    val PADDINGS: Set<String> = setOf("pkcs7", "none", "zero")
    val OUTPUTS: Set<String> = setOf("base64", "base64url", "hex", "raw")
    val KEY_FORMATS: Set<String> = setOf("text", "hex", "base64")

    /** 该算法要不要 iv（ECB 不要），以及 iv 的**要求长度** */
    fun ivSize(algo: String): Int? = when {
        algo.endsWith("-ecb") -> 0
        algo.endsWith("-gcm") -> 12
        algo.startsWith("des3") || algo.startsWith("des") -> 8
        algo.startsWith("aes") -> 16
        else -> null
    }

    /** 规范化算法名（小写、去空白）。不认识返回 null。 */
    fun normalizeAlgo(raw: String?): String? {
        val a = raw?.trim()?.lowercase()?.replace("_", "-") ?: return null
        return if (a in KEY_SIZES) a else null
    }

    fun keyBytes(spec: String, format: String): ByteArray? = when (format) {
        "text" -> spec.encodeToByteArray()
        "hex" -> hexToBytes(spec)
        "base64" -> base64Decode(spec, urlSafe = false)
        else -> null
    }

    fun encodeText(bytes: ByteArray, format: String): String? = when (format) {
        "base64" -> base64Encode(bytes, urlSafe = false)
        "base64url" -> base64Encode(bytes, urlSafe = true)
        "hex" -> hexEncode(bytes)
        "raw" -> bytes.decodeToString()
        else -> null
    }

    fun decodeText(text: String, format: String): ByteArray? = when (format) {
        "base64" -> base64Decode(text, urlSafe = false)
        "base64url" -> base64Decode(text, urlSafe = true)
        "hex" -> hexToBytes(text)
        "raw" -> text.encodeToByteArray()
        else -> null
    }

    /** 编码后的密文 → 明文。任何一步失败返回 null（调用方据此降级，不炸页面）。 */
    fun decrypt(input: String, o: CryptoOptions): String? {
        val cipher = decodeText(input, o.output) ?: return null
        val plain = blockCrypt(o.algo, o.key, o.iv, cipher, encrypt = false, padding = o.padding) ?: return null
        val bytes = if (o.padding == "zero") trimZeros(plain) else plain
        return bytes.decodeToString()
    }

    /** 明文 → 编码后的密文。 */
    fun encrypt(plain: String, o: CryptoOptions): String? {
        val cipher = blockCrypt(o.algo, o.key, o.iv, plain.encodeToByteArray(), encrypt = true, padding = o.padding)
            ?: return null
        return encodeText(cipher, o.output)
    }

    /**
     * 字节进、字节出（图片通路）。不经过 [CryptoOptions.output] 的编码层——
     * 图片请求拿到的就是二进制密文。
     */
    fun decryptBytes(input: ByteArray, o: CryptoOptions): ByteArray? {
        val plain = blockCrypt(o.algo, o.key, o.iv, input, encrypt = false, padding = o.padding) ?: return null
        return if (o.padding == "zero") trimZeros(plain) else plain
    }

    private fun trimZeros(bytes: ByteArray): ByteArray {
        var end = bytes.size
        while (end > 0 && bytes[end - 1] == 0.toByte()) end--
        return if (end == bytes.size) bytes else bytes.copyOfRange(0, end)
    }
}

/**
 * 运行期的加解密参数：算法已规范化、key/iv 已解成字节、编码与补块已定。
 *
 * 为什么不暴露 key：它会出现在缓存键里（见 [fingerprint]），而缓存键会进日志/调试输出。
 * 指纹只用 `contentHashCode`，反推不出密钥。
 */
class CryptoOptions(
    val algo: String,
    val key: ByteArray,
    val iv: ByteArray,
    val padding: String,
    val output: String,
) {
    /** 进缓存键用：改了 key/iv/算法就要让旧缓存失效 */
    val fingerprint: String =
        "$algo/${key.contentHashCode()}/${iv.contentHashCode()}/$padding/$output"

    override fun toString(): String = "CryptoOptions($algo, padding=$padding, output=$output)"
}

// ── 编码层（纯 Kotlin，避免 `java.util.Base64` 在低版本 Android 上的 API 限制）────────

private const val BASE64_STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
private const val BASE64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

internal fun base64Encode(bytes: ByteArray, urlSafe: Boolean): String {
    val alphabet = if (urlSafe) BASE64_URL else BASE64_STD
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
        out.append(alphabet[b0 shr 2])
        out.append(alphabet[(b0 shl 4 or (b1 shr 4)) and 0x3F])
        if (i + 1 < bytes.size) out.append(alphabet[(b1 shl 2 or (b2 shr 6)) and 0x3F]) else out.append('=')
        if (i + 2 < bytes.size) out.append(alphabet[b2 and 0x3F]) else out.append('=')
        i += 3
    }
    // base64url 按惯例不带 padding（RFC 7515）；解码端会自动补回
    return if (urlSafe) out.toString().trimEnd('=') else out.toString()
}

internal fun base64Decode(text: String, urlSafe: Boolean): ByteArray? {
    var s = text.filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' || it == '"' }
    if (s.isEmpty()) return null
    if (urlSafe) s = s.replace('-', '+').replace('_', '/')
    when (s.length % 4) {
        1 -> return null
        2 -> s += "=="
        3 -> s += "="
    }
    val out = ArrayList<Byte>(s.length / 4 * 3)
    var i = 0
    while (i < s.length) {
        val c0 = charIndex(s[i]) ?: return null
        val c1 = charIndex(s[i + 1]) ?: return null
        val c2 = if (s[i + 2] == '=') -1 else (charIndex(s[i + 2]) ?: return null)
        val c3 = if (s[i + 3] == '=') -1 else (charIndex(s[i + 3]) ?: return null)
        out.add(((c0 shl 2) or (c1 shr 4)).toByte())
        if (c2 >= 0) out.add((((c1 and 0x0F) shl 4) or (c2 shr 2)).toByte())
        if (c2 >= 0 && c3 >= 0) out.add((((c2 and 0x03) shl 6) or c3).toByte())
        i += 4
    }
    return out.toByteArray()
}

private fun charIndex(c: Char): Int? {
    if (c == '=') return null
    val i = BASE64_STD.indexOf(c)
    return if (i >= 0) i else null
}

internal fun hexEncode(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}

internal fun hexToBytes(text: String): ByteArray? {
    var s = text.trim().removePrefix("0x").removePrefix("0X")
    if (s.length % 2 != 0) s = "0$s"
    if (s.isEmpty()) return null
    val out = ByteArray(s.length / 2)
    var i = 0
    while (i < s.length) {
        val hi = hexDigit(s[i]) ?: return null
        val lo = hexDigit(s[i + 1]) ?: return null
        out[i / 2] = ((hi shl 4) or lo).toByte()
        i += 2
    }
    return out
}

private fun hexDigit(c: Char): Int? = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> null
}

/**
 * 平台块加密实现。返回 null 表示"这个参数组合算不出来"（长度不对、算法不支持、密文坏块），
 * 调用方一律按"这张图/这个字段没有"降级，不抛异常。
 */
internal expect fun blockCrypt(
    algo: String,
    key: ByteArray,
    iv: ByteArray,
    input: ByteArray,
    encrypt: Boolean,
    padding: String,
): ByteArray?

