package io.github.yuloong07star.luwi.lock

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yuloong07star.luwi.channel.PrivilegedChannel
import io.github.yuloong07star.luwi.tool.LwPower
import java.util.concurrent.Executors
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 录一次解锁 (批次 5, 需求 7)
 *
 * 流程: 进设置页那一条「录制解锁」, 先把密码打在这里 (留空 = 这台机器只有滑动锁) → 应用把屏锁上 →
 * 主人对着锁屏走一遍 → **解开了就自己收工** (认的是"锁屏真的让开了", 不是某个按钮)
 *
 * 手势那一半走特权那边读真手指 ([PrivilegedChannel] 的 `touchRecord`: `/dev/input` 只有那个 uid 读得
 * 到, 而注入的事件不会出现在那条节点上, 所以读到的每一帧都是主人自己的手指); 密码那一半由主人打在这
 * 里, 加密存 —— 键盘上那几个点击的坐标就是密码, 所以它们**故意不录** (见 [LockSteps.settleTaken])
 *
 * 一拍 1.5 秒取一次, 而不是一口气录 60 秒: 每取一次都能看一眼锁屏让开了没有, 而且一次 binder 事务
 * 压在 60 秒上没有任何好处
 */
internal object LockRecord {

    private const val TAG = "LwLock"

    /** 一拍多久 */
    private const val CHUNK_MS = 1_500L

    /** 录多久还走不完就算了: 主人可能放弃了, 或者锁屏根本没让开 */
    private const val CAP_MS = 60_000L

    /** 开录之后先等这么久再看"是不是已经解开了", 免得把它自己锁屏那一下读成"主人已经进去了" */
    private const val SETTLE_MS = 1_200L

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lw-lock-record").apply { isDaemon = true }
    }

    /** 设置页那一段要看的: 正在录没有 */
    var recording: Boolean by mutableStateOf(false)
        private set

    /** 录到几步了 */
    var collected: Int by mutableStateOf(0)
        private set

    /** 上一次录完 (或没录成) 的那句人话 */
    var lastResult: String? by mutableStateOf(null)
        private set

    /** 这一轮从触屏节点上一共读到几帧: "什么都没录到"时, 它是唯一分得清原因的那个读数 */
    var lastSamples: Int by mutableStateOf(0)
        private set

    @Volatile
    private var stopping = false

    /** 这一次的密码只活在内存里, 录成了才写着落盘 */
    @Volatile
    private var pending: String = ""

    /**
     * 开始录
     *
     * @param password 主人在这一页打的那一份 (留空 = 只有滑动那一段)
     * @return 起不来的那句人话, 起来了就是 null
     */
    fun start(context: Context, password: String): String? {
        val app = context.applicationContext
        if (recording) return "a recording is already running: finish it first"
        val proxy = PrivilegedChannel.ensure()
            ?: return "the privileged channel is not available, so the walk cannot be recorded:" +
                " " + (PrivilegedChannel.state().error ?: "no reason reported")
        pending = password
        stopping = false
        collected = 0
        lastSamples = 0
        lastResult = null
        recording = true
        // 先把屏锁上: 主人要走的正是"看到锁屏 → 解开"这一整段
        val locked = runCatching {
            LwPower.dispatch(app, buildJsonObject { put("op", "lock") })
        }.getOrElse { error -> "the screen could not be locked (${error.message})" }
        LockReplay.post(
            app,
            "Recording the unlock",
            "Walk through the unlock on the phone now: swipe and type as you always do. The" +
                " screen was just locked for this. It stops by itself once the phone is open." +
                " ($locked)",
        )
        worker.execute { run(app, proxy) }
        return null
    }

    /** 手动收工 (设置页那个按钮): 主人觉得自己已经把该走的走完了 */
    fun stop() {
        stopping = true
    }

    /** 录制那条线程: 一拍取一次, 锁屏让开就收工 */
    private fun run(context: Context, proxy: io.github.yuloong07star.luwi.channel.LwServiceProxy) {
        val startedAt = System.currentTimeMillis()
        val taken = mutableListOf<LockStep>()
        var problem: String? = null
        try {
            while (true) {
                val chunk = runCatching { proxy.touchRecord(false) }
                    .getOrElse { error ->
                        io.github.yuloong07star.luwi.channel.TouchChunk(
                            error.message ?: "the recorder could not be reached",
                            "",
                            0,
                        )
                    }
                val chunkProblem = chunk.problem
                val steps = LockSteps.decode(chunk.steps)
                if (chunkProblem != null) {
                    problem = chunkProblem
                    break
                }
                lastSamples = chunk.samples
                if (steps.isNotEmpty()) {
                    taken += steps
                    collected = taken.size
                }
                val elapsed = System.currentTimeMillis() - startedAt
                if (stopping) break
                if (elapsed > CAP_MS) {
                    problem = "the recording ran out of time: it was still running after" +
                        " ${CAP_MS / 1000} s, so nothing was saved"
                    break
                }
                // 锁屏真的让开了 = 主人走完了 (它自己那一下锁屏要跳过, 所以先等一段)
                if (elapsed > SETTLE_MS && LockSetting.unlocked(context)) break
                Thread.sleep(CHUNK_MS)
            }
            // 最后再取一次并关掉节点: 那条还没抬起的笔画到这一刻也该收尾了
            val tail = runCatching { proxy.touchRecord(true) }
                .getOrElse { error ->
                    io.github.yuloong07star.luwi.channel.TouchChunk(
                        error.message ?: "the recorder could not be closed",
                        "",
                        lastSamples,
                    )
                }
            if (problem == null) problem = tail.problem
            lastSamples = maxOf(lastSamples, tail.samples)
            taken += LockSteps.decode(tail.steps)
        } catch (error: Throwable) {
            problem = error.message ?: error.javaClass.simpleName
            Log.w(TAG, "the recording stopped with an error", error)
        } finally {
            recording = false
        }
        finish(context, taken, problem)
        pending = ""
    }

    /** 录完那一段: 收成一条序列, 该加密的加密, 该丢的丢掉 */
    private fun finish(context: Context, taken: List<LockStep>, problem: String?) {
        if (problem != null) {
            lastResult = "nothing was saved: $problem"
            LockReplay.post(context, "Recording the unlock", lastResult!!)
            Log.w(TAG, lastResult!!)
            return
        }
        val password = pending.ifEmpty { "" }
        val (steps, pattern) = LockSteps.settleTaken(taken, hasPassword = password.isNotEmpty())
        if (steps.isEmpty() && pattern == null) {
            lastResult = "nothing was saved: no touch was recorded at all (was the walk done on" +
                " another screen, or is this device's touchscreen not readable by the privileged side?)"
            LockReplay.post(context, "Recording the unlock", lastResult!!)
            return
        }
        val stored = when {
            pattern != null -> LockSecret.save(
                context,
                LockSecretData(kind = LockSecretData.PATH, points = pattern),
            )

            password.isNotEmpty() -> LockSecret.save(
                context,
                LockSecretData(kind = LockSecretData.TEXT, text = password),
            )

            else -> {
                LockSecret.clear(context)
                null
            }
        }
        LockSetting.saveSteps(context, steps)
        LockSetting.setTries(context, 0)
        val shape = LockSetting.describe(steps).joinToString(" then ")
        lastResult = if (stored != null) {
            "the gestures were saved, but $stored"
        } else {
            "saved ${steps.size} step(s): $shape" +
                when {
                    pattern != null -> " (the pattern is in the encrypted slot)"
                    password.isNotEmpty() -> " (the password is in the encrypted slot)"
                    else -> ""
                }
        }
        LockReplay.post(context, "Recording the unlock", lastResult!!)
        Log.i(TAG, lastResult!!)
    }
}
