package io.github.miuzarte.littlewhale.voice

/**
 * 常驻语音链现在什么样: 通道方法 `wakeword` 的 status 与通知栏都读这里
 *
 * 三个"成不成"分开记, 因为处置完全不同: 采集跑没跑 (麦克风与前台服务)、VAD 就绪没就绪 (那个
 * 1.8 MB 的模型)、识别器就绪没就绪 (那个 240 MB 的模型)。任何一个不成都不影响别的 —— 缺识别
 * 模型时唤醒词照样该好用, 只是"说一句话进会话"这件事不成, 那时要把这句话说出来而不是装作在听
 */
internal object VoiceState {

    /** 采集线程在跑没有 */
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

    @Volatile
    var lastError: String? = null
}
