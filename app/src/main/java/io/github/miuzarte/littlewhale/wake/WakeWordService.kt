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
import io.github.miuzarte.littlewhale.overlay.BallSpot
import io.github.miuzarte.littlewhale.overlay.OverlayService
import io.github.miuzarte.littlewhale.overlay.OverlayState
import io.github.miuzarte.littlewhale.tool.LwSpeech
import io.github.miuzarte.littlewhale.tool.LwWakeWord
import io.github.miuzarte.littlewhale.voice.AudioCapture
import io.github.miuzarte.littlewhale.voice.SpeechSegmenter
import io.github.miuzarte.littlewhale.voice.VoiceCommands
import io.github.miuzarte.littlewhale.voice.VoiceInbox
import io.github.miuzarte.littlewhale.voice.VoiceRoute
import io.github.miuzarte.littlewhale.voice.VoiceState
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 唤醒词现在什么样: 通道方法 `wakeword` 的 status 就读这里
 *
 * **2026-10-06 起常驻语音只允许视频模式触发** (主人定的口径): 设置页那个「允许常驻语音」与浮标
 * 菜单里那行「一直听」都删掉了, 而 [voiceAllowed] / [voiceActive] 这两个字段留着 —— 它们说的是
 * "视频模式那个记号在不在"与"识别链此刻留没留着", 是排查"切回手机模式之后它还在吃麦克风"这类问题
 * 唯一的读数
 */
internal object WakeWordState {
    @Volatile
    var listening: Boolean = false

    /** 现在守着的那几个词, 显示名那一半 (肥鱼肥鱼 / 小爱同学 …), 同名的只留一个 */
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
     * 视频模式那个记号开着没有 (常驻语音唯一的开门条件)
     *
     * 它与 [listening] 一起才能说清现在的状态: `listening = true, voiceAllowed = false` 是**只有
     * 唤醒词在守**那一个态 (常态), 而开了视频模式之后这两个会同时为真
     */
    @Volatile
    var voiceAllowed: Boolean = false

    /** 识别链此刻留没留着 (视频模式那一档, 或者刚买来的那一句话) —— 与 [listening] 是两件事 */
    @Volatile
    var voiceActive: Boolean = false

    /**
     * **省电模式此刻生不生效** (主人 2026-10-07): 手动那个开关, 或者设置页定的那一段定时
     *
     * 它说的是"麦克风关着, 喊不醒"这一件事 —— 而服务本身、通知、浮标与 host 都还在, 点球也能临时
     * 借一次麦克风说一句。它与 [listening] 是两件事: 省电模式里服务活着而 [listening] 是 false
     */
    @Volatile
    var powerSave: Boolean = false

    /** 上一次失败的原因, 供 status 与日志共用 */
    @Volatile
    var lastError: String? = null
}

/**
 * 半双工那道闸: 这一帧音频该怎么走
 *
 * **走法 A** (主人 2026-10-05 定): 喇叭正在说话时**谁都不许吃音频** —— 不采就没有"把自己的声音录回
 * 去"这回事, 顺带也解决了唤醒词被自己念的那句话叫醒。合闸的代价是"念回答那几秒叫不醒", 那是认下来
 * 的取舍
 *
 * **合上时要作废, 不只是丢样本**: 直接丢掉新来的音频, VAD 手里那半句还留着 —— 几秒后 TTS 结束,
 * 新音频接上去就切成"前半句 + 后半句"拼起来的一段, 而那一段会被当成主人刚说的话。所以合闸的那一帧
 * 要 [HalfDuplex.ABANDON] (让切段器把手里的作废掉), 而唤醒词那一路**跟着一起哑**: "这一帧不许吃"
 * 只写一处, 不为唤醒词开例外
 *
 * 两条判据是三条展开的 `if`, 所以它抽成一个纯函数 (`forward`) —— 没有设备也能量, 见 [HalfDuplexTest]
 */
internal object HalfDuplex {

    /** 喇叭在说话: 这一帧作废掉, 唤醒词与切段都不吃 */
    const val ABANDON = "abandon"

    /** 谁都没在说话: 喂给唤醒词与切段那两个消费者 */
    const val ACCEPT = "accept"

    fun forward(speaking: Boolean): String = if (speaking) ABANDON else ACCEPT
}

/**
 * "这一句话"的窗口该不该收了
 *
 * **两条都算一次活动** (主人 2026-10-06 定的): 出一句字 ([lastTextAt]), 或者 VAD 说这一窗有人声
 * ([lastVoiceAt])。只看前者的话有一条真机上够得着的错 —— 一口气不停顿地说得比 [limit] 还长时, VAD
 * 还没切出段 (要 0.8 s 静音, 或者到 15 s 上限才强切), 于是"正在说话"在计时的眼里等于"没人说话",
 * 看门狗从句子中间就把窗口收回了
 *
 * 抽成纯函数是为了没有设备也能一条条量, 见 [VoiceIdleTest]
 */
internal object VoiceIdle {

    /**
     * 从"最后一次活动"到现在够不够 [limit]
     *
     * 一次活动都没有过时两个时刻都是 0, 于是判据落在 0 上 —— 但调用方传进来的 [lastTextAt] 是
     * **开门那一刻** (见 [WakeWordService.openVoice]), 所以"开了门没人说话"照样会到期
     */
    fun expired(now: Long, lastTextAt: Long, lastVoiceAt: Long, limit: Long): Boolean =
        now - maxOf(lastTextAt, lastVoiceAt) >= limit
}

/**
 * 一直听着麦克风: 唤醒词在那一路音频上等着, 命中之后**开一次"说一句话"的窗口**
 *
 * 这是「喊一声唤醒词」的落点。**它只有一层** (2026-10-06 起): 唤醒词那一路从服务起到服务停一直在守
 * (低功耗守门人: `KeywordSpotter` 与采集, 是"允许唤醒"那个许可唯一的消费者, 也是
 * [WakeWordState.listening] 说的那件事), 而 silero VAD 切段 + SenseVoice 出字那一条**只在需要听
 * 一句话的时候才铺开** —— 唤醒词命中一次, 或者主人在球上点一下 ([listenNow])。
 *
 * **"常驻语音"整条链路已经删掉了**: 那是一个"允许常驻语音"的许可加上一条不收回的识别链, 结果是
 * 对话一直进行下去 (主人 2026-10-06 判定它多余)。现在开门只有上面那两条路, 而**两条都用完就收**:
 * 闲置 [VOICE_IDLE_MS] 之后 [startWatchdog] 把切段与出字收回去, 麦克风回到只喂唤醒词那一路。
 * 于是"谁在吃麦克风"这件事只剩两个态: 只有唤醒词在守 / 正在听你说的这一句。
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
class WakeWordService : Service() {    private var spotter: KeywordSpotter? = null
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
     * 命中之后除了叫醒还要做什么 (批次 4.4)
     *
     * 三个值由设置页那个「叫醒之后」定 ([LwWakeWord.onHit]): 只叫醒 ([LwWakeWord.HIT_WAKE], 缺省)、
     * 顺带切到视频模式、顺带切回手机模式。后两条在这里**只写一句命令进收件箱**, 真正切模式的是宿主
     * 插件那张命令表 —— 命令词表只有一份, 见 [io.github.miuzarte.littlewhale.voice.VoiceCommands]
     */
    private var onHit = LwWakeWord.HIT_WAKE

    /**
     * **视频模式要求把识别链留着** —— 常驻语音现在唯一的开门条件
     *
     * 主人 2026-10-06 定的: 常驻语音留着, 但**只允许视频模式触发它**。设置页那个「允许常驻语音」与
     * 浮标菜单里那行「一直听」都删掉了, 因为那两条路都能让人在手机模式下把它打开, 结果就是对话一直
     * 进行下去 —— 那是主人判定多余的东西。视频模式不一样: 那个模式本来就是"看着东西说话", 连着问
     * 才用得下去 (见 `assets/modes/video.md` 第一节)
     *
     * 判据是 [LwWakeWord.residentWanted] 读的那个记号 (`$DSH_HOME/modes/voice-resident.on`), 由切模式
     * 那一步写/删; 服务在 [onStartCommand] 与 [openVoice] 两处读它 —— 前者管"服务活着的时候切模式",
     * 后者管"命中那一刻它该不该留"
     */
    private var voiceResidency = false

    /**
     * **视频模式里说的话投给哪一场** (进场那一刻定格的, 见 [LwWakeWord.videoTarget])
     *
     * 它与 [voiceResidency] 是一对: 记号说"这一路要不要留着", 这一条说"留下来的这一路往哪儿投" ——
     * 主人 2026-10-07 的口径是"打开视频模式那句话与视频模式里接着说进的话要在同一场", 而"哪一场"
     * 只有页面知道 ([VoiceState.uiSession]), 所以切模式那一步把它定格在 `modes/video-voice.session` 里
     */
    @Volatile
    private var videoTarget: String? = null

    /**
     * "说一句话"那个窗口现在开着没有
     *
     * 它与 [WakeWordState.listening] 是**两件事**: 唤醒词一直在守, 而这一条说的是"切段与出字也铺开
     * 了、麦克风正被吃着"。开门有两个调用方 ([openForOneSentence] 与 [listenNow]), 而**收不收**
     * 看 [voiceSticky]: 一次命中买一句话 (用完由 [startWatchdog] 按 [VOICE_IDLE_MS] 收回去), 视频模式
     * 那一次要一直留着
     */
    @Volatile
    private var voiceActive = false

    /**
     * 这一次开门是不是"要留着"的
     *
     * 命中开的那一次是**用完就收**, 而视频模式要的那一次要一直留着 —— 两者走的都是 [openVoice],
     * 差别只在这一个记号, 见 [openVoice] 与 [startWatchdog]
     */
    @Volatile
    private var voiceSticky = false

    /** 唤醒窗口: 命中唤醒词之后, 头一句出字要带上"这是头一句"的记号 (见 [deliver]) */
    @Volatile
    private var wakeWindow = false

    /**
     * 这一次"说一句话"的窗口该投给哪一场 (**回复框点名的那一场**, 见 [openForOneSentence])
     *
     * 它只在开窗口那一刻取一次 ([OverlayState.replyTarget]), 与 [wakeWindow] 一起被头一句消费掉:
     * 框在屏上时, 点球与喊唤醒词说的那一句都回给"发出那条回复的会话" —— 宿主那侧把它排在时间那
     * 笔账前面。没有回复框时它是 null, 那一句照旧按 20 分钟那一档走
     */
    @Volatile
    private var voiceTarget: String? = null

    /** 这一句话的窗口最后一次**出字**的时刻, 与 [voiceHeardAt] 一起喂给 [VoiceIdle] */
    @Volatile
    private var voiceIdleSince = 0L

    /** 这一次开门里 VAD 最近一次说有人声的时刻, 0 = 还没听见过 (跟着 [openVoice] 归零) */
    @Volatile
    private var voiceHeardAt = 0L

    private var watchdog: Thread? = null

    @Volatile
    private var watching = false

    /** 盯 `VoiceState.speaking` 那一跳的观察者 (通知栏那句"正在念回答"靠它) */
    private var speakingWatch: Thread? = null

    @Volatile
    private var watchingSpeech = false

    /**
     * **省电模式此刻生不生效** (主人 2026-10-07: "增加省电模式开关, 及定时开关")
     *
     * 两个来源 ([LwWakeWord.powerSave]): 设置页那个手动开关, 或者主人自己定的一段定时 (`23:00-07:00`)。
     * 它生效时**只停唤醒词监听那一条** —— 麦克风整个关掉, 喊不醒; host / 浮标 / 通知都留着, 而点球
     * 那一条仍能临时借一次麦克风说一句话 ([listenNow])。
     *
     * 服务**不跟着停**: 定时那一段走完要有人把麦克风打开, 而那个"到点了"只有活着的服务知道
     * (见 [startPowerWatch])
     */
    @Volatile
    private var powerSave = false

    /** 省电模式的观察者: 定时那一段只能靠时间自己走, 见 [startPowerWatch] */
    private var powerWatch: Thread? = null

    @Volatile
    private var watchingPower = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 通知栏那一条的字要在 [fend] 之前就有真相 (省电模式里它写的是"麦克风关着")
        powerSave = runCatching { LwWakeWord.powerSave(this) }.getOrDefault(false)
        WakeWordState.powerSave = powerSave
        fend()
        startPowerWatch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 把这一句话的窗口收回来: 会话界面那个麦克风胶囊与球上"再点一下"走的都是这一条, 而唤醒词
        // 接着守 —— 喊一声 (或者再点一下球) 就能把下一次窗口要回来。**视频模式那个常驻档也走这里**:
        // 收掉之后它就是"只说一句"的档, 而视频模式下次开门时又把它铺开 (见 [residentWanted])
        if (intent?.action == ACTION_HUSH) {
            runCatching { closeVoice() }
            runCatching { announce(listeningText()) }
            return START_STICKY
        }
        // 浮标上点一下 (批次 4.2): 开一次"讲一句话"的窗口, 与命中走同一条路, 只是没有那个词
        if (intent?.action == ACTION_LISTEN_NOW) {
            listenNow()
            return START_STICKY
        }
        // 设置页改过"叫醒之后", 或者视频模式刚开关过: 不重启服务, 只把新值读进来 —— 打开常驻那个档
        // 时顺手把识别链铺开, 关掉时把已经在跑的那条收回来 (**两个方向都要管**, 否则切回手机模式之后
        // 它还在吃麦克风)
        if (intent?.action == ACTION_REFRESH) {
            onHit = LwWakeWord.onHit(this)
            vibrateMs = if (LwWakeWord.vibrate(this)) DEFAULT_VIBRATE_MS else 0
            // 省电模式那两个开关 (手动 / 定时那一段) 也在这一条上读 —— 设置页拨完当场生效
            val wantedPower = runCatching { LwWakeWord.powerSave(this) }.getOrDefault(false)
            if (wantedPower != powerSave) {
                applyPower(wantedPower)
                return START_STICKY
            }
            val wanted = residentWanted()
            if (wanted == voiceResidency) {
                // 记号没变也要重读一次定格: 主人可能又切了一次模式 (那一次会重新定格), 而缓存错过它
                // 的表现正是"视频模式里说的话落回浮标账本"
                refreshVideoTarget()
                return START_STICKY
            }
            voiceResidency = wanted
            refreshVideoTarget()
            WakeWordState.voiceAllowed = wanted
            // **省电模式里不铺常驻链**: 那个记号的意图仍被记着 (出了时段照旧恢复), 只是此刻不生效
            if (wanted && !powerSave) runCatching { openVoice() } else runCatching { closeVoice() }
            runCatching { announce(listeningText()) }
            Log.i(TAG, "video-mode residency is now $wanted")
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
        onHit = intent.getStringExtra(EXTRA_ON_HIT) ?: LwWakeWord.onHit(this)
        // 视频模式那个记号: 服务起来时读一次, 它是"这一路要不要留着"的唯一来源
        voiceResidency = residentWanted()
        refreshVideoTarget()
        powerSave = runCatching { LwWakeWord.powerSave(this) }.getOrDefault(false)
        WakeWordState.powerSave = powerSave
        // 服务重复起来时是"换一套参数重新开始", 不是再开一路: 先收掉上一个循环
        stopListening()
        if (powerSave) {
            // **省电模式: 服务与通知留着, 麦克风与唤醒词那一关着** (主人 2026-10-07)。点球那一条仍
            // 能用 ([listenNow] 里临时借一次), 而这一条链的看门狗照旧要跑 —— 借来的那一句说完也
            // 得有人把它收回去
            listening = false
            WakeWordState.listening = false
            WakeWordState.voiceActive = false
            startWatchdog()
            startSpeakingWatch()
            announce(listeningText())
            Log.i(TAG, "power save is on: the microphone stays closed until the ball asks for a sentence")
            return START_STICKY
        }
        if (!startListening()) return START_NOT_STICKY
        // 视频模式开着时, 那一条链跟着服务一起铺开 —— 那时它才是真的"留着" (那个模式要连着说),
        // 而不是被哪个开关顺带打开的
        if (voiceResidency) openVoice()
        return START_STICKY
    }

    override fun onDestroy() {
        stopPowerWatch()
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
        device.add(voiceSink)
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
            // 一张容错词表里好几条读音共用一个显示名: 不去重那句"正在听「…」"会把同一个词念好几遍
            .distinct()
        WakeWordState.startedAt = System.currentTimeMillis()
        WakeWordState.lastError = null
        WakeWordState.voiceAllowed = voiceResidency
        WakeWordState.voiceActive = false
        startWatchdog()
        startSpeakingWatch()
        announce(listeningText())
        Log.i(TAG, "listening for ${WakeWordState.keywords.joinToString()}, video residency: $voiceResidency")
        return true
    }

    /**
     * 把识别链铺开: 切段器、识别器与认字线程
     *
     * 两个调用方要的**不是同一件事**, 而差别就在这一次要不要留着:
     *
     * - **一次命中 / 球上点一下** ([openForOneSentence]): 用完就收, 闲置 [VOICE_IDLE_MS] 由
     *   [startWatchdog] 收回去
     * - **视频模式** ([residentWanted] 那个记号为真): 一直留着, 看门狗不收 —— 那个模式是"看着东西
     *   说话", 连着问才用得下去 (主人 2026-10-06 定的口径: 常驻语音只允许由视频模式触发)
     *
     * [sticky] 由调用方给, 但**开门之前还要再问一次记号** ([residentWanted]): "设置页那个开关 =
     * 常驻监听"那个错 (2026-10-05 修掉的) 的教训是"开门条件只能有一处说得清", 所以视频模式那条路
     * 命中的那一次也必须算常驻 —— 否则在视频模式里喊一声只买一句话, 下一句就得再喊一次
     */
    private fun openVoice(sticky: Boolean = false) {
        if (voiceActive) return
        // **省电模式里这一路永远不常驻** (主人 2026-10-07: 省电模式就是"麦克风别一直开着"): 视频模式
        // 那个记号此刻也不作数 —— 一句话说完 [closeVoice] 就把麦克风还回去, 出了时段再谈常驻
        if (!powerSave && (sticky || residentWanted())) voiceSticky = true
        prepareSegmentation()
        prepareRecognition()
        startTranscribing()
        voiceActive = true
        voiceIdleSince = System.currentTimeMillis()
        voiceHeardAt = 0L
        VoiceState.capturing = true
        WakeWordState.voiceActive = true
        preheat()
        announce(listeningText())
        Log.i(
            TAG,
            "the voice chain is up (VAD ${VoiceState.vadReady}, ASR ${VoiceState.asrReady}), sticky: $voiceSticky",
        )
    }

    /**
     * 把识别链收回去, 唤醒词接着守
     *
     * 与 [stopListening] 的分工: 那个是整件事收工 (唤醒词与麦克风一起), 这个只收那半条 —— 闲置超时
     * 到了、视频模式关掉了、或者主人手动收了, 都走这里, 而**麦克风不能停** (停了唤醒词也听不见了)
     *
     * **顺序是要紧的** (2026-10-06 的真机 crash 就是它错了一半): 先把手里那一份**交出去并放掉**,
     * 再收别的。切段器那半句要 `flush()` 交出去 (不是 `abandon()` 丢掉) —— 到这一刻主人可能刚把
     * 一整句话说完, 那半段正是有用的那句 —— 而 flush 与 release 都必须在**采集线程还可能在这一帧
     * 里转发**的前提下安全, 所以放在最前面: 那时 `vad` 还是个活的引用
     */
    private fun closeVoice() {
        if (!voiceActive) return
        voiceActive = false
        // 这个记号跟这一次开门同生共死: 下一次开门 (命中或视频模式) 会照着记号重新定
        // (先把它抄下来: 下面"省电模式里还麦克风"那一条要判"这一次是借来的还是常驻的")
        val wasSticky = voiceSticky
        voiceSticky = false
        flushVoice()
        VoiceState.pending = segments.size
        transcribing = false
        transcriber?.let { runCatching { it.join(WORKER_JOIN_MS) } }
        transcriber = null
        segments.clear()
        VoiceState.capturing = false
        WakeWordState.voiceActive = false
        reapRecognizer()
        // **这一次开门活到了收工**: 把"开窗口就把进程带走"那个记号清掉 (见 `LwWakeWord.speakNow`
        // 里的崩兜底) —— 不然连着正常用两句之后那个计数会一路涨到上限, 把好用的设备也锁住
        runCatching { LwWakeWord.noteVoiceClosed() }
        Log.i(TAG, "the voice chain is down again; only the wake word is listening now")
        // **省电模式里借来的那一句说完就把麦克风还回去** (主人 2026-10-07: "点球照样能说一句话"):
        // 借来的那一次开门是不常驻的 ([wasSticky] 为假), 所以这里就是"还"的那一刻。停的是采集与
        // 唤醒词那一路, 服务与通知都还在 —— [stopListening] 里那次 [closeVoice] 会因为 voiceActive
        // 已经是 false 而直接回头, 不会绕回来
        if (powerSave && listening && !wasSticky) {
            runCatching { stopListening() }
            runCatching { announce(listeningText()) }
            Log.i(TAG, "the borrowed sentence is over: the microphone is closed again (power save)")
        }
    }

    /**
     * 把这一句话的窗口交出去: **先把引用摘掉, 再动那个切段器**
     *
     * 这两句的顺序就是那条 use-after-free 的解药 ([voiceSink] 在采集线程上每帧读 `vad`):
     * `vad = null` 之后, 采集那一侧再怎么被调度也只会看到 null (干脆不转发); 而在那之前活着的
     * 那一个引用是**我们自己手里的这一份**, 所以 flush 与 release 都发生在"没有人还能拿到它"之后
     *
     * 与 `SpeechSegmenter.close()` 的幂等记号一起看: 那条保证重复收尾不会第二次碰原生
     */
    private fun flushVoice() {
        val segmenter = vad ?: return
        vad = null
        runCatching { segmenter.flush() }
        runCatching { segmenter.close() }
    }

    /**
     * 视频模式那个记号还在不在 (常驻语音唯一的开门条件)
     *
     * 读它而不是读一个字段, 是因为**服务可能停在切模式之前**: 视频模式开着的时候应用被杀掉再起来,
     * 服务得从磁盘上那份事实里恢复"这一路要留着", 而不是从 Intent 里 (那条路只在服务被显式起时才有)
     */
    private fun residentWanted(): Boolean = runCatching { LwWakeWord.residentWanted(this) }.getOrDefault(false)

    /**
     * 把"视频模式那一场"读进内存 ([videoTarget])
     *
     * 读它只有两个时机 —— 切模式那一拍 ([onStartCommand] 的 ACTION_REFRESH) 与服务起来那一拍 ——
     * 所以出字那一路 ([deliver]) 不必每一句都去开一次文件
     */
    private fun refreshVideoTarget() {
        videoTarget = if (voiceResidency) {
            runCatching { LwWakeWord.videoTarget(this) }.getOrNull()
        } else {
            null
        }
    }

    /**
     * 闲置太久就把这一句话的窗口收掉, 回到只守唤醒词
     *
     * 没有这一条的话, 一次命中就等于"识别链一直挂着" —— 麦克风一直吃、对话一直往下走, 那正是主人
     * 2026-10-06 点名不要的那件事。判据是"最后一段出字之后过了 [VOICE_IDLE_MS] 还没有新的活动", 而
     * **一次都没出过字时看的是开门那一刻**, 不然"开了门没人说话"会永远开着
     *
     * **[voiceSticky] 为真时不收**: 那是视频模式要的那一档 (主人明说那个模式要连着说), 超时只管
     * 一次命中 / 球上点一下买来的那一句
     */
    private fun startWatchdog() {
        // 出字与 VAD 说有人声都算一次活动 (见 [VoiceIdle], 2026-10-06 补的第二条): 只听出字的话,
        // 一口气说得比 [VOICE_IDLE_MS] 还长会被从句子中间收回
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
                val expired = VoiceIdle.expired(
                    now = System.currentTimeMillis(),
                    lastTextAt = voiceIdleSince,
                    lastVoiceAt = voiceHeardAt,
                    limit = VOICE_IDLE_MS,
                )
                if (!expired) continue
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
     * 省电模式的观察者: **定时那一段只能靠时间自己走**
     *
     * 手动那个开关是设置页当场发 `ACTION_REFRESH` 过来的 (那一条会立刻 [applyPower]), 而"23:00 到了"
     * 这件事没有任何人会举手 —— 所以要有一条自己的钟。周期取 [POWER_POLL_MS]: 读两个偏好加一个时刻
     * 比较, 代价可以忽略, 而"到点最多晚十几秒"对省电这件事无所谓
     */
    private fun startPowerWatch() {
        if (watchingPower) return
        watchingPower = true
        powerWatch = Thread({
            while (watchingPower) {
                try {
                    Thread.sleep(POWER_POLL_MS)
                } catch (interrupted: InterruptedException) {
                    return@Thread
                }
                val wanted = runCatching { LwWakeWord.powerSave(this) }.getOrDefault(powerSave)
                if (wanted == powerSave) continue
                runCatching { applyPower(wanted) }
            }
        }, "lw-power-save").apply { start() }
    }

    private fun stopPowerWatch() {
        watchingPower = false
        powerWatch?.let { runCatching { it.interrupt() } }
        powerWatch = null
    }

    /**
     * 省电模式开 / 关那一步 —— 手动开关与定时那一段**共用这一处**
     *
     * 关的那一半 (省电模式生效): 那一句话的窗口先收 ([stopListening] 里那条次序), 唤醒词与麦克风
     * 跟着收 —— 服务本身、通知、浮标与 host 都不动 (主人 2026-10-07: "host、浮标、通知都留着")。
     *
     * 开的那一半 (出了时段): 许可还开着、模型还在就重新开始听 —— **许可不是这里给或收的**, 它只是
     * 照旧生效; 视频模式那个记号也一样, 出了时段照旧把常驻链铺回来
     *
     * 两个线程会走到这里 (主线程序列里的 `ACTION_REFRESH`, 与 [startPowerWatch] 那一拍), 所以它是
     * `@Synchronized` 的, 而且第一句就把值对上 —— 同一档重复进来直接回头
     */
    @Synchronized
    private fun applyPower(save: Boolean) {
        if (save == powerSave) return
        powerSave = save
        WakeWordState.powerSave = save
        if (save) {
            if (listening) {
                runCatching { stopListening() }
            } else {
                runCatching { closeVoice() }
            }
        } else {
            val ready = runCatching {
                LwWakeWord.allow(this) &&
                    WakeWordDownload.readyCount(this) == WakeWordDownload.files.size
            }.getOrDefault(false)
            if (ready && !listening) runCatching { startListening() }
            // 出了时段, 视频模式那个"留着"照旧算数
            if (voiceResidency && listening && !voiceActive) runCatching { openVoice() }
        }
        runCatching { announce(listeningText()) }
        Log.i(TAG, "power save is now $save (listening: $listening)")
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
     * 那个**常驻的**采集消费者: 唤醒词与切段共用同一帧音频, 半双工那道闸也在这里
     *
     * 喇叭正在说话时**谁都不许吃音频** ([VoiceState.speaking]): 不采就没有"把自己的声音录回去"
     * 这回事, 顺带也解决了唤醒词被自己念的那句话叫醒 (走法 A, 主人 2026-10-05 定: 认下"念回答那几秒
     * 叫不醒"这个代价, 换掉自唤醒的风险)。闸放在两个消费者之前 (而不是各自里面), 就是为了让
     * "不许吃"只写一次
     *
     * **合上时要作废, 不只是丢样本**: 直接 `return` 只丢了新来的音频, 而 VAD 手里那半句还留着 ——
     * 几秒后 TTS 结束, 新音频接上去就切成"前半句 + 后半句"拼起来的一段, 那一段会被当成主人刚说的
     * 话, 所以合上的时候让切段器把手里的作废掉 ([SpeechSegmenter.abandon]), 下一次说话从干净的一段
     * 开始, 唤醒词那一路跟着一起哑 (A 走法要的就是这个), 这里不为它开例外
     *
     * **它不许在"这一句话的窗口"开 / 关的时候被摘上摘下** (2026-10-06 的真机 crash 逼出来的
     * 一条): 原来切段那一路是一个只在 [openVoice] 里挂上去、[closeVoice] 里摘下来的 sink, 而
     * [AudioCapture.loop] 是**一边遍历一边调**的 —— 关掉那一刻正被调用的那一个已经拿到手, 于是
     * 它在 `vad.release()` 之后还是走了进去, 下一次 `acceptWaveform` 就是 use-after-free。真机上
     * 抓到的正是这一条 (七次 SIGSEGV, 都在 `lw-audio-captur` 线程的 `Vad.acceptWaveform` ->
     * `SpeechSegmenter.accept` -> `halfDuplex$lambda$0`)。主人看到的现象是"整个应用重启"
     *
     * 所以现在是: **这台 sink 与采集同生共死** (见 [startListening] / [stopListening]), 而
     * "这一句话的窗口开着没有"由 [vad] 那一个引用说 —— 关窗口时先把引用清掉, 再交给 [flushVoice],
     * 于是那一帧拿到的要么是 null (干脆不转发), 要么是一个活着的切段器
     *
     * **这一帧该走哪一条由 [HalfDuplex.forward] 说**, 不写在这里: 它是三条展开的 `if`, 而那三条
     * 判据 (闭闸时作废 / 闭闸时唤醒词跟着哑 / 开闸时两个消费者都喂) 是真的要一条条量的
     */
    private val voiceSink = AudioCapture.Sink { samples ->
        when (HalfDuplex.forward(VoiceState.speaking)) {
            // 在采集线程上: 这一句与 keywordSink / vad 那两个消费者是同一个线程, 所以与它们不冲突
            HalfDuplex.ABANDON -> vad?.abandon()
            HalfDuplex.ACCEPT -> {
                keywordSink.accept(samples)
                vad?.accept(samples)
            }
        }
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
        // 上一次留下的那个先收掉: [openVoice] 的开门判据是 `voiceActive`, 而 `closeVoice` 失败的
        // 那一条路上它可能还挂着一个 (同一条命: 引用先摘, 再动原生)
        flushVoice()
        val path = LwSpeech.vadModel(this)
        val refusal = SpeechSegmenter.inspect(path)
        if (refusal != null) {
            VoiceState.vadReady = false
            VoiceState.vadDetail = refusal
            Log.w(TAG, refusal)
            return
        }
        val created = try {
            SpeechSegmenter.open(path, ::rememberSegment, ::noteVoice)
        } catch (error: Throwable) {
            VoiceState.vadReady = false
            VoiceState.vadDetail = "the silero VAD would not come up: ${error.message ?: error}"
            Log.w(TAG, VoiceState.vadDetail)
            return
        }
        // **引用最后才立起来**: 采集线程是照着它决定要不要转发的, 所以一个"建到一半"的切段器不该
        // 被它看见
        vad = created
        VoiceState.vadReady = true
        VoiceState.vadDetail = "ready: ${SpeechSegmenter.MIN_SILENCE_SECONDS}s of silence ends a segment, "
            .plus("at most ${SpeechSegmenter.MAX_SPEECH_SECONDS}s each")
    }

    /**
     * VAD 说这一窗有人声 (采集线程上叫的): 唤醒窗口的闲置计时把它也算成一次活动
     *
     * 只写一个 `@Volatile` 的 long, 别在这里做任何重活 —— 每个有人声的窗口都会来一次 (32 ms 一窗)
     */
    private fun noteVoice() {
        voiceHeardAt = System.currentTimeMillis()
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
     * **唤醒之后的头一句带一个记号**: 记号只给**命中之后的第一句** —— 一句话被 VAD 切成两段时要是
     * 两段都带, 就会多标一句。**它不再决定新开一场** (2026-10-06 主人改口径: "只要是 1 小时内, 无论
     * 点球/喊唤醒词 都只在同一场对话"; 2026-10-07 这个数改成 20 分钟): 挑会话由宿主那侧按
     * `voice/session.json` 那笔账定
     *
     * **投给哪一场由 [VoiceRoute] 一处说了算**: 视频模式常驻时用定格的那一场 ([videoTarget]), 其余
     * 时候用回复框点名的那一场 ([voiceTarget]) —— 回复框那个记号和头一句一起清 (读一次就消费掉), 而
     * 定格那条在整段常驻里每一句都算数 ("打开视频模式"与之后说的话必须落在同一场, 主人 2026-10-07)
     */
    private fun deliver(text: String) {
        VoiceState.lastText = text
        VoiceState.lastAt = System.currentTimeMillis()
        voiceIdleSince = System.currentTimeMillis()
        // 读一次就消费掉: 头一句之后回到老规矩 (投给最近动过的那个会话)
        val fresh = wakeWindow
        wakeWindow = false
        val target = VoiceRoute.target(voiceTarget, videoTarget, voiceResidency)
        voiceTarget = null
        val seq = VoiceInbox.append(this, text, SOURCE_VOICE, fresh, target)
        if (seq == null) {
            VoiceState.lastError = "could not queue \"$text\": the voice inbox file is not writable"
            Log.w(TAG, VoiceState.lastError!!)
        } else {
            VoiceState.delivered += 1
            VoiceState.lastSeq = seq
            Log.i(
                TAG,
                "segment ${VoiceState.recognized} queued as #$seq" +
                    (if (fresh) " (the head sentence after a wake)" else "") + ": $text",
            )
        }
        runCatching { announce(listeningText()) }
    }

    /**
     * 听到了一次
     *
     * 顺序是"先让人知道, 再叫起来": 震动与那一声短提示音是当场的手感, 通知是事后看得见的记录,
     * **开常驻语音**是"唤醒之后才轮到它"那一步, 唤起才是这个功能的目的, 任何一步失败都不该把监听
     * 带走, 所以各自 runCatching
     */
    private fun hit(keyword: String) {
        WakeWordState.hits += 1
        WakeWordState.lastKeyword = keyword
        WakeWordState.lastHitAt = System.currentTimeMillis()
        Log.i(TAG, "heard $keyword (${WakeWordState.hits} so far)")
        if (vibrateMs > 0) runCatching { buzz(vibrateMs.toLong()) }
        runCatching { announce(heardText(keyword)) }
        runCatching { openForOneSentence() }
        runCatching { wake(keyword) }
        runCatching { afterHit() }
    }

    /**
     * 一次唤醒 (或球上点一下) 要的那一句话
     *
     * 三件事都要做, 而理由各不相同:
     *
     * - **那一声短提示音占住半双工那一小段**: 不占的话它会被麦克风录进去, 而主人紧接着说的第一句
     *   话就带着一声"嘀"进识别器 (占着的那一百多毫秒里采集照跑、只是 VAD 那边作废)
     * - **[openVoice] 是开门**: 唤醒词的全部意义就是"喊一声然后说话", 所以**这一句的机会永远给** ——
     *   要把那句话认出来就得把切段与出字铺开。开的那一次只买一句话: 闲置 [VOICE_IDLE_MS] 由
     *   [startWatchdog] 收回去
     * - **`wakeWindow` 只给头一句**: 一句话被 VAD 切成两段时两段都带记号就会开出两个对话
     * - **回复框点名的那一场在同一个地方取** ([OverlayState.replyTarget]): 点球与唤醒词命中都走这
     *   一个函数, 所以"框在屏上时说的那一句投给框那一场"两条路自动一致; 取的那一刻也算一次
     *   "有人用框" (开语音要给那 20 s 的空闲账续期, 主人 2026-10-06 点名的那一条)
     */
    private fun openForOneSentence() {
        VoiceState.speaking = true
        try {
            beep(BEEP_MS)
        } finally {
            VoiceState.speaking = false
        }
        // 开窗口这一刻取一次: 框可能在说话的这几秒里被收掉, 而这一句的方向不该跟着变
        val target = OverlayState.replyTarget()
        voiceTarget = target
        if (target != null) OverlayState.noteBoxActivity()
        wakeWindow = true
        openVoice()
    }

    /**
     * 手动让它听一句 (浮标上点一下)
     *
     * 与 [hit] 同一条开门路, 只是没有"听到某个词"这件事: 不震动、不报"听到", 而它同样给一句话的
     * 机会 (投给哪一场见 [openForOneSentence]: 回复框在屏上时是框那一场, 否则由宿主那侧按 20 分钟
     * 那一笔账定) —— 球与唤醒词在主人看来是同一件事: "我要说话了"
     */
    private fun listenNow() {
        if (!listening) {
            // **省电模式里点球那一条** (主人 2026-10-07: "点球照样能说一句话"): 麦克风平时是关着的,
            // 点球就是"借一次"的意思 —— 把采集与唤醒词那一路临时拉起来, 这一句说完由 [closeVoice]
            // 还回去。缺权限 / 缺模型时 [startListening] 自己会说清缺哪一条 (fail 那条路)
            if (!powerSave || !startListening()) {
                VoiceState.lastError = "the wake word service is not listening yet, so there is nothing" +
                    " to open the voice chain on"
                Log.w(TAG, VoiceState.lastError!!)
                return
            }
        }
        runCatching { openForOneSentence() }
        runCatching { announce(listeningText()) }
    }

    /**
     * 命中之后除了叫醒还要做的 ([onHit], 批次 4.4)
     *
     * 后两条**不是在这里切模式**, 而是把一句规范命令写进收件箱 ([VoiceCommands]): 命令词表只有一份,
     * 在宿主插件里 (可行性稿 2.7), 而插件是按 seq 顺序读的 —— 这条命令比主人随后说的那句话先落进
     * 文件, 所以"切模式"一定发生在那一句话被投进会话之前
     */
    private fun afterHit() {
        val line = when (onHit) {
            LwWakeWord.HIT_VIDEO -> VoiceCommands.VIDEO
            LwWakeWord.HIT_PHONE -> VoiceCommands.PHONE
            else -> return
        }
        val seq = VoiceInbox.append(this, line)
        if (seq == null) {
            VoiceState.lastError = "the hit asked for \"$line\" but the voice inbox is not writable"
            Log.w(TAG, VoiceState.lastError!!)
            return
        }
        Log.i(TAG, "the hit also asked for \"$line\" (#$seq)")
    }

    /**
     * 听到之后把谁叫起来: **先是浮标那颗球, 而球起来就是去听**
     *
     * 主人 2026-10-05 的口径: "唤醒词只唤醒该浮标的语音输入, 不触发任何其他入口或功能"。所以这一
     * 条现在只做两件事——**把球放出来 + 让它进聆听那一档**([OverlayService.ACTION_LISTEN]), 而
     * 语音链本身早在这之前就已经开门了 ([hit] 里的 [openForOneSentence]), 球那一下只是"同一个
     * 开关从另一头按了一遍" —— 两条路走的是**同一份实现** ([listenNow] 与球上点一下完全一样)
     *
     * 这里**不再把 `MainActivity` 提到前台**: 唤醒是"我要说一句话", 不是"我要看界面"; 界面那半
     * 条是**双击回复框** (2026-10-07 起替掉了长按菜单里那一行)。浮标没开着 (没放球 / 缺悬浮窗授权 /
     * 主人把开关关着) 时
     * 才退回应用那一条老路, 否则一次唤醒会什么都不发生
     */
    private fun wake(keyword: String) {
        val host = DshHost.status
        if (onWake == WAKE_TO_OVERLAY && host is HostStatus.Running && Settings.canDrawOverlays(this) &&
            BallSpot.on(this)
        ) {
            val intent = Intent(this, OverlayService::class.java).apply {
                // **这一条不叫 ACTION_LISTEN**: 那一个在球上是一个开关 (说"再点一下收回来"), 而命中
                // 时这一句话的窗口**已经开着**了 ([openForOneSentence] 刚开过) —— 发 ACTION_LISTEN
                // 会把刚开的那一次收掉, 于是头一句话谁都听不见。叫 ACTION_SHOW 只是"把球放出来",
                // 而它出来时球上那三个字正是"正在听" (状态词读的是 VoiceState.capturing)
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
        // 这一句话的窗口先收: 它自己的收尾要等麦克风还开着 (flush 出来的最后一段得有人认), 而它一收
        // 就把 [vad] 那一个引用清掉, 之后没有任何一帧还会转发给切段器
        runCatching { closeVoice() }
        // 采集再停: 它同时喂着唤醒词与切段, 反过来的话手上那半段话会被下一帧接上, 切成一段怪的
        capture?.let { device ->
            device.remove(voiceSink)
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
     * 通知栏那一行: 唤醒词与识别链各自的状态都在这里, 而且必须分得开
     *
     * 这条通知是"一直开着的麦克风"唯一的可见处 (纪律: 一眼看得见、一下就关得掉), 所以它要说清
     * **哪几条链真的在跑**, 三种组合各有各的说法: 只有唤醒词在守 / 识别链也在跑 (视频模式那个常驻档
     * 或刚买的那一句话) / 正在念回答 (那一小段半双工里谁都不听, 是主人点过名要如实说的状态, 不是
     * 一个看不见的副作用)
     *
     * **文案 2026-10-06 改回来说"常驻语音"**: 那一条链路随常驻语音的删除消失过一次, 主人随即定了新
     * 口径 —— 常驻语音留着, 但只允许视频模式触发 (设置页那个开关与球菜单那行都删了)。所以这里又要
     * 说得清"是常驻的"还是"就这一句", 因为两者对"麦克风会不会一直开着"是两回事
     */
    private fun listeningText(): String {
        val words = WakeWordState.keywords
        // 通知的头一句就是"在听哪个词" (2026-10-06 主人点的那一句): 正在听「肥鱼肥鱼」
        val names = if (words.isEmpty()) "麦克风开着" else "「${words.joinToString("」「")}」"
        val listening = if (words.isEmpty()) names else "正在听$names"
        // **省电模式: 麦克风关着** (主人 2026-10-07) —— 喊不醒是有意的, 所以通知栏要这么写,
        // 而不是装作在听; 点球那一条仍然在, 那句话也得说出来
        if (powerSave && !voiceActive) return "省电模式 · 麦克风关着 · 点球还能说一句"
        // 正在念回答: 这期间唤醒词与识别链都被那道半双工的闸关着, 叫不醒 —— 这一档不能报"正在听"
        if (VoiceState.speaking) return "$names · 正在说话, 先不听"
        // 只有唤醒词在守 (纯 KWS 态): 通知就是那一句"正在听「…」"
        if (!voiceActive) {
            return when {
                // 视频模式的记号开着而这一路还没起来: 缺件要说清是哪一件
                voiceResidency && !VoiceState.vadReady -> "$listening · 常驻语音没就绪"
                voiceResidency && !VoiceState.asrReady -> "$listening · 识别模型还没下"
                else -> listening
            }
        }
        // 开着的时候分两种: 常驻 (视频模式要的那一档) 与"命中之后那一句的机会"(用完闲置超时就收)
        val how = if (voiceSticky) "常驻语音在跑 (视频模式)" else "听着你说这一句"
        val heard = VoiceState.lastText
        return when {
            heard != null -> "$listening · $how · 刚听到: $heard"
            !VoiceState.vadReady -> "$listening · $how · 切段没就绪"
            !VoiceState.asrReady -> "$listening · $how · 识别模型还没下"
            else -> "$listening · $how"
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

        /**
         * 把"这一句话的窗口"收回来, 唤醒词接着守
         *
         * 会话界面那个麦克风胶囊与球上"再点一下"走的都是这一条。**这个名字原来叫 `ACTION_STOP_VOICE`**
         * (2026-10-06 改的): 常驻语音删掉之后, 它收的不再是"常驻那一半", 而是主人此刻正在说的那一句 ——
         * 叫 HUSH 才不会让人以为还有一条常驻的东西可以关
         */
        const val ACTION_HUSH = "io.github.miuzarte.littlewhale.wake.HUSH"

        /** 设置页改过"叫醒之后"之后来这一条: 服务不重启, 只把新值读进来 */
        const val ACTION_REFRESH = "io.github.miuzarte.littlewhale.wake.REFRESH"

        /** 浮标上点一下那条路: 不经过唤醒词, 直接开一次"讲一句话"的窗口 (批次 4.2) */
        const val ACTION_LISTEN_NOW = "io.github.miuzarte.littlewhale.wake.LISTEN_NOW"
        const val EXTRA_ON_HIT = "onHit"
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
        private const val DEFAULT_VIBRATE_MS = 500

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
         * "这一句话"的窗口闲置多久就收回去, 回到只守唤醒词
         *
         * 主人 2026-10-05 定的: 先定 20 s, 真机上看过之后改成 **10 s** —— 这个数是"说完最后一句之后
         * 它还在吃麦克风"的那段时间, 越短越省电与隐私 (而它只影响"想一下再说第二句"能拖多久)。
         * 没有这一条的话一次命中就等于识别链一直挂着, 那正是主人 2026-10-06 判定多余的那件事
         *
         * **浮标与它共用同一条**: 球上点一下走的是 [openForOneSentence], 与命中完全同一条路, 所以
         * "闲置 10 s 自动关"在两条路上是同一个数、同一段代码 (需求: 两条真实生效的关闭路径之一);
         * 另一条是手动关 ([ACTION_HUSH] → [closeVoice], 球上再点一下与输入框上沿那个胶囊都是它),
         * 两条互不冲突 —— 谁先到谁关, 关完再看门狗那一条什么都不做 (`voiceActive` 已经是 false)
         *
         * **活动有两种** (2026-10-06 补上的第二条): 出一句字, 或者 VAD 说这一窗有人声 —— 只看前者的
         * 话, 一口气不停顿地说得比这个数还长, 会在说到一半时被收回 (VAD 要静音 0.8 s 才出字,
         * 见 [VoiceIdle])
         */
        internal const val VOICE_IDLE_MS = 10_000L

        /** 看门狗的检查节拍: 两秒一次, 比超时值小一个量级就够, 它只读内存里的两个数 */
        private const val WATCHDOG_MS = 2_000L

        /**
         * 盯 `VoiceState.speaking` 的节拍
         *
         * 400 ms 是"半双工那道闸开合"能被看见、又不至于刷通知的那个量级: 念一句通常几秒, 所以一次
         * 朗读只换来两条通知 (开始念 / 念完了)
         */
        private const val SPEAKING_POLL_MS = 400L

        /**
         * 省电模式那一拍的周期 (见 [startPowerWatch])
         *
         * 一次 tick 只是读两个偏好加一次时刻比较, 所以 15 s 这种量级足够密: "到点最多晚十几秒"对
         * 省电这件事没有任何影响, 而手动那个开关根本不走这一条 (设置页当场发 `ACTION_REFRESH`)
         */
        private const val POWER_POLL_MS = 15_000L

        private const val WORKER_JOIN_MS = 2000L

        private const val TAG = "LwWakeWord"
        private const val CHANNEL_ID = "lw-wake"

        /** 与浮窗 (3) 和工具通知分开的第三条常驻 */
        private const val NOTIFICATION_ID = 4
    }
}
