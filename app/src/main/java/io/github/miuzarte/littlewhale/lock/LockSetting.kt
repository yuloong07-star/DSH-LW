package io.github.miuzarte.littlewhale.lock

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.miuzarte.littlewhale.host.DshHost
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「锁屏」那一段的两条开关与录下来的那一条序列 (批次 5)
 *
 * 与别的偏好同一个文件 (`littlewhale` 那一份 SharedPreferences), 而后面的东西 (解锁那一份秘密) 不在
 * 这里 —— 它在 [LockSecret] 里, 那是唯一一处加密的存法
 *
 * [revision] 是给设置页的刷新信号: 页面读它一次, 于是任何一处改完都能让那一段重画
 */
internal object LockSetting {

    private const val STORE = "littlewhale"

    /** 叫醒时点亮屏幕 (缺省开: 喊一声屏幕不亮这件事本身就是坏掉的功能) */
    private const val WAKE_SCREEN = "lock-wake-screen"

    /** 唤醒时自动解锁 (缺省关: 这是全版唯一一条"做错了会很糟"的功能) */
    private const val AUTO_UNLOCK = "lock-auto-unlock"

    /**
     * 注入解锁 (缺省关): 解锁时不重放录制的动作, 只把密码注入进去
     *
     * 2026-10-09 在真机上量出来的 (vivo V2417A / Android 16): 亮屏之后一个手势都不发, 只把那六位数字
     * 注入进去再回车, 连着四次都到了桌面 —— 原来那条"11 点笔画 + 5 段滑动"的录制是多余的。省掉的不只
     * 是手势本身, 还有每一步之间那 [LockReplay.STEP_SETTLE_MS] 与每条滑动的时长, 所以这一条明显更快
     *
     * 它依赖"这台机器的锁屏收注入的按键", 所以缺省关着 —— 不认的机器与图案锁继续走录制的动作
     */
    private const val INJECT_UNLOCK = "lock-inject-unlock"

    /** 录下来的那一条序列 (明文, 密码不在里面) */
    private const val STEPS = "lock-steps"

    /** 什么时候录的 (给人看的时间) */
    private const val STEPS_AT = "lock-steps-at"

    /** 连着失败了几次: 到 [LockTries.LIMIT] 就停 */
    private const val TRIES = "lock-tries"

    /** 序列落在哪儿: `$DSH_HOME/lock/unlock.json` (与宿主插件商量的那一份共享事实) */
    const val DIR = "lock"
    const val FILE = "unlock.json"

    var revision: Int by mutableStateOf(0)
        private set

    /** 这一批有没有在用 (设置页那一段的摘要要用) */
    fun wakeScreen(context: Context): Boolean = prefs(context).getBoolean(WAKE_SCREEN, true)

    fun autoUnlock(context: Context): Boolean = prefs(context).getBoolean(AUTO_UNLOCK, false)

    fun injectUnlock(context: Context): Boolean = prefs(context).getBoolean(INJECT_UNLOCK, false)

    fun steps(context: Context): List<LockStep> = LockSteps.decode(readSteps(context))

    fun recordedAt(context: Context): Long = prefs(context).getLong(STEPS_AT, 0L)

    fun tries(context: Context): Int = prefs(context).getInt(TRIES, 0)

    /** 录过没有: 序列非空, 或者有一份秘密 (只有密码、还没录手势那一档) */
    fun recorded(context: Context): Boolean = steps(context).isNotEmpty()

    /**
     * 密码格里有没有一段**打得出来的**密码
     *
     * 「注入解锁」与「唤醒时自动解锁」两道门都要问这一句: 有了密码就够, 不一定要录过手势 (见
     * [LockSteps.injected]) —— 这就是「注入密码」那一行能单独用起来的原因
     */
    fun typable(context: Context): Boolean {
        val (secret, _) = LockSecret.load(context)
        return secret != null && secret.kind == LockSecretData.TEXT && secret.text.isNotEmpty()
    }

    fun setWakeScreen(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(WAKE_SCREEN, on).apply()
        LockReplay.forgetWarnings()
        revision += 1
    }

    /**
     * 拨自动解锁这一条
     *
     * **打开之前先看有没有可放的东西**: 没有录过就打开, 结果只是"喊了没反应", 那是最难查的一档 ——
     * 所以这里让它开不起来, 并回一句为什么
     */
    fun setAutoUnlock(context: Context, on: Boolean): String? {
        if (on && !recorded(context) && !(injectUnlock(context) && typable(context))) {
            return "there is nothing to unlock with yet: record the unlock, or set the password" +
                " under \"inject password\" and turn injection on (both of those rows are above" +
                " this switch)"
        }
        prefs(context).edit().putBoolean(AUTO_UNLOCK, on).apply()
        if (on) setTries(context, 0)
        revision += 1
        return null
    }

    /**
     * 拨注入解锁这一条
     *
     * **没有一段打得出来的密码就没有可注入的东西**: 图案那一条折线走的是另一条路 (手势), 注入对它没有
     * 意义 —— 所以与 [setAutoUnlock] 同一个规矩, 开不起来时回一句为什么, 而不是留一个开着却不生效的
     * 开关 (那种"打开了没用"最难查)
     */
    fun setInjectUnlock(context: Context, on: Boolean): String? {
        if (on) {
            val (secret, problem) = LockSecret.load(context)
            if (secret == null) {
                return "there is nothing to inject yet: record the unlock (or import a script) so" +
                    " that the password is in the encrypted slot first" +
                    (problem?.let { " ($it)" } ?: "")
            }
            val typable = secret.kind == LockSecretData.TEXT && secret.text.isNotEmpty()
            if (!typable) {
                return "the stored secret is ${secret.describe}, and only a password that can be" +
                    " typed can be injected: a pattern has to be drawn, so leave this off and the" +
                    " recording is walked through instead"
            }
        }
        prefs(context).edit().putBoolean(INJECT_UNLOCK, on).apply()
        LockReplay.forgetWarnings()
        revision += 1
        return null
    }

    /**
     * 直接写密码格 (「注入密码」那一行做的事, 不必录一遍手势)
     *
     * 落的还是 [LockSecret] 那**唯一一处加密的存法** —— 与录制时填的密码共用同一个位置, 所以两条路不会
     * 各存一份, 也不会有哪一份是明文。传空串 = 清掉那一格 (密文与那把 Keystore 密钥一起删)
     *
     * @return 存不成的那句人话 (Keystore 拒绝这类), 成了就是 null
     */
    fun setPassword(context: Context, password: String): String? {
        if (password.isEmpty()) {
            LockSecret.clear(context)
            revision += 1
            return null
        }
        val problem = LockSecret.save(
            context,
            LockSecretData(kind = LockSecretData.TEXT, text = password),
        )
        revision += 1
        return problem
    }

    /**
     * 落一条录好的序列
     *
     * 同时**写一份明文副本到 `$DSH_HOME/lock/unlock.json`**: 宿主插件与 `lw_lock op=steps` 都读它
     * (与相机占用表同一个先例 —— 应用与插件之间共享的那一件事落在文件上, 而不是各自记一份)
     */
    fun saveSteps(context: Context, steps: List<LockStep>, at: Long = System.currentTimeMillis()) {
        val bounded = LockSteps.bounded(steps)
        val text = LockSteps.encode(bounded)
        prefs(context).edit().putString(STEPS, text).putLong(STEPS_AT, at).apply()
        runCatching {
            val file = stepsFile(context)
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
        revision += 1
    }

    fun clearSteps(context: Context) {
        prefs(context).edit().remove(STEPS).remove(STEPS_AT).remove(TRIES).apply()
        LockSecret.clear(context)
        runCatching { stepsFile(context).delete() }
        revision += 1
    }

    fun setTries(context: Context, value: Int) {
        prefs(context).edit().putInt(TRIES, value.coerceAtLeast(0)).apply()
        revision += 1
    }

    /** 那一条序列在设备上的明文副本 */
    fun stepsFile(context: Context): File =
        File(File(File(context.filesDir, DshHost.HOME_DIR), DIR), FILE)

    /** 一串步骤的人话 (每一步一句, 密码那一步只说"the password") */
    fun describe(steps: List<LockStep>): List<String> = steps.map(LockSteps::describe)

    /** 什么时候录的, 写成给人看的一句 */
    fun recordedSentence(at: Long): String {
        if (at <= 0L) return "never"
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return format.format(Date(at))
    }

    /** 屏幕亮着没有 */
    fun screenOn(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    /** 还锁着没有 (KeyguardManager 那一句是"还需要解锁"的意思) */
    fun locked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /**
     * 到桌面了没有
     *
     * 两条一起看: 还锁着不算, 而**屏灭着也不算** —— 熄屏时 `isKeyguardLocked` 在只有滑动锁的机器上
     * 会是 false, 只看它就会把"睡着"读成"已经解锁"
     */
    fun unlocked(context: Context): Boolean = screenOn(context) && !locked(context)

    private fun readSteps(context: Context): String? = prefs(context).getString(STEPS, null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
}
