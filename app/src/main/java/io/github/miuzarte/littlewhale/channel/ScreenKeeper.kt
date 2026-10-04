package io.github.miuzarte.littlewhale.channel

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * 一块屏在没有人看预览时也要有的那个输出面
 *
 * 屏是给模型用的, 人看不看它都在跑, 而"人没在看"在系统侧不该有任何后果。让它是有的后果就是没有
 * 输出面: 虚拟屏的合成要写进一个面, 面没了合成器就不为它合成 —— `screencap -d` 会说这个 display
 * id 不合法 (`lw_screenshot` 就是跑它), 无障碍的 `windowsOnAllDisplays` 里也查不到它屏上的窗口
 * (`lw_ui` / `lw_tap` / `lw_type` 读的都是那份列表)。于是这块屏对模型就"看不见也点不动"了: 屏还
 * 在, 菜单里、`lw_screen` 里都还在, 只是谁也没有它的画面
 *
 * 所以收起预览时不能把面交成 null, 而要交成这个: 一个 w×h 的 [ImageReader], 它的尺寸**必须**是屏
 * 自己的尺寸 —— 合成器不缩放, 给小了就是左上角的裁剪 (与预览那块 `SurfaceView` 的 `setFixedSize`
 * 同一个道理)
 *
 * 帧没人要, 但也不能不取: 队列一旦满, 屏就卡在最后一帧上。取帧挂在 reader 自己的回调线程上,
 * 拿到就关掉, 空闲时线程睡着, 不空转
 *
 * 它不是预览的替代品, 只是"没人看"时的占位: 有预览时面是预览的, 收起、或者窗口被系统拆掉时, 才
 * 由它接管
 *
 * @param width 屏自己的宽, 也是这块 buffer 的宽
 * @param height 屏自己的高, 也是这块 buffer 的高
 */
internal class ScreenKeeper(val width: Int, val height: Int) {

    private val reader: ImageReader
    private val thread: HandlerThread

    /** 交给屏的那个面, 直到预览把它顶掉 */
    val surface: Surface

    init {
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        surface = reader.surface
        thread = HandlerThread("lw-keeper-${width}x$height").apply { start() }
        reader.setOnImageAvailableListener({ source ->
            // 取出来就地丢掉。回调里抛出去会带走这个线程, 所以这里什么都吞: 少丢一帧不值得崩
            try {
                source.acquireLatestImage()?.close()
            } catch (problem: Throwable) {
                Log.w(TAG, "the keeper for ${width}x$height could not drop a frame", problem)
            }
        }, Handler(thread.looper))
    }

    /** 这个面还是不是这块屏现在该有的那一块: 屏改过尺寸就得换新的 */
    fun matches(screen: ScreenState): Boolean = width == screen.width && height == screen.height

    /** 连 reader 带它的线程一起交还, 屏没了之后没有别的用处 */
    fun close() {
        try {
            reader.setOnImageAvailableListener(null, null)
        } catch (problem: Throwable) {
            Log.w(TAG, "the keeper for ${width}x$height had no listener to clear", problem)
        }
        try {
            reader.close()
        } catch (problem: Throwable) {
            Log.w(TAG, "the keeper for ${width}x$height did not close cleanly", problem)
        }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "LwScreen"

        /**
         * 两块就够: 一块正被合成器写着, 一块等着被丢, 再多只是白占一块整屏大小的内存
         *
         * 一屏一帧是 w×h×4 字节 (1260×2800 约 14 MB), 所以这是这块屏在没有预览时的一点固定开销
         */
        const val MAX_IMAGES = 2
    }
}
