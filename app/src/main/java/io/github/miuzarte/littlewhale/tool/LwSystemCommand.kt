package io.github.miuzarte.littlewhale.tool

import io.github.miuzarte.littlewhale.channel.LwServiceProtocol
import io.github.miuzarte.littlewhale.channel.PrivilegedChannel
import io.github.miuzarte.littlewhale.ui.DestructiveConfirm
import kotlinx.serialization.json.JsonObject

/**
 * 那些只有特权 uid 才做得动的事
 *
 * 卸载、清数据、停应用、装包、飞行模式、移动数据与蓝牙开关、熄屏 —— 非 root / shell 的应用调不动
 * 它们 (`am` 与 `pm` 对 app uid 直接拒绝), 所以一律转给特权进程。**白名单写在特权那一侧**
 * (`LwSystemCommandTable`): 这一层送过去的只是一个操作名与几个参数, 那边对不上就拒
 *
 * 四种操作在动手之前还要人点一下确认 ([DestructiveConfirm]): 它们要么让别人的应用消失, 要么让人
 * 正在用的应用停下。**没人点就不执行**, 而且屏幕关着的时候弹窗看不见, 于是也不会执行
 */
internal object LwSystemCommand {

    /** 认得"要人点一下"的那几个 */
    private val destructive = setOf("uninstall", "clearData", "forceStop", "install")

    /** 一次调用送过去的参数上限, 与特权那边的表对齐 */
    private const val MAX_ARGS = 4

    /**
     * 一件事做完没有
     *
     * 走 [LwServiceProtocol.SYSTEM_COMMAND] 那个事务: 目标操作、用户、参数与超时一起送过去, 回来
     * 的是特权进程自己的话说做了什么
     */
    fun run(
        operation: String,
        arguments: List<String> = emptyList(),
        userId: Int = 0,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        val service = PrivilegedChannel.ensure()
            ?: throw IllegalStateException(
                "the privileged channel is not available, so ${operation} cannot be run:" +
                    " " + (PrivilegedChannel.state().error ?: "no reason reported"),
            )
        val bounded = arguments.take(MAX_ARGS)
        val answer = service.systemCommand(operation, userId, bounded, timeoutMs)
        return Result(answer.code, answer.output)
    }

    /**
     * 一件破坏性的事: 先等人点, 再去做
     *
     * 等待是挂起而不是轮询: 桥那条请求为每一次调用开一个线程, 所以挂 100 秒不会占住别的调用
     */
    suspend fun destructive(
        operation: String,
        target: String,
        arguments: List<String> = emptyList(),
        userId: Int = 0,
        timeoutMs: Long = INSTALL_TIMEOUT_MS,
    ): JsonObject {
        val what = when (operation) {
            "uninstall" -> "uninstall $target"
            "clearData" -> "clear all data of $target"
            "forceStop" -> "force stop $target"
            "install" -> "install $target"
            else -> "$operation $target"
        }
        if (!DestructiveConfirm.ask(what, "target: $target")) {
            throw IllegalStateException(
                "$operation was not confirmed within ${DestructiveConfirm.DEADLINE_SECONDS} seconds," +
                    " so nothing was done: the app puts this one behind a dialog on the phone, and" +
                    " nobody tapped it",
            )
        }
        val result = run(operation, arguments.ifEmpty { listOf(target) }, userId, timeoutMs)
        return text(
            "$what: " + result.output.ifBlank { "the privileged side said nothing" } +
                if (result.code == 0) "" else " (exit code ${result.code})",
        )
    }

    /** 这一批完整的入口: 破坏性那四条先挂起等人点, 开关那几条直接就做 */
    suspend fun dispatch(request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "forceStop", "clearData", "uninstall", "install" -> destructive(op, request)
        "airplane", "data", "wifi", "bluetooth" -> text(toggle(request, op))
        else -> throw IllegalArgumentException(
            "op has to be one of forceStop, clearData, uninstall, install, airplane, data, wifi," +
                " bluetooth, not \"$op\"",
        )
    }

    /**
     * 破坏性的一条: 先把要做什么说清楚, 再等人点
     *
     * 四种操作的目标都是那一个参数 (要装的包给的是 APK 路径), 所以参数表在这里拼, 不由调用方拼
     */
    private suspend fun destructive(operation: String, request: JsonObject): JsonObject =
        destructive(
            operation = operation,
            target = request.string("package"),
            arguments = listOf(request.string("package")),
            userId = request.int("user", 0),
        )

    /** 开关类的: 先读现在什么样, 再改成相反的那个 */
    private fun toggle(request: JsonObject, what: String): String {
        val wanted = request.bool("on", true)
        val result = run(what, listOf(if (wanted) "on" else "off"))
        return "turned $what " + (if (wanted) "on" else "off") + ": " +
            result.output.ifBlank { "no output" } +
            if (result.code == 0) "" else " (exit code ${result.code})"
    }

    /** 熄屏 / 唤醒: 由 [LwPower] 调 */
    fun power(request: JsonObject): JsonObject {
        val on = request.string("op") == "on"
        val result = run(if (on) "wake" else "sleep")
        return text(
            (if (on) "woke" else "turned off") + " the screen through the privileged side: " +
                result.output.ifBlank { "no output" } +
                if (result.code == 0) "" else " (exit code ${result.code})",
        )
    }

    /** 网络开关: 由 [LwSystem.net] 的路由调 */
    fun network(request: JsonObject): JsonObject {
        val what = request.string("op")
        if (what !in setOf("airplane", "data", "wifi", "bluetooth")) {
            throw IllegalArgumentException("net can change airplane, data, wifi or bluetooth")
        }
        return text(toggle(request, what))
    }

    const val DEFAULT_TIMEOUT_MS = 30_000L

    /** 装一个包要解压、要校验, 30 秒不够 */
    const val INSTALL_TIMEOUT_MS = 120_000L
}

/** 一次特权命令的结果 */
data class Result(val code: Int, val output: String)
