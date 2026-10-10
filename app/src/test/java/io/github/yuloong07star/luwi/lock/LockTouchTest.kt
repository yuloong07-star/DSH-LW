package io.github.yuloong07star.luwi.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把触屏的原始样本合并成步骤 (批次 5)
 *
 * 触屏那个节点的量程是 0~32767 (模拟器的 `virtio_input_multi_touch` 就是这一档), 所以下面的数都按它
 * 折算: 16383 就是屏中间, 1000 与 31000 分别贴着两条边
 */
class LockTouchTest {

    private val max = 32_767

    private fun down(x: Int, y: Int, at: Long) = LockTouch.Sample(x, y, LockTouch.DOWN, at)
    private fun move(x: Int, y: Int, at: Long) = LockTouch.Sample(x, y, LockTouch.MOVE, at)
    private fun up(x: Int, y: Int, at: Long) = LockTouch.Sample(x, y, LockTouch.UP, at)

    @Test
    fun `several move frames become one swipe`() {
        val samples = listOf(
            down(16_000, 31_000, 0),
            move(16_000, 24_000, 40),
            move(16_100, 16_000, 80),
            up(16_000, 8_000, 120),
        )
        val steps = LockTouch.merge(samples, max, max)
        assertEquals(1, steps.size)
        val swipe = steps.first()
        assertTrue("expected a swipe, got $swipe", swipe is LockStep.Swipe)
        swipe as LockStep.Swipe
        assertEquals(16_000f / max, swipe.fromX, 0.001f)
        assertEquals(31_000f / max, swipe.fromY, 0.001f)
        assertEquals(8_000f / max, swipe.toY, 0.001f)
        assertEquals(120L, swipe.durationMs)
    }

    @Test
    fun `a finger that goes back and forth across one pixel is a tap`() {
        val samples = listOf(
            down(16_383, 16_383, 0),
            move(16_400, 16_390, 30),
            up(16_380, 16_385, 60),
        )
        val steps = LockTouch.merge(samples, max, max)
        assertEquals(1, steps.size)
        val tap = steps.first()
        assertTrue("expected a tap, got $tap", tap is LockStep.Tap)
        tap as LockStep.Tap
        assertEquals(16_383f / max, tap.x, 0.001f)
        assertEquals(LockSteps.HOLD_MS, tap.holdMs)
    }

    @Test
    fun `holding a finger down keeps the hold`() {
        val samples = listOf(
            down(16_383, 16_383, 0),
            move(16_383, 16_383, 400),
            up(16_383, 16_383, 800),
        )
        val steps = LockTouch.merge(samples, max, max)
        assertEquals(1, steps.size)
        assertEquals(800L, (steps.first() as LockStep.Tap).holdMs)
    }

    @Test
    fun `a stroke that turns a corner is kept as a polyline`() {
        val samples = listOf(
            down(3_000, 3_000, 0),
            move(9_000, 3_000, 60),
            move(15_000, 3_000, 120),
            move(21_000, 9_000, 180),
            move(27_000, 15_000, 240),
            move(27_000, 24_000, 300),
            move(21_000, 30_000, 360),
            up(15_000, 30_000, 420),
        )
        val steps = LockTouch.merge(samples, max, max)
        assertEquals(1, steps.size)
        val stroke = steps.first()
        assertTrue("expected a stroke, got $stroke", stroke is LockStep.Stroke)
        stroke as LockStep.Stroke
        assertTrue("a pattern wants at least three points, got ${stroke.points.size}", stroke.points.size >= 3)
        assertTrue(stroke.points.size <= LockSteps.MAX_STROKE_POINTS)
        // 比例, 不是设备坐标
        stroke.points.forEach { point ->
            assertTrue(point[0] in 0f..1f)
            assertTrue(point[1] in 0f..1f)
        }
        assertEquals(420L, stroke.durationMs)
    }

    @Test
    fun `a finger that never lifts is not a step yet`() {
        val samples = listOf(down(1_000, 1_000, 0), move(9_000, 9_000, 40))
        assertTrue(LockTouch.merge(samples, max, max).isEmpty())
    }

    @Test
    fun `two walks in a row come out in the order they happened`() {
        val samples = listOf(
            down(16_000, 31_000, 0),
            up(16_000, 8_000, 100),
            down(10_000, 20_000, 400),
            up(10_010, 20_000, 460),
        )
        val steps = LockTouch.merge(samples, max, max)
        assertEquals(2, steps.size)
        assertTrue(steps[0] is LockStep.Swipe)
        assertTrue(steps[1] is LockStep.Tap)
    }

    @Test
    fun `a device whose ranges are not 32767 still comes out as ratios`() {
        // 一块报 0~1079 / 0~2399 的面板 (直接吐像素的那些)
        val samples = listOf(
            down(540, 2_280, 0),
            move(540, 1_200, 40),
            up(540, 600, 80),
        )
        val steps = LockTouch.merge(samples, 1_079, 2_399)
        val swipe = steps.first() as LockStep.Swipe
        assertEquals(0.5f, swipe.fromX, 0.01f)
        assertEquals(0.25f, swipe.toY, 0.01f)
    }

    @Test
    fun `thinning keeps the corners and the ends`() {
        val points = (0..20).map { it / 20f to it / 20f }
        val thinned = LockTouch.thin(points)
        assertEquals(points.first(), thinned.first())
        assertEquals(points.last(), thinned.last())
        assertTrue(thinned.size <= LockSteps.MAX_STROKE_POINTS)
        assertTrue(thinned.size >= 2)
    }

    @Test
    fun `an empty walk has nothing to repeat`() {
        assertNull(LockTouch.merge(emptyList(), max, max).firstOrNull())
    }
}
