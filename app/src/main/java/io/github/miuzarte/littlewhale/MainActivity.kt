package io.github.miuzarte.littlewhale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import io.github.miuzarte.littlewhale.channel.LwApps
import io.github.miuzarte.littlewhale.channel.LwOcr
import io.github.miuzarte.littlewhale.channel.PreviewControl
import io.github.miuzarte.littlewhale.channel.ScreenshotBudget
import io.github.miuzarte.littlewhale.host.DshHostService
import io.github.miuzarte.littlewhale.theme.ThemeStore
import io.github.miuzarte.littlewhale.tool.LwWakeWord
import io.github.miuzarte.littlewhale.tool.SpeakSettings
import io.github.miuzarte.littlewhale.ui.LittleWhaleApp
import io.github.miuzarte.littlewhale.util.PermissionRequests

class MainActivity : ComponentActivity() {

    /**
     * 设置页「权限」段点名要的那些, 都在这里落地
     *
     * 申请只能从 Activity 发起, 而按钮在设置页里, 所以设置页只把"要什么"写进
     * [PermissionRequests.pending], 由 `setContent` 里那个 `LaunchedEffect` 看到之后再弹系统框
     *
     * **一次只发一条**: 一次带两条时 `Activity` 会打 `W Can request only one set of permissions at
     * a time` 并丢掉后一条 (真机 logcat 实测), 于是点一下什么都没有发生。所以结果回来之后立刻问队列
     * 要下一条, 直到问完为止
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        PermissionRequests.answered()
        launchNextPermission()
    }

    /** 把队列里下一条还没问过的权限弹出去, 没有了就收尾 */
    private fun launchNextPermission() {
        val next = PermissionRequests.next(consume = true)
        if (next != null) {
            permissionLauncher.launch(next)
            return
        }
        PermissionRequests.done()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 点通知进来的时候, 屏幕可能锁着: 让这一页越过锁屏显示, 是"从通知回到应用"这条路能走通的
        // 前提。它不解锁设备 —— 要看里面的内容仍然要解锁
        setShowWhenLocked(true)
        // 外观设置要在第一帧之前读出来, 否则会先闪一下默认主题
        ThemeStore.initialize(this)
        // 同理: 预览能不能当触摸板, 默认是关, 得在画画面之前就知道
        PreviewControl.initialize(this)
        // 截图缩到多少像素, 桥在第一次截图时就要用上
        ScreenshotBudget.initialize(this)
        // 朗读的音色与语速: 回答落定就念那条链要用, 而设置页也要在画之前知道选的是哪个
        SpeakSettings.initialize(this)
        // OCR 的模型是懒加载的 (第一次调它才建 session), 这里只把 context 挂上去
        LwOcr.attach(this)
        // 能启动哪些应用要问 PackageManager, 同样只挂 context
        LwApps.attach(this)
        // The host deliberately outlives this activity, so the service owns its lifetime
        DshHostService.start(this)
        // 唤醒词那个许可是存盘的, 所以应用一起来就照着它把监听恢复起来 —— 不然"允许唤醒"只是个记号,
        // 服务不会自己起, 而主人按下它的意思显然是"让它听着", 缺模型 / 缺权限 / 许可关着时它什么都不做;
        // 起不来也不该把界面带走 (那是前台服务那一侧的事, 它会把原因记在状态里)
        runCatching { LwWakeWord.ensure(this) }
        setContent {
            LittleWhaleApp()
            // 设置页里点过的那条授权申请到这一层才真的弹。挂 Content 外面那一层而不是某一页里,
            // 是因为申请要跟着 Activity 活着, 页面来回切不该把它弄丢
            val pending = PermissionRequests.pending ?: return@setContent
            LaunchedEffect(pending) {
                launchNextPermission()
            }
        }
    }
}
