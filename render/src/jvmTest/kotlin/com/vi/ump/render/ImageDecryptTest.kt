package com.vi.ump.render

import com.vi.ump.model.ImageDecryptSpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 密文图片通路的纯逻辑测试（不需要网络、不需要真图）。
 *
 * 覆盖三处最容易写错的地方：
 * 1. AES 参数——key/iv 是 **UTF-8 字节**，不是 hex 解码；
 * 2. `padding=none` 的尾部截断（站点按密文长度近似 PKCS7，尾部有 1–15 个废字节）；
 * 3. 明文嗅探——只认 PNG/GIF/WEBP 的**长魔数**，密文不能被误判成图。
 */
class ImageDecryptTest {

    private val key = "f5d965df75336270"
    private val iv = "97b60394abc2fbe1"

    private fun spec(padding: String) = ImageDecryptSpec(
        algo = "aes-128-cbc",
        key = key,
        iv = iv,
        padding = padding,
    )

    private fun encrypt(plain: ByteArray, transform: String): ByteArray {
        val c = Cipher.getInstance(transform)
        c.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.encodeToByteArray(), "AES"),
            IvParameterSpec(iv.encodeToByteArray()),
        )
        return c.doFinal(plain)
    }

    @Test
    fun decryptsPkcs7Cipher() {
        val plain = ByteArray(64) { (it * 7).toByte() }
        val cipher = encrypt(plain, "AES/CBC/PKCS5Padding")
        assertContentEquals(plain, decryptImage(cipher, spec("pkcs7")))
    }

    @Test
    fun decryptsNoPaddingAndTrimsTail() {
        // 模拟站点：密文长度补到 16 的倍数，尾部留 3 个"填充垃圾"
        val payload = ByteArray(61) { (it + 1).toByte() }
        val padded = payload + byteArrayOf(0x01, 0x02, 0x03)
        val cipher = encrypt(padded, "AES/CBC/NoPadding")
        // 桩：把明文做成"以 FF D9 结尾"的假 JPEG，验证按 EOI 截断
        val withEoi = payload.copyOfRange(0, payload.size - 2) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val cipher2 = encrypt(withEoi + byteArrayOf(0x01, 0x02, 0x03), "AES/CBC/NoPadding")
        assertEquals(0, cipher.size % 16)
        val out = decryptImage(cipher2, spec("none"))
        assertTrue(out != null)
        assertEquals(withEoi.size, out.size)
    }

    @Test
    fun rejectsBadSpec() {
        // key 长度不对：必须在解密前就被拒（配置期已拦，这里兜运行期）
        assertNull(decryptImage(ByteArray(16), spec("pkcs7").copy(key = "short")))
        assertNull(decryptImage(ByteArray(15), spec("none")))
        assertNull(decryptImage(ByteArray(16), spec("pkcs7").copy(algo = "aes-256-cbc")))
    }

    @Test
    fun sniffsPlainImages() {
        assertTrue(looksLikePlainImage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)))
        assertTrue(looksLikePlainImage("GIF89a........".encodeToByteArray()))
        val webp = "RIFF".encodeToByteArray() + ByteArray(4) + "WEBP".encodeToByteArray() + ByteArray(4)
        assertTrue(looksLikePlainImage(webp))
        // 密文（黄果首块实测 09 3d e3 b1…）不能被当成图
        assertFalse(looksLikePlainImage(byteArrayOf(0x09, 0x3D, 0xE3.toByte(), 0xB1.toByte()) + ByteArray(20)))
        // 太短的输入一律不当图（避免半截响应被送去解码）
        assertFalse(looksLikePlainImage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    @Test
    fun sniffsDecryptedJpeg() {
        // 解密的产物最常见的正是 JPEG：三字节 FF D8 FF 才算数，两字节不算
        assertTrue(looksLikeDecodedImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(64)))
        assertFalse(looksLikeDecodedImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        // 解错了（比如 key 轮换）产物就是随机字节，不能当图送进解码器
        assertFalse(looksLikeDecodedImage(byteArrayOf(0x09, 0x3D, 0xE3.toByte(), 0xB1.toByte()) + ByteArray(64)))
        // PNG/GIF/WEBP 也走同一口径
        assertTrue(looksLikeDecodedImage("GIF89a........".encodeToByteArray()))
    }

    @Test
    fun trimsToLastJpegEoi() {
        val body = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(10) { 0x41 } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val withGarbage = body + byteArrayOf(0x00, 0x11, 0x22)
        assertEquals(body.size, trimJpegTail(withGarbage).size)
        // 没有 EOI 就原样返回（让解码器自己去判断）
        assertEquals(5, trimJpegTail(ByteArray(5) { 0x42 }).size)
    }
}
