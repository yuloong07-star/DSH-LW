package io.github.yuloong07star.luwi.tool

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 「API TTS」那条引擎的纯算术: 地址怎么补全、请求体长什么样、回来的字节配什么后缀
 *
 * 这一条最容易错的恰好是这三处 (接口地址少一段、请求体少一个字段、拿到的 mp3 当成 wav), 而它们都不碰
 * 安卓也不碰网络, 所以 JVM 单测能直接量 (见 LwApiSpeechTest)
 *
 * 接口按 **OpenAI 兼容的 `POST /v1/audio/speech`** 做: 请求 `{model, input, voice, speed}`, 回来一段
 * 音频文件 (一般是 mp3), 交给 MediaPlayer 放 —— 所以那条引擎的音量不归应用管, 由服务与手机媒体音量决定
 */
internal object LwApiSpeech {

    /** 没填模型/音色时用的两个缺省: OpenAI 兼容接口都认 */
    const val DEFAULT_MODEL = "tts-1"
    const val DEFAULT_VOICE = "alloy"

    /** 一段最多几个字: 接口自己的上限是 4096, 这里切得比它狠一点, 顺带让"停"有粒度 */
    const val CHUNK_CHARS = 600

    /**
     * 把设置页填的地址补成真正的 endpoint
     *
     * 三种写法都收: 已经点到 `/audio/speech` 的原样用; 到 `/v1` 的补 `/audio/speech`; 其余补
     * `/v1/audio/speech` —— 于是粘贴 `https://api.openai.com`、`https://api.openai.com/v1` 与网关的
     * 完整地址都能用 (地址栏里显示的还是人填的那个, 补全是调用时的事)
     */
    fun endpoint(entered: String): String {
        val trimmed = entered.trim().trimEnd('/')
        return when {
            trimmed.isEmpty() -> ""
            trimmed.endsWith("/audio/speech") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/audio/speech"
            else -> "$trimmed/v1/audio/speech"
        }
    }

    /** OpenAI 兼容的请求体; 空白的模型/音色回落到缺省, 免得服务端拿一个空串去报错 */
    fun requestBody(model: String, input: String, voice: String, speed: Float): String =
        buildJsonObject {
            put("model", model.ifBlank { DEFAULT_MODEL })
            put("input", input)
            put("voice", voice.ifBlank { DEFAULT_VOICE })
            put("speed", speed)
        }.toString()

    /**
     * 回来的字节是什么格式: 只有 `Content-Type` 说了算
     *
     * 后缀本身不影响播放, 但 MediaPlayer 会拿它当线索, 所以宁可猜一个也不留 `.tmp`; 认不出来就按 mp3
     * (这一族的接口默认就是 mp3)
     */
    fun extension(contentType: String?): String {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            type.contains("wav") -> "wav"
            type.contains("ogg") -> "ogg"
            type.contains("aac") -> "aac"
            type.contains("flac") -> "flac"
            type.contains("mp4") || type.contains("m4a") -> "m4a"
            else -> "mp3"
        }
    }
}
