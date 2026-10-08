package io.github.miuzarte.littlewhale.channel

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Touch, aimed at a screen
 *
 * Both halves of this are privileged: a screen of ours lives in this process and an injected event
 * has to name it, and INJECT_EVENTS is a permission the app does not hold. The coordinates are the
 * screen's own, so everything above this - a finger on the preview, a tool call - only has to
 * scale its own box into the screen and never has to know how the screen is built
 *
 * A gesture is a sequence rather than one call, because the platform matches the moves to the
 * press that started them by their down time: the press is remembered here and reused until the
 * finger is lifted
 *
 * **2026-10-08: 那道"真手指一来就把手势掐断"的软停删掉了** (主人点名): 手势一旦开始就跑到底, 中途
 * 不再问玻璃上有没有人. 现在主屏与虚拟屏在这里完全一样, 没有人能在这条路上把它打断
 */
class LwInput(private val context: Context?) {

    private var manager: InputManager? = null
    private var setDisplayIdMethod: Method? = null
    private var injectMethod: Method? = null

    /** What this device's virtual keyboard can produce, which is what typing by key is limited to */
    private var keyboard: KeyCharacterMap? = null

    /** The press a move or a lift belongs to, absent while no finger is down */
    private var downTime = 0L
    private var down = false

    /**
     * Press and lift at one point, holding it down for as long as the caller asked
     *
     * The hold is the whole of what a long press is: the platform's own detector fires once the
     * finger has been down for its threshold, and everything past that is "kept down" - which is
     * what a voice message being recorded or a finger waiting for a drag to start needs. Nothing is
     * special cased for a long hold, because the platform already reads the time
     *
     * The hold is one wait: 没有软停之后它不再需要被切片去听有没有真手指 (那正是切片唯一的用处)
     */
    fun tap(displayId: Int, x: Float, y: Float, holdMs: Long) {
        touch(displayId, LwServiceProtocol.TOUCH_DOWN, x, y)
        SystemClock.sleep(heldFor(holdMs).coerceAtLeast(TAP_HOLD_MS))
        touch(displayId, LwServiceProtocol.TOUCH_UP, x, y)
    }

    /**
     * Drag a finger from one point to another over the requested duration
     *
     * The moves are paced out rather than sent in one burst: a gesture the platform receives as
     * five points in the same millisecond is a jump, and everything that reads velocity - flings,
     * scrolling, a drag handle - reads it wrong. The last move lands on the target and the lift
     * follows at the same point, so the finger stops before it is released
     *
     * 每一步都要等, 主屏与虚拟屏都一样 (见那一行注释里的实测): 少了它, 一串同毫秒的 MOVE 会被平台
     * 合并成一次跳, 而按帧读输入的引擎会把它读成"按下加抬起", 手势就变成了点击
     */
    fun swipe(
        displayId: Int,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ) {
        touch(displayId, LwServiceProtocol.TOUCH_DOWN, fromX, fromY)
        val stepMs = (durationMs / SWIPE_STEPS).coerceAtLeast(MIN_STEP_MS)
        var x = fromX
        var y = fromY
        for (step in 1..SWIPE_STEPS) {
            // **每一步都要等**: 同一毫秒里发出的一串 MOVE 在分发器看来会被合并成一次跳, 而按帧读输入
            // 的引擎 (Unity 就是) 在那一帧里只看到按下加抬起 —— 手势就成了点击, 实测干员列表里 0.407%
            // 的像素动了一下 (按下反馈) 而平台自己的 input swipe 动了 82%
            SystemClock.sleep(stepMs)
            val progress = step.toFloat() / SWIPE_STEPS
            x = fromX + (toX - fromX) * progress
            y = fromY + (toY - fromY) * progress
            touch(displayId, LwServiceProtocol.TOUCH_MOVE, x, y)
        }
        touch(displayId, LwServiceProtocol.TOUCH_UP, toX, toY)
    }

    /**
     * 一条路径上的一个点, 屏自己的坐标
     *
     * 手势是"每一根手指各自一条路径", 而不是一串点: 平台按 pointer id 分辨手指, 所以形状只能这么给
     */
    data class Path(val points: List<Pair<Float, Float>>)

    /**
     * 多指手势: 每条路径一根手指, 一起走
     *
     * 与 [swipe] 同一条道理 (每一步都要等一帧, 否则平台把一串同毫秒的 MOVE 合并成一次跳), 只是这一步
     * 里所有手指一起动。步数是那条最长的路径分出来的, 短的那些在它的终点停住 —— 这正是捏合该有的样子:
     * 两根手指中有一根可能先停下
     *
     * 收紧一点: 指针 id 就是路径下标, 步数按最长的路径算, 而**一条路径至少两个点**才有方向可说
     */
    fun gesture(displayId: Int, paths: List<Path>, durationMs: Long) {
        if (paths.isEmpty()) throw IllegalArgumentException("a gesture needs at least one path")
        paths.forEach { path ->
            if (path.points.size < 2) {
                throw IllegalArgumentException("a gesture path needs at least two points: " +
                    "a single point is a tap, which lw_tap already does")
            }
        }
        val steps = paths.maxOf { it.points.size - 1 }
        val stepMs = (durationMs / steps).coerceAtLeast(MIN_STEP_MS)
        val downTime = SystemClock.uptimeMillis()
        var last = pointsAt(paths, 0)

        sendMulti(displayId, MotionEvent.ACTION_DOWN, downTime, last, firstPointerIndex = 0)
        for (step in 1..steps) {
            SystemClock.sleep(stepMs)
            val next = pointsAt(paths, step)
            if (step == steps && next.size > 1) {
                // 收尾: 多指一起抬起是"每根手指各自抬" - 逐个 POINTER_UP 之后剩下的那一根才 UP, 只发
                // 一个 ACTION_UP 的话另外那根手指会被平台留在屏幕上
                next.indices.toList().dropLast(1).forEach { index ->
                    sendMulti(displayId, MotionEvent.ACTION_POINTER_UP, downTime, next, index)
                }
            }
            sendMulti(
                displayId,
                if (step == steps) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                downTime,
                next,
                firstPointerIndex = 0,
            )
            last = next
        }
    }

    /**
     * 捏合: 两根手指在给定中心两侧起落
     *
     * `scale` 大于 1 是放大 (两指分开), 小于 1 是缩小。它只是 [gesture] 的糖衣, 因为捏合的形状本来
     * 就是"两根手指各自一条直线路径"
     */
    fun pinch(displayId: Int, centerX: Float, centerY: Float, scale: Float, durationMs: Long) {
        if (scale <= 0f) throw IllegalArgumentException("scale has to be greater than zero")
        val spread = PINCH_SPREAD
        val from = listOf(
            centerX - spread to centerY,
            centerX + spread to centerY,
        )
        val to = listOf(
            centerX - spread * scale to centerY,
            centerX + spread * scale to centerY,
        )
        gesture(
            displayId,
            listOf(Path(listOf(from[0], to[0])), Path(listOf(from[1], to[1]))),
            durationMs,
        )
    }

    /** 第 step 步时每根手指在哪, 走完的路径停在它的终点 */
    private fun pointsAt(paths: List<Path>, step: Int): List<Pair<Float, Float>> = paths.map { path ->
        path.points[step.coerceAtMost(path.points.lastIndex)]
    }

    /**
     * 一次多指事件
     *
     * 每一帧都把**所有**手指的位置带上: `MotionEvent` 里一条事件描述的是那一瞬间的整块手势面, 只带
     * 一根手指的话其它手指会被平台当成抬起了
     *
     * @param firstPointerIndex 只抬起/按下某一根手指时它自己的下标 (ACTION_POINTER_UP 的语义)
     */
    private fun sendMulti(
        displayId: Int,
        action: Int,
        downTime: Long,
        points: List<Pair<Float, Float>>,
        firstPointerIndex: Int,
    ) {
        val properties = Array(points.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(points.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = points[index].first
                y = points[index].second
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            /* downTime = */ downTime,
            /* eventTime = */ SystemClock.uptimeMillis(),
            /* action = */ action,
            /* pointerCount = */ points.size,
            /* pointerProperties = */ properties,
            /* pointerCoords = */ coordinates,
            /* metaState = */ 0,
            /* buttonState = */ 0,
            /* xPrecision = */ 1f,
            /* yPrecision = */ 1f,
            /* deviceId = */ 0,
            /* edgeFlags = */ 0,
            /* source = */ InputDevice.SOURCE_TOUCHSCREEN,
            /* flags = */ 0,
        )
        try {
            aim(event, displayId)
            if (!inject(event)) {
                Log.w(TAG, "the device refused a ${points.size}-pointer event on display $displayId")
            }
        } finally {
            event.recycle()
        }
    }

    /**
     * Press and release one key, holding it down for as long as the caller asked
     *
     * A key is not a finger: the platform matches the release to its press by the down time the two
     * events carry, and what a widget or the policy reads as a long press is the flag on the
     * release rather than the time between them - so a hold past the platform's own threshold is
     * lifted with that flag, which is what the platform's own `input keyevent --longpress` does.
     * The hold itself is real as well: it is how long the key stays down
     *
     * @returns whether the device took both halves of the press
     */
    fun key(displayId: Int, keyCode: Int, holdMs: Long): Boolean {
        val downTime = SystemClock.uptimeMillis()
        if (!sendKey(displayId, KeyEvent.ACTION_DOWN, keyCode, downTime, downTime, 0)) {
            return false
        }
        SystemClock.sleep(heldFor(holdMs).coerceAtLeast(KEY_HOLD_MS))
        val flags = if (holdMs >= LwServiceProtocol.LONG_PRESS_MS) KeyEvent.FLAG_LONG_PRESS else 0
        return sendKey(
            displayId,
            KeyEvent.ACTION_UP,
            keyCode,
            downTime,
            SystemClock.uptimeMillis(),
            flags,
        )
    }

    /**
     * Type a string as key presses
     *
     * This is what is left for a screen that reports no field to set the text on - an engine drawn
     * interface with a hidden field behind it, say - and it only produces what a keyboard can: the
     * events come from the virtual keyboard's own map, so letters, digits and its punctuation work
     * and anything else, Chinese above all, does not
     *
     * @returns how many characters were delivered, or [NOT_TYPABLE] when this device's keyboard
     *   cannot produce them
     */
    fun text(displayId: Int, text: String): Int {
        val map = keyboard
            ?: KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).also { keyboard = it }
        val events = map.getEvents(text.toCharArray()) ?: return NOT_TYPABLE
        events.forEach { event ->
            event.source = InputDevice.SOURCE_KEYBOARD
            aim(event, displayId)
            if (!inject(event)) {
                Log.w(TAG, "the device refused a key while typing on display $displayId")
                return NOT_TYPABLE
            }
        }
        return text.length
    }

    /** One half of a key press, aimed at a screen the same way a touch is */
    private fun sendKey(
        displayId: Int,
        action: Int,
        keyCode: Int,
        downTime: Long,
        eventTime: Long,
        flags: Int,
    ): Boolean {
        // The device id is the virtual keyboard's on purpose: the event has to say it came from a
        // keyboard nobody owns, which is the closest thing to "the platform itself pressed this"
        val event = KeyEvent(
            downTime,
            eventTime,
            action,
            keyCode,
            NO_REPEAT,
            NO_META,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            NO_SCANCODE,
            flags,
            InputDevice.SOURCE_KEYBOARD,
        )
        // A key event is not pooled the way a motion event is, so there is nothing to give back
        aim(event, displayId)
        return if (inject(event)) {
            true
        } else {
            Log.w(TAG, "the device refused key $keyCode on display $displayId")
            false
        }
    }

    /**
     * How long to hold something the caller asked to be held for a long press
     *
     * A hold that only just reaches the platform's threshold is a race rather than a long press:
     * the detector runs on the main thread at exactly that moment, so a lift that beats it turns
     * the long press back into a plain press and the caller is never told. Everything that asks for
     * a long press at all is therefore given the margin, which is short enough not to be felt
     */
    private fun heldFor(holdMs: Long): Long =
        if (holdMs in LwServiceProtocol.LONG_PRESS_MS until LONG_PRESS_SAFE_MS) {
            LONG_PRESS_SAFE_MS
        } else {
            holdMs
        }

    /** One moment of a finger, in the screen's own coordinates */
    fun touch(displayId: Int, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        when (action) {
            LwServiceProtocol.TOUCH_DOWN -> {
                downTime = now
                down = true
                send(displayId, MotionEvent.ACTION_DOWN, x, y, now)
            }

            LwServiceProtocol.TOUCH_MOVE -> if (down) send(displayId, MotionEvent.ACTION_MOVE, x, y, now)

            LwServiceProtocol.TOUCH_UP -> {
                // A lift that never saw a press would be delivered as a stray event, which some
                // apps read as a tap of their own
                if (down) send(displayId, MotionEvent.ACTION_UP, x, y, now)
                down = false
            }

            else -> throw IllegalArgumentException("unknown touch action $action")
        }
    }

    /** Ask the device to deliver one event as if it had come from the screen itself */
    private fun send(displayId: Int, action: Int, x: Float, y: Float, eventTime: Long) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            // An event with no source is refused by the dispatcher before it ever reaches a window
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            aim(event, displayId)
            if (!inject(event)) {
                Log.w(TAG, "the device refused $action at $x,$y on display $displayId")
            }
        } finally {
            event.recycle()
        }
    }

    /** Point an event at a display, which is the hidden half of injecting onto a second screen */    private fun aim(event: InputEvent, displayId: Int) {
        val method = setDisplayIdMethod
            ?: InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .also { setDisplayIdMethod = it }
        method.invoke(event, displayId)
    }

    private fun inject(event: InputEvent): Boolean {
        val method = injectMethod
            ?: InputManager::class.java
                .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                .also { injectMethod = it }
        // Waiting for a result would hold this thread until the app under the finger answered,
        // which is the one thing a gesture cannot afford
        return method.invoke(manager(), event, MODE_ASYNC) as Boolean
    }

    private fun manager(): InputManager {
        manager?.let { return it }
        val base = context
            ?: throw IllegalStateException("the privileged process has no context to reach the input manager")
        val service = base.getSystemService(InputManager::class.java)
            ?: throw IllegalStateException("this device has no input manager")
        return service.also { manager = it }
    }

    private companion object {
        const val TAG = "LwInput"

        /** `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC`, which is a plain int in the platform */
        const val MODE_ASYNC = 0

        /** Long enough to read as a press, short enough that a tap still feels immediate */
        const val TAP_HOLD_MS = 40L

        /** How many points a drag is broken into, which is what gives it a speed */
        const val SWIPE_STEPS = 12

        /**
         * 捏合开始时两根手指离中心多远, 单位是屏的像素
         *
         * 这个数是长按不动的: 它决定捏合 "看起来" 从多宽开始, 而多数实现只关心比值。手要落在一个不被
         * 别的东西吃掉的地方, 而中心是调用方给的
         */
        const val PINCH_SPREAD = 160f

        /**
         * One 60 Hz frame, which is the shortest a step may be
         *
         * A step shorter than a frame can land in the same frame as the one before it, and then the
         * app never sees the finger move: it sees a press, a jump and a lift. Four milliseconds was
         * the old floor and it was too short, because nothing paced the steps at all
         */
        const val MIN_STEP_MS = 16L

        /** A key press is short: the platform does not read velocity into one */
        const val KEY_HOLD_MS = 40L

        /** What a long press is held for when the caller asked for one that only just reaches it */
        const val LONG_PRESS_SAFE_MS = 650L

        const val NO_REPEAT = 0
        const val NO_META = 0
        const val NO_SCANCODE = 0

        /** What typing answers when the keyboard cannot produce the text at all */
        const val NOT_TYPABLE = -1
    }
}
