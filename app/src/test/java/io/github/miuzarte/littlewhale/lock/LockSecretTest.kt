package io.github.miuzarte.littlewhale.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 密文信封那一半 (批次 5)
 *
 * `AndroidKeyStore` 在 JVM 单测里根本不存在, 所以能在这里量的是**信封本身**: 拼起来再拆开还是原来
 * 那两个数组, 而一份被改坏的信封要回 null 而不是半个数组。真正"加解密走得通不通"在设备上量
 */
class LockSecretTest {

    @Test
    fun `an envelope survives a round trip`() {
        val iv = ByteArray(12) { it.toByte() }
        val body = "the ciphertext".toByteArray()
        val packed = SecretEnvelope.pack(iv, body)
        val (backIv, backBody) = SecretEnvelope.unpack(packed)!!
        assertEquals(iv.toList(), backIv.toList())
        assertEquals(body.toList(), backBody.toList())
    }

    @Test
    fun `a broken envelope is refused rather than half read`() {
        assertNull(SecretEnvelope.unpack(null))
        assertNull(SecretEnvelope.unpack(""))
        assertNull(SecretEnvelope.unpack("no separator here"))
        assertNull(SecretEnvelope.unpack(":"))
        assertNull(SecretEnvelope.unpack("AAAA:"))
        assertNull(SecretEnvelope.unpack(":AAAA"))
        assertNull(SecretEnvelope.unpack("!!!:!!!"))
    }

    @Test
    fun `what the secret says about itself never includes the secret`() {
        val password = LockSecretData(kind = LockSecretData.TEXT, text = "1234")
        assertEquals("a password of 4 characters", password.describe)
        val pattern = LockSecretData(
            kind = LockSecretData.PATH,
            points = listOf(listOf(0.1f, 0.2f), listOf(0.3f, 0.4f)),
        )
        assertEquals("a pattern of 2 points", pattern.describe)
        assertEquals("nothing", LockSecretData().describe)
    }
}
