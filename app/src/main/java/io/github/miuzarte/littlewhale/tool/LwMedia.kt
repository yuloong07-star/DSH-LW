package io.github.miuzarte.littlewhale.tool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import io.github.miuzarte.littlewhale.workspace.Workspace
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 媒体库那一侧的视野: 这台手机认得这个文件吗
 *
 * 写文件这件事 dsh 自己的文件工具就能做 (同一棵树, 同一个 uid), 而**让系统看见它**只有 app 这一侧
 * 做得到 —— 相册、音乐、文件管理器读的都是媒体库那几张表, 一个刚落进 `/sdcard` 的文件在那几张表里
 * 还不存在。所以这一层的两个方向是同一件事: [listed] 问"它认得吗", [scan] 说"现在认一下"
 *
 * 三条读数的纪律照旧, 而且这里最容易违反: **查得到就是查得到, 查不到要说"问不到"**。媒体库的查询
 * 要 `READ_MEDIA_*` (或者所有文件访问权限) 才看得见别的应用写进去的行, 没给权限时回来的是一个**空
 * 游标** —— 那与"这个文件真的没被扫过"是两件完全不同的事, 说成后者就会让人去重扫一个本来就在的表。
 * 同一个错在这个仓库里已经犯过一次 (读不到 secure settings 被说成"不在无障碍列表里"), 那一次之后
 * 三态就成了规矩
 */
internal object LwMedia {

    private const val TAG = "LwMedia"

    /** 一次扫描最多等多久, 每个文件一个回调 */
    private const val SCAN_TIMEOUT_MS = 5_000L

    /** 一个目录最多扫多少个文件 */
    private const val MAX_SCAN_FILES = 200

    /** 扫描器收下了但没加进去时的占位: `ConcurrentHashMap` 存不了 null */
    private val NOT_ADDED: Uri = Uri.EMPTY

    /**
     * 问媒体库: 这个目录下的文件它认得哪些
     *
     * @return 绝对路径的集合, **null 表示问不到** (没有读媒体的权限), 不是"一个都不认得"
     */
    fun listed(context: Context, directory: File): Set<String>? = ask(
        context,
        "${MediaStore.MediaColumns.DATA} LIKE ?",
        arrayOf(directory.absolutePath + File.separator + "%"),
    )

    /** 问媒体库: 它认得这一个文件吗, null 表示问不到 */
    fun knows(context: Context, file: File): Boolean? =
        ask(context, "${MediaStore.MediaColumns.DATA} = ?", arrayOf(file.absolutePath))
            ?.isNotEmpty()

    /** 真正的查询, 出任何事都回 null (问不到), 不假装是空集 */
    private fun ask(context: Context, selection: String, arguments: Array<String>): Set<String>? = try {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        @Suppress("DEPRECATION")
        val column = MediaStore.MediaColumns.DATA
        context.contentResolver
            .query(collection, arrayOf(column), selection, arguments, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndexOrThrow(column)
                buildSet { while (cursor.moveToNext()) cursor.getString(index)?.let { add(it) } }
            }
    } catch (error: Exception) {
        Log.w(TAG, "could not ask the media library: ${error.message}")
        null
    }

    /**
     * 让媒体库看见这些文件
     *
     * @return 路径 -> 媒体库给它的 uri。`Uri.EMPTY` 表示扫描器收了但**没加进去** (一个 `.nomedia`
     *   目录里的东西, 或者它根本不索引的类型), 而**压根不在这张表里的**表示等到超时它也没回话 ——
     *   那两种情况处置完全不同, 所以不能都写成"失败了"
     */
    fun scan(context: Context, files: List<File>): Map<String, Uri> {
        if (files.isEmpty()) return emptyMap()
        val latch = CountDownLatch(files.size)
        val answers = ConcurrentHashMap<String, Uri>()
        val listener = MediaScannerConnection.OnScanCompletedListener { path, uri ->
            answers[path] = uri ?: NOT_ADDED
            latch.countDown()
        }
        MediaScannerConnection.scanFile(
            context,
            files.map { it.absolutePath }.toTypedArray(),
            null,
            listener,
        )
        if (!latch.await(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "the media scanner left ${latch.count} of ${files.size} files unanswered")
        }
        return answers
    }

    /** 一个路径扫完之后该怎么说, 三种结果三种话 */
    fun outcome(answers: Map<String, Uri>, file: File): String {
        val uri = answers[file.absolutePath]
            ?: return "the media scanner did not answer within ${SCAN_TIMEOUT_MS / 1000}s, so whether" +
                " the library took it is not known yet"
        if (uri == NOT_ADDED) {
            return "the scanner took it but did not add it, which is what a `.nomedia` directory" +
                " or a type the library does not index looks like"
        }
        return "the library now lists it as $uri"
    }

    /**
     * 一句话说清一个文件在媒体库里的状态, 问不到时说的是问不到
     *
     * 这里有两层"看不到": 查询抛异常 (没有读媒体的权限, 或者 ROM 挡了), 以及**查询不抛异常但只
     * 回这个应用自己贡献过的行** —— 后者最像真的, 它会安安静静地把"别人的照片"说成"不在库里"。
     * 所以答"不在"之前先问一句"这一类文件我看得全吗"
     *
     * @param listed 在不在库里, **null 表示问不到**
     */
    fun line(context: Context, listed: Boolean?, file: File): String = when {
        listed == null -> "media library: cannot be asked (this app has no 读取媒体 permission)"
        listed -> "media library: listed"
        sees(context, file) -> "media library: not listed"
        else -> "media library: not listed where this app can see, and it cannot see rows it did" +
            " not add without 读取媒体, so this is not conclusive - lw_media_scan answers for sure"
    }

    /**
     * 这一类文件的媒体库行, 这个应用看得全吗
     *
     * `READ_MEDIA_*` 是按类型分的, 而所有文件访问把它一次盖掉。**没有它的时候查询仍然成功**,
     * 只是看不见别人写进去的行 —— 这正是"读不到不等于没有"最容易骗人的那一层
     */
    fun sees(context: Context, file: File): Boolean {
        if (Environment.isExternalStorageManager()) return true
        val permission = when (mimeOf(file.name)?.substringBefore('/')) {
            "image" -> Manifest.permission.READ_MEDIA_IMAGES
            "video" -> Manifest.permission.READ_MEDIA_VIDEO
            "audio" -> Manifest.permission.READ_MEDIA_AUDIO
            else -> Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /** 这个应用对媒体库的视野完不完整, 给"列一个目录"那句抬头用 */
    fun seesEverything(context: Context): Boolean = Environment.isExternalStorageManager() ||
        MEDIA_PERMISSIONS.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** 收窄过的清单: 只有这三个权限, 而且只提媒体那几类 */
    private val MEDIA_PERMISSIONS = listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
    )

    /** 一个媒体文件的类型, 由媒体库自己回答 (它没有就退到扩展名) */
    fun mimeOf(context: Context, uri: Uri?, file: File): String? = uri
        ?.takeIf { it != NOT_ADDED }
        ?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
        ?: mimeOf(file.name)

    /** 扩展名猜类型, 猜不到就是 null 而不是一个假装的 `application/octet-stream` */
    fun mimeOf(name: String): String? = name
        .substringAfterLast('.', "")
        .lowercase()
        .takeIf { it.isNotEmpty() }
        ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

    /**
     * 这个扩展名是媒体库会索引的那一类吗
     *
     * 只对会索引的类型提媒体库, 不然一个 `.txt` 后面跟一句"不在媒体库里"只是噪音
     */
    fun indexed(name: String): Boolean = mimeOf(name)?.let { mime ->
        mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")
    } ?: false

    /**
     * `lw_media_scan`: 让媒体库看见一个路径
     *
     * **不设工作区那道围栏**: 这件事是"请系统索引一下", 不让出任何内容, 而它最有用的场合恰恰在工作区
     * 外面 —— `lw_download` 把东西放进 `Download/`, 相册里却看不见, 问的就是这一条。文件**存不存在**
     * 这件事本来也不是秘密: 完全权限的会话里 dsh 自己的文件工具就能列
     *
     * 相对的路径按工作区算, 理由与 `lw_files` 一样 (那是 `lw_screenshot` 与 `lw_ui_dump` 落文件的地
     * 方), 而**绝对的照原样收** —— "让手机看见 Downloads 里那个文件"正是这条工具存在的理由
     */
    fun dispatch(context: Context, request: JsonObject): JsonObject {
        val asked = request.string("path")
        val root = Workspace.resolve(context).directory
        val file = if (File(asked).isAbsolute) File(asked) else File(root, asked)
        if (!file.exists()) {
            throw IllegalArgumentException(
                "there is nothing at ${file.path}" +
                    (if (file.path == File(asked).path) "" else " (a relative path is taken from" +
                        " the work area, ${root.path})"),
            )
        }
        val all = if (file.isDirectory) {
            file.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
                ?: throw IllegalStateException("this device did not let the app list ${file.path}")
        } else {
            emptyList()
        }
        val files = if (file.isDirectory) all.take(MAX_SCAN_FILES) else listOf(file)
        if (files.isEmpty()) {
            return text(
                "${file.path} is a directory with no files in it, so there was nothing to scan",
            )
        }
        val answers = scan(context, files)
        val rows = files.joinToString("\n") { "  ${it.name}: ${outcome(answers, it)}" }
        val tail = if (all.size > files.size) " (the first ${files.size} of ${all.size} files)" else ""
        val where = if (file.isDirectory) file.path else file.parent
        return text("asked the media library to index ${files.size} file(s) under $where$tail:\n$rows")
    }
}
