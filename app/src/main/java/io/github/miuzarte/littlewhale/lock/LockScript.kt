package io.github.miuzarte.littlewhale.lock

/**
 * 解锁脚本: 一条步骤序列的**文本写法** (批次 5 追加)
 *
 * 主人可以录一遍 ([LockRecord]), 也可以自己写一份脚本导进来 —— 两条路落在同一个模型上
 * ([LockStep]), 重放那一侧分不出这条序列是录的还是写的
 *
 * **它不是 shell**: 一行一步, 认得出的一共六个动词 ([VERBS]), 别的一律拒。这一批从头到尾没有一条路
 * 能执行任意命令 —— 特权那边是白名单, 不是 shell, 导进来的东西也只是"往哪个坐标按一下"这类数据
 *
 * 两种写法都认 (同一个解析器, 同一份校验):
 *
 * ```
 * # 行式 (给人手写的; 空行与 # 后面的内容都忽略)
 * key WAKEUP
 * swipe 0.5 0.85 0.5 0.18 700
 * tap 0.3 0.7
 * text
 * key ENTER
 * wait unlocked 1500
 * ```
 *
 * ```
 * [ {"kind":"swipe","fromX":0.5,"fromY":0.85,"toX":0.5,"toY":0.18,"durationMs":700},
 *   {"kind":"secret"} ]      // JSON 那一份: 与导出的、以及 $DSH_HOME/lock/unlock.json 同一个形状
 * ```
 *
 * 三条纪律写在解析里, 每条都有一句人话:
 *
 * 1. **坐标是 0~1 的比例, 不是像素** —— 写 `540 1200` 会被拒, 因为那是这一台机器的坐标
 * 2. **密码不许写在脚本里**: `text` 那一步不带参数 (它只说明"这里要一段密码"), 真正的内容走加密那一格
 * 3. 报错要说清**是第几行、差在哪儿**, 而不是一句"格式不对"
 */
internal object LockScript {

    /** 解析结果: 要么有步骤, 要么有一句"第几行哪里不对" (两个同时为空 = 空脚本) */
    data class Parsed(val steps: List<LockStep>, val problem: String?)

    /** 认得出的动词, 就是文档里那一张表 —— `check-lock-steps.mjs` 拿它跟文档对 */
    val VERBS = listOf("key", "tap", "swipe", "stroke", "text", "wait")

    /** `wait` 能等的三件事 */
    private val WAITS = listOf(LockSteps.UNLOCKED, LockSteps.SCREEN_ON, LockSteps.SETTLE)

    /** 一个脚本文件的上限: 1024 步已经是"这不是人能走出来的解锁"了 */
    private const val MAX_LINES = 1_024

    fun parse(raw: String?): Parsed {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Parsed(emptyList(), null)
        // JSON 那一份: 与导出的、以及设备上那份 unlock.json 同一个形状, 直接交给序列那一层读
        if (text.startsWith("[")) {
            val steps = LockSteps.decode(text)
            return if (steps.isEmpty()) {
                Parsed(emptyList(), "this reads as JSON but not as a sequence of unlock steps")
            } else {
                Parsed(steps, null)
            }
        }
        val steps = mutableListOf<LockStep>()
        var number = 0
        for (line in text.lineSequence()) {
            number += 1
            if (number > MAX_LINES) {
                return Parsed(emptyList(), "line $number: a script this long is not an unlock")
            }
            val body = line.substringBefore('#').trim()
            if (body.isEmpty()) continue
            val step = step(body, number)
            step.problem?.let { return Parsed(emptyList(), it) }
            steps += step.steps
            if (steps.size > LockSteps.MAX_STEPS) {
                return Parsed(
                    emptyList(),
                    "line $number: at most ${LockSteps.MAX_STEPS} steps make sense for an unlock," +
                        " and this one already has more",
                )
            }
        }
        return Parsed(LockSteps.bounded(steps), null)
    }

    /** 一行一步 */
    private fun step(body: String, number: Int): Parsed {
        val tokens = body.split(Regex("\\s+"))
        val verb = tokens[0].lowercase()
        val arguments = tokens.drop(1)
        fun bad(why: String) = Parsed(emptyList(), "line $number: $why")
        fun ratio(name: String, value: String?): Float {
            val parsed = value?.toFloatOrNull()
                ?: throw IllegalArgumentException("$name has to be a number, and \"${value.orEmpty()}\" is not")
            if (parsed < 0f || parsed > 1f) {
                throw IllegalArgumentException(
                    "$name is $parsed, and coordinates here are 0~1 ratios of the screen - not" +
                        " pixel counts",
                )
            }
            return parsed
        }

        fun time(name: String, value: String?, fallback: Long, ceiling: Long): Long {
            if (value == null) return fallback
            val parsed = value.toLongOrNull()
                ?: throw IllegalArgumentException("$name has to be whole milliseconds, and \"$value\" is not")
            if (parsed < 0) throw IllegalArgumentException("$name cannot be negative")
            return parsed.coerceAtMost(ceiling)
        }

        return try {
            when (verb) {
                "key" -> {
                    val name = arguments.getOrNull(0)?.takeIf { it.isNotBlank() }
                        ?: return bad("key needs the name of the key to press, as Android names it (ENTER)")
                    Parsed(
                        listOf(
                            LockStep.Key(
                                name.uppercase(),
                                time("the hold", arguments.getOrNull(1), LockSteps.HOLD_MS, LockSteps.MAX_HOLD_MS),
                            ),
                        ),
                        null,
                    )
                }

                "tap" -> {
                    if (arguments.size < 2) return bad("tap needs x and y")
                    Parsed(
                        listOf(
                            LockStep.Tap(
                                ratio("x", arguments[0]),
                                ratio("y", arguments[1]),
                                time("the hold", arguments.getOrNull(2), LockSteps.HOLD_MS, LockSteps.MAX_HOLD_MS),
                            ),
                        ),
                        null,
                    )
                }

                "swipe" -> {
                    if (arguments.size < 4) {
                        return bad("swipe needs fromX fromY toX toY, and may end with a duration")
                    }
                    Parsed(
                        listOf(
                            LockStep.Swipe(
                                ratio("fromX", arguments[0]),
                                ratio("fromY", arguments[1]),
                                ratio("toX", arguments[2]),
                                ratio("toY", arguments[3]),
                                time("the duration", arguments.getOrNull(4), LockSteps.SWIPE_MS, LockSteps.MAX_SWIPE_MS),
                            ),
                        ),
                        null,
                    )
                }

                // stroke 的点数是偶数个坐标, 后面可以再跟一个时长: 数一数就知道最后那个是不是时长
                "stroke" -> {
                    if (arguments.size < 4) return bad("stroke needs at least two x y points")
                    val pairs = if (arguments.size % 2 == 0) arguments else arguments.dropLast(1)
                    val duration = time(
                        "the duration",
                        if (arguments.size % 2 == 0) null else arguments.last(),
                        LockSteps.SWIPE_MS,
                        LockSteps.MAX_SWIPE_MS,
                    )
                    val points = pairs.chunked(2).map { pair ->
                        listOf(ratio("x", pair[0]), ratio("y", pair[1]))
                    }
                    Parsed(listOf(LockStep.Stroke(points, duration)), null)
                }

                // 密码那一段只有这一个动词, 而且**不带参数**: 内容走加密那一格, 不写进脚本
                "text" -> if (arguments.isEmpty()) {
                    Parsed(listOf(LockStep.Secret), null)
                } else {
                    bad(
                        "text takes no argument: the password itself is handed over separately and" +
                            " kept encrypted, so it is never written into a script",
                    )
                }

                "wait" -> {
                    val what = arguments.getOrNull(0)?.lowercase().orEmpty()
                    if (what !in WAITS) {
                        return bad(
                            "wait takes ${LockSteps.UNLOCKED}, ${LockSteps.SCREEN_ON} or" +
                                " ${LockSteps.SETTLE}, not \"$what\"",
                        )
                    }
                    Parsed(
                        listOf(
                            LockStep.Wait(
                                what,
                                time("the timeout", arguments.getOrNull(1), LockSteps.WAIT_MS, LockSteps.MAX_WAIT_MS),
                            ),
                        ),
                        null,
                    )
                }

                else -> bad(
                    "\"$verb\" is not one of the steps this understands (${VERBS.joinToString(", ")})",
                )
            }
        } catch (error: IllegalArgumentException) {
            bad(error.message ?: "that line does not read as a step")
        }
    }

    /**
     * 反过来: 把一条序列写成行式脚本
     *
     * 它有三个用处: 设置页那个导入框拿现在这一条当草稿、给主人的模板、以及单测里那条"写出来再读回去还是
     * 原来那一条"的判据
     */
    fun format(steps: List<LockStep>): String = steps.joinToString("\n") { step ->
        when (step) {
            is LockStep.Key -> "key ${step.name} ${step.holdMs}"
            is LockStep.Tap -> "tap ${ratio(step.x)} ${ratio(step.y)} ${step.holdMs}"
            is LockStep.Swipe -> "swipe ${ratio(step.fromX)} ${ratio(step.fromY)}" +
                " ${ratio(step.toX)} ${ratio(step.toY)} ${step.durationMs}"

            is LockStep.Stroke -> "stroke " +
                step.points.joinToString(" ") { "${ratio(it.getOrElse(0) { 0f })} ${ratio(it.getOrElse(1) { 0f })}" } +
                " ${step.durationMs}"

            LockStep.Secret -> "text"
            is LockStep.Wait -> "wait ${step.what} ${step.timeoutMs}"
        }
    }

    /** 一整份草稿: 注释 + 三种写法各一行, 主人删掉不要的即可 */
    fun template(): String = listOf(
        "# 解锁脚本: 一行一步, 空行与 # 后面的内容都忽略",
        "# 坐标是 0~1 的比例 (左上角 0 0, 右下角 1 1), 不是像素; 密码不写在这里",
        "key WAKEUP",
        "swipe 0.5 0.85 0.5 0.2 700          # 上滑, 从屏下到屏上",
        "tap 0.35 0.7 60                      # 点一下 (x y 按住多少毫秒)",
        "text                                 # 密码 / 图案那一段: 内容另外交给应用加密存",
        "key ENTER",
        "wait unlocked 1500                   # 等锁屏真的让开",
    ).joinToString("\n")

    /** 一个比例写成它最短的样子: 3 位小数够屏幕用, 而 `0.5` 比 `0.500000` 好读 */
    private fun ratio(value: Float): String {
        val rounded = Math.round(value.coerceIn(0f, 1f) * 1000f) / 1000f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
    }
}
