package io.github.miuzarte.littlewhale.tool

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge 那条链的接线: **服务端自己把连接关了**这一条路必须当场给出结论
 *
 * 这一条是 2026-10-09 真机上"点试听没声音、屏幕上什么都没有"的根: 服务端拒绝一个音色时是
 * `Path:turn.start` 之后紧跟一条 close (1007 `Unsupported voice ...`), 而 OkHttp 只在**我们自己发起
 * 关闭**时才回调 `onClosed`, 对端主动关只给 `onClosing` —— 漏掉它就等于漏掉一条"有结论"的路, 调用方
 * 只能等满那个 60 秒预算
 *
 * 这些都不用开设备: 监听器直接喂事件, 闩与失败都看得见
 */
class LwEdgeTtsTest {

    /** 一条不会真的发包的连接: 只要能被回调点名、记得住改了什么都够 */
    private class FakeSocket : WebSocket {
        val sent = mutableListOf<String>()
        var canceled = false
        var closeCode: Int? = null

        override fun request(): Request = Request.Builder().url("https://example.com/").build()

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean = sent.add(text)

        override fun send(bytes: ByteString): Boolean = true

        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            return true
        }

        override fun cancel() {
            canceled = true
        }
    }

    /** 一次连接的那几样: 音频、闩、失败, 加上被喂事件的监听器 */
    private class Wired {
        val socket = FakeSocket()
        val audio = ByteArrayOutputStream()
        val failure = AtomicReference<Throwable?>(null)
        val finished = CountDownLatch(1)
        val listener = EdgeListener("req-1", "<speak/>", audio, failure, finished)
    }

    @Test
    fun `连上就先说输出格式再说文本`() {
        val wired = Wired()
        wired.listener.onOpen(wired.socket, okhttp3.Response.Builder()
            .request(wired.socket.request())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build())
        assertEquals(2, wired.socket.sent.size)
        assertTrue(wired.socket.sent[0], wired.socket.sent[0].contains("Path:speech.config"))
        assertTrue(wired.socket.sent[1], wired.socket.sent[1].contains("Path:ssml"))
    }

    @Test
    fun `服务端在念完之前自己关了 要当场收闩并带出理由`() {
        val wired = Wired()
        wired.listener.onClosing(wired.socket, 1007, "Unsupported voice zh-CN-XiaochenNeural.")
        assertEquals(0, wired.finished.count)
        val failure = wired.failure.get()
        assertNotNull(failure)
        assertTrue("$failure", failure!!.message.orEmpty().contains("1007"))
        assertTrue(
            "$failure",
            failure.message.orEmpty().contains("Unsupported voice zh-CN-XiaochenNeural."),
        )
    }

    @Test
    fun `念完之后对端再关不算失败`() {
        val wired = Wired()
        wired.listener.onMessage(wired.socket, "X-RequestId:1\r\nPath:turn.end\r\n\r\n{}")
        assertEquals(0, wired.finished.count)
        assertEquals(1000, wired.socket.closeCode ?: -1)
        wired.listener.onClosing(wired.socket, 1000, "")
        assertNull(wired.failure.get())
    }

    @Test
    fun `音频帧存下来 元数据帧跳过`() {
        val wired = Wired()
        val header = "Path:audio\r\n".toByteArray(Charsets.US_ASCII)
        wired.listener.onMessage(
            wired.socket,
            ByteString.of(*byteArrayOf((header.size shr 8).toByte(), header.size.toByte()), *header, 7, 8),
        )
        assertEquals(listOf<Byte>(7, 8), wired.audio.toByteArray().toList())
    }
}
