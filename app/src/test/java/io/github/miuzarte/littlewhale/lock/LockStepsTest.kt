package io.github.miuzarte.littlewhale.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 步骤序列那几条纯判据 (批次 5)
 *
 * 量的都是"没有设备也能定的那部分": 比例到像素、上限与夹取、写坏的序列、密码那一段怎么收、失败三次
 * 那个状态机。真正与设备有关的两条 (Keystore 能不能用、注入能不能解开锁屏) 在设备上量
 */
class LockStepsTest {

    private val portrait = 1080 to 2400
    private val landscape = 2400 to 1080

    @Test
    fun `a sequence survives a round trip`() {
        val steps = listOf(
            LockStep.Key("WAKEUP"),
            LockStep.Swipe(0.5f, 0.9f, 0.5f, 0.2f, 240L),
            LockStep.Tap(0.3f, 0.7f),
            LockStep.Secret,
            LockStep.Key("ENTER"),
            LockStep.Wait(LockSteps.UNLOCKED, 900L),
            LockStep.Stroke(listOf(listOf(0.1f, 0.1f), listOf(0.5f, 0.5f), listOf(0.1f, 0.9f)), 400L),
        )
        val back = LockSteps.decode(LockSteps.encode(steps))
        assertEquals(steps, back)
    }

    @Test
    fun `a file that makes no sense reads as an empty sequence`() {
        assertEquals(emptyList<LockStep>(), LockSteps.decode(null))
        assertEquals(emptyList<LockStep>(), LockSteps.decode(""))
        assertEquals(emptyList<LockStep>(), LockSteps.decode("{ not json"))
        assertEquals(emptyList<LockStep>(), LockSteps.decode("""{"kind":"tap"}"""))
    }

    @Test
    fun `ratios are clamped and holds are bounded`() {
        val wild = listOf(
            LockStep.Tap(1.7f, -0.4f, 99_999L),
            LockStep.Swipe(-1f, 2f, 0.5f, 0.5f, 99_999L),
            LockStep.Wait(LockSteps.SETTLE, 99_999L),
        )
        val bounded = LockSteps.bounded(wild)
        assertEquals(LockStep.Tap(1f, 0f, LockSteps.MAX_HOLD_MS), bounded[0])
        assertEquals(
            LockStep.Swipe(0f, 1f, 0.5f, 0.5f, LockSteps.MAX_SWIPE_MS),
            bounded[1],
        )
        assertEquals(LockStep.Wait(LockSteps.SETTLE, LockSteps.MAX_WAIT_MS), bounded[2])
    }

    @Test
    fun `a sequence is cut off at the step ceiling`() {
        val many = List(LockSteps.MAX_STEPS + 6) { LockStep.Key("ENTER") }
        assertEquals(LockSteps.MAX_STEPS, LockSteps.bounded(many).size)
    }

    @Test
    fun `a ratio becomes a pixel on whichever screen it is replayed on`() {
        assertEquals(540f, LockSteps.px(0.5f, portrait.first), 0.001f)
        assertEquals(1200f, LockSteps.px(0.5f, portrait.second), 0.001f)
        assertEquals(1200f, LockSteps.px(0.5f, landscape.first), 0.001f)
        assertEquals(540f, LockSteps.px(0.5f, landscape.second), 0.001f)
        // 640x480 那一档 (换分辨率) 与越界的那两个数
        assertEquals(320f, LockSteps.px(0.5f, 640), 0.001f)
        assertEquals(0f, LockSteps.px(-1f, 1080), 0.001f)
        assertEquals(1079f, LockSteps.px(2f, 1080), 0.001f)
        assertEquals(0f, LockSteps.px(0.5f, 0), 0.001f)
    }

    @Test
    fun `with a password everything from the first tap on is dropped`() {
        val taken = listOf(
            LockStep.Swipe(0.5f, 0.9f, 0.5f, 0.3f),
            LockStep.Tap(0.3f, 0.7f),
            LockStep.Tap(0.4f, 0.7f),
            LockStep.Tap(0.5f, 0.7f),
            LockStep.Key("ENTER"),
        )
        val (steps, pattern) = LockSteps.settleTaken(taken, hasPassword = true)
        assertEquals(listOf(LockStep.Swipe(0.5f, 0.9f, 0.5f, 0.3f), LockStep.Secret), steps)
        assertNull(pattern)
        // 密码那一步说出来也只有"the password"这一句
        assertEquals("the password", LockSteps.describe(LockStep.Secret))
    }

    @Test
    fun `a pattern with no password goes into the secret slot and leaves a marker`() {
        val stroke = LockStep.Stroke(
            listOf(listOf(0.2f, 0.2f), listOf(0.8f, 0.2f), listOf(0.2f, 0.8f)),
            500L,
        )
        val taken = listOf(LockStep.Swipe(0.5f, 0.9f, 0.5f, 0.3f), stroke)
        val (steps, pattern) = LockSteps.settleTaken(taken, hasPassword = false)
        assertEquals(listOf(LockStep.Swipe(0.5f, 0.9f, 0.5f, 0.3f), LockStep.Secret), steps)
        assertEquals(stroke.points, pattern)
        // 折线的点一个都不留在明文那一份里
        assertTrue(steps.none { it is LockStep.Stroke })
    }

    @Test
    fun `a phone that only needs a swipe keeps exactly what was walked through`() {
        val taken = listOf(LockStep.Swipe(0.5f, 0.9f, 0.5f, 0.3f), LockStep.Tap(0.5f, 0.5f))
        val (steps, pattern) = LockSteps.settleTaken(taken, hasPassword = false)
        assertEquals(taken, steps)
        assertNull(pattern)
    }

    @Test
    fun `three failures in a row is where it stops`() {
        assertEquals(1, LockTries.after(0, ok = false))
        assertEquals(2, LockTries.after(1, ok = false))
        assertEquals(3, LockTries.after(2, ok = false))
        assertEquals(3, LockTries.after(3, ok = false))
        assertEquals(0, LockTries.after(2, ok = true))
        // 一份被写坏的计数 (负数) 按"一次都还没失败"算
        assertEquals(1, LockTries.after(-5, ok = false))
        assertTrue(!LockTries.exhausted(2))
        assertTrue(LockTries.exhausted(3))
        assertTrue(LockTries.exhausted(9))
    }
}
