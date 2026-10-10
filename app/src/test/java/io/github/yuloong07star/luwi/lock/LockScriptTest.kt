package io.github.yuloong07star.luwi.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解锁脚本那一份解析器 (批次 5 追加)
 *
 * 量的就是"人写的脚本能不能读进来、写坏的那几行会不会被拒、拒绝时说不说得清第几行"。文档
 * `docs/lock-script.md` 里那三个例子与这里的正例是同一批 —— 文档漂了, 这里会先红
 */
class LockScriptTest {

    private fun parsed(text: String): List<LockStep> {
        val result = LockScript.parse(text)
        assertNull(result.problem)
        return result.steps
    }

    private fun refused(text: String): String {
        val problem = LockScript.parse(text).problem
        assertTrue("this script should have been refused: $text", problem != null)
        return problem!!
    }

    @Test
    fun `a phone that only needs a swipe`() {
        val steps = parsed(
            """
            # 只有滑动锁
            key WAKEUP
            swipe 0.5 0.85 0.5 0.2 700
            wait unlocked 1500
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                LockStep.Key("WAKEUP", LockSteps.HOLD_MS),
                LockStep.Swipe(0.5f, 0.85f, 0.5f, 0.2f, 700L),
                LockStep.Wait(LockSteps.UNLOCKED, 1_500L),
            ),
            steps,
        )
    }

    @Test
    fun `a phone with a pin keeps the password out of the script`() {
        val steps = parsed(
            """
            key WAKEUP
            swipe 0.5 0.85 0.5 0.2 700
            text
            key ENTER
            wait unlocked 1500
            """.trimIndent(),
        )
        assertTrue(steps.contains(LockStep.Secret))
        assertEquals(LockStep.Key("ENTER", LockSteps.HOLD_MS), steps[3])
    }

    @Test
    fun `a pattern is one stroke with a duration at the end`() {
        val steps = parsed(
            """
            key WAKEUP
            stroke 0.2 0.4 0.5 0.4 0.8 0.4 0.8 0.7 600
            """.trimIndent(),
        )
        val stroke = steps[1] as LockStep.Stroke
        assertEquals(4, stroke.points.size)
        assertEquals(600L, stroke.durationMs)
        assertEquals(listOf(0.8f, 0.7f), stroke.points.last())
    }

    @Test
    fun `comments and blank lines do not count as steps`() {
        val steps = parsed(
            """

            # 这一行是注释
            key ENTER   # 后面这一截也是注释

            """.trimIndent(),
        )
        assertEquals(listOf(LockStep.Key("ENTER", LockSteps.HOLD_MS)), steps)
    }

    @Test
    fun `the json the app itself writes is read too`() {
        val json = LockSteps.encode(
            listOf(
                LockStep.Swipe(0.5f, 0.85f, 0.5f, 0.2f, 700L),
                LockStep.Secret,
            ),
        )
        assertEquals(parsed(json), parsed("[${json.trim().removePrefix("[").removeSuffix("]")}]"))
    }

    @Test
    fun `a script that writes pixels instead of ratios is refused`() {
        val problem = refused("swipe 540 2040 540 480")
        assertTrue(problem, problem.contains("line 1"))
        assertTrue(problem, problem.contains("not pixel counts"))
    }

    @Test
    fun `a password written into the script is refused`() {
        val problem = refused("text 1234")
        assertTrue(problem, problem.contains("line 1"))
        assertTrue(problem, problem.contains("takes no argument"))
    }

    @Test
    fun `a verb this build does not know is refused by name`() {
        val problem = refused("swip 0.5 0.5 0.5 0.5")
        assertTrue(problem, problem.contains("\"swip\" is not one of the steps"))
    }

    @Test
    fun `a line with too few arguments says which line it is`() {
        val problem = refused("key ENTER\nswipe 0.5 0.85 0.5")
        assertTrue(problem, problem.contains("line 2"))
        assertTrue(problem, problem.contains("swipe needs fromX fromY toX toY"))
    }

    @Test
    fun `a step that waits for nothing in particular is refused`() {
        val problem = refused("wait whenever")
        assertTrue(problem, problem.contains("line 1"))
        assertTrue(problem, problem.contains("unlocked"))
    }

    @Test
    fun `too many steps is refused rather than silently cut`() {
        val problem = refused(List(LockSteps.MAX_STEPS + 1) { "key ENTER" }.joinToString("\n"))
        assertTrue(problem, problem.contains("at most ${LockSteps.MAX_STEPS} steps"))
    }

    @Test
    fun `an empty script parses to nothing and is not an error`() {
        val result = LockScript.parse("\n# 只有注释\n")
        assertNull(result.problem)
        assertTrue(result.steps.isEmpty())
        assertNull(LockScript.parse(null).problem)
    }

    @Test
    fun `writing a sequence out and reading it back gives the same sequence`() {
        val steps = listOf(
            LockStep.Key("WAKEUP", 60L),
            LockStep.Tap(0.3f, 0.7f, 60L),
            LockStep.Swipe(0.5f, 0.85f, 0.5f, 0.2f, 700L),
            LockStep.Stroke(listOf(listOf(0.2f, 0.4f), listOf(0.5f, 0.4f), listOf(0.8f, 0.7f)), 600L),
            LockStep.Secret,
            LockStep.Wait(LockSteps.SETTLE, 400L),
        )
        assertEquals(steps, parsed(LockScript.format(steps)))
    }

    @Test
    fun `the template this ships is a script this reads`() {
        val steps = parsed(LockScript.template())
        assertTrue(steps.isNotEmpty())
        assertTrue(steps.any { it is LockStep.Secret })
    }
}
