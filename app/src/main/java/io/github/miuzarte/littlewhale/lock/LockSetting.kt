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

    fun steps(context: Context): List<LockStep> = LockSteps.decode(readSteps(context))

    fun recordedAt(context: Context): Long = prefs(context).getLong(STEPS_AT, 0L)

    fun tries(context: Context): Int = prefs(context).getInt(TRIES, 0)

    /** 录过没有: 序列非空, 或者有一份秘密 (只有密码、还没录手势那一档) */
    fun recorded(context: Context): Boolean = steps(context).isNotEmpty()

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
        if (on && !recorded(context)) {
            return "nothing has been recorded yet, so there is nothing to replay: record the unlock" +
                " first (that row is above this switch)"
        }
        prefs(context).edit().putBoolean(AUTO_UNLOCK, on).apply()
        if (on) setTries(context, 0)
        revision += 1
        return null
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
