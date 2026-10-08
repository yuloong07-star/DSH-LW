package io.github.miuzarte.littlewhale.channel

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 通知栏那一侧的读数
 *
 * 这与无障碍那条是同一个形状, 理由也一样: **系统把服务绑进这个进程**, 所以读通知既不过 binder 也
 * 不需要特权 uid。能不能读不由这个类决定 —— 要有「通知使用权」(一道用户在系统设置里给的授权,
 * 应用申请不了), 而授予之后**重装 APK 会把它收走**, 与无障碍那条一样
 *
 * 三条读数的纪律在这里尤其要紧, 因为"没有通知"与"读不到通知"长得一模一样:
 *
 * - [active] 回 null 表示**没有连接** (没授权, 或者系统还没绑上), 不是"通知栏是空的"
 * - 每一条都带上它是不是常驻 (`ongoing`) 与能不能清 (`clearable`), 因为清不掉的那些不是"失败"
 * - 清一条之前先看它能不能清, 并且**清完再读一次**确认它真的没了
 */
class LwNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "connected: the shade is readable")
    }

    override fun onListenerDisconnected() {
        instance = null
        Log.i(TAG, "disconnected: the shade is no longer readable")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * 来了一条通知 —— 这是"事件驱动"那一半
     *
     * 自动指令那条链挂一个回调就不必轮询通知栏 (读一次要过 binder, 而且大多数时候读到的与上一次
     * 一模一样)。**监听表为空时这里什么都不做**, 所以既有那条"主动列通知栏"的路一个字都没变
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (watchers.isEmpty()) return
        val described = try {
            describe(posted, currentRanking)
        } catch (error: Throwable) {
            Log.w(TAG, "could not describe a posted notification", error)
            return
        }
        watchers.forEach { watcher ->
            try {
                watcher.onPosted(described)
            } catch (error: Throwable) {
                Log.w(TAG, "a notification watcher failed", error)
            }
        }
    }

    /** 通知栏里的一条, 按"给人看"的顺序摆好 */
    data class Posted(
        val key: String,
        val packageName: String,
        val id: Int,
        val tag: String?,
        val postedAt: Long,
        val ongoing: Boolean,
        val clearable: Boolean,
        val title: String,
        val text: String,
        val category: String?,
        val channel: String?,
        val importance: Int,
        val group: String?,
        val summary: Boolean,
    )

    companion object {

        private const val TAG = "LwNotifListener"

        /** 一次最多列多少条: 一屏放不下的部分对模型没有用, 而它要读的是这份文本 */
        private const val MAX_POSTED = 50

        /**
         * 每条正文最多留多长
         *
         * 通知的正文可以是任意长的一段 (聊天软件的摘要、邮件前几行), 而这一层是给模型当"发生了什么"
         * 读的, 不是当正文仓库用的
         */
        private const val MAX_TEXT = 300

        /** 系统拥有服务的生命周期, 所以这是 app 唯一握得住它的地方 */
        @Volatile
        private var instance: LwNotificationListener? = null

        /** 进程内的事件回调: 自动指令那条链挂在这里 (它只要"新来了一条", 不要整张通知栏) */
        private val watchers = CopyOnWriteArrayList<PostedWatcher>()

        /** 一条新通知 */
        fun interface PostedWatcher {
            fun onPosted(posted: Posted)
        }

        /**
         * 挂一个回调, 返回的那个东西用来摘掉它
         *
         * 摘掉很重要: 它不是"再多收一份", 而是"通知来时多做一次判定" —— 没有那一类规则时摘掉,
         * 一天几千条通知就一条都不用看了
         */
        fun watch(watcher: PostedWatcher): AutoCloseable {
            watchers += watcher
            return AutoCloseable { watchers.remove(watcher) }
        }

        /** 此刻挂了几个回调 (排查用) */
        val watcherCount: Int get() = watchers.size

        /** 系统有没有把这个服务绑上 —— "设置里写着"与"真的活着"是两件事 */
        val running: Boolean get() = instance != null

        /**
         * 通知栏里现在有什么
         *
         * @return 最新的在前, **null 表示没连上** (没有通知使用权, 或者系统还没绑定), 与"一条都没有"
         *   是两件事
         */
        fun active(): List<Posted>? {
            val service = instance ?: return null
            val posted = try {
                service.activeNotifications
            } catch (error: Throwable) {
                Log.w(TAG, "could not read the shade", error)
                return null
            } ?: return emptyList()
            // 重要度与渠道在 RankingMap 里, 而不在那条通知上: 取得它要经正在跑的服务
            val rankings = try {
                service.currentRanking
            } catch (error: Throwable) {
                Log.w(TAG, "could not read the rankings", error)
                null
            }
            return posted
                .map { describe(it, rankings) }
                .sortedByDescending { it.postedAt }
                .take(MAX_POSTED)
        }

        /**
         * 清掉一条
         *
         * @param key [active] 给的那把钥匙
         * @return 一句给人看的结果: 清掉了, 或者为什么没清
         */
        fun cancel(key: String): String {
            val service = instance
                ?: return "the shade is not readable right now (no 通知使用权, or the system has" +
                    " not bound the service), so nothing was cancelled: turn it on in this app's" +
                    " Settings -> Notifications"
            val posted = try {
                service.activeNotifications
            } catch (error: Throwable) {
                return "could not read the shade to find that notification: ${error.message}"
            }?.firstOrNull { it.key == key }
                ?: return "nothing in the shade has the key $key any more: it was dismissed while" +
                    " this was being done, or the key came from another device's shade"
            if (posted.isOngoing) {
                return "\"${posted.notification?.let { title(it) }.orEmpty()}\" from" +
                    " ${posted.packageName} is an ongoing notification (a music player, a call, a" +
                    " download), and those are the app's to take back rather than anyone else's:" +
                    " it was left alone"
            }
            if (!posted.isClearable) {
                return "the notification from ${posted.packageName} says it cannot be cleared, so" +
                    " it was left alone"
            }
            try {
                service.cancelNotification(key)
            } catch (error: Throwable) {
                return "the system refused to cancel it: ${error.message}"
            }
            // 清一条是"请系统去做", 不是"已经没了": 读回一次才说得清
            val gone = try {
                service.activeNotifications?.none { it.key == key } ?: false
            } catch (error: Throwable) {
                false
            }
            return if (gone) {
                "cancelled the notification from ${posted.packageName}"
            } else {
                "asked the system to cancel ${posted.packageName}'s notification and it is still" +
                    " there, so it did not go away"
            }
        }

        /** 一条通知能说出来的东西, 从 extras 与排行里取 */
        private fun describe(
            posted: StatusBarNotification,
            rankings: RankingMap?,
        ): Posted {
            val notification = posted.notification
            val extras = notification?.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (
                extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras?.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
                )
                ?.toString()
                .orEmpty()
            // 排行是系统给的, 而且要点名去取: `getRanking()` 在 SDK 里不是公开 API, 只有 RankingMap 是。
            // 取不到时重要度就是"没说", 而不是某个默认值
            val rank = Ranking()
            val known = try {
                rankings?.getRanking(posted.key, rank) == true
            } catch (error: Throwable) {
                false
            }
            return Posted(
                key = posted.key,
                packageName = posted.packageName,
                id = posted.id,
                tag = posted.tag,
                postedAt = posted.postTime,
                ongoing = posted.isOngoing,
                clearable = posted.isClearable,
                title = title.take(MAX_TEXT),
                text = text.take(MAX_TEXT),
                category = notification?.category,
                channel = if (known) rank.channel?.id else null,
                importance = if (known) rank.importance else NotificationManager.IMPORTANCE_UNSPECIFIED,
                group = notification?.group,
                summary = notification?.let { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
                    ?: false,
            )
        }

        /** 一条通知的标题, 给"清不了"那几句话用 */
        private fun title(notification: Notification): String =
            notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    }
}
