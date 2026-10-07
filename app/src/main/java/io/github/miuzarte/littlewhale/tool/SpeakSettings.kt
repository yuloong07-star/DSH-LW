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
    private const val ENGINE_KEY = "speak-engine"
    private const val MODEL_KEY = "speak-model"
    private const val AUTO_KEY = "speak-read-aloud"
    private const val VOLUME_KEY = "speak-volume"
    private const val EDGE_VOICE_KEY = "speak-edge-voice"
    private const val API_URL_KEY = "speak-api-url"
    private const val API_KEY_KEY = "speak-api-key"
    private const val API_MODEL_KEY = "speak-api-model"
    private const val API_VOICE_KEY = "speak-api-voice"

    /**
     * 四条引擎
     *
     * - `SYSTEM`: ROM 自带那条, 即时、跟随系统音量, 音色只能在系统那一页挑
     * - `ON_DEVICE`: 主人自己导入的 sherpa-onnx 模型, 能换音色但慢一截
     * - `EDGE`: 微软 Edge 那条在线朗读接口, **免费、不要密钥**, 但接口没有承诺
     * - `API`: 自己填地址与密钥的 OpenAI 兼容接口 (`/v1/audio/speech`)
     */
    enum class Engine { SYSTEM, ON_DEVICE, EDGE, API }

    /** 语速的两端与吸附点, 与 `lw_speak` 的 rate 参数同一套范围 */
    val range: ClosedFloatingPointRange<Float> = 0.5f..2.0f

    val keyPoints: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    /** 离吸附点多近算吸住 (与 Miuix 的 `magnetThreshold` 一个算法) */
    const val MAGNET = 0.03f

    // 音量: 以**现在这个电平**当 100%, 单位是百分数 (分母固定 100)
    //
    // 为什么需要它: 主人放进来的那条音色自己就是小声的 —— 2026-10-06 在开发机上拿同一份
    // `vits-zh-ll` 量过, 峰值 0.27 (-11 dBFS)、RMS 0.057 (-25 dBFS), 比正常语音低十几个 dB, 而
    // 原来那行 `(sample * 32767)` 一点增益都不加, 于是"念出来过小"是模型自带的下限而不是播放的错
    //
    // 为什么 100% 是"一个增益都不加"而不是"听起来正常": 刻度得钉在一个**量得出来的东西**上, 而
    // 这条音色本来的电平正好就是主人说"过小"的那一个。于是 100% = 现在这样, 往上才是加, 换一条
    // 音色也好解释
    //
    // 为什么两端要钉死而不是"想调多大调多大": 同一份模型里还有四个说话人 (代码现在只用 0 号,
    // 但换一个就是一行的事), 其中最响的一条量到 0.493 (-6.1 dBFS), 乘 3 是 1.48 —— **已经削顶**。
    // 所以再往上不是"更响"而是"更破", 而这一侧宁可把上限停在 300% 也不给一个会把声音弄坏的旋钮
    // (0 号那个音色到 3 倍是 0.86, 留得住余量)

    /** 100% = 一个增益都不加, **就是现在这个电平** */
    const val VOLUME_UNITY = 100f

    /** 上限 300% (见上面那段量出来的数), 再往上就是削顶而不是更响了 */
    const val MAX_VOLUME = 300f

    /** 那条滑块的两端, 也是点名传音量时收下的范围 */
    val volumeRange: ClosedFloatingPointRange<Float> = 0f..MAX_VOLUME

    /** 吸附点: 0 是静音, 100 是模型自己的电平, 300 是上限 */
    val volumeKeyPoints: List<Float> = listOf(0f, 50f, 100f, 150f, 200f, MAX_VOLUME)

    /**
     * 增益的上限: 300% 就是 3.0
     *
     * 调用方递下来的已经是"倍数" (百分数除以 [VOLUME_UNITY]): 100% 就是 1.0 (一个数都不改), 顶到
     * 滑块上端才是这个上限 —— 播放那一侧只做夹取, 不再乘第二次
     */
    fun maxGain(): Float = MAX_VOLUME / VOLUME_UNITY

    /** 语速跟随系统 (默认): 那就不去动引擎的语速 */
    var followsSystem: Boolean by mutableStateOf(true)
        private set

    /** 不跟随时用的那个数 */
    var rate: Float by mutableStateOf(1f)
        private set

    /** 音量 (百分数): 100 就是模型自己的电平, 往上加增益, 0 是静音 */
    var volume: Float by mutableStateOf(VOLUME_UNITY)
        private set

    /** 选中的音色 (`Voice.name`), null = 用引擎自己的默认音色 */
    var voice: String? by mutableStateOf(null)
        private set

    /** 用哪条引擎念: 默认系统那条 (自带那条的首字延迟是几百毫秒到一秒) */
    var engine: Engine by mutableStateOf(Engine.SYSTEM)
        private set

    /** 自带那条用的音色目录名 (工作区 `voices/<这个名字>/`), null = 还没选 */
    var model: String? by mutableStateOf(null)
        private set

    /** 两条条件都满足才走自带那条: 选了它、而且真的挑了一个音色 */
    fun usesOnDevice(): Boolean = engine == Engine.ON_DEVICE && model != null

    /** Edge 在线那条用的音色名 (`zh-CN-XiaoxiaoNeural` 这种), 空 = 用缺省那条 */
    var edgeVoice: String by mutableStateOf(LwEdgeSpeech.DEFAULT_VOICE)
        private set

    /** Edge 那条不用别的条件: 选了就走, 音色有缺省 */
    fun usesEdge(): Boolean = engine == Engine.EDGE

    /** API 那条的地址 (人填的原样; 补全发生在调用时, 见 [LwApiSpeech.endpoint]) */
    var apiUrl: String by mutableStateOf("")
        private set

    /** API 那条的密钥 (只存在本应用的偏好文件里; 自建网关不要密钥时留空) */
    var apiKey: String by mutableStateOf("")
        private set

    var apiModel: String by mutableStateOf(LwApiSpeech.DEFAULT_MODEL)
        private set

    var apiVoice: String by mutableStateOf(LwApiSpeech.DEFAULT_VOICE)
        private set

    /** 选了 API 那条、而且填了地址, 才算能用 (没填地址时调用方会直说缺什么) */
    fun usesApi(): Boolean = engine == Engine.API && apiUrl.isNotBlank()

    /**
     * 回答落定就自动念 (默认开)
     *
     * 关掉它只影响那条自动链: 模型点名要念 (`lw_speak`) 与设置页的试听照旧 —— 有时就是不想要它出声,
     * 而"要它念一句"仍然该是能做到的。**这一条宿主那侧要去读** (自动念是宿主发起的), 所以它同时进
     * `lw_speak op=status`
     */
    var readAloud: Boolean by mutableStateOf(true)
        private set

    /** 出字那一刻要的那个数: null 表示"别动引擎的语速, 让它用系统那个" */
    fun effectiveRate(): Float? = if (followsSystem) null else rate

    /** 从磁盘读一次, 在第一帧之前调, 免得先按默认值画一遍再跳 */
    fun initialize(context: Context) {
        val stored = preferences(context)
        followsSystem = stored.getBoolean(FOLLOW_KEY, true)
        rate = stored.getFloat(RATE_KEY, 1f).coerceIn(range.start, range.endInclusive)
        voice = stored.getString(VOICE_KEY, null)
        engine = runCatching { Engine.valueOf(stored.getString(ENGINE_KEY, null).orEmpty()) }
            .getOrDefault(Engine.SYSTEM)
        model = stored.getString(MODEL_KEY, null)
        readAloud = stored.getBoolean(AUTO_KEY, true)
        volume = stored.getInt(VOLUME_KEY, VOLUME_UNITY.toInt()).toFloat()
            .coerceIn(volumeRange.start, volumeRange.endInclusive)
        edgeVoice = stored.getString(EDGE_VOICE_KEY, LwEdgeSpeech.DEFAULT_VOICE).orEmpty()
            .ifBlank { LwEdgeSpeech.DEFAULT_VOICE }
        apiUrl = stored.getString(API_URL_KEY, "").orEmpty()
        apiKey = stored.getString(API_KEY_KEY, "").orEmpty()
        apiModel = stored.getString(API_MODEL_KEY, LwApiSpeech.DEFAULT_MODEL).orEmpty()
            .ifBlank { LwApiSpeech.DEFAULT_MODEL }
        apiVoice = stored.getString(API_VOICE_KEY, LwApiSpeech.DEFAULT_VOICE).orEmpty()
            .ifBlank { LwApiSpeech.DEFAULT_VOICE }
    }

    /** 收下 Edge 那条的音色名; 空 = 回到缺省 */
    fun setEdgeVoice(context: Context, value: String) {
        edgeVoice = value.trim().ifBlank { LwEdgeSpeech.DEFAULT_VOICE }
        preferences(context).edit().putString(EDGE_VOICE_KEY, edgeVoice).apply()
    }

    /** 收下 API 那条的地址 (存人填的原样, 不在这里补全) */
    fun setApiUrl(context: Context, value: String) {
        apiUrl = value.trim()
        preferences(context).edit().putString(API_URL_KEY, apiUrl).apply()
    }

    /** 收下 API 那条的密钥; 空 = 不带 Authorization (自建网关常常不要) */
    fun setApiKey(context: Context, value: String) {
        apiKey = value.trim()
        preferences(context).edit().putString(API_KEY_KEY, apiKey).apply()
    }

    /** 收下 API 那条的模型名; 空 = 回缺省 */
    fun setApiModel(context: Context, value: String) {
        apiModel = value.trim().ifBlank { LwApiSpeech.DEFAULT_MODEL }
        preferences(context).edit().putString(API_MODEL_KEY, apiModel).apply()
    }

    /** 收下 API 那条的音色名; 空 = 回缺省 */
    fun setApiVoice(context: Context, value: String) {
        apiVoice = value.trim().ifBlank { LwApiSpeech.DEFAULT_VOICE }
        preferences(context).edit().putString(API_VOICE_KEY, apiVoice).apply()
    }

    /**
     * 收下新音量
     *
     * 存的是**整数百分数**: 滑块一次拖动能来几十趟回调, 落到整数上就不必每一趟都写一次盘
     */
    fun setVolume(context: Context, value: Float) {
        volume = value.coerceIn(volumeRange.start, volumeRange.endInclusive)
        preferences(context).edit().putInt(VOLUME_KEY, volume.toInt()).apply()
    }

    /** 关掉/打开自动念 */
    fun setReadAloud(context: Context, value: Boolean) {
        readAloud = value
        preferences(context).edit().putBoolean(AUTO_KEY, value).apply()
    }

    /** 换引擎: 挑自带那条但还没有音色时也收下 (设置页会提示去导入), 真正生效看 usesOnDevice */
    fun setEngine(context: Context, value: Engine) {
        engine = value
        preferences(context).edit().putString(ENGINE_KEY, value.name).apply()
    }

    /** 挑一个自带音色 (目录名), 顺手把引擎切到自带那条 —— 挑音色这个动作本身就说明要用它 */
    fun setModel(context: Context, name: String?) {
        model = name
        if (name != null) engine = Engine.ON_DEVICE
        preferences(context).edit().apply {
            if (name == null) remove(MODEL_KEY) else putString(MODEL_KEY, name)
            putString(ENGINE_KEY, engine.name)
        }.apply()
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
