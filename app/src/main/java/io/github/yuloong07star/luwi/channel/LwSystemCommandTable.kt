package io.github.yuloong07star.luwi.channel

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * 特权那一侧认得的那几件事
 *
 * **白名单写在这里, 不写在应用那一侧**: 送过来的只是一个操作名与几个参数, 拼成什么命令完全由这张表
 * 决定。表里没有的名字直接拒, 等于把"模型能不能凑出一条任意命令"这个问题从根上关掉 —— 参数只进得去
 * 被它们占的位置, 拼不出第二个动词
 *
 * 每一条都是 `am` / `pm` / `svc` / `cmd` / `input` 里那一个具体动作, 而不是"把命令给我": 这是这一层
 * 与"给模型一个 shell"之间唯一的区别
 */
internal object LwSystemCommandTable {

    private const val TAG = "LwSysCommand"

    /** 参数最多几个, 每个多长。多出来的直接丢, 不报错 */
    private const val MAX_ARGUMENTS = 4
    private const val MAX_ARGUMENT_CHARS = 512

    /**
     * 一条命令怎么拼
     *
     * @property binary 要跑的那个可执行文件
     * @property prefix 固定的那几个词
     * @property userId 用哪个 user 跑, null 表示不带 `--user` (不是每条命令都吃这个参数)
     * @property maxArguments 这条吃几个参数。默认就是全局上限, **只有通用 intent 一条放得更宽** ——
     *   `am start` 的动作 / 数据 / 组件 / 目标屏四样都要占位置, 放不进 4 个参数。宽的那一条仍然落在
     *   同一套边界里: 同一个二进制、同一个动词、每个参数一样长, 拼不出第二个动词
     */
    private class Entry(
        val binary: String,
        val prefix: List<String>,
        val userId: Boolean = false,
        val maxArguments: Int = MAX_ARGUMENTS,
    )

    private val entries = mapOf(
        "forceStop" to Entry("am", listOf("force-stop")),
        "clearData" to Entry("pm", listOf("clear")),
        "uninstall" to Entry("pm", listOf("uninstall")),
        "install" to Entry("pm", listOf("install", "-r", "-t")),
        "airplane" to Entry("settings", listOf("put", "global", "airplane_mode_on")),
        "data" to Entry("svc", listOf("data")),
        "wifi" to Entry("svc", listOf("wifi")),
        "bluetooth" to Entry("svc", listOf("bluetooth")),
        "wake" to Entry("input", listOf("keyevent", "KEYCODE_WAKEUP")),
        "sleep" to Entry("input", listOf("keyevent", "KEYCODE_SLEEP")),
        // 1.0.3 应用控制的其余几条: 启用 / 停用 / 换默认桌面。都是 pm 与 cmd 的具体动作, 名字认不
        // 出来一律拒 —— 这张表就是"模型能不能凑出一条任意命令"这个问题的答案
        "enable" to Entry("pm", listOf("enable"), userId = true),
        "disable" to Entry("pm", listOf("disable-user"), userId = true),
        "setHome" to Entry("cmd", listOf("package", "set-home-activity"), userId = true),
        // 打开一个链接: 动词固定 VIEW, url 是 `-d` 的值, 目标屏跟在它后面。**不带 userId** ——
        // `--user` 会插在 `-d` 与它的值中间, 把 url 顶成 am 自己的选项
        "openUrl" to Entry("am", listOf("start", "-a", "android.intent.action.VIEW")),
        // 通用 intent: 只放 `am start` 这一个动词, 参数最多 8 个
        "intent" to Entry("am", listOf("start")),
        // 组合键: `input keycombination KEYCODE_CTRL_LEFT KEYCODE_A`
        "keyCombo" to Entry("input", listOf("keycombination")),
    )

    /**
     * 跑一件事
     *
     * @return 退出码与它打印的原话。白名单里没有的名字在这里被拒成一句人话, 而不是交给系统去报错
     */
    fun run(operation: String, userId: Int, arguments: List<String>, timeoutMs: Long): SystemOutput {
        val entry = entries[operation]
            ?: return SystemOutput(2, "this device's privileged side does not know the operation \"$operation\"")

        val bounded = arguments.take(entry.maxArguments).map { it.take(MAX_ARGUMENT_CHARS) }
        if (bounded.any { it.isEmpty() }) {
            return SystemOutput(2, "the $operation operation needs a non-empty argument")
        }
        // 自毁保护: 目标是本应用自己时一律拒 —— 卸载自己或清自己的数据会把通道一起清掉, 之后连
        // 说明这件事的机会都没有了
        if (bounded.any { it == SELF || it.endsWith("/$SELF.apk") }) {
            return SystemOutput(
                2,
                "refusing to run $operation against this app itself: that would take the" +
                    " privileged channel down with it",
            )
        }

        val command = mutableListOf(entry.binary)
        command += entry.prefix
        if (entry.userId) {
            command += listOf("--user", userId.toString())
        }
        command += bounded
        Log.i(TAG, "running $operation as ${command.joinToString(" ")}")
        return execute(command, timeoutMs)
    }

    /** 一条命令跑完为止, 超时就杀掉 */
    private fun execute(command: List<String>, timeoutMs: Long): SystemOutput {
        val budget = timeoutMs.coerceIn(1_000L, 300_000L)
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        } catch (error: Throwable) {
            return SystemOutput(127, "could not start ${command.first()}: ${error.message}")
        }
        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (error: Throwable) {
            ""
        }
        if (!process.waitFor(budget, TimeUnit.MILLISECONDS)) {
            process.destroy()
            return SystemOutput(124, "the command did not finish within ${budget}ms: $output")
        }
        val trimmed = output.trim().lines().takeLast(20).joinToString("\n")
        return SystemOutput(process.exitValue(), trimmed)
    }

    /** 本应用自己的包名: 上面那条自毁保护按它判 */
    const val SELF = "io.github.yuloong07star.luwi"
}
