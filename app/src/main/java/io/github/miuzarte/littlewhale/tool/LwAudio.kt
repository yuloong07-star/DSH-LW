package io.github.miuzarte.littlewhale.tool

/**
 * 模型采样到播放器 PCM 之间那两步纯算术: 升采样与 float -> 16 bit
 *
 * 为什么单开一处: 这两步原来长在 `LwTts` 里, 而 2026-10-06 在真机上遇到的那件事就出在这里 ——
 * 模型峰值 0.238 (有声音) 而交给播放器的采样 RMS 是 0 (-Infinity dBFS): 换算漏了满刻度 32767,
 * 0.238 乘完增益取整之后每个样本都是 0, 两句话因此同时为真
 *
 * 它不碰任何安卓的类, 所以 JVM 单测能直接量 (见 LwAudioTest): 不用设备就能知道"交出去的
 * 是不是静音"
 */
internal object LwAudio {

    /** 满刻度: float 的 1.0 对应 16 bit 有符号样本里的 32767 */
    const val FULL_SCALE = 32767f

    /** 换出来的 PCM 与削顶的样本数 (后者要进日志) */
    class Pcm16(val samples: ShortArray, val clipped: Int)

    /**
     * 乘上增益再落到 16 bit PCM
     *
     * 夹取在 float 这一侧做完再取整: 先转 Int 再比的话, 出界的值早就绕回去了, 而绕回去不是变小
     * 是噪声
     */
    fun toPcm16(samples: FloatArray, gain: Float): Pcm16 {
        var clipped = 0
        val pcm = ShortArray(samples.size) { index ->
            val value = samples[index] * gain * FULL_SCALE
            when {
                value.isNaN() -> 0.toShort()
                value > Short.MAX_VALUE -> {
                    clipped++
                    Short.MAX_VALUE
                }
                value < Short.MIN_VALUE -> {
                    clipped++
                    Short.MIN_VALUE
                }
                else -> value.toInt().toShort()
            }
        }
        return Pcm16(pcm, clipped)
    }

    /**
     * 线性插值升采样
     *
     * 16 kHz 的语音升到 48 kHz 只需要在每两个样本之间插两个点, 而线性插值在语音上听不出差别 ——
     * 换来的是 AudioFlinger 那条路上少一次重采样 (设备侧对 16 kHz 的流有自己的处理)
     *
     * 长度先按 Long 算再落到 Int: 60 个字的一段在 16 kHz 下就是十几万样本, 先乘 48000 会溢出,
     * 而溢出的长度不是报错是**静默变小** —— 长句会被悄悄截掉一半
     */
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (samples.isEmpty() || from <= 0 || to <= from) return samples
        val frames = (samples.size.toLong() * to / from + 1)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val out = FloatArray(frames)
        val step = from.toDouble() / to
        for (index in out.indices) {
            val at = index * step
            val base = at.toInt()
            if (base >= samples.size - 1) {
                out[index] = samples[samples.lastIndex]
                continue
            }
            val fraction = (at - base).toFloat()
            out[index] = samples[base] + (samples[base + 1] - samples[base]) * fraction
        }
        return out
    }
}
