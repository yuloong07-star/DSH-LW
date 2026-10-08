package io.github.miuzarte.littlewhale.lock

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 解锁那一步一步是什么 (批次 5, 需求 7)
 *
 * **坐标一律按屏幕比例存** (0~1 的浮点): 换分辨率、转屏、换密度都不怕 —— 存像素的序列在另一台机器
 * 上就是另一个位置。重放那一刻由 [LockSteps.px] 换算成当前屏的像素
 *
 * 序列是明文的 (`$DSH_HOME/lock/unlock.json`), 所以**密码与图案一个字都不在里面** —— 那两样走
 * [LockSecret] 那一份加密的, 序列里只留 [Secret] 这个位置记号
 */
@Serializable
sealed interface LockStep {

    /** 特权通道的一个按键 (WAKEUP / ENTER / 数字键 …) */
    @Serializable
    @SerialName("key")
    data class Key(val name: String, val holdMs: Long = LockSteps.HOLD_MS) : LockStep

    /** 一次点击 (以及长按: `holdMs` 过了平台那个门槛就是长按) */
    @Serializable
    @SerialName("tap")
    data class Tap(val x: Float, val y: Float, val holdMs: Long = LockSteps.HOLD_MS) : LockStep

    /** 一条直线上的拖动 (上滑解锁最常见的那一条) */
    @Serializable
    @SerialName("swipe")
    data class Swipe(
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val durationMs: Long = LockSteps.SWIPE_MS,
    ) : LockStep

    /**
     * 一条转折多于一次的笔画 (图案锁), 或者一条被保留下来的折线路径
     *
     * 与 [Swipe] 分开是因为重放走的是不同的两条路: 直线走 `swipe`, 折线走 `gesture` —— 把折线按
     * 首尾两点当直线重放, 画出来的图案与主人画的那一个不是一个
     */
    @Serializable
    @SerialName("stroke")
    data class Stroke(
        val points: List<List<Float>>,
        val durationMs: Long = LockSteps.SWIPE_MS,
    ) : LockStep

    /**
     * 密码 / 图案那一段在序列里的位置
     *
     * 它**不含任何内容**: 真正的那一份在 [LockSecret] 里 (Keystore 加密), 重放那一刻才解出来 ——
     * 明文不落盘的落点就是这一条
     */
    @Serializable
    @SerialName("secret")
    data object Secret : LockStep

    /** 等一件事发生 ([LockSteps.UNLOCKED] / [LockSteps.SCREEN_ON] / [LockSteps.SETTLE]) */
    @Serializable
    @SerialName("wait")
    data class Wait(val what: String, val timeoutMs: Long = LockSteps.WAIT_MS) : LockStep
}

/**
 * 步骤序列的读写与那几个纯算式
 *
 * 全是纯函数 (不碰 Context、不碰设备), 所以能一条条单测 —— 见 `LockStepsTest`
 */
internal object LockSteps {

    /** 一次点击按多久 (短到不算长按) */
    const val HOLD_MS = 60L

    /** 一条滑动的默认时长 */
    const val SWIPE_MS = 220L

    /** 一步之间默认停留多久 */
    const val WAIT_MS = 600L

    /** 一条序列最多几步: 解锁这个动作远用不了这么多, 上限是防一份被人写坏的文件 */
    const val MAX_STEPS = 24

    const val MAX_HOLD_MS = 3_000L
    const val MAX_SWIPE_MS = 3_000L
    const val MAX_WAIT_MS = 5_000L

    /** [LockStep.Wait] 能等的几件事 */
    const val UNLOCKED = "unlocked"
    const val SCREEN_ON = "screenOn"
    const val SETTLE = "settle"

    /** 比例到像素: 越界的数夹回去, 不让一份写坏的文件把手指送到屏外 */
    fun px(ratio: Float, sizePx: Int): Float =
        (ratio.coerceIn(0f, 1f) * sizePx.toFloat()).coerceIn(0f, (sizePx - 1).coerceAtLeast(0).toFloat())

    val json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun encode(steps: List<LockStep>): String = json.encodeToString(bounded(steps))

    /**
     * 读一份序列, **读不出来回空表** —— 空表与"读不出来"在重放那一侧是同一件事 (没得放), 而写坏
     * 的文件不该让设置页崩给自己看
     */
    fun decode(raw: String?): List<LockStep> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<LockStep>>(text) }.getOrDefault(emptyList())
    }

    /** 上限与夹取只有这一处: 无论序列是从文件读的、录的、还是人写进来的都过这一道 */
    fun bounded(steps: List<LockStep>): List<LockStep> = steps.take(MAX_STEPS).map { step ->
        when (step) {
            is LockStep.Key -> step.copy(holdMs = step.holdMs.coerceIn(0L, MAX_HOLD_MS))
            is LockStep.Tap -> step.copy(
                x = step.x.coerceIn(0f, 1f),
                y = step.y.coerceIn(0f, 1f),
                holdMs = step.holdMs.coerceIn(0L, MAX_HOLD_MS),
            )

            is LockStep.Swipe -> step.copy(
                fromX = step.fromX.coerceIn(0f, 1f),
                fromY = step.fromY.coerceIn(0f, 1f),
                toX = step.toX.coerceIn(0f, 1f),
                toY = step.toY.coerceIn(0f, 1f),
                durationMs = step.durationMs.coerceIn(0L, MAX_SWIPE_MS),
            )

            is LockStep.Stroke -> step.copy(
                points = step.points.take(MAX_STROKE_POINTS).map { point ->
                    listOf(
                        point.getOrElse(0) { 0f }.coerceIn(0f, 1f),
                        point.getOrElse(1) { 0f }.coerceIn(0f, 1f),
                    )
                },
                durationMs = step.durationMs.coerceIn(0L, MAX_SWIPE_MS),
            )

            LockStep.Secret -> LockStep.Secret
            is LockStep.Wait -> step.copy(timeoutMs = step.timeoutMs.coerceIn(0L, MAX_WAIT_MS))
        }
    }

    /** 一条笔画最多留这么多个点: 再多也不是"画一下", 而是把一次注入拆成几百个往返 */
    const val MAX_STROKE_POINTS = 12

    /**
     * 一句话说清这一步是什么, **密码那一步只说"the password"**
     *
     * 这是给设置页与回执看的, 所以它自己不能把秘密念出来 —— 这条规矩与"密码不进 logcat"是同一条
     */
    fun describe(step: LockStep): String = when (step) {
        is LockStep.Key -> "press ${step.name}"
        is LockStep.Tap -> "tap at ${percent(step.x)}, ${percent(step.y)}"
        is LockStep.Swipe -> "swipe from ${percent(step.fromX)}, ${percent(step.fromY)}" +
            " to ${percent(step.toX)}, ${percent(step.toY)}"

        is LockStep.Stroke -> "draw a ${step.points.size}-point stroke"
        LockStep.Secret -> "the password"
        is LockStep.Wait -> "wait for ${step.what}"
    }

    /** 比例写成百分比: 给人看的那句里不留一串小数 */
    private fun percent(value: Float): String = "${(value.coerceIn(0f, 1f) * 100).toInt()}%"

    /**
     * 把录到的一串步骤收成"主人要重放的那一条"
     *
     * 三条规矩, 都在这一处说清 (所以能单测):
     *
     * 1. **填了密码**: 第一次点击起的东西**全部丢掉** —— 那一段是主人在键盘上敲的密码。序列变成
     *    "划到键盘为止的滑动 + [Secret] + 回车", 而密码本身走加密那一份。丢掉是有意的: 键盘上那几个
     *    点击的坐标放在明文文件里, 等于把密码写在了文件里
     * 2. **没填密码而录到了一条折线** (图案锁): 那条折线进加密那一份 (图案也是秘密), 序列里在那个
     *    位置留一个 [LockStep.Secret]
     * 3. 其余情况原样留着: 一台只有滑动锁的机器, 录到什么就是什么
     *
     * @return 收好的序列, 以及那条要加密的折线 (没有就是 null)
     */
    fun settleTaken(steps: List<LockStep>, hasPassword: Boolean): Pair<List<LockStep>, List<List<Float>>?> {
        val bounded = bounded(steps)
        if (hasPassword) {
            val head = bounded.takeWhile { it !is LockStep.Tap && it !is LockStep.Secret }
            return (head + LockStep.Secret) to null
        }
        val stroke = bounded.filterIsInstance<LockStep.Stroke>().maxByOrNull { it.points.size }
            ?: return bounded to null
        val replaced = bounded.map { if (it === stroke) LockStep.Secret else it }
        return replaced to stroke.points
    }
}

/**
 * 连续失败三次就停 (批次 5, 需求 7)
 *
 * 纯算术, 因为"三次"这个数要能一条条单测: 成功归零, 失败加一, 到上限就停
 */
internal object LockTries {

    /** 连着失败几次就停 */
    const val LIMIT = 3

    /** 一次重放之后的新计数: 成了就归零, 没成加一 (封顶 [LIMIT]) */
    fun after(current: Int, ok: Boolean): Int =
        if (ok) 0 else (current.coerceAtLeast(0) + 1).coerceAtMost(LIMIT)

    /** 这个计数算不算"该停了" */
    fun exhausted(current: Int): Boolean = current.coerceAtLeast(0) >= LIMIT
}
