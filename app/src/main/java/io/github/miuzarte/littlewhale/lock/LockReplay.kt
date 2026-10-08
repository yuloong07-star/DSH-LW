package io.github.miuzarte.littlewhale.lock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import io.github.miuzarte.littlewhale.MainActivity
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.channel.KeyCodes
import io.github.miuzarte.littlewhale.channel.LwInput
import io.github.miuzarte.littlewhale.channel.LwServiceProtocol
import io.github.miuzarte.littlewhale.channel.LwServiceProxy
import io.github.miuzarte.littlewhale.channel.PrivilegedChannel
import io.github.miuzarte.littlewhale.tool.LwSystemCommand
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 唤醒之后那一段: 点亮屏幕, 再把主人自己的那串解锁动作重放一遍 (批次 5, 需求 2 与 7)
 *
 * 点亮三条路按可靠性排, 走到哪一条都要**如实说**: 有特权通道就用通道 (熄屏也能点, 最稳), 没有就拿
 * 应用自己的电源锁 (官方那一条, 代价是必须真的起一个 Activity), 两条都不行时退到全屏 intent 通知
 * (要 `canUseFullScreenIntent()` 真的为真)
 *
 * **重放挂在后台那条线程上**: 唤醒命中那一刻跑在采集线程上 (见 `WakeWordService.voiceSink`), 在那里
 * 等几秒等于让麦克风整整几秒没人读。所以顺序是"点亮 (快, 有预算地等) → 把重放丢出去 → 开麦放球",
 * 而重放自己走它的那几秒, 主人说话与它并行
 *
 * **解锁失败不许把唤醒本身带走**: 重放的结果只影响那一行留痕与失败计数, 语音窗口照开
 */
internal object LockReplay {

    private const val TAG = "LwLock"

    /**
     * 留痕那条通知: 一个 id, 后一次覆盖前一次
     *
     * **渠道名比第一版多了个 `-note`**: 第一版是 `IMPORTANCE_LOW`, 而 2026-10-08 在模拟器上量到那一档
     * **根本不进通知栏那一屏** (同样一条通知, 换成高重要度的渠道就出现了) —— 那正是"每次重放都留一行"
     * 这条承诺的反面。现在是 `IMPORTANCE_DEFAULT` + 自己不出声: 看得见, 也不响
     */
    private const val CHANNEL_ID = "lw-lock-note"

    /** 第一版那条渠道, 只为了删掉它 */
    private const val LEGACY_CHANNEL_ID = "lw-lock"
    private const val NOTICE_ID = 41

    /** 点亮那一步的预算: 它是唤醒那条路上的一步, 长到几秒就会把采集拖住 */
    private const val WAKE_BUDGET_MS = 1_200L

    /** 整条重放的预算: 过了它就停下来如实说"没走完" */
    private const val REPLAY_BUDGET_MS = 6_000L

    /** 每一步之间停一下: 平台读手势要看时间, 连着发会读成一次跳 */
    private const val STEP_SETTLE_MS = 140L

    /**
     * 点亮之后、第一条动作之前的那一段等待
     *
     * 屏幕亮起来到锁屏那一屏真的画出来之间隔着几百毫秒, 而重放是按毫秒走的 —— 抢在那之前把上滑发出去,
     * 它会落在还没画好的屏上 (2026-10-08 实测出现过一次: 同一份录制的重放, 一次到桌面一次没到)。录制那
     * 一侧没有这个问题: 那条手指是人按的, 时间由人掌握
     */
    private const val GUARD_READY_MS = 400L

    /** 输完密码再按回车之间那一下 */
    private const val TYPE_SETTLE_MS = 220L

    /** 一条折线画多久: 每个点给一点时间, 平台才读得出"一笔画" */
    private const val DRAW_MS = 400L

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lw-lock").apply { isDaemon = true }
    }

    @Volatile
    private var replaying = false

    /** 上一次重放的那句人话, 给设置页与 `lw_lock op=status` 看 */
    @Volatile
    var lastReport: String? = null
        private set

    /** 改开关时把上一次的结果清掉, 免得那句话看起来说的是刚刚这一次 */
    fun forgetWarnings() {
        lastReport = null
    }

    /**
     * 唤醒词命中那一刻做的事
     *
     * 点亮是**同步**的 (它有预算, 而且"屏幕亮了没有"决定重放要不要跑), 重放自己丢给 [worker]
     */
    fun onWake(context: Context, budgetMs: Long = WAKE_BUDGET_MS) {
        val app = context.applicationContext
        if (!LockSetting.wakeScreen(app)) return
        val woke = wakeScreen(app, budgetMs)
        Log.i(TAG, "a wake word asked for the screen: $woke")
        if (!LockSetting.autoUnlock(app)) return
        if (LockSetting.unlocked(app)) return
        // 没录过就别走那一条: 它会往通知栏写一行"没得放" —— 一次唤醒一行, 那不是留痕是噪声
        if (!LockSetting.recorded(app)) return
        if (LockTries.exhausted(LockSetting.tries(app))) return
        replayLater(app, "the wake word")
    }

    /** 把一次重放丢到后台线程, 同一刻只允许一次 */
    private fun replayLater(context: Context, source: String) {
        if (replaying) {
            lastReport = "a replay is already running"
            return
        }
        worker.execute {
            replaying = true
            try {
                replay(context, source)
            } catch (error: Throwable) {
                lastReport = "the replay threw: ${error.message ?: error.javaClass.simpleName}"
                Log.w(TAG, "the replay threw", error)
            } finally {
                replaying = false
            }
        }
    }

    /**
     * 手动重放一次 (设置页那个「测试一次」与 `lw_lock op=unlock`), 等人
     *
     * 与 [onWake] 走的是同一个 [replay], 差别只在它在这个线程上等完 —— 调用方要的是结果
     */
    fun replayNow(context: Context, source: String = "the settings page"): ReplayReport {
        val app = context.applicationContext
        if (replaying) {
            return ReplayReport(false, 0, 0, "a replay is already running", true)
        }
        replaying = true
        return try {
            replay(app, source)
        } finally {
            replaying = false
        }
    }

    /**
     * 点亮屏幕, 三条路依次试
     *
     * 回的是**这一条路成没成**的原话, 而不是一个布尔: 没成时缺的是哪一样 (通道 / 电源锁 / 全屏通知
     * 那道授权) 才是主人要拿去办的事
     */
    fun wakeScreen(
        context: Context,
        budgetMs: Long = WAKE_BUDGET_MS,
        allowConnecting: Boolean = false,
    ): String {
        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java)
            ?: return "this device has no power manager, so nothing can light the screen"
        if (power.isInteractive) return "the screen was already on"

        // 路 1: 特权通道的 KEYCODE_WAKEUP (白名单里那条 wake, 与 lw_key 同一个键)
        //
        // **只在这条通道已经活着的时候试它**: `ensure()` 是会去连一次的 (起 app_process / 问
        // Shizuku), 那件事在"这台设备根本没有通道"时要花上几秒 —— 而这一句跑在唤醒那条路上, 花几秒
        // 就是几秒听不见人说话。通道没起来时直接走下面那两条, 并在话里说明白
        val live = PrivilegedChannel.state().connected || allowConnecting
        val viaChannel = if (live) {
            runCatching { LwSystemCommand.run("wake") }
        } else {
            Result.failure(IllegalStateException("the privileged channel is not up"))
        }
        val channelProblem = viaChannel.getOrElse { error ->
            "the privileged channel is not usable (${error.message ?: "no reason given"})"
        }
        if (viaChannel.isSuccess && waitInteractive(power, budgetMs)) {
            return "the privileged channel woke it with KEYCODE_WAKEUP"
        }
        val whyChannel = if (viaChannel.isSuccess) {
            "the privileged channel took KEYCODE_WAKEUP but the screen is still off"
        } else {
            channelProblem
        }

        // 路 2: 应用自己的电源锁 (官方那一条; 拿不到就说明这台机器不给)
        val lock = runCatching {
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "lw:lock-wake",
            ).apply { acquire(budgetMs + 1_000) }
        }.getOrElse { error ->
            return "$whyChannel; this app cannot take a wake lock either (${error.message}), so" +
                " nothing lit the screen. Grant the privileged channel, or turn on 全屏通知 for" +
                " this app and the notification will light it instead"
        }
        val wokeByLock = try {
            waitInteractive(power, budgetMs)
        } finally {
            if (lock.isHeld) runCatching { lock.release() }
        }
        if (wokeByLock) return "the app's own wake lock lit it ($whyChannel)"

        // 路 3: 全屏 intent 通知 —— 唯一一条"没有特权通道也可能亮"的路, 代价是它会弹一整块界面
        return "$whyChannel, and the app's own wake lock was refused or did not take either, so" +
            " nothing lit the screen" + bannerRefusal(app)
    }

    /** 那三条路都不成时, 全屏通知这一条差在哪 */
    private fun bannerRefusal(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return ""
        val allowed = runCatching { manager.canUseFullScreenIntent() }.getOrNull()
        return when (allowed) {
            true -> ", and the full-screen notification it would fall back to is allowed here, so" +
                " check that the device is not in doze"

            false -> ", and 全屏通知 is not granted to this app (Android 14 made it a switch of its" +
                " own: 设置 -> 应用 -> DSH-LW -> 特殊应用权限)"

            null -> ", and this device would not say whether a full-screen notification is allowed"
        }
    }

    /** 等屏幕真的亮起来, 最多 [budgetMs] */
    private fun waitInteractive(power: PowerManager, budgetMs: Long): Boolean {
        var waited = 0L
        while (!power.isInteractive && waited < budgetMs) {
            Thread.sleep(60)
            waited += 60
        }
        return power.isInteractive
    }

    /**
     * 重放一遍
     *
     * 三条纪律都在这里: **每一步之间停一下**, **到桌面就停手** (后面那些步骤是"确认"用的, 已经到
     * 桌面还接着按才是错的), **过了预算就如实说没走完**。计数与留痕由 [report] 收尾
     */
    private fun replay(context: Context, source: String): ReplayReport {
        val steps = LockSetting.steps(context)
        if (steps.isEmpty()) {
            return report(
                context,
                source,
                ReplayReport(false, 0, 0, "nothing has been recorded yet", true),
            )
        }
        val (secret, secretError) = LockSecret.load(context)
        val proxy = PrivilegedChannel.ensure()
            ?: return report(
                context,
                source,
                ReplayReport(
                    false,
                    0,
                    steps.size,
                    "the privileged channel is not available, so nothing could be replayed:" +
                        " " + (PrivilegedChannel.state().error ?: "no reason reported"),
                    false,
                ),
            )
        val (width, height) = screenPixels(context)
        val deadline = System.currentTimeMillis() + REPLAY_BUDGET_MS
        var done = 0
        var stoppedAt: String? = null
        var needsSecret = false

        // 先等屏幕真的亮着, 再给锁屏那几百毫秒 (见 [GUARD_READY_MS])
        if (!LockSetting.screenOn(context)) waitScreenOn(context, WAKE_BUDGET_MS)
        sleep(GUARD_READY_MS)

        for ((index, step) in steps.withIndex()) {
            if (LockSetting.unlocked(context)) break
            if (System.currentTimeMillis() > deadline) {
                stoppedAt = "the replay ran out of time after $done step(s)"
                break
            }
            if (step is LockStep.Secret) needsSecret = true
            val problem = try {
                run(context, proxy, step, width, height, secret, secretError)
            } catch (error: Throwable) {
                error.message ?: error.javaClass.simpleName
            }
            if (problem != null) {
                stoppedAt = "step ${index + 1} (${LockSteps.describe(step)}) did not take: $problem"
                break
            }
            done += 1
            sleep(STEP_SETTLE_MS)
        }

        // 最后再等一会儿: 密码输完到锁屏真的让开之间有一小段
        if (stoppedAt == null && !LockSetting.unlocked(context)) {
            val left = (deadline - System.currentTimeMillis()).coerceIn(200L, 1_500L)
            waitUnlocked(context, left)
        }
        val ok = LockSetting.unlocked(context)
        val detail = when {
            ok -> "walked through $done of ${steps.size} step(s) and the phone is on its home screen"
            stoppedAt != null -> "$stoppedAt, so the phone stayed on the lock screen"
            needsSecret && secret == null -> "the password step had nothing to send" +
                (secretError?.let { " ($it)" } ?: " (it was never stored)")

            else -> "all ${steps.size} step(s) ran, but the phone is still on the lock screen: the" +
                " recording does not match what this screen wants"
        }
        return report(context, source, ReplayReport(ok, done, steps.size, detail, false))
    }

    /** 一步: 五个动作加一个等待 */
    private fun run(
        context: Context,
        proxy: LwServiceProxy,
        step: LockStep,
        width: Int,
        height: Int,
        secret: LockSecretData?,
        secretError: String?,
    ): String? = when (step) {
        is LockStep.Key -> {
            val code = KeyCodes.resolve(step.name)
                ?: return "Android has no key called \"${step.name}\""
            if (proxy.key(LwServiceProtocol.MAIN_DISPLAY, code, step.holdMs)) null
            else "the device refused the ${step.name} key"
        }

        is LockStep.Tap -> {
            proxy.tap(
                LwServiceProtocol.MAIN_DISPLAY,
                LockSteps.px(step.x, width),
                LockSteps.px(step.y, height),
                step.holdMs,
            )
            null
        }

        is LockStep.Swipe -> {
            proxy.swipe(
                LwServiceProtocol.MAIN_DISPLAY,
                LockSteps.px(step.fromX, width),
                LockSteps.px(step.fromY, height),
                LockSteps.px(step.toX, width),
                LockSteps.px(step.toY, height),
                step.durationMs,
            )
            null
        }

        is LockStep.Stroke -> {
            val path = strokePath(step.points, width, height)
            if (path.size < 2) return "the stroke has too few points to draw"
            proxy.gesture(LwServiceProtocol.MAIN_DISPLAY, listOf(LwInput.Path(path)), step.durationMs)
            null
        }

        is LockStep.Wait -> {
            when (step.what) {
                LockSteps.UNLOCKED -> waitUnlocked(context, step.timeoutMs)
                LockSteps.SCREEN_ON -> waitScreenOn(context, step.timeoutMs)
                else -> sleep(step.timeoutMs)
            }
            null
        }

        LockStep.Secret -> when {
            secret == null -> "there is no stored password to send" +
                (secretError?.let { " ($it)" } ?: " (record the unlock again)")

            secret.kind == LockSecretData.PATH -> {
                val path = strokePath(secret.points, width, height)
                if (path.size < 2) return "the stored pattern has too few points to draw"
                proxy.gesture(LwServiceProtocol.MAIN_DISPLAY, listOf(LwInput.Path(path)), DRAW_MS)
                null
            }

            else -> {
                if (secret.text.isEmpty()) return "the stored password is empty"
                val typed = proxy.text(LwServiceProtocol.MAIN_DISPLAY, secret.text)
                if (typed < 0) return "this device's keyboard cannot type the stored password"
                sleep(TYPE_SETTLE_MS)
                proxy.key(
                    LwServiceProtocol.MAIN_DISPLAY,
                    KeyCodes.resolve("ENTER") ?: 66,
                    LockSteps.HOLD_MS,
                )
                null
            }
        }
    }

    private fun strokePath(points: List<List<Float>>, width: Int, height: Int): List<Pair<Float, Float>> =
        points.mapNotNull { point ->
            if (point.size < 2) null
            else LockSteps.px(point[0], width) to LockSteps.px(point[1], height)
        }

    private fun waitUnlocked(context: Context, timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (LockSetting.unlocked(context)) return true
            sleep(STEP_SETTLE_MS)
            waited += STEP_SETTLE_MS
        }
        return LockSetting.unlocked(context)
    }

    private fun waitScreenOn(context: Context, timeoutMs: Long): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return waitInteractive(power, timeoutMs)
    }

    /** 一次重放的结果, 收尾那三件事 (计数 / 自动关 / 留痕) 都在这里 */
    private fun report(context: Context, source: String, result: ReplayReport): ReplayReport {
        val app = context.applicationContext
        val after = LockTries.after(LockSetting.tries(app), result.ok)
        LockSetting.setTries(app, after)
        var sentence = "$source: ${result.detail}" +
            (if (result.ok) "" else " (attempt $after of ${LockTries.LIMIT})")
        if (!result.ok && LockTries.exhausted(after)) {
            LockSetting.setAutoUnlock(app, false)
            sentence += "; automatic unlock is now off: three attempts in a row failed, so this" +
                " needs a person - record the unlock again, or turn the switch back on after fixing it"
        }
        lastReport = sentence
        Log.i(TAG, sentence)
        post(app, "Unlocking by voice", sentence)
        return result.copy(detail = sentence)
    }

    /**
     * 每次重放都在通知栏留一行: 这件事必须看得见
     *
     * 录制那一段也走同一个去处 (见 `LockRecord`), 所以它是 internal 而不是 private
     */
    internal fun post(context: Context, title: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "锁屏", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                },
                PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                NOTICE_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .build(),
            )
        }.onFailure { Log.w(TAG, "the record of a replay could not be posted: ${it.message}") }
    }

    /** 屏幕那两块像素 (主屏自己那一块, 与注入时用的坐标系同一套) */
    private fun screenPixels(context: Context): Pair<Int, Int> {
        val bounds = runCatching {
            context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
        }.getOrNull()
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            return bounds.width() to bounds.height()
        }
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun sleep(ms: Long) {
        if (ms <= 0L) return
        runCatching { Thread.sleep(ms) }
    }

    /**
     * 一个读数: 这个功能此刻什么状态
     *
     * 与设置页那一段看的是同一批事实, 只是这里说给模型听 (`lw_lock op=status`)
     */
    fun status(context: Context): JsonObject {
        val app = context.applicationContext
        val steps = LockSetting.steps(app)
        val (secret, secretError) = LockSecret.load(app)
        val channel = PrivilegedChannel.state()
        return buildJsonObject {
            put("screenOn", LockSetting.screenOn(app))
            put("locked", LockSetting.locked(app))
            put("wakeScreen", LockSetting.wakeScreen(app))
            put("autoUnlock", LockSetting.autoUnlock(app))
            put("steps", steps.size)
            put("stepList", LockSetting.describe(steps).joinToString(" | "))
            put("recordedAt", LockSetting.recordedSentence(LockSetting.recordedAt(app)))
            put("secret", secret?.describe ?: "nothing")
            if (secretError != null) put("secretProblem", secretError)
            put("tries", LockSetting.tries(app))
            put("recording", LockRecord.recording)
            put("collected", LockRecord.collected)
            put("lastSamples", LockRecord.lastSamples)
            LockRecord.lastResult?.let { put("lastRecording", it) }
            put("channel", if (channel.connected) channel.backend.orEmpty() else "not connected")
            lastReport?.let { put("lastReplay", it) }
            put("text", statusSentence(app, steps, secret, secretError, channel.connected))
        }
    }

    /** 那一句话本身: 与 `lw_lock op=status` 的回执是同一条 (所以只有一处写法) */
    private fun statusSentence(
        app: Context,
        steps: List<LockStep>,
        secret: LockSecretData?,
        secretError: String?,
        connected: Boolean,
    ): String = buildString {
        append(if (LockSetting.screenOn(app)) "the screen is on" else "the screen is off")
        append(if (LockSetting.locked(app)) " and the phone is locked" else " and it is not locked")
        append("; ")
        append(if (steps.isEmpty()) "no unlock has been recorded" else "${steps.size} step(s) are recorded")
        if (steps.isNotEmpty()) append(" (${LockSetting.describe(steps).joinToString(" then ")})")
        append("; the password slot holds ${secret?.describe ?: "nothing"}")
        secretError?.let { append(" (it could not be read: $it)") }
        append("; turning the screen on when the wake word is heard is")
        append(if (LockSetting.wakeScreen(app)) " on" else " off")
        append("; automatic unlock is ${if (LockSetting.autoUnlock(app)) "on" else "off"}")
        append(", with ${(LockTries.LIMIT - LockSetting.tries(app)).coerceAtLeast(0)} attempt(s) left")
        append("; the privileged channel is ${if (connected) "up" else "not connected"}")
        lastReport?.let { append(". Last replay: $it") }
    }
}

/**
 * 一次重放的结果
 *
 * @property ok 手机的锁屏真的让开了没有
 * @property done 走完了几步
 * @property total 一共几步
 * @property detail 一句人话 (失败时停在第几步)
 * @property alreadyRunning 同一刻已经有一次在跑, 这一次根本没开始
 */
internal data class ReplayReport(
    val ok: Boolean,
    val done: Int,
    val total: Int,
    val detail: String,
    val alreadyRunning: Boolean,
)
