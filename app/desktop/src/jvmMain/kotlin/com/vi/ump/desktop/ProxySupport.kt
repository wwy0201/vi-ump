package com.vi.ump.desktop

import com.vi.ump.feature.SourceEntry
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 桌面端的代理策略：系统代理做默认通路，配置声明的域名走直连。
 *
 * 为什么需要分流（实测得出的结论，不是猜的）：
 *  - Windows 上 JVM 默认不看系统代理 → 打开 `java.net.useSystemProxies` 才能连上 youtube.com；
 *  - 但打开之后，**国内站点会被强行塞进代理隧道**，腾讯 CDN 会直接断开 TLS 握手
 *    （`Remote host terminated the handshake`）；
 *  - 同一个请求如果是直连、或由 JDK 自带 HttpClient 走同一代理，都是 200 —— 说明不是 TLS 版本或 UA 的问题，
 *    而是"经 HTTP 代理隧道的这条链路"本身不被接受；
 *  - `http.nonProxyHosts` 在 `useSystemProxies=true` 下**不生效**，所以只能自己包一层 [ProxySelector]。
 *
 * 域名规则来自配置的 `runtime.proxyBypass`，不写死在代码里——站点差异属于配置。
 */
object ProxySupport {

    fun installFor(sources: List<SourceEntry>) {
        install(DevSourceLoader.bypassHosts(sources))
    }

    fun install(bypassSuffixes: List<String>) {
        System.setProperty("java.net.useSystemProxies", "true")
        if (bypassSuffixes.isEmpty()) return

        val system: ProxySelector = ProxySelector.getDefault() ?: return
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                val host = uri.host ?: return system.select(uri)
                val direct = bypassSuffixes.any { suffix ->
                    host.equals(suffix, ignoreCase = true) || host.endsWith(".$suffix", ignoreCase = true)
                }
                return if (direct) listOf(Proxy.NO_PROXY) else system.select(uri)
            }

            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
                system.connectFailed(uri, sa, ioe)
            }
        })
    }

    /** 调试用：打印某个 URL 最终走的是代理还是直连 */
    fun describe(url: String): String = runCatching {
        URI(url).let { uri ->
            ProxySelector.getDefault().select(uri).joinToString { p ->
                if (p.type() == Proxy.Type.DIRECT) "DIRECT" else "${p.type()} ${(p.address() as? InetSocketAddress)?.hostString}"
            }
        }
    }.getOrDefault("<unparsable>")
}
