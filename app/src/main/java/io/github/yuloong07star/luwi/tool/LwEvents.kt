package io.github.yuloong07star.luwi.tool

import android.os.SystemClock
import io.github.yuloong07star.luwi.channel.Heard
import io.github.yuloong07star.luwi.channel.LwAccessibility
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.TimeUnit

/**
 * 事件订阅: 让模型"等到一件事发生", 而不是反复读屏
 *
 * 这一条把 agent 从**轮询**变成**被通知**: 按一下之后, 与其每 200ms 读一次无障碍树 (每次都要过 binder,
 * 而且读到的是同一个界面), 不如说"这块屏上有变化就告诉我"。事件由系统送进 `LwAccessibility` 那条有界
 * 队列 (掩码里就四个类型, 见 `res/xml/lw_accessibility.xml`), 这里做的是订阅、游标与回话
 *
 * 三个形状上的决定:
 *
 * - **订阅是一根游标, 不是一份拷贝**: [Watch] 只记"读到哪了"与三个过滤条件, 事件本身留在那条队列里。
 *   所以订阅再多也不涨内存, 而"我订阅之前刚发生了什么"这件事仍然读得到 (订阅那一步顺手把队列尾部
 *   回看几条)
 * - **等是轮询队列, 不是等一个回调**: 队列在服务那侧, 读它是纯内存操作; 150ms 一次的问法比让服务反向
 *   通知简单得多, 而 `notificationTimeout=100` 本来就把事件压到了每秒十条上下
 * - **游标只在答完之后才前移**: 拿到的事件要真的念给模型, 念不下的那些 (超过 limit) 也一起前移 ——
 *   否则下一次 wait 会把它们再念一遍
 *
 * 与别处一样的纪律: 没有变化就说"这段时间里没有变化", **不编一个"没事发生"之外的结论**; 队列满过就说
 * 丢了几条, 因为"我看到的事件"与"发生过的事件"是两件事
 */
internal object LwEvents {

    /** 事件类型就这四个词, 掩码里也是这四个 */
    private val KINDS = setOf("window", "content", "focus", "scroll")

    /** 隔多久看一眼队列 */
    private const val POLL_MS = 150L

    /** 订阅默认活多久, 以及最长能活多久 (忘了它的订阅会自己过期, 不留下永不消失的状态) */
    private const val DEFAULT_LIFE_MS = 10 * 60_000L
    private const val MAX_LIFE_MS = 30 * 60_000L

    /** 一次 wait 默认等多久, 以及上限 (压在桥那条读超时之下) */
    private const val DEFAULT_WAIT_MS = 15_000L
    private const val MAX_WAIT_MS = 90_000L

    /** 一次最多念几条 */
    private const val DEFAULT_LIMIT = 20
    private const val MAX_LIMIT = 100

    /** 订阅时回看队列尾部的几条 ("我订之前刚发生了什么") */
    private const val LOOK_BACK = 5

    /**
     * 一次订阅
     *
     * @property since 游标: 大于它的序号才是"新的"
     * @property lostAtStart 订阅那一刻队列已经丢了几条, 好让"丢了几条"是这一段时间的数, 不是历史总数
     * @property ephemeral 临时的 (一次 wait 建的), 答完就丢
     */
    private class Watch(
        val id: String,
        val displayId: Int?,
        val kinds: Set<String>?,
        val packageName: String?,
        val startedAt: Long,
        val expiresAt: Long,
        val lostAtStart: Long,
        val ephemeral: Boolean,
    ) {
        /** 读到哪了 */
        var since: Long = LwAccessibility.latest
    }

    private val watches = LinkedHashMap<String, Watch>()
    private var nextId = 1

    /** 起一个订阅 (op=start, 默认) */
    fun subscribe(request: JsonObject): JsonObject = when (val op = request.string("op", "start")) {
        "start" -> start(request)
        "stop" -> stop(request)
        "list" -> list()
        else -> throw IllegalArgumentException("op has to be start, stop or list, not \"$op\"")
    }

    private fun start(request: JsonObject): JsonObject {
        if (!LwAccessibility.running) {
            throw IllegalStateException(
                "the accessibility service is off, so the device reports no events at all: turn it" +
                    " on in Luwi's own Settings -> 无障碍, then run tools/lw-install.ps1 if the" +
                    " switch cannot write the setting",
            )
        }
        val displayId = request.intOrNull("displayId")
        val kinds = kinds(request.stringOrNull("kind"))
        val packageName = request.stringOrNull("package")
        val lifeMs = (request.longOrNull("lifeMs") ?: DEFAULT_LIFE_MS).coerceIn(1_000L, MAX_LIFE_MS)
        val watch = synchronized(watches) {
            sweep()
            Watch(
                id = "e${nextId++}",
                displayId = displayId,
                kinds = kinds,
                packageName = packageName,
                startedAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + lifeMs,
                lostAtStart = LwAccessibility.lost,
                ephemeral = false,
            ).also { watches[it.id] = it }
        }
        // 回看是"队列尾部的几条", 不是"序号往前数几条": 合并过的那些事件不占新序号, 按序号往前数会
        // 正好跳过刚刚那一场风暴
        val context = LwAccessibility.heard(
            since = 0L,
            displayId = displayId,
            kinds = kinds,
            packageName = packageName,
        ).takeLast(LOOK_BACK)
        // 订阅没有指定屏时, 每一行都要说清是哪块屏上的事 —— 那正是"另一块屏里切了页面"这个问题
        val wide = displayId == null
        return text(
            buildString {
                append("watching ${scope(watch)} (id ${watch.id}, expires in ${duration(lifeMs)})")
                append("\n")
                if (context.isEmpty()) {
                    append("nothing in that scope had happened before this subscription either")
                } else {
                    append("just before this subscription, in the same scope:\n")
                    append(context.joinToString("\n") { "  ${line(it, context.first().at, wide)}" })
                }
                append("\nthe device's own buffer holds ${LwAccessibility.buffered} of")
                append(" ${LwAccessibility.capacity} events")
                append(" (${LwAccessibility.lost} dropped so far in this session)")
                append("\ncall lw_events_wait with id ${watch.id} (or with the same filters and no id)")
                append(" to collect what happens next")
            },
        )
    }

    private fun stop(request: JsonObject): JsonObject {
        val id = request.string("id")
        val removed = synchronized(watches) { watches.remove(id) }
        return text(
            if (removed != null) {
                "stopped watching ${scope(removed)} (id $id)"
            } else {
                "there was no subscription called $id any more (it expired, or another call" +
                    " stopped it)"
            },
        )
    }

    private fun list(): JsonObject {
        val live = synchronized(watches) {
            sweep()
            // 临时订阅 (一次 wait 建的那个) 不算: 它没有可以给出的名字, 列出来只会让人去用一个用不了的 id
            watches.values.filterNot { it.ephemeral }
        }
        if (live.isEmpty()) {
            return text("no subscriptions are live: start one with lw_events_subscribe")
        }
        val now = System.currentTimeMillis()
        return text(
            "${live.size} subscription(s) live:\n" + live.joinToString("\n") { watch ->
                "  ${watch.id}  ${scope(watch)}  expires in ${duration(watch.expiresAt - now)}"
            },
        )
    }

    /**
     * 等一件事发生 (op 不在这条路上, 这是 `lw_events_wait`)
     *
     * @param request `id` 给就照那根游标接着看; 不给就按同样几个过滤条件建一个**临时的**, 答完丢掉 ——
     *   所以"就等下一次变化"是一次调用, 不必先订阅
     */
    fun wait(request: JsonObject): JsonObject {
        if (!LwAccessibility.running) {
            throw IllegalStateException(
                "the accessibility service is off, so the device reports no events at all: turn it" +
                    " on in Luwi's own Settings -> 无障碍",
            )
        }
        val timeoutMs = (request.longOrNull("timeoutMs") ?: DEFAULT_WAIT_MS).coerceIn(0L, MAX_WAIT_MS)
        val limit = (request.intOrNull("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val asked = request.stringOrNull("id")
        val watch = synchronized(watches) {
            sweep()
            val found = asked?.let { watches[it] }
            if (found != null) {
                found
            } else if (asked != null) {
                throw IllegalArgumentException(
                    "there is no subscription called $asked any more (it expired, or another call" +
                        " stopped it): start one with lw_events_subscribe, or drop the id and pass" +
                        " the filters to this call instead",
                )
            } else {
                Watch(
                    id = "e${nextId++}",
                    displayId = request.intOrNull("displayId"),
                    kinds = kinds(request.stringOrNull("kind")),
                    packageName = request.stringOrNull("package"),
                    startedAt = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + MAX_LIFE_MS,
                    lostAtStart = LwAccessibility.lost,
                    ephemeral = true,
                ).also { watches[it.id] = it }
            }
        }
        // 事件的时刻是 `SystemClock.uptimeMillis` 那一套 (事件自己带的), 所以这里的原点也是它 ——
        // 拿墙钟去减只会得到一个负数, 然后每一行都印 +0ms
        val started = SystemClock.uptimeMillis()
        var seen = LwAccessibility.heard(watch.since, watch.displayId, watch.kinds, watch.packageName)
        while (seen.isEmpty() && SystemClock.uptimeMillis() - started < timeoutMs) {
            Thread.sleep(POLL_MS)
            seen = LwAccessibility.heard(watch.since, watch.displayId, watch.kinds, watch.packageName)
        }
        val waited = (SystemClock.uptimeMillis() - started).coerceAtLeast(0)
        // 游标前移: 念不下的那些也算看过了, 否则下一次会把它们再念一遍
        val newest = seen.lastOrNull()?.sequence
        val now = System.currentTimeMillis()
        val live = synchronized(watches) {
            val found = watches[watch.id]
            if (newest != null) found?.since = newest
            // 一次性的、以及等到一半过期的, 都在这里收掉 —— 不留下一秒就要清的订阅
            if (found != null && (found.ephemeral || found.expiresAt <= now)) {
                watches.remove(found.id)
                null
            } else {
                found
            }
        }
        val shown = seen.takeLast(limit)
        val collapsed = seen.sumOf { it.count }
        val lost = LwAccessibility.lost - watch.lostAtStart
        // 每一行说的是"离第一条多久": 订阅与这次 wait 之间发生的事也会被念出来 (否则那段就瞎了),
        // 而它们的时刻在这次调用之前 —— 拿调用时刻当原点会让每一行都印 +0ms
        val origin = shown.firstOrNull()?.at ?: started
        val wide = watch.displayId == null
        return text(
            buildString {
                if (seen.isEmpty()) {
                    append("nothing matching happened in scope ${scope(watch)} in ${duration(waited)}")
                    append(": the screen did not change in that time. Read it with lw_ui if what you")
                    append(" need is what it shows now rather than what changed")
                } else {
                    append("${shown.size} event(s) in scope ${scope(watch)} after ${duration(waited)}")
                    append(" (${collapsed} changes collapsed into them, $lost dropped)")
                    append("\n")
                    append(shown.joinToString("\n") { "  ${line(it, origin, wide)}" })
                    if (seen.size > shown.size) {
                        append("\nthe first ${seen.size - shown.size} are not printed (limit $limit);")
                        append(" the cursor moved past them too")
                    }
                }
                append("\n")
                if (live == null) {
                    append("this was a one-off wait, or the subscription expired while waiting, so")
                    append(" there is nothing left watching: lw_events_subscribe starts another")
                } else {
                    append("id ${live.id} is still watching ${scope(live)}, expires in ")
                    append(duration(live.expiresAt - now))
                    append(" - call this again with that id to wait for the next thing")
                }
            },
        )
    }

    /** 过期的一次性清理, 每次访问订阅表时顺手做 */
    private fun sweep() {
        val now = System.currentTimeMillis()
        val gone = watches.values.filter { it.expiresAt <= now }.map { it.id }
        gone.forEach { watches.remove(it) }
    }

    /** `kind` 参数: 一个词或者 null (全要); 不认识的词当场拒, 免得静默地什么都不等 */
    private fun kinds(kind: String?): Set<String>? = when {
        kind == null -> null
        kind in KINDS -> setOf(kind)
        else -> throw IllegalArgumentException(
            "kind has to be one of ${KINDS.joinToString(", ")}, not \"$kind\"",
        )
    }

    /** 一个订阅在等什么, 说话的样子 */
    private fun scope(watch: Watch): String = buildString {
        append(watch.displayId?.let { "displayId $it" } ?: "any display")
        append(" · ")
        append(watch.kinds?.joinToString(",") ?: "window,content,focus,scroll")
        watch.packageName?.let { append(" · $it") }
    }

    /**
     * 一行事件: 发生在第几毫秒、哪一类、谁、什么文字
     *
     * @param showDisplay 订阅没有指定屏时把屏也念出来 —— 只订一块屏时它是废话, 而"任意屏"时不念就
     *   分不清哪一条是哪一块屏上的事, 那正是这一批要回答的问题
     */
    private fun line(event: Heard, origin: Long, showDisplay: Boolean = false): String = buildString {
        val offset = (event.at - origin).coerceAtLeast(0)
        append("+${duration(offset)}")
        append("  ")
        if (showDisplay) {
            append(if (event.displayId < 0) "display ?" else "display ${event.displayId}")
            append("  ")
        }
        append(event.kind.padEnd(7))
        append(" ")
        append(event.packageName.ifEmpty { "(no package)" })
        if (event.className.isNotEmpty()) append(" ${event.className.substringAfterLast('.')}")
        if (event.text.isNotEmpty()) append("  \"${event.text}\"")
        if (event.fullScreen) append("  (full screen)")
        // 滚动量只在滚动事件上说话: content 事件上那个字段是 -1, 印出来就是噪音
        if (event.kind == "scroll" && event.scrollDeltaY != 0) append("  scrolled ${event.scrollDeltaY}")
        if (event.count > 1) append("  (x${event.count})")
    }

    /** 时长说成人话: 120ms / 1.4s / 2m30s */
    private fun duration(ms: Long): String = when {
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> "%.1fs".format(ms / 1000.0)
        else -> {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
            val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
            "${minutes}m${seconds}s"
        }
    }

    /** 一个整数参数, 没有就是 null */
    private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.longOrNull?.toInt()

    /** 一个长整数参数, 没有就是 null */
    private fun JsonObject.longOrNull(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
}
