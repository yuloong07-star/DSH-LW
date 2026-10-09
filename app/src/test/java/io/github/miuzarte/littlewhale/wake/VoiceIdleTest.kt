package io.github.miuzarte.littlewhale.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这一句话的窗口什么时候收: 出字与 VAD 的人声都算一次活动 (主人 2026-10-06 定)
 *
 * 为什么值得单测: 只看出字的那一版有一条真机上够得着的错 —— 一口气不停顿地说得比 VOICE_IDLE_MS
 * 还长时, VAD 还没切出段 (要 0.8 s 静音, 或者到 15 s 上限才强切), 看门狗于是从句子中间把窗口收回
 *
 * 抽成纯函数之后这一条能没有设备地钉住, 而 [WakeWordService] 那一侧只负责把两个时刻喂进来
 */
class VoiceIdleTest {

    /** 最后一句出字之后还没到 10 s: 留着 (追问第二句的那一段就是它) */
    @Test
    fun `出字之后不到十秒不收`() {
        assertFalse(VoiceIdle.expired(now = 9_999, lastTextAt = 0, lastVoiceAt = 0, limit = 10_000))
    }

    /** 正好到点就收 (判据是够了就收, 不是过了才收) */
    @Test
    fun `出字之后满十秒就收`() {
        assertTrue(VoiceIdle.expired(now = 10_000, lastTextAt = 0, lastVoiceAt = 0, limit = 10_000))
    }

    /** 一口气连着说: 出字是 12 s 之前的事, 而 VAD 一秒前还听见人声 —— 这一条就是补的那一条 */
    @Test
    fun `还在说话就不收`() {
        assertFalse(VoiceIdle.expired(now = 12_500, lastTextAt = 500, lastVoiceAt = 11_500, limit = 10_000))
    }

    /** 人声也停下来满 10 s: 收 */
    @Test
    fun `人声也停下十秒就收`() {
        assertTrue(VoiceIdle.expired(now = 21_000, lastTextAt = 500, lastVoiceAt = 11_000, limit = 10_000))
    }

    /** 开门之后一直没人说话: 调用方把开门那一刻当作出字时刻传进来, 所以照样到期 */
    @Test
    fun `开门之后没人说话也到期`() {
        assertTrue(VoiceIdle.expired(now = 10_000, lastTextAt = 0, lastVoiceAt = 0, limit = 10_000))
    }

    /** 两个时刻取**后到的那个**: 出字很新而人声很旧时, 不该被旧的那个提前收回 */
    @Test
    fun `两个时刻取后到的那个`() {
        assertFalse(VoiceIdle.expired(now = 12_000, lastTextAt = 11_500, lastVoiceAt = 1_000, limit = 10_000))
    }

    /* ── 想 / 念期间挂起那一笔账 (主人 2026-10-09: "语音输入没说完自动退出") ── */

    /** 助手还在想: 挂起, 不收 (主人正是在等它想的时候会说下一句) */
    @Test
    fun `正在想的时候不关`() {
        assertTrue(VoiceIdle.held(thinking = true, speaking = false))
    }

    /** 助手正在念: 挂起, 不收 (半双工那道闸这一会儿本来就关着, 收窗等于把麦克风也收走) */
    @Test
    fun `正在念的时候不关`() {
        assertTrue(VoiceIdle.held(thinking = false, speaking = true))
    }

    /** 两个都不在: 不挂起, 该计时就计时 (原来那四条判据一个字没变) */
    @Test
    fun `都没在跑就不挂起`() {
        assertFalse(VoiceIdle.held(thinking = false, speaking = false))
    }

    /* ── 投出去一句之后那一档 (主人 2026-10-09: "发送问题后就可以收掉" + "要看还在不在说") ── */

    /** 还没说到一句: 上限就是调用方给的 10 s */
    @Test
    fun `还没说到一句时上限是十秒`() {
        assertEquals(10_000L, VoiceIdle.limit(delivered = false, limit = 10_000L))
    }

    /** 投出去一句之后: 上限掉到那条短尾巴 */
    @Test
    fun `投出去一句之后上限是那条短尾巴`() {
        assertEquals(WakeTuning.SENT_TAIL_MS, VoiceIdle.limit(delivered = true, limit = 10_000L))
    }

    /**
     * 投出去之后**人声照样续期**: 接着说不收, 一停下来就收
     *
     * 这一条与 [VoiceIdleTest] 上面那些的区别只在"上限" —— 服务那一侧还会把"想 / 念期间挂起"这一档
     * 关掉 (见 `WakeWordService.startWatchdog` 里那个 `!voiceDelivered` 的分支), 所以问一句之后
     * 等回答的那几十秒里窗口照样会收
     */
    @Test
    fun `投出去之后还在说就不收停了就收`() {
        val sentAt = 10_000L
        val limit = VoiceIdle.limit(delivered = true, limit = 10_000L)
        // 还在说: 最后一次人声是 2 秒前投出的那一句之后
        assertFalse(VoiceIdle.expired(now = 12_200, lastTextAt = sentAt, lastVoiceAt = 12_000, limit = limit))
        // 停了: 人声之后够一条尾巴就收
        assertTrue(VoiceIdle.expired(now = 12_000 + WakeTuning.SENT_TAIL_MS, lastTextAt = sentAt, lastVoiceAt = 12_000, limit = limit))
    }
}
