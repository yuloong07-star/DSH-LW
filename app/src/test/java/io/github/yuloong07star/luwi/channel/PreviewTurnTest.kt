package io.github.yuloong07star.luwi.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览那一帧的转角 (2026-10-10 主人: "视频模式下镜头比例有误, 高度受挤压")
 *
 * 为什么值得单测: 它错一档就是"画面躺着"或者"被压扁", 而这三种输入 (传感器方向 / 屏幕转过多少 /
 * 前后摄) 在真机上没法一条条摆出来 —— 竖着拿、横着拿、换前摄各量一次要开三次相机。式子本身没有
 * 设备也能量, 就把它钉在这里
 */
class PreviewTurnTest {

    /** 真机那一档 (vivo V2417A): 传感器 90, 竖屏 `getRotation()` = 0 → 后摄要转 90 度 */
    @Test
    fun `传感器九十配竖屏要转九十度`() {
        assertEquals(90, PreviewTurn.degrees(sensorOrientation = 90, displayDegrees = 0, mirror = false))
        assertTrue("转过之后高宽互换", PreviewTurn.swapsSides(90))
    }

    /**
     * 横着拿 (屏幕转过 90) 就**不用再转**: 传感器那一帧本来就是横的
     *
     * 这一条是"今天横屏本来就是对的"那个事实的钉子 —— 上一版之所以没人报, 正是因为横屏那一档刚好
     * 不用转; 而竖屏那一档按同一个式子要转 90 度, 少的就是那一步
     */
    @Test
    fun `横屏时后摄不用转`() {
        assertEquals(0, PreviewTurn.degrees(sensorOrientation = 90, displayDegrees = 90, mirror = false))
        assertFalse("没转就不换高宽", PreviewTurn.swapsSides(0))
    }

    /** 前摄的方向是反的 (`传感器 + 屏幕`), 这一条防的是"顺手把两个方向写成同一个"那种改法 */
    @Test
    fun `前摄的方向与后摄相反`() {
        assertEquals(90 + 0, PreviewTurn.degrees(sensorOrientation = 90, displayDegrees = 0, mirror = true))
        assertEquals(270, PreviewTurn.degrees(sensorOrientation = 270, displayDegrees = 0, mirror = true))
        assertEquals(
            "同一对输入, 前后摄差一倍屏角",
            180,
            PreviewTurn.degrees(sensorOrientation = 90, displayDegrees = 90, mirror = true),
        )
    }

    /** 负角与超过一圈的输入都要缩回 0..359: 传感器与屏幕那两个数在真机上就是 0/90/180/270 的任意组合 */
    @Test
    fun `角度缩回一圈之内`() {
        assertEquals(270, PreviewTurn.degrees(sensorOrientation = 270, displayDegrees = 0, mirror = false))
        assertEquals(270, PreviewTurn.degrees(sensorOrientation = 0, displayDegrees = 90, mirror = false))
        assertEquals(0, PreviewTurn.degrees(sensorOrientation = 90, displayDegrees = 270, mirror = true))
        assertEquals(0, PreviewTurn.degrees(sensorOrientation = 0, displayDegrees = 360, mirror = false))
        assertEquals(0, PreviewTurn.degrees(sensorOrientation = -90, displayDegrees = -90, mirror = false))
    }

    /** 高宽互换只认 90 与 270: 0 与 180 那一档窗口照缓冲区原样摆 */
    @Test
    fun `只有九十与二百七十那一档换高宽`() {
        assertTrue(PreviewTurn.swapsSides(90))
        assertTrue(PreviewTurn.swapsSides(270))
        assertTrue(PreviewTurn.swapsSides(-90))
        assertFalse(PreviewTurn.swapsSides(0))
        assertFalse(PreviewTurn.swapsSides(180))
        assertFalse(PreviewTurn.swapsSides(360))
    }
}
