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
import io.github.miuzarte.littlewhale.ui.LittleWhaleApp
import io.github.miuzarte.littlewhale.util.PermissionRequests

class MainActivity : ComponentActivity() {

    /**
     * 设置页「权限」段点名要的那些, 都在这里落地
     *
     * 申请只能从 Activity 发起, 而按钮在设置页里, 所以设置页只把"要什么"写进
     * [PermissionRequests.pending], 由 `setContent` 里那个 `LaunchedEffect` 看到之后再弹系统框
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { PermissionRequests.done() }

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
        // OCR 的模型是懒加载的 (第一次调它才建 session), 这里只把 context 挂上去
        LwOcr.attach(this)
        // 能启动哪些应用要问 PackageManager, 同样只挂 context
        LwApps.attach(this)
        // The host deliberately outlives this activity, so the service owns its lifetime
        DshHostService.start(this)
        setContent {
            LittleWhaleApp()
            // 设置页里点过的那条授权申请到这一层才真的弹。挂 Content 外面那一层而不是某一页里,
            // 是因为申请要跟着 Activity 活着, 页面来回切不该把它弄丢
            val pending = PermissionRequests.pending ?: return@setContent
            LaunchedEffect(pending) {
                permissionLauncher.launch(pending.permissions.toTypedArray())
            }
        }
    }
}
