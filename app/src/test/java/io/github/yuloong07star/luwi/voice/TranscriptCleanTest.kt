package io.github.yuloong07star.luwi.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 转写结果的清洁版: 修得回来的乱码修回来, 修不回来的脏串丢掉
 *
 * 主人 2026-10-07 报的那一条 (输入框上的麦克风"识别时会出现乱码"), 两个例子都钉在这里: `ä½ å¥½`
 * 必须变回 `你好`, 而泰文那种引擎不该说的话必须变成空串 (空串 = 页面说"没有识别到内容", 而不是把
 * 那一串插进草稿框)
 */
class TranscriptCleanTest {

    /** 正常的中文原样通过 (带全角标点) */
    @Test
    fun `正常中文原样通过`() {
        assertEquals("你好，世界！", TranscriptClean.clean("你好，世界！"))
    }

    /** 英文、数字与常见符号原样通过 */
    @Test
    fun `英文与数字原样通过`() {
        assertEquals("Call me at 18:30, ok?", TranscriptClean.clean("Call me at 18:30, ok?"))
    }

    /**
     * **主人给的那个例子**: `你好` 的 UTF-8 字节 (E4 BD A0 E5 A5 BD) 被当 Latin-1 读出来
     *
     * 修回来的判据就是这一条 —— 逐字节可复现, 所以它不是猜的
     */
    @Test
    fun `UTF8 被当 Latin1 读的那一串修回来`() {
        val mojibake = String(
            byteArrayOf(0xE4.toByte(), 0xBD.toByte(), 0xA0.toByte(), 0xE5.toByte(), 0xA5.toByte(), 0xBD.toByte()),
            Charsets.ISO_8859_1,
        )
        // 先确认这一串就是主人看到的样子 (ä ½ 空格 å ¥ ½, 其中"空格"是不换行空格)
        assertEquals("ä\u00BD\u00A0å\u00A5\u00BD", mojibake)
        assertEquals("你好", TranscriptClean.clean(mojibake))
    }

    /** 混在中文里的那一小段乱码也能修 (修的是整串, 不是某几个字) */
    @Test
    fun `中英混排里的乱码段也修回来`() {
        val tail = String(
            byteArrayOf(0xE5.toByte(), 0xA5.toByte(), 0xBD.toByte()),
            Charsets.ISO_8859_1,
        )
        assertEquals("say 好", TranscriptClean.clean("say $tail"))
    }

    /** **主人给的第二个例子**: 泰文 —— 这个应用声明认的是中英日韩粤, 那一串不是一个答案 */
    @Test
    fun `泰文那种外文被丢掉`() {
        assertEquals("", TranscriptClean.clean("คุณยุติสุดท้าย"))
    }

    /** 西里尔字母同理 (引擎没声明俄语) */
    @Test
    fun `西里尔字母被丢掉`() {
        assertEquals("", TranscriptClean.clean("привет"))
    }

    /** 修不回来的乱码串 (满是对不上号的 Latin-1 符号) 也要丢掉, 不能原样插进输入框 */
    @Test
    fun `修不回来的乱码符号串被丢掉`() {
        assertEquals("", TranscriptClean.clean("½¿ ¡¤§"))
    }

    /**
     * **正常的外文名字不许被误改**: `café` 的 é 也在 Latin-1 范围里, 但按 Latin-1 取字节之后
     * **不是合法 UTF-8** —— 所以它原样留下, 这是"严格解码"那一条的意义
     */
    @Test
    fun `拉丁字母的名字原样留下`() {
        assertEquals("café Müller", TranscriptClean.clean("café Müller"))
    }

    /** 单个符号照旧收 (`°` / `×` 在正经句子里说得过去) */
    @Test
    fun `单个符号不算乱码`() {
        assertEquals("今天 25° 左右", TranscriptClean.clean("今天 25° 左右"))
    }

    /** 空与空白还是空 (调用方按"没有识别到内容"处理) */
    @Test
    fun `空白还是空`() {
        assertEquals("", TranscriptClean.clean(""))
        assertEquals("", TranscriptClean.clean("   \n "))
    }
}
