package com.vi.ump.tv

import android.content.Context
import android.util.Log
import okhttp3.Handshake
import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android 4.4 的 TLS 补齐。
 *
 * 4.4 的 SSLSocket 支持 TLS 1.2，但默认不启用；而且系统信任库较旧，
 * Let's Encrypt 的 ISRG Root X1/X2 不在其中，e-hentai 这类站点会
 * 报 “Trust anchor for certification path not found”。
 *
 * 这里用 Conscrypt 提供现代 TLS 实现，并在系统 TrustManager 之外
 * 叠加一份内置的 ISRG 根证书；只有系统校验失败时才尝试内置根。
 */
object TlsSupport {

    private const val TAG = "ViUmpTvTls"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var installed: Boolean = false
        private set

    fun install(context: Context) {
        appContext = context.applicationContext
        if (installed) return
        runCatching {
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }
            // 4.4 的 HttpsURLConnection 默认仍绑在开机时创建的系统 SSLContext（只给 TLS1.0），
            // 只装 Provider 不会生效。ExoPlayer 的 DefaultHttpDataSource 走 HttpsURLConnection，
            // https 的 m3u8 会 "sslv3 alert handshake failure"，必须显式设默认工厂。
            val (factory, _) = socketFactory()
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(factory)
            installed = true
        }.onFailure {
            Log.w(TAG, "Conscrypt 安装失败，回退系统 TLS：${it.message}")
        }
    }

    /** 自检用：把握手到的证书 subject/issuer 打到 logcat，便于判断缺哪个根 */
    fun logPeerChain(handshake: Handshake?) {
        handshake?.peerCertificates()?.forEach { cert ->
            val x509 = cert as? X509Certificate ?: return@forEach
            Log.i(TAG, "peer: ${x509.subjectX500Principal.name} <- ${x509.issuerX500Principal.name}")
        }
    }

    /** OkHttp 用的 SSLSocketFactory + 合并后的 TrustManager */
    fun socketFactory(): Pair<javax.net.ssl.SSLSocketFactory, X509TrustManager> {
        val systemTrust = systemTrustManager()
        val bundledTrust = bundledTrustManager(appContext)
        val trustManager: X509TrustManager = if (bundledTrust != null) {
            CompositeTrustManager(systemTrust, bundledTrust)
        } else {
            systemTrust
        }

        val context = runCatching {
            SSLContext.getInstance("TLS", "Conscrypt")
        }.getOrElse {
            // 没有 Conscrypt 时退回系统 TLSv1.2；4.4 上仍可能握手失败
            SSLContext.getInstance("TLSv1.2")
        }
        context.init(null, arrayOf<TrustManager>(trustManager), null)
        return context.socketFactory to trustManager
    }

    private fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** 读取 res/raw 里的 ISRG Root X1/X2；读不到就返回 null，继续用系统信任库 */
    private fun bundledTrustManager(context: Context?): X509TrustManager? {
        if (context == null) return null
        return runCatching {
            val factory = CertificateFactory.getInstance("X.509")
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            intArrayOf(
                R.raw.isrg_root_x1,
                R.raw.isrg_root_x2,
                R.raw.gts_root_r4,
                // 抖音：*.douyin.com ← RapidSSL TLS RSA CA G1 ← DigiCert Global Root G2（4.4 系统库里没有）
                R.raw.digicert_global_root_g2,
                R.raw.digicert_global_root_ca,
            ).forEachIndexed { index, resId ->
                // 逐个加载：4.4 上万一 X2 的 EC 证书解析失败，也不能拖累 X1
                runCatching {
                    context.resources.openRawResource(resId).use { input ->
                        val cert = factory.generateCertificate(input)
                        keyStore.setCertificateEntry("vi-ump-extra-root-$index", cert)
                    }
                }.onFailure {
                    Log.w(TAG, "内置根证书 $resId 加载失败：${it.message}")
                }
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        }.onFailure {
            Log.w(TAG, "内置根证书加载失败，继续用系统信任库：${it.message}")
        }.getOrNull()
    }

    private class CompositeTrustManager(
        private val system: X509TrustManager,
        private val extra: X509TrustManager,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            system.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (systemFailure: CertificateException) {
                try {
                    extra.checkServerTrusted(chain, authType)
                } catch (extraFailure: CertificateException) {
                    extraFailure.addSuppressed(systemFailure)
                    throw extraFailure
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            system.acceptedIssuers + extra.acceptedIssuers
    }
}
