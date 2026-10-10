package io.github.yuloong07star.luwi.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 唤醒那一份参数表 ([WakeTuning]) 不许漂
 *
 * 为什么值得单测: 这四个数**只有一个地方能改** (那张表), 而它们是主人 2026-10-09 按真机漏唤醒的
 * 手感点名给的 —— 一次重构、一次"顺手抄到第二处", 就可能把 0.01 抄回 0.25, 而那种错在编译上是看不见的
 *
 * 另一条判据也在这里: 冷却下限与命令尾上限**都要比一个极限小**, 否则 [WakeDecision.afterDelivery]
 * 那条 min 会把冷却吃掉 (下限大于上限时收到的反而是上限)
 */
class WakeTuningTest {

    /** 四个 KWS 参数: 主人点名的就是这四个数 */
    @Test
    fun `四个 KWS 参数是点名的那些`() {
        assertEquals(0.01f, WakeTuning.KEYWORDS_THRESHOLD)
        assertEquals(3.0f, WakeTuning.KEYWORDS_SCORE)
        // 1 是"不要求停顿"那一档: 越大越难唤醒 (解码器要求关键词之后挂这么多帧空白), 见 WakeTuning
        // 那段注释 —— 主人喊完就接着说指令是常态, 抬到 3 等于要 160 ms 静音, 那一句永远等不到
        assertEquals(1, WakeTuning.NUM_TRAILING_BLANKS)
        assertEquals(16, WakeTuning.MAX_ACTIVE_PATHS)
    }

    /** 两个窗: 1.5 s 冷却下限 (主人说 1-2 s), 2 s 命令尾上限 (第二轮从 5 s 收下来的) */
    @Test
    fun `两个窗是点名的那些`() {
        assertEquals(1_500L, WakeTuning.HIT_COOLDOWN_MS)
        assertEquals(2_000L, WakeTuning.COMMAND_TAIL_MAX_MS)
    }

    /** 投出去一句话之后的那条短尾巴: 0.3 s (主人 2026-10-09: "发送问题后就可以收掉") */
    @Test
    fun `投出去之后的短尾巴是三百毫秒`() {
        assertEquals(300L, WakeTuning.SENT_TAIL_MS)
    }

    /** 冷却下限必须小于上限: 反过来的话收窗那一步会把冷却压没 */
    @Test
    fun `冷却下限小于命令尾上限`() {
        assertTrue(WakeTuning.HIT_COOLDOWN_MS <= WakeTuning.COMMAND_TAIL_MAX_MS)
    }
}
