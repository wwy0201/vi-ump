package com.vi.ump.query

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 通用加解密引擎的纯逻辑测试（docs/03 §3.3）。
 *
 * 用 JDK 自带的 `javax.crypto` 当"对立面"生成密文：引擎解出来必须与原文一致，
 * 这样测的是**编排**（编码层 / 补块 / 参数校验），不是密码学本身。
 */
class CryptoCodecTest {

    private val key16 = "f5d965df75336270"
    private val iv16 = "97b60394abc2fbe1"

    private fun opts(
        algo: String = "aes-128-cbc",
        key: ByteArray = key16.encodeToByteArray(),
        iv: ByteArray = iv16.encodeToByteArray(),
        padding: String = "pkcs7",
        output: String = "base64",
    ) = CryptoOptions(algo, key, iv, padding, output)

    private fun jce(algo: String, key: ByteArray, iv: ByteArray, padding: String, plain: ByteArray): ByteArray {
        val family = if (algo.startsWith("aes")) "AES" else "DESede"
        val mode = algo.substringAfterLast('-').uppercase()
        val jcePadding = if (padding == "pkcs7") "PKCS5Padding" else "NoPadding"
        val cipher = Cipher.getInstance("$family/$mode/$jcePadding")
        val keySpec = SecretKeySpec(key, family)
        if (mode == "ECB") cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        else if (mode == "GCM") cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        else cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }

    @Test
    fun base64RoundTrip() {
        val bytes = byteArrayOf(0, 1, 2, -1, -2, 127, -128)
        val enc = base64Encode(bytes, urlSafe = false)
        assertEquals("AAEC//5/gA==", enc)
        assertTrue(enc.endsWith("=="))
        assertEquals(bytes.toList(), base64Decode(enc, urlSafe = false)?.toList())
        // urlSafe：去 padding、用 -_ 字母表，解码端要能还原
        val url = base64Encode(bytes, urlSafe = true)
        assertTrue(!url.contains('='))
        assertEquals(bytes.toList(), base64Decode(url, urlSafe = true)?.toList())
        // 带换行的 PEM 体也要能解
        assertEquals(bytes.toList(), base64Decode("AAEC\n//5/gA==", urlSafe = false)?.toList())
    }

    @Test
    fun hexRoundTrip() {
        val bytes = byteArrayOf(0x09, 0x3D, 0xE3.toByte(), 0xB1.toByte())
        assertEquals("093de3b1", hexEncode(bytes))
        assertEquals(bytes.toList(), hexToBytes("0x093DE3B1")?.toList())
        assertNull(hexToBytes("zz"))
    }

    @Test
    fun aesCbcPkcs7() {
        val plain = "黄果剧场 · 第 12 集".encodeToByteArray()
        val cipher = jce("aes-128-cbc", key16.encodeToByteArray(), iv16.encodeToByteArray(), "pkcs7", plain)
        val text = base64Encode(cipher, urlSafe = false)
        assertEquals("黄果剧场 · 第 12 集", CryptoCodec.decrypt(text, opts()))
    }

    @Test
    fun aes256WithHexKey() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val iv = ByteArray(16) { (it + 11).toByte() }
        val plain = "aes-256 站点常用 hex 写 key".encodeToByteArray()
        val cipher = jce("aes-256-cbc", key, iv, "pkcs7", plain)
        val o = CryptoOptions("aes-256-cbc", key, iv, "pkcs7", "hex")
        assertEquals(
            "aes-256 站点常用 hex 写 key",
            CryptoCodec.decrypt(hexEncode(cipher), o),
        )
        // keyFormat=hex 时，32 字节密钥就是 64 个字符
        assertNotNull(CryptoCodec.keyBytes(hexEncode(key), "hex"))
    }

    @Test
    fun zeroPaddingTrimsTail() {
        val plain = "hello-zero".encodeToByteArray() + ByteArray(6) // 补到 16 字节，补的是 0
        val cipher = jce("aes-128-cbc", key16.encodeToByteArray(), iv16.encodeToByteArray(), "none", plain)
        val text = base64Encode(cipher, urlSafe = false)
        assertEquals("hello-zero", CryptoCodec.decrypt(text, opts(padding = "zero")))
        // none 口径不裁，尾部 0 会留在明文里（ASCII 上就是一串 \u0000）
        val raw = CryptoCodec.decrypt(text, opts(padding = "none"))
        assertNotNull(raw)
        assertTrue(raw!!.endsWith("\u0000"))
    }

    @Test
    fun gcmRoundTrip() {
        val iv = ByteArray(12) { (it + 3).toByte() }
        val plain = "gcm 认证模式".encodeToByteArray()
        val cipher = jce("aes-128-gcm", key16.encodeToByteArray(), iv, "none", plain)
        val o = CryptoOptions("aes-128-gcm", key16.encodeToByteArray(), iv, "none", "base64")
        assertEquals("gcm 认证模式", CryptoCodec.decrypt(base64Encode(cipher, false), o))
        // GCM 支持加密侧：往返一次
        val enc = CryptoCodec.encrypt("gcm 认证模式", o)
        assertEquals("gcm 认证模式", CryptoCodec.decrypt(enc!!, o))
    }

    @Test
    fun des3RoundTrip() {
        val key = ByteArray(24) { (it + 1).toByte() }
        val iv = ByteArray(8) { (it + 5).toByte() }
        val plain = "3des 老站点".encodeToByteArray()
        val cipher = jce("des3-cbc", key, iv, "pkcs7", plain)
        val o = CryptoOptions("des3-cbc", key, iv, "pkcs7", "base64")
        assertEquals("3des 老站点", CryptoCodec.decrypt(base64Encode(cipher, false), o))
    }

    @Test
    fun rejectsBrokenInput() {
        // 非法 base64
        assertNull(CryptoCodec.decrypt("!!!!", opts()))
        // 长度不是块长（NoPadding 口径）
        assertNull(CryptoCodec.decrypt(base64Encode(ByteArray(15), false), opts(padding = "none")))
        // 算法不认识
        assertNull(CryptoCodec.decrypt("AAAA", opts(algo = "sm4-cbc")))
    }

    @Test
    fun encryptThenDecryptBytes() {
        // 图片通路：字节进、字节出，不经过 output 编码层
        val o = optionsOfImage()
        val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(29) { it.toByte() }
        val cipher = jce("aes-128-cbc", o.key, o.iv, "pkcs7", plain)
        assertEquals(plain.toList(), CryptoCodec.decryptBytes(cipher, o)?.toList())
    }

    private fun optionsOfImage() = CryptoOptions(
        "aes-128-cbc", key16.encodeToByteArray(), iv16.encodeToByteArray(), "pkcs7", "raw",
    )

    @Test
    fun fingerprintStableAndHidesKey() {
        val a = opts()
        val b = opts()
        assertEquals(a.fingerprint, b.fingerprint)
        assertTrue(!a.fingerprint.contains(key16))
        // 换算法必须让缓存失效
        assertTrue(a.fingerprint != opts(algo = "aes-192-cbc", key = ByteArray(24)).fingerprint)
    }
}
