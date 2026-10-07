package io.github.miuzarte.littlewhale.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页那个开关与球的真实存在对不对得上 (2026-10-06 主人报的"两个入口各说各话")
 *
 * 那一次的现象是: 从球的长按菜单里点「关掉浮标」, 屏幕上球还在 (窗没摘), 而设置页那个开关的状态
 * 与它并不一致。窗那一半的根因在 [OverlayService.hideBall] 里 (服务停掉不会让它加的窗自己消失),
 * 而"开关该画成什么"这一半落在这里 —— 它没有设备也能量, 所以这条纪律有一条能跑的红线
 */
class BallSwitchTest {

    /** 球在屏幕上: 开关必须是开 (这是那个开关最直白的含义) */
    @Test
    fun `球在屏幕上就是开`() {
        assertTrue(BallSwitch.onFor(showing = true, remembered = true))
    }

    /**
     * **球在屏幕上、而存盘记号是 false**: 仍然是开
     *
     * 这一格正是故障现场: 关球那条路把记号写下去了 (或者是上一趟失败留下的), 而窗还在。这时开关
     * 必须跟着**看得见的那颗球**走 —— 画成"关"就是"界面替人宣称它关掉了", 而人一抬头球还在
     */
    @Test
    fun `球还在屏幕上时不许画成关`() {
        assertTrue(BallSwitch.onFor(showing = true, remembered = false))
    }

    /**
     * **球不在、而记号说要在** (应用刚起来、服务还没把球放出来那一小段): 仍然是开
     *
     * 反过来画成"关"就是自己把主人的设置擦掉了: 他明明开着, 下一次 `ensure()` 也会照那个记号把球
     * 放出来 —— 开关在这一秒里说"关"是一句假话
     */
    @Test
    fun `记号说要在、球还没起来时也是开`() {
        assertTrue(BallSwitch.onFor(showing = false, remembered = true))
    }

    /** 球不在、记号也是假的: 这才是真的关 (菜单里关掉之后落到的那一格) */
    @Test
    fun `球不在而记号也是假才是关`() {
        assertFalse(BallSwitch.onFor(showing = false, remembered = false))
    }
}
