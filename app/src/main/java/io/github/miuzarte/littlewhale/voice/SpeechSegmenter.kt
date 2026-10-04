package io.github.miuzarte.littlewhale.voice

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.security.MessageDigest
import java.util.Arrays

/**
 * 把一路音频切成一段一段的话
 *
 * 切段用的是 silero VAD (`Vad`, 与唤醒词那个 `KeywordSpotter` 同一个 AAR, 所以不新增依赖也不涨
 * APK), 模型是一个 1.8 MB 的 onnx 文件, 落在 filesDir 里由宿主那侧下载
 *
 * 四个数是主人定的, 集中在这里**只此一份** (纪律: 参数先落表再写码):
 *
 * | 数 | 值 | 说的是什么 |
 * | :-- | :-- | :-- |
 * | [MIN_SPEECH_SECONDS] | 0.25 s | 短于这个的响声不算一句话 (咳嗽 / 关门 / 敲桌子) |
 * | [MIN_SILENCE_SECONDS] | 3.0 s | 静音这么久就把当前这段收尾交出去 |
 * | [MAX_SPEECH_SECONDS] | 15.0 s | 一句话说到这个长度被强制切断 —— 一直说下去不能永远不成段 |
 * | [THRESHOLD] | 0.5 | silero 自己的判决阈值, 与 sherpa-onnx 的缺省一致 |
 *
 * 喂给 `Vad` 的**必须是整窗口** ([WINDOW_SIZE] = 512, 16 kHz 下 32 ms): 采集那一侧交来的是
 * 100 ms 一帧, 所以这里自己攒够一个窗口再喂, 不拿帧长去赌原生那一侧的缓冲行为
 *
 * 它跑在采集线程上, 所以自己只做 VAD (几十微秒)。切出来的整段话交给 [onSegment], 而那一段的识别
 * 是调用方的事 —— 在采集线程上认一段话会把采集卡住几百毫秒, 那就是丢音频
 */
internal class SpeechSegmenter private constructor(
    private val vad: Vad,
    private val onSegment: (FloatArray) -> Unit,
) : AudioCapture.Sink {

    /** 攒窗口用的那一块, 一直是同一个 */
    private val window = FloatArray(WINDOW_SIZE)
    private var filled = 0

    /** 已经切出去几段, 供状态显示 */
    @Volatile
    var segments: Int = 0
        private set

    /** 这一段是不是因为到了 [MAX_SPEECH_SECONDS] 被切断的 (上一段), 供调用方如实说明 */
    @Volatile
    var lastWasTruncated: Boolean = false
        private set

    override fun accept(samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val take = minOf(WINDOW_SIZE - filled, samples.size - offset)
            System.arraycopy(samples, offset, window, filled, take)
            filled += take
            offset += take
            if (filled == WINDOW_SIZE) {
                filled = 0
                vad.acceptWaveform(window)
                drain()
            }
        }
    }

    /**
     * 收尾
     *
     * 停听的时候不能把手里那半段话扔掉: 最后一句很可能正好说完, 差的就是那几十毫秒的尾巴。补零到
     * 一个整窗口交进去, 再让 VAD 把剩下的段吐出来
     */
    fun flush() {
        if (filled > 0) {
            Arrays.fill(window, filled, WINDOW_SIZE, 0f)
            filled = 0
            vad.acceptWaveform(window)
        }
        runCatching { vad.flush() }
        drain()
    }

    fun close() {
        runCatching { vad.release() }
    }

    /** VAD 说一段说完了: 取出来交出去, 再弹掉 —— 不弹的话下一轮还会读到同一段 */
    private fun drain() {
        while (!vad.empty()) {
            val segment = vad.front().samples
            vad.pop()
            if (segment.isEmpty()) continue
            segments += 1
            // 15 s 是被切断的那一条: 到上限时 silero 自己把段交出来, 而它的长度就是那个上限,
            // 所以判据取"够长"的九成, 不去猜它内部到底怎么算的
            lastWasTruncated = segment.size >= (MAX_SPEECH_SECONDS * AudioCapture.SAMPLE_RATE * 0.9f).toInt()
            onSegment(segment)
        }
    }

    companion object {
        /** silero 在 16 kHz 下的一窗: v4 是 512, v5 是 256, 我们钉的这一份是 512 */
        const val WINDOW_SIZE = 512

        /** 与 sherpa-onnx 自己的缺省一致 */
        const val THRESHOLD = 0.5f

        /** 主人 2026-10-05 定的三条 */
        const val MIN_SPEECH_SECONDS = 0.25f
        const val MIN_SILENCE_SECONDS = 3.0f
        const val MAX_SPEECH_SECONDS = 15.0f

        const val MODEL_FILE = "silero_vad.onnx"

        /** 与 dsh 自己那份 `speech-to-text-sensevoice` 钉的是同一个文件、同一个哈希 */
        const val MODEL_BYTES = 1_807_522L
        const val MODEL_SHA256 = "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28"

        /**
         * 模型文件的体检结论: null 表示可以加载
         *
         * 三件事分开说 (没有文件 / 字节数不对 / 哈希不对), 因为处置完全不同 —— 前一条是"还没下",
         * 后两条是"下坏了"或者"这不是那个模型"。哈希在 1.8 MB 上只要十几毫秒, 所以每次加载都算
         */
        fun inspect(model: File): String? {
            if (!model.isFile) {
                return "there is no silero VAD model at ${model.absolutePath}: run lw_speech op=prepare once"
            }
            if (model.length() != MODEL_BYTES) {
                return "the VAD model is ${model.length()} bytes, not $MODEL_BYTES: it is truncated or a different file"
            }
            val actual = digestOf(model)
            if (actual != MODEL_SHA256) {
                return "the VAD model hashes to $actual, not $MODEL_SHA256: it is not the pinned file"
            }
            return null
        }

        /** 建一个切段器; 模型坏了或原生库起不来就抛, 由调用方翻译成人话 */
        fun open(model: File, onSegment: (FloatArray) -> Unit): SpeechSegmenter {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = model.absolutePath,
                    threshold = THRESHOLD,
                    minSilenceDuration = MIN_SILENCE_SECONDS,
                    minSpeechDuration = MIN_SPEECH_SECONDS,
                    windowSize = WINDOW_SIZE,
                    maxSpeechDuration = MAX_SPEECH_SECONDS,
                ),
                tenVadModelConfig = TenVadModelConfig(),
                sampleRate = AudioCapture.SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            return SpeechSegmenter(Vad(assetManager = null, config = config), onSegment)
        }

        private fun digestOf(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
