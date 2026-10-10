package io.github.yuloong07star.luwi.wake

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 省电模式那个定时时段: 解析与"此刻在不在里面"
 *
 * 这一条值得单测是因为它的错法是静默的 —— 判据算反只是让麦克风在不该关的时候关着 (或者反过来),
 * 屏幕上没有一处会报错。三种写法 (普通 / 跨零点 / 起止相同) 与四个边界 (起点、终点、两侧各一分钟)
 * 都在这里钉住
 */
class PowerWindowTest {

    @Test
    fun `解析普通时段`() {
        val window = PowerWindow.parse("09:30-18:00")
        assertEquals(PowerWindow.Window(9 * 60 + 30, 18 * 60), window)
    }

    @Test
    fun `全角冒号与波浪线也认`() {
        assertEquals(PowerWindow.Window(23 * 60, 7 * 60), PowerWindow.parse("23：00～07：00"))
    }

    @Test
    fun `只写小时也认`() {
        assertEquals(PowerWindow.Window(23 * 60, 7 * 60), PowerWindow.parse("23-7"))
    }

    @Test
    fun `看不懂的写法回 null`() {
        assertNull(PowerWindow.parse(""))
        assertNull(PowerWindow.parse("晚上十一点"))
        assertNull(PowerWindow.parse("25:00-07:00"))
        assertNull(PowerWindow.parse("23:70-07:00"))
        assertNull(PowerWindow.parse("23:00"))
    }

    @Test
    fun `白天时段按左闭右开`() {
        val window = PowerWindow.parse("09:00-18:00")!!
        assertFalse(PowerWindow.inside(8 * 60 + 59, window))
        assertTrue(PowerWindow.inside(9 * 60, window))
        assertTrue(PowerWindow.inside(17 * 60 + 59, window))
        assertFalse(PowerWindow.inside(18 * 60, window))
    }

    @Test
    fun `跨零点的夜时段两头都算`() {
        val window = PowerWindow.parse("23:00-07:00")!!
        assertTrue(PowerWindow.inside(23 * 60, window))
        assertTrue(PowerWindow.inside(23 * 60 + 59, window))
        assertTrue(PowerWindow.inside(0, window))
        assertTrue(PowerWindow.inside(6 * 60 + 59, window))
        assertFalse(PowerWindow.inside(7 * 60, window))
        assertFalse(PowerWindow.inside(22 * 60 + 59, window))
    }

    @Test
    fun `起止相同是空窗, 永远不省电`() {
        val window = PowerWindow.parse("23:00-23:00")!!
        assertTrue(window.empty)
        assertFalse(PowerWindow.inside(23 * 60, window))
        assertFalse(PowerWindow.inside(0, window))
    }

    @Test
    fun `时刻按给的那个时区算`() {
        // 2026-10-07T00:30:00Z = 北京 08:30
        val epoch = ZonedDateTime.parse("2026-10-07T00:30:00Z").toInstant().toEpochMilli()
        assertEquals(8 * 60 + 30, PowerWindow.minutesOfDay(epoch, ZoneId.of("Asia/Shanghai")))
        assertEquals(0 * 60 + 30, PowerWindow.minutesOfDay(epoch, ZoneId.of("UTC")))
    }

    @Test
    fun `给人看的那一句是补零的时刻`() {
        assertEquals("23:00 - 07:00", PowerWindow.describe(PowerWindow.Window(23 * 60, 7 * 60)))
        assertEquals("07:05", PowerWindow.label(7 * 60 + 5))
    }
}
