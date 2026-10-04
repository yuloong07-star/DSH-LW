package io.github.miuzarte.littlewhale.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.miuzarte.littlewhale.MainActivity
import io.github.miuzarte.littlewhale.R

/** 浮窗现在什么样: 通道方法 `overlay` 的 state 就读这里 */
internal object OverlayState {
    @Volatile
    var showing: Boolean = false

    @Volatile
    var url: String? = null

    /** 上一次失败的原因, 供 state 与日志共用 */
    @Volatile
    var lastError: String? = null
}

/**
 * 把 dsh 的界面浮在别的应用上面, 而且能打字
 *
 * 这是"面板 A"的落点 (见 docs/floating-input.md): 窗里就是一个 WebView, 指着 host 报出来的那个
 * 带 token 的地址 —— 也就是同一个 GUI 的第二个客户端, 所以会话、cookie、localStorage 与主界面
 * 共享, 页面里那段输入框和麦克风按钮照常可用
 *
 * 三条与别的窗不同的规矩:
 * 1. **不加 `FLAG_NOT_FOCUSABLE`**。要收系统输入法就必须可获焦, 代价是窗外不再穿透 —— 手指落在
 *    窗外会被窗口吃掉。窗做得小一点是这一条的补偿
 * 2. **前台服务**。浮窗挂在服务上, 应用退到后台窗口才不会被系统收走; 通知栏留一条常驻通知,
 *    点它回到应用, 通知上的按钮直接关掉浮窗
 * 3. **麦克风授权与宿主 WebView 同一条规矩** (见 ui/HostScreen.kt): 只放 `RESOURCE_AUDIO_CAPTURE`,
 *    只给 `127.0.0.1` 那个来源, 别的一律拒
 */
class OverlayService : Service() {

    private var window: WindowManager? = null
    private var root: View? = null
    private var web: WebView? = null
    private var collapseLabel: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsed = false
    private var expandedHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        fend()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopSelf()
            return START_NOT_STICKY
        }
        show(intent)
        return START_STICKY
    }

    override fun onDestroy() {
        OverlayState.showing = false
        val view = root
        root = null
        if (view != null) runCatching { window?.removeView(view) }
        web?.let { runCatching { it.destroy() } }
        web = null
        collapseLabel = null
        window = null
        params = null
        super.onDestroy()
    }

    /** 建窗或者把它提到前面: 已经建过就只更新链接与状态 */
    private fun show(intent: Intent?) {
        val url = intent?.getStringExtra(EXTRA_URL) ?: OverlayState.url
        if (url.isNullOrEmpty()) {
            fail("no GUI URL was handed over, so there is nothing to float")
            return
        }
        val manager = getSystemService(WindowManager::class.java)
        if (manager == null) {
            fail("this device has no window manager")
            return
        }
        if (root == null) {
            val metrics = resources.displayMetrics
            val margin = dp(MARGIN_DP)
            val width = intent?.getIntExtra(EXTRA_WIDTH, 0).orZero()
                .takeIf { it > 0 } ?: (metrics.widthPixels - margin * 2)
            val height = intent?.getIntExtra(EXTRA_HEIGHT, 0).orZero()
                .takeIf { it > 0 } ?: (metrics.heightPixels * DEFAULT_HEIGHT_PERCENT / 100)
            expandedHeight = height
            val layout = WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 只有 LAYOUT_IN_SCREEN: 不加 NOT_FOCUSABLE, 输入法才会给这个窗打字
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = intent?.getIntExtra(EXTRA_X, 0).orZero().takeIf { it > 0 } ?: margin
                y = intent?.getIntExtra(EXTRA_Y, 0).orZero().takeIf { it > 0 }
                    ?: (metrics.heightPixels - height - dp(140)).coerceAtLeast(margin)
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            val view = buildView(url)
            try {
                manager.addView(view, layout)
            } catch (error: Throwable) {
                fail("the system refused the floating window: ${error.message}")
                return
            }
            root = view
            params = layout
            window = manager
        } else if (url != OverlayState.url) {
            // 同一条浮窗活过 host 的一次重启: 地址变了就重新载一次, 不然页面挂在旧端口上
            web?.loadUrl(url)
        }
        OverlayState.lastError = null
        OverlayState.url = url
        OverlayState.showing = true
    }

    /** 拖动的那一条: 标题栏按住就能挪, 位置是相对屏幕左上角 */
    private fun dragHandle(bar: View) {
        bar.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var fromX = 0
            private var fromY = 0

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val layout = params ?: return false
                // 窗不一定还在: 关窗与拖动可以撞在一起, 所以这里也判一次空
                val target = root ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        fromX = layout.x
                        fromY = layout.y
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        layout.x = fromX + (event.rawX - downX).toInt()
                        layout.y = fromY + (event.rawY - downY).toInt()
                        runCatching { window?.updateViewLayout(target, layout) }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
    }

    /** 收起/展开: 收起只剩标题栏, 展开回到原来那个高度 */
    private fun toggleCollapsed() {
        val layout = params ?: return
        val view = root ?: return
        collapsed = !collapsed
        if (collapsed) {
            web?.visibility = View.GONE
            layout.height = dp(BAR_HEIGHT_DP)
            collapseLabel?.text = "展开"
        } else {
            web?.visibility = View.VISIBLE
            layout.height = expandedHeight
            collapseLabel?.text = "收起"
        }
        runCatching { window?.updateViewLayout(view, layout) }
    }

    /**
     * 回应用去
     *
     * 从后台启动 Activity 在 Android 10 起是默认禁止的, 而持有 SYSTEM_ALERT_WINDOW 正是官方
     * 豁免之一 —— 这台手机的这条权限已经给了, 所以这里可以直接拉
     */
    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }
    }

    private fun buildView(url: String): View {
        // WebView 要一个带主题的 context, 服务自己没有主题, 所以套一层
        val themed = ContextThemeWrapper(this, R.style.Theme_LittleWhale)
        val column = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }
        val bar = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(4), dp(4))
            minimumHeight = dp(BAR_HEIGHT_DP)
        }
        val title = TextView(themed).apply {
            text = "素云"
            setTextColor(TEXT)
            textSize = 14f
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val collapse = button(themed, "收起") { toggleCollapsed() }
        val toApp = button(themed, "应用") { openApp() }
        val close = button(themed, "×") { stopSelf() }
        bar.addView(collapse)
        bar.addView(toApp)
        bar.addView(close)
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        collapseLabel = collapse
        dragHandle(bar)

        val page = WebView(themed).apply {
            settings.javaScriptEnabled = true
            // 主题与字号存在 localStorage 里, 与主界面共用同一份
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            // 语音输出那一半靠页面播放音频, 而 WebView 默认要有手势才放音
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val audio = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    if (request.origin.host != "127.0.0.1" || audio.isEmpty()) {
                        Log.w(TAG, "denied ${request.resources.joinToString()} for ${request.origin}")
                        request.deny()
                        return
                    }
                    Log.i(TAG, "granting ${audio.joinToString()} to ${request.origin}")
                    request.grant(audio.toTypedArray())
                }
            }
            loadUrl(url)
        }
        column.addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        web = page
        return column
    }

    private fun button(parent: Context, label: String, onClick: () -> Unit): TextView =
        TextView(parent).apply {
            text = label
            setTextColor(TEXT)
            textSize = 13f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }

    private fun fail(reason: String) {
        OverlayState.lastError = reason
        OverlayState.showing = false
        Log.w(TAG, reason)
        stopSelf()
    }

    /**
     * 前台服务: 类型里带上 microphone, 浮窗里的 WebView 才好在后台录到音
     *
     * 后台起步时带 while-in-use 类型的那一半可能被系统拒 (持有 SYSTEM_ALERT_WINDOW 是豁免之一,
     * 但不是所有 ROM 都认), 那就退到 specialUse, 并把代价写清楚: 前台时麦克风照常, 后台可能拿不到
     */
    private fun fend() {
        val both = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), both)
        } catch (error: Throwable) {
            Log.w(TAG, "the microphone foreground type was refused, falling back to specialUse", error)
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val close = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("输入框浮在别的应用上面")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(0, "关掉浮窗", close)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "浮窗输入", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        const val ACTION_SHOW = "io.github.miuzarte.littlewhale.overlay.SHOW"
        const val ACTION_HIDE = "io.github.miuzarte.littlewhale.overlay.HIDE"
        const val EXTRA_URL = "url"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"

        private const val TAG = "LwOverlay"
        private const val CHANNEL_ID = "lw-overlay"

        /** 与 LwNotify 那条通知分开: 那条是给模型的, 这条是浮窗自己的常驻 */
        private const val NOTIFICATION_ID = 3
        private const val MARGIN_DP = 12
        private const val BAR_HEIGHT_DP = 44
        private const val DEFAULT_HEIGHT_PERCENT = 45
        private val BACKGROUND = 0xF21B1B1F.toInt()
        private val TEXT = 0xFFEDEDED.toInt()
    }
}
