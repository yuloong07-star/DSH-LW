package io.github.miuzarte.littlewhale.tool

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaPlayer
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

    /**
     * 正在放的那一段
     *
     * 留一个引用是为了**停得下来**: 播放那一侧在等它自己放完, 掐断时要把那个等待叫停 (原来用
     * `AudioTrack` 的标记回调时也一样 —— 掐断之后那个回调不会再来)
     */
    private class Playing(val player: MediaPlayer)

    @Volatile
    private var current: Playing? = null

    /** 这一次念有没有被叫停 (被叫停时报的是"停了", 不是"没放完") */
    @Volatile
    private var stopped = false

    /** 音色放在哪: 工作区根的 `voices/`。设置页把这条路径显示出来, 不然没人知道该往哪拷 */
    fun root(context: Context): File = File(Workspace.resolve(context).directory, ROOT)

    /** 播放用的临时 wav 放哪: 应用自己的 cache (放完就删, 不占用户的空间也不用任何权限) */
    private fun tempRoot(context: Context): File =
        File(context.cacheDir, "read").apply { mkdirs() }

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
     * [volume] 是滑块百分数除以 [SpeakSettings.VOLUME_UNITY] 之后的倍数: 100% 就是 1.0 (一个数都不改,
     * 等于这条模型本来的电平), 300% 就是 3.0, 上限见 [SpeakSettings.maxGain]
     *
     * 半双工与系统那条共用一个标记 ([VoiceState.speaking]): 出声的时候采集链一个样本都不吃, 所以
     * 换成自带引擎也不会把喇叭里的字录回去
     */
    fun speak(
        context: Context,
        voice: Voice,
        text: String,
        speed: Float,
        volume: Float,
    ): String {
        val tts = try {
            engine(voice)
        } catch (error: Throwable) {
            return "the on-device engine would not come up: ${error.message ?: error}"
        }
        val pieces = LwSpeak.chunk(text, CHUNK_CHARS)
        if (pieces.isEmpty()) return "there is nothing to say"
        val gain = gainOf(volume)
        val started = System.currentTimeMillis()
        var frames = 0
        VoiceState.speaking = true
        playing.set(true)
        stopped = false
        try {
            pieces.forEach { piece ->
                if (stopped) return "stopped on request before saying \"${piece.take(SCRIBBLE)}\""
                val audio = tts.generate(piece, SPEAKER, speed)
                // 生成这一段要几秒, 那期间被叫停就算停住了 —— **不能`接着放**然后再回一句"停了",
                // 那是报了一件没发生的事 (2026-10-05 在真机上就是这么露出来的)
                if (stopped) return "stopped on request before saying \"${piece.take(SCRIBBLE)}\""
                if (audio.samples.isEmpty()) throw IllegalStateException("the model produced no audio")
                frames += audio.samples.size
                if (!play(context, audio.samples, audio.sampleRate, piece.length, gain)) {
                    // 被掐断与"没放完"是两件事, 分开说
                    return if (stopped) {
                        "stopped on request while saying \"${piece.take(SCRIBBLE)}\""
                    } else {
                        "the model made ${audio.samples.size} samples but playback did not finish"
                    }
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "on-device speech failed", error)
            return "the on-device engine failed: ${error.message ?: error}"
        } finally {
            playing.set(false)
            current = null
            VoiceState.speaking = false
        }
        val taken = System.currentTimeMillis() - started
        return "said ${text.length} characters in ${pieces.size} piece(s) with ${voice.name} in ${taken} ms"
    }

    /** 正在出声 (供状态查询) */
    val speaking: Boolean get() = playing.get()

    /**
     * 滑块给的倍数换算成增益: 100% 是 1.0 (模型自己的电平), 300% 是 3.0
     *
     * 原来这里是 `coerceIn(0f, 1f) * maxGain()`, 它让 100% 直接变成 3 倍, 而 100% 以上全被夹到
     * 同一个值 —— "想放大"这件事因此整条是坏的: 声音先被顶到上限, 旋钮再往上一点都不动
     */
    internal fun gainOf(volume: Float): Float = volume.coerceIn(0f, SpeakSettings.maxGain())

    /** 交给 MediaPlayer 的临时 wav 的 44 字节头 (单声道 16 bit) */
    private fun wavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val header = ByteArray(44)
        fun put(offset: Int, text: String) {
            text.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        put(0, "RIFF")
        putInt(4, 36 + dataSize)
        put(8, "WAVEfmt ")
        putInt(16, 16)
        header[20] = 1
        header[22] = 1
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)
        header[32] = 2
        header[34] = 16
        put(36, "data")
        putInt(40, dataSize)
        return header
    }
    /**
     * 掐断正在念的
     *
     * 三种情形分开说, 因为它们是三件事:
     *
     * - **正在放**: 停那块 track 并把等待叫醒 (推理那一步掐不断, 原生调用是阻塞的)
     * - **正在生成还没出声**: 只立一个标记, 那一段生成完就不会再放 (原来这里是"什么都不做然后照放",
     *   却在答案里说"停了" —— 报了一件没发生的事, 2026-10-05 真机上测出来的)
     * - **什么都没在念**: 回 null, 由调用方如实说"没东西可停"
     *
     * @returns 一句人话说明停住了什么, 没东西可停时回 null
     */
    fun stop(): String? {
        val busy = playing.get()
        stopped = true
        val held = current ?: return if (busy) "the reading was called off before it spoke" else null
        runCatching { held.player.stop() }
        Log.i(TAG, "on-device playback stopped on request")
        return "the on-device playback was cut off"
    }

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
                numThreads = THREADS,
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
                numThreads = THREADS,
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
                numThreads = THREADS,
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
     * **走 MediaPlayer 而不是 AudioTrack** —— 这是 2026-10-06 在真机上量出来的结论:
     *
     * 原来那条路是 `AudioTrack` + `MODE_STATIC`, 而在这台设备上它**交出去的是静音**: 轨道照样
     * 建起来、时长也照样走满、系统那侧报 `state:started` 且 `mutedState:none`, 可缓冲区里是零 ——
     * `LwTts` 自己量出来的 RMS 是 `0 (-Infinity dBFS)`。同一段正弦拿 `MediaPlayer` 放出来是听得见的
     * (音频自检那四声里, 听得见的正是 MediaPlayer 与 ToneGenerator 那两声), 而**系统那条能出声的
     * TTS 引擎走的也是解码器这一类路**。所以这里改成: 把采样写成临时 wav 交给 MediaPlayer
     *
     * 代价是一次临时文件往返 (每段几十 KB, 落在应用自己的 cache 里, 放完就删), 换来的是"真的出声"
     *
     * [gain] 就是音量那一条: 1.0 是模型自己的电平, 往上乘就是调大。削顶仍然要夹住 (short 出界会绕
     * 回去, 那声音不是变小而是变成噪声), 顺手把夹了多少个样本数出来 —— 音量滑块调到会破的那个位置
     * 是能被看见的, 而不是只能靠耳朵
     */
    private fun play(context: Context, samples: FloatArray, sampleRate: Int, characters: Int, gain: Float): Boolean {
        val rate = if (sampleRate < MIX_RATE) MIX_RATE else sampleRate
        val source = if (rate == sampleRate) samples else LwAudio.resample(samples, sampleRate, rate)
        // 这一步就是"模型有声音而喇叭里全零"唯一会出错的地方, 见 LwAudio 上面那段
        val converted = LwAudio.toPcm16(source, gain)
        val pcm = converted.samples
        val frames = pcm.size
        if (converted.clipped > 0) {
            Log.w(TAG, "gain ${"%.2f".format(gain)} clipped ${converted.clipped} of $frames samples")
        }
        var rms = 0.0
        for (value in pcm) rms += value.toDouble() * value
        rms = if (frames == 0) 0.0 else kotlin.math.sqrt(rms / frames)
        // 一行日志就够: 帧数、增益与**交给系统之前的真实电平** (0 就说明交出去的是静音)
        Log.i(
            TAG,
            "playing $frames frames at ${rate}Hz (model $sampleRate) gain ${"%.2f".format(gain)}" +
                " rms ${"%.0f".format(rms)} (${"%.1f".format(20 * kotlin.math.log10(rms / 32768.0))} dBFS)" +
                " via MediaPlayer",
        )
        if (frames == 0) return false
        val file = File.createTempFile("lw-read", ".wav", tempRoot(context))
        val player = MediaPlayer()
        val done: Boolean
        try {
            file.outputStream().use { stream ->
                stream.write(wavHeader(frames * 2, rate))
                val bytes = ByteArray(frames * 2)
                for (index in 0 until frames) {
                    val value = pcm[index].toInt()
                    bytes[index * 2] = (value and 0xFF).toByte()
                    bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                }
                stream.write(bytes)
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            current = Playing(player)
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
            // 预算按音频长度给, 再加一段余量: 到点就说没放完, 不无限等
            val budget = (frames * 1000L / rate) + PLAY_TAIL_MS
            val deadline = System.currentTimeMillis() + budget
            while (player.isPlaying && System.currentTimeMillis() < deadline) {
                if (stopped) break
                Thread.sleep(20)
            }
            done = !player.isPlaying && !stopped
            if (!done && !stopped) Log.w(TAG, "playback of $characters characters did not report finishing")
        } finally {
            current = null
            runCatching { player.release() }
            runCatching { file.delete() }
        }
        return done
    }

    private const val ROOT = "voices"
    private const val TAG = "LwTts"

    /** 交给系统的那条流用 48 kHz: 见 [play] 那段注释 */
    private const val MIX_RATE = 48_000

    /**
     * 推理用几个线程
     *
     * **钉成 1 不是保守, 是这台设备上量出来的**: 这台 mt6989 上 sherpa-onnx 的多线程推理踩过两次
     * 坑 —— 2026-10-05 的 ASR 频繁 SIGSEGV 最后也是单线程解决的, 而 2026-10-06 自带 TTS "推理跑完
     * 但输出全是零"同样只在多线程下出现 (开发机 4 线程完全正常)。核数在这里没有意义, 出声才有
     */
    private const val THREADS = 1

    /** 单说话人模型都用 0 号; 多说话人的模型要挑声音时再说 */
    private const val SPEAKER = 0

    /**
     * 一段最多几个字
     *
     * 这个数是**停止的粒度**: 一次推理掐不断 (原生调用是阻塞的), 所以"停"最迟在这一段生成完 + 放完
     * 之后生效。原来给的 120 字能让一次叫停等上三秒, 60 字大约一半
     */
    private const val CHUNK_CHARS = 60

    /** 被叫停时回话里带多少个字, 够认出停在哪一句就够 */
    private const val SCRIBBLE = 12

    /** 模型目录里可能带的读法规则 (FST): 有就用, 顺序就是这个顺序 */
    private val RULE_FILES = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst")

    /** 播完之后等回调的余量 */
    private const val PLAY_TAIL_MS = 2_000L
}
