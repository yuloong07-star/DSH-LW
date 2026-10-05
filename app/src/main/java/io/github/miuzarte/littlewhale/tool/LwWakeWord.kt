package io.github.miuzarte.littlewhale.tool

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.miuzarte.littlewhale.util.Capability
import io.github.miuzarte.littlewhale.util.PermissionGate
import io.github.miuzarte.littlewhale.voice.VoiceInbox
import io.github.miuzarte.littlewhale.voice.VoiceState
import io.github.miuzarte.littlewhale.wake.WakeWordModel
import io.github.miuzarte.littlewhale.wake.WakeWordService
import io.github.miuzarte.littlewhale.wake.WakeWordState
import io.github.miuzarte.littlewhale.wake.WakeWordWords
import io.github.miuzarte.littlewhale.wake.keywordName
import io.github.miuzarte.littlewhale.wake.symbolsOf
import io.github.miuzarte.littlewhale.wake.unknownTokens
import io.github.miuzarte.littlewhale.wake.wakeWordModelOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 唤醒词: 一直听着麦克风, 听到某个词就把 dsh 叫起来
 *
 * 这一层只管三件事 —— 模型在不在、词表写了什么、服务起没起。真正的监听与识别在
 * [WakeWordService] 里, 识别器是 [LwSpeech] 同一份 sherpa-onnx, 只是换成了 `KeywordSpotter`
 *
 * 词表那一半有个坑必须先说: sherpa-onnx 的 keywords 文件每一行是**模型的 token 序列**加一个
 * `@显示名`, 写中文原文进去不会报错, 只会被静默丢掉 —— 于是"照做了但永远不触发"。所以这一层
 * 拿模型自带的 tokens.txt 逐行对一遍, 对不上的 token 当面报出来, 不装作写成功了
 */
internal object LwWakeWord {

    /** 模型落在 filesDir 下的这一层, 与宿主侧的插件是约定, 谁都不许单边改 */
    const val MODEL_ROOT = "wake-word"

    /** 缺省那一套: 中文的 zipformer KWS, 3.3M 参数 */
    const val MODEL_NAME = "kws-zipformer-wenetspeech-3.3M"

    /** 词表文件名, 与模型自带的那个同名: 认的是内容, 不是名字 */
    private const val KEYWORDS_FILE = "keywords.txt"

    /** 设置页那一份人写的词表 (友好格式) 存在与别处同一个偏好文件里 */
    private const val STORE = "littlewhale"
    private const val WORDS_KEY = "wake-words"

    /** 这一个能力的名字, 与设置页里那条一致 */
    private val microphone = Capability(
        name = "麦克风",
        why = "唤醒词要一直听着麦克风",
        permissions = listOf(Manifest.permission.RECORD_AUDIO),
    )

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "status" -> status(context, request)
        "keywords" -> keywords(context, request)
        "start" -> start(context, request)
        "stop" -> stop(context)
        else -> throw IllegalArgumentException("op has to be status, keywords, start or stop, not \"$op\"")
    }

    /** 模型在哪儿: 缺省 filesDir/wake-word/kws-zipformer-wenetspeech-3.3M, 也可以点名别处 */
    private fun modelDirectory(context: Context, request: JsonObject): File =
        request.stringOrNull("model")?.let { File(it) } ?: directory(context)

    /** 同一个缺省目录, 给不经过通道的调用方用 (下载器与设置页都要它) */
    internal fun directory(context: Context): File = File(File(context.filesDir, MODEL_ROOT), MODEL_NAME)

    private fun keywordsFile(directory: File): File = File(directory, KEYWORDS_FILE)

    /**
     * 设置页那一份词表: 存的格式与人输入的一样 (`词=带音调数字拼音`), 没存过就是缺省那句
     *
     * 为什么不回读 keywords.txt: 那里面是 token 序列 (`d à f éi y ú @大肥鱼大肥鱼`), 给人编辑太难
     * 看, 而两种写法说的本来就是同一件事
     */
    internal fun words(context: Context): String = context
        .getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString(WORDS_KEY, null)
        ?.takeIf { it.isNotBlank() }
        ?: WakeWordWords.DEFAULT

    /** 现在的词表里那几个显示名 (素云 / 大肥鱼大肥鱼 …), 没有词表就是空的 */
    internal fun names(context: Context): List<String> {
        val file = keywordsFile(directory(context))
        if (!file.isFile) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.map { keywordName(it) }
    }

    /**
     * 收下设置页编辑过的那段文本: 先逐行对符号表, 全对才写, 写完把友好格式一起存下来
     *
     * 抛出来的句子直接给界面念 —— 对不上 token 时说清是哪一个, 不装作写成功了
     */
    internal fun setWords(context: Context, text: String): List<String> {
        val directory = directory(context)
        val table = File(directory, "tokens.txt")
        if (!table.isFile) {
            throw IllegalArgumentException("模型还没下, 没有符号表可以核词表 (先按「下载唤醒词模型」)")
        }
        val lines = WakeWordWords.lines(text, symbolsOf(table))
        if (lines.isEmpty()) throw IllegalArgumentException("词表不能是空的")
        directory.mkdirs()
        File(directory, KEYWORDS_FILE).writeText(lines.joinToString("\n") + "\n")
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString(WORDS_KEY, text.trim())
            .apply()
        return lines.map { keywordName(it) }
    }

    /** 引擎、模型与词表现在什么样: 页面与模型据此决定能不能开始听 */
    private fun status(context: Context, request: JsonObject): JsonObject {
        val directory = modelDirectory(context, request)
        val model = wakeWordModelOf(directory)
        val keywords = keywordsFile(directory)
        val lines = if (keywords.isFile) keywords.readLines().filter { it.isNotBlank() } else emptyList()
        val symbols = model?.tokens?.takeIf { it.isFile }?.let { symbolsOf(it) }.orEmpty()
        val unknown = lines.flatMap { unknownTokens(it, symbols) }.distinct()
        val refusal = PermissionGate.refusal(context, microphone)
        return buildJsonObject {
            put("model", MODEL_NAME)
            put("directory", directory.absolutePath)
            put("keywordsPath", keywords.absolutePath)
            put("present", model?.complete == true)
            put("modelFiles", model?.describe() ?: "")
            put("symbols", symbols.size)
            put("keywords", lines.joinToString(" | "))
            put("keywordNames", lines.joinToString(", ") { keywordName(it) })
            put("unknownTokens", unknown.joinToString(" "))
            put("permission", refusal == null)
            put("listening", WakeWordState.listening)
            put("liveKeywords", WakeWordState.keywords.joinToString(", "))
            put("hits", WakeWordState.hits)
            put("lastKeyword", WakeWordState.lastKeyword ?: "")
            put("lastHitAt", WakeWordState.lastHitAt)
            put("startedAt", WakeWordState.startedAt)
            put("microphoneForeground", WakeWordState.microphoneForeground)
            put("lastError", WakeWordState.lastError ?: "")
            // 常驻语音链那三个"成不成"与它切出来的东西: 采集 / 切段 / 出字
            put("capturing", VoiceState.capturing)
            put("vadReady", VoiceState.vadReady)
            put("vadDetail", VoiceState.vadDetail)
            put("asrReady", VoiceState.asrReady)
            put("asrDetail", VoiceState.asrDetail)
            put("segments", VoiceState.segments)
            put("recognized", VoiceState.recognized)
            put("pending", VoiceState.pending)
            put("dropped", VoiceState.dropped)
            put("lastTruncated", VoiceState.lastTruncated)
            put("lastText", VoiceState.lastText ?: "")
            put("lastAt", VoiceState.lastAt)
            put("delivered", VoiceState.delivered)
            put("lastSeq", VoiceState.lastSeq)
            put("speaking", VoiceState.speaking)
            put("inbox", VoiceInbox.file(context).absolutePath)
            put(
                "text",
                table(
                    listOf(
                        "model" to MODEL_NAME,
                        "directory" to directory.absolutePath,
                        "model on disk" to (model?.let { it.complete.toString() } ?: "no"),
                        "symbols in tokens.txt" to symbols.size.toString(),
                        "keywords" to (lines.joinToString(" | ").ifEmpty { "none" }),
                        "tokens not in the model's table" to (unknown.joinToString(" ").ifEmpty { "none" }),
                        "microphone permission" to (refusal ?: "granted"),
                        "listening" to WakeWordState.listening.toString(),
                        "hits" to WakeWordState.hits.toString(),
                        "last heard" to (WakeWordState.lastKeyword ?: "nothing yet"),
                        "capture running" to VoiceState.capturing.toString(),
                        "silero VAD" to VoiceState.vadDetail,
                        "SenseVoice" to VoiceState.asrDetail,
                        "segments cut" to "${VoiceState.segments} (recognised ${VoiceState.recognized},"
                            .plus(" pending ${VoiceState.pending}, dropped ${VoiceState.dropped})"),
                        "last text" to (VoiceState.lastText ?: "nothing yet"),
                        "queued for the host" to "${VoiceState.delivered} line(s), last #${VoiceState.lastSeq}",
                        "inbox" to VoiceInbox.file(context).absolutePath,
                        "reading aloud" to VoiceState.speaking.toString(),
                        "last problem" to (WakeWordState.lastError ?: "none"),
                    ),
                ),
            )
        }
    }

    /**
     * 写词表
     *
     * 每一行都是 token 序列加 `@显示名`, 例如 `s ù y ún @素云`。逐行核对模型那张符号表, 有对不上
     * 的就整批不写 (半张词表比没有词表更难看: 有的词会触发, 有的永不触发, 而人说不出为什么)
     */
    private fun keywords(context: Context, request: JsonObject): JsonObject {
        val directory = modelDirectory(context, request)
        val file = keywordsFile(directory)
        val model: WakeWordModel? = wakeWordModelOf(directory)
        val symbols = model?.tokens?.takeIf { it.isFile }?.let { symbolsOf(it) }
            ?: unavailable(
                "writing the keywords table",
                "the model's tokens.txt is not in ${directory.absolutePath}, so there is nothing to" +
                    " check the keywords against: run `lw_wakeword op=prepare` first",
            )
        val asked = request.strings("lines").map { it.trim() }.filter { it.isNotEmpty() }
        if (asked.isEmpty()) {
            val current = if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
            return buildJsonObject {
                put("written", false)
                put("keywordsPath", file.absolutePath)
                put("keywords", current.joinToString(" | "))
                put("text", "this table is what it is; name lines=<one line per keyword> to replace it")
            }
        }
        val malformed = asked.filterNot { it.contains('@') }
        if (malformed.isNotEmpty()) {
            throw IllegalArgumentException(
                "each line has to end with @ its display name, for example `s ù y ún @素云`;" +
                    " these do not: ${malformed.joinToString(" | ")}",
            )
        }
        val unknown = asked.flatMap { unknownTokens(it, symbols) }.distinct()
        if (unknown.isNotEmpty()) {
            unavailable(
                "writing the keywords table",
                "these tokens are not in the model's table, and sherpa-onnx would drop their whole" +
                    " line without saying so: ${unknown.joinToString(" ")}. Check the pinyin against" +
                    " the model's tokens.txt in ${directory.absolutePath}",
            )
        }
        directory.mkdirs()
        file.writeText(asked.joinToString("\n") + "\n")
        return buildJsonObject {
            put("written", true)
            put("keywordsPath", file.absolutePath)
            put("keywords", asked.joinToString(" | "))
            put("keywordNames", asked.joinToString(", ") { keywordName(it) })
            put("text", "the table now holds ${asked.size} keyword(s): ${asked.joinToString(" | ")}")
        }
    }

    /**
     * 开始听
     *
     * 麦克风那条权限先过闸: 缺权限时回的是"缺哪一条、怎么给", 不是起一个听不见的服务
     */
    private fun start(context: Context, request: JsonObject): JsonObject {
        PermissionGate.refusal(context, microphone)?.let { throw IllegalStateException(it) }
        val directory = modelDirectory(context, request)
        val model = wakeWordModelOf(directory)
        val keywords = keywordsFile(directory)
        if (model?.complete != true) {
            unavailable(
                "starting the wake word",
                "the model is not in ${directory.absolutePath}: run `lw_wakeword op=prepare` first",
            )
        }
        if (!keywords.isFile || keywords.readLines().none { it.isNotBlank() }) {
            unavailable(
                "starting the wake word",
                "there is no keywords table at ${keywords.absolutePath}: run `lw_wakeword" +
                    " op=keywords lines=[...]` first",
            )
        }
        val intent = Intent(context, WakeWordService::class.java).apply {
            putExtra(WakeWordService.EXTRA_MODEL_DIR, directory.absolutePath)
            putExtra(WakeWordService.EXTRA_KEYWORDS_FILE, keywords.absolutePath)
            putExtra(WakeWordService.EXTRA_THRESHOLD, request.number("threshold", DEFAULT_THRESHOLD))
            putExtra(WakeWordService.EXTRA_SCORE, request.number("score", DEFAULT_SCORE))
            putExtra(
                WakeWordService.EXTRA_ON_WAKE,
                if (request.string("onWake", WakeWordService.WAKE_TO_APP) == WakeWordService.WAKE_TO_OVERLAY) {
                    WakeWordService.WAKE_TO_OVERLAY
                } else {
                    WakeWordService.WAKE_TO_APP
                },
            )
            putExtra(WakeWordService.EXTRA_VIBRATE_MS, request.int("vibrateMs", DEFAULT_VIBRATE_MS))
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Throwable) {
            unavailable("starting the wake word service", error.message ?: error.toString())
        }
        return buildJsonObject {
            put("started", true)
            put("model", MODEL_NAME)
            put("directory", directory.absolutePath)
            put(
                "keywords",
                keywords.readLines().filter { it.isNotBlank() }.joinToString(" | "),
            )
            put("text", "the service is up; it listens on the microphone and reports a hit in status")
        }
    }

    /** 停: 服务一停, 识别器、麦克风与那条常驻通知一起收 */
    private fun stop(context: Context): JsonObject {
        val stopped = hush(context)
        return buildJsonObject {
            put("stopped", true)
            put("hits", WakeWordState.hits)
            put("detail", if (stopped) "the listener was stopped" else "no listener was running")
        }
    }

    /**
     * 起监听, 用缺省那几个数
     *
     * 设置页那个开关走这一条, 与通道那条 ([start]) 是同一个实现 —— 界面与模型各有一套参数的话,
     * "开关开着而模型那边调到别处"这种状态迟早会出现
     */
    internal fun listen(context: Context): JsonObject = start(
        context,
        buildJsonObject {
            put("threshold", DEFAULT_THRESHOLD)
            put("score", DEFAULT_SCORE)
            put("vibrateMs", DEFAULT_VIBRATE_MS)
        },
    )

    /** 停监听: 回"有没有真的停掉一个" */
    internal fun hush(context: Context): Boolean {
        val stopped = context.stopService(Intent(context, WakeWordService::class.java))
        WakeWordState.listening = false
        return stopped
    }

    /** 与 sherpa-onnx 自己的缺省值一致 */
    private const val DEFAULT_THRESHOLD = 0.25
    private const val DEFAULT_SCORE = 1.5
    private const val DEFAULT_VIBRATE_MS = 200
}
