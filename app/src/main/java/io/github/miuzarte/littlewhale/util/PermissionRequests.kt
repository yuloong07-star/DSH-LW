package io.github.miuzarte.littlewhale.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一次还没落地的运行时权限申请
 *
 * 申请只能从 Activity 发起, 而按钮在设置页里, 所以中间的这一步用一个可以观察的对象接起来:
 * 设置页把 [pending] 置上, 应用那一层看到了就去弹系统框, 结果回调再把话传回来
 *
 * @property permissions 这一次要的权限
 * @property onResult 有了答案之后做什么 (设置页拿它刷新状态)
 */
class PermissionRequest(
    val permissions: List<String>,
    val onResult: () -> Unit,
)

/** 待处理的申请, 同一时刻最多一条: 系统框本身就不允许叠着弹 */
object PermissionRequests {

    /** 现在等着弹的那一条, null 表示没有 */
    var pending: PermissionRequest? by mutableStateOf(null)
        private set

    /** 排队要一条申请 */
    fun request(permissions: List<String>, onResult: () -> Unit = {}) {
        if (permissions.isEmpty()) {
            onResult()
            return
        }
        if (pending != null) return
        pending = PermissionRequest(permissions, onResult)
    }

    /** 应用那一层弹完了, 把这一条收掉 */
    fun done() {
        val current = pending ?: return
        pending = null
        current.onResult()
    }
}
