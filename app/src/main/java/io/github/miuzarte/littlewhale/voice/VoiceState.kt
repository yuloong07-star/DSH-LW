package io.github.miuzarte.littlewhale.voice

/**
 * "说一句话"那一路现在什么样: 通道方法 `wakeword` 的 status 与通知栏都读这里
 *
 * 三个"成不成"分开记, 因为处置完全不同: 采集跑没跑 (麦克风与前台服务)、VAD 就绪没就绪 (那个
 * 1.8 MB 的模型)、识别器就绪没就绪 (那个 240 MB 的模型),任何一个不成都不影响别的 —— 缺识别
 * 模型时唤醒词照样该好用, 只是"说一句话进会话"这件事不成, 那时要把这句话说出来而不是装作在听
 *
 * **"常驻语音"那一条一直不收的链已经删掉了** (2026-10-06): 所以下面这些字段说的都只是**这一句话的
 * 窗口** —— 唤醒词命中一次、或者球上点一下开一次, 闲置 `WakeWordService.VOICE_IDLE_MS` 收回去
 */
internal object VoiceState {

    /**
     * **这一句话的窗口开着没有** (切段 + 出字), 不是"麦克风开没开"
     *
     * 这两件事必须分得开: 只有唤醒词在守的时候麦克风也是开着的 (它当然开着, 不然听不见那个词),
     * 而那时这里的值是 **false** —— 一直开着的麦克风这件事说的其实是"唤醒词还在守", 它在
     * `WakeWordState.listening` 里。改这一行之前先想清楚: 把唤醒词也算进 capturing, 就等于把
     * "设置页那个开关 = 常驻监听"那个错又写回来一遍 (那是 2026-10-05 修掉的那一个)
     */
    @Volatile
    var capturing: Boolean = false

    /** 切段器就绪没有, 以及不就绪时那句话 */
    @Volatile
    var vadReady: Boolean = false

    @Volatile
    var vadDetail: String = "not started yet"

    /** 识别器就绪没有 (就是模型在不在), 以及不就绪时那句话 */
    @Volatile
    var asrReady: Boolean = false

    @Volatile
    var asrDetail: String = "not started yet"

    /** VAD 切出来几段 */
    @Volatile
    var segments: Int = 0

    /** 其中认出来几段 (空文本不算) */
    @Volatile
    var recognized: Int = 0

    /** 上一段是不是被 15 s 上限切断的 */
    @Volatile
    var lastTruncated: Boolean = false

    /** 等着认的段数, 以及因为排队满而丢掉的段数 (满了就意味着这台设备认不过来) */
    @Volatile
    var pending: Int = 0

    @Volatile
    var dropped: Int = 0

    /** 最近一段认出来的话与它的时刻 */
    @Volatile
    var lastText: String? = null

    @Volatile
    var lastAt: Long = 0

    /** 投进队列几句, 以及最后那句拿到的序号 (宿主那侧按序号去重) */
    @Volatile
    var delivered: Int = 0

    @Volatile
    var lastSeq: Long = 0

    /**
     * 喇叭正在说话
     *
     * 半双工那道闸看的就是它: 念回答的时候采集链一个样本都不吃 —— 不采就不会把自己的声音录回去,
     * 顺带也不会被自己念的那句话叫醒,写入方是朗读那一侧 ([io.github.miuzarte.littlewhale.tool.LwSpeak])
     */
    @Volatile
    var speaking: Boolean = false

    /**
     * **页面现在在看哪一场** (空 = 还没有人报过 / 现在没有选中的会话)
     *
     * 只有一个写入方: 注入到会话页面里的那段脚本一秒问一次 `localStorage['dsh.sessions.current']`,
     * 变了就经桥 [io.github.miuzarte.littlewhale.ui.WakeBridge.session] 报上来。用处也只有一个:
     * 切进视频模式那一刻把它**定格** (`LwWakeWord.resident` 写进 `modes/video-voice.session`),
     * 于是视频模式里说的话与"打开视频模式"那句话落在同一场 (主人 2026-10-07: 两条投递目标要统一)
     *
     * 它只是一个**提示**: 宿主那侧还要过"这一场还在、还是根会话"的检查, 认不出来就落回原来的规则
     */
    @Volatile
    var uiSession: String? = null

    @Volatile
    var lastError: String? = null
}
