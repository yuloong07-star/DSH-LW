package io.github.miuzarte.littlewhale.channel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `.active` 那一行该当成哪个模式 (批次 4)
 *
 * 这一份**不碰设备**: 判据本身是纯的 ([LwModes.resolve]), 而它要挡的正是那几档"设备上很难造"的状态 ——
 * 老机器上还写着 `screen` (识屏模式在批次 4 摘掉了)、文件是空的、两边带空白、我们自己不认识的名字。
 * 四档的下场必须是确定的: 退役的名字与空的那两档**都当手机模式** (那块屏本来就在那一档里), 认识的名字
 * 归一化之后原样报出来
 */
internal class LwModesTest {

    @Test
    fun `退役的名字当手机模式`() {
        assertEquals(LwModes.PHONE, LwModes.resolve(LwModes.SCREEN_RETIRED))
        assertEquals(LwModes.PHONE, LwModes.resolve("  SCREEN  "))
    }

    @Test
    fun `空的与读不到的都当手机模式`() {
        assertEquals(LwModes.PHONE, LwModes.resolve(null))
        assertEquals(LwModes.PHONE, LwModes.resolve(""))
        assertEquals(LwModes.PHONE, LwModes.resolve("   \n"))
    }

    @Test
    fun `两个模式原样认出来 (大小写与空白归一)`() {
        assertEquals(LwModes.PHONE, LwModes.resolve("phone"))
        assertEquals(LwModes.VIDEO, LwModes.resolve(" Video "))
        assertEquals(LwModes.PHONE, LwModes.resolve("PHONE"))
    }

    /** 别的名字照旧原样报出来 (坏文件不该被悄悄改成手机模式 —— 那是另一件事, 由调用方去说) */
    @Test
    fun `不认识的名字原样返回`() {
        assertEquals("assistant", LwModes.resolve("assistant"))
    }
}
