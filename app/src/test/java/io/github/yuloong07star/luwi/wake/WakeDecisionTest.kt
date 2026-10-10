package io.github.yuloong07star.luwi.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命中之后的去抖 ([WakeDecision])
 *
 * 为什么值得单测: 阈值从 0.25 降到 0.01 之后, "同一个词连着报两遍"与"指令前半段被当成下一次唤醒"
 * 这两件事在真机上都会变多, 而压它们的这段算术**在模拟器上完全跑不到** (KWS 那一路的原生库在 linker
 * 就崩)。所以三条判据 (冷却下限 / 命令尾上限 / 第一段出字收窗) 在这里逐条钉住
 */
class WakeDecisionTest {

    /** 窗里不算数, 到点才算 —— 判据是"够了就放行", 与 [VoiceIdle] 那条同一个口径 */
    @Test
    fun `冷却窗里不算命中`() {
        assertFalse(WakeDecision.accepts(now = 1_499, mutedUntil = 1_500))
        assertTrue(WakeDecision.accepts(now = 1_500, mutedUntil = 1_500))
    }

    /** 没有静默窗 (还没命中过 / 服务刚起来) 时一律放行 */
    @Test
    fun `没有静默窗时放行`() {
        assertTrue(WakeDecision.accepts(now = 0, mutedUntil = 0))
        assertTrue(WakeDecision.accepts(now = 12_345, mutedUntil = 0))
    }

    /** 命中那一下立起来的是**上限**: 命令尾最多静默 [WakeTuning.COMMAND_TAIL_MAX_MS] */
    @Test
    fun `命中立起来的是五秒上限`() {
        assertEquals(1_000 + WakeTuning.COMMAND_TAIL_MAX_MS, WakeDecision.afterHit(1_000))
    }

    /** 第一段出字来得很早 (几百毫秒): 静默窗收到冷却下限, 不能比它更早 */
    @Test
    fun `出字太早也留够冷却下限`() {
        val hitAt = 10_000L
        val cap = WakeDecision.afterHit(hitAt)
        assertEquals(hitAt + WakeTuning.HIT_COOLDOWN_MS, WakeDecision.afterDelivery(hitAt + 300, hitAt, cap))
    }

    /** 出字来得比冷却下限晚、但还在上限里 (说话长): 收到"出字那一刻", 命令已经认完了 */
    @Test
    fun `出字晚了就收到出字那一刻`() {
        val hitAt = 10_000L
        val cap = WakeDecision.afterHit(hitAt)
        // 要落在冷却下限 (1.5 s) 与上限 (2 s) 之间: 超过了那是"迟到"那一档 (下面一条)
        val deliveredAt = hitAt + 1_800
        assertEquals(deliveredAt, WakeDecision.afterDelivery(deliveredAt, hitAt, cap))
    }

    /** 出字来得比上限还晚时只收到上限为止, 不把窗往后拖 */
    @Test
    fun `出字迟到也不拖过上限`() {
        val hitAt = 10_000L
        val cap = WakeDecision.afterHit(hitAt)
        assertEquals(cap, WakeDecision.afterDelivery(hitAt + 9_000, hitAt, cap))
    }

    /** 没有静默窗时收窗那一步回 0, 调用方不必自己分支 */
    @Test
    fun `没有静默窗时收窗回零`() {
        assertEquals(0L, WakeDecision.afterDelivery(now = 5_000, hitAt = 0, mutedUntil = 0))
    }

    /** 一次完整的命中: 立窗 -> 出字收窗 -> 过冷却下限才再放行 */
    @Test
    fun `一次命中的三段`() {
        val hitAt = 1_000L
        val cap = WakeDecision.afterHit(hitAt)
        val until = WakeDecision.afterDelivery(now = hitAt + 200, hitAt = hitAt, mutedUntil = cap)
        assertFalse(WakeDecision.accepts(until - 1, until))
        assertTrue(WakeDecision.accepts(until, until))
        assertTrue(until <= cap)
    }
}
