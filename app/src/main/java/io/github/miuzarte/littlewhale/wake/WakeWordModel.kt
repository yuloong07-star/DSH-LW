package io.github.miuzarte.littlewhale.wake

import java.io.File

/**
 * 一个 KWS 模型落在磁盘上的那几件东西
 *
 * 目录名与文件名都不写死: 上游那两套 (GitHub Release 里的 `-mobile` 一版与 ModelScope 上的
 * 一版) 文件名不一样, 量化过的与没量化过的又各是一份, 所以按前缀找、按 `.int8.` 优先 ——
 * 手机上是 CPU 跑, int8 那一份快得多, 而 3.3M 的模型本来就不缺精度
 */
internal class WakeWordModel(
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
) {
    /** 这几个文件都在, 才算一套完整的模型 */
    val complete: Boolean
        get() = listOf(encoder, decoder, joiner, tokens).all { it.isFile && it.length() > 0 }

    /** 给模型看的一句话, 说明用的是哪一份 */
    fun describe(): String = listOf(encoder, decoder, joiner, tokens)
        .joinToString(", ") { "${it.name} (${it.length()}B)" }
}

/** 在一个目录里找一套 KWS 模型; 找不到就回 null, 由调用方给一句人话 */
internal fun wakeWordModelOf(directory: File): WakeWordModel? {
    val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
    if (files.isEmpty()) return null

    fun pick(prefix: String): File? = files
        .filter { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
        .sortedBy { if (it.name.contains(".int8.")) 0 else 1 }
        .firstOrNull()

    return WakeWordModel(
        encoder = pick("encoder") ?: return null,
        decoder = pick("decoder") ?: return null,
        joiner = pick("joiner") ?: return null,
        tokens = files.firstOrNull { it.name == "tokens.txt" } ?: return null,
    )
}

/**
 * 模型认得的那张符号表
 *
 * sherpa-onnx 的 keywords 文件里每一行是 token 序列, 而 `EncodeKeywords` 只认这张表里有的符号:
 * 中文原文 (比如「素云」) 一个都不在表里, 它不报错, 只是**那一行被静默丢掉** —— 结果是"照做了
 * 但永远不触发"。所以写词表之前先对一遍, 对不上就当面说
 */
internal fun symbolsOf(tokens: File): Set<String> = tokens.readLines()
    .mapNotNull { line -> line.substringBefore(' ').trim().takeIf { it.isNotEmpty() } }
    .toSet()

/** 一行关键词里, 出现在 `@` 之前、又不在符号表里的那些 token */
internal fun unknownTokens(line: String, symbols: Set<String>): List<String> = line
    .substringBefore('@')
    .trim()
    .split(' ')
    .filter { it.isNotEmpty() && it !in symbols }

/** 一行关键词的显示名: `@` 之后那一段, 没有就回整行 */
internal fun keywordName(line: String): String =
    line.substringAfter('@', "").trim().ifEmpty { line.trim() }
