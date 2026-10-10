package io.github.yuloong07star.luwi.channel

import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.yuloong07star.luwi.lock.LockStep
import io.github.yuloong07star.luwi.lock.LockTouch
import java.io.FileInputStream

/**
 * 把真手指在屏上的动作读成步骤 (批次 5, 需求 7 的录制那一半)
 *
 * 它必须活在这一侧: `/dev/input` 是 `input` 组的, 应用不在那个组里, 而这个进程是被 root 或 shell
 * 起来的。**注入的事件不会出现在这个节点上** (`InputManager.injectInputEvent` 进的是内核读取之后那一
 * 段), 所以这里读到的每一帧都是真手指 —— 那条判据是 1.0.3 量过的, 这一批的验证脚本里再量一次
 *
 * 与那个已经删掉的 [LwTouchWatch] (1.0.3 的"触摸刹车") 是同一套读法: 一次读一个 `input_event`, 按本
 * 进程的字长算记录长度, 短读要补齐再看 —— 差别只在于那个只看"有没有手指", 这个要坐标
 *
 * **一次 call 交出去的是"已经完成的笔画"**: 手指还按着的那一条留在手里 (见 [drain]), 于是调用方按
 * 2 秒一拍来取也不会把一条上滑切成两半
 */
class LwTouchRecord {

    private val monitor = Any()

    @Volatile
    private var reader: Thread? = null

    private var stream: FileInputStream? = null

    @Volatile
    private var node: String? = null

    @Volatile
    private var failure: String? = null

    /**
     * 这是第几次读 (每一轮 [start] 加一)
     *
     * 它防的是**上一轮那一声"读到一半被打断"落到这一轮的账上**: [stop] 关掉节点之后, 那条读线程要过
     * 一会儿才发现自己被打断, 而那时下一轮已经开始了 —— 不认这一代的话, 新开的那一轮每一次 [drain]
     * 都会拿到上一轮那句失败, 表现是"录制永远什么都没录到" (2026-10-08 实测踩到的正是这一条)
     */
    private var generation = 0

    /** 这一轮一共读到过几帧 */
    @Volatile
    private var seen = 0

    /** 触屏那个节点报的两条量程: 比例就是 `value / 量程` */
    @Volatile
    private var xMax = 32767

    @Volatile
    private var yMax = 32767

    /** 还没交给调用方的样本, 按到达顺序 */
    private val samples = mutableListOf<LockTouch.Sample>()

    /** 手里这些样本里那根手指还按着没有 */
    private var down = false

    /** 现在报坐标的是哪一个 slot (多指协议 B; 只跟 0 号, 解锁用的是一根手指) */
    private var slot = 0
    private var x = 0
    private var y = 0

    /**
     * 手指来了但坐标还没到
     *
     * 协议 B 的顺序是 `ABS_MT_TRACKING_ID` 先来、坐标紧跟其后 (模拟器与真机都是这个顺序), 而按下那一帧
     * 要记的是**坐标那一帧**的位置 —— 在 tracking id 那一帧就记, 记下的是上一个手势留下的位置 (实测就
     * 是 0,0), 一条上滑会被记成"从左上角划过来"
     */
    private var waitingForPosition = false

    /**
     * 这两条轴**这一轮见过没有**
     *
     * 内核只上报变化过的坐标, 所以一场录制里某条轴可能一帧都不出现 (模拟器上那条 virtio 节点就是:
     * 一只只在竖直方向划动的手指, `ABS_MT_POSITION_X` 从头到尾不出现 —— 因为它与上一个手势的值一样,
     * 而 `getevent -p` 报回来的"当前值"又是个 0)。没见过的那条轴**按屏中间算**, 不按 0 算: 0 是屏幕
     * 最左边那一条, 在真机上正好是返回手势那一条 (2026-10-08 实测踩到的)
     */
    private var xKnown = false
    private var yKnown = false

    /**
     * 开始读 (已经在读就什么都不做)
     *
     * @return 读不成的理由, 成了就是 null
     */
    fun start(): String? {
        synchronized(monitor) {
            if (reader?.isAlive == true) return null
            // 上一轮那条线程可能还卡在 read 上 (它手里的节点已经被 stop 关过), 先把它那一代作废
            generation += 1
            val mine = generation
            runCatching { stream?.close() }
            stream = null
            reader = null
            val device = InputDevices.touchscreen(readInputDevices())
                ?: return "no touchscreen was recognised among the input devices"
            val opened = try {
                FileInputStream(device.path)
            } catch (error: Throwable) {
                return "${device.path} could not be read: ${error.message}"
            }
            node = device.path
            // 量程可能是反的 (个别面板报负数), 取绝对值那一侧: 比例只看这一段有多长
            xMax = (device.xMax - device.xMin).coerceAtLeast(1)
            yMax = (device.yMax - device.yMin).coerceAtLeast(1)
            // 起点用节点此刻的值: 内核只上报变化过的坐标, 一个没动过的那条轴整场都不会出现 (见
            // [InputDevice.xValue])
            x = device.xValue
            y = device.yValue
            xKnown = device.xValue != 0
            yKnown = device.yValue != 0
            samples.clear()
            down = false
            seen = 0
            stream = opened
            failure = null
            reader = Thread({ read(opened, mine) }, "lw-touch-record").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "recording touches from ${device.path} \"${device.name}\" (${xMax}x${yMax})")
            return null
        }
    }

    /**
     * 把已经走完的笔画交出去, **手里那条没抬起的留着**
     *
     * 切在**最后一个"抬起"**上: 那之前的是一串完整的动作 (用户已经做完的), 之后的 (手指还按着) 留到
     * 下一次 —— 不这么做的话, 一拍正好切在一条上滑中间, 那条上滑重放出来就只有半条
     */
    fun drain(): Pair<String?, List<LockStep>> {
        val taken: List<LockTouch.Sample>
        synchronized(monitor) {
            if (samples.isEmpty()) return failure to emptyList()
            // 最后一个 UP 之后的那一段就是"还没走完的那一条"
            val cut = samples.indexOfLast { it.phase == LockTouch.UP }
            taken = if (cut < 0) {
                // 一条都没走完: 什么都交不出去, 但把手里的先放回去
                if (down) return null to emptyList()
                // 没有任何 UP 而又没按着 (比如刚起读) —— 清掉这些噪声
                samples.toList().also { samples.clear() }
            } else {
                val complete = samples.take(cut + 1).toList()
                val rest = samples.drop(cut + 1).toMutableList()
                samples.clear()
                samples.addAll(rest)
                complete
            }
        }
        return failure to LockTouch.merge(taken, xMax, yMax)
    }

    /** 收工: 关掉节点 (手里那半条不要) */
    fun stop() {
        synchronized(monitor) {
            // 这一代作废: 这条线程等一下发现被打断时, 那句话不许落到下一轮的账上
            generation += 1
            runCatching { stream?.close() }
            stream = null
            reader = null
            samples.clear()
            down = false
        }
    }

    /** 这一轮读到过几帧 (给读数用) */
    fun seen(): Int = seen

    /**
     * 读那个节点, 一次一个 `input_event`
     *
     * 记录长度按本进程的字长算 (64 位是 24 字节: 两个 long 的 timeval + 三个 int), 而**短读要补齐再
     * 看**: 内核给的字节数不一定正好是一条记录
     */
    private fun read(source: FileInputStream, mine: Int) {
        val record = ByteArray(if (Process.is64Bit()) RECORD_64 else RECORD_32)
        val header = record.size - FIELDS
        var filled = 0
        try {
            while (true) {
                val read = source.read(record, filled, record.size - filled)
                if (read < 0) break
                filled += read
                if (filled < record.size) continue
                filled = 0
                consider(record, header)
            }
        } catch (error: Throwable) {
            // 只有还属于这一代的才记账 (见 [generation])
            val problem = "reading $node stopped: ${error.message}"
            if (mine == generation) failure = problem
            Log.w(TAG, problem)
        }
    }

    /**
     * 一帧: 按类型与码分派 (这个函数就是 `input-event-codes.h` 里那几行)
     *
     * 坐标那两行**每一帧都要记**: 一条笔画里手指的位置本来就来自这些帧, 只在按下 / 抬起各记一次的话,
     * 一条上滑只剩首尾两点, 图案那种折线更是会塌成一条直线
     */
    private fun consider(record: ByteArray, header: Int) {
        val type = field(record, header, 2)
        val code = field(record, header + 2, 2)
        val value = field(record, header + 4, 4)
        when {
            type == EV_ABS && code == ABS_MT_SLOT -> slot = value

            type == EV_ABS && (code == ABS_MT_POSITION_X || code == ABS_X) -> {
                if (code == ABS_X || slot == 0) {
                    x = value
                    xKnown = true
                }
                position()
            }

            type == EV_ABS && (code == ABS_MT_POSITION_Y || code == ABS_Y) -> {
                if (code == ABS_Y || slot == 0) {
                    y = value
                    yKnown = true
                }
                position()
            }

            type == EV_ABS && code == ABS_MT_TRACKING_ID -> touch(value != -1)

            type == EV_KEY && code == BTN_TOUCH -> touch(value != 0)
        }
    }

    /** 坐标来了: 手指按着就记一帧 (第一帧坐标顺便把"按下"记下来) */
    private fun position() {
        if (!down) return
        if (waitingForPosition) {
            waitingForPosition = false
            add(LockTouch.DOWN)
            return
        }
        add(LockTouch.MOVE)
    }

    /** 手指来了或走了: 走完的那条由 [drain] 交出去 */
    private fun touch(isDown: Boolean) {
        if (!isDown && !down) return
        if (isDown) {
            // 按下那一帧先不记: 等紧跟着的那一帧坐标 (见 [waitingForPosition])
            if (!down) {
                waitingForPosition = true
                // 这一轮还没见过的轴按屏中间算 (见 [xKnown])
                if (!xKnown) x = xMax / 2
                if (!yKnown) y = yMax / 2
            }
            down = true
            return
        }
        down = false
        waitingForPosition = false
        add(LockTouch.UP)
    }

    /** 记一帧 */
    private fun add(phase: Int) {
        seen += 1
        synchronized(monitor) {
            // 上限是防"手指一直按着"那条路把内存吃满: 到了就丢掉最早的, 只留最近的一段
            if (samples.size >= MAX_SAMPLES) samples.subList(0, MAX_SAMPLES / 4).clear()
            samples.add(LockTouch.Sample(x, y, phase, SystemClock.uptimeMillis()))
        }
    }

    /** One little endian field of an `input_event`, which is how the kernel writes them */
    private fun field(record: ByteArray, at: Int, width: Int): Int {
        var value = 0
        for (byte in 0 until width) {
            value = value or ((record[at + byte].toInt() and 0xFF) shl (byte * 8))
        }
        return value
    }

    private companion object {
        const val TAG = "LwTouchRecord"

        /** `struct input_event` on a 64 bit process: two longs of timeval, then the event */
        const val RECORD_64 = 24

        /** The same on a 32 bit one, where a timeval is two ints */
        const val RECORD_32 = 16

        /** Type, code and value, whatever the timeval in front of them is worth */
        const val FIELDS = 8

        /** 手里最多留这么多帧 */
        const val MAX_SAMPLES = 4_000

        const val EV_KEY = 0x01
        const val EV_ABS = 0x03
        const val ABS_X = 0x00
        const val ABS_Y = 0x01
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        const val BTN_TOUCH = 0x14a
    }
}
