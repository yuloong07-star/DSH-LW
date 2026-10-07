package io.github.miuzarte.littlewhale.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「回应用」那一次请求的两条判据
 *
 * 这一条值得单测是因为它的错法是静默的: `BallReturn` 是界面那一侧 `LaunchedEffect` 的钥匙, 而 Compose
 * 的状态在"值没变"时不会通知任何人 —— 如果同一个会话连点两次拿到的是同一个对象, 第二次就什么都不发生
 * (主人 2026-10-07 报的"在 dsh 应用里双击回复框没有任何反馈"就落在这一档)
 */
class BallReturnTest {

    @Test
    fun `同一个会话连问两次也一定是一次新的请求`() {
        BallReturn.ask("session-a")
        val first = BallReturn.request
        assertEquals("session-a", first?.session)

        BallReturn.ask("session-a")
        val second = BallReturn.request
        assertEquals("session-a", second?.session)
        assertTrue(
            "第二次必须是新的序号, 否则界面那一步不会被叫到",
            (second?.seq ?: 0L) > (first?.seq ?: 0L),
        )
    }

    @Test
    fun `做完旧的请求不许把新来的那一个清掉`() {
        BallReturn.ask("session-b")
        val first = BallReturn.request
        BallReturn.ask("session-b")
        val second = BallReturn.request

        BallReturn.done(first?.seq ?: 0L)
        assertEquals("旧的收尾不该吃掉第二下", second, BallReturn.request)

        BallReturn.done(second?.seq ?: 0L)
        assertNull(BallReturn.request)
    }

    @Test
    fun `空会话不进请求`() {
        BallReturn.ask("session-c")
        val before = BallReturn.request
        BallReturn.ask(null)
        BallReturn.ask("")
        BallReturn.ask("   ")
        assertEquals("空白不该盖掉已经在办的那一次", before, BallReturn.request)
        BallReturn.done(before?.seq ?: 0L)
        assertNull(BallReturn.request)
    }
}
