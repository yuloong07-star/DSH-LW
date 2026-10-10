package io.github.yuloong07star.luwi.channel

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    /** 预览面现在是什么: 相机那侧据此决定要不要重建 session */
    @Volatile
    private var surface: Surface? = null

    /** 面上有东西了 / 它没了, 通知相机那侧 */
    @Volatile
    private var onSurfaceChanged: (() -> Unit)? = null

    /** 小窗现在开着没有 */
    fun isOpen(): Boolean = root != null

    /** 有效的那块面; 没有就是 null (相机那时只挂 ImageReader, 抓帧照旧) */
    fun surfaceNow(): Surface? = surface?.takeIf { it.isValid }

    /**
     * 把小窗摆出来
     *
     * @param widthPx / heightPx 预览缓冲区的尺寸, 必须是相机支持的那一档 (由 `LwCamera` 挑好)
     * @return 一句人话 (成功或为什么没成)
     */
    fun show(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        onSurface: () -> Unit,
        onClose: () -> Unit,
    ): String {
        if (isOpen()) return "the camera window is already up"
        val done = CountDownLatch(1)
        var failure: String? = null
        onSurfaceChanged = onSurface
        main.post {
            try {
                build(context.applicationContext, widthPx, heightPx, onClose)
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
            ?: "camera window up (${widthPx}x$heightPx buffer), drag it anywhere, the × closes it"
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

    private fun build(context: Context, widthPx: Int, heightPx: Int, onClose: () -> Unit) {
        val window = context.getSystemService(WindowManager::class.java)
            ?: throw IllegalStateException("this device has no window manager")
        val density = context.resources.displayMetrics.density
        // 窗口按 dp 摆: 480p 的缓冲区在这块 560dpi 的屏上是 ~137dp 宽的一块, 屏幕上占比刚好
        val width = (widthPx / density * VIEW_SCALE).roundToInt().coerceAtLeast(96)
        val height = (heightPx / density * VIEW_SCALE).roundToInt().coerceAtLeast(128)
        val layout = FrameLayout(context).apply {
            setBackgroundColor(0xCC000000.toInt())
        }
        val view = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                    texture.setDefaultBufferSize(widthPx, heightPx)
                    surface = Surface(texture)
                    onSurfaceChanged?.invoke()
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) {
                    texture.setDefaultBufferSize(widthPx, heightPx)
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
        root = layout
        params = layoutParams
        preview = view
        Log.i(TAG, "camera window up ${layoutParams.width}x${layoutParams.height} at ${layoutParams.x},${layoutParams.y}")
    }

    /** 相对预览缓冲区放大到看得清又不占地方的那一档 */
    private const val VIEW_SCALE = 1.4f

    private const val WINDOW_TIMEOUT_MS = 3_000L
}
