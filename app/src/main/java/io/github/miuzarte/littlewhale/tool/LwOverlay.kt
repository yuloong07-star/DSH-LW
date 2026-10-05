package io.github.miuzarte.littlewhale.tool

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.miuzarte.littlewhale.channel.LwModes
import io.github.miuzarte.littlewhale.host.DshHost
import io.github.miuzarte.littlewhale.host.HostStatus
import io.github.miuzarte.littlewhale.overlay.BallSpot
import io.github.miuzarte.littlewhale.overlay.OverlayService
import io.github.miuzarte.littlewhale.overlay.OverlayState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 浮标的开关: 把一颗球浮在别的应用上面, 点一下就能说话
 *
 * 窗是 [OverlayService] 建的, 这里只管四件事 —— 查权限、把球放出来 / 收起来、展开或收起那条输入条、
 * 再看它现在什么样。真正的绘制与输入都不在这一层
 *
 * **球与输入条是两件事**: 球常驻且不获焦 (窗外照旧穿透), 输入条只在展开时挂上 (它要收系统输入法,
 * 所以它可获焦)。`show` 默认只放球, `expand=true` 才顺带把输入条也挂上
 *
 * `SYSTEM_ALERT_WINDOW` 那条特殊访问本机已经给了 (见 lw_permissions 的「悬浮窗」), 而清单里的
 * `MANAGE_OVERLAY_PERMISSION` 只是把授权位占住 —— 没有它, 下面这一步会直接说缺哪一条, 不会
 * 悄悄什么都不画
 */
internal object LwOverlay {

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "show" -> show(context, request)
        "expand" -> expand(context)
        "collapse" -> collapse(context)
        "hide" -> hide(context)
        "state" -> state(context)
        // 宿主推来的「在想」: 一轮在跑 / 跑完了, 只改一个记号, 球那边 400 ms 读一次
        "phase" -> phase(request)
        else -> throw IllegalArgumentException(
            "op has to be show, expand, collapse, hide, state or phase, not \"$op\"",
        )
    }

    private fun show(context: Context, request: JsonObject): JsonObject {
        val expand = request.bool("expand", false)
        refusal(context)?.let { unavailable("floating the ball", it) }
        val reason = launch(context, expand)
        if (reason != null) unavailable("floating the ball", reason)
        return buildJsonObject {
            put("shown", true)
            put("expanded", expand)
            put("url", (DshHost.status as? HostStatus.Running)?.url ?: "")
            put("x", OverlayState.x)
            put("y", OverlayState.y)
            put(
                "detail",
                "the ball is up: tap it to speak, hold it for the menu (keyboard, always-listening," +
                    " the two modes, back to the app, close it) and drag it to an edge to dock it there",
            )
        }
    }

    /** 展开那条可获焦的输入条 (球留着) */
    private fun expand(context: Context): JsonObject {
        refusal(context)?.let { unavailable("expanding the floating window", it) }
        val started = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_EXPAND
            (DshHost.status as? HostStatus.Running)?.url?.let { putExtra(OverlayService.EXTRA_URL, it) }
        }
        try {
            ContextCompat.startForegroundService(context, started)
        } catch (error: Throwable) {
            unavailable("expanding the floating window", error.message ?: error.toString())
        }
        BallSpot.setOn(context, true)
        return buildJsonObject {
            put("expanded", true)
            put("detail", "the keyboard strip is up, so this window takes focus while it is open")
        }
    }

    /** 收起输入条, 球留着 */
    private fun collapse(context: Context): JsonObject {
        val intent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_COLLAPSE)
        runCatching { ContextCompat.startForegroundService(context, intent) }
        return buildJsonObject {
            put("collapsed", true)
            put("detail", "the keyboard strip is gone; the ball stays")
        }
    }

    /** 关掉浮标: 存盘那个开关一起关 (否则下一次应用启动它又自己冒出来) */
    private fun hide(context: Context): JsonObject {
        val stopped = context.stopService(Intent(context, OverlayService::class.java))
        BallSpot.setOn(context, false)
        OverlayState.showing = false
        OverlayState.expanded = false
        return buildJsonObject {
            put("hidden", true)
            put("detail", if (stopped) "the ball was stopped" else "no ball was running")
        }
    }

    /** 宿主说的一轮在跑没有: 只记记号, 不画任何东西 (没球的时候它就是个没人看的字段) */
    private fun phase(request: JsonObject): JsonObject {
        val asked = request.string("phase").lowercase()
        val phase =
            if (asked == OverlayState.PHASE_THINKING) OverlayState.PHASE_THINKING else OverlayState.PHASE_IDLE
        OverlayState.phase = phase
        return buildJsonObject {
            put("phase", phase)
            put("ball", OverlayState.showing)
        }
    }

    private fun state(context: Context): JsonObject {
        val host = DshHost.status
        return buildJsonObject {
            put("permission", Settings.canDrawOverlays(context))
            put("showing", OverlayState.showing)
            put("expanded", OverlayState.expanded)
            put("remembered", BallSpot.on(context))
            put("x", OverlayState.x)
            put("y", OverlayState.y)
            put("word", OverlayState.word ?: "")
            put("phase", OverlayState.phase)
            put("mode", LwModes.active(context))
            put("url", OverlayState.url ?: "")
            put("page", OverlayState.lastError ?: "")
            put("said", OverlayState.lastHint ?: "")
            put(
                "host",
                when (host) {
                    is HostStatus.Running -> host.url
                    is HostStatus.Installing -> "installing ${host.done}/${host.total}"
                    is HostStatus.Failed -> "failed: ${host.reason}"
                    HostStatus.Idle -> "idle"
                    HostStatus.Starting -> "starting"
                },
            )
        }
    }

    /**
     * 应用起来时照那个存盘开关把球放出来
     *
     * 与唤醒词那条 `ensure` 同一个道理: 开关是存盘的, 没人照着它做事的话它就只是个记号。缺权限、
     * 开关关着时这里什么都不做 (失败只记日志: 界面不该因为一颗球起不来而不出来)
     */
    internal fun ensure(context: Context) {
        if (!BallSpot.on(context)) return
        if (refusal(context) != null) return
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
            (DshHost.status as? HostStatus.Running)?.url?.let { putExtra(OverlayService.EXTRA_URL, it) }
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    /** 设置页那个开关走这一条: 回一句话 (放出去了没有 / 缺哪一条), 与工具那边说的是同一句 */
    internal fun setOn(context: Context, on: Boolean): String {
        if (!on) {
            val stopped = context.stopService(Intent(context, OverlayService::class.java))
            BallSpot.setOn(context, false)
            OverlayState.showing = false
            return if (stopped) "the ball is gone" else "no ball was running"
        }
        val reason = refusal(context) ?: launch(context, expand = false)
        if (reason != null) return reason
        return "the ball is up: tap it to speak, hold it for the menu, drag it to an edge to dock it there"
    }

    /** 放球 / 展开: null = 已经发出去了, 否则是那句拒绝的理由 */
    private fun launch(context: Context, expand: Boolean): String? {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = if (expand) OverlayService.ACTION_EXPAND else OverlayService.ACTION_SHOW
            (DshHost.status as? HostStatus.Running)?.url?.let { putExtra(OverlayService.EXTRA_URL, it) }
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Throwable) {
            return "starting the floating ball failed: ${error.message ?: error}"
        }
        BallSpot.setOn(context, true)
        return null
    }

    private fun refusal(context: Context): String? =
        if (Settings.canDrawOverlays(context)) {
            null
        } else {
            "the overlay permission is not granted: turn on 显示在其他应用上层 for this app in the" +
                " system settings, or run `appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow`"
        }
}
