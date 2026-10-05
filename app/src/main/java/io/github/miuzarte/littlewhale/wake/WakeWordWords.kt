package io.github.miuzarte.littlewhale.wake

/**
 * 词表那一半: 人写的 `词=带音调数字的拼音` 变成模型认得的那一行
 *
 * 为什么不能直接写中文: sherpa-onnx 的 keywords 文件每一行是**模型的 token 序列**加一个
 * `@显示名`, 而 `EncodeKeywords` 只认模型 tokens.txt 里有的符号 —— 中文原文一个都不在表里, 它
 * 不报错, 只是把那一行静默丢掉, 结果是"照做了但永远不触发"。所以拼音要拆成声母 + 带调韵母,
 * 逐个对一遍符号表, 对不上就当面报错
 *
 * 这一份与宿主插件里那一段 (host-plugin/index.mjs 的 wakeWordLine) 是**同一套算法的两份实现**:
 * 设置页在 app 里, 而模型那条路在 Node 里, 两边都要能独立写词表。改了一边记得看另一边
 */
internal object WakeWordWords {

    /** 缺省那一句: 设置页与插件两边的缺省必须一致, 它认的是词表内容不是谁写的 */
    const val DEFAULT = "大肥鱼大肥鱼=da4 fei2 yu2 da4 fei2 yu2"

    /** 声母: 长的在前, 免得 zh 被拆成 z + h */
    private val INITIALS = listOf(
        "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l",
        "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w",
    )

    /** 声调往哪个元音上标 */
    private val TONES = mapOf(
        'a' to "āáǎà",
        'o' to "ōóǒò",
        'e' to "ēéěè",
        'i' to "īíǐì",
        'u' to "ūúǔù",
        'ü' to "ǖǘǚǜ",
    )

    /**
     * 整段文本 -> 一行一个关键词
     *
     * 一行一对 (`词=拼音`), 也认分号与逗号分隔 —— 设置页那个输入框里换行最自然, 但手滑打成一行也要能用
     */
    fun lines(text: String, symbols: Set<String>): List<String> = text
        .split('\n', ';', '；')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line(it, symbols) }

    /**
     * 一对 `词=拼音` -> `d à f éi y ú @大肥鱼`
     *
     * 对不上的 token 直接抛: 半张能用的词表比没有词表更难查 (有的词会触发, 有的永不触发, 而人说不出
     * 为什么)
     */
    fun line(pair: String, symbols: Set<String>): String {
        val at = pair.indexOf('=')
        if (at <= 0) throw IllegalArgumentException("要写成 词=拼音, 例如 $DEFAULT")
        val word = pair.substring(0, at).trim()
        val pinyin = pair.substring(at + 1).trim()
        if (word.isEmpty() || pinyin.isEmpty()) throw IllegalArgumentException("要写成 词=拼音, 例如 $DEFAULT")
        val tokens = mutableListOf<String>()
        for (raw in pinyin.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val syllable = marked(raw)
            if (syllable.isEmpty()) throw IllegalArgumentException("\"$pair\" 里的 \"$raw\" 是空的")
            val initial = INITIALS.firstOrNull { syllable.startsWith(it) && syllable.length > it.length }
            val parts = if (initial == null) listOf(syllable) else listOf(initial, syllable.substring(initial.length))
            for (token in parts) {
                if (token !in symbols) {
                    throw IllegalArgumentException(
                        "模型的符号表里没有 \"$token\" (来自 \"$raw\"), sherpa-onnx 会把整行静默丢掉," +
                            " 所以不能这么写",
                    )
                }
            }
            tokens += parts
        }
        return "${tokens.joinToString(" ")} @$word"
    }

    /** 一行关键词的显示名: `@` 之后那一段, 没有就回整行 */
    fun displayName(line: String): String = keywordName(line)

    /** su4 -> sù; 已经带调号的 (sù) 原样回来; 轻声 (第 5 声) 不带调号 */
    private fun marked(raw: String): String {
        var syllable = raw.trim().lowercase().replace("u:", "ü").replace("v", "ü")
        val tone = Regex("([1-5])$").find(syllable) ?: return syllable
        syllable = syllable.dropLast(1)
        if (tone.groupValues[1] == "5") return syllable
        val at = toneIndex(syllable)
        val marks = if (at >= 0) TONES[syllable[at]] else null
        if (marks == null) return syllable
        return syllable.substring(0, at) + marks[tone.groupValues[1].toInt() - 1] + syllable.substring(at + 1)
    }

    /** 一个音节里调号落在哪个字符上: a / o / e 优先, iu 落在 u, ui 落在 i, 其余落在最后一个元音 */
    private fun toneIndex(syllable: String): Int {
        for (vowel in listOf('a', 'o', 'e')) {
            val at = syllable.indexOf(vowel)
            if (at >= 0) return at
        }
        if (syllable.endsWith("iu") || syllable.endsWith("ui")) return syllable.length - 1
        for (at in syllable.length - 1 downTo 0) {
            if (syllable[at] in "iuü") return at
        }
        return -1
    }
}
