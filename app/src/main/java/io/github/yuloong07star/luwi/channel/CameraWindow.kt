package io.github.yuloong07star.luwi.channel

import android.content.Context
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 预览那一路: 帧的方向与显示方向差多少度, 以及小窗那一块要不要换高宽
 *
 * **纯函数, 没有设备也能量** (见 PreviewTurnTest): 相机那套式子只有三个输入 —— 传感器装的方向、
 * 屏幕此刻转过多少、是不是前摄 —— 而它错一档画出来的就是"画面躺着"或者"被压扁"(2026-10-10 主人报的
 * 正是后一种: `SENSOR_ORIENTATION` 90 配着竖屏拿, 一个像素都没转, 窗口还按传感器那套横比例开出来)
 *
 * **2026-10-10 两次实测之后定下来的口径**: 这一路**不自己转帧**, 只把窗口那一块换成"显示方向"的比例。
 * 判据是同一时刻那一张静态图 —— 应用按 1280x720 要的, 真机上拿回来的是 **720x1280 的竖图、EXIF=0**
 * (这台设备的 HAL 自己就把帧转成显示方向了, 连我们请求的 `JPEG_ORIENTATION` 都没用上)。所以预览
 * 若再转一次, 画面就横过来 (`ALT` 键的字是躺着的) —— 2026-10-10 在 vivo 与模拟器上都量到了这一条
 *
 * 两个方向是相反的, 这不是笔误: 后摄是 `传感器 - 屏幕`, 前摄是 `传感器 + 屏幕` —— 与 JPEG 那侧算
 * EXIF 用的是同一个式子 (见 `LwCamera.jpegRotation`), 两处必须一致
 */
internal object PreviewTurn {

    /** 缩到 0..359 */
    private fun norm(degrees: Int): Int = ((degrees % 360) + 360) % 360

    fun degrees(sensorOrientation: Int, displayDegrees: Int, mirror: Boolean): Int {
        val sensor = norm(sensorOrientation)
        val display = norm(displayDegrees)
        return norm(if (mirror) sensor + display else sensor - display)
    }

    /**
     * 那一帧与显示方向差 90/270 时, 小窗那一块的高宽要互换
     *
     * 帧本身不转 (HAL 已经转好了), 换的只是**窗口的形状**: 横过来的窗口配竖着的画面, 画出来就是被
     * 压扁的 —— 主人报的"高度受挤压"正是这一条
     */
    fun swapsSides(degrees: Int): Boolean = norm(degrees) % 180 != 0
}

/**
 * 相机预览那块小窗: 一个我们自己的悬浮窗, 相机的预览面就是它
 *
 * 这是与虚拟屏那条路的根本差别 —— 虚拟屏是"借一块别人画的屏", 相机在别人的进程里, 我们只能截屏;
 * 这里预览面**由我们直接拿在手里**, 同一个 CameraDevice 上再挂一个 ImageReader 就能抓帧 (见
 * [io.github.yuloong07star.luwi.tool.LwCamera]), 不用截屏、不用相机应用、也不占一块虚拟屏
 *
 * 为什么是悬浮窗 (`TYPE_APPLICATION_OVERLAY`) 而不是应用内的一个小窗: 人在别的应用里的时候也要看得见
 * 相机对着什么, 而项目里已经有这条权限与这套写法 (见 overlay 里那个浮窗输入框)。**建窗口必须在主线程**,
 * 而通道那头是工作线程, 所以这里 post 到主线程并等它做完
 *
 * 为什么是 `TextureView` 而不是 `SurfaceView`: 第一版用 SurfaceView, 画面是黑的 —— SurfaceView 的
 * surface 合成在窗口**底下**, 而窗口这一层有自己的背景 (那块半透明黑), 于是把预览整个盖住了 (要么
 * 让窗口不画背景, 要么把 surface 提到窗口之上, 前者会让叉子与边框一起没掉, 后者会把叉子压在画面下)。
 * TextureView 就在正常的视图树里画, 没有这一层 z 序问题, 也能直接跟着窗口的圆角与缩放走
 *
 * 可拖动: 小窗挡住什么都不奇怪, 所以按住它自己就能挪 (与虚拟屏预览那个小窗一个手感), 右上角一个小
 * 叉子收掉它 (收就是 close, 与通道那条 op 走同一条路)
 */
internal object CameraWindow {

    private const val TAG = "LwCameraWindow"

    private val main = Handler(Looper.getMainLooper())

    private var manager: WindowManager? = null
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var preview: TextureView? = null

    /** 屏幕转不转那个观察者 (只在窗开着时挂着, 见 [watchDisplay] / [removeNow]) */
    private var displayManager: DisplayManager? = null
    private var displayWatch: DisplayManager.DisplayListener? = null

    /** 预览面现在是什么: 相机那侧据此决定要不要重建 session */
    @Volatile
    private var surface: Surface? = null

    /** 面上有东西了 / 它没了, 通知相机那侧 */
    @Volatile
    private var onSurfaceChanged: (() -> Unit)? = null

    /**
     * 这一块预览的"该怎么摆": 缓冲区尺寸, 以及相机装的方向与它是哪一头
     *
     * **2026-10-10 加的** (主人: "视频模式下镜头比例有误, 高度受挤压"): 原来这里只有缓冲区尺寸,
     * 窗口照着它摆, 而**一个像素都不转** —— 于是竖着拿手机时那一帧是横的, 而窗口按传感器那个横比例
     * 开出来, 画面就被压成扁的。帧要先转成"显示方向" (见 [previewDegrees]), 转完之后高宽互换,
     * 窗口与那块面都要照换过来的比例摆
     */
    private var bufferWidth = 0
    private var bufferHeight = 0

    /** 传感器装的方向 (相机静态特征里的 `SENSOR_ORIENTATION`, 90 / 270 那一档) */
    private var sensorOrientation = 90

    /** 前摄: 转的圈数换成 270 之外还要左右翻一下 (镜子那一条, 与相机应用一致) */
    private var mirror = false

    /** 小窗现在开着没有 */
    fun isOpen(): Boolean = root != null

    /** 有效的那块面; 没有就是 null (相机那时只挂 ImageReader, 抓帧照旧) */
    fun surfaceNow(): Surface? = surface?.takeIf { it.isValid }

    /**
     * 把小窗摆出来
     *
     * @param widthPx / heightPx 预览缓冲区的尺寸, 必须是相机支持的那一档 (由 `LwCamera` 挑好) ——
     *   **是传感器那一套坐标**, 不是屏幕上的宽高 (转不转由 [sensorOrientation] / [mirror] 算)
     * @param sensorOrientation 相机静态特征里的 `SENSOR_ORIENTATION`
     * @param mirror 前摄: 预览要左右翻一下
     * @return 一句人话 (成功或为什么没成)
     */
    fun show(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        sensorOrientation: Int = 90,
        mirror: Boolean = false,
        onSurface: () -> Unit,
        onClose: () -> Unit,
    ): String {
        if (isOpen()) return "the camera window is already up"
        val done = CountDownLatch(1)
        var failure: String? = null
        onSurfaceChanged = onSurface
        main.post {
            try {
                build(context.applicationContext, widthPx, heightPx, sensorOrientation, mirror, onClose)
            } catch (error: Throwable) {
                failure = error.message ?: error.toString()
            } finally {
                done.countDown()
            }
        }
        if (!done.await(WINDOW_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return "the window did not come up within ${WINDOW_TIMEOUT_MS}ms (the main thread is busy)"
        }
        return failure?.let { "the camera window did not come up: $it" }
            ?: "camera window up (${widthPx}x$heightPx buffer, laid out along the display direction)," +
            " drag it anywhere, the × closes it"
    }

    /** 收掉小窗; 回"有没有真的收掉一个" */
    fun hide(): Boolean {
        if (root == null) return false
        // 主线程上直接摘: 那个叉子就是主线程点的, 再 post 回主线程等自己就是死锁
        if (Looper.myLooper() == Looper.getMainLooper()) return removeNow()
        val done = CountDownLatch(1)
        var removed = false
        main.post {
            removed = removeNow()
            done.countDown()
        }
        done.await(WINDOW_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return removed
    }

    /** 真正摘窗口的那两步 (只在主线程上跑) */
    private fun removeNow(): Boolean {
        if (root == null) return false
        var removed = false
        try {
            root?.let { view -> runCatching { manager?.removeView(view) } }
            removed = true
        } catch (error: Throwable) {
            Log.w(TAG, "removing the window failed: ${error.message}")
        }
        // 屏幕转不转那个观察者也跟着摘 (它挂着这块窗的引用)
        runCatching { displayWatch?.let { displayManager?.unregisterDisplayListener(it) } }
        displayWatch = null
        displayManager = null
        root = null
        preview = null
        params = null
        releaseSurface()
        return removed
    }

    private fun releaseSurface() {
        val gone = surface
        surface = null
        gone?.let { runCatching { it.release() } }
    }

    private fun build(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        sensor: Int,
        flip: Boolean,
        onClose: () -> Unit,
    ) {
        val window = context.getSystemService(WindowManager::class.java)
            ?: throw IllegalStateException("this device has no window manager")
        val density = context.resources.displayMetrics.density
        bufferWidth = widthPx
        bufferHeight = heightPx
        sensorOrientation = sensor
        mirror = flip
        // **窗口照"显示方向"那一档摆** (2026-10-10): 帧是相机自己转好交过来的, 而它落在的那块缓冲区
        // 还是传感器那套横比例 —— 小窗要是不跟着换成竖的, 竖着拿手机时画面就是被压扁的 (主人报的那一条)
        val turned = PreviewTurn.swapsSides(previewDegrees(context))
        val shownWidth = if (turned) heightPx else widthPx
        val shownHeight = if (turned) widthPx else heightPx
        // 窗口按 dp 摆: 480p 的缓冲区在这块 560dpi 的屏上是 ~137dp 宽的一块, 屏幕上占比刚好
        val width = (shownWidth / density * VIEW_SCALE).roundToInt().coerceAtLeast(96)
        val height = (shownHeight / density * VIEW_SCALE).roundToInt().coerceAtLeast(128)
        val layout = FrameLayout(context).apply {
            setBackgroundColor(0xCC000000.toInt())
        }
        val view = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                    texture.setDefaultBufferSize(widthPx, heightPx)
                    applyTransform()
                    surface = Surface(texture)
                    onSurfaceChanged?.invoke()
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) {
                    texture.setDefaultBufferSize(widthPx, heightPx)
                    applyTransform()
                    onSurfaceChanged?.invoke()
                }

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    releaseSurface()
                    onSurfaceChanged?.invoke()
                    return true
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        }
        val close = TextView(context).apply {
            text = "×"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(20, 4, 20, 8)
            setOnClickListener { onClose() }
        }
        layout.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        layout.addView(
            close,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ),
        )
        val layoutParams = WindowManager.LayoutParams(
            (width * density).roundToInt(),
            (height * density).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不吃焦点 (输入法不该被它抢走), 也不想被系统按屏幕边界裁掉
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (context.resources.displayMetrics.widthPixels - width * density).roundToInt() - 24
            y = context.resources.displayMetrics.heightPixels / 6
        }
        // 拖动: 与小窗预览同一个手感 —— 按住哪儿都能挪, 只有那个叉子是点击
        layout.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(touched: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = layoutParams.x
                        startY = layoutParams.y
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = startX + (event.rawX - downX).roundToInt()
                        layoutParams.y = startY + (event.rawY - downY).roundToInt()
                        runCatching { window.updateViewLayout(layout, layoutParams) }
                        return true
                    }
                }
                return false
            }
        })
        window.addView(layout, layoutParams)
        manager = window
        watchDisplay(context)
        root = layout
        params = layoutParams
        preview = view
        Log.i(TAG, "camera window up ${layoutParams.width}x${layoutParams.height} at ${layoutParams.x},${layoutParams.y}")
    }

    /**
     * 预览要转多少度才是"屏幕上的正方向"
     *
     * 后摄按 `传感器方向 - 屏幕转过的角度`, 前摄按 `传感器方向 + 屏幕转过的角度` (两个方向相反是相机那
     * 一套约定, 与 JPEG 的 EXIF 同一个式子) —— 与 `LwCamera` 算 EXIF 用的是同一套, 但这里要的是**画**
     * 那一侧, 所以它必须跟着屏幕转: 屏幕一转就要重算, 见 [watchDisplay]
     */
    private fun previewDegrees(context: Context): Int {
        return PreviewTurn.degrees(sensorOrientation, displayDegrees(context), mirror)
    }

    /** 屏幕现在转了多少度 (0 / 90 / 180 / 270) */
    private fun displayDegrees(context: Context): Int = when (
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
    ) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /**
     * 前摄把画面左右翻一下 (镜子那一条, 与相机应用一致)
     *
     * **这里故意不转帧**: 这一路的帧是 HAL 转好交过来的 (见 [PreviewTurn] 那一段说明), 小窗要做的
     * 只有"按显示方向的比例摆"与"前摄翻一面"两件事
     */
    private fun applyTransform() {
        val view = preview ?: return
        val viewWidth = view.width.toFloat()
        val viewHeight = view.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val matrix = Matrix()
        if (mirror) matrix.postScale(-1f, 1f, viewWidth / 2f, viewHeight / 2f)
        view.setTransform(matrix)
    }

    /**
     * 盯着屏幕转不转: 转了就把窗口与那一帧一起重摆
     *
     * 不盯的话"竖着打开、然后横过来"会停在打开那一刻的方向上 —— 而这块小窗是常驻的, 人拿手机本来
     * 就会转 (2026-10-10 与比例那一条同批)
     */
    private fun watchDisplay(context: Context) {
        val displays = context.getSystemService(DisplayManager::class.java) ?: return
        displayManager = displays
        displayWatch = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                relayoutFor(context)
            }
        }
        displays.registerDisplayListener(displayWatch, main)
    }

    /** 屏幕转完之后重算窗口尺寸与那一帧的摆放 (只在主线程上跑) */
    private fun relayoutFor(context: Context) {
        val layout = root ?: return
        val window = manager ?: return
        val layoutParams = params ?: return
        val density = context.resources.displayMetrics.density
        val turned = PreviewTurn.swapsSides(previewDegrees(context))
        val shownWidth = if (turned) bufferHeight else bufferWidth
        val shownHeight = if (turned) bufferWidth else bufferHeight
        if (shownWidth <= 0 || shownHeight <= 0) return
        layoutParams.width = (shownWidth / density * VIEW_SCALE).roundToInt().coerceAtLeast(96)
        layoutParams.height = (shownHeight / density * VIEW_SCALE).roundToInt().coerceAtLeast(128)
        val metrics = context.resources.displayMetrics
        layoutParams.x = layoutParams.x.coerceIn(0, (metrics.widthPixels - layoutParams.width).coerceAtLeast(0))
        layoutParams.y = layoutParams.y.coerceIn(0, (metrics.heightPixels - layoutParams.height).coerceAtLeast(0))
        runCatching { window.updateViewLayout(layout, layoutParams) }
        applyTransform()
    }

    /** 相对预览缓冲区放大到看得清又不占地方的那一档 */
    private const val VIEW_SCALE = 1.4f

    private const val WINDOW_TIMEOUT_MS = 3_000L
}
