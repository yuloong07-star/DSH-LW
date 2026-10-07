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
 * {"seq":7,"at":1759600000000,"text":"今天天气怎么样","source":"voice","wake":true}
 * ```
 *
 * **`wake` 是"这一句是唤醒之后的头一句"那个记号**: 只有**头一句**带它 —— 一句话被 VAD 切成两段时
 * 两段都带就会多标一句, 所以 [WakeWordService] 那边读一次就清。**2026-10-06 主人改了口径** ("只要
 * 是 1 小时内, 无论点球/喊唤醒词 都只在同一场对话", **2026-10-07 这个数改成 20 分钟**): 它只是个
 * 记号, 挑会话由宿主那侧按 `session.json` 那笔账定, 唤醒头句不再另开一场
 *
 * **`to` 是"这一句投给哪一场对话"那个点名** (同一天那条例外): 回复框在屏上时, 框里的键盘输入与
 * 开语音说的那一句都带上"发出那条回复的会话" —— 宿主那侧把它排在时间那笔账前面。没有回复框时
 * 这个键是缺的, 读起来与没写一样
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

    /** 浮标那一场对话的账本 (宿主写, [currentSession] 读): 与 `inbox.jsonl` 同一个目录 */
    const val SESSION_FILE = "session.json"

    /** 默认来源: 认出来的话 */
    const val SOURCE_VOICE = "voice"

    /**
     * 键盘那条路写进来的 (浮标上那块文字框): 与语音**同一个队列、同一个入口**
     *
     * 分成两个来源只是为了让宿主那边能说清"这一句是打的还是说的", 投递的去向完全一样
     */
    const val SOURCE_KEYBOARD = "keyboard"

    /**
     * 球自己写出去的 (现在只有一句: [VoiceCommands.INTERRUPT], 见 [OverlayService.interruptBall])
     *
     * 它**不是主人说的一句话**, 而是一个手势换来的命令 —— 分成第三个来源是为了排查时一眼看得出
     * "这一句是双击球来的", 而不是"主人说了什么被打断了"
     */
    const val SOURCE_BALL = "ball"

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
     *
     * [fresh] 是"这一句是唤醒之后的头一句"那个记号: 只有头一句传 true, 而**只有 true 才写进那一行**
     * —— 读者认的是一个"在不在"而不是一个布尔值, 所以老版本写的行 (没有这个键) 读起来与
     * `wake: false` 是同一个意思, 不会有新旧两种行要分辨; 而**它不决定新开一场** (见文件头那一段)
     *
     * [to] 是**回复框点名的那一场会话** (见文件头): 空 / 空白就是"没点名", 那样连键都不写 ——
     * 宿主那一侧读不到这个键与读到空串是一个意思
     */
    fun append(
        context: Context,
        text: String,
        source: String = "voice",
        fresh: Boolean = false,
        to: String? = null,
    ): Long? {
        val line = text.trim()
        if (line.isEmpty()) return null
        val pointed = to?.trim().orEmpty()
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
                    if (fresh) put("wake", true)
                    if (pointed.isNotEmpty()) put("to", pointed)
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

    /**
     * 浮标现在那一场对话是哪个会话 (`$DSH_HOME/voice/session.json`), 没有就是 null
     *
     * 那一份是**宿主写的** ([host-plugin] 的 `voiceSessionBump`: 每投一句就把它改成那一场), 应用这一侧
     * 只读它 —— 它在这里的用处只有一个: "回应用"要回到**浮标那一场对话**(主人 2026-10-06), 而应用这一侧
     * 要让会话界面切过去就必须知道是哪一个会话
     *
     * 读不出来 (文件不在 / 半行 / 字段不认识) 一律回 null, 那一档"回应用"就只把界面放到前面 —— 宁可少
     * 做一步, 也不猜一个会话 id 让界面跳到别处去
     */
    fun currentSession(context: Context): String? {
        // 与 [file] 同一个目录: 宿主那侧算的是 `join(dirname(inbox), 'session.json')`
        val target = File(file(context).parentFile, SESSION_FILE)
        val raw = runCatching { target.takeIf { it.isFile }?.readText() }.getOrNull() ?: return null
        val id = runCatching { json.parseToJsonElement(raw).jsonObject["id"]?.jsonPrimitive?.content }
            .getOrNull()
        return id?.takeIf { it.isNotBlank() }
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
