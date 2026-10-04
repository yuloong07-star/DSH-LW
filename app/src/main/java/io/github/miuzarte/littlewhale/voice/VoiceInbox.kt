package io.github.miuzarte.littlewhale.voice

import android.content.Context
import io.github.miuzarte.littlewhale.host.DshHost
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * 语音出字的投递队列: 应用这一侧写, 宿主那一侧读
 *
 * 为什么是一份文件而不是一条新的套接字: 现有的回环桥是**宿主问、应用答**, 方向反过来的话要么
 * 让宿主开一个监听口 (新的攻击面, 而且应用还得知道那个口在哪), 要么让应用去猜 dsh 的 HTTP 接口
 * (那是内部协议)。而这份文件落在 `$DSH_HOME` 里, 三个好处一次拿到 —— 宿主重启不丢、应用先写
 * 宿主后起也投得出去、`$DSH_HOME` 是真文件系统 (不是 `/sdcard` 那个 FUSE), 所以宿主那边的
 * inotify 是能用的
 *
 * 每一行一句话, JSON Lines:
 *
 * ```json
 * {"seq":7,"at":1759600000000,"text":"今天天气怎么样","source":"voice"}
 * ```
 *
 * **`seq` 是给读者去重用的**: 文件满了会从尾部留下若干行重写一遍, 于是读者的字节游标可能指到
 * 新文件之外。只靠游标去重会在那一刻重放, 而带上序号之后"已经投递过的那条"永远认得出。所以
 * 读者的正确读法是: 记下已投递的最大 seq, 只投递比它大的行
 *
 * 序号怎么续: 每次服务起来时从文件尾部读一次, 之后在内存里加。轮转之后尾部那行仍然在, 所以
 * 序号不会倒回 1
 */
internal object VoiceInbox {

    /** 目录名: 与宿主那侧 `voice/inbox.jsonl` 是同一个约定, 谁都不许单边改 */
    const val DIRECTORY = "voice"
    const val FILE_NAME = "inbox.jsonl"

    /** 超过这个大小就从尾部留 [KEEP_LINES] 行重写: 上千句话才可能走到, 到了也不该无限涨 */
    private const val CAP_BYTES = 1 shl 20
    private const val KEEP_LINES = 200

    /** 找尾部序号时最多回看几行: 末尾那半行最多占一行, 多给几行不怕 */
    private const val TAIL_LINES = 8

    private val json = Json { ignoreUnknownKeys = true }

    /** 只让一个写者进来: 投递线程与轮转都在这里串起来 */
    private val lock = Any()

    private var nextSeq: Long = -1

    /** 队列文件在哪: 宿主那侧按同一个表达式算 (它拿的是 DSH_HOME 环境变量) */
    fun file(context: Context): File =
        File(File(File(context.filesDir, DshHost.HOME_DIR), DIRECTORY), FILE_NAME)

    /**
     * 投一句话进去, 回它拿到的序号
     *
     * 回 null 表示**没写进去** (目录建不出来、磁盘满了), 调用方要如实说 —— "投出去了"与"写失败
     * 了"对主人是两件完全不同的事
     */
    fun append(context: Context, text: String, source: String = "voice"): Long? {
        val line = text.trim()
        if (line.isEmpty()) return null
        val target = file(context)
        synchronized(lock) {
            return try {
                target.parentFile?.mkdirs()
                if (target.length() > CAP_BYTES) rotate(target)
                if (nextSeq < 0) nextSeq = lastSeq(target)
                nextSeq += 1
                val record = buildJsonObject {
                    put("seq", nextSeq)
                    put("at", System.currentTimeMillis())
                    put("text", line)
                    put("source", source)
                }
                target.appendText("$record\n")
                nextSeq
            } catch (problem: Throwable) {
                // 序号只在真的写进去之后才算数, 失败时退回去, 不然读者会看到一段空洞
                if (nextSeq > 0) nextSeq -= 1
                null
            }
        }
    }

    /** 文件尾部那行的序号, 认不出来就是 0 (读的是最后几行, 不是整份) */
    private fun lastSeq(target: File): Long {
        if (!target.isFile) return 0
        val tail = target.readLines().takeLast(TAIL_LINES)
        for (line in tail.asReversed()) {
            val seq = seqOf(line)
            if (seq != null) return seq
        }
        return 0
    }

    /** 从尾部留 [KEEP_LINES] 行重写: 丢的是旧句子, 而 `seq` 让读者不会因此重放 */
    private fun rotate(target: File) {
        val kept = target.readLines().takeLast(KEEP_LINES)
        target.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
    }

    private fun seqOf(line: String): Long? = try {
        json.parseToJsonElement(line).jsonObject["seq"]?.jsonPrimitive?.long
    } catch (_: Throwable) {
        // 末尾可能留着半行 (进程被杀), 那一行就是没有序号
        null
    }
}
