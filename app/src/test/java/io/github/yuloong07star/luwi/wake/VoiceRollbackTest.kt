package io.github.yuloong07star.luwi.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "开了又关"那条快径的判据 (2026-10-10 主人: "三击 ball 会打开语音输入, 并且会卡一下")
 *
 * 为什么值得单测: 这是一条**只挡开销、不挡行为**的闸 —— 判错一边会白读一次几百 MB 的识别模型并把
 * 通知栏闪一下 (主人报的那一声"卡"), 判错另一边会在主人真说了话的时候跳过收尾。三个边界 (窗口
 * 299/300/301 ms) 与两个"听过东西"的记号各钉一条, 而 [WakeWordService] 那一侧只负责照着答案做
 */
class VoiceRollbackTest {

    private val window = WakeWordService.VOICE_ROLLBACK_MS

    /** 开了 200 ms、一个人声都没听到: 快径 (预热与通知那两句都还没发生) */
    @Test
    fun `窗口里没听到人声就走快径`() {
        val opened = 1_000_000L
        assertTrue(VoiceRollback.isFast(opened + 200, opened, heardAt = 0L, delivered = false, windowMs = window))
    }

    /**
     * 窗口的两条边: **开的那一瞬与压在上沿都算**, 过 1 ms 就不算了
     *
     * 上沿这一条是必要的 —— [WakeWordService.openVoice] 是"开门那一刻"记的时间, 而收窗是同一条
     * 主线程上稍后一拍的事, 差 0 ms 与差 300 ms 都够得着
     */
    @Test
    fun `快径窗口的边界`() {
        val opened = 1_000_000L
        assertTrue("刚开门就收", VoiceRollback.isFast(opened, opened, 0L, false, window))
        assertTrue("299 ms", VoiceRollback.isFast(opened + 299, opened, 0L, false, window))
        assertTrue("正好压在上沿也算", VoiceRollback.isFast(opened + 300, opened, 0L, false, window))
        assertFalse("301 ms 就不算快径了", VoiceRollback.isFast(opened + 301, opened, 0L, false, window))
    }

    /**
     * **听到过一窗人声就不算** —— 这是这条闸最重要的一边
     *
     * VAD 每 32 ms 报一次有人声的窗, 而它一报就说明主人真的在说话: 那时窗口不该被当成"根本没打开过"
     * 静默回退, 预热与收尾都该照常走完
     */
    @Test
    fun `听到过人声就不走快径`() {
        val opened = 1_000_000L
        assertFalse(
            "人声是 1 ms 前才听到的, 也算听到过",
            VoiceRollback.isFast(opened + 200, opened, heardAt = opened + 199, delivered = false, windowMs = window),
        )
        assertFalse(
            "人声在这一段之前听到的 (刚开门就有人说话)",
            VoiceRollback.isFast(opened + 200, opened, heardAt = opened, delivered = false, windowMs = window),
        )
    }

    /** 已经出过字 (投出去一句) 的那一次不算"开了又关": 主人真的说完了, 该照常收尾 */
    @Test
    fun `出过字就不走快径`() {
        val opened = 1_000_000L
        assertFalse(
            VoiceRollback.isFast(opened + 200, opened, heardAt = 0L, delivered = true, windowMs = window),
        )
    }

    /**
     * 不知道什么时候开的门 (openedAt = 0) 一律不当快径
     *
     * 那一种只出现在"服务自己刚起来、这一次门是视频模式铺的"这类路上 —— 判成快径会把通知栏那两句
     * 也一起吞掉, 而那种门本来就是常驻的, 不该省
     */
    @Test
    fun `不知道开关时刻就不当快径`() {
        assertFalse(VoiceRollback.isFast(1_000_000L, openedAt = 0L, heardAt = 0L, delivered = false, windowMs = window))
    }

    /**
     * 快径窗口比"短命的一次"那个读数窄: **两个数是两件事**
     *
     * [WakeWordState.shortLivedOpens] 记的是"这一声门是不是白开了"(500 ms), 而快径管的是"那两笔还没
     * 开始的活要不要取消"(300 ms)。窄的那个先收, 于是主人看到的读数与真正省下的开销对得上
     */
    @Test
    fun `快径窗口比短命读数窄`() {
        assertTrue(WakeWordService.VOICE_ROLLBACK_MS < WakeWordService.VOICE_SHORT_LIVED_MS)
    }
}
