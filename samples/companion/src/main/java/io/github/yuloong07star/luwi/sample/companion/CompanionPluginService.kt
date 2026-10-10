package io.github.yuloong07star.luwi.sample.companion

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import io.github.yuloong07star.luwi.plugin.api.ILwPlugin
import io.github.yuloong07star.luwi.plugin.api.ILwPluginContext
import io.github.yuloong07star.luwi.plugin.api.LwPluginApi
import io.github.yuloong07star.luwi.plugin.api.LwPluginResults

/**
 * 样例伴侣的本体
 *
 * 三件事一眼就能看完: [describe] 自述 (与包里那份 `plugin.json` 对照, 不一致 LW 就不让启用)、
 * [lifecycle] 接那四个事件 (LW 在 `attach` 那一次把 context 与插件自己的目录递进来)、
 * [invoke] 收工具调用。三条工具各演示一条路 —— 纯算的 / 借 LW 能力的 / 要敏感能力而默认没授权的
 */
class CompanionPluginService : Service() {
    override fun onBind(intent: Intent?): IBinder = plugin

    private val plugin = object : ILwPlugin() {
        /** LW 在 `attach` 那一次递进来的东西, 没 attach 之前调用工具一律如实报 */
        private var context: ILwPluginContext? = null
        private var directory: String = ""
        private var lwVersion: String = ""
        private var grants: Set<String> = emptySet()

        override fun describe(): Bundle = Bundle().apply {
            putString(LwPluginApi.Plugin.ID, ID)
            putString(LwPluginApi.Plugin.NAME, "你好伴侣")
            putString(LwPluginApi.Plugin.VERSION, VERSION)
            putString(LwPluginApi.Plugin.API, LwPluginApi.API_VERSION.toString())
            putStringArrayList(LwPluginApi.Plugin.TOOLS, arrayListOf(TOOL_PING, TOOL_BATTERY, TOOL_ASK))
            putStringArrayList(LwPluginApi.Plugin.CAPABILITIES, arrayListOf("device.read", "notify.post"))
            putStringArrayList(LwPluginApi.Plugin.DANGEROUS, arrayListOf("session.post"))
        }

        override fun lifecycle(event: String, args: Bundle?): Bundle = when (event) {
            LwPluginApi.Event.ATTACH -> {
                context = ILwPluginContext.asInterface(args?.getBinder(LwPluginApi.Attach.CONTEXT))
                directory = args?.getString(LwPluginApi.Attach.DIR).orEmpty()
                lwVersion = args?.getString(LwPluginApi.Attach.LW).orEmpty()
                grants = args?.getStringArrayList(LwPluginApi.Attach.GRANTS)?.toSet().orEmpty()
                LwPluginResults.ok("挂上了, 目录 $directory, LW $lwVersion, 已授权 ${grants.size} 条")
            }
            LwPluginApi.Event.ENABLE -> LwPluginResults.ok("你好伴侣开始在岗")
            LwPluginApi.Event.DISABLE -> LwPluginResults.ok("你好伴侣下班了")
            LwPluginApi.Event.DETACH -> {
                context = null
                LwPluginResults.ok("摘下来了")
            }
            else -> LwPluginResults.fail("不认识这个事件: $event")
        }

        override fun invoke(tool: String, args: Bundle?): Bundle = when (tool) {
            TOOL_PING -> {
                val said = args?.getString("text").orEmpty()
                LwPluginResults.ok(
                    "你好伴侣收到了「$said」—— 我住在 $directory, 对面的 LW 是 $lwVersion, " +
                        "拿到的授权有 ${if (grants.isEmpty()) "一条都没有" else grants.joinToString(" ")}",
                )
            }
            TOOL_BATTERY -> tell("device.read", Bundle().apply { putString("op", "battery") })
            TOOL_ASK -> tell("session.post", Bundle().apply { putString("text", args?.getString("question").orEmpty()) })
            else -> LwPluginResults.fail("你好伴侣没有这个工具: $tool")
        }

        /** 借 LW 一条能力: 它没接通 / 没授权 / 参数不对, 都由 LW 那一句话说了算, 这里不编 */
        private fun tell(capability: String, args: Bundle): Bundle {
            val bound = context ?: return LwPluginResults.fail("LW 还没把 context 递过来 (attach 没走完)")
            val result = bound.call(capability, args)
            return if (LwPluginResults.isOk(result)) {
                LwPluginResults.ok(LwPluginResults.text(result))
            } else {
                LwPluginResults.fail(LwPluginResults.text(result))
            }
        }
    }

    companion object {
        const val ID = "io.github.yuloong07star.luwi.sample.companion"
        const val VERSION = "1.0.0"
        const val TOOL_PING = "hello_ping"
        const val TOOL_BATTERY = "hello_battery"
        const val TOOL_ASK = "hello_ask"
    }
}
