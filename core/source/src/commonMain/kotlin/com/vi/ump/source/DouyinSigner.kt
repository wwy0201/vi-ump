package com.vi.ump.source

import com.vi.ump.storage.AppSettings
import com.vi.ump.storage.SettingsKeys
import com.vi.ump.storage.createAppSettings
import kotlin.random.Random

/**
 * 抖音请求签名（`sign: "douyin"`）。
 *
 * 三件事必须在发请求前一起做，少一样都会拿到 7 字节的 `blocked`：
 * 1. `a_bogus` —— query 串的签名（[Abogus]）；
 * 2. `msToken` cookie —— 107 位随机串，每次请求换新的；
 * 3. `ttwid` cookie —— 设备令牌，向 `ttwid.bytedance.com` 注册一次后缓存复用。
 *
 * ttwid 拿不到时退回一个内置常量（一年有效），总比整页打不开好。
 */
internal object DouyinSigner {

    private const val REGISTER_URL = "https://ttwid.bytedance.com/ttwid/union/register/"
    private const val REGISTER_BODY =
        """{"region":"cn","aid":1768,"needFid":false,"service":"www.ixigua.com",""" +
            """"migrate_info":{"ticket":"","source":"node"},"cbUrlProtocol":"https","union":true}"""

    /** 兜底 ttwid（2026-11 实测可用；注册接口失败时用） */
    private const val FALLBACK_TTWID =
        "1%7CuU8XkW_dG0MznF5TL26fWAxfSjbuSfWfT3YaOaXJ6KQ%7C1795498418%7C" +
            "f093bc3fb372b657e43fea1f4b3631acb98ad6fd58c89f89eb6f009ef99b1844"

    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    private var ttwid: String? = null

    /** 落盘用的设置（同进程只建一次；建不起来就退回纯内存缓存） */
    private val settings: AppSettings? by lazy {
        runCatching { createAppSettings() }.getOrNull()
    }

    private val rnd = Abogus.KotlinRandom(Random.Default)
    private val fingerprint: String by lazy { Abogus.fingerprint(rnd) }

    suspend fun sign(
        url: String,
        headers: Map<String, String>,
        fetcher: Fetcher,
    ): Pair<String, Map<String, String>> {
        val q = url.indexOf('?')
        val base = if (q >= 0) url.substring(0, q) else url
        val query = if (q >= 0) url.substring(q + 1) else ""
        // 复签时先摘掉上一次的 a_bogus，否则它会进哈希
        val clean = query.split('&').filter { it.isNotBlank() && !it.startsWith("a_bogus=") }
        val params = clean.joinToString("&")

        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        val sign = Abogus.sign(params, ua, fingerprint, nowMillis(), rnd)
        val newUrl = if (params.isEmpty()) "$base?a_bogus=${sign.percentEncode()}"
        else "$base?$params&a_bogus=${sign.percentEncode()}"

        val cookie = buildCookie(headers, ensureTtwid(fetcher))
        return newUrl to (headers - headers.keys.filter { it.equals("Cookie", true) } + ("Cookie" to cookie))
    }

    private fun buildCookie(headers: Map<String, String>, ttwid: String): String {
        val old = headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value.orEmpty()
        val kept = old.split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("msToken=") || it.startsWith("ttwid=") }
        val ms = "msToken=" + randomToken(107)
        return (kept + ms + "ttwid=$ttwid").joinToString("; ")
    }

    private fun randomToken(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALNUM[rnd.nextInt(0, ALNUM.length)]) }
        return sb.toString()
    }

    /** 注册一次设备令牌并落盘；失败就用兜底常量，缓存跨请求、跨进程复用 */
    private suspend fun ensureTtwid(fetcher: Fetcher): String {
        ttwid?.let { return it }
        // 进程重启后先看盘上有没有：抖音的 ttwid 一年有效，没必要每次冷启动都重新注册
        settings?.getString(SettingsKeys.DOUYIN_TTWID)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                println("[dy] ttwid cached len=${it.length}")
                ttwid = it
                return it
            }
        val fetched = runCatching {
            fetcher.fetch(
                REGISTER_URL,
                method = "POST",
                requestHeaders = mapOf("Content-Type" to "application/json"),
                body = REGISTER_BODY,
                bodyType = com.vi.ump.model.BodyType.JSON,
            )
        }.onFailure {
            println("[dy] ttwid register failed: ${it.message}")
        }.getOrNull()

        val setCookie = fetched?.headers?.entries
            ?.firstOrNull { it.key.equals("Set-Cookie", true) }?.value
            .orEmpty()
        val found = setCookie.split(',', ';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("ttwid=") }
            ?.removePrefix("ttwid=")
            ?.takeIf { it.isNotBlank() }

        val value = found ?: FALLBACK_TTWID
        println("[dy] ttwid ${if (found != null) "registered" else "fallback"} len=${value.length}")
        // 兜底常量也落盘：下一次冷启动别再白打一次注册请求
        runCatching { settings?.putString(SettingsKeys.DOUYIN_TTWID, value) }
        ttwid = value
        return value
    }
}
