package io.github.miuzarte.littlewhale.tool

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import com.k2fsa.sherpa.onnx.WaveReader
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
 */
internal object LwSpeech {

    /** 模型落在 filesDir 下的这一层, 与宿主侧的语音插件是约定, 谁都不许单边改 */
    const val MODEL_ROOT = "speech-models"

    /** 现在只有 SenseVoice 一套: 中英日韩粤, 自带标点与逆文本规整 */
    const val MODEL_NAME = "sense-voice"

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
        request.stringOrNull("model")?.let { File(it) }
            ?: File(File(context.filesDir, MODEL_ROOT), MODEL_NAME)

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "status" -> status(context, request)
        "transcribe" -> transcribe(context, request)
        "release" -> release()
        else -> throw IllegalArgumentException("op has to be status, transcribe or release, not \"$op\"")
    }

    /** 引擎与模型现在什么样: 页面据此决定录音按钮能不能按 */
    private fun status(context: Context, request: JsonObject): JsonObject {
        val directory = modelDirectory(context, request)
        val model = File(directory, MODEL_FILE)
        val tokens = File(directory, TOKENS_FILE)
        val held = synchronized(lock) { loaded }
        return buildJsonObject {
            put("engine", "sherpa-onnx")
            put("sherpa", runCatching { VersionInfo.version }.getOrElse { "unavailable: ${it.message}" })
            put("onnxruntime", runCatching { VersionInfo.onnxruntimeVersion }.getOrElse { "unavailable" })
            put("model", MODEL_NAME)
            put("directory", directory.absolutePath)
            put("modelPath", model.absolutePath)
            put("tokensPath", tokens.absolutePath)
            put("modelBytes", model.length())
            put("tokensBytes", tokens.length())
            put("present", model.isFile && tokens.isFile)
            put("loaded", held != null && held.directory == directory.absolutePath)
            put("languages", LANGUAGES.joinToString(", "))
        }
    }

    /** 认一段录音: 只认 16 kHz 单声道的 WAV, 也就是页面那一侧录出来的样子 */
    private fun transcribe(context: Context, request: JsonObject): JsonObject {
        val path = request.string("wav")
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
            put("text", text.trim())
            put("language", language)
            put("sampleRate", wave.sampleRate)
            put("seconds", wave.samples.size.toDouble() / wave.sampleRate)
            put("elapsedMs", System.currentTimeMillis() - startedAt)
        }
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

    /** 把占着的识别器还回去: 下一次 transcribe 会为当时那套模型再建一个 */
    private fun release(): JsonObject {
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
}
