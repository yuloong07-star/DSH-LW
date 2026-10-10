package io.github.yuloong07star.luwi.lock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 主人交给本应用的那一份秘密: 解锁密码, 或者图案那一条折线 (批次 5, 需求 7)
 *
 * **它只以密文落盘**: 明文在 [save] 里过一手就没了, 读的时候只在内存里解出来给重放用。这是这一批
 * 最敏感的一条, 所以做成三个地方一起守: 密钥在 Android Keystore 里 (应用删了就没了, 也不在任何备份
 * 里), 密文放应用私有的 SharedPreferences, 而**日志里一个字都不许出现** (谁都不许把 content 打出来)
 *
 * 与步骤序列分开放 (那个在 `$DSH_HOME/lock/unlock.json`, 明文): 序列要说得出"第几步是密码", 而
 * 密码本身不能在那份文件里 —— 那正是这一批"明文不落盘"的落点。键盘上那几个点击的坐标同理: 坐标就是
 * 密码, 所以它们要么进这里, 要么录的时候就被丢掉 (见 [LockSteps.settleTaken])
 */
@Serializable
internal data class LockSecretData(
    /** [TEXT] = 一串字符 (PIN / 密码), [PATH] = 图案那一条折线 */
    val kind: String = TEXT,
    val text: String = "",
    /** 图案的比例坐标, 与步骤里那一套是同一个单位 */
    val points: List<List<Float>> = emptyList(),
) {
    /** 给人看的一句, **不含内容** */
    val describe: String
        get() = when (kind) {
            PATH -> "a pattern of ${points.size} points"
            else -> if (text.isEmpty()) "nothing" else "a password of ${text.length} characters"
        }

    companion object {
        const val TEXT = "text"
        const val PATH = "path"
    }
}

/**
 * 密文的信封: `base64(iv):base64(ciphertext)`
 *
 * 抽出来是因为它是**唯一能在 JVM 上测的那一半** —— `AndroidKeyStore` 在单元测试里根本不存在, 而
 * "拼起来再拆开还是原来那两个数组"这条判据值得量 (见 `LockSecretTest`)
 */
internal object SecretEnvelope {

    fun pack(iv: ByteArray, ciphertext: ByteArray): String =
        Base64.getEncoder().encodeToString(iv) + ":" +
            Base64.getEncoder().encodeToString(ciphertext)

    /** 拆不开就回 null: 一份被改坏的信封不该让重放拿到半截密钥材料 */
    fun unpack(raw: String?): Pair<ByteArray, ByteArray>? {
        val text = raw?.trim().orEmpty()
        val at = text.indexOf(':')
        if (at <= 0 || at == text.length - 1) return null
        return runCatching {
            val iv = Base64.getDecoder().decode(text.substring(0, at))
            val body = Base64.getDecoder().decode(text.substring(at + 1))
            if (iv.isEmpty() || body.isEmpty()) null else iv to body
        }.getOrNull()
    }
}

/**
 * 那一份秘密的读写
 *
 * 密钥一建就不再换 (换等于把存过的那一份扔掉), `setRandomizedEncryptionRequired(true)` 让每次加密
 * 都带一个新的 iv —— GCM 拿同一个 iv 加两次是能把密钥用废的
 */
internal object LockSecret {

    private const val TAG = "LwLock"

    private const val STORE = "luwi"
    private const val KEY = "lock-secret"

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "lw-unlock"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private val json = Json { ignoreUnknownKeys = true }

    /** 存过没有 (只问"有没有", 不解密) */
    fun has(context: Context): Boolean = prefs(context).getString(KEY, null)?.isNotBlank() == true

    /**
     * 把这一份秘密加密存下来
     *
     * @return 存不成的那句人话, 成了就是 null
     */
    fun save(context: Context, data: LockSecretData): String? = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = json.encodeToString(data).toByteArray()
        val sealed = SecretEnvelope.pack(cipher.iv, cipher.doFinal(body))
        prefs(context).edit().putString(KEY, sealed).commit()
        null
    } catch (error: Throwable) {
        // 只说类型, 不带上内容: 这一条的异常信息里可能就有正在加密的那串字符
        Log.w(TAG, "the unlock secret could not be stored: ${error.javaClass.simpleName}")
        "the secret could not be encrypted (${error.javaClass.simpleName}): this device refused a" +
            " Keystore key, so the password was not stored"
    }

    /** 读出来 (解不开就是 null, 同时回一句为什么) */
    fun load(context: Context): Pair<LockSecretData?, String?> {
        val raw = prefs(context).getString(KEY, null)
        if (raw.isNullOrBlank()) return null to null
        val (iv, body) = SecretEnvelope.unpack(raw)
            ?: return null to "the stored secret is not readable any more (it was not a sealed value)"
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            val plain = cipher.doFinal(body)
            val data = runCatching { json.decodeFromString<LockSecretData>(String(plain)) }.getOrNull()
            if (data == null) null to "the stored secret did not decode" else data to null
        } catch (error: Throwable) {
            null to ("the stored secret could not be decrypted (${error.javaClass.simpleName}):" +
                " the Keystore key is gone, so record the unlock again")
        }
    }

    /** 清掉: 密文与那把密钥一起删 (只删密文等于留一把再也用不上的钥匙) */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).commit()
        runCatching {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
        }.onFailure { Log.w(TAG, "the Keystore key could not be deleted: ${it.message}") }
    }

    /** 那把 AES 密钥, 没有就建一把 (不要求用户认证: 无人值守那一档就要跑得起来) */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
}
