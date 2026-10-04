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
import io.github.miuzarte.littlewhale.voice.AudioCapture
import io.github.miuzarte.littlewhale.voice.SpeechSegmenter
import io.github.miuzarte.littlewhale.voice.VoiceInbox
import io.github.miuzarte.littlewhale.voice.VoiceState
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

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

    /** 上一次失败的原因, 供 status 与日志共用 */
    @Volatile
    var lastError: String? = null
}

/**
 * 一直听着麦克风: 唤醒词在那一路音频上等着, 切段与识别在同一条音频上跑
 *
 * 这是「喊一声素云」的落点, 也是 2.0.0「一直听」的落点。三件事与浮窗那套不同:
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
        // 服务重复起来时是"换一套参数重新开始", 不是再开一路: 先收掉上一个循环
        stopListening()
        if (!startListening()) return START_NOT_STICKY
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
        prepareSegmentation()
        prepareRecognition()
        val device = AudioCapture(::onCaptureError).also { capture = it }
        device.add(halfDuplex)
        if (!device.start()) {
            // 麦克风起不来是这条链的唯一硬失败: 谁来读都没有音频了
            stopListening()
            fail(VoiceState.lastError ?: "the microphone would not open")
            return false
        }
        startTranscribing()
        listening = true
        WakeWordState.listening = true
        WakeWordState.keywords = keywords.readLines()
            .filter { it.isNotBlank() }
            .map { keywordName(it) }
        WakeWordState.startedAt = System.currentTimeMillis()
        WakeWordState.lastError = null
        VoiceState.capturing = true
        announce(listeningText())
        Log.i(TAG, "listening for ${WakeWordState.keywords.joinToString()}")
        return true
    }

    /**
     * 半双工那道闸, 也是采集上唯一挂着的消费者
     *
     * 喇叭正在说话时**谁都不许吃音频**: 不采就没有"把自己的声音录回去"这回事, 顺带也解决了
     * 唤醒词被自己念的那句话叫醒,闸放在两个消费者之前 (而不是各自里面), 就是为了让"不许吃"
     * 只写一次
     */
    private val halfDuplex = AudioCapture.Sink { samples ->
        if (VoiceState.speaking) return@Sink
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
     */
    private fun deliver(text: String) {
        VoiceState.lastText = text
        VoiceState.lastAt = System.currentTimeMillis()
        val seq = VoiceInbox.append(this, text, SOURCE_VOICE)
        if (seq == null) {
            VoiceState.lastError = "could not queue \"$text\": the voice inbox file is not writable"
            Log.w(TAG, VoiceState.lastError!!)
        } else {
            VoiceState.delivered += 1
            VoiceState.lastSeq = seq
            Log.i(TAG, "segment ${VoiceState.recognized} queued as #$seq: $text")
        }
        runCatching { announce(listeningText()) }
    }

    /**
     * 听到了一次
     *
     * 三件事都做, 顺序是"先让人知道, 再叫起来": 震动是当场的手感, 通知是事后看得见的记录,
     * 唤起才是这个功能的目的。任何一步失败都不该把监听带走, 所以各自 runCatching
     */
    private fun hit(keyword: String) {
        WakeWordState.hits += 1
        WakeWordState.lastKeyword = keyword
        WakeWordState.lastHitAt = System.currentTimeMillis()
        Log.i(TAG, "heard $keyword (${WakeWordState.hits} so far)")
        if (vibrateMs > 0) runCatching { buzz(vibrateMs.toLong()) }
        runCatching { announce(heardText(keyword)) }
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
        // 采集先停: 它同时喂着唤醒词与切段, 反过来的话手上那半段话会被下一帧接上, 切成一段怪的
        capture?.let { device ->
            device.remove(halfDuplex)
            device.stop()
        }
        capture = null
        // 收尾: 手里剩的那半句交出去, 再由转写线程认完 (它的循环要等队列空了才退)
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
        stream?.let { runCatching { it.release() } }
        stream = null
        spotter?.let { runCatching { it.release() } }
        spotter = null
        WakeWordState.listening = false
        VoiceState.capturing = false
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
     * 通知栏那一行: 唤醒词与常驻链的状态都在这里
     *
     * 这条通知是"一直开着的麦克风"唯一的可见处 (纪律: 一眼看得见、一下就关得掉), 所以它要说清
     * 三件事里的哪几件真的在跑 —— 只有唤醒词在听、切段没就绪、刚认出来什么
     */
    private fun listeningText(): String {
        val words = WakeWordState.keywords
        val base = if (words.isEmpty()) "正在听着麦克风" else "正在听「${words.joinToString("」「")}」"
        val heard = VoiceState.lastText
        return when {
            heard != null -> "$base · 刚听到: $heard"
            !VoiceState.vadReady -> "$base · 切段没就绪"
            !VoiceState.asrReady -> "$base · 识别模型还没下"
            else -> "$base · 常驻识别在跑"
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
        const val EXTRA_MODEL_DIR = "modelDir"
        const val EXTRA_KEYWORDS_FILE = "keywordsFile"
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_SCORE = "score"
        const val EXTRA_ON_WAKE = "onWake"
        const val EXTRA_VIBRATE_MS = "vibrateMs"

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

        /**
         * 等着认的段最多几段
         *
         * 一段最长 15 s, 而认一段是几百毫秒到几秒 —— 8 段是"这台设备明显认不过来"的那个量级,
         * 到那时丢新的并计数, 而不是让内存跟着一起涨
         */
        private const val SEGMENT_QUEUE = 8

        /** 转写线程取队列的等待: 只影响收工那一刻的响应, 不影响延迟 */
        private const val POLL_MS = 200L

        private const val WORKER_JOIN_MS = 2000L

        private const val TAG = "LwWakeWord"
        private const val CHANNEL_ID = "lw-wake"

        /** 与浮窗 (3) 和工具通知分开的第三条常驻 */
        private const val NOTIFICATION_ID = 4
    }
}
