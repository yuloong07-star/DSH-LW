package io.github.yuloong07star.luwi.host

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
    fun `null 不进请求`() {
        BallReturn.ask("session-c")
        val before = BallReturn.request
        // `null` 是"这个 intent 没带那个键" (比如从桌面图标冷启动那一档), 界面照旧只是被提到前面
        BallReturn.ask(null)
        assertEquals("没带那个键不该盖掉已经在办的那一次", before, BallReturn.request)
        BallReturn.done(before?.seq ?: 0L)
        assertNull(BallReturn.request)
    }

    /**
     * **空会话是一个真的请求: "去新会话界面"** (2026-10-09 主人: "这里没有 (还没发过话) 就进入新会话
     * 界面")
     *
     * 原来它被当成"没点名"直接丢掉 (`ask` 的第一句是 `if (wanted.isEmpty()) return`), 于是"两笔账都
     * 空着"那一档在界面上什么都不发生 —— 而主人要的是新会话。现在记号就是空串 ([BallReturn.NEW_SESSION]),
     * 两种"没有"因此分得开: `null` 是"没带那个键", 空串是"带的是新会话这个意图"
     */
    @Test
    fun `空会话进请求就是去新会话界面`() {
        assertEquals("", BallReturn.NEW_SESSION)
        BallReturn.ask("session-d")
        val before = BallReturn.request
        BallReturn.ask(BallReturn.NEW_SESSION)
        val asked = BallReturn.request
        assertEquals("这一场的记号是空", "", asked?.session)
        assertTrue(
            "空会话也是一次新请求, 界面那一步要被叫到",
            (asked?.seq ?: 0L) > (before?.seq ?: 0L),
        )
        // 空白也按同一个意思收 (从 intent 里读出来那一档的长度是 0~n 个空格)
        BallReturn.ask("   ")
        assertEquals("", BallReturn.request?.session)
        BallReturn.done(BallReturn.request?.seq ?: 0L)
        assertNull(BallReturn.request)
    }
}
