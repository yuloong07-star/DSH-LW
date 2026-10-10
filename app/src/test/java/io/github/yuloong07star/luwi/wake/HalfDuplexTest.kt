package io.github.yuloong07star.luwi.wake

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 半双工那道闸那三条判据 (走法 A, 主人 2026-10-05 定)
 *
 * 为什么值得单测: 这一条原来内联在采集那台 sink 里, 而它的三条判据各自都有过一版错的 ——
 * 只丢样本不作废 (拼出"前半句+后半句")、闭闸时只哑切段而不哑唤醒词 (自唤醒)、以及开闸时忘了喂
 * 某一个消费者。现在它是纯函数, 三条就能一条条钉住, 而 [WakeWordService] 那一侧只负责照着答案做
 *
 * 与 `docs/wake-voice-states.md` 判据 6 是同一条: 那一条量的是"念的期间没被录进去"与"念完之后
 * 那一句是完整的", 这里的三种走法就是它的全部实现
 */
class HalfDuplexTest {

    /** 谁都没在说话: 两个消费者都喂 (正常听着的每一帧都是这一档) */
    @Test
    fun `没在念的时候两个消费者都吃`() {
        assertEquals(HalfDuplex.ACCEPT, HalfDuplex.forward(speaking = false))
    }

    /** 喇叭在说话: 这一帧作废 —— 而且唤醒词跟着一起哑, 不为它开例外 */
    @Test
    fun `念回答的时候这一帧作废`() {
        assertEquals(HalfDuplex.ABANDON, HalfDuplex.forward(speaking = true))
    }

    /**
     * 这个答案**不能**靠"有没有传样本"决定: 采集那一帧无论长短都走同一条路, 判据只有 `speaking`
     * 一个输入 —— 这一条钉的是"函数只有一个入参"这件事本身
     */
    @Test
    fun `判据只有 speaking 一个输入`() {
        val method = HalfDuplex::class.java.getMethod("forward", Boolean::class.javaPrimitiveType)
        assertEquals(1, method.parameterCount)
    }
}
