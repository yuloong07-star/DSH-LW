package io.github.yuloong07star.luwi.channel

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * One node of a screen, as much of it as a caller can use
 *
 * Only nodes that say something or do something are kept at all: a tree is mostly layout
 * containers, and a caller reading every one of them learns less than it pays for
 */
data class UiNode(
    val depth: Int,
    val className: String,
    val text: String,
    val description: String,
    val viewId: String,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val bounds: Rect,
    /** Where a tap has to land for this node to be the thing that gets pressed, null when nothing at or above it is clickable */
    val target: Rect?,
)

/** What one screen answered about itself */
data class UiTree(
    val packageName: String,
    val nodes: List<UiNode>,
    val truncated: Boolean,
    val error: String?,
)

/** What asking for a node by name did, with the candidates when the name was not one thing */
data class UiTap(
    val outcome: String,
    val matches: List<UiNode>,
    val via: String,
    val target: Rect?,
    val error: String?,
)

/**
 * What typing into a screen produced
 *
 * @property outcome `typed`, `ambiguous` (more than one field and none of them focused), `none`
 *   (nothing on that screen takes text), `unavailable` or `failed`
 * @property field the node the text went into, when one was found
 * @property matches the fields to choose from when the screen showed several
 * @property text what the field reads afterwards, empty for a field that hides what is in it
 * @property password whether the field hides what is in it
 * @property error why nothing was typed, null when something was
 */
data class UiTyping(
    val outcome: String,
    val field: UiNode?,
    val matches: List<UiNode>,
    val text: String,
    val password: Boolean,
    val error: String?,
)

/**
 * 一次滚动做了什么
 *
 * @property outcome `scrolled`, `refused` (控件拒绝了动作, 多半已经到顶或到底), `none` (这一屏没有可滚动的
 *   东西), `unavailable`
 * @property scrolled 真正滚了几屏
 * @property node 滚的是哪一个控件
 * @property error 没滚成的原因, 滚成了是 null
 */
data class UiScroll(
    val outcome: String,
    val scrolled: Int,
    val node: UiNode?,
    val error: String?,
)
/**
 * 一件事发生过的痕迹, 收成一行
 *
 * `windowId` 是事件自带的, 而**事件上没有 displayId** —— 那个只能拿 windowId 去窗口表里反查, 所以这里
 * 两个都留着: 记的时候顺手反查一次 (有缓存), 反查不到就是 -1, 而不是猜一个 0
 *
 * @property sequence 单调递增的序号, 订阅就是拿它当游标的
 * @property kind 四个词之一: `window` / `content` / `focus` / `scroll`
 * @property displayId 这块窗口在哪块屏上, -1 表示没查出来 (窗口可能已经关了)
 * @property text 事件带的文字 (窗口标题、被聚焦字段的文本或描述), 截断过
 * @property at `SystemClock.uptimeMillis` 那一套的时刻, 与 `System.currentTimeMillis` 不同源
 */
data class Heard(
    val sequence: Long,
    val kind: String,
    var displayId: Int,
    val windowId: Int,
    val packageName: String,
    val className: String,
    val text: String,
    val at: Long,
    val fullScreen: Boolean,
    val scrollDeltaY: Int,
) {
    /** 这一行代表几条 (同一类事件连着来时合并, 所以是可变的) */
    var count: Int = 1
}

/**
 * What the device says is on a screen, read through an accessibility service
 *
 * This is the one part of the channel that runs in the app's own process: the service is declared
 * in this app's manifest, so the system delivers its callbacks here, and reading a tree needs
 * neither a binder nor the privileged process - only injecting touch does
 *
 * What it is for: text, and coordinates in the screen's own pixels. A caller that can name a thing
 * does not have to measure it off a picture that was scaled to fit a token budget, which is where
 * the error in the picture-first approach comes from - and naming it can also be acted on directly,
 * through the node's own click action, without a coordinate ever being guessed
 *
 * The service reads the whole device, which is not a property of this class but of what an
 * accessibility service is, so it stays opt in and every caller here is made to name the one screen
 * it means
 */
class LwAccessibility : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        val windows = try {
            windowsOnAllDisplays
        } catch (error: Throwable) {
            null
        }
        val displays = windows?.let { (0 until it.size()).map { index -> it.keyAt(index) } }
        Log.i(TAG, "connected, displays $displays")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        // 事件不再只写日志: 它进那条有界队列, 由 `lw_events_wait` 取走 (批次 6)。掩码里就那四个类型
        // (见 res/xml/lw_accessibility.xml), 别的系统也不会送过来
        record(event, type)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "unbound")
        return super.onUnbind(intent)
    }

    companion object {

        private const val TAG = "LwA11y"

        /** A tree can be a WebView or a long list, so both of these are hard ceilings */
        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 40

        /** 整棵树落文件时用的两条: 缩进最多这么深, 一段文字最多留这么长 */
        private const val MAX_DUMP_DEPTH = 24
        private const val MAX_DUMP_TEXT = 120

        /** How far up from a node to look for the thing that actually takes the click */
        private const val MAX_CLICKABLE_STEPS = 12

        /** How many candidates an ambiguous name is worth listing */
        private const val MAX_CANDIDATES = 12
        /** 一次调用最多连着滚几屏, 免得一个笔误把长列表拖到底 */
        private const val MAX_SCROLL_TIMES = 10

        /** The system owns the service's lifetime, so this is the app's only handle on it */
        @Volatile
        private var instance: LwAccessibility? = null

        /** 进程内的事件回调: 自动指令那条链要"事件一到就叫一声", 而不是每 150 ms 读一次队列 */
        private val watchers = java.util.concurrent.CopyOnWriteArrayList<WindowWatcher>()

        /** 窗口变了 (通知栏之外最常被问到的那件事: 现在是哪个应用在前台) */
        fun interface WindowWatcher {
            fun onWindow(packageName: String, kind: String)
        }

        /**
         * 挂一个回调, 返回的那个东西用来摘掉它
         *
         * 回调在主线程上跑 (事件就是送到那里的), 所以里面只做"转手": 引擎把它丢进自己的工作线程,
         * 判定与写文件都不在无障碍这条链上做
         */
        fun watchWindows(watcher: WindowWatcher): AutoCloseable {
            watchers += watcher
            return AutoCloseable { watchers.remove(watcher) }
        }

        /** 此刻挂了几个回调 (排查用) */
        val watcherCount: Int get() = watchers.size

        /** Whether the device has the service on, which is the first thing a caller has to be told */
        val running: Boolean get() = instance != null

        /**
         * 一件事发生过的痕迹, 收成一行
         *
         * 类型声明在文件顶层 (与 `UiNode` 那些一样), 因为 companion 里嵌套的类外面要写成
         * `LwAccessibility.Companion.Heard` 才引用得到
         */

        /** 队列上限: 一次风暴之后模型要的是"最近发生了什么", 不是全部 */
        private const val MAX_HEARD = 200

        /** 同一类事件在这个窗口里连着来就合并成一行 (滚动时的 content / scroll 尤其密) */
        private const val COALESCE_MS = 400L

        /** 一行里留多少文字 */
        private const val MAX_HEARD_TEXT = 80

        /** windowId -> displayId 的缓存多久重读一次 (读窗口表要过 binder, 不能每个事件都读) */
        private const val WINDOW_CACHE_MS = 1_000L

        /** 查不到时的重试间隔: 比上面那一秒短得多, 因为查不到的往往正是刚出现的窗口 */
        private const val WINDOW_MISS_MS = 200L

        private val buffer = ArrayDeque<Heard>()
        private val bufferLock = Any()
        private var sequence = 0L
        private var dropped = 0L
        private val windowDisplays = HashMap<Int, Int>()
        private var windowsReadAt = 0L
        private var windowsMissedAt = 0L

        /** 事件类型收成四个词; 掩码外的类型不记 */
        private fun kindOf(type: Int): String? = when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "focus"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scroll"
            else -> null
        }

        /** 收一条事件 */
        private fun record(event: AccessibilityEvent, type: Int) {
            val kind = kindOf(type) ?: return
            val windowId = event.windowId
            val displayId = displayOf(windowId)
            val text = (event.text?.firstOrNull() ?: event.contentDescription)
                ?.toString()
                ?.take(MAX_HEARD_TEXT)
                .orEmpty()
            val packageName = event.packageName?.toString().orEmpty()
            val className = event.className?.toString().orEmpty()
            val at = event.eventTime
            synchronized(bufferLock) {
                sequence++
                val last = buffer.lastOrNull()
                val sameAsLast = last != null &&
                    last.kind == kind &&
                    last.windowId == windowId &&
                    last.packageName == packageName &&
                    last.className == className &&
                    at - last.at < COALESCE_MS
                if (sameAsLast) {
                    last!!.count++
                } else {
                    buffer.addLast(
                        Heard(
                            sequence = sequence,
                            kind = kind,
                            displayId = displayId,
                            windowId = windowId,
                            packageName = packageName,
                            className = className,
                            text = text,
                            at = at,
                            fullScreen = event.isFullScreen,
                            scrollDeltaY = try {
                                event.scrollDeltaY
                            } catch (error: Throwable) {
                                0
                            },
                        ),
                    )
                    while (buffer.size > MAX_HEARD) {
                        buffer.removeFirst()
                        dropped++
                    }
                }
            }
            // 进程内的那声"窗口变了" (自动指令那条链): 放在锁外面, 回调再慢也不该占着队列
            if (kind == "window" && packageName.isNotEmpty() && watchers.isNotEmpty()) {
                watchers.forEach { watcher ->
                    try {
                        watcher.onWindow(packageName, kind)
                    } catch (error: Throwable) {
                        Log.w(TAG, "a window watcher failed", error)
                    }
                }
            }
        }

        /**
         * 这个窗口在哪块屏上
         *
         * 事件本身不带 displayId, 所以拿 windowId 去 `windowsOnAllDisplays` 里反查, 结果缓存一秒 —— 读
         * 窗口表要过 binder, 而事件一秒能来十几条
         *
         * **查不到时不走那一秒**: 缺的往往正是**刚刚出现的那个窗口**, 而"某个应用起来了"那一条事件
         * 恰恰是最要紧的一条 (实测: 启动时那几条全报 display unknown)。所以没命中就用一个短得多的
         * 间隔重试, 只是防着"每来一条都读一次窗口表"
         *
         * 反查不到就是 -1: 窗口可能已经关了, 那时"不知道"比猜一个 0 (那是别人的手机) 安全
         */
        private fun displayOf(windowId: Int): Int {
            synchronized(bufferLock) { windowDisplays[windowId]?.let { return it } }
            val now = SystemClock.uptimeMillis()
            val due = now - windowsReadAt > WINDOW_CACHE_MS || now - windowsMissedAt > WINDOW_MISS_MS
            if (!due) return -1
            val windows = try {
                instance?.windowsOnAllDisplays
            } catch (error: Throwable) {
                null
            }
            if (windows == null) {
                windowsMissedAt = now
                return -1
            }
            val fresh = HashMap<Int, Int>()
            for (index in 0 until windows.size()) {
                val display = windows.keyAt(index)
                for (window in windows.valueAt(index)) fresh[window.id] = display
            }
            synchronized(bufferLock) {
                windowDisplays.clear()
                windowDisplays.putAll(fresh)
            }
            windowsReadAt = now
            windowsMissedAt = now
            return synchronized(bufferLock) { windowDisplays[windowId] } ?: -1
        }

        /**
         * [since] 之后的事件, 最早的在前面
         *
         * 三个过滤都是"给了才筛" (null = 不过滤), 这样"等任何一块屏上的任何变化"与"只等那块屏的滚动"
         * 是同一条路
         *
         * @param since 游标, 一般是 [latest] 或者上一次读到的序号
         * @param displayId 只要这块屏上的, null 表示不限
         * @param kinds 只要这几种 (四个词), null 表示不限
         * @param packageName 只要这个应用发的, null 表示不限
         */
        fun heard(
            since: Long,
            displayId: Int? = null,
            kinds: Set<String>? = null,
            packageName: String? = null,
        ): List<Heard> = synchronized(bufferLock) {
            buffer.filter { event ->
                event.sequence > since &&
                    (displayId == null || event.displayId == displayId) &&
                    (kinds == null || event.kind in kinds) &&
                    (packageName == null || event.packageName == packageName)
            }
        }.map { event ->
            // 记的时候没反查出屏的, 读的时候再试一次: 那一刻窗口表里可能还没有它 (刚起来), 而现在有了
            if (event.displayId < 0) {
                val fresh = displayOf(event.windowId)
                if (fresh >= 0) event.displayId = fresh
            }
            event
        }

        /** 现在的游标: 拿它当"从现在开始看"的起点 */
        val latest: Long get() = synchronized(bufferLock) { sequence }

        /** 到现在为止因为队列满丢掉了几条 */
        val lost: Long get() = synchronized(bufferLock) { dropped }

        /**
         * 队列里现在有几条, 以及它最多能装几条
         *
         * 两个都露出来是为了让"有界"这件事**看得见**: 一个数字一直涨到上限就不再涨, 而那之后
         * [lost] 开始走 —— 这两件事合起来才是"队列不会无限涨"的证据, 而不是一句注释
         */
        val buffered: Int get() = synchronized(bufferLock) { buffer.size }
        val capacity: Int get() = MAX_HEARD

        /**
         * Everything one screen contains that is worth saying
         *
         * The bounds that come back are the screen's own pixels, which is not obvious: an
         * accessibility node is reported in the coordinates of the display its window is on, and
         * this was checked against a picture of the same screen before anything was built on it
         */
        fun tree(displayId: Int): UiTree {
            if (instance == null) return UiTree("", emptyList(), false, NOT_ENABLED)
            val window = windowOn(displayId)
                ?: return UiTree("", emptyList(), false, "no window is on display $displayId")
            val root = window.root
                ?: return UiTree("", emptyList(), false, "display $displayId has no readable window content")
            // A tree is a snapshot from the moment the window was asked; refreshing asks the app
            // that owns it what it looks like now, which is what makes two calls in a row agree
            try {
                root.refresh()
            } catch (error: Throwable) {
                Log.d(TAG, "could not refresh the tree", error)
            }
            val gathered = gather(root)
            return UiTree(
                packageName = root.packageName?.toString().orEmpty(),
                nodes = gathered.list.map { it.ui },
                truncated = gathered.truncated,
                error = null,
            )
        }

        /**
         * Act on the node one screen calls by that name
         *
         * The matching is deliberately narrow to wide - the same text, then the same description,
         * then a text that contains it, then a description that does: a caller that says exactly
         * what it read off the tree gets exactly that node, and a caller that says part of a name
         * still lands somewhere sane
         *
         * Several nodes can share one row (a title and the summary beside it are two nodes with one
         * click target), so candidates that would all press the same thing count as one match. When
         * the name really does reach more than one thing, nothing is pressed and the candidates go
         * back: guessing which button a caller meant is how a screen ends up somewhere nobody asked
         *
         * @param holdMs how long the press is meant to be held. The platform's long click is its own
         *   action, so a hold past its threshold asks for that action on the closest ancestor which
         *   takes one - and a press held longer than a long click is not something an accessibility
         *   action can express, which is what the caller's finger fallback is for
         */
        fun tap(displayId: Int, name: String, holdMs: Long = 0L): UiTap {
            if (instance == null) return UiTap("unavailable", emptyList(), "", null, NOT_ENABLED)
            if (name.isBlank()) return UiTap("none", emptyList(), "", null, "no name was given")
            val window = windowOn(displayId)
                ?: return UiTap("none", emptyList(), "", null, "no window is on display $displayId")
            val root = window.root
                ?: return UiTap("none", emptyList(), "", null, "display $displayId has no readable window content")
            val gathered = gather(root)
            val matches = match(gathered, name)
            if (matches.isEmpty()) {
                return UiTap("none", emptyList(), "", null, "nothing on display $displayId says \"$name\"")
            }
            val distinct = matches.distinctBy { keyOf(it) }
            if (distinct.size > 1) {
                return UiTap("ambiguous", distinct.take(MAX_CANDIDATES).map { it.ui }, "", null, null)
            }
            val hit = distinct.first()
            val long = holdMs >= LwServiceProtocol.LONG_PRESS_MS
            val target = (if (long) longClickTarget(hit.node) else null) ?: hit.target ?: hit.node
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            val action = if (long) {
                AccessibilityNodeInfo.ACTION_LONG_CLICK
            } else {
                AccessibilityNodeInfo.ACTION_CLICK
            }
            val clicked = try {
                target.performAction(action)
            } catch (error: Throwable) {
                return UiTap("none", listOf(hit.ui), "", bounds, "the click action failed: ${error.message}")
            }
            if (!clicked) {
                // Some views are drawn by hand and take a touch but not an accessibility action; the
                // caller is told which, and where, so it can fall back to a finger
                return UiTap("unclicked", listOf(hit.ui), "", bounds, null)
            }
            return UiTap("clicked", listOf(hit.ui), if (target === hit.node) "self" else "ancestor", bounds, null)
        }

        /**
         * 滚一屏可滚动的内容, 走无障碍自己的滚动动作
         *
         * 平台把"再往下看一屏"交给了控件自己 (`ACTION_SCROLL_FORWARD`), 所以列表、表单、滚动容器都不必
         * 先知道手指该从哪儿划到哪儿 —— 比注入的拖动既准又稳, 也不受"注入的 MOVE 到不了某些屏"那条限制
         *
         * @param forward 往后看一屏 (内容往上走); false 是往前回一屏
         * @param name 从哪儿滚: 给了名字就用那个控件所在的可滚动容器, 不给就用这一屏上最大的那块可滚动区域
         * @param times 连着滚几屏, 上限 [MAX_SCROLL_TIMES]
         */
        fun scroll(displayId: Int, forward: Boolean, name: String?, times: Int): UiScroll {
            if (instance == null) return UiScroll("unavailable", 0, null, NOT_ENABLED)
            val window = windowOn(displayId)
                ?: return UiScroll("none", 0, null, "no window is on display $displayId")
            val root = window.root
                ?: return UiScroll("none", 0, null, "display $displayId has no readable window content")
            try {
                root.refresh()
            } catch (error: Throwable) {
                Log.d(TAG, "could not refresh the tree", error)
            }
            val gathered = gather(root)
            val wanted = name?.takeIf { it.isNotBlank() }
            var chosen: Pair<AccessibilityNodeInfo, UiNode>? = null
            if (wanted == null) {
                chosen = gathered.list
                    .filter { it.ui.scrollable }
                    .maxByOrNull { areaOf(it.ui.bounds) }
                    ?.let { it.node to it.ui }
            } else {
                for (found in match(gathered, wanted)) {
                    val node = scrollTarget(found.node)
                    if (node != null) {
                        chosen = node to found.ui
                        break
                    }
                }
            }
            val target = chosen
                ?: return UiScroll(
                    "none",
                    0,
                    null,
                    if (wanted == null) {
                        "nothing on display $displayId scrolls"
                    } else {
                        "nothing scrollable around the named control on display $displayId"
                    },
                )
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            var done = 0
            for (step in 1..times.coerceIn(1, MAX_SCROLL_TIMES)) {
                val took = try {
                    target.first.performAction(action)
                } catch (error: Throwable) {
                    Log.w(TAG, "could not scroll", error)
                    false
                }
                if (!took) break
                done++
            }
            return UiScroll(
                outcome = if (done > 0) "scrolled" else "refused",
                scrolled = done,
                node = target.second,
                error = if (done > 0) {
                    null
                } else {
                    "the scrollable control refused the scroll action, which is what reaching its end looks like"
                },
            )
        }
        /**
         * Put text into the field one screen is showing
         *
         * The field is found rather than guessed at: the focused one when the app has put focus in
         * one, otherwise the only editable thing on the screen, otherwise nothing is typed and the
         * candidates come back - the same judgement [tap] makes about a name that reaches more than
         * one control
         *
         * Setting the text is the platform's own action rather than a stream of key presses, which
         * is what makes this work for what no keyboard can produce (Chinese, an emoji) and for a
         * screen with no IME in front of it: the text lands in the field while the phone is showing
         * something else entirely
         */
        fun type(displayId: Int, input: String, replace: Boolean): UiTyping {
            if (instance == null) {
                return UiTyping("unavailable", null, emptyList(), "", false, NOT_ENABLED)
            }
            if (input.isEmpty()) {
                return UiTyping("none", null, emptyList(), "", false, "no text was given")
            }
            val window = windowOn(displayId) ?: return UiTyping(
                "none", null, emptyList(), "", false, "no window is on display $displayId",
            )
            val root = window.root ?: return UiTyping(
                "none", null, emptyList(), "", false, "display $displayId has no readable window content",
            )
            val fields = gather(root).list.filter { it.ui.editable }
            if (fields.isEmpty()) {
                return UiTyping("none", null, emptyList(), "", false, "nothing on display $displayId takes text")
            }
            val target = fields.firstOrNull { it.node.isFocused }
                ?: fields.singleOrNull()
                ?: return UiTyping(
                    "ambiguous", null, fields.take(MAX_CANDIDATES).map { it.ui }, "", false, null,
                )
            val current = try {
                target.node.text?.toString().orEmpty()
            } catch (error: Throwable) {
                ""
            }
            val wanted = if (replace) input else insert(current, target.node, input)
            val request = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, wanted)
            }
            val accepted = try {
                target.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, request)
            } catch (error: Throwable) {
                return UiTyping(
                    "failed", target.ui, emptyList(), "", false, "the field refused the text: ${error.message}",
                )
            }
            if (!accepted) {
                return UiTyping("failed", target.ui, emptyList(), "", false, "the field did not take the text")
            }
            // The cursor goes after what was just written, because that is where a person's cursor
            // would be and so where the next thing typed belongs
            moveCursor(target.node, wanted.length)
            val hidden = target.node.isPassword
            return UiTyping("typed", target.ui, emptyList(), if (hidden) "" else readBack(target.node, wanted), hidden, null)
        }

        /** Where typing goes: the cursor, when the field says where it is, otherwise the end */
        private fun insert(current: String, node: AccessibilityNodeInfo, input: String): String {
            val at = try {
                val start = node.textSelectionStart
                val end = node.textSelectionEnd
                if (start in 0..end && end <= current.length) end else current.length
            } catch (error: Throwable) {
                current.length
            }
            return current.substring(0, at) + input + current.substring(at)
        }

        /**
         * Put the cursor after the text
         *
         * A field may refuse this and none of them need it: the text is already in place, and a
         * cursor left where it was only decides where the next character goes
         */
        private fun moveCursor(node: AccessibilityNodeInfo, at: Int) {
            val request = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, at)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, at)
            }
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, request) }
        }

        /** What the field says now, which is not always what was written into it */
        private fun readBack(node: AccessibilityNodeInfo, fallback: String): String = try {
            node.refresh()
            node.text?.toString() ?: fallback
        } catch (error: Throwable) {
            fallback
        }

        /**
         * 这块屏上现在有没有窗口
         *
         * `lw_launch` 之后用它自查, 因为 `am start` 报成功不等于窗口落在了这一块屏上: 应用已经在跑
         * 的时候, 系统把 intent 交给的是那个正在跑的实例, 而它的窗口可能在别处 —— 于是"启动了"与
         * "这块屏上一片空白"同时成立, 而这一层以前要模型自己想到
         */
        fun hasWindow(displayId: Int): Boolean = windowOn(displayId) != null

        /**
         * 这块屏上现在有没有能打字的字段
         *
         * "先按一下那个点、再打字"那条路要用它: 按下去常常是**打开一个新页面** (设置里的搜索框就是),
         * 而那个页面的字段要等它画出来才在树里。不等这一下就会退回按键 —— 按键落进空气里, 而答案
         * 只会说"打了几个键"
         */
        fun hasEditable(displayId: Int): Boolean {
            val window = windowOn(displayId) ?: return false
            val root = window.root ?: return false
            return try {
                gather(root).list.any { it.ui.editable }
            } catch (error: Throwable) {
                Log.d(TAG, "could not look for an editable field on display $displayId", error)
                false
            }
        }

        /**
         * 这块屏上现在画着的是哪个应用
         *
         * `lw_take_photo` 用它分辨"相机真的开了"与"这次启动被系统丢掉了": 后者不抛异常、不报错,
         * 屏上还是原来那个界面, 而调用方会以为相机正开着等人按快门 —— 同一类静默失败在这个仓库里
         * 已经踩过两次 (熄屏的截图、被丢掉的 secure settings 写入)
         *
         * @return 包名。null 有两种意思 (服务没开, 或者这块屏上没有应用窗口), 用 [running] 分开
         */
        fun packageOn(displayId: Int): String? = try {
            windowOn(displayId)?.root?.packageName?.toString()
        } catch (error: Throwable) {
            Log.d(TAG, "could not read the package on display $displayId", error)
            null
        }

        /** The window a caller means, preferring the one an app is actually showing there */
        private fun windowOn(displayId: Int): AccessibilityWindowInfo? {
            val all = try {
                instance?.windowsOnAllDisplays
            } catch (error: Throwable) {
                Log.w(TAG, "could not read the windows", error)
                null
            } ?: return null
            val onDisplay = (0 until all.size())
                .flatMap { all.valueAt(it) }
                .filter { it.displayId == displayId }
            return onDisplay.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                ?: onDisplay.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?: onDisplay.firstOrNull()
        }

        /**
         * 屏幕上叫这个名字的控件现在有没有, 有的话把它连坐标一起交出来
         *
         * 这是 `lw_wait_for` 用的那一半: 匹配与 [tap] 是同一套 (从窄到宽), 所以"等的东西"与"点的东西"
         * 不会是两个不同的东西。它**不动手**, 只回答
         *
         * @returns 找到的候选 (与 [tap] 一样按"按下会按到谁"去过重)。空列表就是还没有
         */
        fun find(displayId: Int, name: String): Pair<List<UiNode>, String?> {
            if (instance == null) return emptyList<UiNode>() to NOT_ENABLED
            if (name.isBlank()) return emptyList<UiNode>() to "no name was given"
            val window = windowOn(displayId)
                ?: return emptyList<UiNode>() to "no window is on display $displayId"
            val root = window.root
                ?: return emptyList<UiNode>() to "display $displayId has no readable window content"
            return match(gather(root), name).distinctBy { keyOf(it) }
                .take(MAX_CANDIDATES)
                .map { it.ui } to null
        }

        /**
         * 整棵树, 一个节点不落
         *
         * 与 [tree] 是两件事: 那个只留"值得说的"节点 (有文字或能动), 是给模型快速看一眼用的; 这个把
         * 布局容器也写下来, 是给"为什么会这样"这类问题用的 —— 有时候答案正好在一个不说话的容器上
         */
        fun dump(displayId: Int, limit: Int): UiDump {
            if (instance == null) return UiDump("", 0, true, NOT_ENABLED)
            val window = windowOn(displayId)
                ?: return UiDump("", 0, false, "no window is on display $displayId")
            val root = window.root
                ?: return UiDump("", 0, false, "display $displayId has no readable window content")
            val builder = StringBuilder()
            var count = 0
            var stopped = false

            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (count >= limit) {
                    stopped = true
                    return
                }
                count++
                val bounds = Rect()
                runCatching { node.getBoundsInScreen(bounds) }
                builder.append("  ".repeat(depth.coerceAtMost(MAX_DUMP_DEPTH)))
                builder.append(node.className?.toString()?.substringAfterLast('.').orEmpty())
                node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }?.let {
                    builder.append('#').append(it)
                }
                builder.append(" [").append(bounds.toShortString())
                if (node.isClickable) builder.append(" click")
                if (node.isScrollable) builder.append(" scroll")
                if (node.isEditable) builder.append(" edit")
                if (node.isCheckable) builder.append(if (node.isChecked) " checked" else " unchecked")
                builder.append(']')
                val text = node.text?.toString()?.trim().orEmpty()
                val description = node.contentDescription?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) builder.append(" text=\"").append(text.take(MAX_DUMP_TEXT)).append('"')
                if (description.isNotEmpty()) {
                    builder.append(" desc=\"").append(description.take(MAX_DUMP_TEXT)).append('"')
                }
                builder.append('\n')
                val children = runCatching { node.childCount }.getOrDefault(0)
                for (index in 0 until children) {
                    val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                    visit(child, depth + 1)
                }
            }

            visit(root, 0)
            return UiDump(builder.toString(), count, stopped, null)
        }

        /** [dump] 的结果 */
        data class UiDump(
            val text: String,
            val nodes: Int,
            val truncated: Boolean,
            val error: String?,
        )

        /** One node, the record a caller reads, and the thing that takes a click for it */
        private class Found(
            val node: AccessibilityNodeInfo,
            val ui: UiNode,
            val target: AccessibilityNodeInfo?,
        )

        /** What a walk of one tree produced */
        private class Gathered(val list: List<Found>, val truncated: Boolean)

        private fun gather(root: AccessibilityNodeInfo): Gathered {
            val found = mutableListOf<Found>()
            var truncated = false

            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (found.size >= MAX_NODES || depth > MAX_DEPTH) {
                    truncated = true
                    return
                }
                val record = describe(node, depth)
                if (record != null) {
                    val target = clickTarget(node)
                    val targetBounds = target?.let { clickable ->
                        Rect().also { clickable.getBoundsInScreen(it) }
                    }
                    found += Found(node, record.copy(target = targetBounds), target)
                }
                val children = try {
                    node.childCount
                } catch (error: Throwable) {
                    return
                }
                for (index in 0 until children) {
                    val child = try {
                        node.getChild(index)
                    } catch (error: Throwable) {
                        null
                    } ?: continue
                    visit(child, depth + 1)
                }
            }

            visit(root, 0)
            return Gathered(found, truncated)
        }

        /** What one node is, or null when it is only a container with nothing to say */
        private fun describe(node: AccessibilityNodeInfo, depth: Int): UiNode? {
            val bounds = Rect()
            return try {
                node.getBoundsInScreen(bounds)
                val record = UiNode(
                    depth = depth,
                    className = node.className?.toString()?.substringAfterLast('.').orEmpty(),
                    text = node.text?.toString()?.trim().orEmpty(),
                    description = node.contentDescription?.toString()?.trim().orEmpty(),
                    viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty(),
                    clickable = node.isClickable,
                    scrollable = node.isScrollable,
                    editable = node.isEditable,
                    checkable = node.isCheckable,
                    checked = node.isChecked,
                    bounds = Rect(bounds),
                    target = null,
                )
                val interesting = record.text.isNotEmpty() ||
                    record.description.isNotEmpty() ||
                    record.clickable ||
                    record.scrollable ||
                    record.editable ||
                    record.checkable
                if (interesting) record else null
            } catch (error: Throwable) {
                null
            }
        }

        /** The node itself when it takes a click, else the closest ancestor that does */
        private fun clickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo? = node
            var steps = 0
            while (current != null && steps < MAX_CLICKABLE_STEPS) {
                if (current.isClickable) return current
                current = try {
                    current.parent
                } catch (error: Throwable) {
                    null
                }
                steps++
            }
            return null
        }

        /** The node itself when it takes a long click, else the closest ancestor that does */
        private fun longClickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo? = node
            var steps = 0
            while (current != null && steps < MAX_CLICKABLE_STEPS) {
                if (current.isLongClickable) return current
                current = try {
                    current.parent
                } catch (error: Throwable) {
                    null
                }
                steps++
            }
            return null
        }

        /** 一块矩形的面积, 用来在一屏上认出最大的那块可滚动区域 */
        private fun areaOf(rect: Rect): Int = rect.width().coerceAtLeast(0) * rect.height().coerceAtLeast(0)

        /** The node itself when it scrolls, else the closest ancestor that does */
        private fun scrollTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo? = node
            var steps = 0
            while (current != null && steps < MAX_CLICKABLE_STEPS) {
                if (current.isScrollable) return current
                current = try {
                    current.parent
                } catch (error: Throwable) {
                    null
                }
                steps++
            }
            return null
        }
        /** The nodes a name reaches, through successively looser readings of that name */
        private fun match(gathered: Gathered, name: String): List<Found> {
            val list = gathered.list
            val passes: List<(Found) -> Boolean> = listOf(
                { it.ui.text.equals(name, ignoreCase = true) },
                { it.ui.description.equals(name, ignoreCase = true) },
                { it.ui.text.contains(name, ignoreCase = true) },
                { it.ui.description.contains(name, ignoreCase = true) },
            )
            passes.forEach { pass ->
                val hits = list.filter(pass)
                if (hits.isNotEmpty()) return hits
            }
            return emptyList()
        }

        /** What makes two candidates the same press rather than two different ones */
        private fun keyOf(found: Found): String {
            val rect = found.ui.target ?: found.ui.bounds
            return "${found.target?.className}|${rect.left},${rect.top},${rect.right},${rect.bottom}"
        }

        /** Said the same way everywhere, because every caller runs into it */
        private const val NOT_ENABLED =
            "the accessibility service is off, so the device will not report what is on a screen"
    }
}
