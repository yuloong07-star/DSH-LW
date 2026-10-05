package io.github.miuzarte.littlewhale.tool

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.github.miuzarte.littlewhale.voice.VoiceState
import io.github.miuzarte.littlewhale.workspace.Workspace
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自带的那条朗读: 用 sherpa-onnx 的 TTS 念, 音色就是主人自己放进来的模型目录
 *
 * 为什么要有它: 系统那条引擎的音色是**它自己的** —— 应用没法往别人家的引擎里塞音色文件 (没有这种
 * API), 所以"想换一个声音"这件事只有自带一条引擎才做得到。而这件事的代价很实在, 所以默认仍然是
 * 系统 TTS, 这里只是"想要特定音色时"才切过来:
 *
 * - **慢**: CPU 推理, 首字要几百毫秒到一秒; 系统 TTS 是即时的
 * - **占内存**: 一个中文 VITS 约 120 MB 常驻 (识别模型那 240 MB 还在旁边)
 * - **模型不在包里**: 主人自己下、自己放 (见下), 所以 APK 不涨
 *
 * 音色放在工作区的 `voices/<名字>/` 下, 一个目录算一个音色 —— 放这里是因为**工作区是真实路径**,
 * 用文件管理器拷进去最省事, 而 SAF 给的那套 `content://` 树原生库读不了
 *
 * 认哪些模型: 按目录里的文件认, 不认目录名。VITS (`model.onnx`)、Matcha (`model.onnx` +
 * `vocoder.onnx`)、Kokoro (`model.onnx` + `voices.bin`)。认不出来就如实说认不出来, 不装作能用
 */
internal object LwTts {

    /** 模型家族: 配置文件不一样, 而认目录靠的是文件组合 */
    internal enum class Family(val label: String) {
        VITS("VITS"),
        MATCHA("Matcha"),
        KOKORO("Kokoro"),
    }

    /**
     * 一个音色
     *
     * [problem] 不为空表示这个目录用不了, 而那句话就是原因 (缺哪个文件 / 认不出是什么模型) ——
     * 设置页照原样显示它, 所以"为什么用不了"永远看得到
     */
    internal class Voice(
        val name: String,
        val directory: File,
        val family: Family?,
        val problem: String?,
    ) {
        val usable: Boolean get() = problem == null
    }

    /** 已经建起来的引擎: 换目录或换家族才重建 (建一次要读上百 MB 模型) */
    private class Loaded(val directory: String, val family: Family, val tts: OfflineTts)

    private val lock = Any()
    private var loaded: Loaded? = null

    /** 念的时候把引擎放掉用, 半双工那道闸与系统那条共用一个标记 */
    private val playing = AtomicBoolean(false)

    /** 音色放在哪: 工作区根的 `voices/`。设置页把这条路径显示出来, 不然没人知道该往哪拷 */
    fun root(context: Context): File = File(Workspace.resolve(context).directory, ROOT)

    /**
     * 扫一遍有哪些音色
     *
     * 排序按名字, 而每个目录都要过一遍文件检查 —— 这样"放进去但放错了"当场就能看见原因
     */
    fun list(context: Context): List<Voice> {
        val root = root(context)
        val directories = root.listFiles { file -> file.isDirectory }?.sortedBy { it.name }.orEmpty()
        return directories.map { directory -> inspect(directory) }
    }

    /** 按目录里的文件认家族, 顺便说清缺什么 */
    private fun inspect(directory: File): Voice {
        val tokens = File(directory, "tokens.txt").isFile
        val model = File(directory, "model.onnx").isFile
        if (!tokens) return Voice(directory.name, directory, null, "缺 tokens.txt")
        if (!model) return Voice(directory.name, directory, null, "缺 model.onnx")
        val family = when {
            File(directory, "voices.bin").isFile -> Family.KOKORO
            File(directory, "vocoder.onnx").isFile -> Family.MATCHA
            File(directory, "lexicon.txt").isFile -> Family.VITS
            else -> null
        }
        if (family == null) {
            return Voice(
                directory.name, directory, null,
                "认不出这是什么模型: 有 model.onnx 与 tokens.txt, 但既没有 lexicon.txt (VITS) 也没有 vocoder.onnx (Matcha) 或 voices.bin (Kokoro)",
            )
        }
        // 中文这两条都要 jieba 的分词目录, 少了它只会念出拼音一样的怪音, 所以当面报出来
        if (family != Family.KOKORO && !File(directory, "dict").isDirectory) {
            return Voice(directory.name, directory, family, "缺 dict/ 这个分词目录 (中文模型要它)")
        }
        return Voice(directory.name, directory, family, null)
    }

    /**
     * 念一段, 回来一句人话
     *
     * 半双工与系统那条共用一个标记 ([VoiceState.speaking]): 出声的时候采集链一个样本都不吃, 所以
     * 换成自带引擎也不会把喇叭里的字录回去
     */
    fun speak(context: Context, voice: Voice, text: String, speed: Float): String {
        val tts = try {
            engine(voice)
        } catch (error: Throwable) {
            return "the on-device engine would not come up: ${error.message ?: error}"
        }
        val pieces = LwSpeak.chunk(text, CHUNK_CHARS)
        if (pieces.isEmpty()) return "there is nothing to say"
        val started = System.currentTimeMillis()
        VoiceState.speaking = true
        playing.set(true)
        try {
            pieces.forEach { piece ->
                val audio = tts.generate(piece, SPEAKER, speed)
                if (audio.samples.isEmpty()) throw IllegalStateException("the model produced no audio")
                if (!play(audio.samples, audio.sampleRate, piece.length)) {
                    return "the model made ${audio.samples.size} samples but playback did not finish"
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "on-device speech failed", error)
            return "the on-device engine failed: ${error.message ?: error}"
        } finally {
            playing.set(false)
            VoiceState.speaking = false
        }
        val taken = System.currentTimeMillis() - started
        return "said ${text.length} characters in ${pieces.size} piece(s) with ${voice.name} in ${taken} ms"
    }

    /** 正在出声 (供状态查询) */
    val speaking: Boolean get() = playing.get()

    /** 把引擎还回去 (下一次 speak 会为当时那个目录再建一个) */
    fun release() {
        val held = synchronized(lock) {
            val current = loaded
            loaded = null
            current
        }
        held?.tts?.release()
    }

    private fun engine(voice: Voice): OfflineTts {
        val held = loaded
        if (held != null && held.directory == voice.directory.absolutePath && held.family == voice.family) {
            return held.tts
        }
        return synchronized(lock) {
            val current = loaded
            if (current != null && current.directory == voice.directory.absolutePath
                && current.family == voice.family
            ) {
                current.tts
            } else {
                current?.tts?.release()
                loaded = null
                val family = requireNotNull(voice.family) { "an unusable voice has no family" }
                val built = OfflineTts(assetManager = null, config = config(voice.directory, family))
                loaded = Loaded(voice.directory.absolutePath, family, built)
                built
            }
        }
    }

    private fun config(directory: File, family: Family): OfflineTtsConfig {
        val model = File(directory, "model.onnx").absolutePath
        val tokens = File(directory, "tokens.txt").absolutePath
        val lexicon = File(directory, "lexicon.txt").takeIf { it.isFile }?.absolutePath.orEmpty()
        val dict = File(directory, "dict").takeIf { it.isDirectory }?.absolutePath.orEmpty()
        val modelConfig = when (family) {
            Family.VITS -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model,
                    lexicon = lexicon,
                    tokens = tokens,
                    dictDir = dict,
                ),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                provider = "cpu",
            )

            Family.MATCHA -> OfflineTtsModelConfig(
                matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = model,
                    vocoder = File(directory, "vocoder.onnx").absolutePath,
                    lexicon = lexicon,
                    tokens = tokens,
                    dictDir = dict,
                ),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                provider = "cpu",
            )

            Family.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = model,
                    voices = File(directory, "voices.bin").absolutePath,
                    tokens = tokens,
                    // espeak 那套是英文/多语言要的, 在就给
                    dataDir = File(directory, "espeak-ng-data").takeIf { it.isDirectory }?.absolutePath.orEmpty(),
                    dictDir = dict,
                ),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                provider = "cpu",
            )
        }
        // 数字、日期、电话这类怎么念靠这几个 FST: 模型目录里给了就用, 没给就照字面念
        val rules = RULE_FILES
            .map { File(directory, it) }
            .filter { it.isFile }
            .joinToString(",") { it.absolutePath }
        return OfflineTtsConfig(model = modelConfig, ruleFsts = rules, maxNumSentences = 1)
    }

    /**
     * 放一段采样, 等它真的放完
     *
     * 用 MODE_STATIC: 一次推完整段 (一段最多 [CHUNK_CHARS] 个字, 几秒音频), 播完由标记回调叫醒 ——
     * 比流式写省事, 也不会因为写得太快把尾巴截掉
     */
    private fun play(samples: FloatArray, sampleRate: Int, characters: Int): Boolean {
        val frames = samples.size
        val pcm = ShortArray(frames) { index ->
            (samples[index].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
        val latch = CountDownLatch(1)
        return try {
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(frames)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(ignored: AudioTrack?) = latch.countDown()
                    override fun onPeriodicNotification(ignored: AudioTrack?) = Unit
                },
            )
            track.play()
            // 预算按音频长度给, 再加一段余量: 到点就说没放完, 不无限等
            val budget = (frames * 1000L / sampleRate) + PLAY_TAIL_MS
            val finished = latch.await(budget, TimeUnit.MILLISECONDS)
            if (!finished) Log.w(TAG, "playback of $characters characters did not report finishing")
            finished
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private const val ROOT = "voices"
    private const val TAG = "LwTts"

    /** 单说话人模型都用 0 号; 多说话人的模型要挑声音时再说 */
    private const val SPEAKER = 0

    /** 一段最多几个字: 太长会让一次推理的等待变得难熬, 也不利于中途停 */
    private const val CHUNK_CHARS = 120

    /** 模型目录里可能带的读法规则 (FST): 有就用, 顺序就是这个顺序 */
    private val RULE_FILES = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst")

    /** 播完之后等回调的余量 */
    private const val PLAY_TAIL_MS = 2_000L
}
