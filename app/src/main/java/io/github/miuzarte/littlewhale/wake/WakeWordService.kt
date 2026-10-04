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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import java.io.File

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
 * 一直听着麦克风, 听到唤醒词就叫一声
 *
 * 这是「喊一声素云」的落点。三件事与浮窗那套不同:
 *
 * 1. **它靠 sherpa-onnx 的关键词检测 (KWS) 跑在本地**: 3.3M 参数的 zipformer, 16 kHz 单声道,
 *    一句话不出设备。识别器与模型是 `LwSpeech` 那套的同一份 AAR, 只是换了 `KeywordSpotter`
 *    这一个类
 * 2. **前台服务, 类型是 microphone**: 后台一直开麦克风必须有这个类型, 而且起服务那一刻应用
 *    得在前台 (或握着 `SYSTEM_ALERT_WINDOW`, 见下面 [fend] 的注释)。通知栏留一条常驻, 上面
 *    一个「停止」按钮 —— 一直开着的麦克风必须有一眼看得见、一下就关得掉的地方
 * 3. **听到之后的动作是可配的**: 默认把应用提到前面, 也可以把浮窗叫起来 (见 [onWake])
 *
 * 关键词不接受中文原文: sherpa-onnx 的 keywords 文件里每一行是**模型的 token 序列**加一个
 * `@显示名` (见模型自带的 keywords.txt), EncodeKeywords 只认 token 表里有的符号。所以词表由
 * 宿主那一侧写进来 (lw_wakeword op=keywords), 这边只管读与校验
 */
class WakeWordService : Service() {

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var listening = false

    private var keywordsFile: File? = null
    private var modelDirectory: File? = null
    private var threshold = DEFAULT_THRESHOLD
    private var score = DEFAULT_SCORE
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
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
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
        val microphone = try {
            openMicrophone()
        } catch (error: Throwable) {
            stream.release()
            spotter.release()
            fail("the microphone would not open: ${error.message ?: error}")
            return false
        }
        this.spotter = spotter
        this.stream = stream
        recorder = microphone
        listening = true
        WakeWordState.listening = true
        WakeWordState.keywords = keywords.readLines()
            .filter { it.isNotBlank() }
            .map { keywordName(it) }
        WakeWordState.startedAt = System.currentTimeMillis()
        WakeWordState.lastError = null
        microphone.startRecording()
        worker = Thread({ listen(spotter, stream, microphone) }, "lw-wake-word").apply { start() }
        announce(listeningText())
        Log.i(TAG, "listening for ${WakeWordState.keywords.joinToString()}")
        return true
    }

    /** 100 ms 一段读进来交给识别器, 与 sherpa-onnx 那个 Android 样例同一个尺寸 */
    private fun listen(spotter: KeywordSpotter, stream: OnlineStream, microphone: AudioRecord) {
        val buffer = ShortArray(SAMPLE_RATE / 10)
        while (listening) {
            val read = try {
                microphone.read(buffer, 0, buffer.size)
            } catch (error: Throwable) {
                fail("reading the microphone failed: ${error.message ?: error}")
                return
            }
            if (read <= 0) continue
            val samples = FloatArray(read) { buffer[it] / 32768.0f }
            stream.acceptWaveform(samples, SAMPLE_RATE)
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
        worker?.let { runCatching { it.join(WORKER_JOIN_MS) } }
        worker = null
        recorder?.let { device ->
            runCatching { device.stop() }
            runCatching { device.release() }
        }
        recorder = null
        stream?.let { runCatching { it.release() } }
        stream = null
        spotter?.let { runCatching { it.release() } }
        spotter = null
        WakeWordState.listening = false
    }

    private fun openMicrophone(): AudioRecord {
        val bytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (bytes <= 0) throw IllegalStateException("this device reports no usable microphone buffer")
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            // 两倍是留一段余量: 一段读 100 ms, 缓冲只有一段长就会被读空
            bytes * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("the audio recorder came back uninitialized")
        }
        return record
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

    private fun listeningText(): String {
        val words = WakeWordState.keywords
        return if (words.isEmpty()) "正在听着麦克风" else "正在听「${words.joinToString("」「")}」"
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

        /** 关键词检测是 16 kHz / 80 维 fbank, 与模型训练时那几个数对不上就什么都听不出来 */
        private const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** sherpa-onnx 的缺省值, 与它自己文档里那组一致: 分数越低越容易触发, 阈值越低越容易报 */
        private const val DEFAULT_THRESHOLD = 0.25
        private const val DEFAULT_SCORE = 1.5
        private const val DEFAULT_VIBRATE_MS = 200

        private const val WORKER_JOIN_MS = 2000L

        private const val TAG = "LwWakeWord"
        private const val CHANNEL_ID = "lw-wake"

        /** 与浮窗 (3) 和工具通知分开的第三条常驻 */
        private const val NOTIFICATION_ID = 4
    }
}
