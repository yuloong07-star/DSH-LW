package io.github.yuloong07star.luwi.tool

import android.app.NotificationManager
import android.content.Context
import io.github.yuloong07star.luwi.channel.LwNotificationListener
import io.github.yuloong07star.luwi.channel.LwPermission
import io.github.yuloong07star.luwi.channel.PrivilegedChannel
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit

/**
 * 通知栏那一侧: 有什么, 以及清掉什么
 *
 * 读数来自 [LwNotificationListener] —— 系统把那个服务绑在**这个进程里**, 所以这条工具既不过 binder,
 * 也不需要特权 uid, 与无障碍那条是同一个形状
 *
 * 三条纪律与别处一样, 而这里最容易违反的是第一条: **"没有通知"与"读不到通知"长得一模一样**。
 * 没有通知使用权时 `activeNotifications` 是空的, 而说成"通知栏是空的"会让人以为设备上真的没有通知 ——
 * 所以没连上时回的是"读不到", 并把怎么打开说清楚
 */
internal object LwNotifications {

    /** 一次最多列多少条, 与监听那边一致 (它已经截过一次, 这里是给文本一个上限) */
    private const val MAX_LISTED = 50

    /** 一次最多清多少条, 免得一个 package 的误写把整个通知栏扫干净 */
    private const val MAX_CANCELLED = 20

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "list" -> list()
        "cancel" -> cancel(request)
        else -> throw IllegalArgumentException("op has to be list or cancel, not \"$op\"")
    }

    /** 通知栏里现在有什么 */
    private fun list(): JsonObject {
        val posted = LwNotificationListener.active() ?: throw IllegalStateException(unreadable())
        if (posted.isEmpty()) {
            return text(
                "the notification shade is empty: the listener is connected and reports nothing" +
                    " posted right now",
            )
        }
        val now = System.currentTimeMillis()
        val rows = posted.take(MAX_LISTED).joinToString("\n") { line(it, now) }
        val tail = if (posted.size > MAX_LISTED) {
            "\n(and ${posted.size - MAX_LISTED} more, oldest first drop off this list)"
        } else {
            ""
        }
        return text(
            "${posted.size} notification(s) in the shade, newest first. Each line starts with the" +
                " key lw_notifications op=cancel takes:\n$rows$tail",
        )
    }

    /** 清掉一条 (按 key) 或者一个应用的全部可清通知 (按 package) */
    private fun cancel(request: JsonObject): JsonObject {
        val key = request.stringOrNull("key")
        if (key != null) return text(LwNotificationListener.cancel(key))
        val packageName = request.stringOrNull("package")
            ?: throw IllegalArgumentException(
                "this call has to name either key (one notification, as op=list reported it) or" +
                    " package (every clearable notification from one app)",
            )
        val posted = LwNotificationListener.active() ?: throw IllegalStateException(unreadable())
        val mine = posted.filter { it.packageName == packageName }
        if (mine.isEmpty()) {
            return text(
                "nothing in the shade is from $packageName right now" +
                    if (posted.isEmpty()) " (the shade is empty)" else "",
            )
        }
        val results = mine.take(MAX_CANCELLED).map { LwNotificationListener.cancel(it.key) }
        val left = if (mine.size > MAX_CANCELLED) {
            " (${mine.size - MAX_CANCELLED} of them were left alone: this call takes at most" +
                " $MAX_CANCELLED at a time)"
        } else {
            ""
        }
        return text(
            "$packageName has ${mine.size} notification(s) in the shade$left:\n" +
                results.joinToString("\n") { "  $it" },
        )
    }

    /** 一行一条: key, 谁发的, 重要度, 能不能清, 标题与正文, 多久之前 */
    private fun line(posted: LwNotificationListener.Posted, now: Long): String {
        val importance = importance(posted.importance)
        val state = when {
            posted.ongoing -> "ongoing, the app's own to take back"
            posted.clearable -> "clearable"
            else -> "not clearable"
        }
        val body = listOf(posted.title, posted.text)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { "(no text: the notification carries a layout of its own)" }
        val channel = posted.channel?.let { " on channel $it" }.orEmpty()
        val group = if (posted.summary) " (a group summary)" else ""
        return "  [${posted.key}] ${posted.packageName} · $importance$channel · $state$group · " +
            "${ago(now - posted.postedAt)}\n    $body"
    }

    /** 重要度说成人话, 认不出来就说认不出来 */
    private fun importance(value: Int): String = when (value) {
        NotificationManager.IMPORTANCE_MAX -> "max importance"
        NotificationManager.IMPORTANCE_HIGH -> "high importance"
        NotificationManager.IMPORTANCE_DEFAULT -> "default importance"
        NotificationManager.IMPORTANCE_LOW -> "low importance (silent)"
        NotificationManager.IMPORTANCE_MIN -> "min importance"
        NotificationManager.IMPORTANCE_NONE -> "importance none (blocked)"
        else -> "importance unknown"
    }

    /**
     * 多久之前, 短到只有一位有效数字, 而且**自己带上"之前"那个词**
     *
     * 带上是因为调用处写 `$x ago` 会让"刚刚"变成 "just now ago" —— 这种半句话在模型读到的每一行里
     */
    private fun ago(since: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(since)
        return when {
            since < TimeUnit.SECONDS.toMillis(45) -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 60 * 24 -> "${minutes / 60}h ago"
            else -> "${minutes / (60 * 24)}d ago"
        }
    }

    /**
     * 读不到通知栏时的那句话
     *
     * 分两种情况说: 授权在不在名单里 (那是系统的事实) 与服务有没有被绑上 (那是这一刻的事实)。两者
     * 都会让 `activeNotifications` 是空的, 而**处置不同**: 前者要人点一下设置页那个开关, 后者等一会儿
     * 或者重新拨一次开关
     */
    private fun unreadable(): String {
        val state = runCatching {
            val context = PrivilegedChannel.context()
            context?.let { LwPermission(it).notificationListeners() }
        }.getOrNull()
        val why = when {
            state == null -> "this app cannot read the list of listeners right now"
            !state.componentListed ->
                "this app is not in the device's list of notification listeners"
            state.granted == false -> "the system says this app does not hold 通知使用权"
            else -> "this app is listed but the system has not bound the service"
        }
        return "the notification shade cannot be read: $why. Turn it on in Luwi's own Settings" +
            " -> 通知 -> 允许 DSH 读取通知栏 (that switch writes the setting through the privileged" +
            " channel), or open the system page from the same section"
    }
}
