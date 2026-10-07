package io.github.miuzarte.littlewhale.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * 模型采样 -> 播放器 PCM16 这一步的算术
 *
 * 这一层是"模型有声音"与"喇叭里全零"之间唯一的一道台阶: 2026-10-06 在真机上量到模型峰值 0.238,
 * 而同一段采样交给播放器的 RMS 是 0 (-Infinity dBFS) —— 两件事同时为真的唯一解释就在这里
 *
 * 三条不开设备的断言: **换算不能把语音取整成零** (漏掉满刻度就会), **增益按滑块来** (100% 是
 * 1.0 而不是 3.0), **升采样既不静音也不丢帧** (长度按 Long 算, 长句不会算出错误的长度)
 */
class LwAudioTest {

    /** 这条音色在开发机上量到的峰值: 0.238 (-11 dBFS) */
    private val speechPeak = 0.23768462f

    /** 一段像语音的采样: 峰值 [peak] 的正弦, 默认 24_448 帧 (1536ms @16k) —— 与真机上那一段同量级 */
    private fun speech(peak: Float = speechPeak, frames: Int = 24_448): FloatArray =
        FloatArray(frames) { index -> (peak * sin(index * 0.05)).toFloat() }

    /* ── float -> PCM16: 漏掉满刻度就是"模型有声音, 播出去全是零" ────────── */

    @Test
    fun `模型电平的采样乘上 3 倍增益还是听得见的 PCM16`() {
        val pcm = LwAudio.toPcm16(speech(), gain = 3f)
        val peak = pcm.samples.maxOf { abs(it.toInt()) }
        // 0.238 * 3 * 32767 = 23370 (-2.9 dBFS): 300% 该到 23000 上下, 而不是逐样本取整成 0
        assertTrue("峰值 $peak 不在 23000..23400 里", peak in 23_000..23_400)
        assertEquals(0, pcm.clipped)
    }

    @Test
    fun `滑块 100 的增益就是模型自己的电平`() {
        val pcm = LwAudio.toPcm16(floatArrayOf(0.5f, -0.5f, 0f), gain = 1f)
        assertEquals(16_383, pcm.samples[0].toInt())
        assertEquals(-16_383, pcm.samples[1].toInt())
        assertEquals(0, pcm.samples[2].toInt())
    }

    @Test
    fun `出界的样本夹在两端并数出来`() {
        val pcm = LwAudio.toPcm16(floatArrayOf(0.9f, -0.9f, 0.1f), gain = 2f)
        assertEquals(32_767, pcm.samples[0].toInt())
        assertEquals(-32_768, pcm.samples[1].toInt())
        assertEquals(2, pcm.clipped)
    }

    /* ── 升采样: 插入的帧数与幅度都要对 ─────────────────────────────────── */

    @Test
    fun `16k 升到 48k 的帧数与幅度都留得住`() {
        val samples = speech()
        val out = LwAudio.resample(samples, 16_000, 48_000)
        // 真机上那一段就是这个数: 24_448 * 3 + 1 = 73_345
        assertEquals(24_448 * 3 + 1, out.size)
        val peak = out.maxOf { abs(it) }
        assertTrue("峰值 $peak 与原始的 $speechPeak 差太多", abs(peak - speechPeak) < 1e-4f)
    }

    @Test
    fun `两点之间的中点落在中点上`() {
        val out = LwAudio.resample(floatArrayOf(0f, 1f), 16_000, 48_000)
        assertEquals(1f / 3f, out[1], 1e-6f)
        assertEquals(2f / 3f, out[2], 1e-6f)
    }

    @Test
    fun `长的一段不会把长度算错`() {
        // 60 个字的一段大约十几秒, 16k 下就是十几万帧 —— 按 Int 先乘 48000 会溢出, 长度会静默变小
        val out = LwAudio.resample(FloatArray(200_000) { 0.1f }, 16_000, 48_000)
        assertEquals(200_000 * 3 + 1, out.size)
    }

    /* ── 增益: 滑块说多少就是多少 ──────────────────────────────────────── */

    @Test
    fun `滑块 100 是不加不减, 300 才是三倍`() {
        assertEquals(1f, LwTts.gainOf(1f), 0f)
        assertEquals(3f, LwTts.gainOf(3f), 0f)
        assertEquals(3f, LwTts.gainOf(9f), 0f)
        assertEquals(0f, LwTts.gainOf(-1f), 0f)
    }
}
