package io.github.miuzarte.littlewhale.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.host.BallReturn
import io.github.miuzarte.littlewhale.theme.ApplySystemBarsAppearance
import io.github.miuzarte.littlewhale.theme.ThemeSettings
import io.github.miuzarte.littlewhale.theme.ThemeStore
import io.github.miuzarte.littlewhale.theme.controller
import kotlinx.serialization.Serializable

/** 应用里有哪几页, 可序列化是因为导航要把返回栈存下来 */
@Serializable
sealed interface Screen : NavKey {
    @Serializable
    data object Home : Screen

    @Serializable
    data object Settings : Screen
}

/** 谁想换页就问它, 页面自己不持有返回栈 */
class RootNavigator(
    val push: (Screen) -> Unit,
    val pop: () -> Unit,
)

val LocalRootNavigator = staticCompositionLocalOf<RootNavigator> {
    error("No RootNavigator provided")
}

/**
 * 整个应用: 主题 + 两页之间的导航
 *
 * 外观设置在这里落到 Miuix 上, 所以设置页改完立刻生效, 不需要重启; 导航用的是 miuix-nav,
 * 页面切换的动效与侧滑返回也跟着设置走
 */
@Composable
fun LittleWhaleApp() {
    val settings = ThemeStore.current
    val backStack = rememberNavBackStack<Screen>(Screen.Home)
    val navigator = remember(backStack) {
        RootNavigator(
            push = { backStack.add(it) },
            pop = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        )
    }
    // 「回应用」那一跳 (双击回复框 / 菜单「返回应用」) 落到设置页那一层时: **先回主页** —— 会话界面在
    // 主页上, 不回去的话会话切了也看不见。请求本身由主页那块 WebView 落地 (见 HostScreen), 这里只管
    // 把挡在前面的页收掉
    val ballRequest = BallReturn.request
    LaunchedEffect(ballRequest?.seq) {
        if (ballRequest == null) return@LaunchedEffect
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    val controller = remember(
        settings.mode,
        settings.monet,
        settings.seed,
        settings.palette,
        settings.spec,
    ) {
        settings.controller()
    }
    val swipe = if (settings.swipeBack) NavSwipeDirection.LeftToRight else NavSwipeDirection.None
    val crossActivity = settings.transition == ThemeSettings.TRANSITION_AOSP
    val transition = if (crossActivity) CrossActivityTransition else NavTransitions.MiuixDefault

    // 主页上按返回不等于离开应用: 这一句提示是"再按一次"的第一次, 第二次才把任务放到后台去。
    // 不管任务是去是留, host 与虚拟屏都照跑, 所以这里既不 finish 也不停服务
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()

    /**
     * 返回键这一下归这里管
     *
     * 第一下弹提示, 第二下把任务放到后台 —— **不是退出**: 服务与虚拟屏照跑, 从最近任务回来还是原样。
     * 提示条挂在主页那层 `SnackbarHost` 上, 所以这里只负责决定"弹"还是"走"
     */
    val onBack = {
        if (ExitHint.press()) {
            activity?.moveTaskToBack(true)
        } else if (ExitHint.showHint()) {
            val message = activity?.getString(R.string.exit_hint_again)
            if (message != null) {
                scope.launch {
                    ExitHint.host.showSnackbar(
                        message,
                        duration = SnackbarDuration.Custom(ExitHint.VISIBLE_MS),
                    )
                }
            }
        }
    }

    /**
     * 破坏性操作等人点一下
     *
     * 挂在整个应用这一层而不是某一页里: 模型要卸应用的时候, 人可能正看着设置页, 也可能停在主页
     */
    @Composable
    fun confirm() {
        val pending = DestructiveConfirm.pending
        OverlayDialog(
            show = pending != null,
            title = stringResource(R.string.destructive_confirm_title),
            summary = pending?.let { "${it.what}\n\n${it.target}" } ?: "",
            onDismissRequest = { pending?.decide(false) },
            onDismissFinished = {},
        ) {
            Text(
                text = stringResource(R.string.destructive_confirm_hint, DESTRUCTIVE_WAIT_SECONDS),
                color = colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.button_cancel),
                    onClick = { pending?.decide(false) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.destructive_confirm_ok),
                    onClick = { pending?.decide(true) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }

    MiuixTheme(controller = controller) {
        // 系统栏图标要跟着实际渲染出来的配色, 手动钉成深色时也得跟着变
        ApplySystemBarsAppearance(LocalActivity.current?.window)
        val cornerRadius = rememberNavSystemCornerRadius()
        val backdrop = colorScheme.surface
        val effects = remember(cornerRadius, backdrop, crossActivity) {
            NavDisplayEffects(
                enableCornerClip = true,
                cornerClipRadius = cornerRadius,
                // 跨 activity 那一套四角都收, Miuix 默认只收上边缘那两个角
                cornerClipMode = if (crossActivity) NavCornerClipMode.All else NavCornerClipMode.Leading,
                dimAmount = 0.5f,
                blockInputDuringTransition = true,
                backdropColor = backdrop,
            )
        }
        CompositionLocalProvider(
            LocalRootNavigator provides navigator,
            LocalSquircleEnabled provides settings.squircle,
        ) {
            NavDisplay(
                backStack = backStack,
                // 还有上一页就是普通的返回; 只剩主页时那一下交给 onBack 决定
                onBack = { if (backStack.size > 1) navigator.pop() else onBack() },
                transition = transition,
                effects = effects,
            ) {
                entry<Screen.Home>(swipeDismiss = swipe) { HostScreen() }
                entry<Screen.Settings>(swipeDismiss = swipe) { SettingsScreen() }
            }
            // 破坏性操作的确认框挂在整个应用这一层: 不管当时在哪一页, 模型要卸应用都得先让人点一下
            confirm()
            // **返回键的最后一道**: NavDisplay 的 onBack 在返回栈空的时候不一定被叫到 (实现在库
            // 里, 不保证), 而没被叫到的那一下是平台的默认行为 —— 把这个 activity 关掉, 于是 host
            // 与虚拟屏一起没了。这个 handler 在 Composition 里登记, 比库自己的早, 所以那一下会被
            // 这里吃掉: 要么弹提示, 要么把任务放到后台, 两者都不结束任何东西
            BackHandler { onBack() }
        }
    }
}

/**
 * 等人点一下的上限, 秒
 *
 * 与 `DestructiveConfirm` 里那个常量说的是同一件事 (那边是毫秒, 那边判超时): 这是个文件级的常数,
 * 因为 `confirm` 是个 composable, 里面只能有表达式
 */
private const val DESTRUCTIVE_WAIT_SECONDS = 100
