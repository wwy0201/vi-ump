package com.vi.ump.source

/**
 * 抖音 a_bogus 纯算实现（移植自 f2 的 `utils/abogus.py`，Apache-2.0）。
 *
 * 为什么放进 App 里而不是让配置签名：抖音网页版推荐流（`aweme/v1/web/tab/feed/`）
 * 要求 query 上带一个由 bdms SDK 生成的 `a_bogus`，没有它一律返回 7 字节 `blocked`；
 * 而本项目的源配置引擎只会"拼 URL + 抽字段"，表达不了这层签名，只能由代码算。
 *
 * 链路：SM3 二次哈希（盐 `cus`）→ 定长字段表 → 位掩码混淆 → 自定义 Base64。
 * 关键点：`big_array` 是**有状态的**（每次加密都会改动它），所以每次签名都用一份新拷贝。
 */
internal object Abogus {

    private const val SALT = "cus"
    private const val AID = 6383
    private const val PAGE_ID = 0

    /** 请求方法位（GET 用 [0,1,8]，POST 用 [0,1,14]；14 兼容 8，故写死） */
    private val OPTIONS = intArrayOf(0, 1, 14)

    private val UA_KEY = byteArrayOf(0x00, 0x01, 0x0E)

    private const val ALPHABET0 = "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe"

    private const val ALPHABET1 = "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe"

    private val SORT_INDEX = intArrayOf(
        18, 20, 52, 26, 30, 34, 58, 38, 40, 53, 42, 21, 27, 54, 55, 31, 35, 57, 39, 41, 43, 22, 28,
        32, 60, 36, 23, 29, 33, 37, 44, 45, 59, 46, 47, 48, 49, 50, 24, 25, 65, 66, 70, 71,
    )

    private val SORT_INDEX_2 = intArrayOf(
        18, 20, 26, 30, 34, 38, 40, 42, 21, 27, 31, 35, 39, 41, 43, 22, 28, 32, 36, 23, 29, 33, 37,
        44, 45, 46, 47, 48, 49, 50, 24, 25, 52, 53, 54, 55, 57, 58, 59, 60, 65, 66, 70, 71,
    )

    private val BIG_ARRAY = intArrayOf(
        121, 243, 55, 234, 103, 36, 47, 228, 30, 231, 106, 6, 115, 95, 78, 101, 250, 207, 198, 50,
        139, 227, 220, 105, 97, 143, 34, 28, 194, 215, 18, 100, 159, 160, 43, 8, 169, 217, 180, 120,
        247, 45, 90, 11, 27, 197, 46, 3, 84, 72, 5, 68, 62, 56, 221, 75, 144, 79, 73, 161,
        178, 81, 64, 187, 134, 117, 186, 118, 16, 241, 130, 71, 89, 147, 122, 129, 65, 40, 88, 150,
        110, 219, 199, 255, 181, 254, 48, 4, 195, 248, 208, 32, 116, 167, 69, 201, 17, 124, 125, 104,
        96, 83, 80, 127, 236, 108, 154, 126, 204, 15, 20, 135, 112, 158, 13, 1, 188, 164, 210, 237,
        222, 98, 212, 77, 253, 42, 170, 202, 26, 22, 29, 182, 251, 10, 173, 152, 58, 138, 54, 141,
        185, 33, 157, 31, 252, 132, 233, 235, 102, 196, 191, 223, 240, 148, 39, 123, 92, 82, 128, 109,
        57, 24, 38, 113, 209, 245, 2, 119, 153, 229, 189, 214, 230, 174, 232, 63, 52, 205, 86, 140,
        66, 175, 111, 171, 246, 133, 238, 193, 99, 60, 74, 91, 225, 51, 76, 37, 145, 211, 166, 151,
        213, 206, 0, 200, 244, 176, 218, 44, 184, 172, 49, 216, 93, 168, 53, 21, 183, 41, 67, 85,
        224, 155, 226, 242, 87, 177, 146, 70, 190, 12, 162, 19, 137, 114, 25, 165, 163, 192, 23, 59,
        9, 94, 179, 107, 35, 7, 142, 131, 239, 203, 149, 136, 61, 249, 14, 156,
    )

    /**
     * 生成 `a_bogus`。
     *
     * @param params 请求的 query 字符串（**不含前导 `?`**，顺序必须与实际发出的 URL 完全一致，
     *   空格用 `+` 而不是 `%20`）
     * @param userAgent 与实际请求头里一模一样的 UA（进哈希）
     * @param nowMs 时间戳（毫秒），测试可注入
     * @param rnd 随机源，测试可注入
     */
    fun sign(
        params: String,
        userAgent: String,
        fingerprint: String,
        nowMs: Long,
        rnd: RandomSource,
    ): String {
        var out = randomBytes(3, rnd)
        out += transformBytes(payloadValues(params, userAgent, fingerprint, nowMs))
        return encode(out, 0)
    }

    /** 定长字段表（`ab_dir` 的扁平化结果），拆出来只为方便和 Python 版逐项对拍 */
    internal fun payloadValues(
        params: String,
        userAgent: String,
        fingerprint: String,
        nowMs: Long,
    ): List<Int> {
        // ① 哈希：URL 加盐后做两次 SM3；body 为空（GET）；UA 先 RC4 再哈希（不加盐）
        val array1 = sm3ToArray(sm3ToArray(params + SALT))
        val array2 = sm3ToArray(sm3ToArray("" + SALT))
        val array3 = sm3ToArray(base64Encode(bytesAsCharString(rc4(UA_KEY, userAgent)), 1))
        val end = nowMs

        val dir = HashMap<Int, Int>()
        dir[8] = 3
        dir[18] = 44
        dir[66] = 0
        dir[69] = 0
        dir[70] = 0
        dir[71] = 0
        dir[20] = ((nowMs shr 24) and 255).toInt()
        dir[21] = ((nowMs shr 16) and 255).toInt()
        dir[22] = ((nowMs shr 8) and 255).toInt()
        dir[23] = (nowMs and 255).toInt()
        dir[24] = (nowMs / 256L / 256 / 256 / 256).toInt()
        dir[25] = (nowMs / 256L / 256 / 256 / 256 / 256).toInt()

        dir[26] = ((OPTIONS[0] shr 24) and 255)
        dir[27] = ((OPTIONS[0] shr 16) and 255)
        dir[28] = ((OPTIONS[0] shr 8) and 255)
        dir[29] = OPTIONS[0] and 255

        dir[30] = (OPTIONS[1] / 256) and 255
        dir[31] = OPTIONS[1] % 256
        dir[32] = (OPTIONS[1] shr 24) and 255
        dir[33] = (OPTIONS[1] shr 16) and 255

        dir[34] = ((OPTIONS[2] shr 24) and 255)
        dir[35] = ((OPTIONS[2] shr 16) and 255)
        dir[36] = ((OPTIONS[2] shr 8) and 255)
        dir[37] = OPTIONS[2] and 255

        dir[38] = array1[21]
        dir[39] = array1[22]
        dir[40] = array2[21]
        dir[41] = array2[22]
        dir[42] = array3[23]
        dir[43] = array3[24]

        dir[44] = ((end shr 24) and 255).toInt()
        dir[45] = ((end shr 16) and 255).toInt()
        dir[46] = ((end shr 8) and 255).toInt()
        dir[47] = (end and 255).toInt()
        dir[48] = dir[8]!!
        dir[49] = (end / 256L / 256 / 256 / 256).toInt()
        dir[50] = (end / 256L / 256 / 256 / 256 / 256).toInt()

        dir[51] = ((PAGE_ID shr 24) and 255)
        dir[52] = ((PAGE_ID shr 16) and 255)
        dir[53] = ((PAGE_ID shr 8) and 255)
        dir[54] = PAGE_ID and 255
        dir[55] = PAGE_ID
        dir[56] = AID
        dir[57] = AID and 255
        dir[58] = (AID shr 8) and 255
        dir[59] = (AID shr 16) and 255
        dir[60] = (AID shr 24) and 255

        dir[64] = fingerprint.length
        dir[65] = fingerprint.length

        val values = ArrayList<Int>(SORT_INDEX.size + fingerprint.length + 1)
        for (k in SORT_INDEX) values += dir[k] ?: 0

        var x = 0
        for (i in 0 until SORT_INDEX_2.size - 1) {
            if (i == 0) x = dir[SORT_INDEX_2[0]] ?: 0
            x = x xor (dir[SORT_INDEX_2[i + 1]] ?: 0)
        }

        for (c in fingerprint) values += c.code
        values += x

        return values
    }

    // ── SM3（GB/T 32905-2016）───────────────────────────────────

    private fun rotl(x: Int, n: Int): Int {
        val s = n and 31
        return if (s == 0) x else (x shl s) or (x ushr (32 - s))
    }

    private fun p0(x: Int): Int = x xor rotl(x, 9) xor rotl(x, 17)
    private fun p1(x: Int): Int = x xor rotl(x, 15) xor rotl(x, 23)

    fun sm3ToArray(input: String): List<Int> {
        val digest = sm3(input.toByteArray(Charsets.UTF_8))
        return digest.map { it.toInt() and 0xFF }
    }

    private fun sm3ToArray(bytes: ByteArray): List<Int> =
        sm3(bytes).map { it.toInt() and 0xFF }

    /** 二次哈希：把上一次的 32 字节摘要当成新的输入 */
    private fun sm3ToArray(values: List<Int>): List<Int> =
        sm3(ByteArray(values.size) { values[it].toByte() }).map { it.toInt() and 0xFF }

    fun sm3(msg: ByteArray): ByteArray {
        var v0 = 0x7380166f
        var v1 = 0x4914b2b9
        var v2 = 0x172442d7
        var v3 = 0xda8a0600.toInt()
        var v4 = 0xa96f30bc.toInt()
        var v5 = 0x163138aa
        var v6 = 0xe38dee4d.toInt()
        var v7 = 0xb0fb0e4e.toInt()

        val bitLen = msg.size.toLong() * 8
        val padded = ArrayList<Byte>(msg.size + 64)
        msg.forEach { padded.add(it) }
        padded.add(0x80.toByte())
        while (padded.size % 64 != 56) padded.add(0)
        for (i in 7 downTo 0) padded.add(((bitLen shr (i * 8)) and 0xFF).toByte())

        val w = IntArray(68)
        val w1 = IntArray(64)
        var i = 0
        while (i < padded.size) {
            for (j in 0 until 16) {
                val b = i + j * 4
                w[j] = ((padded[b].toInt() and 0xFF) shl 24) or
                    ((padded[b + 1].toInt() and 0xFF) shl 16) or
                    ((padded[b + 2].toInt() and 0xFF) shl 8) or
                    (padded[b + 3].toInt() and 0xFF)
            }
            for (j in 16 until 68) {
                w[j] = p1(w[j - 16] xor w[j - 9] xor rotl(w[j - 3], 15)) xor
                    rotl(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) w1[j] = w[j] xor w[j + 4]

            var a = v0; var b = v1; var c = v2; var d = v3
            var e = v4; var f = v5; var g = v6; var h = v7

            for (j in 0 until 64) {
                val t = if (j < 16) 0x79cc4519 else 0x7a879d8a
                val ss1 = rotl(rotl(a, 12) + e + rotl(t, j % 32), 7)
                val ss2 = ss1 xor rotl(a, 12)
                val ff = if (j < 16) a xor b xor c else (a and b) or (a and c) or (b and c)
                val gg = if (j < 16) e xor f xor g else (e and f) or (e.inv() and g)
                val tt1 = ff + d + ss2 + w1[j]
                val tt2 = gg + h + ss1 + w[j]
                d = c
                c = rotl(b, 9)
                b = a
                a = tt1
                h = g
                g = rotl(f, 19)
                f = e
                e = p0(tt2)
            }

            v0 = v0 xor a; v1 = v1 xor b; v2 = v2 xor c; v3 = v3 xor d
            v4 = v4 xor e; v5 = v5 xor f; v6 = v6 xor g; v7 = v7 xor h
            i += 64
        }

        val out = ByteArray(32)
        val vs = intArrayOf(v0, v1, v2, v3, v4, v5, v6, v7)
        for (k in vs.indices) {
            out[k * 4] = ((vs[k] shr 24) and 0xFF).toByte()
            out[k * 4 + 1] = ((vs[k] shr 16) and 0xFF).toByte()
            out[k * 4 + 2] = ((vs[k] shr 8) and 0xFF).toByte()
            out[k * 4 + 3] = (vs[k] and 0xFF).toByte()
        }
        return out
    }

    // ── 随机（可注入，便于用 Python 的确定性向量做回归）──────────

    interface RandomSource {
        fun next(): Double
        fun nextInt(from: Int, until: Int): Int
    }

    class KotlinRandom(private val random: kotlin.random.Random = kotlin.random.Random.Default) : RandomSource {
        override fun next(): Double = random.nextDouble()
        override fun nextInt(from: Int, until: Int): Int = random.nextInt(from, until)
    }

    /** 12 字节随机前缀：每 4 字节由 2 个随机数拼出来，位掩码与浏览器一致 */
    fun randomBytes(length: Int, rnd: RandomSource): String {
        val sb = StringBuilder()
        repeat(length) {
            val rd = (rnd.next() * 10000).toInt()
            sb.append((((rd and 255) and 170) or 1).toChar())
            sb.append((((rd and 255) and 85) or 2).toChar())
            sb.append((((rd ushr 8) and 170) or 5).toChar())
            sb.append((((rd ushr 8) and 85) or 40).toChar())
        }
        return sb.toString()
    }

    /** 生成与浏览器 `navigator` 一致的指纹串（只供哈希用，取值本身不重要） */
    fun fingerprint(rnd: RandomSource): String {
        val innerW = rnd.nextInt(1024, 1921)
        val innerH = rnd.nextInt(768, 1081)
        val outerW = innerW + rnd.nextInt(24, 33)
        val outerH = innerH + rnd.nextInt(75, 91)
        val sizeW = rnd.nextInt(1024, 1921)
        val sizeH = rnd.nextInt(768, 1081)
        val availW = rnd.nextInt(1280, 1921)
        val availH = rnd.nextInt(800, 1081)
        val screenY = if (rnd.nextInt(0, 2) == 0) 0 else 30
        return "$innerW|$innerH|$outerW|$outerH|0|$screenY|0|0|$sizeW|$sizeH|" +
            "$availW|$availH|$innerW|$innerH|24|24|Win32"
    }

    /**
     * 把 RC4 输出的字节当成"字符码"（Latin-1 视角下的字符），供自定义 Base64 使用。
     * Python 版是 `to_ord_str(rc4(...))`，即每个字节变成一个字符。
     */
    private fun bytesAsCharString(bytes: ByteArray): String =
        String(CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() })

    /**
     * 自定义 Base64（6 位一组，`=` 补齐）。与标准 Base64 只差字符表：
     * `alphabetIndex=0` 是最终输出的 s4 表，`1` 是 UA 预处理用的 s3 表。
     */
    fun base64Encode(input: String, alphabetIndex: Int = 0): String {
        val alphabet = if (alphabetIndex == 1) ALPHABET1 else ALPHABET0
        val bytes = IntArray(input.length) { input[it].code and 0xFF }
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or b
            bits += 8
            while (bits >= 6) {
                bits -= 6
                sb.append(alphabet[(buffer shr bits) and 0x3F])
            }
        }
        if (bits > 0) sb.append(alphabet[(buffer shl (6 - bits)) and 0x3F])
        while (sb.length % 4 != 0) sb.append('=')
        return sb.toString()
    }

    // ── 混淆 / 编码 ─────────────────────────────────────────────

    private fun transformBytes(values: List<Int>): String {
        val big = BIG_ARRAY.copyOf()
        val chars = IntArray(values.size) { values[it] and 0xFFFF }
        val sb = StringBuilder(chars.size)
        var indexB = big[1]
        var initial = 0
        var valueE = 0
        for (index in chars.indices) {
            val sum: Int
            if (index == 0) {
                initial = big[indexB]
                sum = indexB + initial
                big[1] = initial
                big[indexB] = indexB
            } else {
                sum = initial + valueE
            }
            val modSum = sum % big.size
            val valueF = big[modSum]
            sb.append((chars[index] xor valueF).toChar())

            val slot = (index + 2) % big.size
            valueE = big[slot]
            val nextSum = (indexB + valueE) % big.size
            initial = big[nextSum]
            big[nextSum] = big[slot]
            big[slot] = initial
            indexB = nextSum
        }
        return sb.toString()
    }

    private fun encode(input: String, alphabetIndex: Int = 0): String {
        val alphabet = ALPHABET0
        val chars = IntArray(input.length) { input[it].code }
        val sb = StringBuilder()
        var i = 0
        while (i < chars.size) {
            var n = 0
            if (i + 2 < chars.size) {
                n = (chars[i] shl 16) or (chars[i + 1] shl 8) or chars[i + 2]
            } else if (i + 1 < chars.size) {
                n = (chars[i] shl 16) or (chars[i + 1] shl 8)
            } else {
                n = chars[i] shl 16
            }
            var j = 18
            val masks = intArrayOf(0xFC0000, 0x03F000, 0x0FC0, 0x3F)
            for (k in masks) {
                if (j == 6 && i + 1 >= chars.size) break
                if (j == 0 && i + 2 >= chars.size) break
                sb.append(alphabet[((n and k) shr j) and 0x3F])
                j -= 6
            }
            i += 3
        }
        while (sb.length % 4 != 0) sb.append('=')
        return sb.toString()
    }

    private fun rc4(key: ByteArray, plaintext: String): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) % 256
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(plaintext.length)
        var a = 0
        var b = 0
        for (idx in plaintext.indices) {
            a = (a + 1) % 256
            b = (b + s[a]) % 256
            val t = s[a]; s[a] = s[b]; s[b] = t
            val k = s[(s[a] + s[b]) % 256]
            out[idx] = (plaintext[idx].code xor k).toByte()
        }
        return out
    }
}
