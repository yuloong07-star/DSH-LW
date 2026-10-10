package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「API TTS」那条引擎: 把文本 POST 给一个 **OpenAI 兼容的 `/v1/audio/speech`**, 拿回音频字节, 交给
 * [LwVoiceClip] 放
 *
 * 地址与密钥由用户在设置页自己填 (见 [SpeakSettings]): 地址可以是域名、`…/v1` 或完整端点, 补全的规则
 * 在 [LwApiSpeech.endpoint]; 密钥为空时不带 `Authorization` (自建网关常常不要密钥)
 *
 * 音量: 拿回来的是编码过的音频, 应用这一侧不解码就没有增益可加 —— 响度由服务与手机媒体音量决定,
 * 所以设置页对这条引擎显示的是"它自己决定"而不是那条滑块
 */
internal object LwApiTts {

    /** 现在有没有在放 */
    val speaking: Boolean get() = LwVoiceClip.speaking

    /**
     * 念一段: 成功回 "said …" (与其它引擎同形, 调用方据此判成败), 失败回一句人话
     *
     * [context] 只用来找放临时文件的 cache 目录
     */
    fun speak(context: Context, text: String, speed: Float): String {
        val endpoint = LwApiSpeech.endpoint(SpeakSettings.apiUrl)
        if (endpoint.isEmpty()) {
            return "the API engine has no address yet: fill it in on the settings page"
        }
        val pieces = LwSpeak.chunk(text, LwApiSpeech.CHUNK_CHARS)
        if (pieces.isEmpty()) return "there is nothing to say"
        val key = SpeakSettings.apiKey
        val model = SpeakSettings.apiModel
        val voice = SpeakSettings.apiVoice
        val started = System.currentTimeMillis()
        stopped = false
        return try {
            pieces.forEach { piece ->
                if (stopped) return "stopped on request before saying \"${piece.take(SCRIBBLE)}\""
                val clip = fetch(endpoint, key, model, voice, speed, piece)
                if (!LwVoiceClip.play(context, clip.bytes, clip.extension)) {
                    return if (stopped) {
                        "stopped on request while saying \"${piece.take(SCRIBBLE)}\""
                    } else {
                        "the audio did not finish playing"
                    }
                }
            }
            "said ${text.length} characters in ${pieces.size} piece(s) with API $model" +
                " in ${System.currentTimeMillis() - started} ms"
        } catch (error: Throwable) {
            Log.w(TAG, "the API TTS call failed", error)
            "the API TTS call failed: ${error.message ?: error}"
        }
    }

    /** 掐断: 没在放时回 null (与自带那条同形) */
    fun stop(): String? {
        if (!LwVoiceClip.speaking) return null
        stopped = true
        return LwVoiceClip.stop()
    }

    /** 一次请求的产物: 字节与它的后缀 */
    private class Clip(val bytes: ByteArray, val extension: String)

    private fun fetch(
        endpoint: String,
        key: String,
        model: String,
        voice: String,
        speed: Float,
        input: String,
    ): Clip {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (key.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { stream ->
                stream.write(LwApiSpeech.requestBody(model, input, voice, speed).toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.use { stream ->
                        stream.readBytes().decodeToString().take(MAX_ERROR_CHARS)
                    }
                }.getOrNull().orEmpty()
                throw IllegalStateException("the endpoint answered $code${if (detail.isBlank()) "" else ": $detail"}")
            }
            val bytes = connection.inputStream.use { readCapped(it) }
            if (bytes.isEmpty()) throw IllegalStateException("the endpoint returned no audio")
            return Clip(bytes, LwApiSpeech.extension(connection.contentType))
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** 读回来但夹住上限: 一个失控的响应不该把内存吃掉 */
    private fun readCapped(stream: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            if (buffer.size() + read > MAX_BYTES) {
                throw IllegalStateException("the endpoint returned more than ${MAX_BYTES / 1024 / 1024} MB")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private const val TAG = "LwApiTts"
    private const val SCRIBBLE = 12
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_BYTES = 24 * 1024 * 1024
    private const val MAX_ERROR_CHARS = 400

    /** 这一次念有没有被叫停 */
    @Volatile
    private var stopped = false
}
