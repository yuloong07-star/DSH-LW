package io.github.yuloong07star.luwi.channel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 通知使用权的开关, 在应用自己的设置页上
 *
 * 与 [AccessibilitySetting] 是同一个形状, 也是同一个理由: 服务只能靠写一条 secure setting 打开, 而
 * 那件事由特权进程做; 而"开没开"根本不是一条设置, 是**系统有没有把它绑上**, 所以显示的状态读自服务
 * 实例本身, 会比请求晚一小步
 *
 * 写设置是阻塞的, 所以在自己的工作线程上做, 页面看 [working] 与 [lastError], 不等它
 */
object NotificationSetting {

    private const val TAG = "NotificationSetting"

    /** 一次一个, 因为它们写的是同一个 key */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lw-notification-access").apply { isDaemon = true }
    }

    /** 有没有一次请求在路上 */
    var working: Boolean by mutableStateOf(false)
        private set

    /** 上一次失败的原因, 下一次成功时清掉 */
    var lastError: String? by mutableStateOf(null)
        private set

    /** 系统是不是真的把服务绑上了 —— 这一页与 `lw_probe` 都以它为准 */
    var enabled: Boolean by mutableStateOf(LwNotificationListener.running)
        private set

    /** 读齐的那几件事实 (含"横幅给不给"), 给这一页当说明用 */
    var state: NotificationState? by mutableStateOf(null)
        private set

    /** 页面出现时读一次真实状态 */
    fun refresh() {
        enabled = LwNotificationListener.running
        refreshState()
    }

    /** 把几件事实读齐, 在 worker 线程上 (要起子进程问名单) */
    fun refreshState() {
        worker.execute {
            val context = PrivilegedChannel.context() ?: return@execute
            val inspected = try {
                LwPermission(context).notificationListeners()
            } catch (error: Throwable) {
                Log.w(TAG, "could not read the notification access state", error)
                null
            }
            if (inspected != null) {
                state = inspected
                enabled = inspected.running
            }
        }
    }

    /**
     * 请求打开或关掉
     *
     * @param on 开关被拨到哪一边
     */
    fun set(on: Boolean, done: (Boolean) -> Unit = {}) {
        if (working) return
        working = true
        worker.execute {
            val written = try {
                val service = PrivilegedChannel.ensure()
                    ?: throw IllegalStateException(
                        PrivilegedChannel.state().error ?: "the privileged channel is not available",
                    )
                if (service.setNotificationListener(on)) {
                    lastError = null
                    true
                } else {
                    throw IllegalStateException("the privileged process could not write the setting")
                }
            } catch (error: Throwable) {
                lastError = error.message ?: error.javaClass.simpleName
                Log.w(TAG, "could not ${if (on) "enable" else "disable"} notification access", error)
                false
            }
            settle(on)
            working = false
            done(written)
        }
    }

    /** 给系统一点时间去绑定或解绑, 然后报真实状态 */
    private fun settle(on: Boolean) {
        var waited = 0L
        while (LwNotificationListener.running != on && waited < SETTLE_BUDGET_MS) {
            Thread.sleep(SETTLE_STEP_MS)
            waited += SETTLE_STEP_MS
        }
        val context = PrivilegedChannel.context()
        state = try {
            context?.let { LwPermission(it).notificationListeners() }
        } catch (error: Throwable) {
            Log.w(TAG, "could not read the state after a write", error)
            null
        }
        enabled = state?.running ?: LwNotificationListener.running
        if (enabled != on) {
            lastError = state?.reason() ?: "系统没有把服务绑上, 设置写进去了但没生效"
        }
    }

    private const val SETTLE_BUDGET_MS = 3_000L
    private const val SETTLE_STEP_MS = 200L
}
