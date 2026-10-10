package io.github.yuloong07star.luwi.voice

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 转写出来的那一句在**交给别人之前**过一遍: 修掉看得出来的编码乱码, 丢掉引擎不该说出来的文字
 *
 * 主人 2026-10-07 报的那一条: "输入框上麦克风语音识别 bug, 识别时会出现乱码", 给的例子是
 * `ä½ å¥½` 与 `คุณยุติสุดท้าย`。
 *
 * 2026-10-07 的取证 (真机 + 同一份模型) 说清了两件事, 这一层的判据就建在它们上:
 *
 * - **通路是干净的**: 页面 (base64 WAV) → 宿主 → 应用 (GLM-ASR) → 回页面 → 插进草稿框, 这一整条
 *   用同一份代码在电脑上的浏览器里跑过一次, 中文原样回来。所以乱码不是某一跳解码错了
 * - **模型对坏音频是稳的**: 静音 / 白噪声 / 电流声 / 音乐 / 削顶 / 三倍快慢放, 这一份模型要么回
 *   空串, 要么照样认对
 *
 * 于是剩下的解释只剩"模型自己偶尔吐出来的那点东西": LLM 型识别器在难音频上会吐外文 (那一句泰文),
 * 而"把 UTF-8 字节当 Latin-1 读"正是中文语料里最常见的一种脏样本 —— `ä½ å¥½` 就是 `你好` 的
 * UTF-8 字节被当 Latin-1 读出来的样子 (逐字节可复现)。
 *
 * 这一层因此做两件事, 都不改识别本身:
 *
 * 1. **能把乱码修回来就修**: 整串都在 Latin-1 范围内、按 Latin-1 取字节再严格解码 UTF-8 成功时,
 *    用修出来的那一句 (`ä½ å¥½` → `你好`)。修不出合法 UTF-8 就不动它 (法语 `café` 那种正常文字
 *    因此不会被误改)
 * 2. **修不回来的脏串就丢掉**: 这个应用声明自己认的是中英日韩粤, 所以只要出现这些之外的**文字**
 *    (泰文 / 西里尔 / 阿拉伯文 …), 或者一串满是 Latin-1 符号的伪文本 (½ ¿ ¡ ¤ 那种), 就回空串 ——
 *    页面会显示"没有识别到内容", 而**不是把那串东西插进输入框**
 *
 * 这是纯函数 (没有安卓、没有文件、没有时间), 所以 JVM 单测能直接量 (见 [TranscriptCleanTest])
 */
internal object TranscriptClean {

    /**
     * 一句转写的清洁版
     *
     * 空串是合法答案 (它表示"这句不算数"), 调用方按"没有识别到内容"处理 —— 不要把它换成别的文字
     */
    fun clean(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val repaired = repair(trimmed) ?: trimmed
        if (repaired.isEmpty()) return ""
        return if (supported(repaired)) repaired else ""
    }

    /**
     * 试着把"UTF-8 被当 Latin-1 读"的那一串修回来; 修不了回 null (调用方拿原串)
     *
     * 三条都要满足, 少一条都不动:
     *
     * - **整串都在 `U+00FF` 以内**: 真正的乱码每个字符就是一个字节; 只要有一个字符超出去, 它就不是
     *   这样来的
     * - **至少有一个 `U+0080` 以上的字符**: 纯 ASCII 原样不动 (改了也白改)
     * - **严格 UTF-8 解码成功**: `REPORT` 而不是替换字符 —— 解码"成功"但满地 `�` 的那种不算修好
     */
    private fun repair(text: String): String? {
        var high = false
        val bytes = ByteArray(text.length)
        for (index in text.indices) {
            val code = text[index].code
            if (code > 0xFF) return null
            if (code >= 0x80) high = true
            bytes[index] = code.toByte()
        }
        if (!high) return null
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull() ?: return null
        if (decoded.isEmpty() || decoded == text) return null
        return decoded
    }

    /**
     * 这一句里有没有这个应用认不了的字
     *
     * 判据是**白名单**: 空白、数字、ASCII 可见字符、中日韩文字与它们那几套标点、拉丁字母 (含带音符
     * 的那一批, 欧洲语言的名字与地名会出现), 其余一律拒。白名单的好处是**新的外文字进来默认是拒**,
     * 而黑名单要人先把每一种文字都想一遍
     */
    private fun supported(text: String): Boolean {
        var mojibakeish = 0
        for (char in text) {
            if (isSupported(char)) continue
            // 拉丁-1 那一段里的"符号" (½ ¿ ¡ ¤ § ° ± µ · ¸ ¹ º ¼ ¾ ¾ × ÷ 之类) 不是任何语言的正文:
            // 真正的中英日韩句子里出现两三个这种字符, 只可能是没修回来的乱码 —— 计数而不是立刻判死,
            // 是因为单独一个 `°` (温度) 与 `×` (乘号) 在正经文字里说得过去
            if (char.code in 0x00A0..0x00BF || char.code == 0x00D7 || char.code == 0x00F7) {
                mojibakeish += 1
                if (mojibakeish >= 2) return false
                continue
            }
            return false
        }
        return true
    }

    /** 这一个字符算不算"这个应用该说出来的字" */
    private fun isSupported(char: Char): Boolean = when {
        char.isWhitespace() -> true
        // 数字: 半角 / 全角 / 各种文字的数码都收 —— 一串数字不构成"乱码"
        char.isDigit() -> true
        // ASCII 看得见的那些 (字母、标点、符号)
        char.code in 0x20..0x7E -> true
        // 拉丁字母 (Latin-1 Supplement 的字母那一半 + Latin Extended-A/B): café / Müller / Łódź …
        char.code in 0x00C0..0x024F -> true
        // 符号、标点与表情: 破折号 / 引号 / 省略号 / 货币 / 箭头 / 数学符 / 几何图形 / emoji …
        // (这一档只收"符号", 所以它不会把泰文那种外文放进来 —— 那些是字母, 落在下面的白名单之外)
        char.code in 0x2000..0x206F -> true
        char.code in 0x20A0..0x20CF -> true
        char.code in 0x2100..0x214F -> true
        char.code in 0x2190..0x21FF -> true
        char.code in 0x2200..0x22FF -> true
        char.code in 0x2460..0x24FF -> true
        char.code in 0x25A0..0x27BF -> true
        char.code in 0x2900..0x297F -> true
        // emoji 是代理对 (每个字两个 char): 高位与低位都放行, 于是"带表情的一句"不会整句被拒
        char.code in 0xD800..0xDFFF -> true
        char.code in 0xFE00..0xFE0F -> true
        // 中日韩: 汉字 (扩展 A + 基本区 + 兼容区), 假名, 谚文, 与它们那几套标点 / 全角字符
        char.code in 0x2E80..0x2EFF -> true
        char.code in 0x3000..0x303F -> true
        char.code in 0x3040..0x30FF -> true
        char.code in 0x3130..0x318F -> true
        char.code in 0x31F0..0x31FF -> true
        char.code in 0x3400..0x4DBF -> true
        char.code in 0x4E00..0x9FFF -> true
        char.code in 0xAC00..0xD7AF -> true
        char.code in 0xF900..0xFAFF -> true
        char.code in 0xFF00..0xFFEF -> true
        else -> false
    }
}
