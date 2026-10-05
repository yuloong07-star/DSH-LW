package io.github.miuzarte.littlewhale.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮标那几处边界的判据: 状态词该说哪个、松手贴哪边、半隐藏多少、拖出去多远、键盘压不压得住
 *
 * 这些都是**没有设备也能量**的算式, 而它们恰恰是最容易在真机上"看着差不多"却差一个球的地方:
 * 半隐藏多了一点点就点不到, 拖动少钳一下球就飞出去, 吸附判反了它永远停在对面 —— 真机上这些症状
 * 都长得像"手感有点怪", 说不到具体哪一个数上
 *
 * 参考的是开源那一份 (Petterpx/FloatingX) 的约定: 半隐是**比例** (`FxHalfHide`, demo 用 0.3),
 * 吸附取**剩余距离最近**的边 (`nearestEdge`), 拖动允许暂时越界再回弹 (`rebound`)
 */
class BallTest {

    private val ball = 144 // 48 dp @ 3x, vivo 那块屏的密度
    private val screen = 1260

    /* ── 状态词: 在念 > 在想 > 在听 ─────────────────────────────────────────── */

    @Test
    fun `什么都没在跑时球上画的是那个标`() {
        assertNull(BallStatus.wordFor(speaking = false, thinking = false, listening = false))
    }

    @Test
    fun `常驻语音在跑时说的是正在听`() {
        assertEquals(BallWord.LISTENING, BallStatus.wordFor(speaking = false, thinking = false, listening = true))
    }

    /**
     * 一轮在跑而麦克风没开: 这是"打字问的"那一情形, 它照样要显示"正在想" —— 用眼睛盯着球的人
     * 此刻等的就是回答
     */
    @Test
    fun `宿主说一轮在跑时说的是正在想`() {
        assertEquals(BallWord.THINKING, BallStatus.wordFor(speaking = false, thinking = true, listening = false))
    }

    /**
     * 念回答那几秒里半双工那道闸把麦克风整个关掉了, 所以"正在听"与"正在念"可以同时为真 ——
     * 这时说的是"正在念", 因为那才是此刻真正发生的事 (听见自己念的话会被录回去, 那不是"在听")
     */
    @Test
    fun `念回答的时候正在念盖过正在听`() {
        assertEquals(BallWord.SPEAKING, BallStatus.wordFor(speaking = true, thinking = false, listening = true))
    }

    @Test
    fun `念回答的时候正在念也盖过正在想`() {
        assertEquals(BallWord.SPEAKING, BallStatus.wordFor(speaking = true, thinking = true, listening = true))
    }

    @Test
    fun `正在想盖过正在听`() {
        assertEquals(BallWord.THINKING, BallStatus.wordFor(speaking = false, thinking = true, listening = true))
    }

    /* ── 吸附: 取剩余距离最近的那条边 ───────────────────────────────────────── */

    @Test
    fun `贴左边时靠左半屏的都吸到左边`() {
        assertEquals(0, BallGeometry.snapX(0, screen, ball))
        assertEquals(0, BallGeometry.snapX(screen / 2 - ball, screen, ball))
    }

    @Test
    fun `贴右边时靠右半屏的都吸到右边`() {
        assertEquals(screen - ball, BallGeometry.snapX(screen - ball, screen, ball))
        assertEquals(screen - ball, BallGeometry.snapX(screen / 2 + 1, screen, ball))
    }

    /**
     * 屏幕中点那条线: 两边的剩余距离一样, 取左边 —— 这是个**确定的**选择 (不是"随机哪边"),
     * 单测把它钉住, 免得以后改成另一个方向时没人发现
     */
    @Test
    fun `正中间时吸到左边`() {
        val middle = (screen - ball) / 2
        assertEquals(0, BallGeometry.snapX(middle, screen, ball))
    }

    /**
     * 拖出屏幕外之后松手: 距离是"到那条边要走的距离", 负的按 0 算不了 —— 藏出去一半的球
     * (x 是负的) 必须还认得出自己贴的是左边, 否则松手那一刻它会飞到对面去
     */
    @Test
    fun `拖出屏幕外时贴的是它自己那一边`() {
        assertEquals(0, BallGeometry.snapX(-40, screen, ball))
        assertEquals(screen - ball, BallGeometry.snapX(screen + 40, screen, ball))
    }

    @Test
    fun `吸附之后知道自己在哪一边`() {
        assertEquals(BallGeometry.EDGE_LEFT, BallGeometry.edgeFor(0, screen, ball))
        assertEquals(BallGeometry.EDGE_RIGHT, BallGeometry.edgeFor(screen - ball, screen, ball))
    }

    /* ── 半隐: 比例, 不是像素 ───────────────────────────────────────────────── */

    @Test
    fun `半隐按球宽的比例藏`() {
        val hidden = (ball * BallGeometry.HALF_HIDE).toInt()
        assertEquals(0.3f, BallGeometry.HALF_HIDE, 0.0001f)
        assertEquals(43, hidden) // 48 dp 的 30% 在 3 倍密度下是 43 px, 还剩 101 px 看得见
        // 贴左边就往屏幕外推 (x 是负的), 贴右边就往外推一把 —— 两边都是"那一份留在框外"
        assertEquals(-hidden, BallGeometry.peekX(0, BallGeometry.EDGE_LEFT, ball))
        assertEquals(screen - ball + hidden, BallGeometry.peekX(screen - ball, BallGeometry.EDGE_RIGHT, ball))
    }

    /** 藏起来的那一份不许吃掉整个球: 全藏了就点不到了 */
    @Test
    fun `半隐之后还剩得下一半以上`() {
        val visible = ball - (ball * BallGeometry.HALF_HIDE).toInt()
        assertTrue(visible > ball / 2)
    }

    /* ── 拖动: 允许暂时越界 (rebound) ───────────────────────────────────────── */

    @Test
    fun `拖动可以把它推出屏幕到半隐的位置`() {
        val hidden = (ball * BallGeometry.HALF_HIDE).toInt()
        assertEquals(-hidden, BallGeometry.dragX(-9999, screen, ball))
        assertEquals(screen - ball + hidden, BallGeometry.dragX(9999, screen, ball))
    }

    @Test
    fun `拖动中屏幕里面照旧跟手`() {
        assertEquals(300, BallGeometry.dragX(300, screen, ball))
    }

    /* ── 纵向与键盘 ─────────────────────────────────────────────────────────── */

    @Test
    fun `纵向不许出屏`() {
        assertEquals(0, BallGeometry.clampY(-20, 2800, ball))
        assertEquals(2800 - ball, BallGeometry.clampY(9999, 2800, ball))
        assertEquals(700, BallGeometry.clampY(700, 2800, ball))
    }

    /** 拿不到 inset (0 = 没有键盘, 或者这一屏不上报) 时什么都不动: 猜一个高度会让球跳到半空中 */
    @Test
    fun `没有键盘时不动它`() {
        assertEquals(2000, BallGeometry.clampAboveIme(2000, 0, 2800, ball))
    }

    @Test
    fun `键盘起来时把它抬到键盘上沿之上`() {
        val ime = 900
        val keyboardTop = 2800 - ime
        assertEquals(keyboardTop - ball, BallGeometry.clampAboveIme(2600, ime, 2800, ball))
    }

    @Test
    fun `已经在键盘上方的球不动`() {
        assertEquals(300, BallGeometry.clampAboveIme(300, 900, 2800, ball))
    }

    /** 键盘比屏幕还高 (横屏 + 手写键盘那种) 时算出来的位置要落在屏幕里, 不能是负数 */
    @Test
    fun `键盘高过屏幕时球停在顶上`() {
        assertEquals(0, BallGeometry.clampAboveIme(500, 2800, 2800, ball))
    }

    /* ── 换屏之后按存盘的边重算 ─────────────────────────────────────────────── */

    @Test
    fun `存的是边与 y, 换宽度之后重算 x`() {
        assertEquals(0, BallGeometry.xForEdge(BallGeometry.EDGE_LEFT, 2800, ball))
        assertEquals(2800 - ball, BallGeometry.xForEdge(BallGeometry.EDGE_RIGHT, 2800, ball))
    }

    /** 认不出来的边当成右边 (存盘里那个值坏了也不该让球跑到左上角去) */
    @Test
    fun `认不出的边落在右边`() {
        assertEquals(screen - ball, BallGeometry.xForEdge("up", screen, ball))
    }
}
