package io.github.miuzarte.littlewhale.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * 破坏性操作的那道确认
 *
 * 卸载、清数据、停应用、装 APK 这四件事不由模型说了算: 请求会挂在这里等人点一下, **没人点就不执行**。
 * 这是应用进程里的一道闸, 审批 answerer 那条路绕不过它 —— 那个 answerer 管的是 dsh 自己的审批流,
 * 而这里是模型看不见的一层
 *
 * 挂了多久有上限: 桥那边一条请求的读超时是 120 秒, 所以这里等 100 秒就收, 超时回一句"没人确认",
 * 而不是把模型的这一次调用挂死
 *
 * 屏幕关着的时候这一层是看不见的, 于是它不会执行 —— 这正是要的: 半夜没人看手机的时候, 谁也不能
 * 悄悄把另一个应用卸掉
 */
object DestructiveConfirm {

    /** 等一个人点一下的上限, 压在桥那条 120 秒的读超时之下 */
    const val DEADLINE_SECONDS = 100L

    private const val DEADLINE_MS = DEADLINE_SECONDS * 1_000

    /** 正在等人点的那一件事, null 表示没有 */
    var pending: Pending? by mutableStateOf(null)
        private set

    /**
     * 等人确认一件事
     *
     * @param what 做什么 (给对话框的标题用, 也回给模型)
     * @param target 对谁做, 让点的人看得清
     * @return true 表示有人点了确定
     */
    suspend fun ask(what: String, target: String): Boolean = withContext(Dispatchers.Main) {
        try {
            withTimeout(DEADLINE_MS) { wait(what, target) }
        } catch (timeout: TimeoutCancellationException) {
            pending = null
            false
        }
    }

    private suspend fun wait(what: String, target: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            pending = Pending(what, target) { confirmed ->
                pending = null
                if (continuation.isActive) continuation.resume(confirmed)
            }
        }

    /** 弹窗要画的那一条 */
    class Pending(
        val what: String,
        val target: String,
        private val finish: (Boolean) -> Unit,
    ) {
        fun decide(confirmed: Boolean) = finish(confirmed)
    }
}
