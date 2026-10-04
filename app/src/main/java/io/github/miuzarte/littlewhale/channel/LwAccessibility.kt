package io.github.miuzarte.littlewhale.channel

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
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
        // Only at debug level: this fires constantly and nothing here consumes it yet
        Log.d(TAG, "event ${AccessibilityEvent.eventTypeToString(type)} ${event.packageName}")
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

        /** Whether the device has the service on, which is the first thing a caller has to be told */
        val running: Boolean get() = instance != null

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
