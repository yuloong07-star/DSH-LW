package io.github.miuzarte.littlewhale.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import io.github.miuzarte.littlewhale.MainActivity
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.host.DshHost
import io.github.miuzarte.littlewhale.host.HostStatus
import io.github.miuzarte.littlewhale.overlay.OverlayService
import io.github.miuzarte.littlewhale.tool.LwSpeech
import io.github.miuzarte.littlewhale.tool.LwWakeWord
import io.github.miuzarte.littlewhale.voice.AudioCapture
import io.github.miuzarte.littlewhale.voice.SpeechSegmenter
import io.github.miuzarte.littlewhale.voice.VoiceInbox
import io.github.miuzarte.littlewhale.voice.VoiceState
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 唤醒词现在什么样: 通道方法 `wakeword` 的 status 就读这里 */
internal object WakeWordState {
    @Volatile
    var listening: Boolean = false

    /** 现在守着的那几个词, 显示名那一半 (素云 / 小爱同学 …) */
    @Volatile
    var keywords: List<String> = emptyList()

    /** 从监听开始到现在命中过几次, 以及最后一次是什么时候、哪个词 */
    @Volatile
    var hits: Int = 0

    @Volatile
    var lastKeyword: String? = null

    @Volatile
    var lastHitAt: Long = 0

    @Volatile
    var startedAt: Long = 0

    /** 麦克风前台服务那条类型有没有被系统接下来: 退到 specialUse 时后台可能录不到音 */
    @Volatile
    var microphoneForeground: Boolean = false

    /**
     * 主人允许不允许"常驻语音" (设置页那个默认关的许可)
     *
     * 它与 [listening] 一起才能说清现在的状态: `listening = true, voiceActive = false` 是**只有
     * 唤醒词在守**那一个态, 而"设置页那个开关 = 常驻监听"那个错的表现正好是这两个永远同真同假
     */
    @Volatile
    var voiceAllowed: Boolean = false

    /** 常驻语音 (切段 + 出字) 现在真的在跑没有 —— 与 [listening] 是两件事 */
    @Volatile
    var voiceActive: Boolean = false

    /** 上一次失败的原因, 供 status 与日志共用 */
    @Volatile
    var lastError: String? = null
}

/**
 * 一直听着麦克风: 唤醒词在那一路音频上等着, 命中之后才把常驻语音铺开
 *
 * 这是「喊一声素云」的落点, 也是 2.0.0「一直听」的落点, **它是两层, 不是一层** —— 这一层分工
 * 是 2026-10-05 定下来的状态机 (见 `docs/wake-voice-states.md`):
 *
 * 1. **唤醒词那一路一直在守** (低功耗守门人): `KeywordSpotter` 与采集, 从服务起到服务停, 它是
 *    "允许唤醒"那个许可唯一的消费者, 也是 [WakeWordState.listening] 说的那件事
 * 2. **常驻语音那一路只在命中之后 / 主人明说允许时常驻** ([openVoice]): silero VAD 切段 +
 *    SenseVoice 出字, 它是 [VoiceState.capturing] 说的那件事, 闲置 [VOICE_IDLE_MS] 就收回去
 *
 * 改动之前这两层是焊在一起的 (起服务就把三块一起铺开, 于是设置页那个开关等于常驻监听), 所以
 * 现在的纪律是: **想开门只有两条路** —— 主人把"允许常驻语音"那个许可打开, 或者唤醒词命中一次
 *
 * 三件事与浮窗那套不同:
 *
 * 1. **它靠 sherpa-onnx 跑在本地**: 唤醒词是关键词检测 (KWS, 3.3M 参数的 zipformer), 切段是
 *    silero VAD, 出字是 SenseVoice —— 同一份 AAR、同一个 16 kHz 单声道音频, 一句话不出设备
 * 2. **前台服务, 类型是 microphone**: 后台一直开麦克风必须有这个类型, 而且起服务那一刻应用
 *    得在前台 (或握着 `SYSTEM_ALERT_WINDOW`, 见下面 [fend] 的注释)。通知栏留一条常驻, 上面
 *    一个「停止」按钮 —— 一直开着的麦克风必须有一眼看得见、一下就关得掉的地方
 * 3. **听到之后的动作是可配的**: 默认把应用提到前面, 也可以把浮窗叫起来 (见 [onWake])
 *
 * **采集只有一路** ([AudioCapture]): 唤醒词与切段是同一段音频的两个消费者, 两个消费者都在采集
 * 线程上跑, 而切出来的整段话交给另一条线程去认 —— 在采集线程上认一段话会卡住采集几百毫秒, 那就
 * 是丢音频,这条链有三个独立的"成不成" (采集 / 切段 / 出字), 任何一个不成都不该把别的带走: 缺
 * silero 模型时唤醒词照样好用, 只是没有"说一句话进会话", 那时 [VoiceState] 要如实说明是哪一条
 *
 * 关键词不接受中文原文: sherpa-onnx 的 keywords 文件里每一行是**模型的 token 序列**加一个
 * `@显示名` (见模型自带的 keywords.txt), EncodeKeywords 只认 token 表里有的符号。所以词表由
 * 宿主那一侧写进来 (lw_wakeword op=keywords), 这边只管读与校验
 */
class WakeWordService : Service() {

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var vad: SpeechSegmenter? = null
    private var capture: AudioCapture? = null
    private var transcriber: Thread? = null

    /** 切出来等着认的段: 上限是"设备认不过来时丢最新的", 而丢了多少要看得见 */
    private val segments = ArrayBlockingQueue<FloatArray>(SEGMENT_QUEUE)

    @Volatile
    private var transcribing = false

    @Volatile
    private var listening = false

    private var keywordsFile: File? = null
    private var modelDirectory: File? = null

    // 类型写出来是必须的: 这两个数从 Intent 那来的是 Double (extra 只有 double), 而 sherpa 的
    // 配置要的是 Float,原来靠 `= DEFAULT_THRESHOLD` 推出来的类型是 Double, 于是同一个文件里
    // ".toFloat() 赋给 Double 字段" 与 "Double 传给要 Float 的形参" 两处都过不了编译
    private var threshold = DEFAULT_THRESHOLD.toFloat()
    private var score = DEFAULT_SCORE.toFloat()
    private var onWake = WAKE_TO_APP
    private var vibrateMs = DEFAULT_VIBRATE_MS

    /**
     * 主人允许不允许常驻语音**留着** (那个默认关的许可)
     *
     * 名字里那个 `Residency` 是要紧的: 它管的是"这一路留不留着", 不是"能不能听到一句话" ——
     * 一次唤醒永远能听到一句话, 见 [openVoice], 开着的时候服务一起来就铺, 而且闲置超时不收
     */
    private var voiceResidency = false

    /**
     * 常驻语音现在真的在跑没有
     *
     * 它与 [WakeWordState.listening] 是**两件事**, 这正是这个类改动的核心: 唤醒词一直在守, 而常驻
     * 语音只在"主人允许 + 命中过 / 一起手就开着"时才在跑
     */
    @Volatile
    private var voiceActive = false

    /**
     * 这一次开门是不是"要留着"的
     *
     * 唤醒词命中开的那一次是**用完就收** (闲置超时收回去), 而"允许常驻语音"开的那一次要一直留着 ——
     * 两者走的都是 [openVoice], 差别只在这一个记号, 见 [openVoice] 与 [startWatchdog]
     */
    @Volatile
    private var voiceSticky = false

    /** 唤醒窗口: 命中唤醒词之后, 头一句出字要开一个新对话 (见 [deliver]) */
    @Volatile
    private var wakeWindow = false

    /** 常驻语音这一路最后一次活动的时刻, 闲置超时照着它算 */
    @Volatile
    private var voiceIdleSince = 0L

    private var watchdog: Thread? = null

    @Volatile
    private var watching = false

    /** 盯 `VoiceState.speaking` 那一跳的观察者 (通知栏那句"正在念回答"靠它) */
    private var speakingWatch: Thread? = null

    @Volatile
    private var watchingSpeech = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        fend()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 只收常驻语音那半条: 会话界面那个胶囊走这一条 (它代表的就是"常驻语音正在吃麦克风"),
        // 而唤醒词接着守 —— 喊一声就能把它要回来
        if (intent?.action == ACTION_STOP_VOICE) {
            runCatching { closeVoice() }
            runCatching { announce(listeningText()) }
            return START_STICKY
        }
        // 设置页刚改过许可: 不重启服务, 只把新值读进来 —— 打开"允许常驻语音"时顺手把它铺开,
        // 关掉时把已经在跑的那半条收回来 (两个方向都要管, 否则关掉之后它还在吃麦克风)
        if (intent?.action == ACTION_REFRESH) {
            val wanted = LwWakeWord.allowVoice(this)
            if (wanted == voiceResidency) return START_STICKY
            voiceResidency = wanted
            WakeWordState.voiceAllowed = wanted
            if (wanted) runCatching { openVoice(sticky = true) } else runCatching { closeVoice() }
            runCatching { announce(listeningText()) }
            Log.i(TAG, "always-listening voice permission is now $wanted")
            return START_STICKY
        }
        val directory = intent?.getStringExtra(EXTRA_MODEL_DIR)
        val keywords = intent?.getStringExtra(EXTRA_KEYWORDS_FILE)
        if (directory.isNullOrEmpty() || keywords.isNullOrEmpty()) {
            fail("the model directory and the keywords file both have to be handed over")
            return START_NOT_STICKY
        }
        modelDirectory = File(directory)
        keywordsFile = File(keywords)
        threshold = intent.getDoubleExtra(EXTRA_THRESHOLD, DEFAULT_THRESHOLD).toFloat()
        score = intent.getDoubleExtra(EXTRA_SCORE, DEFAULT_SCORE).toFloat()
        onWake = intent.getStringExtra(EXTRA_ON_WAKE) ?: WAKE_TO_APP
        vibrateMs = intent.getIntExtra(EXTRA_VIBRATE_MS, DEFAULT_VIBRATE_MS)
        voiceResidency = intent.getBooleanExtra(EXTRA_VOICE, false)
        // 服务重复起来时是"换一套参数重新开始", 不是再开一路: 先收掉上一个循环
        stopListening()
        if (!startListening()) return START_NOT_STICKY
        // 允许常驻语音时, 常驻那条链跟着服务一起开 —— 那时它才是真的"常驻" (主人明说允许了),
        // 而不是被那个开关顺带打开的
        if (voiceResidency) openVoice(sticky = true)
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    /**
     * 起识别器与麦克风, 失败时给一句人话
     *
     * 顺序是刻意的: 先看权限, 再建识别器 (它会读模型, 慢), 最后才开麦克风 —— 这样"缺权限"
     * 与"模型坏了"分得开, 而麦克风一旦开了就一定有人在读它
     *
     * 只有唤醒词那一条是"起不来就别听了" (它是这个服务的门槛, 也是主人按下那个开关的意图),切段
     * 与出字是两条**尽力而为**的附加链: silero 模型没下、识别模型没下、原生库起不来, 都只记在
     * [VoiceState] 里, 服务照常听着唤醒词 —— 反过来做就会变成"没下模型导致喊不醒"
     */
    private fun startListening(): Boolean {
        val directory = modelDirectory ?: return false
        val keywords = keywordsFile ?: return false
        if (!hasMicrophone()) {
            fail("the microphone permission is not granted, so there is nothing to listen with")
            return false
        }
        val model = wakeWordModelOf(directory)
        if (model == null) {
            fail("no encoder/decoder/joiner/tokens set was found in ${directory.absolutePath}")
            return false
        }
        if (!model.complete) {
            fail("the model in ${directory.absolutePath} is incomplete: ${model.describe()}")
            return false
        }
        if (!keywords.isFile || keywords.readLines().none { it.isNotBlank() }) {
            fail("the keywords file ${keywords.absolutePath} is missing or empty")
            return false
        }
        val spotter = try {
            KeywordSpotter(
                assetManager = null,
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = AudioCapture.SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = model.encoder.absolutePath,
                            decoder = model.decoder.absolutePath,
                            joiner = model.joiner.absolutePath,
                        ),
                        tokens = model.tokens.absolutePath,
                        numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 2),
                        modelType = "zipformer2",
                    ),
                    // 关键词走文件: 与 config 里那份默认词表是同一份, createStream 传空串就用它
                    keywordsFile = keywords.absolutePath,
                    keywordsScore = score,
                    keywordsThreshold = threshold,
                ),
            )
        } catch (error: Throwable) {
            fail("the keyword spotter would not come up: ${error.message ?: error}")
            return false
        }
        val stream = try {
            spotter.createStream("")
        } catch (error: Throwable) {
            spotter.release()
            fail("the keyword stream would not come up: ${error.message ?: error}")
            return false
        }
        this.spotter = spotter
        this.stream = stream
        val device = AudioCapture(::onCaptureError).also { capture = it }
        device.add(halfDuplex)
        if (!device.start()) {
            // 麦克风起不来是这条链的唯一硬失败: 谁来读都没有音频了
            stopListening()
            fail(VoiceState.lastError ?: "the microphone would not open")
            return false
        }
        listening = true
        WakeWordState.listening = true
        WakeWordState.keywords = keywords.readLines()
            .filter { it.isNotBlank() }
            .map { keywordName(it) }
        WakeWordState.startedAt = System.currentTimeMillis()
        WakeWordState.lastError = null
        WakeWordState.voiceAllowed = voiceResidency
        WakeWordState.voiceActive = false
        startWatchdog()
        startSpeakingWatch()
        announce(listeningText())
        Log.i(TAG, "listening for ${WakeWordState.keywords.joinToString()}, voice allowed: $voiceResidency")
        return true
    }

    /**
     * 把常驻语音那一路铺开: 切段器、识别器与认字线程
     *
     * **这是"常驻语音"唯一的开门口**, 而且只有两个调用方 —— [onStartCommand] 里主人明说允许常驻时
     * ([sticky] = true), 与 [hit] 里唤醒词命中时 ([sticky] = false), 改动之前它散在 [startListening]
     * 里, 于是"起服务"就等于"常驻", 那正是被修掉的那个错 (2026-10-05 主人定的状态机: 唤醒词是低功耗
     * 守门人, 命中之后才轮到常驻)
     *
     * **[sticky] 与"允许常驻语音"那个许可是两件事, 这一条是这次改动最容易写错的地方**:
     *
     * - **一次命中永远作数** ([sticky] = false): 唤醒词的全部意义就是"喊一声然后说话", 所以命中之后
     *   那一句话必须有人认 —— 哪怕"允许常驻语音"关着, 关着时命中仍然震动、响一声、把应用提到前面,
     *   **再给你一句话的机会**, 然后闲着 [VOICE_IDLE_MS] 收回去
     * - **只有"允许常驻语音"开着时才常驻** ([sticky] = true, 或 [voiceSticky] 记住的那一次): 那才是
     *   主人说的"一直听", 服务一起来就铺
     *
     * 换句话说: 那个许可管的是**这一路留不留着**, 不是"能不能听到一句话", 写成"关着就连命中都不开"
     * 会让这个功能自相矛盾 —— 喊醒了却听不见你说什么
     */
    private fun openVoice(sticky: Boolean = false) {
        if (voiceActive) return
        if (sticky) voiceSticky = true
        prepareSegmentation()
        prepareRecognition()
        startTranscribing()
        voiceActive = true
        voiceIdleSince = System.currentTimeMillis()
        VoiceState.capturing = true
        WakeWordState.voiceActive = true
        preheat()
        announce(listeningText())
        Log.i(
            TAG,
            "the always-listening voice chain is up (VAD ${VoiceState.vadReady}, ASR ${VoiceState.asrReady})," +
                " sticky: $voiceSticky",
        )
    }

    /**
     * 把常驻语音那一路收回去, 唤醒词接着守
     *
     * 与 [stopListening] 的分工: 那个是整件事收工 (唤醒词与麦克风一起), 这个只收常驻那半条 ——
     * 闲置超时到了就走这里, 而**麦克风不能停** (停了唤醒词也听不见了)
     *
     * 收尾的顺序与 [stopListening] 一致, 而且手里剩的那半句要 `flush()` 交出去 (不是 `abandon()`
     * 丢掉): 到这一刻主人可能刚把一整句话说完, 那半段正是有用的那句
     */
    private fun closeVoice() {
        if (!voiceActive) return
        voiceActive = false
        // 这个记号跟这一次开门同生共死: 下一次开门 (命中或服务起来) 会重新定
        voiceSticky = false
        vad?.let { segmenter ->
            runCatching { segmenter.flush() }
            runCatching { segmenter.close() }
        }
        vad = null
        VoiceState.pending = segments.size
        transcribing = false
        transcriber?.let { runCatching { it.join(WORKER_JOIN_MS) } }
        transcriber = null
        segments.clear()
        VoiceState.capturing = false
        WakeWordState.voiceActive = false
        reapRecognizer()
        Log.i(TAG, "the always-listening voice chain is down; only the wake word is listening now")
    }

    /**
     * 闲置太久就把常驻语音收掉, 回到只守唤醒词
     *
     * 没有这一条的话, 一次命中就等于"常驻语音一直挂着" —— 那是把"设置开关 = 常驻监听"那个错换个
     * 位置再犯一次, 判据是"最后一段出字之后过了 [VOICE_IDLE_MS] 还没有新的活动", 而**一次都没出过
     * 字时看的是开门那一刻**, 不然"开了门没人说话"会永远开着
     *
     * **[voiceSticky] 为真时不收**: 那是"允许常驻语音"开着的那个态, 主人的意思就是一直听 —— 超时只
     * 管唤醒词命中的那一次 (一次一句话的机会)
     */
    private fun startWatchdog() {
        if (watching) return
        watching = true
        watchdog = Thread({
            while (watching) {
                try {
                    Thread.sleep(WATCHDOG_MS)
                } catch (interrupted: InterruptedException) {
                    return@Thread
                }
                if (!voiceActive || voiceSticky) continue
                val idle = System.currentTimeMillis() - voiceIdleSince
                if (idle < VOICE_IDLE_MS) continue
                // 在采集线程之外收: closeVoice 会 join 认字线程, 在采集线程上等它就是把采集卡死
                runCatching { closeVoice() }
            }
        }, "lw-voice-idle").apply { start() }
    }

    private fun stopWatchdog() {
        watching = false
        watchdog?.let { runCatching { it.interrupt() } }
        watchdog = null
    }

    /**
     * 认出实例还回去
     *
     * 那 240 MB 不该在只守唤醒词的时候一直占着 (纪律: 触发条件是唤醒词, 那代价也该跟着它走)
     * 下一段要认出时 [LwSpeech.recognize] 会自己再建一个, 所以这里失败也不影响什么
     */
    private fun reapRecognizer() {
        runCatching { LwSpeech.dispatch(this, buildJsonObject { put("op", "release") }) }
    }

    /**
     * 半双工那道闸, 也是采集上唯一挂着的消费者
     *
     * 喇叭正在说话时**谁都不许吃音频**: 不采就没有"把自己的声音录回去"这回事, 顺带也解决了
     * 唤醒词被自己念的那句话叫醒 (走法 A, 主人 2026-10-05 定: 认下"念回答那几秒叫不醒"这个代价,
     * 换掉自唤醒的风险), 闸放在两个消费者之前 (而不是各自里面), 就是为了让"不许吃"只写一次
     *
     * **合上时要作废, 不只是丢样本**: 直接 `return` 只丢了新来的音频, 而 VAD 手里那半句还留着 ——
     * 几秒后 TTS 结束, 新音频接上去就切成"前半句 + 后半句"拼起来的一段, 那一段会被当成主人刚说的
     * 话, 所以合上的时候让切段器把手里的作废掉 ([SpeechSegmenter.abandon]), 下一次说话从干净的一段
     * 开始, 唤醒词那一路跟着一起哑 (A 走法要的就是这个), 这里不为它开例外
     */
    private val halfDuplex = AudioCapture.Sink { samples ->
        if (VoiceState.speaking) {
            // 在采集线程上: 这一句与 keywordSink / vad 那两个消费者是同一个线程, 所以与它们不冲突
            vad?.abandon()
            return@Sink
        }
        keywordSink.accept(samples)
        vad?.accept(samples)
    }

    /**
     * 唤醒词这一路消费者: 与原来那条循环一模一样, 只是音频从共享采集那来
     *
     * 它在采集线程上跑, 而 KWS 一次只认 100 ms (几十毫秒的活), 所以不会拖住采集
     */
    private val keywordSink = AudioCapture.Sink { samples ->
        val spotter = spotter ?: return@Sink
        val stream = stream ?: return@Sink
        stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val heard = spotter.getResult(stream).keyword
            if (heard.isNotBlank()) {
                // 命中之后必须立刻复位, 不然同一个词会被连着报好几次
                spotter.reset(stream)
                hit(heard)
            }
        }
    }

    /** 切段那一路消费者: 把整段话丢进队列就走, 不等识别 */
    private fun rememberSegment(samples: FloatArray) {
        VoiceState.segments += 1
        VoiceState.lastTruncated = vad?.lastWasTruncated ?: false
        if (!segments.offer(samples)) {
            // 队列满 = 这台设备认不过来,丢的是最新的那一段, 而丢了多少必须看得见
            VoiceState.dropped += 1
            Log.w(TAG, "the transcriber is behind: dropped a ${samples.size} sample segment")
        }
        VoiceState.pending = segments.size
    }

    /** 切段器: silero 模型不在或不对就只记原因, 不影响唤醒词 */
    private fun prepareSegmentation() {
        val path = LwSpeech.vadModel(this)
        val refusal = SpeechSegmenter.inspect(path)
        if (refusal != null) {
            VoiceState.vadReady = false
            VoiceState.vadDetail = refusal
            Log.w(TAG, refusal)
            return
        }
        val created = try {
            SpeechSegmenter.open(path, ::rememberSegment)
        } catch (error: Throwable) {
            VoiceState.vadReady = false
            VoiceState.vadDetail = "the silero VAD would not come up: ${error.message ?: error}"
            Log.w(TAG, VoiceState.vadDetail)
            return
        }
        vad = created
        VoiceState.vadReady = true
        VoiceState.vadDetail = "ready: ${SpeechSegmenter.MIN_SILENCE_SECONDS}s of silence ends a segment, "
            .plus("at most ${SpeechSegmenter.MAX_SPEECH_SECONDS}s each")
    }

    /** 出字那一段有没有模型: 没有就说清楚, 识别器那时**一个都不会建** (240 MB) */
    private fun prepareRecognition() {
        val ready = LwSpeech.ready(this)
        VoiceState.asrReady = ready
        VoiceState.asrDetail = if (ready) {
            "ready: the SenseVoice model is on disk and gets loaded by the first segment"
        } else {
            "the SenseVoice model is not downloaded yet: run lw_speech op=prepare once"
        }
        if (!ready) Log.w(TAG, VoiceState.asrDetail)
    }

    private fun onCaptureError(reason: String) {
        VoiceState.lastError = reason
        Log.w(TAG, reason)
    }

    private val preheating = AtomicBoolean(false)

    /**
     * 命中那一下把识别器先装进内存, 别等第一段
     *
     * SenseVoice 是 240 MB 量级, 第一次认要先把模型读进来 —— 那一下是一到几秒, 不预热的话这笔
     * 账正好落在**主人说完第一句话之后**: 他等的那段就凭空多出好几秒 (2026-10-05 主人反馈"说话
     * 结束到发送等得太久"里的一段), 命中之后他还要说一句话, 那几秒正好与说话重叠, 所以这里是
     * 拿时间换时间
     *
     * 三条纪律: **另起一条线程** (在采集线程上装模型就是几百毫秒不读麦克风, 唤醒词那一路会哑掉)、
     * **一次只有一个** ([preheating] 挡住连击)、**失败只记不抛** (识别模型没下时预热必然不成,
     * 而那不影响唤醒词 —— 与 [prepareRecognition] 那句"尽力而为"是同一件事)
     */
    private fun preheat() {
        if (!VoiceState.asrReady) return
        if (!preheating.compareAndSet(false, true)) return
        Thread({
            try {
                LwSpeech.warmUp(this)
                Log.i(TAG, "the recogniser is in memory, so the first segment will not wait for it")
            } catch (error: Throwable) {
                Log.w(TAG, "preheating the recogniser failed: ${error.message ?: error}")
            } finally {
                preheating.set(false)
            }
        }, "lw-voice-warmup").start()
    }

    /**
     * 认字那条线程: 一段一段地认, 一次一段
     *
     * 单独一条线程是必须的: SenseVoice 认一句要几百毫秒, 放在采集线程上就是几百毫秒不读麦克风,
     * 队列留着上限, 满时丢最新的那一段并计数 —— 悄悄丢比报错更难查
     */
    private fun startTranscribing() {
        if (transcribing) return
        transcribing = true
        transcriber = Thread({ transcribeLoop() }, "lw-voice-asr").apply { start() }
    }

    private fun transcribeLoop() {
        // 收工那一刻还要把队列里的认完: 主人停下来之前说的最后一句通常正是有用的那句
        while (transcribing || segments.isNotEmpty()) {
            val segment = try {
                segments.poll(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                return
            } ?: continue
            VoiceState.pending = segments.size
            val text = try {
                LwSpeech.recognize(this, segment)
            } catch (error: Throwable) {
                VoiceState.lastError = "recognising a segment failed: ${error.message ?: error}"
                Log.w(TAG, VoiceState.lastError!!, error)
                null
            } ?: continue
            if (text.isBlank()) continue
            VoiceState.recognized += 1
            deliver(text)
        }
    }

    /**
     * 出字之后去哪儿: 投进队列, 由宿主那侧读走并送进会话
     *
     * 这一句只做"投出去"这一件事, 而**投没投进去要如实说**: 写失败 (磁盘满 / 目录建不出来) 与
     * 投出去了对主人是两回事, 所以失败时状态里留下原因, 通知栏也照它改
     *
     * **唤醒之后的头一句要带一个记号** (主人 2026-10-05 定: 命中唤醒词就开一个新对话): 那个记号由
     * 宿主那侧的 `voiceDeliver` 认, 认到就跳过"正在跑的轮优先"那条规矩直接新建一个会话, 记号只给
     * **命中之后的第一句** —— 一句话被 VAD 切成两段时要是两段都带, 就会开出两个对话
     */
    private fun deliver(text: String) {
        VoiceState.lastText = text
        VoiceState.lastAt = System.currentTimeMillis()
        voiceIdleSince = System.currentTimeMillis()
        // 读一次就消费掉: 头一句之后回到老规矩 (投给最近动过的那个会话)
        val fresh = wakeWindow
        wakeWindow = false
        val seq = VoiceInbox.append(this, text, SOURCE_VOICE, fresh)
        if (seq == null) {
            VoiceState.lastError = "could not queue \"$text\": the voice inbox file is not writable"
            Log.w(TAG, VoiceState.lastError!!)
        } else {
            VoiceState.delivered += 1
            VoiceState.lastSeq = seq
            Log.i(
                TAG,
                "segment ${VoiceState.recognized} queued as #$seq" +
                    (if (fresh) " (this one opens a new conversation)" else "") + ": $text",
            )
        }
        runCatching { announce(listeningText()) }
    }

    /**
     * 听到了一次
     *
     * 七件事都做, 顺序是"先让人知道, 再叫起来": 震动与那一声短提示音是当场的手感, 通知是事后看得
     * 见的记录, **开常驻语音**是"唤醒之后才轮到它"那一步, 预热识别器是给随后那句话省时间, 唤起
     * 才是这个功能的目的, 任何一步失败都不该把监听带走, 所以各自 runCatching
     */
    private fun hit(keyword: String) {
        WakeWordState.hits += 1
        WakeWordState.lastKeyword = keyword
        WakeWordState.lastHitAt = System.currentTimeMillis()
        Log.i(TAG, "heard $keyword (${WakeWordState.hits} so far)")
        if (vibrateMs > 0) runCatching { buzz(vibrateMs.toLong()) }
        // 那一声短提示音要占住"半双工"那一小段: 不占的话它会被麦克风录进去, 而主人紧接着说的
        // 第一句话就带着一声"嘀"进识别器, 占着的那一百多毫秒里采集照跑、只是 VAD 那边作废
        runCatching {
            VoiceState.speaking = true
            try {
                beep(BEEP_MS)
            } finally {
                VoiceState.speaking = false
            }
        }
        runCatching { announce(heardText(keyword)) }
        // 唤醒词是常驻语音的开门条件 (状态机那条 ②), 而**这一句的机会永远给**: 命中之后头一句话要
        // 开一个新对话, 要把那句话认出来就得把切段与出字铺开, 所以这里不看那个许可 —— 许可管的是
        // "这一路留不留着" (sticky), 不是"能不能听到一句话", 写成"关着就不开"会让这个功能自相矛盾:
        // 喊醒了却听不见你说什么, 预热识别器在 openVoice 里做 (开了门才有意义)
        wakeWindow = true
        runCatching { openVoice(sticky = false) }
        runCatching { wake(keyword) }
    }

    /** 听到之后把谁叫起来: 浮窗优先, 浮窗起不来就回到应用 */
    private fun wake(keyword: String) {
        val host = DshHost.status
        if (onWake == WAKE_TO_OVERLAY && host is HostStatus.Running && Settings.canDrawOverlays(this)) {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW
                putExtra(OverlayService.EXTRA_URL, host.url)
            }
            ContextCompat.startForegroundService(this, intent)
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(EXTRA_HEARD, keyword)
        }
        // 从后台把一个 Activity 提到前台, 官方豁免之一就是握着 SYSTEM_ALERT_WINDOW;
        // 这台机器上没有悬浮窗授权的构建会在这里被拦, 那就只剩通知栏那一条路
        startActivity(intent)
    }

    private fun stopListening() {
        listening = false
        stopWatchdog()
        stopSpeakingWatch()
        // 常驻那半条先收: 它自己的收尾要等麦克风还开着 (flush 出来的最后一段得有人认), 而它一收
        // 就没人再吃采样的切段那一路了
        runCatching { closeVoice() }
        // 采集再停: 它同时喂着唤醒词与切段, 反过来的话手上那半段话会被下一帧接上, 切成一段怪的
        capture?.let { device ->
            device.remove(halfDuplex)
            device.stop()
        }
        capture = null
        stream?.let { runCatching { it.release() } }
        stream = null
        spotter?.let { runCatching { it.release() } }
        spotter = null
        wakeWindow = false
        VoiceState.capturing = false
        WakeWordState.listening = false
        WakeWordState.voiceActive = false
    }

    /**
     * 半双工那道闸什么时候开合的, 通知栏也得跟着改
     *
     * [listeningText] 里有一句"正在念回答, 先不听" —— 那是主人点过名要如实说的状态 (纪律: 静音是
     * 一个状态, 不是一个看不见的副作用), 而那道闸是 `LwSpeak` 那一侧合上的, 它不会回来叫我们
     * 所以这里用一个 400 ms 的观察者盯 `VoiceState.speaking` 那一跳, **只在真的变了的时候**才重发
     * 通知 (念一句通常几秒, 所以这是几次通知, 不是几十次)
     *
     * 为什么不是让 `LwSpeak` 直接调一下: 那个类在 `tool` 包里, 通知是这边私有的东西, 从那边伸手
     * 要跨一个包再往回叫; 而这条链本来就在轮询好几个 `@Volatile` 状态量 (设置页那边也是一秒一次)
     */
    private fun startSpeakingWatch() {
        if (watchingSpeech) return
        watchingSpeech = true
        speakingWatch = Thread({
            var was = VoiceState.speaking
            while (watchingSpeech) {
                try {
                    Thread.sleep(SPEAKING_POLL_MS)
                } catch (interrupted: InterruptedException) {
                    return@Thread
                }
                val now = VoiceState.speaking
                if (now == was) continue
                was = now
                runCatching { announce(listeningText()) }
            }
        }, "lw-voice-notify").apply { start() }
    }

    private fun stopSpeakingWatch() {
        watchingSpeech = false
        speakingWatch?.let { runCatching { it.interrupt() } }
        speakingWatch = null
    }

    private fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun buzz(ms: Long) {
        val manager = getSystemService(VibratorManager::class.java)
        val device = manager?.defaultVibrator ?: getSystemService(Vibrator::class.java)
        device?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /**
     * 命中那一声短提示音
     *
     * 手机可能扣在桌上或者静音, 所以它不是唯一的反馈 (震动与通知都在), 但它是"当场就听见"的那一条。
     * 造一个用完就放: ToneGenerator 会占着音频输出, 而命中一次与下一次之间可能隔着几小时 —— 留着
     * 一个常驻的实例换不来什么。放的那一下要等它响完, 所以放在另一条线程上睡一下再放, 不挡命中
     * 之后的唤起
     */
    private fun beep(ms: Int) {
        val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
        generator.startTone(ToneGenerator.TONE_PROP_BEEP, ms)
        Thread {
            runCatching { Thread.sleep(ms + 200L) }
            runCatching { generator.release() }
        }.start()
    }

    /**
     * 前台服务: 类型里必须带 microphone
     *
     * 后台起步时带 while-in-use 类型的那一半可能被系统拒 (Android 14 起从后台起麦克风前台服务
     * 是受限的, 持有 `SYSTEM_ALERT_WINDOW` 是豁免之一, 但不是所有 ROM 都认), 那就退到 specialUse
     * 并把代价记在状态里 —— 前台时麦克风照常, 退到后台可能就拿不到了。这一条与 OverlayService
     * 是同一个写法, 只是那边丢掉的是浮窗里的录音, 这边丢掉的是整件事本身
     */
    private fun fend() {
        val both = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(listeningText()), both)
            WakeWordState.microphoneForeground = true
        } catch (error: Throwable) {
            Log.w(TAG, "the microphone foreground type was refused, falling back to specialUse", error)
            WakeWordState.microphoneForeground = false
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(listeningText()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }
    }

    /**
     * 通知栏那一行: 唤醒词与常驻语音各自的状态都在这里, 而且必须分得开
     *
     * 这条通知是"一直开着的麦克风"唯一的可见处 (纪律: 一眼看得见、一下就关得掉), 所以它要说清
     * **哪几条链真的在跑**, 三种组合各有各的说法: 只有唤醒词在守 / 唤醒词 + 常驻语音 / 正在念回答
     * (那一小段半双工里谁都不听, 是主人点过名要如实说的状态, 不是一个看不见的副作用)
     *
     * 改动之前这里无条件说"常驻识别在跑", 而那时它确实无条件在跑 —— 所以"界面替人宣称"与"代码
     * 顺带打开"是同一个错的两面, 分开之后两边都要跟着分开说
     */
    private fun listeningText(): String {
        val words = WakeWordState.keywords
        val base = if (words.isEmpty()) "正在听着麦克风" else "正在听「${words.joinToString("」「")}」"
        // 正在念回答: 这期间唤醒词与常驻语音都被那道半双工的闸关着, 叫不醒
        if (VoiceState.speaking) return "$base · 正在念回答, 先不听"
        // 只有唤醒词在守 (纯 KWS 态): 这里一个字都不该提"常驻"
        if (!voiceActive) {
            return when {
                // 那个许可开着而这一路没起来: 缺件要说清是哪一件
                voiceResidency && !VoiceState.vadReady -> "$base · 常驻语音没就绪"
                voiceResidency && !VoiceState.asrReady -> "$base · 识别模型还没下"
                voiceResidency -> base
                // 许可是关着的: 这时要说清"还能说一句" —— 不然人会以为喊了也没用
                else -> "$base · 命中之后可以讲一句话"
            }
        }
        // 开着的时候分两种: 常驻 (许可靠着) 与"命中之后那一句的机会"(用完闲置超时就收)
        val how = if (voiceSticky) "常驻语音在跑" else "听着你说这一句"
        val heard = VoiceState.lastText
        return when {
            heard != null -> "$base · $how · 刚听到: $heard"
            !VoiceState.vadReady -> "$base · $how · 切段没就绪"
            !VoiceState.asrReady -> "$base · $how · 识别模型还没下"
            else -> "$base · $how"
        }
    }

    private fun heardText(keyword: String): String = "听到「$keyword」"

    /** 通知栏那一条常驻: 文字随状态走, 上面一个「停止」 */
    private fun announce(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "唤醒词", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun fail(reason: String) {
        WakeWordState.lastError = reason
        WakeWordState.listening = false
        Log.w(TAG, reason)
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "io.github.miuzarte.littlewhale.wake.STOP"

        /** 只收常驻语音那半条 (会话界面那个胶囊), 唤醒词接着守 */
        const val ACTION_STOP_VOICE = "io.github.miuzarte.littlewhale.wake.STOP_VOICE"

        /** 设置页改过许可之后来这一条: 服务不重启, 只把新值读进来 */
        const val ACTION_REFRESH = "io.github.miuzarte.littlewhale.wake.REFRESH"
        const val EXTRA_MODEL_DIR = "modelDir"
        const val EXTRA_KEYWORDS_FILE = "keywordsFile"
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_SCORE = "score"
        const val EXTRA_ON_WAKE = "onWake"
        const val EXTRA_VIBRATE_MS = "vibrateMs"

        /** 主人允许不允许"常驻语音" (那条默认关的许可): 它只决定命中之后要不要铺开切段与出字 */
        const val EXTRA_VOICE = "voice"

        /** 听到之后干什么: 把应用提到前面, 或者把浮窗叫起来 */
        const val WAKE_TO_APP = "app"
        const val WAKE_TO_OVERLAY = "overlay"

        /** 从 MainActivity 那一侧读得到的最后听到的词, 供界面显示 */
        const val EXTRA_HEARD = "heard"

        /** 投递给宿主那行话的来源标记: 会话里据此看得出这是说出来的, 不是打字的 */
        const val SOURCE_VOICE = "voice"

        /** 关键词检测是 16 kHz / 80 维 fbank, 与模型训练时那几个数对不上就什么都听不出来 */
        private const val FEATURE_DIM = 80

        /** sherpa-onnx 的缺省值, 与它自己文档里那组一致: 分数越低越容易触发, 阈值越低越容易报 */
        private const val DEFAULT_THRESHOLD = 0.25
        private const val DEFAULT_SCORE = 1.5
        private const val DEFAULT_VIBRATE_MS = 200

        /** 命中那一声短提示音: 一个系统自带的气泡音, 够短也够认得出, 音量取 ToneGenerator 的 0..100 */
        private const val BEEP_MS = 120
        private const val TONE_VOLUME = 80

        /**
         * 等着认的段最多几段
         *
         * 一段最长 15 s, 而认一段是几百毫秒到几秒 —— 8 段是"这台设备明显认不过来"的那个量级,
         * 到那时丢新的并计数, 而不是让内存跟着一起涨
         */
        private const val SEGMENT_QUEUE = 8

        /** 转写线程取队列的等待: 只影响收工那一刻的响应, 不影响延迟 */
        private const val POLL_MS = 200L

        /**
         * 常驻语音闲置多久就收回去, 回到只守唤醒词
         *
         * 主人 2026-10-05 定的: 先定 20 s, 真机上看过之后改成 **10 s** —— 这个数是"说完最后一句之后
         * 它还在吃麦克风"的那段时间, 越短越省电与隐私 (而它只影响"想一下再说第二句"能拖多久)。
         * 没有这一条的话一次命中就等于常驻一直挂着, 那是把"设置开关 = 常驻监听"那个错换个位置再犯
         *
         * 这一条**只管唤醒词命中的那一次**; "允许常驻语音"开着时 ([voiceSticky]) 看门狗不收回
         */
        private const val VOICE_IDLE_MS = 10_000L

        /** 看门狗的检查节拍: 两秒一次, 比超时值小一个量级就够, 它只读内存里的两个数 */
        private const val WATCHDOG_MS = 2_000L

        /**
         * 盯 `VoiceState.speaking` 的节拍
         *
         * 400 ms 是"半双工那道闸开合"能被看见、又不至于刷通知的那个量级: 念一句通常几秒, 所以一次
         * 朗读只换来两条通知 (开始念 / 念完了)
         */
        private const val SPEAKING_POLL_MS = 400L

        private const val WORKER_JOIN_MS = 2000L

        private const val TAG = "LwWakeWord"
        private const val CHANNEL_ID = "lw-wake"

        /** 与浮窗 (3) 和工具通知分开的第三条常驻 */
        private const val NOTIFICATION_ID = 4
    }
}
