package io.github.miuzarte.littlewhale.tool

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import io.github.miuzarte.littlewhale.channel.LwAccessibility
import io.github.miuzarte.littlewhale.util.Capability
import io.github.miuzarte.littlewhale.util.PermissionGate
import io.github.miuzarte.littlewhale.workspace.Workspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 让这台手机拍一张照
 *
 * 走的是系统相机 (`ACTION_IMAGE_CAPTURE` + 一条 `FileProvider` 的 `content://`), 不引 CameraX:
 * 这个应用要做的是"请这台设备拍一张", 不是自己实现一个取景器 —— 而相机的取景、对焦、闪光灯、各家
 * ROM 的美颜开关全是相机应用的事
 *
 * **这条能力天生要有人在旁边**, 与录屏一样: 按下快门的是人, 不是模型, 而快门在手机的屏上 (相机
 * 应用被启到主屏, 不是我们的虚拟屏 —— 一块没人看的屏上按不了快门)。所以答案分两半: 相机开没开,
 * 以及那张照片有没有落下来
 *
 * 两处静默失败各自要一句实话:
 *
 * - **后台启动 activity 会被系统丢掉** (Android 10 起的 BAL), 而且它不抛异常。判据是这块屏上现在
 *   画着哪个应用 (通过无障碍那扇窗), 而不是"`startActivity` 返回了"
 * - **相机应用可能根本不往我们的文件里写** (有的实现忽略 `EXTRA_OUTPUT`, 只回一张缩略图), 所以
 *   等的是"那个文件真的有了内容", 不是"相机开过"
 */
internal object LwPhoto {

    private const val TAG = "LwPhoto"

    /** 照片落在工作区下的这个目录里 */
    private const val DIRECTORY = "photos"

    /** 默认等多久: 一个人举起手机按快门, 20 秒是一条合理的线 */
    private const val DEFAULT_WAIT_MS = 20_000L

    /** 最多等多久 */
    private const val MAX_WAIT_MS = 120_000L

    /** 轮询间隔 */
    private const val POLL_MS = 200L

    /** 等相机应用出现在屏上的时间, 过了就认为这次启动被丢了 */
    private const val CAMERA_UP_MS = 3_000L

    /**
     * 这条能力要的权限
     *
     * **相机应用去拍, 而我们自己也得握着 `CAMERA`**: 一个在清单里声明了相机权限却没有它的应用,
     * 调 `ACTION_IMAGE_CAPTURE` 会被系统直接拒 (SecurityException) —— 那是权限模型的一条规则, 与
     * 谁真的按快门无关
     */
    private val camera = Capability(
        name = "相机",
        why = "lw_take_photo 让系统相机去拍一张",
        permissions = listOf(Manifest.permission.CAMERA),
    )

    /** 拍一张: 开相机, 等照片落下来, 让媒体库看见它 */
    fun dispatch(context: Context, request: JsonObject): JsonObject {
        PermissionGate.refusal(context, camera)?.let {
            throw IllegalStateException(
                "$it. This app has to hold 相机 itself even though the camera app is the one" +
                    " taking the picture: Android refuses a capture intent from an app that" +
                    " declares the permission without holding it",
            )
        }
        val waitMs = (request["waitMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_WAIT_MS)
            .coerceIn(0L, MAX_WAIT_MS)
        val directory = File(Workspace.resolve(context).directory, DIRECTORY).apply { mkdirs() }
        val file = fresh(directory)
        // 相机应用要写的那个 uri 必须已经存在: 它拿到的是一条内容地址, 不是一条路径
        if (!file.createNewFile()) {
            throw IllegalStateException("could not make room for the photo at ${file.path}")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.lw.files", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 问一句谁接这个 intent, 只为了答案里能点名是哪个相机; **它空手而归不等于没有相机** ——
        // Android 11 起别的包对这个应用不可见, 靠的是清单里那条 queries 声明 (已经加上了)。所以
        // 查不到也照样去开, 真的没有接收者时 `startActivity` 会说出真相
        val handler = context.packageManager.queryIntentActivities(intent, 0).firstOrNull()
        val packageName = handler?.activityInfo?.packageName
        val label = handler?.let {
            runCatching { it.loadLabel(context.packageManager).toString() }.getOrNull()
        } ?: "the camera app"
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            file.delete()
            throw IllegalStateException(
                "no app on this device takes a picture: nothing answers" +
                    " android.media.action.IMAGE_CAPTURE, so there is no camera to open (or the" +
                    " camera app is disabled)",
            )
        } catch (error: Throwable) {
            file.delete()
            throw IllegalStateException(
                "the camera ($label) could not be opened: ${error.message}. Android only lets an" +
                    " app start an activity from the background in some cases (holding 显示在其他" +
                    " 应用上层 is one of them), so this is likely that: bring DSH-LW to the front" +
                    " and call again",
            )
        }

        // 后台启动被丢掉时不抛异常: 唯一看得见的判据是那块屏上现在画着谁
        if (packageName != null && !cameraCameUp(packageName, CAMERA_UP_MS)) {
            file.delete()
            val front = LwAccessibility.packageOn(0)
            val seen = when {
                !LwAccessibility.running -> {
                    "the accessibility service is off, so this cannot say what the screen shows" +
                        " instead"
                }
                front == null -> "nothing was showing on the phone's screen either"
                else -> "the phone's screen still shows $front"
            }
            throw IllegalStateException(
                "the camera ($label) did not come up on the phone's screen within" +
                    " ${CAMERA_UP_MS}ms, and $seen (the screen is ${LwSystem.screenState(context)}):" +
                    " Android drops an activity started from the background without saying so." +
                    " Nothing was written and the empty file this call made was removed - bring" +
                    " DSH-LW to the front, or grant 显示在其他应用上层 so this app may open the" +
                    " camera from the background",
            )
        }

        val started = System.currentTimeMillis()
        var elapsed = waitForPhoto(file, started + waitMs)
        // 到点了, 但相机还在这块屏上: 那一刻不是在等一个不在场的人, 而是在等人按快门或者按确认
        // (这套相机按了快门还会停在审核屏, 文件要等人点了 Done 才写下来 —— 实测 45 秒里有 40.8 秒
        // 花在那一下上, 所以默认那 20 秒会正好卡在审核屏上)。**只多等一次**, 总时长压在 MAX_WAIT_MS
        if (elapsed == null && cameraInFront(packageName)) {
            val spent = System.currentTimeMillis() - started
            val more = minOf(waitMs, MAX_WAIT_MS - spent)
            if (more > 0) elapsed = waitForPhoto(file, System.currentTimeMillis() + more)
        }
        if (elapsed == null) {
            // 相机已经不在屏上了, 那条 content:// 也就没人会再写: 空的留着只是垃圾; 而相机还在时
            // **不能删** —— 人再点一下 Done, 写的就是这个文件, 删掉等于把那张照片弄丢
            val waiting = cameraInFront(packageName)
            if (!waiting) file.delete()
            return text(
                "opened $label on the phone's screen, and nothing has been written after" +
                    " ${waitMs}ms${if (waiting) " (and a second helping of the same)" else ""}" +
                    " (the screen is now ${LwSystem.screenState(context)}): " +
                    if (waiting) {
                        "the camera is still open there, and a photo has to be taken by a person" +
                            " holding the phone. It will write to ${file.path} when they do, so" +
                            " ask them for it and then look at that path (lw_files op=list on" +
                            " photos/ works too): nothing was undone, the camera is still waiting"
                    } else {
                        "the camera was closed without taking anything, so the empty file this" +
                            " call made was removed. A photo has to be taken by a person holding" +
                            " the phone - ask them for one, or call this again with a longer" +
                            " waitMs (at most ${MAX_WAIT_MS}ms)"
                    },
            )
        }

        val answers = LwMedia.scan(context, listOf(file))
        return text(
            "$label took a photo on the phone's screen after ${seconds(elapsed)}: ${file.path}" +
                " (${LwFiles.shape(file)}). " + LwMedia.outcome(answers, file) +
                ". read_image on that path shows it to you, lw_open_file shows it to the person," +
                " and lw_share hands it to another app",
        )
    }

    /** 一个还没被占用的文件名, 同一秒里连着两次也不会撞 */
    private fun fresh(directory: File): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var candidate = File(directory, "photo-$stamp.jpg")
        var index = 2
        while (candidate.exists()) {
            candidate = File(directory, "photo-$stamp-$index.jpg")
            index++
        }
        return candidate
    }

    /** 等了多久, 说成 0.4s / 3.2s 这种 */
    private fun seconds(ms: Long): String = "%.1fs".format(ms / 1000.0)

    /**
     * 等这个应用出现在那块屏上
     *
     * 无障碍服务没开的时候回 true: 没有判据不等于没有发生, 这时不该把一个可能好好的启动说成失败
     * —— 后面等文件的那一段仍然会给出真实的结论
     */
    private fun cameraCameUp(packageName: String, timeoutMs: Long): Boolean {
        if (!LwAccessibility.running) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (LwAccessibility.packageOn(0) == packageName) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /** 等那个文件真的有内容, 回等了多久 (ms), 到点还是空的就回 null */
    private fun waitForPhoto(file: File, deadline: Long): Long? {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            if (file.length() > 0) {
                val elapsed = System.currentTimeMillis() - started
                Log.i(TAG, "a photo landed after ${elapsed}ms")
                return elapsed
            }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    /**
     * 相机还在这块屏上吗
     *
     * 判不出来的时候回 true (保持那个文件, 不假装相机已经走了): 那一边的处置是保守的, 而反过来的
     * 猜错会把一张正在被写的照片弄丢
     */
    private fun cameraInFront(packageName: String?): Boolean {
        if (packageName == null || !LwAccessibility.running) return true
        return LwAccessibility.packageOn(0) == packageName
    }
}
