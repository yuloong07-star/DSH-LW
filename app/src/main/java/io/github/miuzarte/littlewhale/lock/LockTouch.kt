package io.github.miuzarte.littlewhale.lock

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 把触屏上那串原始样本合并成步骤 (批次 5, 需求 7 的录制那一半)
 *
 * **纯函数**: 样本进, 步骤出, 不碰文件也不碰设备 —— 所以"多帧 MOVE 合成一次滑动"、"抖动不算滑动"
 * 这些判据都能在 JVM 上一条条量 (见 `LockTouchTest`)
 *
 * 坐标进来时是**触屏那个节点自己的数** (`ABS_MT_POSITION_X/Y`, 模拟器上是 0~32767), 出去时一律换成
 * 0~1 的比例: 比例是这台设备的屏幕与那台设备的屏幕之间唯一能通用的东西
 *
 * 一条笔画 (按下到抬起之间) 落成哪一个步骤只有三条判据, 按顺序:
 *
 * 1. 首尾距离小于 [TAP_SLOP] → 一次点击 (按了 500 ms 以上就是长按, 把时长带上)
 * 2. 中间的点离首尾那条直线不超过 [STRAIGHT_SLOP] → 一条直线滑动
 * 3. 剩下的都是**折线** (图案锁那种一笔画): 留着折线的点, 重放时走 `gesture` 那条路
 */
internal object LockTouch {

    const val DOWN = 0
    const val MOVE = 1
    const val UP = 2

    /** 一次触摸的一帧: 设备坐标 + 落在哪一相 + 什么时候 */
    data class Sample(val x: Int, val y: Int, val phase: Int, val at: Long)

    /** 首尾离得比这个比例还近就算一次点击 (1080 宽的屏上约 13 px) */
    const val TAP_SLOP = 0.012f

    /** 中间的点离直线比这个比例还远就当折线 (1080 宽的屏上约 22 px) */
    const val STRAIGHT_SLOP = 0.02f

    /** 折线上两个相邻点至少隔这么远, 免得把手指的抖动全画成折线 */
    const val POINT_STEP = 0.03f

    /** 平台那个长按门槛: 过了它就是"按住", 时长要带上 */
    const val LONG_PRESS_MS = 500L

    /**
     * 合并一串样本
     *
     * @param xMax 触屏那个节点 ABS 的上界, 比例就是 `value / xMax`
     * @param yMax 同上, 竖的那一半
     */
    fun merge(samples: List<Sample>, xMax: Int, yMax: Int): List<LockStep> {
        val width = xMax.coerceAtLeast(1)
        val height = yMax.coerceAtLeast(1)
        val strokes = strokesOf(samples)
        val steps = mutableListOf<LockStep>()
        strokes.forEach { stroke -> stepOf(stroke, width, height)?.let(steps::add) }
        return LockSteps.bounded(steps)
    }

    /** 按下到抬起之间算一条笔画, 没抬起的那些 (还没走完) 不算 */
    fun strokesOf(samples: List<Sample>): List<List<Sample>> {
        val strokes = mutableListOf<List<Sample>>()
        var current: MutableList<Sample>? = null
        samples.forEach { sample ->
            when (sample.phase) {
                DOWN -> current = mutableListOf(sample)
                MOVE -> current?.add(sample)
                UP -> {
                    val stroke = current
                    if (stroke != null) {
                        stroke.add(sample)
                        strokes.add(stroke)
                    }
                    current = null
                }
            }
        }
        return strokes
    }

    /** 一条笔画落成哪一个步骤 */
    private fun stepOf(stroke: List<Sample>, xMax: Int, yMax: Int): LockStep? {
        if (stroke.size < 2) return null
        val first = stroke.first()
        val last = stroke.last()
        val fromX = first.x.toFloat() / xMax
        val fromY = first.y.toFloat() / yMax
        val toX = last.x.toFloat() / xMax
        val toY = last.y.toFloat() / yMax
        val duration = (last.at - first.at).coerceAtLeast(0L)
        if (hypot(toX - fromX, toY - fromY) < TAP_SLOP) {
            return LockStep.Tap(
                x = fromX,
                y = fromY,
                holdMs = if (duration >= LONG_PRESS_MS) duration else LockSteps.HOLD_MS,
            )
        }
        val points = stroke.map { it.x.toFloat() / xMax to it.y.toFloat() / yMax }
        if (maxDeviation(points) <= STRAIGHT_SLOP) {
            return LockStep.Swipe(fromX, fromY, toX, toY, duration.coerceAtLeast(1L))
        }
        return LockStep.Stroke(
            points = thin(points).map { listOf(it.first, it.second) },
            durationMs = duration.coerceAtLeast(1L),
        )
    }

    /** 中间的点里离首尾那条直线最远的那个有多远 (比例) */
    private fun maxDeviation(points: List<Pair<Float, Float>>): Float {
        if (points.size <= 2) return 0f
        val (x1, y1) = points.first()
        val (x2, y2) = points.last()
        val length = hypot(x2 - x1, y2 - y1)
        if (length == 0f) return 0f
        return points.drop(1).dropLast(1).maxOf { (x, y) ->
            // 点到直线的距离 = 叉积 / 底边长
            abs((x2 - x1) * (y1 - y) - (x1 - x) * (y2 - y1)) / length
        }
    }

    /**
     * 折线抽稀: 首尾一定留着, 中间只留隔得够远的点
     *
     * 不这么做的代价是重放时一次注入要发送几百个点 (手机上一条笔画每 8 ms 就有一个), 而画图案这件事
     * **要点在拐角**, 不在点的密度
     */
    fun thin(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.size <= 2) return points
        val kept = mutableListOf(points.first())
        points.drop(1).dropLast(1).forEach { point ->
            val last = kept.last()
            if (hypot(point.first - last.first, point.second - last.second) >= POINT_STEP) {
                kept.add(point)
            }
        }
        val tail = points.last()
        val last = kept.last()
        if (hypot(tail.first - last.first, tail.second - last.second) >= POINT_STEP * 0.5f) {
            kept.add(tail)
        } else {
            kept[kept.size - 1] = tail
        }
        return if (kept.size <= LockSteps.MAX_STROKE_POINTS) {
            kept
        } else {
            // 还是太长就等距取点, 但首尾照旧留着
            val step = (kept.size - 1).toFloat() / (LockSteps.MAX_STROKE_POINTS - 1)
            (0 until LockSteps.MAX_STROKE_POINTS).map { index ->
                kept[(index * step).toInt().coerceAtMost(kept.size - 1)]
            }
        }
    }
}
