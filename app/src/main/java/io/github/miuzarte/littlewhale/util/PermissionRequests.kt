package io.github.miuzarte.littlewhale.util

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 待落地的运行时权限申请
 *
 * 申请只能从 Activity 发起, 而按钮在设置页里, 所以中间的这一步用一个可以观察的对象接起来:
 * 设置页把要什么写进来, 应用那一层看到了就去弹系统框, 结果回调再把话传回来
 *
 * **一条一条来, 不能一把全发**。真机上实测过: 一次 `requestPermissions` 带两条权限时
 * `Activity` 直接打 `W Can request only one set of permissions at a time` 并丢掉后一条, 于是点了
 * 没有任何下文 —— 这正是"身体传感器与活动"那一条点了打不开的原因 (它要的两条里有一条发不出去)。
 * 所以队列在这里排队, 每一条拿到答案之后再放行下一条
 *
 * @property permissions 一次要的那几条权限的**完整清单**
 * @property onResult 全部要完 (或被拒) 之后做什么 (设置页拿它刷新状态)
 */
class PermissionRequest(
    val permissions: List<String>,
    val onResult: () -> Unit,
)

/** 待处理的申请, 同一时刻最多一条: 系统框本身就不允许叠着弹 */
object PermissionRequests {

    private const val TAG = "LwPermissions"

    /** 现在等着弹的那一条, null 表示没有 */
    var pending: PermissionRequest? by mutableStateOf(null)
        private set

    /** 这一条还剩下哪几条没问 */
    private var remaining: List<String> = emptyList()

    /** 正在问的那一条权限, 只给日志用 */
    private var asking: String? = null

    /** 排队要一条申请 */
    fun request(permissions: List<String>, onResult: () -> Unit = {}) {
        if (permissions.isEmpty()) {
            onResult()
            return
        }
        if (pending != null) {
            Log.i(TAG, "a request is already in flight, so this one was dropped")
            return
        }
        remaining = permissions
        pending = PermissionRequest(permissions, onResult)
    }

    /**
     * 下一条该弹的权限, 没有就 null
     *
     * 应用那一层每次拿到答案 (或者第一次看到 pending) 都问一次这里, 于是权限是一条一条被问到的
     */
    fun next(consume: Boolean): String? {
        if (pending == null) return null
        if (!consume) return asking ?: remaining.firstOrNull()
        asking = remaining.firstOrNull()
        return asking
    }

    /** 刚问的那一条有答案了, 把它从队列里去掉 */
    fun answered() {
        val asked = asking ?: return
        remaining = remaining.filterNot { it == asked }
        asking = null
    }

    /** 这一条申请整个结束了 */
    fun done() {
        val current = pending ?: return
        pending = null
        remaining = emptyList()
        asking = null
        current.onResult()
    }
}
