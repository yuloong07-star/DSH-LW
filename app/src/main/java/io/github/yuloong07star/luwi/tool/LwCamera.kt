package io.github.yuloong07star.luwi.tool

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import io.github.yuloong07star.luwi.channel.CameraWindow
import io.github.yuloong07star.luwi.util.Capability
import io.github.yuloong07star.luwi.util.PermissionGate
import io.github.yuloong07star.luwi.workspace.Workspace
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 本机摄像头抓帧: 自己开 Camera2, 预览画在我们那块小窗上, 抓下来的帧直接交给模型
 *
 * 为什么要有这一条: 虚拟屏那条路是"借一块屏 + 开相机应用 + 截屏" —— 相机在别人的进程里, 我们只能读屏,
 * 一次取景要起应用、要截屏、还要读回一张几百 KB 的 PNG。这里相机开在**我们自己进程**里, 同一个
 * CameraDevice 上挂一个 ImageReader 就能拿 JPEG 帧 (一次 capture 是两百到四百毫秒), 不用截屏、不用
 * 相机应用、也不占一块虚拟屏, 而画面照样有人看得见 (见 [CameraWindow] 那块小窗)
 *
 * 三件事与别的工具不同, 都要记住:
 *
 * 1. **相机是独占的**: 这条链开着的时候系统相机打不开, 反过来也一样。所以 `close` 不是可选项
 * 2. **后台开相机受限制**: 安卓 9 起就不许后台应用碰相机, 例外是应用持有一条 `camera` 类型的前台服务
 *    (安卓 10 起有类型这套, 14 起是硬要求), 而宿主那条前台服务只报 specialUse —— 起不来的原因会原样
 *    报出来 (见 [backgroundNote]), 不装作拍到了
 * 3. **帧是"一次性"的**: 每一张 Image 必须 close, 不然 ImageReader 那三格的队列很快满, 之后一张都
 *    拿不到。所以每抓一张就立刻读字节、落文件、放掉
 *
 * 抓下来的帧落在工作区的 `photos/` 里 (与 [LwPhoto] 的照片同一处, 人拿文件管理器看得见), 而 host 那侧
 * 与我们是同一个 uid, 所以插件能直接把文件读成附件
 */
internal object LwCamera {

    private const val TAG = "LwCamera"

    /** 与 `lw_take_photo` 同一处: 工作区下的 photos */
    private const val DIRECTORY = "photos"

    /** 这一条能力要的权限: 相机的运行时授权 */
    private val camera = Capability(
        name = "相机",
        why = "视频模式要自己开摄像头抓帧",
        permissions = listOf(Manifest.permission.CAMERA),
    )

    /** 抓下来的帧: 每张 JPEG 最多存三张在队列里, 多了就丢 (丢的那张当场 close) */
    private val frames = ArrayBlockingQueue<Image>(FRAME_QUEUE)

    private val lock = Any()

    /**
     * 切模式那两半跑在上面的那条线程
     *
     * **一条就够, 而且必须是一条**: 切模式是个序列 (视频 → 手机 → 识屏), 两条线程同时开收就成了两个
     * 顺序打架的相机操作。这里只保顺序, 真正互斥的还是 [lock] (见 [ensure] 与 [teardown])
     */
    private val switcher = Executors.newSingleThreadExecutor { work ->
        Thread(work, "lw-camera-switch").apply { isDaemon = true }
    }

    // 这些字段两头都在用 (开相机那条回调在相机线程上, 取帧与收工在桥的请求线程上), 而桥是**一次请求
    // 一条线程**, 所以每一个都要 volatile: 少了它, 下一次调用可能读到上一次的旧值 (最坏的样子是
    // "相机开着而 status 说没开", 于是又去开一次, 而那次会以"被自己占用"失败)
    @Volatile
    private var thread: HandlerThread? = null

    @Volatile
    private var handler: Handler? = null

    @Volatile
    private var cameraId: String? = null

    /**
     * 现在开的是哪一头
     *
     * 前后摄是**两个设备**: 换一头没有别的手段, 只能把旧的还回去再开新的 (所以 `open`/`snapshot` 带上
     * `lens` 时, 这一条链会自己收一次再开一次)
     */
    @Volatile
    private var facing = CameraCharacteristics.LENS_FACING_BACK

    @Volatile
    private var device: CameraDevice? = null

    @Volatile
    private var session: CameraCaptureSession? = null

    @Volatile
    private var reader: ImageReader? = null

    /** 抓帧的那一小段时间里不许重建 session (重建会把正要来的那张丢掉) */
    @Volatile
    private var capturing = false

    /**
     * 正在开相机 (从 openCamera 到会话建好)
     *
     * 这中间**不许有人重建会话**: 预览面是异步可用的, `onSurfaceTextureAvailable` 正好落在这一段里,
     * 而它触发的重建会把开相机那条路刚建好的会话关掉 —— 现象是"相机开不起来" (其实是预览那条路把
     * 自己的会话关了), 2026-10-05 在模拟器上连撞两次
     */
    @Volatile
    private var opening = false

    @Volatile
    private var shot = Size(0, 0)

    /**
     * 开这一趟相机时用的是多少像素那**一档清晰度**
     *
     * 抓帧的尺寸在开相机那一刻就定死了 (`ImageReader` 与预览缓冲都按它建), 所以设置页改了清晰度
     * 之后必须**重开一次**才生效。这一个数就是那个判据: [status] 拿它与 [VideoLooks.pixels] 比,
     * 不一样就在状态里说"下一次开相机才生效" (`sizePending`), 而 `op=rule` 就是当场重开那一条
     */
    @Volatile
    private var shotPixels = 0

    @Volatile
    private var preview = Size(0, 0)

    @Volatile
    private var rotation = 90

    /** 因为队列满被丢掉的帧: 不为零说明读得比拍得慢, 报出来而不是装作没发生 */
    @Volatile
    private var dropped = 0

    /** 这一趟开出摄像头之后抓过哪些文件, `close {clean:true}` 按它清理 */
    private val produced = mutableListOf<File>()
    @Volatile
    private var lastError: String? = null

    /**
     * 预览那条路自己的问题 (重复请求没挂上)
     *
     * 与 [lastError] 分开是**故意的**: 预览面只是给人看的, 它起不来照样能从 ImageReader 抓帧, 所以
     * 那不是"相机开不起来" —— 混在一起写过一版, 结果是"画面黑了"把一台能用的相机整条拆掉
     */
    @Volatile
    private var previewError: String? = null

    /**
     * 当前这条会话的输出里有没有那块预览面
     *
     * **会话的输出在建成那一刻就定死了**: 面要是晚一步才可用, 它就不在这条会话里, 而任何以它为目标的
     * 请求都会被相机拒掉 (`CaptureRequest contains unconfigured Input/Output Surface`) —— 那时唯一
     * 正确的动作是重建会话, 不是硬把面塞进请求里 (2026-10-05 就是这样让预览一直黑着的)
     */
    @Volatile
    private var sessionHasPreview = false

    /**
     * 会话的第几代
     *
     * `createCaptureSession` 是异步的, 而**旧那一次的 `onConfigured` 可能后到**: 它会把自己的会话写进
     * [session] 并把 [sessionHasPreview] 改回旧值, 于是新会话被旧的顶掉、预览又挂不上去。所以每次建会话
     * 领一个号, 回调里对不上号的当场关掉、什么都不改
     *
     * **这里不能取 [lock]**: 这个号是在 `onOpened` (相机线程) 里领的, 而开相机的那条请求线程正持着
     * [lock] 等 latch —— 在回调线程上等那把锁, 就是"latch 等不到、锁不放开"互相等死, 现象是**每次开相机
     * 都超时** (2026-10-05 自己踩的)。所以用一个原子数, 不碰锁
     */
    private val sessionGeneration = AtomicInteger(0)

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "status" -> status(context)
        "open" -> open(context, request)
        "snapshot" -> snapshot(context, request)
        "close" -> close(context, request)
        // 设置页改完那三条规则之后走这一条: 相机开着就重开一次 (尺寸要重开才生效)
        "rule" -> rule(context, request)
        else -> throw IllegalArgumentException("op has to be status, open, snapshot, close or rule, not \"$op\"")
    }

    /**
     * 切进视频模式时用它: **不等人** —— 相机开在另一条线程上, 当场回一句"在开"
     *
     * 为什么要这样: 开一条相机链是几百毫秒的活 (挑设备 → 按档位选尺寸 → 建 ImageReader → 摆小窗 →
     * 等 `onOpened` → 建会话), 而切模式那一步要的只是"这件事已经在做了"。排在回执里等它, 主人看到的
     * 就是"说完切视频模式之后卡一下"; 而**真相一点都没少**: [ensure] 全程握着 [lock], 所以紧接着来的
     * `lw_look` 会等在锁上, 开完了直接用那一台, 不会开出第二台相机 (前后摄是独占的两个设备)
     */
    internal fun warmUp(context: Context): String {
        if (device != null) return "the camera is already up on the ${lensName(facing)} lens"
        val app = context.applicationContext
        switcher.execute {
            runCatching { ensure(app, facing, required = false) }
                .onFailure { error ->
                    lastError = error.message ?: error.toString()
                    Log.w(TAG, "the camera asked for by the mode switch did not come up", error)
                }
        }
        return "the camera is coming up on the ${lensName(facing)} lens in the background"
    }

    /**
     * 切出视频模式 (去手机 / 识屏模式) 时用它: **也不等人**
     *
     * 与 [warmUp] 对称: 收是一条要还设备、关会话、放帧、收小窗的活, 而"切走了"这件事本身与它无关 ——
     * 切模式的回执不该等这条链。同样的取舍: 真相由后来者拿 (下一次 `camera op=status` 照实说)
     *
     * **不管看着开没开都排一次收工**, 只有回执那句话分两档: 刚切进视频模式又立刻切走时, 这边看到的
     * 还是"什么都没开"(开那条链排在 [switcher] 上还没轮到), 而**排一次就对了** —— 那一条线程是串行的,
     * 收工一定跑在开相机之后, 不会留下一台没人管的相机
     */
    internal fun coolDown(context: Context): String {
        val looked = device != null || reader != null || thread != null || CameraWindow.isOpen()
        val app = context.applicationContext
        switcher.execute {
            runCatching {
                // 与 `op=close {clean:true}` 同一份收工: 这一趟抓的帧一起删掉
                teardown(true)
                CameraWindow.hide()
            }.onFailure { error -> Log.w(TAG, "putting the camera away after a mode switch failed", error) }
        }
        return if (looked) {
            "the camera is being put away in the background"
        } else {
            "the camera was not open"
        }
    }

    /**
     * 设置页改完清晰度之后走这一条: 相机开着就按新的一档重开, 没开着就什么都不做
     *
     * 界面那一侧不走桥 (它就在应用进程里), 所以这一个是给它用的直通入口 —— 与 `op=rule` 同一份实现
     * ([rule]), 失败只记日志: 设置页拖一下滑块不该弹任何东西出来
     */
    internal fun reapply(context: Context) {
        runCatching { rule(context, buildJsonObject { put("pixels", VideoLooks.pixels) }) }
            .onFailure { Log.w(TAG, "the camera did not take the new capture size: ${it.message}") }
    }

    /**
     * 规则变了: 相机开着就**重开一次**让它生效
     *
     * 只有"尺寸"这一条真的需要它 —— 张数与间隔是每一次 `op=snapshot` 现读的 (见 [snapshot]), 所以
     * 改完立刻就是新的。尺寸不一样: 抓帧的 `ImageReader` 与那块预览缓冲都在开相机时按当时那一档建,
     * 而定下来的缓冲尺寸改不了 (与换镜头同一个道理, 见 [ensure])
     *
     * 相机没开着时什么都不做 —— "下一次开"本来就会读新的值
     */
    private fun rule(context: Context, request: JsonObject): JsonObject {
        val wanted = request.int("pixels", VideoLooks.pixels)
            .coerceIn(VideoLooks.MIN_PIXELS, VideoLooks.MAX_PIXELS)
        val live = device != null && session != null
        if (!live || shotPixels == wanted) {
            return buildJsonObject {
                put("applied", false)
                put("open", live)
                put("size", if (shot.width > 0) "${shot.width}x${shot.height}" else "")
                put("pending", live && shotPixels != wanted)
                put(
                    "text",
                    if (!live) {
                        "the camera is not open, so the next one will use $wanted px"
                    } else {
                        "the capture size is already the one on this setting (${shot.width}x${shot.height})"
                    },
                )
            }
        }
        synchronized(lock) {
            if (device != null || reader != null || thread != null) {
                teardown(false)
                // 重开要重摆那块预览缓冲: 它的尺寸是照抓帧那一档挑的, 缓冲区尺寸定下来就改不了
                // (与换镜头那条路一样, 见 [ensure])
                CameraWindow.hide()
            }
            opening = true
            try {
                bringUp(context, facing, false)
            } catch (error: Throwable) {
                teardown(false)
                CameraWindow.hide()
                throw error
            } finally {
                opening = false
                handler?.let { onCamera -> addLateSurface(onCamera) }
            }
        }
        return buildJsonObject {
            put("applied", true)
            put("open", true)
            put("size", "${shot.width}x${shot.height}")
            put("lens", lensName(facing))
            put("text", "the camera was reopened at ${shot.width}x${shot.height} for $wanted px")
        }
    }

    /** 现在什么样: 权限、摄像头开没开、小窗在不在、抓过几张、上一次出的什么问题 */
    private fun status(context: Context): JsonObject {
        val refusal = PermissionGate.refusal(context, camera)
        val ids = runCatching { context.getSystemService(CameraManager::class.java)?.cameraIdList?.toList() }
            .getOrNull().orEmpty()
        val open = device != null
        return buildJsonObject {
            put("permission", refusal == null)
            put("cameras", ids.size)
            put("cameraId", cameraId ?: "")
            // 报的是**实际**开着的那一头, 不是谁要的那一头
            put("lens", lensName(facing))
            put("open", open)
            put("session", session != null)
            put("window", CameraWindow.isOpen())
            put("shot", if (shot.width > 0) "${shot.width}x${shot.height}" else "")
            put("preview", if (preview.width > 0) "${preview.width}x${preview.height}" else "")
            put("frames", produced.size)
            // 丢帧是"取景比读帧快"的证据: 报一个数字, 而不是让人以为每一张都到手了
            put("lost", dropped)
            // **取景那三条规则** (设置页「视频识别」那一段): 缺省张数、每张间隔、这一档清晰度的像素数
            // —— `lw_look` 就是照这几个数给缺省的 (它自己没有设置可读), 而 `sizePending` 说的是
            // "清晰度改了但还没重开相机, 所以下一次抓帧还是老尺寸"
            put("lookCount", VideoLooks.count)
            put("lookIntervalMs", VideoLooks.intervalMs)
            put("lookPixels", VideoLooks.pixels)
            // 取景的几张要不要拼成一张网格: **拼图是插件做的**, 而它读的是应用写在 host 目录里的
            // 那个记号 (见 [VideoLooks.SHEET_KEY]) —— 这一项就是那个记号此刻在不在, 插件照它决定
            put("lookSheet", VideoLooks.sheetMarkPresent(context))
            put("sizePending", open && shotPixels != VideoLooks.pixels)
            put("previewError", previewError ?: "")
            put("lastError", lastError ?: "")
            put(
                "text",
                table(
                    listOf(
                        "camera permission" to (refusal ?: "granted"),
                        "cameras on this device" to ids.size.toString(),
                        "lens in use" to (if (device != null) lensName(facing) else "none (the camera is not open)"),
                        "camera open" to open.toString(),
                        "capture session" to (session != null).toString(),
                        "preview window" to CameraWindow.isOpen().toString(),
                        "snapshot size" to (if (shot.width > 0) "${shot.width}x${shot.height}" else "not chosen yet"),
                        "preview buffer" to (if (preview.width > 0) "${preview.width}x${preview.height}" else "not chosen yet"),
                        "frames kept" to produced.size.toString(),
                        "frames dropped" to dropped.toString(),
                        "frames per look" to VideoLooks.count.toString(),
                        "between frames" to "${VideoLooks.intervalMs}ms",
                        "one grid per look" to if (VideoLooks.sheetMarkPresent(context)) "yes" else "no",
                        "quality tier" to "${VideoLooks.pixels} px"
                            + (if (open && shotPixels != VideoLooks.pixels) " (the camera is still on the old size)" else ""),
                        "last preview problem" to (previewError ?: "none"),
                        "last problem" to (lastError ?: "none"),
                    ),
                ),
            )
        }
    }

    /**
     * 开摄像头并把那块小窗摆出来
     *
     * `lens` 点名要哪一头 (`front` / `back`, 缺省 back): 已经在开着而且就是那一头时什么都不做; 开着但
     * 是**另一头**时先还回去再开 (见 [ensure])
     */
    private fun open(context: Context, request: JsonObject): JsonObject {
        PermissionGate.refusal(context, camera)?.let { throw IllegalStateException(it) }
        val named = lensOf(request)
        ensure(context, named ?: facing, named != null)
        return status(context).let { state ->
            buildJsonObject {
                state.forEach { (key, value) -> put(key, value) }
                put("opened", true)
                put(
                    "text",
                    "the camera is open on the ${lensName(facing)} camera (${shot.width}x${shot.height} stills)"
                        + " and the preview window is"
                        + (if (CameraWindow.isOpen()) " up" else " not up")
                        + "; op=snapshot takes frames, op=close gives the camera back",
                )
            }
        }
    }

    /**
     * 抓几张: 一张一张来 (capture 完等那一张回来再发下一张)
     *
     * 为什么不一次发 N 张: ImageReader 那个队列只有三格, 连发会把前面几张顶掉, 而"到底拿到哪几张"就
     * 变得说不清。一张一张等回到的是**确定的那几张**, 代价是每张两百到四百毫秒
     *
     * 抓帧前先把重复请求停一下 (它占着这条会话的队列), 无论拍完、拍一半失败还是超时, 都在 finally 里
     * 挂回去 —— 少挂一次, 预览就是一块死画面而没人知道
     */
    private fun snapshot(context: Context, request: JsonObject): JsonObject {
        PermissionGate.refusal(context, camera)?.let { throw IllegalStateException(it) }
        // 张数与间隔都读设置页那一份 (主人 2026-10-06 要的那三条里的两条): 调用方点名时听它的 ——
        // 缺省值只有一处, 在 [VideoLooks], 而"越界怎么办"也只有那一处 ([VideoLooks.clampInterval])
        val count = request.int("count", VideoLooks.count).coerceIn(1, MAX_COUNT)
        val intervalMs = VideoLooks.clampInterval(request.int("intervalMs", VideoLooks.intervalMs))
        // `lens` 点名要哪一头: 没点名就用手上这一头 (镜头是**粘的** —— 上一眼看的是前摄, 这一眼就还在
        // 前摄, 换来换去每张都要多花一次开关设备的钱)
        val named = lensOf(request)
        ensure(context, named ?: facing, named != null)
        val directory = File(Workspace.resolve(context).directory, DIRECTORY).apply { mkdirs() }
        val onCamera = handler ?: unavailable("taking a frame", "the camera thread is not up")
        val stamp = System.currentTimeMillis()
        val files = mutableListOf<File>()
        // **量到的那几拍**: 要的是"墙上两张之间隔多久", 而一次抓帧本身要两三百毫秒, 所以真做到几拍
        // 只有量出来才算数 (与 `lw_screenshot` 连拍同一条纪律: 报要的那个数就是一句假话)
        val offsets = mutableListOf<Long>()
        val started = System.currentTimeMillis()
        try {
            synchronized(lock) {
                capturing = true
                runCatching { session?.stopRepeating() }
                repeat(count) { index ->
                    if (index > 0) sleepUntil(started, offsets.last(), intervalMs)
                    // 要的是**这一次**请求的那一帧: 上一趟留在队列里的 (比如超时之后才到的那张) 先丢掉,
                    // 不然交出去的会是上一张 —— 那是"拍到了"的假话
                    drainFrames()
                    captureStill(onCamera, index)
                    val image = try {
                        frames.poll(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        null
                    } ?: unavailable(
                        "taking frame ${index + 1} of $count",
                        "the camera handed over nothing within ${FRAME_TIMEOUT_MS}ms"
                            + (lastError?.let { " ($it)" } ?: ""),
                    )
                    offsets += System.currentTimeMillis() - started
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        val file = File(directory, "cam-$stamp-$index.jpg")
                        file.writeBytes(bytes)
                        files += file
                        produced += file
                    } finally {
                        image.close()
                    }
                }
            }
        } finally {
            // 拍完 / 拍一半失败 / 超时都走这里: 重复请求挂回去, 捕获标记放下。会话里要是还没有预览面
            // (面是抓帧当中才到手的), 那就顺手重建一次, 不然画面会一直黑着
            synchronized(lock) {
                capturing = false
                val live = session
                if (live != null && sessionHasPreview) startPreview(live, onCamera) else addLateSurface(onCamera)
            }
        }
        val elapsed = System.currentTimeMillis() - started
        return buildJsonObject {
            put("count", files.size)
            put("paths", files.joinToString(",") { it.absolutePath })
            put("elapsedMs", elapsed)
            put("shot", "${shot.width}x${shot.height}")
            // 这几张是**哪一头**拍的: 换过镜头之后, 不加这一项就分不清看图看的是哪一边
            put("lens", lensName(facing))
            put("lost", dropped)
            // **要的间隔与量到的间隔都报**: 一次抓帧两百到四百毫秒, 所以绝大多数设备上都做不到
            // 间隔短于"抓一张的耗时" —— 那时说"做到了"就是假话 (与连拍 `offsets` 同一条纪律)
            put("intervalMs", intervalMs)
            put("offsets", offsets.joinToString(","))
            put(
                "text",
                table(
                    listOf(
                        "frames" to files.size.toString(),
                        "size" to "${shot.width}x${shot.height}",
                        "took" to "${elapsed}ms (${if (files.isEmpty()) 0 else elapsed / files.size}ms each)",
                        "between frames" to describeGaps(offsets, intervalMs),
                        "files" to files.joinToString(", ") { it.name },
                    ) + if (dropped == 0) emptyList() else listOf("dropped" to "$dropped frame(s) were lost"),
                ),
            )
        }
    }

    /**
     * 隔到下一张该拍的那一刻为止
     *
     * 间隔说的是**墙钟上两张之间隔多久**, 不是"拍完再歇多久" —— 后者会让真实间隔随着这台设备忙闲
     * 漂移 (一张快一张慢, 于是同一段过程交出来的时间轴是歪的)。所以这里算的是"从上一张那一刻起
     * 该过多久", 已经过了就不再等
     *
     * @param started 这一趟的起点
     * @param last 上一张落地时距 [started] 多少毫秒
     */
    private fun sleepUntil(started: Long, last: Long, intervalMs: Int) {
        val want = last + intervalMs - (System.currentTimeMillis() - started)
        if (want <= 0) return
        try {
            Thread.sleep(want)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 那几拍量出来是多少: 与要的那个数差得多就直说这台设备拍不了那么快 */
    private fun describeGaps(offsets: List<Long>, intervalMs: Int): String {
        if (offsets.size < 2) return "only one frame, so no gap to measure"
        val gaps = offsets.zipWithNext { earlier, later -> later - earlier }
        val wanted = intervalMs.toLong() * (gaps.size)
        val measured = gaps.average().toLong()
        val note = if (measured > intervalMs * 1.5) {
            ", slower than asked: one capture costs a couple of hundred ms on its own"
        } else {
            ""
        }
        return "${gaps.joinToString(" / ")}ms, asked ${intervalMs}ms${note}"
    }

    /** 把摄像头还回去: 会话、设备、帧队列、小窗一起收; `clean` 时顺手删掉这一趟抓的文件 */
    private fun close(context: Context, request: JsonObject): JsonObject {
        val clean = request.bool("clean", false)
        val removed = teardown(clean)
        val window = CameraWindow.hide()
        return buildJsonObject {
            put("closed", true)
            put("window", false)
            put("removed", removed.size)
            put("text", "the camera is back (window ${if (window) "closed" else "was not up"})"
                + (if (clean) ", ${removed.size} frame file(s) removed" else ""))
        }
    }

    /**
     * 把这条链上的一切收干净: 会话 / 设备 / 帧队列 / ImageReader / 相机线程 / 这一趟的文件
     *
     * 三条路都走它 —— 正常收工 (`op=close`)、开不起来、抓帧失败: 相机是**独占**的, 留一个活的
     * CameraDevice 就是"系统相机打不开, 而我们还以为没事"。小窗不在锁里摘 (见 [close] 那一行):
     * `CameraWindow.hide` 要等主线程, 而主线程可能在等这把锁
     *
     * @param clean 顺手删掉这一趟抓的帧文件
     * @return 删掉的文件名
     */
    private fun teardown(clean: Boolean): List<String> {
        val removed = mutableListOf<String>()
        synchronized(lock) {
            capturing = false
            previewError = null
            sessionHasPreview = false
            sessionGeneration.incrementAndGet()
            runCatching { session?.close() }
            session = null
            runCatching { device?.close() }
            device = null
            runCatching { reader?.close() }
            reader = null
            drainFrames()
            if (clean) {
                produced.forEach { file -> if (file.delete()) removed += file.name }
                produced.clear()
            }
            runCatching { thread?.quitSafely() }
            thread = null
            handler = null
        }
        return removed
    }

    /** 把帧队列里还没被取走的 Image 放掉: 每一张都占着一块相机缓冲, 不放就是队列永远满 */
    private fun drainFrames() {
        while (true) {
            val image = frames.poll() ?: break
            runCatching { image.close() }
        }
    }

    /**
     * 把摄像头开起来 + 摆小窗: 已经开着就什么都不做
     *
     * 开不出来时**不把半个相机留在手里**: 会话、设备、reader、线程、小窗一起收掉再往上抛。相机是独占
     * 的, 留一个活的 CameraDevice 就是"系统相机也打不开, 而我们还以为没事"
     */
    /**
     * 把摄像头开起来 + 摆小窗
     *
     * @param lens 要哪一头
     * @param required 调用方**点名**要这一头: 那时只认它, 设备上没有就如实说没有 (见 [pickCamera])
     */
    private fun ensure(context: Context, lens: Int, required: Boolean) {
        synchronized(lock) {
            if (device != null && session != null && facing == lens) return
            // 换一头, 或者上一趟只开到一半: 先把手里这台还干净 —— 前后摄是**两个设备**, 不还旧的直接开
            // 新的会以"被自己占用"失败
            if (device != null || reader != null || thread != null) {
                val switching = device != null && facing != lens
                teardown(false)
                // 换一头时连小窗一起重来: 预览缓冲区的尺寸是按那一头的档位选的, 而缓冲区尺寸定下来就改不了
                if (switching) CameraWindow.hide()
            }
            opening = true
            try {
                bringUp(context, lens, required)
            } catch (error: Throwable) {
                teardown(false)
                CameraWindow.hide()
                throw error
            } finally {
                // 放开之后, 预览面那次"晚到的可用"才允许重建会话 (那时开相机这条路已经走完了) ——
                // 这一段里被挡掉的那些通知, 在这里按**当前**的面补一次
                opening = false
                handler?.let { onCamera -> addLateSurface(onCamera) }
            }
        }
    }

    /**
     * 真的去开: 挑摄像头 → 按 StreamConfigurationMap 选尺寸与格式 → 建 ImageReader → 摆小窗 → 等
     * onOpened → 建会话
     *
     * 全程回调, 一处 sleep 都没有: 唯一的"等"是带超时的 CountDownLatch (桥是一连接一请求, 它这头必须
     * 给出一个答案), 而超时与失败一样**如实报出来**, 不当成拍到了
     */
    private fun bringUp(context: Context, wanted: Int, required: Boolean) {
        val window = context.getSystemService(CameraManager::class.java)
            ?: unavailable("opening the camera", "this device has no camera manager")
        val ids = cameraIds(window)
        val chosen = pickCamera(window, ids, wanted, required)
            ?: unavailable(
                "opening the camera",
                if (ids.isEmpty()) {
                    "this device reports no camera at all"
                } else {
                    "no camera on this device faces ${lensName(wanted)}"
                },
            )
        val characteristics = runCatching { window.getCameraCharacteristics(chosen) }
            .getOrElse { unavailable("reading the camera", it.message ?: it.toString()) }
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: unavailable("reading the camera", "it publishes no stream configuration map")
        // 尺寸不是猜的: 从这张表里挑 JPEG 那一档, 取**设置页那一档清晰度**以内最大的一个
        // (见 [VideoLooks.pick]: 档位与"抓帧不是拍大片"是同一件事)
        shotPixels = VideoLooks.pixels
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            .map { size -> size.width to size.height }
        shot = VideoLooks.pick(jpegSizes, shotPixels)?.let { (width, height) -> Size(width, height) }
            ?: unavailable("opening the camera", "it publishes no JPEG size to capture into")
        // 预览面是 TextureView 的 SurfaceTexture (见 [CameraWindow]), 所以要问它那一档的尺寸;
        // 拿 SurfaceHolder 那一档去问是另一个 Surface 类, 在这里不对
        preview = closestTo(
            map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty(),
            PREVIEW_TARGET_PIXELS,
        ) ?: shot
        rotation = jpegRotation(characteristics, context)
        cameraId = chosen
        // 记**实际**开的那一头: 缺省要后摄而设备只有前摄时, 退到的那台是前摄, 报的也得是前摄
        facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: wanted
        if (thread == null) {
            val started = HandlerThread("lw-camera").apply { start() }
            thread = started
            handler = Handler(started.looper)
        }
        val onCamera = handler ?: unavailable("opening the camera", "the camera thread did not start")
        // 帧那台 ImageReader 必须在开相机之前就位: 第一次建 session 就要把它挂上, 不然会话只认预览,
        // 后面的 snapshot 会以"帧队列没起来"收场
        reader = ImageReader.newInstance(shot.width, shot.height, ImageFormat.JPEG, FRAME_QUEUE).apply {
            setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireNextImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                // 队列只有三格: 满了就当场放掉这一张并记数 —— 悄悄丢帧等于"你以为每一张都到手了"
                if (!frames.offer(image)) {
                    dropped += 1
                    runCatching { image.close() }
                }
            }, onCamera)
        }
        // 先摆小窗: 预览面要一点时间才出来, 先摆上后面那次建 session 就能带上它
        val said = CameraWindow.show(
            context = context,
            widthPx = preview.width,
            heightPx = preview.height,
            onSurface = { rebuildSession() },
            // 那个叉子是主线程上的点击, 而 close 里边要等主线程把窗口摘掉 —— 所以另起一条线程做
            onClose = {
                Thread(
                    { runCatching { close(context, buildJsonObject { put("clean", true) }) } },
                    "lw-camera-close",
                ).start()
            },
        )
        // 小窗只是给人看的: 它起不来照样能从 ImageReader 抓帧, 所以那是"记下来的问题", 不是开相机失败
        val windowTrouble = if (CameraWindow.isOpen()) null else said
        // 这一次尝试自己要清白: 上一次留下的问题不能让下面那句误判成"这一次也没开起来"
        lastError = null
        val latch = CountDownLatch(1)
        try {
            window.openCamera(chosen, object : CameraDevice.StateCallback() {
                override fun onOpened(opened: CameraDevice) {
                    device = opened
                    buildSession(onCamera, latch)
                }

                override fun onDisconnected(taken: CameraDevice) {
                    runCatching { taken.close() }
                    // 设备被抢走了, 会话跟着没了: 两个都要清, 不然 status 会报"会话还在"而其实什么都拍不了
                    runCatching { session?.close() }
                    session = null
                    sessionHasPreview = false
                    device = null
                    lastError = "another app took the camera over"
                    latch.countDown()
                }

                override fun onError(broken: CameraDevice, error: Int) {
                    runCatching { broken.close() }
                    runCatching { session?.close() }
                    session = null
                    sessionHasPreview = false
                    device = null
                    // 这些码各是一件事 (被占用 / 到台数上限 / 被策略或后台挡住 / 硬件 / 相机服务), 报一句
                    // "error 3" 等于什么都没说
                    lastError = when (error) {
                        // 这几个码声明在 StateCallback 里, 不是 CameraDevice 上 (SDK 源码: StateCallback:1507 起)
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ->
                            "another app is already using this camera"
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
                            "this device has as many cameras open as it allows"
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ->
                            "the camera is disabled by device policy, or by this app being in the background"
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE ->
                            "the camera hardware reported a fatal error"
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE ->
                            "the camera service stopped answering"
                        else -> "the camera device reported error $error"
                    } + backgroundNote()
                    latch.countDown()
                }
            }, onCamera)
        } catch (refused: CameraAccessException) {
            unavailable("opening the camera", whyOpenFailed(refused) + backgroundNote())
        } catch (security: SecurityException) {
            unavailable(
                "opening the camera",
                "this app is not allowed to use the camera: ${security.message ?: "the permission was refused"}"
                    + " (the camera permission is granted from Luwi's settings, under 权限)",
            )
        } catch (error: Throwable) {
            unavailable("opening the camera", (error.message ?: error.toString()) + backgroundNote())
        }
        if (!latch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            unavailable(
                "opening the camera",
                "the camera did not answer within ${OPEN_TIMEOUT_MS}ms" + backgroundNote(),
            )
        }
        // openCamera 回来了但没开成 (onError / onDisconnected / 会话建不起来): 那就是失败, 由 ensure 收摊
        lastError?.let { throw IllegalStateException("the camera would not come up: $it") }
        // 开到这儿是真的开了: 小窗没起来不影响抓帧, 但这件事要留下痕迹, 不能当没发生
        lastError = windowTrouble
        // 会话是在面可用之前建的那一种: 现在把它补进输出里 (开相机这一路走完, 期间的重建都被挡着)
        addLateSurface(onCamera)
    }

    /** 建会话: 预览面 + 那台 ImageReader 一起挂上 (抓帧只要后者, 前者是给人看的) */
    private fun buildSession(onCamera: Handler, latch: CountDownLatch?) {
        val current = device ?: run {
            latch?.countDown()
            return
        }
        val outputs = mutableListOf<OutputConfiguration>()
        // 只问一次面: 问两次的话两次之间它可能被收掉, 于是"以为带上它了"而其实没带
        val surface = CameraWindow.surfaceNow()
        val withPreview = surface != null
        surface?.let { outputs += OutputConfiguration(it) }
        reader?.let { outputs += OutputConfiguration(it.surface) }
        if (outputs.isEmpty()) {
            latch?.countDown()
            return
        }
        runCatching { session?.close() }
        session = null
        sessionHasPreview = false
        val generation = sessionGeneration.incrementAndGet()
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            Executor { command -> onCamera.post(command) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(ready: CameraCaptureSession) {
                    // 旧那一次的回调: 关掉它, 一个字段都不碰 (碰了就是把新会话顶掉)
                    if (generation != sessionGeneration.get()) {
                        runCatching { ready.close() }
                        latch?.countDown()
                        return
                    }
                    session = ready
                    // 这条会话的输出里到底有没有预览面, 决定后面能不能往上挂重复请求
                    sessionHasPreview = withPreview
                    Log.i(TAG, "capture session is up with ${outputs.size} output(s)")
                    // **重复请求才是"一直在预览"**: 会话建好只是"能拍", 不挂一条重复请求的话预览面
                    // 一张都不会收到 (第一版就是这样: 面挂上了、会话也 configured, 而画面全黑)
                    startPreview(ready, onCamera)
                    latch?.countDown()
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    if (generation != sessionGeneration.get()) {
                        latch?.countDown()
                        return
                    }
                    lastError = "the preview session would not come up (${outputs.size} output(s))"
                    Log.w(TAG, lastError!!)
                    latch?.countDown()
                }
            },
        )
        try {
            current.createCaptureSession(configuration)
        } catch (error: Throwable) {
            lastError = "creating the capture session failed: ${error.message}"
            runCatching { reader?.close() }
            reader = null
            latch?.countDown()
        }
    }

    /**
     * 挂一条重复请求: 预览面靠它一直有帧
     *
     * 会话建好只是"能拍", 真正让画面动起来的是这条 `setRepeatingRequest` —— 少了它, 面挂上了、会话也
     * configured, 而预览区一片黑 (2026-10-05 就是这么黑了一次)。抓帧那条 `capture` 是另一条路, 两者
     * 可以同时在
     */
    private fun startPreview(session: CameraCaptureSession, onCamera: Handler) {
        val dev = device ?: return
        // 没有小窗就是"没有人看", 这条本来就不该挂
        val surface = CameraWindow.surfaceNow() ?: return
        // 面在会话建成之后才到手: 这一条会话里没有它, 挂上去只会被相机拒掉, 该做的是重建 (见 [addLateSurface])
        if (!sessionHasPreview) {
            previewError = "the preview surface is not part of this capture session"
            return
        }
        runCatching {
            val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()
            session.setRepeatingRequest(request, null, onCamera)
            previewError = null
        }.onFailure { error ->
            // 记在 [previewError] 而不是 [lastError]: 画面起不来不等于相机不能用
            previewError = "the preview request would not start: ${error.message}"
            Log.w(TAG, previewError!!)
        }
    }

    /**
     * 面迟到了就重建一次会话
     *
     * 面的可用是异步的 (TextureView 挂上窗口之后才有), 而会话的输出在建成那一刻就定死了 —— 所以
     * "开完相机才发现面已经到手"与"抓完一张才发现面已经到手"都要走这里补一次
     */
    private fun addLateSurface(onCamera: Handler) {
        if (sessionHasPreview || CameraWindow.surfaceNow() == null) return
        buildSession(onCamera, null)
    }

    /** 预览面后来才有 (或换了一块) 时重建一次会话; 抓帧与开相机当中不重建 */
    private fun rebuildSession() {
        val onCamera = handler ?: return
        if (device == null) return
        onCamera.post {
            // 开相机那条路走完之前不重建 (它自己会在最后补一次, 见 [addLateSurface]); 抓帧当中也不重建,
            // 那会把手正要来的那一帧丢掉
            if (capturing || opening || device == null) return@post
            buildSession(onCamera, null)
        }
    }

    /**
     * 发一次拍照请求, 等它完成 (帧从那台 ImageReader 上回来)
     *
     * `capture` 是**单帧**那条路 (连续流是 `setRepeatingRequest`), 两者语义不混: 会话被关掉、相机被抢走
     * 时 `capture` 自己会抛, 那时如实报出是哪一张、为什么, 而不是当成"拍到了"
     */
    private fun captureStill(onCamera: Handler, index: Int) {
        val session = session ?: unavailable("taking frame ${index + 1}", "the capture session is not up")
        val dev = device ?: unavailable("taking frame ${index + 1}", "the camera is not open")
        val target = reader ?: unavailable("taking frame ${index + 1}", "the frame reader is not up")
        val request = try {
            dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(target.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, rotation)
            }.build()
        } catch (error: Throwable) {
            unavailable("taking frame ${index + 1}", "the capture request could not be built: ${error.message}")
        }
        val failure = arrayOfNulls<String>(1)
        val latch = CountDownLatch(1)
        try {
            session.capture(
                request,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        latch.countDown()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failed: CaptureFailure,
                    ) {
                        failure[0] = "the camera refused that capture (reason ${failed.reason}, frame ${failed.frameNumber})"
                        latch.countDown()
                    }
                },
                onCamera,
            )
        } catch (refused: CameraAccessException) {
            unavailable(
                "taking frame ${index + 1}",
                "the camera would not take the capture: ${refused.message} (reason ${refused.reason})",
            )
        } catch (error: Throwable) {
            unavailable("taking frame ${index + 1}", "the capture could not be sent: ${error.message}")
        }
        if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            unavailable(
                "taking frame ${index + 1}",
                "the capture did not complete within ${CAPTURE_TIMEOUT_MS}ms"
                    + (lastError?.let { " ($it)" } ?: ""),
            )
        }
        failure[0]?.let { unavailable("taking frame ${index + 1}", it) }
    }

    /** 这台设备报了哪些摄像头 (拿不到就是空的, 由调用方把那句理由说出来) */
    private fun cameraIds(manager: CameraManager): List<String> =
        runCatching { manager.cameraIdList.toList() }.getOrNull().orEmpty()

    /**
     * 这一次调用点名要哪一头
     *
     * 回 null 是"**没有点名**" (缺省看后摄), 与"点名要后摄"是两件事: 只有前摄的设备上, 前者可以退到那台
     * 前摄, 后者必须如实说"没有后摄" —— 混成一件事就会报一个假的成功 (要前摄, 给你后摄, 还说开好了)
     */
    private fun lensOf(request: JsonObject): Int? = when (val asked = request.stringOrNull("lens")?.lowercase()) {
        null -> null
        "back" -> CameraCharacteristics.LENS_FACING_BACK
        "front" -> CameraCharacteristics.LENS_FACING_FRONT
        else -> throw IllegalArgumentException("lens has to be front or back, not \"$asked\"")
    }

    /** 报给人看的名字 */
    private fun lensName(lens: Int): String =
        if (lens == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"

    /**
     * 一个方向的摄像头
     *
     * @param required 调用方点名要这一头: 那时只认它, 找不到就回 null (拿另一头顶上等于报了个假的成功)。
     *   没点名时才退到"设备上有的第一台" —— 只有前摄的设备上, 缺省取景不该变成"没有相机可用"
     */
    private fun pickCamera(manager: CameraManager, ids: List<String>, lens: Int, required: Boolean): String? {
        val matching = ids.firstOrNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == lens
            }.getOrDefault(false)
        }
        if (matching != null) return matching
        return if (required) null else ids.firstOrNull()
    }

    /**
     * `openCamera` 抛出来的原因翻成人话
     *
     * 每个 reason 各是一件事 (被占用 / 到台数上限 / 被策略禁用 / 断开 / 别的错), 这台设备上"退出码 0 而
     * 什么都没发生"已经坑过一次, 所以这里不合并成一句"打不开"
     */
    private fun whyOpenFailed(refused: CameraAccessException): String = when (refused.reason) {
        CameraAccessException.CAMERA_IN_USE -> "another app is already using this camera"
        CameraAccessException.MAX_CAMERAS_IN_USE ->
            "this device has as many cameras open as it allows (another app is holding one)"
        CameraAccessException.CAMERA_DISABLED ->
            "the camera is disabled by device policy, or by this app being in the background"
        CameraAccessException.CAMERA_DISCONNECTED -> "the camera was disconnected while it was being opened"
        else -> "the camera could not be opened: ${refused.message} (reason ${refused.reason})"
    }

    /**
     * 这个进程此刻在系统眼里算什么
     *
     * 安卓从 9 起就不许**后台**应用用相机, 放行的条件是"看得见"或持有一条 `camera` 类型的前台服务 ——
     * 而宿主那条前台服务只报 specialUse, 所以"应用在后台"正是最常见的那个失败原因。`getMyMemoryState`
     * 是公开 API, 问的是自己这一个进程, 所以这不是猜
     */
    private fun backgroundNote(): String {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        val importance = state.importance
        // 100 = 前台 (有看得见的界面): 那种情况下"在后台"当然不是原因。**125 (前台服务) 不算数** ——
        // 那是服务在前台而应用自己可能在后台, 而相机放行看的是那条前台服务的类型是不是 camera,
        // 宿主那条只报 specialUse, 所以 125 恰恰是"在后台"那一类
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            return ". This app is in the foreground, so being in the background is not the reason"
        }
        val where = when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
                "in the background with a foreground service running (that service is specialUse, not camera)"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible but not in the foreground"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "only perceptible to the system"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "in the background with the screen off"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "in the background running a service"
            else -> "in the background (importance $importance)"
        }
        // 行首那个 `+` 在 Kotlin 里会被读成一元加号, 所以操作符留在上一行末尾
        return ". This app is $where, and Android only lets a background app use the camera when it holds" +
            " a foreground service of type camera: bring Luwi to the front and look again"
    }

    /** 离目标像素数最近的那一档 (预览只要看得见, 越小越省) */
    private fun closestTo(sizes: List<Size>, target: Int): Size? = sizes
        .filter { it.width > 0 && it.height > 0 }
        .minByOrNull { abs(it.width * it.height - target) }

    /**
     * JPEG 该转多少度
     *
     * 传感器是横着装的, 竖着拿手机要转 90 度, 不然抓下来的图躺着 —— 这条路与相机应用不一样, 没人替我们
     * 记这件事
     */
    private fun jpegRotation(characteristics: CameraCharacteristics, context: Context): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        // 不能问 context.display: 应用上下文不是"显示上下文", 那条路在有的版本上直接抛。默认屏就够,
        // 这台设备的主屏也是它
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val degrees = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor + degrees) % 360
        } else {
            (sensor - degrees + 360) % 360
        }
    }

    private const val FRAME_QUEUE = 3

    /**
     * 一次取景最多几张
     *
     * **它不是"相机自己的缺省"**: 张数、间隔、清晰度这三条规则归 [VideoLooks] (设置页「视频识别」
     * 那一段), 这里只留一个上限 —— 那份规则的校验 (`VideoLooks.countRange`) 与设置页的滑块都拿它当
     * 上界, 两处各写一个 12 就迟早会漂开。缺省张数也读那一份 ([VideoLooks.count])
     */
    const val MAX_COUNT = 12

    /** 预览面只是给人看一眼, 480p 的量级就够 */
    private const val PREVIEW_TARGET_PIXELS = 640 * 480

    private const val OPEN_TIMEOUT_MS = 5_000L
    private const val CAPTURE_TIMEOUT_MS = 5_000L
    private const val FRAME_TIMEOUT_MS = 4_000L
}
