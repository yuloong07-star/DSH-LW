package io.github.miuzarte.littlewhale.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * "回应用"要落到哪一场对话上
 *
 * 主人 2026-10-06 的口径: **回应用时回到最近的浮标对话** —— 浮标的对话有它自己那一场 (在 `dsh-ball`
 * 那个工作区里, 见 host-plugin 的 `ensureBallWorkspace` 与 `voiceTargetSession`), 而会话界面自己记着
 * "正在看哪一场"(它在浏览器 localStorage 里), 它本来**不会**跟着浮标走 —— 所以"把界面放到前面"这件事
 * 与"让界面切到那一场"是两件不同的事, 后者要有人把会话 id 交给页面 ([HostScreen] 那一段 JS)
 *
 * 这一条就是那一根线: 浮标那一侧写下 ([ask], 由 `OverlayService.openApp` 从回复框记着的那一场或
 * `voice/session.json` 取), 会话界面那一侧做完才清 ([done]) —— 它**只存一个 id 与一个序号**:
 *
 * - 写的时候界面可能还没建起来 (冷启动那一档): 所以它在会话界面里是"取到才清", 没取到就留着
 * - **序号是 2026-10-07 加的, 而且是必须的**: 界面那一侧是拿这个对象当 `LaunchedEffect` 的钥匙的,
 *   而 Compose 的状态在"值没变"时不会通知任何人 —— 同一个会话连点两次, 第二次就静默什么都不发生
 *   (主人报的那条"在 dsh 应用里双击回复框没有任何反馈"正是这一档)
 */
internal object BallReturn {

    /**
     * 一次"回应用"的请求: 落到哪一场 + 自增序号
     *
     * 序号只加不减, 所以**每一次 [ask] 都是一个新请求**, 哪怕会话 id 一个字没变
     */
    data class Request(val session: String, val seq: Long)

    /** 待办的那一次请求 (null = 没有这个请求) */
    var request: Request? by mutableStateOf(null)
        private set

    private var seq: Long = 0

    /** 现在待办的是哪一场 (没有请求就是 null): 给要读"那一个 id"的地方用 */
    val session: String? get() = request?.session

    /** 浮标那边叫一声: [session] 是要落到的那一场, 空/空白就什么都不做 */
    fun ask(session: String?) {
        val wanted = session?.trim().orEmpty()
        if (wanted.isEmpty()) return
        seq += 1
        request = Request(wanted, seq)
    }

    /**
     * 会话界面把这一次请求做完 (或者放弃) 了
     *
     * **只清自己那一个序号**: 做的那几秒里可能又来了一次新请求 (同一个会话连点两下就是这一档),
     * 清掉别人的请求等于把主人的第二下吃了
     */
    fun done(seq: Long) {
        if (request?.seq == seq) request = null
    }
}
