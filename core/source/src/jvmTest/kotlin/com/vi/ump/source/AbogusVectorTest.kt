package com.vi.ump.source

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 回归向量：用 Python（f2 的 `abogus.py`）+ 固定时间戳 + 固定 LCG 随机源生成，
 * Kotlin 移植版必须逐字节一致，否则签出来的 a_bogus 会被抖音判成 `blocked`。
 */
class AbogusVectorTest {

    private class Lcg(private var s: Long = 12345) : Abogus.RandomSource {
        private fun step(): Double {
            s = (s * 1103515245L + 12345L) % 2147483648L
            return s.toDouble() / 2147483648.0
        }

        override fun next(): Double = step()

        override fun nextInt(from: Int, until: Int): Int =
            from + (step() * (until - from)).toInt()
    }

    private val url = "device_platform=webapp&aid=6383&channel=channel_pc_web&pc_client_type=1" +
        "&version_code=190500&version_name=19.5.0&cookie_enabled=true" +
        "&screen_width=1920&screen_height=1080&browser_language=zh-CN" +
        "&browser_platform=Win32&browser_name=Edge&browser_version=130.0.0.0" +
        "&browser_online=true&engine_name=Blink&engine_version=130.0.0.0" +
        "&os_name=Windows&os_version=10&cpu_core_num=12&device_memory=8&platform=PC" +
        "&downlink=10&effective_type=4g&round_trip_time=50&count=20" +
        "&refresh_index=1&video_type_select=1&aweme_pc_rec_raw=1&pull_type=0"

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"

    private val fp = "1512|937|1540|1015|0|30|0|0|1512|982|1512|982|1512|937|24|24|Win32"

    @Test
    fun sm3MatchesKnownDigest() {
        // "abc" 的标准 SM3 摘要（GB/T 32905-2016 附录 A.1）
        val hex = Abogus.sm3("abc".toByteArray(Charsets.UTF_8))
            .joinToString("") { ((it.toInt() and 0xFF).toString(16).padStart(2, '0')) }
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0", hex)
    }

    @Test
    fun matchesPythonVector() {
        val expected = "EJW0/5wdpnLsXjSb5RxLfY3q6XB3YQie0SVkMD2fgnfPQ639HMOE9exoo7UvbV8jF" +
            "T/nIeLjy4hbT3KkrQCr8qwf9Wvx/2CZm640t-Ph5xSSs1feeLmsrTJO4kj3SFFB57NIxORkqwQc" +
            "F8RmA2re-wHvPjojx2f39gaK"
        val actual = Abogus.sign(url, ua, fp, 1795498500000L, Lcg())
        assertEquals(expected, actual)
    }
}
