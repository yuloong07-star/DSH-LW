package io.github.miuzarte.littlewhale.tool

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.miuzarte.littlewhale.host.DshHost
import io.github.miuzarte.littlewhale.host.HostStatus
import io.github.miuzarte.littlewhale.overlay.OverlayService
import io.github.miuzarte.littlewhale.overlay.OverlayState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 浮窗的开关: 把 dsh 的界面浮在别的应用上面
 *
 * 窗是 [OverlayService] 建的, 这里只负责三件事 —— 查权限、把 host 报出来的那个带 token 的地址
 * 交过去、再看它现在什么样。真正的绘制与输入都不在这一层
 *
 * `SYSTEM_ALERT_WINDOW` 那条特殊访问本机已经给了 (见 lw_permissions 的「悬浮窗」), 而清单里的
 * `MANAGE_OVERLAY_PERMISSION` 只是把授权位占住 —— 没有它, 下面这一步会直接说缺哪一条, 不会
 * 悄悄什么都不画
 */
internal object LwOverlay {

    /** 与 OverlayService 里的边距同一个数: 不点名位置时贴左上角, 但留一点缝 */
    private const val MARGIN_DP = 12

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "show" -> show(context, request)
        "hide" -> hide(context)
        "state" -> state(context)
        else -> throw IllegalArgumentException("op has to be show, hide or state, not \"$op\"")
    }

    private fun show(context: Context, request: JsonObject): JsonObject {
        if (!Settings.canDrawOverlays(context)) {
            unavailable(
                "floating the dsh window",
                "the overlay permission is not granted: turn on 显示在其他应用上层 for this app in the" +
                    " system settings, or run `appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow`",
            )
        }
        val running = DshHost.status
        if (running !is HostStatus.Running) {
            unavailable(
                "floating the dsh window",
                "the host has not reported a GUI URL yet, so there is no page to float",
            )
        }
        val metrics = context.resources.displayMetrics
        val margin = (MARGIN_DP * metrics.density).toInt()
        val width = request.int("width", metrics.widthPixels - margin * 2)
            .coerceIn(dp(context, 200), metrics.widthPixels)
        val height = request.int("height", metrics.heightPixels * 45 / 100)
            .coerceIn(dp(context, 120), metrics.heightPixels)
        val x = request.int("x", margin)
        val y = request.int("y", (metrics.heightPixels - height - dp(context, 140)).coerceAtLeast(margin))
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
            putExtra(OverlayService.EXTRA_URL, running.url)
            putExtra(OverlayService.EXTRA_WIDTH, width)
            putExtra(OverlayService.EXTRA_HEIGHT, height)
            putExtra(OverlayService.EXTRA_X, x)
            putExtra(OverlayService.EXTRA_Y, y)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Throwable) {
            unavailable("starting the floating window", error.message ?: error.toString())
        }
        return buildJsonObject {
            put("shown", true)
            put("url", running.url)
            put("width", width)
            put("height", height)
            put("x", x)
            put("y", y)
            put("detail", "the window is up; it can be dragged by its title bar and closed with ×")
        }
    }

    /** 关掉: 服务停了窗就跟着没, 通知也一起收 */
    private fun hide(context: Context): JsonObject {
        val stopped = context.stopService(Intent(context, OverlayService::class.java))
        OverlayState.showing = false
        return buildJsonObject {
            put("hidden", true)
            put("detail", if (stopped) "the floating window was stopped" else "no floating window was running")
        }
    }

    private fun state(context: Context): JsonObject {
        val host = DshHost.status
        return buildJsonObject {
            put("permission", Settings.canDrawOverlays(context))
            put("showing", OverlayState.showing)
            put("url", OverlayState.url ?: "")
            put("page", OverlayState.lastError ?: "")
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

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
