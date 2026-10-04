package io.github.miuzarte.littlewhale.tool

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.serialization.json.JsonObject

/**
 * 屏幕与电源: 点亮、熄灭、锁上、看现在什么样
 *
 * 这一条是清单里那个死结的解: 熄屏的时候 `screencap` 交的还是最后一帧, 注入的触摸唤不醒屏, 两个都
 * 不报错 —— 于是模型只能对着一张已经不存在的界面动手。[LwSystem.screenState] 早就会说 `off`, 而这里
 * 是看到那句之后该做的事
 *
 * 点亮与读状态是普通的电源管理, 在 app 进程里做; 熄屏走特权 (非系统应用没有那类权限); 锁屏先试
 * `lockNow()`, 没有设备管理员时退到熄屏 (灭屏之后系统自己会锁上)
 */
internal object LwPower {

    /** 等屏幕真的亮起来的预算 */
    private const val WAKE_BUDGET_MS = 5_000L

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "state" -> text(LwSystem.screenState(context) + ", " + lockState(context))
        "on" -> wake(context)
        "off" -> LwSystemCommand.power(request)
        "lock" -> lock(context, request)
        else -> throw IllegalArgumentException("op has to be state, on, off or lock, not \"$op\"")
    }

    /** 点亮屏幕: 先请求唤醒, 真的亮了才说点亮了 */
    private fun wake(context: Context): JsonObject {
        val power = context.getSystemService(PowerManager::class.java)
            ?: unavailable("waking the screen", "this device has no power manager")
        if (power.isInteractive) {
            return text("the screen is already on (" + LwSystem.screenState(context) + "), so nothing was done")
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val wakeLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "lw:wake",
        )
        try {
            wakeLock.acquire(WAKE_BUDGET_MS + 2_000)
        } catch (error: Throwable) {
            // 应用拿不到电源锁时退到特权那边的按键唤醒 (KEYCODE_WAKEUP)
            val fallback = LwSystemCommand.power(
                kotlinx.serialization.json.buildJsonObject {
                    put("op", kotlinx.serialization.json.JsonPrimitive("on"))
                },
            )
            return text(
                "no wake lock (${error.message}), so the screen was woken through the privileged" +
                    " side: " + answerOf(fallback),
            )
        }
        try {
            var waited = 0L
            while (!power.isInteractive && waited < WAKE_BUDGET_MS) {
                Thread.sleep(100)
                waited += 100
            }
            val state = LwSystem.screenState(context)
            return text(
                if (power.isInteractive) {
                    "woke the screen: $state" +
                        if (keyguard?.isKeyguardLocked == true) {
                            ", which is still locked: dismissing a keyguard needs an activity," +
                                " and the app comes to the front over the lock screen when the" +
                                " notification is tapped"
                        } else {
                            ""
                        }
                } else {
                    "asked the screen to wake but it is still $state after ${WAKE_BUDGET_MS}ms," +
                        " so check that the device is not in a doze or that the power button is stuck"
                },
            )
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    /** 锁屏: 有设备管理员就直接锁, 没有就熄屏 (灭屏之后系统自己会锁) */
    private fun lock(context: Context, request: JsonObject): JsonObject {
        val policy = context.getSystemService(DevicePolicyManager::class.java)
        // 本应用不是设备管理员, 所以 lockNow 这一路现在多半走不到: 写在这里是为了别把它当成能用的
        // 东西 —— 真要锁屏, 只有 `isAdminActive` 之后那句话才成立
        val admin = policy != null && policy.isAdminActive(
            android.content.ComponentName(context, javaClass),
        )
        if (admin) {
            return try {
                policy!!.lockNow()
                text("locked the screen: " + LwSystem.screenState(context))
            } catch (error: Throwable) {
                text("the device administrator refused to lock (${error.message})")
            }
        }
        val fallback = LwSystemCommand.power(request)
        return text(
            "this app is not a device administrator, so it cannot lock the keyguard directly; the" +
                " screen was turned off instead, and the system locks it: " + answerOf(fallback),
        )
    }

    private fun lockState(context: Context): String {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return "unknown"
        return when {
            keyguard.isKeyguardLocked -> "keyguard locked"
            keyguard.isDeviceLocked -> "device locked"
            else -> "unlocked"
        }
    }

    /** 从另一个工具的答案里取那句文本, 好拼成一句话 */
    private fun answerOf(answer: JsonObject): String =
        (answer["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: answer.toString()
}
