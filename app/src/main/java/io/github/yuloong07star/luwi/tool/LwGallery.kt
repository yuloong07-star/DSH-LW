package io.github.yuloong07star.luwi.tool

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import io.github.yuloong07star.luwi.workspace.Workspace
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 把一张成图放进手机的相册
 *
 * 这是 p 图那条链的最后一站 (`lw_image op=album`): 图是上游生图接口给的, 字节在宿主那一侧读得出来,
 * 而**"相册里有它"只有应用这一侧做得到** —— 相册、文件管理器读的都是媒体库那几张表, 一个落在工作区
 * 里的文件在那张表里还不存在 (那条路是 `lw_media_scan`, 它说的是"请系统认一下这个文件", 说不了
 * "把它放进相册")
 *
 * **写的是 MediaStore, 不是往 `Pictures/` 里扔一个文件**: 从 Android 10 起一个应用可以往媒体库插入
 * 自己的图片而不需要任何权限, 而直接写 `/sdcard/Pictures` 需要"所有文件访问权限" —— 本机那份权限
 * 是可选给你的 (`Workspace.resolve` 的第一档), 所以直接用 MediaStore 这一条路在两种机器上都成立。
 * 插入分两半 (`IS_PENDING`): 先占一行再写字节, 没写完就被人看见的图不算一张图
 *
 * 打开那一步与 `lw_open_file` 同一条路: 交给系统里接图片的那个应用。**后台启动 activity 会被系统
 * 丢掉** (Android 10 起的 BAL), 而它不抛异常, 所以 `startActivity` 返回了不等于人看见了 —— 这里的
 * 判据只能是"那一句没抛", 所以答案说的是"请系统打开它", 不承诺它已经在眼前
 */
internal object LwGallery {

    private const val TAG = "LwGallery"

    /** 相册里那个目录的名字 (主人翻相册时看见的那一层) */
    private const val ALBUM = "Luwi"

    /**
     * `lw_image op=album`: 拷进相册, 顺手打开
     *
     * 参数: `source` (要拷的那张图的路径, 绝对的照原样, 相对的按工作区算), `name` (相册里叫什么),
     * `open` (要不要打开, 缺省开)
     */
    fun dispatch(context: Context, request: JsonObject): JsonObject {
        val source = picture(context, request.string("source"))
        val mime = LwMedia.mimeOf(source.name)
            ?: throw IllegalArgumentException(
                "the name ${source.name} does not say what kind of file it is, so this cannot tell" +
                    " whether it is a picture",
            )
        if (!mime.startsWith("image/")) {
            throw IllegalArgumentException(
                "${source.path} is $mime, and the album holds pictures: convert it first, or use" +
                    " lw_media_scan if it only has to be visible to the media library",
            )
        }
        val name = request.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: source.name
        val uri = insert(context, source, name, mime)
        val landed = landed(context, uri)
        val open = request.bool("open", true)
        val shown = if (open) open(context, uri, mime) else null
        return text(
            "put a copy of ${source.path} into the phone's album as ${landed.first} (${LwFiles.shape(source)})" +
                "; " + landed.second +
                (if (open) {
                    "\nopened it: " + (shown
                        ?: "the system was asked to show it - whether it came to the front is the" +
                            " device's own doing, and a background app is not always allowed to" +
                            " raise one, so say you filed it rather than that they are looking at it")
                } else {
                    "\nleft it closed: this call did not ask for it to be opened"
                }) +
                "\nthe work-area copy is still there; the album copy is the one the gallery lists",
        )
    }

    /**
     * 要拷的那张图
     *
     * 不设工作区那道围栏, 与 `lw_media_scan` 同一个理由: 这件事做的是"把这一张收进相册", 而它最有
     * 用的场合正是宿主刚写在工作区里的那张成图 —— 相对的路径按工作区算 (那是宿主写文件的地方),
     * 绝对的照原样收
     */
    private fun picture(context: Context, path: String): File {
        val root = Workspace.resolve(context).directory
        val asked = path.trim()
        val file = if (File(asked).isAbsolute) File(asked) else File(root, asked)
        if (!file.isFile) {
            throw IllegalArgumentException(
                "there is nothing at ${file.path}" +
                    (if (file.path == File(asked).path) "" else " (a relative path is taken from" +
                        " the work area, ${root.path})"),
            )
        }
        return file
    }

    /** 往媒体库插一行, 写完字节才把 `IS_PENDING` 摘掉 */
    private fun insert(context: Context, source: File, name: String, mime: String): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException(
                "the media library refused a row for $name, so nothing was filed",
            )
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("the row for $name has nowhere to write to")
            out.use { target -> source.inputStream().use { input -> input.copyTo(target) } }
        } catch (problem: Throwable) {
            // 写不进去就把那一行撤掉: 留一个 pending 的空行, 相册里会出现一张打不开的图
            runCatching { resolver.delete(uri, null, null) }
            throw IllegalStateException(
                "the picture could not be written into the album row for $name: ${problem.message}",
                problem,
            )
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /**
     * 写完读回来: 那一行真的在吗, 它自己说的名字是什么
     *
     * 媒体库可能给重名的文件改一个名字 (`x.png` → `x (1).png`), 所以名字与路径都要**以读回来的
     * 那一份为准**, 不是我们送进去的那一份 —— 主人按名字去找文件, 而"库里叫什么是它说的"
     */
    private fun landed(context: Context, uri: Uri): Pair<String, String> {
        val columns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA)
        try {
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    @Suppress("DEPRECATION")
                    val path = if (pathIndex >= 0) cursor.getString(pathIndex) else null
                    if (name != null) {
                        return name to when {
                            path != null -> "the library has it at $path"
                            else -> "the library lists it at $uri (this Android version does not" +
                                " hand out the plain path any more, and the uri is what other apps" +
                                " get)"
                        }
                    }
                }
            }
        } catch (problem: Throwable) {
            Log.w(TAG, "could not read the album row back", problem)
        }
        val fallbackName = uri.lastPathSegment ?: "that picture"
        return fallbackName to ("the library was given the row at $uri but did not read it back," +
            " so this cannot say what it is called")
    }

    /** 交给系统里接图片的那个应用; 回 null 表示那句没能发出去 (后台启动被挡会让它抛) */
    private fun open(context: Context, uri: Uri, mime: String): String? = try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        null
    } catch (problem: Throwable) {
        Log.w(TAG, "could not open the album picture", problem)
        "no app on this device took it (${problem.message}), so it is in the album but nothing" +
            " came up - open the gallery and it is under Pictures/$ALBUM"
    }
}
