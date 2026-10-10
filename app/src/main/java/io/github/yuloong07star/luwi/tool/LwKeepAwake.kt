package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/**
 * 让设备别睡
 *
 * 熄屏是这台设备上最阴的一类失败: 截图照样交出一张图 (最后一帧), 注入的触摸唤不醒屏, 两个都不报错 ——
 * 模型于是在读一个已经不存在的界面。所以"跑这件事的这段时间别睡"值得是一个显式的开关, 而不是让模型
 * 自己想办法
 *
 * 用的是一个 **`PARTIAL_WAKE_LOCK`**: 屏幕可以关, CPU 不睡。这正是"把这件事跑完"要的那一种, 而
 * `SCREEN_BRIGHT_WAKE_LOCK` 那一类会一直亮着屏幕, 那是另一个意思 (用户盯着一块亮着的屏)
 *
 * **它不会自己松手**, 谁开谁关 —— 一个被忘掉的唤醒锁是用户看不见的电量黑洞, 所以答案里带"已经按了
 * 多久", 而且关的时候如实说本来就关着, 不假装刚刚生效
 */
internal object LwKeepAwake {

    private const val TAG = "LwKeepAwake"

    /** 这个名字会出现在 `dumpsys power` 里, 带着包名一眼看得出是谁按住的 */
    private const val LOCK_NAME = "luwi:keep-awake"

    /** 唤醒锁不能跨进程活着, 所以它跟着这个对象走, 而这个对象跟着应用进程走 */
    private var lock: PowerManager.WakeLock? = null

    /** 这一把是什么时候按下的, 用来回答"按了多久" (-1 表示没按着) */
    private var since: Long = -1L

    /**
     * 开 / 关 / 问
     *
     * @param request `op` 是 `on` / `off` / `status`, 省略时按 `on` 算 —— 一个只带这个工具名的调用
     *   想要的显然是"别睡"
     */
    fun dispatch(request: JsonObject): JsonObject {
        val op = request.string("op").trim().ifEmpty { "on" }
        return when (op) {
            "on" -> acquire()
            "off" -> release()
            "status", "get" -> text(status())
            else -> throw IllegalArgumentException("op has to be on, off or status, not \"$op\"")
        }
    }

    private fun acquire(): JsonObject {
        val held = lock?.isHeld == true
        if (held) return text("already holding it: " + status())
        val context = requireContext()
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: throw IllegalStateException("this device has no power manager to ask")
        val fresh = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_NAME)
        // 不用 acquire(timeout) 那种写法: 超时到了一个还在跑的任务就成了"跑到一半设备又睡了",
        // 而这件事该由调用方明说, 不该由这一层猜一个时长
        fresh.acquire()
        lock = fresh
        since = System.currentTimeMillis()
        Log.i(TAG, "holding a partial wake lock")
        return text("holding it: the screen may still go off, but the CPU will not sleep" + statusTail())
    }

    private fun release(): JsonObject {
        val held = lock?.isHeld == true
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        since = -1L
        Log.i(TAG, "released the partial wake lock, held=$held")
        return text(
            if (held) {
                "let go: the device goes back to sleeping on its own"
            } else {
                "nothing was holding it, so nothing changed (a device is allowed to sleep already)"
            },
        )
    }

    /** 现在什么样, 一句话 */
    private fun status(): String {
        val held = lock?.isHeld == true
        return if (held) {
            "holding a partial wake lock (the CPU will not sleep)${statusTail()}"
        } else {
            "not holding anything: the device sleeps on its own, and a screen that has gone off" +
                " hands back its last frame without saying so"
        }
    }

    private fun statusTail(): String {
        if (since < 0) return ""
        val seconds = (System.currentTimeMillis() - since) / 1000
        return " (held for ${seconds}s)"
    }

    /** 给 `lw_device` 那一类只读答案用的一句话 */
    fun line(): String = status()
}
