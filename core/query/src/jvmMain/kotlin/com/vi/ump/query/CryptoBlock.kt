package com.vi.ump.query

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM（桌面）侧块加密。与 `androidMain` 的同名文件内容一致——
 * 两端都用 `javax.crypto`，不引第三方加密库。
 */
internal actual fun blockCrypt(
    algo: String,
    key: ByteArray,
    iv: ByteArray,
    input: ByteArray,
    encrypt: Boolean,
    padding: String,
): ByteArray? {
    val family = when {
        algo.startsWith("aes-") -> "AES"
        algo.startsWith("des3-") -> "DESede"
        algo.startsWith("des-") -> "DES"
        else -> return null
    }
    val mode = algo.substringAfterLast('-').uppercase()
    if (mode != "CBC" && mode != "ECB" && mode != "CTR" && mode != "GCM") return null

    val expectedKey = CryptoCodec.KEY_SIZES[algo] ?: return null
    if (key.size != expectedKey) return null
    val ivNeed = CryptoCodec.ivSize(algo) ?: return null
    if (ivNeed > 0 && iv.size != ivNeed) return null

    val jcePadding = when {
        mode == "CTR" || mode == "GCM" -> "NoPadding"
        padding == "pkcs7" -> "PKCS5Padding"
        else -> "NoPadding"
    }

    if (!encrypt && jcePadding == "NoPadding" && mode != "GCM") {
        val block = if (family == "AES") 16 else 8
        if (input.isEmpty() || input.size % block != 0) return null
    }

    return try {
        val cipher = Cipher.getInstance("$family/$mode/$jcePadding")
        val keySpec = SecretKeySpec(key, family)
        val op = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        when {
            mode == "ECB" -> cipher.init(op, keySpec)
            mode == "GCM" -> cipher.init(op, keySpec, GCMParameterSpec(128, iv))
            else -> cipher.init(op, keySpec, IvParameterSpec(iv))
        }
        cipher.doFinal(input)
    } catch (e: Exception) {
        null
    }
}
