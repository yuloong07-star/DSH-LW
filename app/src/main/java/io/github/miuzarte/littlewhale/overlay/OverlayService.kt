package io.github.miuzarte.littlewhale.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.animation.doOnEnd
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.miuzarte.littlewhale.MainActivity
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.channel.LwModes
import io.github.miuzarte.littlewhale.host.DshHost
import io.github.miuzarte.littlewhale.host.HostStatus
import io.github.miuzarte.littlewhale.tool.LwWakeWord
import io.github.miuzarte.littlewhale.voice.VoiceCommands
import io.github.miuzarte.littlewhale.voice.VoiceInbox
import io.github.miuzarte.littlewhale.voice.VoiceState
import io.github.miuzarte.littlewhale.wake.WakeWordState

/**
 * 浮标现在什么样: 通道方法 `overlay` 的 state 就读这里
 *
 * `phase` 是**宿主推来的**那一个 (见 host-plugin 的 `turn/start` / `turn/end`): "在想"只有宿主知道,
 * 应用这一侧没有第二条路能看出"一轮正在跑"
 */
internal object OverlayState {

    const val PHASE_IDLE = "idle"
    const val PHASE_THINKING = "thinking"

    /** 球在不在 */
    @Volatile
    var showing: Boolean = false

    /** 输入条展开没有 */
    @Volatile
    var expanded: Boolean = false

    @Volatile
    var url: String? = null

    /** 上一次失败的原因, 供 state 与日志共用 */
    @Volatile
    var lastError: String? = null

    /**
     * 球刚说过的那一句话 (提示音那种, 不是失败)
     *
     * 与 [lastError] 分开是**故意的**: 成功的话 ("已经让它切到视频模式") 与失败的话 ("写不进收件箱")
     * 对读状态的人是两件事, 混在一个字段里就会把"它刚做成了什么"报成 `last problem`
     */
    @Volatile
    var lastHint: String? = null

    /** 球现在停在哪儿 (窗口坐标) */
    @Volatile
    var x: Int = 0

    @Volatile
    var y: Int = 0

    /** 宿主说的一轮在跑没有 */
    @Volatile
    var phase: String = PHASE_IDLE

    /** 球上现在画的那三个字, 空 = 空闲 */
    @Volatile
    var word: String? = null
}

/**
 * 浮标: 一颗不吃焦的小球, 点一下说话, 长按出菜单, 拖到哪儿吸附到哪边
 *
 * 它与 `docs/floating-input.md` 里那块「面板 A」是同一个服务的两块窗:
 *
 * - **球** ([BallView]) 常驻, `FLAG_NOT_FOCUSABLE`, 所以手指落在球外面照旧给底下的应用 (改动之前
 *   那块窗收系统输入法, 代价正是窗外全被它吃掉)
 * - **输入条** (那块 WebView) 只在展开时挂上, 可获焦、收输入法, 收起时把它从窗口上摘下来 ——
 *   页面本身留着 (会话、cookie 与主界面共享, 摘下来再挂回去不必重载)
 *
 * 三件事与别的窗不同, 都要记住:
 * 1. **两块窗是分开加的**, 不是运行期改 `FLAG_NOT_FOCUSABLE`: 那个标志在各 ROM 上改起来行为不一
 *    (有的要摘掉再加一遍), 而加窗/摘窗没有这个不确定面
 * 2. **前台服务**: 球挂在服务上, 应用退到后台球才不会被系统收走; 通知栏那一条的文字跟着球的状态走,
 *    上面的按钮直接关掉浮标
 * 3. **麦克风授权与宿主 WebView 同一条规矩** (见 ui/HostScreen.kt): 只放 `RESOURCE_AUDIO_CAPTURE`,
 *    只给 `127.0.0.1` 那个来源, 别的一律拒
 */
class OverlayService : Service() {

    private var window: WindowManager? = null

    private var ballView: BallView? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var stripView: View? = null
    private var stripParams: WindowManager.LayoutParams? = null
    private var web: WebView? = null
    private var stripTitle: TextView? = null

    private var menuView: View? = null

    private val handler = Handler(Looper.getMainLooper())

    /** 球的状态词、标题与通知都靠它刷: 400 ms 一次, 与唤醒词服务那个观察者同一个节奏 */
    private val tick = object : Runnable {
        override fun run() {
            runCatching { refresh() }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private var snap: ValueAnimator? = null

    /** 球真正停靠的那个 x (半藏是它上面加的一个偏移) 与那个 y */
    private var ballRestX = 0
    private var ballBaseY = 0

    /** 现在半藏着没有 (只留一条边在外面) */
    private var peeked = false

    /** 最后一次碰它的时刻: 闲置超过 [PEEK_IDLE_MS] 就半藏, 参考那套叫 `floating_ball_idle_to_edge` */
    private var lastTouchAt = 0L

    private var ballDragging = false
    private var dragFromX = 0
    private var dragFromY = 0

    /** 输入条归位用的那个 y (拖动改它) */
    private var stripBaseY = 0

    /** 键盘占了屏幕下方多少 (输入条的 inset 给的): 球靠它避开键盘, 输入条靠它整体上抬 */
    private var imeBottom = 0

    private var lastWidth = 0
    private var lastHeight = 0
    private var lastWord: BallWord? = null
    private var lastNotice: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        fend()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                BallSpot.setOn(this, false)
                stopSelf()
                return START_NOT_STICKY
            }

            // 收起输入条, 球留着
            ACTION_COLLAPSE -> {
                collapse()
                return START_STICKY
            }

            ACTION_EXPAND -> {
                showBall(intent)
                expand(intent)
                return START_STICKY
            }

            // 球上那一下「说话」: 直接调常驻语音链, **不碰页面自己的麦克风**
            ACTION_LISTEN -> {
                listenNow()
                return START_STICKY
            }
        }
        showBall(intent)
        if (intent?.getBooleanExtra(EXTRA_EXPAND, false) == true) expand(intent)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        snap?.cancel()
        snap = null
        closeMenu()
        web?.let { runCatching { it.destroy() } }
        web = null
        ballView = null
        ballParams = null
        stripView = null
        stripParams = null
        stripTitle = null
        window = null
        OverlayState.showing = false
        OverlayState.expanded = false
        OverlayState.word = null
        super.onDestroy()
    }

    /**
     * 转屏 / 换分辨率: 按存盘那条边把球摆回该在的地方
     *
     * 存的是「边 + y」而不是 x, 就是为这一刻 —— 横过来之后右边那个 x 是另一个数
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { replace() }
        runCatching { refresh(force = true) }
    }

    /* ── 球 ───────────────────────────────────────────────────────────────── */

    private fun showBall(intent: Intent?) {
        intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotEmpty() }?.let { OverlayState.url = it }
        val manager = getSystemService(WindowManager::class.java)
        if (manager == null) {
            fail(getString(R.string.overlay_no_window_manager))
            return
        }
        window = manager
        if (ballView == null) {
            val metrics = resources.displayMetrics
            val ball = dp(BallView.BALL_SIZE_DP)
            val spot = BallSpot.read(this)
            val edge = spot?.first ?: BallGeometry.EDGE_RIGHT
            val restY = BallGeometry.clampY(spot?.second ?: (metrics.heightPixels * 2 / 3), metrics.heightPixels, ball)
            val restX = BallGeometry.xForEdge(edge, metrics.widthPixels, ball)
            val view = BallView(this, ballListener)
            val layout = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 球不许获焦: 有它挡着也不该让窗外的触摸变少 —— 除此之外 NOT_TOUCH_MODAL 是把
                // "窗外照旧"这件事写出来, 而不是靠"非获焦"顺带成立
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = restX
                this.y = restY
            }
            try {
                manager.addView(view, layout)
            } catch (error: Throwable) {
                fail(getString(R.string.overlay_refused, error.message ?: error.toString()))
                return
            }
            ballView = view
            ballParams = layout
            ballRestX = restX
            ballBaseY = restY
            // 刚出来不半藏: 先让人看见它落在哪儿, 闲置之后才自己收边
            lastTouchAt = System.currentTimeMillis()
            lastWidth = metrics.widthPixels
            lastHeight = metrics.heightPixels
            if (!BallSpot.hintShown(this)) {
                BallSpot.markHint(this)
                hint(getString(R.string.ball_hint))
            }
        }
        OverlayState.lastError = null
        OverlayState.showing = true
        handler.removeCallbacks(tick)
        handler.post(tick)
        refresh(force = true)
    }

    private val ballListener = object : BallView.Listener {
        override fun onPressStart() {
            // 半藏着的球在手指落下的这一瞬间就要滑出来: 晚一步就成了"按住了却拖不动"。
            // 那一条滑动动画也要掐掉 —— 手指一动它就该跟手, 而不是继续走自己的
            snap?.cancel()
            lastTouchAt = System.currentTimeMillis()
            unpeek()
            closeMenu()
        }

        override fun onTap() {
            listenNow()
        }

        override fun onLongPress() {
            toggleMenu()
        }

        override fun onDragTo(dx: Float, dy: Float) {
            val metrics = resources.displayMetrics
            val ball = dp(BallView.BALL_SIZE_DP)
            // 位移是相对"按下那一点"的, 所以基准只在第一帧取一次 —— 每帧用当前 x 当基准的话,
            // 拖动会随帧率加速 (位置既是结果又是基准)
            if (!ballDragging) {
                ballDragging = true
                dragFromX = ballRestX
                dragFromY = ballBaseY
            }
            ballRestX = BallGeometry.dragX((dragFromX + dx).toInt(), metrics.widthPixels, ball)
            ballBaseY = (dragFromY + dy).toInt().coerceIn(0, (metrics.heightPixels - ball).coerceAtLeast(0))
            applyBallPosition()
        }

        override fun onDrop() {
            ballDragging = false
            lastTouchAt = System.currentTimeMillis()
            snapToEdge()
        }
    }

    /**
     * 松手吸附最近那一边, 落定之后写存盘, 然后收边
     *
     * 停靠即收边 (参考那套的 `adsorb(halfHide)` 就是这个意思) —— 它在别的应用上一直是一块挡视线的
     * 东西, 而停靠这个动作本身已经说明"我放这儿了"; 有事 (正在听 / 正在想 / 正在念) 或者人碰它时
     * 立刻滑回来, 见 [unpeek]
     */
    private fun snapToEdge() {
        val metrics = resources.displayMetrics
        val ball = dp(BallView.BALL_SIZE_DP)
        val target = BallGeometry.snapX(ballRestX, metrics.widthPixels, ball)
        val from = ballParams?.x ?: ballRestX
        ballRestX = target
        peeked = false
        moveX(from, target) {
            val edge = BallGeometry.edgeFor(target, metrics.widthPixels, ball)
            BallSpot.write(this@OverlayService, edge, target, ballBaseY)
            applyBallPosition()
            if (lastWord == null) peek()
        }
    }

    /**
     * 侧边半藏 (参考那套的 `floating_ball_idle_to_edge`): 闲置一会儿就把球挪出去一多半
     *
     * 只留一条边在外面 —— 它在别的应用上一直是块挡视线的东西, 而闲着的时候它没有任何话要说;
     * 有事 (正在听 / 正在想 / 正在念) 或者人碰它时立刻滑回来, 见 [unpeek]
     *
     * **不做成"缩成一个小点"**: 缩尺寸要动窗口大小, 那样半藏与展开是两套几何; 挪坐标只有一套
     */
    private fun peek() {
        if (peeked || ballDragging || menuView != null) return
        val layout = ballParams ?: return
        val ball = dp(BallView.BALL_SIZE_DP)
        val edge = BallGeometry.edgeFor(ballRestX, resources.displayMetrics.widthPixels, ball)
        peeked = true
        moveX(layout.x, BallGeometry.peekX(ballRestX, edge, ball))
        ballView?.animate()?.alpha(PEEK_ALPHA)?.setDuration(EDGE_SNAP_MS)?.start()
    }

    /** 滑回来: 有人碰它, 或者它有状态要说了 */
    private fun unpeek() {
        if (!peeked) return
        val layout = ballParams ?: return
        peeked = false
        moveX(layout.x, ballRestX)
        ballView?.animate()?.alpha(1f)?.setDuration(EDGE_SNAP_MS)?.start()
    }

    /** 把球的窗口挪到某个 x (半藏那个偏移已经算在里面): 吸附与半藏共用这一条动画 */
    private fun moveX(from: Int, to: Int, done: (() -> Unit)? = null) {
        snap?.cancel()
        snap = ValueAnimator.ofInt(from, to).setDuration(EDGE_SNAP_MS).apply {
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val held = ballParams ?: return@addUpdateListener
                held.x = animator.animatedValue as Int
                runCatching { window?.updateViewLayout(ballView, held) }
            }
            doOnEnd { done?.invoke() }
            start()
        }
    }

    /** 球的窗口位置 = 停靠点 (+ 半藏偏移) + 键盘那一层的钳制, 一切挪动都从这里走 */
    private fun applyBallPosition() {
        val layout = ballParams ?: return
        val metrics = resources.displayMetrics
        val ball = dp(BallView.BALL_SIZE_DP)
        val edge = BallGeometry.edgeFor(ballRestX, metrics.widthPixels, ball)
        layout.x = if (peeked) BallGeometry.peekX(ballRestX, edge, ball) else ballRestX
        layout.y = BallGeometry.clampAboveIme(ballBaseY, imeBottom, metrics.heightPixels, ball)
        runCatching { window?.updateViewLayout(ballView, layout) }
        OverlayState.x = ballRestX
        OverlayState.y = ballBaseY
    }

    /* ── 输入条 ───────────────────────────────────────────────────────────── */

    /** 展开: 把那条可获焦的窗挂上 (页面留着, 摘下来再挂回去不必重载) */
    private fun expand(intent: Intent?) {
        val manager = window ?: return
        intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotEmpty() }?.let { OverlayState.url = it }
        val url = OverlayState.url ?: (DshHost.status as? HostStatus.Running)?.url
        if (url.isNullOrEmpty()) {
            hint(getString(R.string.overlay_no_url))
            return
        }
        if (stripView != null) {
            OverlayState.expanded = true
            return
        }
        val metrics = resources.displayMetrics
        val margin = dp(MARGIN_DP)
        val width = intent?.getIntExtra(EXTRA_WIDTH, 0).orZero().takeIf { it > 0 }
            ?: (metrics.widthPixels - margin * 2)
        val height = intent?.getIntExtra(EXTRA_HEIGHT, 0).orZero().takeIf { it > 0 }
            ?: (metrics.heightPixels * DEFAULT_HEIGHT_PERCENT / 100)
        val layout = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 这一块是**照着获焦去的**: 只有能获焦的窗才收得到系统输入法, 代价是展开期间窗外
            // 不再穿透 —— 球那颗常驻的不获焦, 两边合起来才是"平时不挡, 打字时才占住"
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = intent?.getIntExtra(EXTRA_X, 0).orZero().takeIf { it > 0 } ?: margin
            y = intent?.getIntExtra(EXTRA_Y, 0).orZero().takeIf { it > 0 }
                ?: (metrics.heightPixels - height - dp(140)).coerceAtLeast(margin)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val view = stripView ?: buildStrip(url)
        try {
            manager.addView(view, layout)
        } catch (error: Throwable) {
            fail(getString(R.string.overlay_refused, error.message ?: error.toString()))
            return
        }
        stripView = view
        stripParams = layout
        stripBaseY = layout.y
        OverlayState.expanded = true
        OverlayState.url = url
        refresh(force = true)
    }

    /** 收起: 把输入条从窗口上摘下来, 球留着 (页面实例不销毁) */
    private fun collapse() {
        val view = stripView ?: return
        runCatching { window?.removeView(view) }
        OverlayState.expanded = false
        // 键盘那点高度是输入条上报的, 而它刚被摘下来 —— 摘下来之后还可能来一次收尾的 inset 回调,
        // 所以这里先把那个数清掉并在下一拍把球放回主人放的那个 y。**2026-10-05 真机上抓到的**:
        // 收起之后球比主人放的位置高了 440 px, 就是那次收尾回调把"键盘还占着 891 px"留在状态里
        imeBottom = 0
        handler.postDelayed({ runCatching { applyBallPosition() } }, IME_SETTLE_MS)
        refresh(force = true)
    }

    /** 关掉浮标: 存盘那个开关一起关, 否则下一次应用启动它又自己冒出来 */
    private fun hideBall() {
        BallSpot.setOn(this, false)
        stopSelf()
    }

    /**
     * 球上那一下「说话」
     *
     * 走的是常驻语音那一条链 (`LwWakeWord.speakNow`), **不是页面自己的麦克风** —— 球、输入条与主页
     * 三处共用同一个识别器, 而拒绝的理由 (缺权限 / 缺模型 / 正在念回答) 原样说给主人听, 不装作在听
     */
    private fun listenNow() {
        val refusal = runCatching { LwWakeWord.speakNow(this) }
            .getOrElse { error -> error.message ?: error.toString() }
        if (refusal != null) hint(refusal) else closeMenu()
    }

    /* ── 菜单 ─────────────────────────────────────────────────────────────── */

    private fun toggleMenu() {
        if (menuView != null) closeMenu() else showMenu()
    }

    private fun closeMenu() {
        val view = menuView ?: return
        menuView = null
        runCatching { window?.removeView(view) }
    }

    /**
     * 长按出来的那个菜单
     *
     * 它是第三块窗, 而不是 PopupWindow: overlay 窗里的 PopupWindow 要另设 windowLayoutType 才挂得上
     * 别处, 而这里已经有"加窗 / 摘窗"这一套现成的
     *
     * **它可获焦** (与球相反): 菜单开着的时候点外面应当关掉菜单, 而不是顺手按到底下那个应用的按钮上
     */
    private fun showMenu() {
        val manager = window ?: return
        val ball = ballParams ?: return
        val metrics = resources.displayMetrics
        val themed = ContextThemeWrapper(this, R.style.Theme_LittleWhale)
        val column = MenuRoot(themed).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(MENU_BACKGROUND)
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            onOutside = { closeMenu() }
        }
        column.addView(
            label(themed, getString(R.string.ball_menu_title, modeName())),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        menuEntries().forEach { (title, run) ->
            column.addView(menuRow(themed, title) { run() })
        }
        val width = dp(MENU_WIDTH_DP)
        val height = dp(MENU_HEIGHT_DP)
        val size = dp(BallView.BALL_SIZE_DP)
        val layout = WindowManager.LayoutParams(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCH_MODAL + WATCH_OUTSIDE_TOUCH = 点外面时我收到一个 OUTSIDE 事件并自己关掉,
            // 而底下那个应用**不会**同时被按一下
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val onRight = ball.x + size / 2 > metrics.widthPixels / 2
            x = if (onRight) (ball.x - width - dp(8)).coerceAtLeast(0) else ball.x + size + dp(8)
            y = ball.y.coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
        }
        try {
            manager.addView(column, layout)
        } catch (error: Throwable) {
            hint(getString(R.string.overlay_refused, error.message ?: error.toString()))
            return
        }
        menuView = column
        column.isFocusableInTouchMode = true
        column.requestFocus()
        column.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                closeMenu()
                true
            } else {
                false
            }
        }
    }

    /** 菜单里那几行: 说话 / 键盘 / 一直听 / 两个模式 / 回应用 / 关掉浮标 (D14: 「一直听」两处之一) */
    private fun menuEntries(): List<Pair<String, () -> Unit>> {
        val alwaysOn = LwWakeWord.allowVoice(this)
        return listOf(
            getString(R.string.ball_menu_speak) to { closeMenu(); listenNow() },
            getString(R.string.ball_menu_keyboard) to { closeMenu(); expand(null) },
            getString(if (alwaysOn) R.string.ball_menu_always_off else R.string.ball_menu_always_on) to {
                closeMenu()
                toggleAlwaysListening(alwaysOn)
            },
            getString(R.string.ball_menu_mode_video) to { closeMenu(); askMode(LwModes.VIDEO) },
            getString(R.string.ball_menu_mode_phone) to { closeMenu(); askMode(LwModes.PHONE) },
            getString(R.string.ball_menu_app) to { closeMenu(); openApp() },
            getString(R.string.ball_menu_close) to { closeMenu(); hideBall() },
        )
    }

    /**
     * 「一直听」那一个许可是常驻语音的开关, **不是唤醒词的**
     *
     * 唤醒词那个许可关着时这里什么都不做、只把原因说出来: 替主人把它按下去就是"一个许可自己打开",
     * 那条纪律上一批刚从 `LwModes` 里修掉一次
     */
    private fun toggleAlwaysListening(turnOn: Boolean) {
        if (!LwWakeWord.allow(this)) {
            hint(getString(R.string.ball_allow_wake_off))
            return
        }
        LwWakeWord.setAllow(this, LwWakeWord.allow(this), turnOn)
        LwWakeWord.refresh(this)
        hint(getString(if (turnOn) R.string.ball_voice_on else R.string.ball_voice_off))
    }

    /**
     * 菜单里那两个模式: 写的是一句话, 走的是与"说出来"同一条路
     *
     * 为什么不在这里直接调 `LwModes`: 命令词表只有一份, 在宿主插件里 (可行性稿 2.7: 改词表不必重下
     * 关键词表也不必重建 APK); 这里再写一遍就成了第二份实现, 两份迟早漂开
     */
    private fun askMode(mode: String) {
        if (DshHost.status !is HostStatus.Running) {
            hint(getString(R.string.ball_mode_no_host))
            return
        }
        val line = if (mode == LwModes.VIDEO) VoiceCommands.VIDEO else VoiceCommands.PHONE
        if (VoiceInbox.append(this, line) == null) {
            hint(getString(R.string.ball_mode_cannot_queue))
            return
        }
        hint(getString(R.string.ball_mode_asked, modeName(mode)))
    }

    private fun modeName(mode: String? = null): String =
        getString(if ((mode ?: LwModes.active(this)) == LwModes.VIDEO) R.string.mode_video else R.string.mode_phone)

    /* ── 状态 ─────────────────────────────────────────────────────────────── */

    /**
     * 400 ms 一次: 球上那三个字、输入条标题与通知栏都照它改
     *
     * **三个字有两个来源**: "正在念"与"正在听"在应用这一侧 (`VoiceState`), "正在想"是宿主推来的
     * (`OverlayState.phase`, 见 host-plugin 的 turn/start 与 turn/end) —— 而宿主没在跑的时候那条
     * phase 一律不采纳, 否则宿主崩了球会永远停在"正在想"
     */
    private fun refresh(force: Boolean = false) {
        val metrics = resources.displayMetrics
        if (force || metrics.widthPixels != lastWidth || metrics.heightPixels != lastHeight) {
            lastWidth = metrics.widthPixels
            lastHeight = metrics.heightPixels
            replace()
        }
        val word = BallStatus.wordFor(
            speaking = VoiceState.speaking,
            thinking = OverlayState.phase == OverlayState.PHASE_THINKING && DshHost.status is HostStatus.Running,
            listening = WakeWordState.voiceActive || VoiceState.capturing,
        )
        if (force || word != lastWord) {
            lastWord = word
            ballView?.show(word)
            OverlayState.word = word?.label(this)
            stripTitle?.text = title(word)
        }
        // 有状态就滑出来说话, 闲着就收边 (参考那套的 `floating_ball_idle_to_edge`); 手指正按着、
        // 或者菜单开着的时候不收 —— 那两种情形下它正在被用
        if (word != null) {
            unpeek()
        } else if (!peeked && !ballDragging && menuView == null &&
            System.currentTimeMillis() - lastTouchAt > PEEK_IDLE_MS
        ) {
            peek()
        }
        val notice = notice(word)
        if (force || notice != lastNotice) {
            lastNotice = notice
            announce(notice)
        }
    }

    /** 输入条那条标题: 「DSH-LW · 正在听 · 视频模式」(没有状态词时就只剩应用名与模式) */
    private fun title(word: BallWord?): String {
        val parts = mutableListOf(getString(R.string.app_name))
        word?.let { parts += it.label(this) }
        if (LwModes.active(this) == LwModes.VIDEO) parts += modeName(LwModes.VIDEO)
        return parts.joinToString(" · ")
    }

    /** 通知栏那一行: 球在不在、什么模式、正在做什么 (三种状态都如实说) */
    private fun notice(word: BallWord?): String = listOfNotNull(
        getString(R.string.overlay_notification),
        modeName(),
        word?.label(this),
    ).joinToString(" · ")

    private fun replace() {
        val metrics = resources.displayMetrics
        val ball = dp(BallView.BALL_SIZE_DP)
        val edge = BallSpot.read(this)?.first ?: BallGeometry.edgeFor(ballRestX, metrics.widthPixels, ball)
        ballRestX = BallGeometry.xForEdge(edge, metrics.widthPixels, ball)
        ballBaseY = BallGeometry.clampY(ballBaseY, metrics.heightPixels, ball)
        applyBallPosition()
    }

    /**
     * 键盘那一层
     *
     * inset 只能是**那块可获焦的输入条**给的 (球自己不收输入法, 它的窗上报 0): 键盘起来时输入条
     * 整体抬到键盘上沿, 球抬到不会被键盘压住的地方; 拿不到 inset 时两边都原样不动 —— 猜一个高度
     * 只会让球跳到半空中
     */
    private fun applyIme() {
        val metrics = resources.displayMetrics
        stripParams?.let { layout ->
            val lifted = if (imeBottom > 0 &&
                layout.y + layout.height > metrics.heightPixels - imeBottom
            ) {
                (metrics.heightPixels - imeBottom - layout.height).coerceAtLeast(0)
            } else {
                stripBaseY
            }
            if (layout.y != lifted) {
                layout.y = lifted
                runCatching { window?.updateViewLayout(stripView, layout) }
            }
        }
        // 球靠同一条 inset 避开键盘 (它自己那块窗上报 0), 钳制写在 applyBallPosition 里
        applyBallPosition()
    }

    /** 说一句话给主人听 (Toast + 记一笔): 它**不是**失败, 所以不写 [OverlayState.lastError] */
    private fun hint(text: String) {
        OverlayState.lastHint = text
        Log.i(TAG, text)
        runCatching { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    private fun fail(reason: String) {
        OverlayState.lastError = reason
        OverlayState.showing = false
        Log.w(TAG, reason)
        stopSelf()
    }

    /* ── 输入条那一块 (面板 A) ─────────────────────────────────────────────── */

    /** 拖动的那一条: 标题栏按住就能挪, 位置是相对屏幕左上角 */
    private fun dragHandle(bar: View) {
        bar.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var fromX = 0
            private var fromY = 0

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val layout = stripParams ?: return false
                // 窗不一定还在: 收起与拖动可以撞在一起, 所以这里也判一次空
                val target = stripView ?: return false
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
                        // 键盘没起来时这一处就是"主人放的那个 y", 键盘起来时它留给落回去用
                        if (imeBottom == 0) stripBaseY = layout.y
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (imeBottom == 0) stripBaseY = layout.y
                        return true
                    }
                }
                return false
            }
        })
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

    private fun buildStrip(url: String): View {
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
        val heading = TextView(themed).apply {
            text = title(lastWord)
            setTextColor(TEXT)
            textSize = 14f
        }
        bar.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stripTitle = heading
        val collapseButton = button(themed, getString(R.string.overlay_collapse)) { collapse() }
        val toApp = button(themed, getString(R.string.overlay_app)) { openApp() }
        val close = button(themed, getString(R.string.overlay_close)) { hideBall() }
        bar.addView(collapseButton)
        bar.addView(toApp)
        bar.addView(close)
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dragHandle(bar)

        // 键盘那点高度只有这块窗拿得到 (它是唯一可获焦的那一块), 球在另一个窗里靠它避让
        ViewCompat.setOnApplyWindowInsetsListener(column) { view, insets ->
            // **已经摘下来的条不再认 inset**: 收起之后那次收尾回调会拿一个"已经没有键盘"的高度
            // 去按球 (见 [collapse])
            if (!view.isAttachedToWindow) return@setOnApplyWindowInsetsListener insets
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (ime != imeBottom) {
                imeBottom = ime
                runCatching { applyIme() }
            }
            insets
        }

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

    private fun menuRow(parent: Context, label: String, onClick: () -> Unit): TextView =
        TextView(parent).apply {
            text = label
            setTextColor(TEXT)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun label(parent: Context, text: String): TextView =
        TextView(parent).apply {
            this.text = text
            setTextColor(HINT)
            textSize = 12f
            setPadding(dp(14), dp(10), dp(14), dp(6))
        }

    private fun button(parent: Context, label: String, onClick: () -> Unit): TextView =
        TextView(parent).apply {
            text = label
            setTextColor(TEXT)
            textSize = 13f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
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
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(notice(null)), both)
        } catch (error: Throwable) {
            Log.w(TAG, "the microphone foreground type was refused, falling back to specialUse", error)
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(notice(null)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }
    }

    private fun notification(text: String): Notification {
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
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(0, getString(R.string.overlay_close), close)
            .setOngoing(true)
            .build()
    }

    private fun announce(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        const val ACTION_SHOW = "io.github.miuzarte.littlewhale.overlay.SHOW"
        const val ACTION_HIDE = "io.github.miuzarte.littlewhale.overlay.HIDE"
        const val ACTION_EXPAND = "io.github.miuzarte.littlewhale.overlay.EXPAND"
        const val ACTION_COLLAPSE = "io.github.miuzarte.littlewhale.overlay.COLLAPSE"
        const val ACTION_LISTEN = "io.github.miuzarte.littlewhale.overlay.LISTEN"
        const val EXTRA_URL = "url"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_EXPAND = "expand"

        private const val TAG = "LwOverlay"
        private const val CHANNEL_ID = "lw-overlay"

        /** 与 LwNotify 那条通知分开: 那条是给模型的, 这条是浮窗自己的常驻 */
        private const val NOTIFICATION_ID = 3
        private const val MARGIN_DP = 12
        private const val BAR_HEIGHT_DP = 44
        private const val DEFAULT_HEIGHT_PERCENT = 45
        private const val MENU_WIDTH_DP = 168
        private const val MENU_HEIGHT_DP = 320
        /** 与那套内置动画的缺省时长同一个数 (200 ms 的淡入淡出/缩放) */
        private const val EDGE_SNAP_MS = 200L

        /** 闲置多久收边, 以及半藏时那点透明度 (参考那套闲置那一档是 0.29, 这里留得亮一些好认) */
        private const val PEEK_IDLE_MS = 4_000L
        private const val PEEK_ALPHA = 0.5f

        /** 收起输入条之后等一拍再摆球: 键盘那一次收尾的 inset 回调就在这之后 */
        private const val IME_SETTLE_MS = 400L

        /** 与唤醒词服务那个观察者同一个节奏: 400 ms 一次, 只在变了的时候重画 */
        private const val TICK_MS = 400L

        private val BACKGROUND = 0xF21B1B1F.toInt()
        private val MENU_BACKGROUND = 0xF71B1B1F.toInt()
        private val TEXT = 0xFFEDEDED.toInt()
        private val HINT = 0x99EDEDED.toInt()
    }
}

/**
 * 菜单那层要"点外面就关掉"
 *
 * `FLAG_WATCH_OUTSIDE_TOUCH` 给的是一个 `ACTION_OUTSIDE` (坐标在窗外), 它只会走到窗口根那一层 ——
 * 而 `LinearLayout` 自己不吃这个事件, 所以这里收一下
 */
private class MenuRoot(context: Context) : LinearLayout(context) {

    var onOutside: (() -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }
}
