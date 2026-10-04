package io.github.miuzarte.littlewhale.tool

import android.content.Context
import android.graphics.BitmapFactory
import io.github.miuzarte.littlewhale.workspace.Workspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 工作区里那几个文件, 以及**手机这一侧怎么看它们**
 *
 * 这一条要说清它为什么存在, 因为它很容易被写成一个重复的东西: dsh 自己就带着 `read` / `write` /
 * `glob`, 而工作区是那套工具的家 (进程 cwd 与 home 都在那儿), 所以"在工作区里读写一个文件"这件事
 * 模型本来就会做。加一条同名的工具只会让它在两个都行的选择之间犹豫
 *
 * 真正只有 app 这一侧拿得到的是三样:
 *
 * - **手机怎么看这个文件**: 媒体库里有没有它 (相册、音乐、文件管理器读的就是那张表), 系统认的
 *   mime 是哪一条, 一张图多大。写完一个文件之后 dsh 那侧看不见"相册里还是空的"这件事
 * - **一个不随会话目录漂移的锚**: 工作区是 app 侧解析出来的 (`Workspace.resolve`), 而会话的目录
 *   是用户在界面里选的 —— `lw_screenshot` 与 `lw_ui_dump` 落在工作区, 相对路径也就该有一个固定的
 *   起点
 * - **写完顺手让系统看见**: 两步动作要让模型记住两步, 而它通常只会记住一步
 *
 * 围栏照计划书: **只认工作区里的路径**, 越界一律拒。这不是安卓层面的隔离 (应用本来就握着所有文件
 * 访问权限), 而是与 dsh 自己的 fs 策略对齐 —— 已经有一条能读整个 `/sdcard` 的路 (会话自己的文件
 * 工具), 不必再开第二条
 */
internal object LwFiles {

    /** 一个目录最多列多少条 */
    private const val MAX_ENTRIES = 200

    /** 一次能读回多少字节, 再大就该交给会话自己的 read (它带 offset 与 limit) */
    private const val MAX_READ_BYTES = 256 * 1024

    /** 判定"这不是文本"时看开头多少字节 */
    private const val SNIFF_BYTES = 4096

    /** 取图片尺寸只读文件头, 所以这个上限只防"一个目录里两千张图"这种 */
    private const val MAX_DIMENSIONS = 60

    /** `lw_files` 的三个动作 */
    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "list" -> list(context, request)
        "read" -> read(context, request)
        "write" -> write(context, request)
        else -> throw IllegalArgumentException("op has to be list, read or write, not \"$op\"")
    }

    /**
     * 把调用给的路径落到工作区里
     *
     * 相对的按工作区根算, 绝对的也必须在工作区里面 —— 两条都先 `canonicalFile`, 因为设备上
     * `/sdcard` 是 `/storage/emulated/0` 的一个符号链接, 不规范化会让同一个文件有两种写法
     */
    private fun inWorkspace(context: Context, path: String?): File {
        val root = Workspace.resolve(context).directory.canonicalFile
        val asked = path?.trim().orEmpty()
        val file = when {
            asked.isEmpty() -> root
            File(asked).isAbsolute -> File(asked)
            else -> File(root, asked)
        }
        val resolved = file.canonicalFile
        if (resolved != root && !resolved.path.startsWith(root.path + File.separator)) {
            throw IllegalArgumentException(
                "${resolved.path} is outside the work area (${root.path}), so nothing was done:" +
                    " this tool is anchored to the work area on purpose. This session's own file" +
                    " tools (read, write, glob) are not, and they are the ones to use for anywhere" +
                    " else",
            )
        }
        return resolved
    }

    /** 列一个目录, 带手机那一侧的读数 */
    private fun list(context: Context, request: JsonObject): JsonObject {
        val directory = inWorkspace(context, request.stringOrNull("path"))
        if (!directory.exists()) {
            throw IllegalArgumentException("there is nothing at ${directory.path}")
        }
        if (!directory.isDirectory) {
            throw IllegalArgumentException(
                "${directory.path} is a file, not a directory: read it with op=read",
            )
        }
        val children = directory.listFiles()
            ?: throw IllegalStateException(
                "the app could not list ${directory.path}, which on this device is usually a" +
                    " permission the app does not have for that directory (all files access is" +
                    " what shared storage needs)",
            )
        val listed = LwMedia.listed(context, directory)
        val sorted = children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val shown = sorted.take(MAX_ENTRIES)
        var budget = MAX_DIMENSIONS
        val rows = shown.joinToString("\n") { child ->
            val sized = child.isFile && budget > 0
            if (sized) budget--
            entry(context, child, listed, sized)
        }
        val directories = children.count { it.isDirectory }
        val files = children.size - directories
        val hidden = File(directory, ".nomedia").exists()
        return text(
            buildString {
                append(directory.path)
                append(" ($directories director${if (directories == 1) "y" else "ies"}, $files file")
                append(if (files == 1) ")" else "s)")
                if (sorted.size > shown.size) {
                    append("\nthe first ${shown.size} of ${sorted.size} entries, directories first")
                }
                if (hidden) {
                    append("\nthis directory holds a `.nomedia` file, so the media library skips")
                    append(" everything in it and nothing here will show up in the gallery")
                }
                if (listed == null) {
                    append("\nthe media library cannot be asked from this app (no 读取媒体")
                    append(" permission), so whether it lists these files is not known here")
                } else if (!LwMedia.seesEverything(context)) {
                    append("\nthis app has no all-files access, so its view of the media library")
                    append(" covers only the types it may read: a `not listed` below may mean")
                    append(" \"not visible to this app\" rather than \"not in the library\"")
                }
                append("\n")
                append(rows.ifEmpty { "  (empty)" })
            },
        )
    }

    /** 一行一条: 名字 大小 类型 尺寸 媒体库 */
    private fun entry(
        context: Context,
        file: File,
        listed: Set<String>?,
        withDimensions: Boolean,
    ): String {
        if (file.isDirectory) return "  ${file.name}/  directory"
        val parts = mutableListOf(
            "  ${file.name}",
            bytes(file.length()),
            LwMedia.mimeOf(file.name) ?: "unknown type",
        )
        if (withDimensions) dimensions(file)?.let { parts += "${it.first}x${it.second}" }
        if (LwMedia.indexed(file.name)) {
            parts += LwMedia.line(context, listed?.let { file.absolutePath in it }, file)
        }
        return parts.joinToString("  ")
    }

    /** 读一个文本文件 */
    private fun read(context: Context, request: JsonObject): JsonObject {
        val file = inWorkspace(context, request.string("path"))
        if (!file.isFile) {
            throw IllegalArgumentException("there is no file at ${file.path}")
        }
        val size = file.length()
        if (size > MAX_READ_BYTES) {
            throw IllegalStateException(
                "${file.path} is ${bytes(size)}, past what this tool reads" +
                    " (${bytes(MAX_READ_BYTES.toLong())}): this session's own read tool takes an" +
                    " offset and a limit, so read it in pieces with that",
            )
        }
        val head = ByteArray(SNIFF_BYTES)
        val got = file.inputStream().use { it.read(head) }
        if (head.take(got.coerceAtLeast(0)).any { it == 0.toByte() }) {
            throw IllegalStateException(
                "${file.path} looks like a binary file (it has NUL bytes in its first" +
                    " $got bytes), and this tool reads text: if it is a picture," +
                    " read_image shows it to you and lw_open_file shows it to the person, and" +
                    " lw_share hands it to another app",
            )
        }
        val body = file.readText()
        val kind = LwMedia.mimeOf(file.name) ?: "unknown type"
        val library = if (LwMedia.indexed(file.name)) {
            ", " + LwMedia.line(context, LwMedia.knows(context, file), file)
        } else {
            ""
        }
        return text("${file.path} (${bytes(size)}, $kind$library)\n$body")
    }

    /** 写一个文本文件, 写完读回, 顺手让媒体库看见 */
    private fun write(context: Context, request: JsonObject): JsonObject {
        val file = inWorkspace(context, request.string("path"))
        if (file.isDirectory) {
            throw IllegalArgumentException("${file.path} is a directory, not a file to write")
        }
        val body = request["text"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("this call has to carry the text to write in \"text\"")
        val existed = file.isFile
        file.parentFile?.mkdirs()
        file.writeText(body)
        val back = if (file.isFile) file.readText() else null
        if (back != body) {
            throw IllegalStateException(
                "wrote ${body.length} characters to ${file.path} but reading it back gave" +
                    " ${back?.let { "${it.length} characters" } ?: "nothing"}: the write did not" +
                    " stick, so this is not reported as done",
            )
        }
        // 顺手让系统看见: 只对媒体库真会索引的类型做, 因为"相册能不能找到它"这句话对一个 .txt
        // 没有意义, 而对一张图就是全部意义 (写完一个文件之后 dsh 那一侧看不见"相册里还是空的")
        val library = if (LwMedia.indexed(file.name)) {
            "; " + LwMedia.outcome(LwMedia.scan(context, listOf(file)), file)
        } else {
            ""
        }
        return text(
            "wrote ${body.length} characters to ${file.path}" +
                (if (existed) " (replaced what was there)" else " (a new file)") +
                "; read back the same ${back.length} characters$library",
        )
    }

    /** 一张图的宽高, 只读文件头; 不是图或者读不出来就是 null */
    private fun dimensions(file: File): Pair<Int, Int>? {
        if (LwMedia.mimeOf(file.name)?.startsWith("image/") != true) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return options.outWidth to options.outHeight
    }

    /** 一个文件的样子: 尺寸 (图才有), 大小, 类型 —— 一句话, 给别的工具当答案的一半用 */
    fun shape(file: File): String = listOfNotNull(
        dimensions(file)?.let { "${it.first}x${it.second}" },
        bytes(file.length()),
        LwMedia.mimeOf(file.name) ?: "unknown type",
    ).joinToString(", ")
}
