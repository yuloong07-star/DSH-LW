package io.github.miuzarte.littlewhale.tool

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import io.github.miuzarte.littlewhale.MainActivity
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.util.Capability
import io.github.miuzarte.littlewhale.util.PermissionGate
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 通知、震动、剪贴板、分享与下载
 *
 * 全是 app 进程里用 Context 就能做的事。共同点是**不需要特权**, 所以没有一行走 binder: 这里的
 * 东西直接执行, 结果由桥按同一套协议回给 host
 *
 * 每个函数回的是 `{"text": ...}` (见 [text]), 所以桥那一层只要 `-> LwNotify.xxx(...)` 一行
 */
internal object LwNotify {

    /** 给模型看的通知走这条通道, 与 host 那条常驻的通道分开: host 那条是低重要度的背景音 */
    private const val CHANNEL_ID = "lw-tools"

    /** 一条工具通知的固定 id: 重复调用是更新同一条, 不是堆一屏 */
    private const val NOTIFICATION_ID = 2

    /** 震动上限: 再长就没有意义, 而且会让人以为卡住了 */
    private const val MAX_VIBRATE_MS = 3_000

    private val vibrate = Capability(
        name = "震动",
        why = "lw_vibrate 与通知自带的震动",
        permissions = listOf(Manifest.permission.VIBRATE),
    )

    /** 发一条通知, 可以顺手震一下; 点它回到应用 */
    fun notify(context: Context, request: JsonObject): JsonObject {
        val title = request.string("title")
        val body = request.string("text")
        val vibrateMs = request.int("vibrateMs", 0).coerceIn(0, MAX_VIBRATE_MS)
        if (vibrateMs > 0) {
            PermissionGate.refusal(context, vibrate)?.let { throw IllegalStateException(it) }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: unavailable("notifications", "this device has no notification manager")
        ensureChannel(context, manager, vibrateMs > 0)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVibrate(if (vibrateMs > 0) longArrayOf(0, vibrateMs.toLong()) else null)
        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (error: SecurityException) {
            // POST_NOTIFICATIONS 没给时就是这一句, 明说而不是静默
            throw IllegalStateException(
                "the notification was refused (${error.message}): grant 通知 in the app's own" +
                    " Settings -> Permissions, or run tools/lw-install.ps1 -Perms",
            )
        }
        return text(
            "posted a notification titled ${quote(title)}${if (vibrateMs > 0) " with a ${vibrateMs}ms vibration" else ""}" +
                "; the same id is reused by the next call, so this one is replaced rather than stacked",
        )
    }

    /** 通知通道只在要震动时开震动, 别的不动 (用户的通道设置不该被反复洗掉) */
    private fun ensureChannel(context: Context, manager: NotificationManager, wantsVibration: Boolean) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_tools),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_tools_description)
            if (wantsVibration) enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** 震一下 */
    fun vibrate(context: Context, request: JsonObject): JsonObject {
        PermissionGate.refusal(context, vibrate)?.let { throw IllegalStateException(it) }
        val ms = request.int("ms", 200).coerceIn(1, MAX_VIBRATE_MS)
        val device = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?: context.getSystemService(Vibrator::class.java)
            ?: unavailable("vibration", "this device has no vibrator service")
        if (!device.hasVibrator()) unavailable("vibration", "this device has no vibrator")
        device.vibrate(VibrationEffect.createOneShot(ms.toLong(), 128))
        return text("vibrated for ${ms}ms")
    }

    /** 剪贴板: 写随时都行, 读要应用在前台 (Android 10 起只有获得焦点的应用能读) */
    fun clipboard(context: Context, request: JsonObject): JsonObject {
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: unavailable("the clipboard", "this device has no clipboard service")
        return when (val op = request.string("op")) {
            "get" -> {
                val clip = manager.primaryClip
                val value = if (clip == null || clip.itemCount == 0) {
                    ""
                } else {
                    clip.getItemAt(0).coerceToText(context).toString()
                }
                if (value.isEmpty()) {
                    throw IllegalStateException(
                        "the clipboard is empty, or this app is not the foreground app: since" +
                            " Android 10 only the focused app may read it. Bring DSH-LW to the" +
                            " front and call this again, or hand text out with lw_share instead",
                    )
                }
                text(value)
            }

            "set" -> {
                val value = request.string("text")
                manager.setPrimaryClip(ClipData.newPlainText("DSH-LW", value))
                text("wrote ${value.length} characters to the clipboard")
            }

            else -> throw IllegalArgumentException("op has to be get or set, not \"$op\"")
        }
    }

    /**
     * 把文本或文件交给别的应用
     *
     * 文件走 FileProvider: Android 10 起 `file://` 一律被拒, 而对方要的是读一份内容, 不是一条路径
     */
    fun share(context: Context, request: JsonObject): JsonObject {
        val body = request.stringOrNull("text")
        val path = request.stringOrNull("path")
        if (body == null && path == null) {
            throw IllegalArgumentException("this call has to name text or path, or both")
        }
        val send = if (path != null) {
            val (uri, guessed) = fileUri(context, path)
            Intent(Intent.ACTION_SEND).apply {
                type = request.stringOrNull("mime") ?: guessed
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                body?.let { putExtra(Intent.EXTRA_TEXT, it) }
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = request.stringOrNull("mime") ?: "text/plain"
                putExtra(Intent.EXTRA_TEXT, body)
            }
        }
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (error: Throwable) {
            unavailable("sharing", "no app on this device takes ${send.type}: ${error.message}")
        }
        return text(
            "handed ${if (path != null) quote(path) else "${body?.length ?: 0} characters"} to the" +
                " system chooser as ${send.type}; which app takes it is the user's pick, so this" +
                " only says the chooser opened",
        )
    }

    /** 用别的应用打开一个文件 */
    fun openFile(context: Context, request: JsonObject): JsonObject {
        val (uri, guessed) = fileUri(context, request.string("path"))
        val type = request.stringOrNull("mime") ?: guessed
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (error: Throwable) {
            unavailable("opening that file", "no app on this device takes $type: ${error.message}")
        }
        return text("opened $uri as $type; which app took it is the device's own default")
    }

    /** 下载一个 URL 到公共的下载目录 (工作是工作区的事, 下载是把东西交到手机上) */
    fun download(context: Context, request: JsonObject): JsonObject {
        val url = request.string("url")
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: unavailable("downloads", "this device has no download manager")
        val name = request.stringOrNull("to")
            ?: url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
            ?: "download"
        val id = try {
            manager.enqueue(
                DownloadManager.Request(Uri.parse(url))
                    .setTitle(name)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                    ),
            )
        } catch (error: Throwable) {
            throw IllegalStateException("the download was refused: ${error.message}")
        }
        return text(
            "queued download $id: $url -> Downloads/$name in shared storage. The system downloads" +
                " it on its own schedule, so this says it was queued rather than that it finished",
        )
    }

    /** 一个路径变成对方能读的 uri, 顺带猜一个 mime */
    private fun fileUri(context: Context, path: String): Pair<Uri, String> {
        val file = File(path)
        if (!file.isFile) throw IllegalArgumentException("there is no file at $path")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.lw.files", file)
        val mime = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
        return uri to mime
    }
}

/** 一段文字加个引号, 只说用到的地方 */
internal fun quote(value: String): String = "\"" + value.replace("\n", "\\n") + "\""
