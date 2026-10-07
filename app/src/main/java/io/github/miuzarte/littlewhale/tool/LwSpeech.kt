package io.github.miuzarte.littlewhale.tool

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import com.k2fsa.sherpa.onnx.WaveReader
import io.github.miuzarte.littlewhale.voice.SpeechSegmenter
import io.github.miuzarte.littlewhale.voice.TranscriptClean
import io.github.miuzarte.littlewhale.voice.VoiceState
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 本机语音转写: 把一段 16 kHz 单声道 WAV 交给 sherpa-onnx, 在 app 进程里离线出字
 *
 * 为什么落在这一层: dsh 官方的本地语音 provider 要 sherpa-onnx-node, 而那个 npm 包的原生
 * addon 只有 darwin / linux / win 三套二进制, npm 上也没有 android-arm64 那一个 —— 宿主
 * 侧的 Node 因此转不了。转写只能在 app 这一侧做: APK 静态链接了 sherpa-onnx (见
 * app/build.gradle.kts 里那个取 AAR 的任务), 模型由宿主侧的语音插件下载到 [modelDirectory],
 * 这里只负责读模型、跑一次前向、把文本交回去
 *
 * 三个动作: `status` 看模型与引擎在不在, `transcribe` 认一段录音, `release` 把认出来的实例
 * 放掉 —— SenseVoice int8 的模型是 240 MB 量级, 装进内存之后不便宜, 长时间不用值得放
 *
 * **两套引擎**: 这里这一套是 SenseVoice (快, 常驻语音链走它); 另有 [GlmAsr] 那一套
 * (GLM-ASR-Nano, 1.5B, 准但慢一个数量级), 由 `engine` 这个参数选, 缺省还是 SenseVoice ——
 * 换引擎是调用方的决定, 不是这里的默认
 */
internal object LwSpeech {

    /** 模型落在 filesDir 下的这一层, 与宿主侧的语音插件是约定, 谁都不许单边改 */
    const val MODEL_ROOT = "speech-models"

    /** 现在只有 SenseVoice 一套: 中英日韩粤, 自带标点与逆文本规整 */
    const val MODEL_NAME = "sense-voice"

    /** 切段那个 silero 模型住在同一层的另一个子目录里, 两者互不相干 */
    const val VAD_NAME = "silero-vad"

    private const val MODEL_FILE = "model.int8.onnx"
    private const val TOKENS_FILE = "tokens.txt"

    /** 录音与模型都按这个采样率, 别处对不上就是别处错 */
    private const val SAMPLE_RATE = 16000

    /** provider 报给页面的语言就是这几个, 别的一律落回 auto */
    private val LANGUAGES = setOf("auto", "zh", "en", "yue", "ja", "ko")

    /** 已经建起来的识别器: 换模型或换语言才重建, 认一次是几百毫秒到几秒 */
    private class Loaded(val directory: String, val language: String, val recognizer: OfflineRecognizer)

    /** 识别器不是线程安全的, 而通道那边是多线程应答, 所以一次只放一个进来 */
    private val lock = Any()
    private var loaded: Loaded? = null

    /** 模型在哪儿: 缺省 filesDir/speech-models/sense-voice, 调用方也可以点名别处 */
    fun modelDirectory(context: Context, request: JsonObject): File =
        request.stringOrNull("model")?.let { File(it) } ?: modelDirectory(context)

    /** 同一个缺省目录, 给不经过通道的调用方 (常驻语音链) 用 */
    fun modelDirectory(context: Context): File = File(File(context.filesDir, MODEL_ROOT), MODEL_NAME)

    /** 切段模型的那一个文件: 宿主那侧照 status 报出来的 vadPath 放, 应用这侧照它读 */
    fun vadModel(context: Context): File =
        File(File(File(context.filesDir, MODEL_ROOT), VAD_NAME), SpeechSegmenter.MODEL_FILE)

    /** 识别模型在不在位 (常驻语音链据此决定要不要去加载那 240 MB) */
    fun ready(context: Context): Boolean {
        val directory = modelDirectory(context)
        return File(directory, MODEL_FILE).isFile && File(directory, TOKENS_FILE).isFile
    }

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "status" -> status(context, request)
        "transcribe" -> transcribe(context, request)
        "release" -> release(request)
        "warmup" -> warmUp(context, request)
        else -> throw IllegalArgumentException("op has to be status, transcribe, warmup or release, not \"$op\"")
    }

    /** 引擎与模型现在什么样: 页面据此决定录音按钮能不能按 */
    private fun status(context: Context, request: JsonObject): JsonObject {
        val directory = modelDirectory(context, request)
        val model = File(directory, MODEL_FILE)
        val tokens = File(directory, TOKENS_FILE)
        val vad = vadModel(context)
        val present = model.isFile && tokens.isFile
        val held = synchronized(lock) { loaded }
        // **模型不在就不要碰原生库**: `VersionInfo.version` 是一条 JNI 调用, 会把 sherpa 那个
        // .so 拉起来 —— 而它只为回答一句"引擎是什么版本"。插件的 `lw-native` provider 在**加载时**
        // 就问一次状态 (页面靠它决定录音按钮能不能按), 于是这一问落在每次 host 启动的路径上:
        // 模型还没下、或者这台设备的 ABI 跑不动那个库时, 整条启动就跟着那个库一起死 (2026-10-05
        // 在模拟器上实测: AndroidRuntime 之外的 SIGSEGV, 线程是 lw-bridge-reque)。
        // 模型下好了才回去问版本, 那时才是真的要用它
        val sherpa = if (present) {
            runCatching { VersionInfo.version }.getOrElse { "unavailable: ${it.message}" }
        } else {
            "not loaded (the model is not downloaded yet)"
        }
        val onnxruntime = if (present) {
            runCatching { VersionInfo.onnxruntimeVersion }.getOrElse { "unavailable" }
        } else {
            "not loaded"
        }
        return buildJsonObject {
            put("engine", "sherpa-onnx")
            put("sherpa", sherpa)
            put("onnxruntime", onnxruntime)
            put("model", MODEL_NAME)
            put("directory", directory.absolutePath)
            put("modelPath", model.absolutePath)
            put("tokensPath", tokens.absolutePath)
            put("modelBytes", model.length())
            put("tokensBytes", tokens.length())
            put("present", present)
            put("vadPath", vad.absolutePath)
            put("vadPresent", vad.isFile)
            put("vadBytes", vad.length())
            put("loaded", held != null && held.directory == directory.absolutePath)
            put("languages", LANGUAGES.joinToString(", "))
            // 另一套引擎的状态放在同一个答复里: 调用方问一次就知道两档各能不能用
            put("glm", GlmAsr.status(context))
        }
    }

    /** 认一段录音: 只认 16 kHz 单声道的 WAV, 也就是页面那一侧录出来的样子 */
    private fun transcribe(context: Context, request: JsonObject): JsonObject {
        val path = request.string("wav")
        if (engineOf(request) == GLM) {
            val startedAt = System.currentTimeMillis()
            val text = GlmAsr.transcribe(context, File(path))
            return buildJsonObject {
                // **出字之后过一遍清洁**: 修得回来的编码乱码修回来, 引擎不该说的那些文字丢掉
                // (主人 2026-10-07 报的"识别时会出现乱码", 判据在 [TranscriptClean])
                put("text", TranscriptClean.clean(text))
                // 原始那一份也带上: 宿主那侧把它写进 transcripts.log, 下一次出乱码才有得对账
                put("raw", text)
                put("engine", GLM)
                put("elapsedMs", System.currentTimeMillis() - startedAt)
            }
        }
        val recording = File(path)
        if (!recording.isFile) {
            unavailable("transcribing $path", "there is no such file")
        }
        val directory = modelDirectory(context, request)
        val model = File(directory, MODEL_FILE)
        val tokens = File(directory, TOKENS_FILE)
        if (!model.isFile || !tokens.isFile) {
            unavailable(
                "transcribing with the model in ${directory.absolutePath}",
                "the model is not installed yet: $MODEL_FILE or $TOKENS_FILE is missing",
            )
        }
        val language = languageOf(request.stringOrNull("language"))
        val wave = runCatching { WaveReader.readWave(recording.absolutePath) }.getOrElse { error ->
            unavailable("reading ${recording.name}", error.message ?: error.toString())
        }
        if (wave.samples.isEmpty()) {
            unavailable("reading ${recording.name}", "the recording holds no samples")
        }
        val startedAt = System.currentTimeMillis()
        val text = synchronized(lock) {
            val recognizer = recognizerFor(directory, model, tokens, language)
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(wave.samples, wave.sampleRate)
                recognizer.decode(stream)
                recognizer.getResult(stream).text
            } finally {
                stream.release()
            }
        }
        return buildJsonObject {
            // 与 GLM 那一条同一个清洁口 (两套引擎的输出都要过)
            put("text", TranscriptClean.clean(text))
            put("raw", text)
            put("engine", ENGINE)
            put("language", language)
            put("sampleRate", wave.sampleRate)
            put("seconds", wave.samples.size.toDouble() / wave.sampleRate)
            put("elapsedMs", System.currentTimeMillis() - startedAt)
        }
    }

    /**
     * 认一段内存里的采样, 给常驻语音链用 —— 它手里是 VAD 切出来的一段 float, 不是一个 wav 文件
     *
     * 返回 null 表示模型不在: 调用方据此知道"这次没出字"而不是拿到一个空串以为"没人说话"。识别器
     * 是同一个常驻实例 ([recognizerFor] 按目录与语言缓存), 所以第二段之后不再重复加载那 240 MB
     */
    fun recognize(context: Context, samples: FloatArray, language: String = "auto"): String? {
        if (samples.isEmpty()) return null
        val directory = modelDirectory(context)
        val model = File(directory, MODEL_FILE)
        val tokens = File(directory, TOKENS_FILE)
        if (!model.isFile || !tokens.isFile) return null
        return synchronized(lock) {
            val recognizer = recognizerFor(directory, model, tokens, languageOf(language))
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                // 清洁口就在出字这一处: 唤醒词那一条链 (常驻语音) 走的正是它
                val raw = recognizer.getResult(stream).text
                val clean = TranscriptClean.clean(raw)
                // **丢掉了要说一声**: 那一句本来会作为"主人说的话"进会话, 静默丢掉等于点了一下没反应;
                // 状态里留一句 (通知栏与 `wakeword op=status` 都读它), 而清掉是下一次成功出字时的事
                if (clean.isEmpty() && raw.isNotBlank()) {
                    VoiceState.lastError = "a segment came back as text this app cannot speak (" +
                        raw.take(20) + "), so it was dropped"
                }
                clean
            } finally {
                stream.release()
            }
        }
    }

    /**
     * 把那 240 MB 先装进内存, 不出字
     *
     * 第一次 [recognize] 与第一次 [warmUp] 走的是同一条 [recognizerFor], 所以预热过的下一次认就是
     * 纯推理 —— 常驻语音链在**命中唤醒词那一下**调它, 那笔加载的时间因此落在主人还在说话的那几秒里,
     * 而不是落在他"说完了在等"的那几秒里
     *
     * 回 false 表示模型不在位, 什么都没做 (与 [recognize] 回 null 是同一个意思)
     */
    fun warmUp(context: Context): Boolean {
        val directory = modelDirectory(context)
        val model = File(directory, MODEL_FILE)
        val tokens = File(directory, TOKENS_FILE)
        if (!model.isFile || !tokens.isFile) return false
        synchronized(lock) { recognizerFor(directory, model, tokens, "auto") }
        return true
    }

    /** 认出模型文件建一个识别器; 找不到就建, 模型或语言换了就重建 */
    private fun recognizerFor(directory: File, model: File, tokens: File, language: String): OfflineRecognizer {
        val held = loaded
        if (held != null && held.directory == directory.absolutePath && held.language == language) {
            return held.recognizer
        }
        held?.recognizer?.release()
        loaded = null
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model.absolutePath,
                    // 空串就是让模型自己认语言, 与 provider 报出去的 auto 是一回事
                    language = if (language == "auto") "" else language,
                    useInverseTextNormalization = true,
                ),
                tokens = tokens.absolutePath,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            ),
        )
        val created = OfflineRecognizer(config = config)
        loaded = Loaded(directory.absolutePath, language, created)
        return created
    }

    /** 把 SenseVoice 那 240 MB 先读进内存, 等真要认的时候只剩推理 */
    private fun warmUp(context: Context, request: JsonObject): JsonObject {
        if (engineOf(request) == GLM) {
            val ready = GlmAsr.warmUp(context)
            return buildJsonObject { put("warmed", ready); put("engine", GLM) }
        }
        return buildJsonObject {
            put("warmed", warmUp(context))
            put("engine", ENGINE)
        }
    }

    /**
     * 把占着的识别器还回去: 下一次 transcribe 会为当时那套模型再建一个
     *
     * 不带 `engine` 就两套一起放 (那是"不听了, 把内存还回来"的意思); 点名一套只放那一套
     */
    private fun release(request: JsonObject): JsonObject {
        val asked = request.stringOrNull("engine")
        if (asked == GLM) {
            return buildJsonObject { put("engine", GLM); put("glm", GlmAsr.release()) }
        }
        if (asked == ENGINE) {
            return buildJsonObject { put("engine", ENGINE); put("sherpa", releaseSherpa()) }
        }
        return buildJsonObject {
            put("sherpa", releaseSherpa())
            put("glm", GlmAsr.release())
        }
    }

    /** 把占着的识别器还回去: 下一次 transcribe 会为当时那套模型再建一个 */
    private fun releaseSherpa(): JsonObject {
        val held = synchronized(lock) {
            val current = loaded
            loaded = null
            current
        }
        if (held == null) {
            return buildJsonObject {
                put("released", false)
                put("detail", "no recognizer was loaded")
            }
        }
        held.recognizer.release()
        return buildJsonObject {
            put("released", true)
            put("directory", held.directory)
            put("language", held.language)
        }
    }

    private fun languageOf(value: String?): String {
        val asked = value?.trim()?.lowercase().orEmpty()
        return if (asked in LANGUAGES) asked else "auto"
    }

    /** 引擎那两个字: 不认识的一律落回 SenseVoice, 一个拼错的名字不该把转写变成"没有" */
    private fun engineOf(request: JsonObject): String =
        if (request.stringOrNull("engine")?.trim()?.lowercase() == GLM) GLM else ENGINE

    const val ENGINE = "sherpa"
    const val GLM = "glm"
}
