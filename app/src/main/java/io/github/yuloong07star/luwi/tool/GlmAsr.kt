package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 智谱 GLM-ASR-Nano: 更强也更慢的那一条转写路
 *
 * 它不是一个库而是一个**常驻进程**: `libglmasr.so` (静态链了 llama.cpp 与 mtmd, 见
 * app/src/main/native/glmasr/) 起来时把那 1.6 GB 的模型读进内存, 之后每段录音只走一次
 * stdin/stdout 上的一问一答。这么做的理由与 liblauncher.so 一样 —— 安卓 10+ 不许 app 执行
 * 自己 data 目录里的东西, 而 nativeLibraryDir 里的可以
 *
 * **它比 SenseVoice 慢一个数量级**, 这是模型大小决定的: 1.5B 对 234M。实测 (天玑 9300,
 * Q4_K 主模型 + Q8_0 音频编码器, 8 线程, 补 4 秒窗口): 两三个字的话约 4 秒, 8 秒的话约
 * 10 秒。所以它当"听得准"的那一档, 而不是常驻语音链的那一档 —— 常驻那条依旧走 SenseVoice
 *
 * 与 SenseVoice 那一套的另一个差别: 模型不随 APK 发, 也不在 Maven 上, 由宿主侧的
 * `lw_speech op=prepare engine=glm` 下到 [modelDirectory] 里
 */
internal object GlmAsr {

    private const val TAG = "LwGlmAsr"

    /** 与 SenseVoice 共用 speech-models 这一层, 各自一个子目录 */
    const val MODEL_NAME = "glm-asr"

    /** 主模型: Q4_K, 935 MiB, 这是"最佳平衡"那一档 */
    const val MODEL_FILE = "model-q4k.gguf"
    const val MODEL_BYTES = 980_472_032L
    const val MODEL_SHA256 = "5d2fc1b22f90286b0d7141c821d9eac4e294cd6d6cc480d2d9bd7427c9c718af"

    /** 音频编码器: 上游只发了 BF16 与 Q8_0 两档, 没有 Q4_K, 所以这里取小的那一个 */
    const val MMPROJ_FILE = "mmproj-q8.gguf"
    const val MMPROJ_BYTES = 720_211_744L
    const val MMPROJ_SHA256 = "764227793db868b41b2e8dbe04ab2633cc503e1020466928ef95c60fd7fbb0d4"

    /** 那 30 秒静音窗口的替代值, 见 native 那一侧 `--pad-seconds` 的长注释 */
    private const val PAD_SECONDS = 4

    /** 一次转写的上限: 20 秒的话在这台设备上要 20 秒上下, 再慢就只能当它坏了 */
    private const val TRANSCRIBE_TIMEOUT_MS = 180_000L

    /** 起进程要把 1.6 GB 从 flash 读进来, 冷启动实测 3 到 5 秒, 给足余量 */
    private const val START_TIMEOUT_MS = 120_000L

    /** 进程的 stderr 留最后这么多行, 出事时报出去的就是它 */
    private const val STDERR_LINES = 40

    private class Session(val process: Process) {
        val answers = ArrayBlockingQueue<String>(4)
        val errors = ArrayBlockingQueue<String>(STDERR_LINES)
        var lastUsedAt = System.currentTimeMillis()
    }

    private val lock = Any()
    private var session: Session? = null

    fun modelDirectory(context: Context): File =
        File(File(context.filesDir, LwSpeech.MODEL_ROOT), MODEL_NAME)

    fun modelFile(context: Context): File = File(modelDirectory(context), MODEL_FILE)

    fun mmprojFile(context: Context): File = File(modelDirectory(context), MMPROJ_FILE)

    /** 两个文件都在位才算装好 (两个都要 sha256 对得上才写进那个目录) */
    fun ready(context: Context): Boolean =
        modelFile(context).isFile && mmprojFile(context).isFile

    fun status(context: Context): JsonObject {
        val directory = modelDirectory(context)
        val model = modelFile(context)
        val mmproj = mmprojFile(context)
        val held = synchronized(lock) { session }
        return buildJsonObject {
            put("model", MODEL_NAME)
            put("directory", directory.absolutePath)
            put("modelPath", model.absolutePath)
            put("modelBytes", model.length())
            put("modelExpectedBytes", MODEL_BYTES)
            put("modelSha256", MODEL_SHA256)
            put("mmprojPath", mmproj.absolutePath)
            put("mmprojBytes", mmproj.length())
            put("mmprojExpectedBytes", MMPROJ_BYTES)
            put("mmprojSha256", MMPROJ_SHA256)
            put("present", ready(context))
            put("loaded", held != null && held.process.isAlive)
            put("padSeconds", PAD_SECONDS)
        }
    }

    /** 认一段 wav; 缺模型时如实说缺, 而不是回一个空串 */
    fun transcribe(context: Context, wav: File): String {
        if (!wav.isFile) throw IllegalArgumentException("there is no recording at ${wav.absolutePath}")
        if (!ready(context)) {
            throw IllegalStateException(
                "the GLM-ASR model is not downloaded yet: run lw_speech op=prepare engine=glm once",
            )
        }
        val request = buildJsonObject { put("wav", wav.absolutePath) }.toString()
        val answer = synchronized(lock) {
            val live = ensureSession(context)
            val line = ask(live, request, TRANSCRIBE_TIMEOUT_MS)
            live.lastUsedAt = System.currentTimeMillis()
            line
        }
        if (answer == null) {
            val detail = failureDetail(context)
            release()
            throw IllegalStateException(detail)
        }
        val ok = answer.contains("\"ok\":true")
        val text = valueOf(answer, "text")
        if (!ok) {
            throw IllegalStateException("GLM-ASR refused ${wav.name}: ${valueOf(answer, "error")}")
        }
        return text.trim()
    }

    /** 把模型先读进内存: 第一次转写之前的那三五秒不该落在"我说完了在等"的那几秒里 */
    fun warmUp(context: Context): Boolean {
        if (!ready(context)) return false
        synchronized(lock) { ensureSession(context) }
        return true
    }

    /** 把那 1.6 GB 还回去 (进程退出, 模型与 KV cache 一起没) */
    fun release(): JsonObject {
        val held = synchronized(lock) {
            val current = session
            session = null
            current
        }
        if (held == null) {
            return buildJsonObject {
                put("released", false)
                put("detail", "no GLM-ASR process was running")
            }
        }
        runCatching {
            held.process.outputStream.bufferedWriter().use { it.write("{\"quit\":true}\n") }
        }
        held.process.destroy()
        return buildJsonObject {
            put("released", true)
            put("exitCode", runCatching { held.process.waitFor(2, TimeUnit.SECONDS) }.getOrElse { false }
                .let { if (it) held.process.exitValue() else -1 })
        }
    }

    private fun ensureSession(context: Context): Session {
        session?.let { if (it.process.isAlive) return it }
        session = start(context)
        return session!!
    }

    private fun start(context: Context): Session {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libglmasr.so")
        if (!binary.isFile) {
            throw IllegalStateException("the GLM-ASR runtime is missing at ${binary.absolutePath}")
        }
        val builder = ProcessBuilder(
            binary.absolutePath,
            "-m", modelFile(context).absolutePath,
            "--mmproj", mmprojFile(context).absolutePath,
            "--pad-seconds", PAD_SECONDS.toString(),
            "--threads", threads().toString(),
        )
        builder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
        val startedAt = System.currentTimeMillis()
        val process = builder.start()
        val created = Session(process)
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                    lines.forEach { created.answers.put(it) }
                }
            }.onFailure { Log.w(TAG, "the GLM-ASR reader stopped", it) }
        }, "lw-glmasr-out").apply { isDaemon = true }.start()
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.errorStream)).useLines { lines ->
                    lines.forEach { line ->
                        if (created.errors.remainingCapacity() == 0) created.errors.poll()
                        created.errors.put(line)
                    }
                }
            }.onFailure { Log.w(TAG, "the GLM-ASR log reader stopped", it) }
        }, "lw-glmasr-err").apply { isDaemon = true }.start()

        val ready = created.answers.poll(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ?: throw IllegalStateException("the GLM-ASR runtime said nothing in $START_TIMEOUT_MS ms")
        if (!ready.contains("\"ready\":true")) {
            process.destroy()
            throw IllegalStateException("the GLM-ASR runtime did not start: $ready")
        }
        Log.i(TAG, "GLM-ASR is up in ${System.currentTimeMillis() - startedAt} ms: $ready")
        return created
    }

    private fun ask(held: Session, request: String, timeoutMs: Long): String? {
        if (!held.process.isAlive) return null
        val writer: BufferedWriter = held.process.outputStream.bufferedWriter()
        writer.write(request)
        writer.write("\n")
        writer.flush()
        return held.answers.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** 出事时给人看的那一句: 进程自己的最后几行才是原因, 不要只报"超时" */
    private fun failureDetail(context: Context): String {
        val held = synchronized(lock) { session }
        val tail = held?.errors?.joinToString(" / ").orEmpty()
        val exit = held?.let { if (it.process.isAlive) "still running" else "exit ${it.process.exitValue()}" }
            ?: "no process"
        return "GLM-ASR did not answer in time ($exit)" + if (tail.isEmpty()) "" else ": $tail"
    }

    /**
     * 几线程: 这台设备 8 个核 (4 大 4 小), 全用上最快, 但 app 里还跑着 Node 与 WebView,
     * 所以留两个给它们
     */
    private fun threads(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 8)

    /** 从那一行 JSON 里取一个字符串字段: 协议那一层是固定的形状, 不为它拉一个解析器 */
    private fun valueOf(line: String, key: String): String {
        val needle = "\"$key\":\""
        val at = line.indexOf(needle)
        if (at < 0) return ""
        val from = at + needle.length
        val out = StringBuilder()
        var i = from
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                when (val next = line[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    else -> out.append(next)
                }
                i += 2
                continue
            }
            if (c == '"') break
            out.append(c)
            i++
        }
        return out.toString()
    }
}
