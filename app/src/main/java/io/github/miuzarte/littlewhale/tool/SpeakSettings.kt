package io.github.miuzarte.littlewhale.tool

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 念东西用哪个音色、多快
 *
 * 这两件事本来都归系统的「文字转语音输出」那一页, 但**分两半**: 引擎与语音包只能在那里换 (应用
 * 改不了别人家的引擎), 而"用这条引擎的哪个音色"与"多快"应用这一侧就能定 —— 于是这一段放在设置页
 * 里直接调, 那一页只留一个跳转
 *
 * **默认是"跟随系统"**: 没设过语速时 [effectiveRate] 回 null, 调用方据此**不去碰引擎的语速**
 * (系统的设置就生效了)。这一条是有来由的 —— 原来 `LwSpeak` 无条件 `setSpeechRate(rate ?: 1.0)`,
 * 于是每次出声都把系统里调好的语速按回 1.0, 而那是用户自己的设置
 *
 * 音色认的是 `TextToSpeech.Voice.name` (引擎给的稳定名字), 不认显示名 —— 显示名随语言变
 */
object SpeakSettings {

    /** 与 [io.github.miuzarte.littlewhale.host.HostSettings] 同一个偏好文件, 全 app 一个 */
    private const val STORE = "littlewhale"

    private const val FOLLOW_KEY = "speak-rate-follows-system"
    private const val RATE_KEY = "speak-rate"
    private const val VOICE_KEY = "speak-voice"

    /** 语速的两端与吸附点, 与 `lw_speak` 的 rate 参数同一套范围 */
    val range: ClosedFloatingPointRange<Float> = 0.5f..2.0f

    val keyPoints: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    /** 离吸附点多近算吸住 (与 Miuix 的 `magnetThreshold` 一个算法) */
    const val MAGNET = 0.03f

    /** 语速跟随系统 (默认): 那就不去动引擎的语速 */
    var followsSystem: Boolean by mutableStateOf(true)
        private set

    /** 不跟随时用的那个数 */
    var rate: Float by mutableStateOf(1f)
        private set

    /** 选中的音色 (`Voice.name`), null = 用引擎自己的默认音色 */
    var voice: String? by mutableStateOf(null)
        private set

    /** 出字那一刻要的那个数: null 表示"别动引擎的语速, 让它用系统那个" */
    fun effectiveRate(): Float? = if (followsSystem) null else rate

    /** 从磁盘读一次, 在第一帧之前调, 免得先按默认值画一遍再跳 */
    fun initialize(context: Context) {
        val stored = preferences(context)
        followsSystem = stored.getBoolean(FOLLOW_KEY, true)
        rate = stored.getFloat(RATE_KEY, 1f).coerceIn(range.start, range.endInclusive)
        voice = stored.getString(VOICE_KEY, null)
    }

    fun setFollowsSystem(context: Context, value: Boolean) {
        followsSystem = value
        preferences(context).edit().putBoolean(FOLLOW_KEY, value).apply()
    }

    /** 收下新语速, 顺手把"跟随系统"关掉 —— 拖了滑块就是要一个具体的数 */
    fun setRate(context: Context, value: Float) {
        rate = value.coerceIn(range.start, range.endInclusive)
        followsSystem = false
        preferences(context).edit()
            .putFloat(RATE_KEY, rate)
            .putBoolean(FOLLOW_KEY, false)
            .apply()
    }

    /** 收下新音色, null = 回到引擎默认 */
    fun setVoice(context: Context, name: String?) {
        voice = name
        preferences(context).edit().apply {
            if (name == null) remove(VOICE_KEY) else putString(VOICE_KEY, name)
        }.apply()
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
}
