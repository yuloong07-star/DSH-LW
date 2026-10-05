package io.github.miuzarte.littlewhale.tool

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.voice.VoiceState
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 本机朗读: 把一段文字交给系统语音合成念出来
 *
 * 为什么是这一条: 它是这台手机上唯一不需要密钥、不需要联网的语音输出 —— 引擎是 ROM 自带的
 * (vivo 的 AIService / AiAgent 声明了 `android.intent.action.TTS_SERVICE`, 见
 * AndroidManifest.xml 里 queries 那一条: Android 11 起不声明, 系统引擎对本应用就是不可见的),
 * 而网络那几家 (edge-tts、MiMo TTS) 要么走别人的服务, 要么要密钥
 *
 * 四个动作: `status` 看引擎与中文音色在不在, `speak` 念一段, `stop` 掐断正在念的, `release`
 * 把引擎还回去 (初始化要几百毫秒, 所以平时留着复用)
 */
internal object LwSpeak {

    /** 引擎初始化与一次朗读各自的上限; 到点就说没念完, 不无限等 */
    private const val INIT_BUDGET_MS = 8_000L
    private const val SPEAK_BUDGET_MS = 15_000L
    private const val SPEAK_BUDGET_PER_CHAR_MS = 250L
    private const val MAX_WAIT_MS = 120_000L

    /** 设置页最多列几个中文音色: 有的引擎能列出几十个, 全铺开会把这一页撑得没法看 */
    private const val MAX_VOICES = 8

    private const val TAG = "LwSpeak"

    /** 正在等的那一条: 只有它的完成/出错才算这一次念完了 */
    private class Utterance(val id: String, val latch: CountDownLatch)

    private val lock = Any()
    private var engine: TextToSpeech? = null

    @Volatile
    private var current: Utterance? = null

    @Volatile
    private var failure: String? = null

    @Volatile
    private var spoken: Int = 0

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "status" -> status(context)
        "speak" -> speak(context, request)
        "stop" -> stop()
        "release" -> release()
        else -> throw IllegalArgumentException("op has to be status, speak, stop or release, not \"$op\"")
    }

    /** 引擎现在什么样: 有没有引擎、默认是哪一个、中文音色能不能用、以及这一侧选了什么 */
    private fun status(context: Context): JsonObject {
        val attempt = runCatching { engine(context) }
        val tts = attempt.getOrNull()
        val chinese = tts?.let { runCatching { it.setLanguage(Locale.CHINESE) }.getOrElse { -99 } }
        val voices = tts?.let { runCatching { it.voices }.getOrNull() }
        return buildJsonObject {
            put("engine", tts?.defaultEngine ?: "unavailable")
            put("voices", voices?.size ?: 0)
            put(
                "chineseVoices",
                chineseVoices(voices)
                    ?.joinToString(", ") { it.name }
                    .orEmpty(),
            )
            // 设置页里选的那两样: 语速跟不跟随系统、以及选了哪个音色
            put("rateFollowsSystem", SpeakSettings.followsSystem)
            put("rate", SpeakSettings.rate.toDouble())
            put("selectedVoice", SpeakSettings.voice ?: "the engine's own default")
            put(
                "chinese",
                when (chinese) {
                    TextToSpeech.LANG_AVAILABLE -> "available"
                    TextToSpeech.LANG_COUNTRY_AVAILABLE -> "available (country)"
                    TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "available (variant)"
                    TextToSpeech.LANG_MISSING_DATA -> "missing data: the engine still has to download its Chinese voice"
                    TextToSpeech.LANG_NOT_SUPPORTED -> "not supported by this engine"
                    null -> "unknown: the engine did not come up (${attempt.exceptionOrNull()?.message})"
                    else -> "unknown ($chinese)"
                },
            )
            put("speaking", current != null)
            put("utterances", spoken)
        }
    }

    /**
     * 设置页要的那几个中文音色
     *
     * 只给中文的: 这个应用念的一律是中文回答, 把英文音色列出来只会让人选错。引擎没起来时回 null
     * (调用方据此说"引擎还没就绪"而不是"没有音色")
     */
    internal fun chineseVoices(context: Context): List<Voice>? =
        runCatching { chineseVoices(engine(context)?.voices) }.getOrNull()

    private fun chineseVoices(voices: Set<Voice>?): List<Voice>? =
        voices
            ?.filter { it.locale.language == "zho" || it.locale.language == "zh" }
            ?.sortedBy { it.name }
            ?.take(MAX_VOICES)

    /** 设置页那个「试听」: 用当前设置念一句, 让人当场听见音色与语速 */
    internal fun preview(context: Context): JsonObject =
        speak(context, buildJsonObject { put("op", "speak"); put("text", context.getString(R.string.settings_speak_sample)) })

    /** 念一段: 太长就按句切, 只等最后一片念完 */
    private fun speak(context: Context, request: JsonObject): JsonObject {
        val text = request.string("text").trim()
        if (text.isEmpty()) {
            unavailable("speaking", "the text is empty")
        }
        val tts = engine(context)
        val chinese = tts.setLanguage(Locale.CHINESE)
        if (chinese == TextToSpeech.LANG_MISSING_DATA || chinese == TextToSpeech.LANG_NOT_SUPPORTED) {
            unavailable(
                "speaking Chinese",
                "the system engine has no usable Chinese voice (setLanguage said $chinese);" +
                    " download a voice pack in the system's text-to-speech settings",
            )
        }
        // 语速的优先级: 这次调用点名要的 > 设置页里选的 > **什么都不动**
        //
        // 最后那一档是必须的: 原来写死 `rate ?: 1.0` 就等于每次出声都把系统里调好的语速按回 1.0,
        // 而"系统的设置是用户的"。所以没点名、设置页又选了跟随系统时, 这里一个数都不设
        val asked = request.numberOrNull("rate")?.toFloat()
        val chosen = asked?.coerceIn(0.5f, 2.0f) ?: SpeakSettings.effectiveRate()
        if (chosen != null) runCatching { tts.setSpeechRate(chosen) }
        // 音色同理由设置页定; 认的是 Voice.name, 找不到就退回引擎默认 (不报错, 只留一行日志)
        SpeakSettings.voice?.let { name ->
            val wanted = runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == name }
            if (wanted != null) {
                runCatching { tts.voice = wanted }
                    .onFailure { Log.w(TAG, "the engine would not take the voice $name", it) }
            } else {
                Log.w(TAG, "the engine no longer lists the voice $name, using its own default")
            }
        }
        val pieces = chunk(text, TextToSpeech.getMaxSpeechInputLength())
        val latch = CountDownLatch(1)
        val lastId = "lw-speak-${++spoken}-${pieces.lastIndex}"
        failure = null
        current = Utterance(lastId, latch)
        val interrupt = request.bool("interrupt", true)
        val budget = (SPEAK_BUDGET_MS + text.length * SPEAK_BUDGET_PER_CHAR_MS).coerceAtMost(MAX_WAIT_MS)
        // 半双工: **先关麦克风再出声**, 反过来就有几十毫秒的喇叭内容被录进去,见 VoiceState.speaking
        // 与采集那条链上的 halfDuplex 闸 —— 不采就不会把自己的声音录回去, 也叫不醒自己
        //
        // try 要包住整段: 喇叭排队失败时 `unavailable` 会抛, 那时标记也必须放回去, 不然麦克风就
        // 一直关着, 下一次说话谁也听不见
        VoiceState.speaking = true
        val finished = try {
            pieces.forEachIndexed { index, piece ->
                val id = "lw-speak-$spoken-$index"
                val mode = if (interrupt && index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val result = tts.speak(piece, mode, null, id)
                if (result != TextToSpeech.SUCCESS) {
                    current = null
                    unavailable("speaking", "the engine refused the utterance ($result)")
                }
            }
            latch.await(budget, TimeUnit.MILLISECONDS)
        } finally {
            VoiceState.speaking = false
        }
        val reported = failure
        current = null
        return buildJsonObject {
            put("spoken", finished && reported == null)
            put("text", text)
            put("pieces", pieces.size)
            put("characters", text.length)
            put("waitedMs", budget)
            put("detail", reported ?: if (finished) "the engine finished" else "still speaking after ${budget}ms")
        }
    }

    /** 掐断正在念的, 队列里的也一起 */
    private fun stop(): JsonObject {
        val tts = synchronized(lock) { engine }
        if (tts == null) {
            return buildJsonObject {
                put("stopped", false)
                put("detail", "no engine is loaded, so there is nothing to stop")
            }
        }
        val result = tts.stop()
        current = null
        // 掐断了就不能让那道半双工的闸一直关着
        VoiceState.speaking = false
        return buildJsonObject {
            put("stopped", result == TextToSpeech.SUCCESS)
            put("detail", "stop said $result")
        }
    }

    /** 把引擎放掉: 下一次 status/speak 会重新初始化 */
    private fun release(): JsonObject {
        val held = synchronized(lock) {
            val loaded = engine
            engine = null
            loaded
        }
        current = null
        if (held == null) {
            return buildJsonObject {
                put("released", false)
                put("detail", "no engine was loaded")
            }
        }
        held.stop()
        held.shutdown()
        return buildJsonObject { put("released", true) }
    }

    /**
     * 建一个可用的引擎: 初始化是异步的, 而且 TextToSpeech 要在有 Looper 的线程上建, 所以这一步
     * 交给主线程做, 这里等它回话
     */
    private fun engine(context: Context): TextToSpeech {
        synchronized(lock) {
            engine?.let { return it }
            val app = context.applicationContext
            val ready = CountDownLatch(1)
            var created: TextToSpeech? = null
            var init = TextToSpeech.ERROR
            val create = Runnable {
                created = TextToSpeech(app) { result ->
                    init = result
                    ready.countDown()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                create.run()
            } else {
                Handler(Looper.getMainLooper()).post(create)
            }
            val up = ready.await(INIT_BUDGET_MS, TimeUnit.MILLISECONDS)
            val instance = created
            if (!up || init != TextToSpeech.SUCCESS || instance == null) {
                instance?.let { runCatching { it.shutdown() } }
                unavailable(
                    "speaking through the system engine",
                    "the text to speech engine did not come up within ${INIT_BUDGET_MS}ms (init said $init)",
                )
            }
            instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    val waiting = current
                    if (waiting != null && waiting.id == utteranceId) waiting.latch.countDown()
                }

                override fun onError(utteranceId: String?) {
                    failure = "the engine reported an error for $utteranceId"
                    current?.latch?.countDown()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    failure = "the engine reported error $errorCode for $utteranceId"
                    current?.latch?.countDown()
                }
            })
            engine = instance
            return instance
        }
    }

    /** 按句号换行切到引擎能吃的长度; 切不出好位置就硬切 */
    private fun chunk(text: String, limit: Int): List<String> {
        val safe = if (limit <= 0) 4000 else limit
        if (text.length <= safe) return listOf(text)
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + safe).coerceAtMost(text.length)
            if (end < text.length) {
                val cut = text.lastIndexOfAny(charArrayOf('。', '！', '？', '；', '\n', '.', '!', '?', ';'), end)
                if (cut > start + safe / 2) end = cut + 1
            }
            pieces += text.substring(start, end)
            start = end
        }
        return pieces
    }
}
