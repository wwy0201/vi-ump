package com.vi.ump.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.vi.ump.model.ImageDecryptSpec
import com.vi.ump.query.CryptoCodec
import com.vi.ump.query.CryptoOptions
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * TV 图片通路：OkHttp 3.12 取字节 → 可选按源配置解密 → BitmapFactory 下采样。
 *
 * 4.4 的 Dalvik 堆很小，所以：
 *  - 字节缓存按"字节数"算容量（最多 12MB）；
 *  - Bitmap 缓存按 `getByteCount()` 算，最多占最大堆的 1/8；
 *  - 解码前先读 bounds 算 inSampleSize，绝不把整张 4000px 长图直接解进内存。
 */
object TvImageLoader {

    private val executor = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    private val bytes = object : LruCache<String, ByteArray>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private val bitmaps = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtLeast(8L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cachedBytes(key: String): ByteArray? = bytes.get(key)

    fun clear() {
        bytes.evictAll()
        bitmaps.evictAll()
    }

    /** 只预下载字节；真正显示时再解码，避免一次预载 N 张把堆撑爆。 */
    fun preload(
        url: String,
        headers: Map<String, String>,
        decrypt: ImageDecryptSpec?,
        fetcher: LegacyFetcher,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        val key = cacheKey(url, decrypt)
        if (bytes.get(key) != null) {
            onDone?.invoke(true)
            return
        }
        executor.execute {
            val raw = runCatching { runBlocking { fetcher.fetchBytes(url, headers) } }.getOrNull()
            val plain = raw?.let { decodeIfNeeded(it, decrypt) }
            if (plain != null && plain.isNotEmpty()) bytes.put(key, plain)
            if (onDone != null) main.post { onDone(plain != null) }
        }
    }

    fun load(
        view: ImageView,
        url: String?,
        headers: Map<String, String>,
        decrypt: ImageDecryptSpec?,
        fetcher: LegacyFetcher,
        reqWidth: Int,
        reqHeight: Int,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val tag = url.orEmpty() + "#" + reqWidth + "x" + reqHeight
        view.tag = tag
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            view.setImageDrawable(null)
            onResult?.invoke(false)
            return
        }
        val key = cacheKey(url, decrypt) + "@" + reqWidth + "x" + reqHeight
        bitmaps.get(key)?.let {
            view.setImageBitmap(it)
            onResult?.invoke(true)
            return
        }
        view.setImageDrawable(null)
        executor.execute {
            val byteKey = cacheKey(url, decrypt)
            val raw = bytes.get(byteKey) ?: runCatching {
                runBlocking { fetcher.fetchBytes(url, headers) }
            }.getOrNull()?.let { decodeIfNeeded(it, decrypt) }
            if (raw == null || raw.isEmpty()) {
                if (onResult != null) main.post { onResult(false) }
                return@execute
            }
            if (bytes.get(byteKey) == null) bytes.put(byteKey, raw)
            val bitmap = runCatching { decode(raw, reqWidth, reqHeight) }.getOrNull()
            if (bitmap != null) bitmaps.put(key, bitmap)
            main.post {
                if (bitmap != null && view.tag == tag) {
                    view.setImageBitmap(bitmap)
                    onResult?.invoke(true)
                } else {
                    onResult?.invoke(false)
                }
            }
        }
    }

    private fun cacheKey(url: String, spec: ImageDecryptSpec?): String =
        if (spec == null) url else url + "@" + spec.algo + "/" + spec.key.hashCode() + "/" + spec.iv.hashCode()

    private fun decodeIfNeeded(raw: ByteArray, spec: ImageDecryptSpec?): ByteArray? {
        if (spec == null) return raw
        return runCatching {
            val algo = CryptoCodec.normalizeAlgo(spec.algo) ?: return null
            val key = CryptoCodec.keyBytes(spec.key, spec.keyFormat) ?: return null
            if (key.size != CryptoCodec.KEY_SIZES[algo]) return null
            val iv = if (spec.iv.isBlank()) ByteArray(0) else CryptoCodec.keyBytes(spec.iv, spec.ivFormat) ?: return null
            val need = CryptoCodec.ivSize(algo) ?: 0
            if (need > 0 && iv.size != need) return null
            val options = CryptoOptions(algo, key, iv, spec.padding, spec.output)
            CryptoCodec.decryptBytes(raw, options)?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun decode(raw: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        if (reqWidth > 0 && reqHeight > 0) {
            while (bounds.outWidth / (sample * 2) >= reqWidth && bounds.outHeight / (sample * 2) >= reqHeight) {
                sample *= 2
            }
        } else {
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSide / (sample * 2) >= 2048) sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
    }
}
