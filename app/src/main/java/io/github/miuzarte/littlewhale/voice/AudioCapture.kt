package io.github.miuzarte.littlewhale.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一路采集, 谁要谁挂上来
 *
 * 这东西存在的唯一理由就是**只能开一路麦克风**: 唤醒词 (KWS) 与切段 (silero VAD) 是同一段音频的
 * 两个消费者, 各开一个 `AudioRecord` 会互相抢设备 —— 后开的那个拿不到数据, 而已有的那路会静默
 * 变成噪声, 两边都不报错。所以采集收在这里, 读一次广播给所有 [Sink]
 *
 * 形状是刻意的: 16 kHz 单声道 PCM16, 每次读 100 ms (与 sherpa-onnx 那个 Android 样例同一个
 * 尺寸), 读到的短整数当场转成 [-1, 1) 的浮点 —— 两个消费者要的都是浮点
 *
 * [Sink.accept] 在采集线程上被调用, 所以消费者**必须快**: VAD 一次窗口是几十微秒, 而一整段语音
 * 识别是几百毫秒, 后者要自己挪到另一条线程去 (见 [SpeechSegmenter] 与 `WakeWordService` 里那个
 * 转写线程)。同时**不许把传进去的数组留着** —— 每一帧都复用同一块内存, 留下它就是留下一个会被
 * 下一帧覆盖的视图
 */
internal class AudioCapture(private val onError: (String) -> Unit) {

    /** 一个消费者: 拿到的是这一帧的采样, 只在这一刻有效 */
    fun interface Sink {
        fun accept(samples: FloatArray)
    }

    private val sinks = CopyOnWriteArrayList<Sink>()

    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /** 采集线程现在在跑没有 (与麦克风是否真的打开是两件事, 但正常时同进同退) */
    val isRunning: Boolean get() = running

    fun add(sink: Sink) {
        sinks += sink
    }

    fun remove(sink: Sink) {
        sinks -= sink
    }

    /**
     * 开麦并起采集线程
     *
     * 失败时回 false 并把原因交给 [onError], 这里**不抛**: 调用方是前台服务, 它要把"缺权限"与
     * "设备没有可用的麦克风缓冲"分开报出去
     */
    fun start(): Boolean {
        if (running) return true
        val device = try {
            open()
        } catch (error: Throwable) {
            onError("the microphone would not open: ${error.message ?: error}")
            return false
        }
        recorder = device
        running = true
        try {
            device.startRecording()
        } catch (error: Throwable) {
            running = false
            recorder = null
            runCatching { device.release() }
            onError("the microphone would not start recording: ${error.message ?: error}")
            return false
        }
        worker = Thread({ loop(device) }, "lw-audio-capture").apply { start() }
        return true
    }

    /** 收工: 先让循环退出, 再放麦克风, 顺序反了会在读的地方抛 */
    fun stop() {
        if (!running && recorder == null) return
        running = false
        worker?.let { runCatching { it.join(WORKER_JOIN_MS) } }
        worker = null
        recorder?.let { device ->
            runCatching { device.stop() }
            runCatching { device.release() }
        }
        recorder = null
    }

    private fun loop(device: AudioRecord) {
        val buffer = ShortArray(FRAME_SAMPLES)
        while (running) {
            val read = try {
                device.read(buffer, 0, buffer.size)
            } catch (error: Throwable) {
                onError("reading the microphone failed: ${error.message ?: error}")
                return
            }
            if (read <= 0) continue
            val samples = FloatArray(read) { buffer[it] / 32768.0f }
            for (sink in sinks) {
                // 一个消费者炸了不能把采集带走: 另一个消费者还在听, 而"唤醒词还能用"比"VAD 出错"
                // 重要得多, 所以这里逐个吞掉并留一行日志
                try {
                    sink.accept(samples)
                } catch (problem: Throwable) {
                    Log.w(TAG, "a sink threw on ${read} samples and was skipped this frame", problem)
                }
            }
        }
    }

    private fun open(): AudioRecord {
        val bytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (bytes <= 0) throw IllegalStateException("this device reports no usable microphone buffer")
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            // 两倍是留一段余量: 一段读 100 ms, 缓冲只有一段长就会被读空
            bytes * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("the audio recorder came back uninitialized")
        }
        return record
    }

    companion object {
        /** 采集与两个模型都按这个采样率, 别处对不上就是别处错 */
        const val SAMPLE_RATE = 16000

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 100 ms 一帧, 与 sherpa-onnx 那个 Android 样例同一个尺寸 */
        const val FRAME_SAMPLES = SAMPLE_RATE / 10

        private const val WORKER_JOIN_MS = 2000L
        private const val TAG = "LwVoice"
    }
}
