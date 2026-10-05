package io.github.miuzarte.littlewhale.voice

/**
 * 常驻语音链现在什么样: 通道方法 `wakeword` 的 status 与通知栏都读这里
 *
 * 三个"成不成"分开记, 因为处置完全不同: 采集跑没跑 (麦克风与前台服务)、VAD 就绪没就绪 (那个
 * 1.8 MB 的模型)、识别器就绪没就绪 (那个 240 MB 的模型),任何一个不成都不影响别的 —— 缺识别
 * 模型时唤醒词照样该好用, 只是"说一句话进会话"这件事不成, 那时要把这句话说出来而不是装作在听
 */
internal object VoiceState {

    /**
     * **常驻语音那一路在不在跑** (切段 + 出字), 不是"麦克风开没开"
     *
     * 这两件事必须分得开: 只有唤醒词在守的时候麦克风也是开着的 (它当然开着, 不然听不见那个词),
     * 而那时这里的值是 **false** —— 一直开着的麦克风这件事在状态上的落点是 [VoiceState.capturing],
     * 而"还在听唤醒词"那一半在 `WakeWordState.listening` 里, 改这一行之前先想清楚:
     * 把唤醒词也算进 capturing, 就等于把"设置页那个开关 = 常驻监听"那个错又写回来一遍
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

    @Volatile
    var lastError: String? = null
}
