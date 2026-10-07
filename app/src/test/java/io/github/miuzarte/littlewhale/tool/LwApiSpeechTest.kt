package io.github.miuzarte.littlewhale.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API TTS 那三件纯算术: 地址补全、请求体、按 Content-Type 认后缀
 *
 * 不开设备也不联网: 这三件事是"填了地址却发不出去"与"拿回来放不出声"最常见的原因, 所以先在 JVM 上钉死
 */
class LwApiSpeechTest {

    /* ── 地址补全: 三种写法都要能用 ─────────────────────────────────────── */

    @Test
    fun `只填域名时补成 v1 的 audio speech`() {
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            LwApiSpeech.endpoint("https://api.openai.com"),
        )
    }

    @Test
    fun `填到 v1 时只补最后一段`() {
        assertEquals(
            "https://gateway.example/tts/v1/audio/speech",
            LwApiSpeech.endpoint("https://gateway.example/tts/v1"),
        )
    }

    @Test
    fun `填全了就不动它, 结尾斜杠也去掉`() {
        assertEquals(
            "https://gateway.example/tts/v1/audio/speech",
            LwApiSpeech.endpoint("  https://gateway.example/tts/v1/audio/speech/  "),
        )
    }

    @Test
    fun `空地址补出来还是空`() {
        assertEquals("", LwApiSpeech.endpoint("   "))
    }

    /* ── 请求体: 四个字段都要在 ─────────────────────────────────────────── */

    @Test
    fun `请求体带上模型 文本 音色与语速`() {
        val body = LwApiSpeech.requestBody("tts-1-hd", "你好", "nova", 1.25f)
        assertTrue(body, body.contains("\"model\":\"tts-1-hd\""))
        assertTrue(body, body.contains("\"input\":\"你好\""))
        assertTrue(body, body.contains("\"voice\":\"nova\""))
        assertTrue(body, body.contains("\"speed\":1.25"))
    }

    @Test
    fun `空白模型与音色回落到缺省`() {
        val body = LwApiSpeech.requestBody("", "hi", "  ", 1f)
        assertTrue(body, body.contains("\"model\":\"${LwApiSpeech.DEFAULT_MODEL}\""))
        assertTrue(body, body.contains("\"voice\":\"${LwApiSpeech.DEFAULT_VOICE}\""))
    }

    /* ── 后缀: 认识常见几种, 认不出按 mp3 ─────────────────────────────── */

    @Test
    fun `按 content type 认后缀`() {
        assertEquals("wav", LwApiSpeech.extension("audio/wav"))
        assertEquals("ogg", LwApiSpeech.extension("audio/ogg; codecs=opus"))
        assertEquals("aac", LwApiSpeech.extension("audio/aac"))
        assertEquals("flac", LwApiSpeech.extension("audio/flac"))
        assertEquals("mp3", LwApiSpeech.extension("audio/mpeg"))
        assertEquals("mp3", LwApiSpeech.extension(null))
        assertEquals("mp3", LwApiSpeech.extension("application/octet-stream"))
    }
}
