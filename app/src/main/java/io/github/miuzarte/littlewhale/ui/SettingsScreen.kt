package io.github.miuzarte.littlewhale.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.tts.Voice
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.channel.AccessibilitySetting
import io.github.miuzarte.littlewhale.channel.ChannelSetting
import io.github.miuzarte.littlewhale.channel.LwOcr
import io.github.miuzarte.littlewhale.channel.NotificationSetting
import io.github.miuzarte.littlewhale.channel.RemoteBackend
import io.github.miuzarte.littlewhale.channel.RouteState
import io.github.miuzarte.littlewhale.channel.ScreenshotBudget
import io.github.miuzarte.littlewhale.tool.LwCamera
import io.github.miuzarte.littlewhale.tool.VideoLooks
import io.github.miuzarte.littlewhale.constants.UiSpacing
import io.github.miuzarte.littlewhale.host.DshHost
import io.github.miuzarte.littlewhale.host.HostStatus
import io.github.miuzarte.littlewhale.overlay.BallGeometry
import io.github.miuzarte.littlewhale.overlay.BallSpot
import io.github.miuzarte.littlewhale.overlay.BallSwitch
import io.github.miuzarte.littlewhale.overlay.OverlayState
import io.github.miuzarte.littlewhale.scaffolds.ArrowSlider
import io.github.miuzarte.littlewhale.scaffolds.LazyColumn
import io.github.miuzarte.littlewhale.scaffolds.SectionSmallTitle
import io.github.miuzarte.littlewhale.scaffolds.SuperTextField
import io.github.miuzarte.littlewhale.theme.MonetKeyColorOptions
import io.github.miuzarte.littlewhale.theme.ThemeSettings
import io.github.miuzarte.littlewhale.theme.ThemeStore
import io.github.miuzarte.littlewhale.tool.LwEdgeSpeech
import io.github.miuzarte.littlewhale.tool.LwOverlay
import io.github.miuzarte.littlewhale.tool.LwSpeak
import io.github.miuzarte.littlewhale.tool.LwTts
import io.github.miuzarte.littlewhale.tool.LwWakeWord
import io.github.miuzarte.littlewhale.tool.SpeakSettings
import io.github.miuzarte.littlewhale.util.Grant
import io.github.miuzarte.littlewhale.util.PermissionCatalog
import io.github.miuzarte.littlewhale.util.PermissionGate
import io.github.miuzarte.littlewhale.util.PermissionRequests
import io.github.miuzarte.littlewhale.wake.WakeWordDownload
import io.github.miuzarte.littlewhale.wake.WakeWordState
import io.github.miuzarte.littlewhale.wake.WakeWordWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 设置页的字符串都在 `strings.xml` 里 (en 与 zh 各一份), 名字以 `settings_` 开头, 措辞照 SFA 的
 * `values-zh/strings.xml` - 名字对齐了才不用记两套说法
 *
 * 这一页原来硬编码中文 (2026-09-21 定的"不做多语言"), 2026-09-22 改成跟着资源走
 */

/** 外观模式, 与 `ThemeSettings.mode` 的下标一一对应 (SFA 的 theme_follow_system / light / dark) */
private val themeModes = listOf(
    R.string.settings_theme_follow_system,
    R.string.settings_theme_light,
    R.string.settings_theme_dark,
)

/**
 * 过渡风格, 与 `ThemeSettings.transition` 的下标一一对应
 *
 * 就是 SFA 的 `pref_transition_style_miuix` 与 `pref_transition_style_aosp` 两项, 没有 "无": 页面
 * 切换要么是 Miuix 那种整页平移, 要么是平台那种缩小让位
 */
private val transitionStyles = listOf(
    R.string.settings_transition_miuix,
    R.string.settings_transition_aosp,
)

/** 调色板风格与色彩规范直接取 Miuix 的枚举名, 它们是 Google 那边的术语 */
private val paletteStyles = ThemePaletteStyle.entries.map { it.name }
private val colorSpecs = ThemeColorSpec.entries.map { it.name }

/**
 * 设置页
 *
 * 它自己是滚动的, 顶栏跟着滚; 缩进与段间距都由 [LazyColumn] 那个脚手架统一给, 一段一个 item,
 * Card 自己不写外边距 - 这是照 SFA 的排法, 免得每段各缩各的
 *
 * 外观那几项改完立刻作用到整个应用, 因为主题是 [LittleWhaleTheme] 外面那一层在管; 工作区与网络从
 * 原来的两个面板搬到这里, 平铺成两段, 它们要重启 host 才生效
 */
@Composable
fun SettingsScreen() {
    val navigator = LocalRootNavigator.current
    val context = LocalContext.current
    val settings = ThemeStore.current
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = navigator.pop) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                // 要重启 host 才生效的事都收在这里, 页面上就不必每个面板各放一个重启按钮
                actions = {
                    OverlayIconDropdownMenu(
                        entry = DropdownEntry(
                            items = listOf(
                                DropdownItem(
                                    text = stringResource(R.string.settings_restart_host),
                                    summary = stringResource(R.string.settings_restart_host_summary),
                                    onClick = { DshHost.restart(context) },
                                ),
                            ),
                        ),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.More,
                            contentDescription = stringResource(R.string.settings_more),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = innerPadding,
            scrollBehavior = scrollBehavior,
        ) {
            item {
                SectionSmallTitle(stringResource(R.string.settings_section_appearance))
                TabRow(
                    tabs = themeModes.map { stringResource(it) },
                    selectedTabIndex = settings.mode.coerceIn(0, themeModes.lastIndex),
                    onTabSelected = { ThemeStore.update(settings.copy(mode = it)) },
                )
                Spacer(modifier = Modifier.height(UiSpacing.ContentVertical))
                Card {
                    SwitchPreference(
                        title = stringResource(R.string.settings_monet),
                        summary = stringResource(R.string.settings_monet_summary),
                        checked = settings.monet,
                        onCheckedChange = { ThemeStore.update(settings.copy(monet = it)) },
                    )
                    AnimatedVisibility(settings.monet) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.settings_monet_key_color),
                            summary = stringResource(R.string.settings_monet_key_color_summary),
                            items = MonetKeyColorOptions,
                            selectedIndex = settings.seed.coerceIn(0, MonetKeyColorOptions.lastIndex),
                            onSelectedIndexChange = { ThemeStore.update(settings.copy(seed = it)) },
                        )
                    }
                    AnimatedVisibility(settings.monet && settings.seed > 0) {
                        Column {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_monet_palette_style),
                                summary = stringResource(R.string.settings_monet_palette_style_summary),
                                items = paletteStyles,
                                selectedIndex = settings.palette.coerceIn(0, paletteStyles.lastIndex),
                                onSelectedIndexChange = { ThemeStore.update(settings.copy(palette = it)) },
                            )
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_monet_color_spec),
                                summary = stringResource(R.string.settings_monet_color_spec_summary),
                                items = colorSpecs,
                                selectedIndex = settings.spec.coerceIn(0, colorSpecs.lastIndex),
                                onSelectedIndexChange = { ThemeStore.update(settings.copy(spec = it)) },
                            )
                        }
                    }
                    SwitchPreference(
                        title = stringResource(R.string.settings_squircle),
                        summary = stringResource(R.string.settings_squircle_summary),
                        checked = settings.squircle,
                        onCheckedChange = { ThemeStore.update(settings.copy(squircle = it)) },
                    )
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_navigation))
                Card {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.settings_transition),
                        summary = stringResource(R.string.settings_transition_summary),
                        items = transitionStyles.map { stringResource(it) },
                        selectedIndex = settings.transition.coerceIn(0, transitionStyles.lastIndex),
                        onSelectedIndexChange = { ThemeStore.update(settings.copy(transition = it)) },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.settings_swipe_back),
                        summary = stringResource(R.string.settings_swipe_back_summary),
                        checked = settings.swipeBack,
                        onCheckedChange = { ThemeStore.update(settings.copy(swipeBack = it)) },
                    )
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_channel))
                Card {
                    ChannelItems()
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_accessibility))
                Card {
                    AccessibilityItems()
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_notifications))
                Card {
                    NotificationItems()
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_screenshot))
                Card {
                    ScreenshotItems()
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_look))
                Card {
                    LookItems()
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_ocr))
                Card {
                    OcrItems()
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_wake))
                Card {
                    WakeItems()
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_ball))
                Card {
                    BallItems()
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_speak))
                Card {
                    SpeakItems()
                }
            }

            // 工作区与网络这两段已经从这里撤掉 (主人 2026-10-06): 工作区落在哪是 host 启动时按
            // Workspace.resolve 那三档自己挑的, 网络那个开关改完也要重启 host —— 两件事都是"平时
            // 不用动"的, 留在这里只把下面那些常用的段推得更远。**能力一个都没删**: 三档解析、
            // Workspace.requestAllFilesAccess、HostSettings.lanAccess 与上面 ⋮ 里那条重启 host 照旧,
            // 只是不再有这两个设置入口 (要改是 `tools/lw-install.ps1` 与重启那一步的事)

            // 权限这一段放最后: 它是这一页最长的一段 (十七个能力), 放中间会把后面每一段都推到很远,
            // 而这些权限本来就是装完之后偶尔来调一次的东西
            item {
                SectionSmallTitle(stringResource(R.string.settings_section_permissions))
                Card {
                    PermissionsItems()
                }
            }
        }
    }
}

/**
 * 特权通道: 动屏幕的能力都要先有它, 而它要么经 root 要么经 Shizuku
 *
 * 连接本身**不在这里发起**: 应用启动时 `DshHostService` 就在后台连了一次 (连一次要起一个
 * app_process, 是秒级的, 不该落在模型第一次截图上), 所以这一页通常进来就是"已连接"。按钮只在
 * 没连上时出现, 它是**重试** —— 真正的用处是"刚在 KernelSU 里给完授权, 不想重启应用"
 *
 * 只列状态, 不解释: 两条路各自在哪一档一眼能看完。**root 那行可能是"未检测"**, 这不是偷懒 ——
 * 有没有 root 在应用侧查不到 (KernelSU 对没在名单上的应用把 `su` 整个收走, 而不是拿出来问一句),
 * 所以"没授权"与"还没试过"在试之前是同一件事
 */
@Composable
private fun ChannelItems() {
    val state = ChannelSetting.state
    val routes = ChannelSetting.routes
    val working = ChannelSetting.working
    LaunchedEffect(Unit) { ChannelSetting.refresh() }
    // fillMaxWidth 是必要的: 这一段的子项全是纯文字, 撑不满宽度, 而 Miuix 的 Card 是包着内容的 ——
    // 不填满, 卡片就比这一页别的卡片窄一截 (设置项自己会撑开, 所以别处不用管)
    Column(modifier = Modifier.fillMaxWidth().padding(UiSpacing.Large)) {
        Text(
            text = when {
                state == null -> stringResource(R.string.settings_channel_reading)
                state.connected -> stringResource(
                    R.string.settings_channel_connected,
                    state.backend.orEmpty(),
                    state.uid ?: -1,
                )

                else -> stringResource(
                    R.string.settings_channel_disconnected,
                    state.error.orEmpty(),
                )
            },
        )
        routes.forEach { route ->
            Text(
                text = stringResource(
                    R.string.settings_channel_route,
                    route.backend.label,
                    stringResource(routeState(route)),
                ),
                modifier = Modifier.padding(top = UiSpacing.Medium),
            )
        }
        if (state?.connected != true) {
            Button(
                onClick = { ChannelSetting.connect() },
                enabled = !working,
                modifier = Modifier.fillMaxWidth().padding(top = UiSpacing.Medium),
            ) {
                Text(
                    text = stringResource(
                        if (working) R.string.settings_channel_connecting else R.string.settings_channel_connect,
                    ),
                )
            }
        }
    }
}

/** 一条路在哪一档, 只有 root 会有"未检测": 别的那两个问题不用试就能答 */
private fun routeState(route: RouteState): Int = when (route.backend) {
    RemoteBackend.ROOT -> when (route.granted) {
        true -> R.string.settings_channel_state_allowed
        false -> R.string.settings_channel_state_denied
        null -> R.string.settings_channel_state_unknown
    }

    RemoteBackend.SHIZUKU -> when {
        !route.available -> R.string.settings_channel_state_stopped
        route.granted == true -> R.string.settings_channel_state_allowed
        else -> R.string.settings_channel_state_denied
    }
}

/**
 * 权限那一段
 *
 * 每一条是一个能力, 不是一个权限名: 模型问的是"能不能发通知", 而不是"有没有 POST_NOTIFICATIONS"。
 * 状态每次重进这一页现读 (用户可能在系统设置页里改过), 所以 [PermissionCatalog] 的答案不缓存
 *
 * 两条路: 能点名要的直接弹系统框 (`PermissionRequests` -> MainActivity 里的 launcher), 只能去系
 * 统页点的就开那一页。**没有第三步** —— 这里不做"假装已授权"
 */
@Composable
private fun PermissionsItems() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    // 申请的结果回来要重画, 所以留一个能变的记号
    var revision by remember { mutableStateOf(0) }
    val runtime = PermissionCatalog.runtime
    val special = PermissionCatalog.special

    // 从系统设置页回来时把每一条重读一遍
    //
    // 这一步是给"安装未知应用"那类权限用的: 它的开关在系统那一页上 (设置 → 安装未知应用),
    // **应用这边读的是 canRequestPackageInstalls() 的实时值**, 但只在重组时读 —— 用户去那一页把开关
    // 打开再回来, 页面不重组, 于是显示的还是旧状态。ON_RESUME 正好是"从别处回来"的那一刻
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 这一段自己是纯文本, 不像设置项那样自带内边距, 所以**自己缩进**:
    //   左 16dp 与设置项的标题对齐, 上 16dp 让第一行离开卡片的圆角 —— 圆角是按卡片边裁的, 文字贴着
    //   左上角时前几个字会被那道弧切掉 (实测就是这样)
    // 颜色用主题里的次要文本档, 不写死灰度: 它在浅色与深色下各自是对的那一支
    Text(
        text = stringResource(R.string.settings_permissions_summary),
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(
            start = UiSpacing.Large,
            end = UiSpacing.Large,
            top = UiSpacing.Large,
            bottom = UiSpacing.Medium,
        ),
    )
    (runtime + special).forEach { capability ->
        // revision 变了就重算: 这是"授权之后状态要跟着变"的那一处
        val state = remember(revision, capability) { PermissionCatalog.state(context, capability) }
        ArrowPreference(
            title = capability.name,
            // 副标题是"状态 · 它是干什么的"。**但有一条例外**: 「安装未知应用」这台设备上读出来的值
            // (canRequestPackageInstalls) 与系统页上看到的对不上, 与其在屏幕上放一个会和系统打架的
            // 结论, 那一条只留说明 —— 点一下就是去它自己那一页, 那里才是权威
            summary = if (capability.noState) {
                capability.note ?: capability.why
            } else {
                buildString {
                    append(
                        when (state) {
                            Grant.GRANTED -> stringResource(R.string.settings_permission_granted)
                            Grant.DENIED -> stringResource(R.string.settings_permission_denied)
                            Grant.MISSING -> stringResource(R.string.settings_permission_missing)
                        },
                    )
                    append(" · ")
                    append(capability.note ?: capability.why)
                }
            },
            onClick = {
                // 特殊访问只能开系统页; 能点名的才弹框。`ask` 返回 false 有两种情况 —— 特殊访问,
                // 或者用户已经"拒绝且不再问", 两种都只能让它去设置页
                //
                // 三级回退在 `openSettings` 里: 它自己那页 → 应用详情页 → 都没有就返回 false。
                // 最后那一种**不再弹提示**: 一句解释性文案换不来一次跳转, 而它已经删掉了
                if (!(activity != null && PermissionGate.ask(activity, capability))) {
                    PermissionGate.openSettings(context, capability)
                }
                // 系统框是异步的, 这一下是把"点了之后可能已经变了"重算一次; 真正的答案回来时
                // PermissionRequests.pending 会变, 那时再重算一次
                revision++
            },
        )
    }
}

/** 无障碍读屏: 模型按名字操作, 而不是在缩过的截图上量坐标 */
@Composable
private fun AccessibilityItems() {
    val context = LocalContext.current
    val working = AccessibilitySetting.working
    LaunchedEffect(Unit) { AccessibilitySetting.refresh() }
    SwitchPreference(
        title = stringResource(R.string.settings_accessibility),
        summary = stringResource(R.string.settings_accessibility_summary),
        checked = AccessibilitySetting.enabled,
        enabled = !working,
        onCheckedChange = { AccessibilitySetting.set(it) },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_accessibility_manual),
        onClick = {
            context.startActivity(accessibilitySettingsIntent())
        },
    )
}

/** 系统那页, 万一特权通道写不进去还有一个能手动开的地方 */
private fun accessibilitySettingsIntent(): Intent =
    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * 通知栏的读数: 与无障碍那一段同一个形状
 *
 * 这一段还兼着一件事: **横幅 (全屏通知) 的授权也是在这一页说明的**。从 Android 14 起它不是装完就有
 * 的 —— 非闹钟/通话类的应用要人去「特殊应用权限」里手动开 —— 所以那件事的状态跟着这一段一起显示,
 * 而 `lw_notify` 也会自己说一遍它现在到底会不会弹成横幅
 */
@Composable
private fun NotificationItems() {
    val context = LocalContext.current
    val working = NotificationSetting.working
    LaunchedEffect(Unit) { NotificationSetting.refresh() }
    SwitchPreference(
        title = stringResource(R.string.settings_notifications),
        summary = stringResource(R.string.settings_notifications_summary),
        checked = NotificationSetting.enabled,
        enabled = !working,
        onCheckedChange = { NotificationSetting.set(it) },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_notifications_manual),
        onClick = {
            val packageUri = Uri.fromParts("package", context.packageName, null)
            // 那一页在个别 ROM 上没有接收者 (`startActivity` 会抛, 而这是一个点击换来的崩溃), 所以
            // 与横幅那条一样退到应用详情页
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (!opened) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
    // 横幅是另一道授权, 而且系统把它藏在「特殊应用权限」里, 从这一页直接跳过去省得找
    ArrowPreference(
        title = stringResource(R.string.settings_notifications_banner),
        onClick = {
            val packageUri = Uri.fromParts("package", context.packageName, null)
            val opened = runCatching {
                context.startActivity(
                    Intent(FULL_SCREEN_INTENT_SETTINGS, packageUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            // 那一页要 API 34 才有, 低版本或没有它的 ROM 退到应用详情页 (权限开关都在上面)
            if (!opened) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
}

/** 全屏通知 (横幅) 那一页, API 34 起才有 */
private const val FULL_SCREEN_INTENT_SETTINGS = "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"

/**
 * 视频模式取景那三条: 每张间隔、截图张数、截图清晰度
 *
 * 与上面「截图」那两条是**两笔账**: 那两条管的是 `lw_screenshot` 那条路 (整屏画面缩到多少像素),
 * 而这三条管的是视频模式那一台**自己开的相机** (见 `LwCamera` 与 `VideoLooks`): 抓几张 JPEG 帧、
 * 两张之间隔多久、抓帧按多大挑尺寸
 *
 * 三条的形状各不相同, 理由是它们要对的数字性质不一样:
 *
 * - **间隔**连着滑 (中间那些值都能用), 吸附点只是帮着停到好看的几个数上; 它说的是"墙钟上两张之间
 *   隔多久", 所以摘要里写着"这是要的那个数, 不是做到的那个数" —— 抓一张本身就要两三百毫秒
 * - **张数**是整数档 (1..12), 与 `lw_look` 那个 `frames` 参数同一个上限; 它就是"缺省"这个意思
 * - **清晰度**是三档 (与上面像素那条同一个道理): 每一档要对的是一张图交出去之后的处理预算, 中间的
 *   值没有对应的预算可言。它要**重开相机**才生效, 所以拖完立刻让相机重开一次 (`camera op=rule`) ——
 *   不然"我改了清晰度而它还是老样子"会是一个说不清的中间态
 */
@Composable
private fun LookItems() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val levels = VideoLooks.levels
    val last = levels.lastIndex
    val levelNames = levels.map { stringResource(it.first, it.second) }
    SwitchPreference(
        title = stringResource(R.string.settings_look_sheet),
        summary = stringResource(R.string.settings_look_sheet_summary),
        checked = VideoLooks.sheet,
        onCheckedChange = { VideoLooks.setSheet(context, it) },
    )
    ArrowSlider(
        title = stringResource(R.string.settings_look_interval),
        summary = stringResource(R.string.settings_look_interval_summary, VideoLooks.intervalMs),
        value = VideoLooks.intervalMs.toFloat(),
        onValueChange = { VideoLooks.setInterval(context, it.roundToInt()) },
        valueRange = VideoLooks.intervalRange,
        // 连续滑动: 吸附点只帮着停到常见的那几个数上, 中间的值照样给
        steps = 0,
        showKeyPoints = true,
        keyPoints = VideoLooks.intervalKeyPoints,
        magnetThreshold = VideoLooks.INTERVAL_MAGNET,
        unit = "ms",
        inputSummary = stringResource(
            R.string.settings_look_interval_dialog,
            VideoLooks.intervalRange.start.toInt(),
            VideoLooks.intervalRange.endInclusive.toInt(),
        ),
        inputLabel = "ms",
        inputFilter = { text -> text.filter(Char::isDigit) },
        inputValueRange = VideoLooks.intervalRange,
        onInputConfirm = { raw -> raw.toIntOrNull()?.let { VideoLooks.setInterval(context, it) } },
    )
    ArrowSlider(
        title = stringResource(R.string.settings_look_count),
        summary = stringResource(R.string.settings_look_count_summary, VideoLooks.count),
        value = VideoLooks.count.toFloat(),
        onValueChange = { VideoLooks.setCount(context, it.roundToInt()) },
        valueRange = VideoLooks.countRange,
        // 整数: 两端之间每一个数都是一个可选值 (Compose 那个 steps 的算法)
        steps = (VideoLooks.countRange.endInclusive.toInt() - 2).coerceAtLeast(0),
        showKeyPoints = false,
        inputLabel = "frames",
        inputFilter = { text -> text.filter(Char::isDigit) },
        inputValueRange = VideoLooks.countRange,
        onInputConfirm = { raw -> raw.toIntOrNull()?.let { VideoLooks.setCount(context, it) } },
    )
    ArrowSlider(
        title = stringResource(R.string.settings_look_quality),
        summary = stringResource(R.string.settings_look_quality_summary, VideoLooks.pixels),
        value = VideoLooks.level.toFloat(),
        onValueChange = { VideoLooks.setLevel(context, it.roundToInt().coerceIn(0, last)) },
        valueRange = 0f..last.toFloat(),
        steps = (levels.size - 2).coerceAtLeast(0),
        showKeyPoints = true,
        keyPoints = levels.indices.map { it.toFloat() },
        displayFormatter = { levelNames[it.roundToInt().coerceIn(0, last)] },
        inputSummary = levelNames.joinToString(" / "),
        inputLabel = "px",
        inputInitialValue = VideoLooks.pixels.toString(),
        inputFilter = { text -> text.filter(Char::isDigit) },
        inputValueRange = levels.first().second.toFloat()..levels.last().second.toFloat(),
        onInputConfirm = { raw ->
            val typed = raw.toIntOrNull() ?: 0
            VideoLooks.setLevel(context, levels.indices.minBy { abs(levels[it].second - typed) })
        },
        // 改完立刻让相机按新的一档重开: 抓帧的尺寸是开相机那一刻定死的, 不重开就要等下一次
        onValueChangeFinished = { scope.launch { LwCamera.reapply(context) } },
    )
    // 一段话说明这一半与那一半: 与上面截图那两条同一个口径 (超预算的图会在 host 那边重编码一次)
    Column(modifier = Modifier.fillMaxWidth().padding(UiSpacing.Large)) {
        Text(
            text = stringResource(R.string.settings_look_note),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 一屏画面交给模型之前要过的两条预算: 先按**像素**缩到一个尺寸, 再按**字节**缩到装得下
 *
 * 两条都用滑块而不是下拉, 但形状不一样, 因为它们要对的数字性质不一样:
 *
 * - **像素那条是三档**, 滑块就只有三个位置。那三个数分别对应 dsh 的 `low` / 缺省 / DeepSeek 那头
 *   处理图片的预算, 中间的值没有对应的预算可言, 而越过那条路由的预算会让整个模型请求失败 (设备上
 *   编不了图) —— 所以打字也只会落到最接近的一档
 * - **字节那条是连续的**, 中间那几个吸附点把 256 KiB 到 1 MiB 三等分。它是连续的是因为要对齐的数字
 *   在 `settings.yaml` 里, 那个数可以是任何一个, 而滑块只负责给常见的几个, 真要对上就点标题那一行打字
 *
 * **一句话分清楚两半账**: 这两条给的都是 **app 这一半** —— 截图在产生时缩到多少, 而路由那一半
 * (`imagePixelBudget` / `imageMaxBytes`) 在 dsh 自己的 `settings.yaml` 里, app 读不到也写不到。app 这一半
 * 高于路由那一半不但没用, 还会把注定被拒的图交出去, 所以**两条滑块的上端都停在路由缺省那个数**
 * (像素那条的"高"档是唯一的例外, 见上), 而**打字**能给的更宽 —— 那是给"路由也一起调高了"的人留的口子
 *
 * 两条都写着那句"超了会失败": 这不是吓唬, 是这台设备上真实的下场 (见 `Picture` 的注释)
 */
@Composable
private fun ScreenshotItems() {
    val context = LocalContext.current
    val levels = ScreenshotBudget.levels
    val last = levels.lastIndex
    val levelNames = levels.map { stringResource(it.first, it.second) }
    ArrowSlider(
        title = stringResource(R.string.settings_screenshot_budget),
        summary = stringResource(R.string.settings_screenshot_budget_summary),
        value = ScreenshotBudget.index.toFloat(),
        onValueChange = { ScreenshotBudget.set(context, it.roundToInt().coerceIn(0, last)) },
        valueRange = 0f..last.toFloat(),
        // 三档 = 端点之间只有一个离散点, 这是 Compose 那个 steps 的算法
        steps = (levels.size - 2).coerceAtLeast(0),
        showKeyPoints = true,
        keyPoints = levels.indices.map { it.toFloat() },
        displayFormatter = { levelNames[it.roundToInt().coerceIn(0, last)] },
        // 打字给的是像素数, 但只落到最接近的一档, 所以对话框里把那三档列出来
        inputSummary = levelNames.joinToString(" / "),
        inputLabel = "px",
        inputInitialValue = ScreenshotBudget.pixels.toString(),
        inputFilter = { text -> text.filter(Char::isDigit) },
        inputValueRange = levels.first().second.toFloat()..levels.last().second.toFloat(),
        onInputConfirm = { raw ->
            val typed = raw.toIntOrNull() ?: 0
            ScreenshotBudget.set(
                context,
                levels.indices.minBy { abs(levels[it].second - typed) },
            )
        },
    )
    ArrowSlider(
        title = stringResource(R.string.settings_screenshot_bytes),
        summary = stringResource(R.string.settings_screenshot_bytes_summary, ScreenshotBudget.bytes),
        value = ScreenshotBudget.kib,
        onValueChange = { ScreenshotBudget.setBytes(context, it.roundToInt()) },
        valueRange = ScreenshotBudget.byteRange,
        // 连续滑动: 吸附交给 keyPoints 与 magnetThreshold, 设了 steps 反而滑不到中间的值
        steps = 0,
        showKeyPoints = true,
        keyPoints = ScreenshotBudget.byteKeyPoints,
        magnetThreshold = ScreenshotBudget.BYTE_MAGNET,
        unit = "KiB",
        // 打字能给的比滑块宽: 滑块上的每个值都该是能过那条路由的, 而打字是配另一条路由用的
        inputSummary = stringResource(
            R.string.settings_screenshot_bytes_dialog,
            ScreenshotBudget.byteRange.endInclusive.toInt(),
            ScreenshotBudget.byteInputRange.endInclusive.toInt(),
        ),
        inputLabel = "KiB",
        inputFilter = { text -> text.filter(Char::isDigit) },
        inputValueRange = ScreenshotBudget.byteInputRange,
        onInputConfirm = { raw ->
            raw.toIntOrNull()?.let { ScreenshotBudget.setBytes(context, it) }
        },
    )
}

/**
 * 朗读: 音色与语速
 *
 * 这两件事与"引擎是谁、要不要下语音包"是两半账: **引擎与语音包只能去系统的「文字转语音输出」那
 * 一页换** (应用改不了别人家的引擎), 而"用这条引擎的哪个中文音色"与"多快"应用这一侧就能定 ——
 * 于是这里直接调, 那一页留一个跳转
 *
 * 音色那一串是**引擎报上来的**, 不是这一页写死的字符串, 所以名字跟着引擎走; 引擎还没起来时
 * 只显示一句"还没就绪", 不假装列了一张空表
 *
 * 语速默认**跟随系统**: 跟随的时候我们一个数都不设, 系统里调的就是生效的。拖一下滑块 = 要一个
 * 具体的数, 于是自动改成不跟随 (拖动这个动作本身就是"我要自己定")
 */
@Composable
private fun SpeakItems() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<Voice>?>(null) }
    var speaking by remember { mutableStateOf(false) }
    // 要打字的那几行 (API 四项 + Edge 的自定义音色): 一次开一个
    var editing by remember { mutableStateOf<SpeakText?>(null) }
    // 引擎初始化要几百毫秒, 不能在组合里做: 放到 IO 上, 回来了再画音色那一段
    LaunchedEffect(Unit) {
        voices = withContext(Dispatchers.IO) { LwSpeak.chineseVoices(context) }
    }
    // 关掉朗读: 只关"回答落定就自动念"那条链, 手动让模型念 (lw_speak) 与下面那个试听不受影响 ——
    // 有时就是不想要它出声, 而"要它念一句"仍然该是能做到的
    SwitchPreference(
        title = stringResource(R.string.settings_speak_auto),
        summary = stringResource(R.string.settings_speak_auto_summary),
        checked = SpeakSettings.readAloud,
        onCheckedChange = { SpeakSettings.setReadAloud(context, it) },
    )
    SwitchPreference(
        title = stringResource(R.string.settings_speak_follow),
        summary = stringResource(R.string.settings_speak_follow_summary),
        checked = SpeakSettings.followsSystem,
        onCheckedChange = { SpeakSettings.setFollowsSystem(context, it) },
    )
    ArrowSlider(
        title = stringResource(R.string.settings_speak_rate),
        summary = if (SpeakSettings.followsSystem) {
            stringResource(R.string.settings_speak_rate_following)
        } else {
            stringResource(R.string.settings_speak_rate_summary, SpeakSettings.rate)
        },
        value = SpeakSettings.rate,
        onValueChange = { SpeakSettings.setRate(context, it) },
        valueRange = SpeakSettings.range,
        // 连续滑动: 吸附点只帮着停到好看的那几个数上, 中间的值照样给
        steps = 0,
        showKeyPoints = true,
        keyPoints = SpeakSettings.keyPoints,
        magnetThreshold = SpeakSettings.MAGNET,
        displayFormatter = { "%.2f x".format(it) },
        // 打字给的是精确倍数: 耳朵认的那个数常常落在两个吸附点之间
        inputSummary = stringResource(
            R.string.settings_speak_rate_dialog,
            SpeakSettings.range.start,
            SpeakSettings.range.endInclusive,
        ),
        inputLabel = "x",
        inputInitialValue = "%.2f".format(SpeakSettings.rate),
        inputValueRange = SpeakSettings.range,
        onInputConfirm = { raw -> raw.toFloatOrNull()?.let { SpeakSettings.setRate(context, it) } },
    )

    // 引擎四条: 系统那条即时但只有它自己的音色; 自带那条能换音色 (主人自己放模型) 但慢一截;
    // Edge 那条免费、在线、不要密钥; API 那条要自己填地址与密钥
    val onDevice = SpeakSettings.engine == SpeakSettings.Engine.ON_DEVICE
    val edge = SpeakSettings.usesEdge()
    val api = SpeakSettings.engine == SpeakSettings.Engine.API
    ArrowPreference(
        title = stringResource(R.string.settings_speak_engine_system),
        summary = stringResource(
            if (!onDevice) R.string.settings_speak_voice_current else R.string.settings_speak_voice_pick,
        ),
        onClick = { SpeakSettings.setEngine(context, SpeakSettings.Engine.SYSTEM) },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_speak_engine_on_device),
        summary = when {
            onDevice && SpeakSettings.model != null -> stringResource(
                R.string.settings_speak_engine_on_device_using,
                SpeakSettings.model.orEmpty(),
            )

            onDevice -> stringResource(R.string.settings_speak_engine_on_device_pick)

            else -> stringResource(R.string.settings_speak_engine_on_device_summary)
        },
        onClick = { SpeakSettings.setEngine(context, SpeakSettings.Engine.ON_DEVICE) },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_speak_engine_edge),
        summary = if (edge) {
            stringResource(R.string.settings_speak_engine_edge_using, SpeakSettings.edgeVoice)
        } else {
            stringResource(R.string.settings_speak_engine_edge_summary)
        },
        onClick = { SpeakSettings.setEngine(context, SpeakSettings.Engine.EDGE) },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_speak_engine_api),
        summary = when {
            api && SpeakSettings.apiUrl.isNotBlank() ->
                stringResource(R.string.settings_speak_engine_api_using, SpeakSettings.apiModel)

            api -> stringResource(R.string.settings_speak_engine_api_pick)

            else -> stringResource(R.string.settings_speak_engine_api_summary)
        },
        onClick = { SpeakSettings.setEngine(context, SpeakSettings.Engine.API) },
    )

    if (onDevice) {
        // 自己放进来的音色: 目录里有什么就列什么, 用不了的连原因一起列出来
        var models by remember { mutableStateOf<List<LwTts.Voice>?>(null) }
        val rescan: () -> Unit = {
            scope.launch { models = withContext(Dispatchers.IO) { LwTts.list(context) } }
        }
        LaunchedEffect(Unit) { rescan() }
        val listed = models
        when {
            listed == null -> ArrowPreference(
                title = stringResource(R.string.settings_speak_models),
                summary = stringResource(R.string.settings_speak_models_scanning),
                onClick = rescan,
            )

            listed.isEmpty() -> ArrowPreference(
                title = stringResource(R.string.settings_speak_models),
                summary = stringResource(R.string.settings_speak_models_none, LwTts.root(context).absolutePath),
                onClick = rescan,
            )

            else -> listed.forEach { voice ->
                ArrowPreference(
                    title = voice.name,
                    summary = when {
                        !voice.usable -> voice.problem.orEmpty()
                        SpeakSettings.model == voice.name -> stringResource(R.string.settings_speak_voice_current)
                        else -> stringResource(
                            R.string.settings_speak_models_usable,
                            voice.family?.label.orEmpty(),
                        )
                    },
                    onClick = { if (voice.usable) SpeakSettings.setModel(context, voice.name) },
                )
            }
        }
        // 目录路径要显示出来: 不然没人知道该把模型拷到哪, 也没法自己核对
        ArrowPreference(
            title = stringResource(R.string.settings_speak_models_directory),
            summary = stringResource(R.string.settings_speak_models_directory_summary, LwTts.root(context).absolutePath),
            onClick = rescan,
        )
        // 音量: 放在这一段里, 因为它只对自带这条引擎有意义 —— 系统那条的增益在引擎自己手里, 应用
        // 这一侧没有 API 能碰, 所以换到上面那条引擎时这一行干脆不出现, 而不是留个拖了没反应的滑块
        //
        // 基准是**这条音色本来的电平**: 100% 一个增益都不加, 100 往上才是加。这不是拍脑袋的刻度,
        // 是 2026-10-06 在开发机上量出来的 (见 SpeakSettings 那一段注释)
        val hint = stringResource(
            when {
                SpeakSettings.volume <= 0f -> R.string.settings_speak_volume_hint_muted
                SpeakSettings.volume <= SpeakSettings.VOLUME_UNITY ->
                    R.string.settings_speak_volume_hint_unity

                else -> R.string.settings_speak_volume_hint_boosted
            },
        )
        ArrowSlider(
            title = stringResource(R.string.settings_speak_volume),
            summary = stringResource(R.string.settings_speak_volume_summary, SpeakSettings.volume.toInt(), hint),
            value = SpeakSettings.volume,
            onValueChange = { SpeakSettings.setVolume(context, it) },
            valueRange = SpeakSettings.volumeRange,
            // 连续滑动: 吸附点只帮着停到好看的那几个数上 (100% 与 300% 都在里面)
            steps = 0,
            showKeyPoints = true,
            keyPoints = SpeakSettings.volumeKeyPoints,
            magnetThreshold = SpeakSettings.MAGNET,
            displayFormatter = { "${it.toInt()}%" },
            inputSummary = stringResource(
                R.string.settings_speak_volume_dialog,
                SpeakSettings.volumeRange.start.toInt(),
                SpeakSettings.volumeRange.endInclusive.toInt(),
            ),
            inputLabel = "%",
            inputInitialValue = SpeakSettings.volume.toInt().toString(),
            inputValueRange = SpeakSettings.volumeRange,
            onInputConfirm = { raw -> raw.toFloatOrNull()?.let { SpeakSettings.setVolume(context, it) } },
        )
    } else if (edge) {
        // Edge 那条: 音色是它在线的那几个, 点一个就用; 还能自己填名字 (它那边有几百条)
        LwEdgeSpeech.VOICES.forEach { (name, label) ->
            ArrowPreference(
                title = label,
                summary = stringResource(
                    if (SpeakSettings.edgeVoice == name) R.string.settings_speak_voice_current
                    else R.string.settings_speak_voice_pick,
                ),
                onClick = { SpeakSettings.setEdgeVoice(context, name) },
            )
        }
        ArrowPreference(
            title = stringResource(R.string.settings_speak_edge_custom),
            summary = SpeakSettings.edgeVoice,
            onClick = { editing = SpeakText.EDGE_VOICE },
        )
        // 响度不在应用手里: 这一行说实话, 点它去系统声音设置 (媒体音量在那里)
        ArrowPreference(
            title = stringResource(R.string.settings_speak_volume),
            summary = stringResource(R.string.settings_speak_online_volume),
            onClick = { openSoundSettings(context) },
        )
    } else if (api) {
        // API 那条: 地址与密钥是钥匙, 模型与音色是给服务看的
        ArrowPreference(
            title = stringResource(R.string.settings_speak_api_url),
            summary = if (SpeakSettings.apiUrl.isBlank()) {
                stringResource(R.string.settings_speak_api_url_empty)
            } else {
                stringResource(R.string.settings_speak_api_url_value, SpeakSettings.apiUrl)
            },
            onClick = { editing = SpeakText.API_URL },
        )
        ArrowPreference(
            title = stringResource(R.string.settings_speak_api_key),
            summary = stringResource(
                if (SpeakSettings.apiKey.isBlank()) R.string.settings_speak_api_key_empty
                else R.string.settings_speak_api_key_set,
            ),
            onClick = { editing = SpeakText.API_KEY },
        )
        ArrowPreference(
            title = stringResource(R.string.settings_speak_api_model),
            summary = SpeakSettings.apiModel,
            onClick = { editing = SpeakText.API_MODEL },
        )
        ArrowPreference(
            title = stringResource(R.string.settings_speak_api_voice),
            summary = SpeakSettings.apiVoice,
            onClick = { editing = SpeakText.API_VOICE },
        )
        ArrowPreference(
            title = stringResource(R.string.settings_speak_volume),
            summary = stringResource(R.string.settings_speak_online_volume),
            onClick = { openSoundSettings(context) },
        )
    } else {
        // 系统那条也有音量这回事, 只是它不由应用定: 给一句实话, 而不是一个拖不动的控件
        ArrowPreference(
            title = stringResource(R.string.settings_speak_volume),
            summary = stringResource(R.string.settings_speak_volume_system),
            onClick = { openTtsSettings(context) },
        )
        val listed = voices
        when {
            listed == null -> ArrowPreference(
                title = stringResource(R.string.settings_speak_voice),
                summary = stringResource(R.string.settings_speak_voice_unknown),
                onClick = {
                    scope.launch {
                        voices = withContext(Dispatchers.IO) { LwSpeak.chineseVoices(context) }
                    }
                },
            )

            listed.isEmpty() -> ArrowPreference(
                title = stringResource(R.string.settings_speak_voice),
                summary = stringResource(R.string.settings_speak_voice_none),
                onClick = { openTtsSettings(context) },
            )

            else -> {
                // 引擎自带的那个也列出来: 选过别的之后要能回到它
                ArrowPreference(
                    title = stringResource(R.string.settings_speak_voice_default),
                    summary = stringResource(
                        if (SpeakSettings.voice == null) R.string.settings_speak_voice_current
                        else R.string.settings_speak_voice_pick,
                    ),
                    onClick = { SpeakSettings.setVoice(context, null) },
                )
                listed.forEach { voice ->
                    ArrowPreference(
                        title = voiceLabel(voice),
                        summary = stringResource(
                            if (SpeakSettings.voice == LwSpeak.voiceKey(voice)) R.string.settings_speak_voice_current
                            else R.string.settings_speak_voice_pick,
                        ),
                        onClick = { SpeakSettings.setVoice(context, LwSpeak.voiceKey(voice)) },
                    )
                }
            }
        }
    }
    // 引擎与语音包只能去系统那一页: 这里给的是跳转, 而不是一个做不到的下拉 (那两条在线引擎与
    // 那一页无关, 所以选着它们时不摆这一行)
    if (!edge && !api) {
        ArrowPreference(
            title = stringResource(R.string.settings_speak_system),
            summary = stringResource(R.string.settings_speak_system_summary),
            onClick = { openTtsSettings(context) },
        )
    }
    // 试听: 音色与语速是耳朵判断的东西, 看一眼数字没有意义 —— 而**正在念的时候这一行就是停止**,
    // 不然一段长回答只能等它念完 (两条引擎都停得下来, 见 LwSpeak.stop)
    ArrowPreference(
        title = stringResource(if (speaking) R.string.settings_speak_stop else R.string.settings_speak_preview),
        summary = stringResource(
            if (speaking) R.string.settings_speak_previewing else R.string.settings_speak_preview_summary,
        ),
        onClick = {
            if (speaking) {
                scope.launch { withContext(Dispatchers.IO) { runCatching { LwSpeak.stop() } } }
                return@ArrowPreference
            }
            speaking = true
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { LwSpeak.preview(context) } }
                speaking = false
            }
        },
    )
    editing?.let { field ->
        SpeakTextFieldDialog(
            field = field,
            onDismissRequest = { editing = null },
            onConfirm = { value ->
                when (field) {
                    SpeakText.API_URL -> SpeakSettings.setApiUrl(context, value)
                    SpeakText.API_KEY -> SpeakSettings.setApiKey(context, value)
                    SpeakText.API_MODEL -> SpeakSettings.setApiModel(context, value)
                    SpeakText.API_VOICE -> SpeakSettings.setApiVoice(context, value)
                    SpeakText.EDGE_VOICE -> SpeakSettings.setEdgeVoice(context, value)
                }
                editing = null
            },
        )
    }
}

/** 音色在列表里的样子: 名字加地区, 因为同一个引擎常有 zh-CN 与 zh-TW 两条 */
private fun voiceLabel(voice: Voice): String = "${voice.name} (${voice.locale.toLanguageTag()})"

/**
 * 浮标: 一颗球, 点一下说话
 *
 * 这一段的落点是**一颗球在不在屏幕上**, 而不是"那段代码跑没跑": 球是那块常驻的 overlay 窗, 而它的
 * 开关是存盘的 ([BallSpot.on]) —— 开关管"下一次应用起来要不要把它放出来", 服务管"它现在在不在",
 * 所以状态那一行必须说三件事: 缺权限 / 球在(停在哪边) / 球不在
 *
 * 缺权限时**不替人打开开关**: 屏幕上什么都不会出现, 而状态那一行直说缺哪一条 (与别的段同一条纪律)
 *
 * 每秒看一眼: 球的位置是拖动那一侧写进偏好的, 而权限可能在系统设置里被改掉, 不轮询界面就是死的
 */
@Composable
private fun BallItems() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(BallSpot.on(context)) }
    var permission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var showing by remember { mutableStateOf(OverlayState.showing) }
    var edge by remember { mutableStateOf(BallSpot.read(context)?.first ?: BallGeometry.EDGE_RIGHT) }
    var hostUp by remember { mutableStateOf(DshHost.status is HostStatus.Running) }
    var hideProblem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            permission = Settings.canDrawOverlays(context)
            showing = OverlayState.showing
            edge = BallSpot.read(context)?.first ?: BallGeometry.EDGE_RIGHT
            hostUp = DshHost.status is HostStatus.Running
            // **开关跟的是球的真实存在, 不是"上一次点了什么"** (2026-10-06): 菜单里、通知栏上、
            // 或者服务自己那边都能把球收掉, 那几条路都不会回来改这个页面里的记忆值 —— 只认本地那一个
            // 记号的话, 球被菜单关掉之后这个开关会一直画着"开", 而屏幕上什么都没有。
            // 判据本身在 [BallSwitch] 里 (纯函数, 见 BallSwitchTest)
            on = BallSwitch.onFor(showing = OverlayState.showing, remembered = BallSpot.on(context))
            OverlayState.hideFailed?.let { hideProblem = it }
            delay(POLL_MS)
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(UiSpacing.Large)) {
        Text(
            text = when {
                !permission -> stringResource(R.string.settings_ball_permission)
                showing -> stringResource(
                    R.string.settings_ball_state_up,
                    stringResource(
                        if (edge == BallGeometry.EDGE_LEFT) R.string.ball_dock_left else R.string.ball_dock_right,
                    ),
                )

                else -> stringResource(R.string.settings_ball_state_down)
            },
        )
        if (showing && !hostUp) {
            Text(
                text = stringResource(R.string.settings_ball_missing_host),
                modifier = Modifier.padding(top = UiSpacing.Medium),
            )
        }
        // 球没起来时把上一次的原因摊开说 (系统拒了那块窗 / 没有窗口管理器): 失败不许静默
        if (!showing) {
            OverlayState.lastError?.let { reason ->
                Text(text = reason, modifier = Modifier.padding(top = UiSpacing.Medium))
            }
        }
        // **关不掉不许假装关掉了** (2026-10-06): 那一次的毛病正是"开关画成关、而球还在屏幕上",
        // 所以服务那边读回发现窗没摘下来时, 这里要把原因说出来
        hideProblem?.let { reason ->
            Text(
                text = stringResource(R.string.settings_ball_hide_failed, reason),
                modifier = Modifier.padding(top = UiSpacing.Medium),
            )
        }
    }
    SwitchPreference(
        title = stringResource(R.string.settings_ball_on),
        summary = stringResource(R.string.settings_ball_on_summary),
        checked = on,
        onCheckedChange = { wanted ->
            on = wanted
            hideProblem = null
            LwOverlay.setOn(context, wanted)
            showing = OverlayState.showing
        },
    )
    // 拖球那条经验 (主人 2026-10-06 让写在这儿): 球贴在屏幕最左/最右时, 直接往中间拖会先经过系统的
    // **返回手势区** (屏幕左右边缘那一条竖带) —— 那一下被系统吃掉, 表现是"球没挪动, 反而退出了当前
    // 应用"。先往上拖一点, 离开那条竖带再横着挪, 就不会撞上它
    //
    // 样子按主人点的来: **灰色小字**, 而且**上面空一行** —— 紧贴着上面那张卡片时, 最后一行会被卡片的
    // 圆角与描边压住看着像被切掉; 左右缩进跟页面一致 (卡片没有水平缩进, 这一行是页面级文字)
    Spacer(modifier = Modifier.height(UiSpacing.PageItem))
    Text(
        text = stringResource(R.string.settings_ball_drag_tip),
        color = colorScheme.onSurfaceVariantActions,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = UiSpacing.PageHorizontal),
    )
}

/**
 * 唤醒词: 词表、模型与那个许可
 *
 * 三件事分三段说清, 因为它们的处置完全不同: **词表**是用户自己写的 (写错时 sherpa-onnx 会把整行
 * 静默丢掉, 所以这里逐 token 核对后才写), **模型**是那 5.3 MB 的下载 (装完才对得上符号表), 而
 * **那个开关是许可、不是"现在就常驻"**:
 *
 * - 「允许唤醒」= 允不允许这个应用听着唤醒词 (缺省开), 它只决定服务起不起来
 * - ~~「允许常驻语音」~~ = **已经删掉了** (2026-10-06): 那个许可开着时识别链一直不收, 对话会一直
 *   进行下去。现在命中 (或球上点一下) 只买一句话, 闲置 10 s 由服务自己收回来, 没有开关可给
 *
 * 改动之前这里只有一个开关, 而它直接等于常驻监听 (`checked = WakeWordState.listening`) —— 主人
 * 2026-10-05 判定的那个错就落在这一行: 打开"允许唤醒"顺手把常驻麦克风也打开了, 现在这个许可
 * **只写偏好、什么都不启动**, 运行时状态另外如实显示 (状态那一行说三件事: 唤醒词在守着哪几个词 /
 * 许可开着而服务没起 / 服务在跑而这一句的窗口也开着)
 *
 * 每秒看一眼: 下载进度、那个状态、命中数都是别处改的 (服务在另一个进程状态里跑), 不轮询界面就是
 * 死的 —— 这几条读的都是内存、两份偏好与四次 `File.length()`, 一秒一次的花销可以忽略
 */
@Composable
private fun WakeItems() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 编辑框的初值: 人存过就是那一份, **没存过就是真正的缺省那张词表** —— 不能给空
    // (空框会让人以为"没有词", 而把敲进去的那一句当成"新增"), 也不能给另一份缺省
    var words by remember {
        mutableStateOf(LwWakeWord.words(context).ifEmpty { WakeWordWords.defaultText() })
    }
    var names by remember { mutableStateOf(LwWakeWord.names(context)) }
    var allowWake by remember { mutableStateOf(LwWakeWord.allow(context)) }
    var onHit by remember { mutableStateOf(LwWakeWord.onHit(context)) }
    var vibrate by remember { mutableStateOf(LwWakeWord.vibrate(context)) }
    var listening by remember { mutableStateOf(WakeWordState.listening) }
    var resident by remember { mutableStateOf(WakeWordState.voiceActive) }
    var hits by remember { mutableStateOf(WakeWordState.hits) }
    // 省电模式那三件 (主人 2026-10-07): 手动开关 / 此刻生不生效 / 定时那一段的原文
    var powerManual by remember { mutableStateOf(LwWakeWord.powerSaveManual(context)) }
    var powerSave by remember { mutableStateOf(LwWakeWord.powerSave(context)) }
    var powerWindow by remember { mutableStateOf(LwWakeWord.powerWindowText(context)) }
    var editingWindow by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(WakeWordDownload.readyCount(context)) }
    var note by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    val total = WakeWordDownload.files.size
    val microphone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    // 这几句是给开关那些分支念的, 而 stringResource 不能进 when 的 lambda 里之后再取, 所以先取出来
    val needModel = stringResource(R.string.settings_wake_need_model)
    val micMissing = stringResource(R.string.settings_wake_mic_missing)
    LaunchedEffect(Unit) {
        WakeWordDownload.refresh(context)
        while (true) {
            allowWake = LwWakeWord.allow(context)
            onHit = LwWakeWord.onHit(context)
            vibrate = LwWakeWord.vibrate(context)
            listening = WakeWordState.listening
            resident = WakeWordState.voiceActive
            hits = WakeWordState.hits
            powerManual = LwWakeWord.powerSaveManual(context)
            powerSave = LwWakeWord.powerSave(context)
            powerWindow = LwWakeWord.powerWindowText(context)
            ready = WakeWordDownload.readyCount(context)
            names = LwWakeWord.names(context)
            delay(POLL_MS)
        }
    }
    // fillMaxWidth 是必要的: 这一段的子项是纯文字, 撑不满宽度, 而 Miuix 的 Card 是包着内容的
    Column(modifier = Modifier.fillMaxWidth().padding(UiSpacing.Large)) {
        Text(
            text = when {
                // 省电模式开着时, 下面那两句说的都不是真相 (服务活着而麦克风是关着的) —— 这一档最先说
                powerSave -> stringResource(R.string.settings_wake_state_power_save)
                // 常驻那一档只会在视频模式里出现 (识别链留着 = 常驻语音在跑)
                listening && resident -> stringResource(R.string.settings_wake_state_voice)
                listening -> stringResource(
                    R.string.settings_wake_state_listening,
                    // **念的是 `keywords.txt` 里那几个词** (真正在守的那一份), 不是偏好里那一份:
                    // 2026-10-06 主人报的"设置说明的唤醒词和真实的不一致"就是这里念错了来源
                    names.joinToString("」「").ifEmpty { WakeWordWords.displayName(WakeWordWords.DEFAULT) },
                )
                // 许可开着而服务没起: 这一句与"许可关着"必须分得开, 否则人不知道是许可没给还是没起来
                allowWake && microphone && ready == total ->
                    stringResource(R.string.settings_wake_state_allowed)
                else -> stringResource(R.string.settings_wake_state_idle)
            },
        )
        if (hits > 0) {
            Text(
                text = stringResource(R.string.settings_wake_hits, hits),
                modifier = Modifier.padding(top = UiSpacing.Medium),
            )
        }
        if (!microphone) {
            Text(text = micMissing, modifier = Modifier.padding(top = UiSpacing.Medium))
        }
        val problem = WakeWordState.lastError ?: note
        if (problem != null) {
            Text(text = problem, modifier = Modifier.padding(top = UiSpacing.Medium))
        }
    }
    // 第一个开关是**许可**, 不是"现在就常驻": 它只决定服务起不起来听唤醒词
    SwitchPreference(
        title = stringResource(R.string.settings_wake_allow),
        summary = stringResource(R.string.settings_wake_allow_summary),
        checked = allowWake,
        onCheckedChange = { wanted ->
            LwWakeWord.setAllow(context, wanted)
            allowWake = wanted
            note = if (wanted) {
                when {
                    // 缺什么先说什么, 不去替人按下那个 5 MB 的下载 (开关不该是一个下载按钮)
                    !microphone -> micMissing
                    ready < total -> needModel
                    // 起不来时把原因原样说出来 (缺模型 / 缺词表各有各的说法), 不装作打开了
                    else -> runCatching { LwWakeWord.listen(context) }.exceptionOrNull()?.let { throwable ->
                        throwable.message ?: throwable.toString()
                    }
                }
            } else {
                LwWakeWord.hush(context)
                null
            }
        },
    )
    // **第二个开关 (「允许常驻语音」) 拿掉了, 而常驻语音本身留着** (主人 2026-10-06 定的口径):
    // 那个开关能在手机模式下把识别链打开, 结果就是对话一直进行下去 —— 主人判定多余的是那两个入口
    // (它和浮标菜单里那行「一直听」), 不是这条链。现在**只有视频模式能要求它留着** (切模式那一步写
    // `modes/voice-resident.on` 那个记号), 所以这里没有开关可给, 但状态那一行照旧如实说
    //
    // **省电模式那两行是 2026-10-07 主人点名加上的** ("增加省电模式开关, 及定时开关"): 它只停唤醒词
    // 监听那一条 (麦克风整个关掉, 喊不醒), 而 host / 浮标 / 通知都留着、点球也照样能说一句 —— 所以它
    // 与上面那个许可**并列**, 不是第二个"允许常驻语音"。定时那一段由主人自己填 (23:00-07:00)
    SwitchPreference(
        title = stringResource(R.string.settings_wake_power_save),
        summary = stringResource(R.string.settings_wake_power_save_summary),
        checked = powerManual,
        onCheckedChange = { wanted ->
            powerManual = wanted
            LwWakeWord.setPowerSave(context, wanted)
            powerSave = LwWakeWord.powerSave(context)
            // 关掉省电而监听没起时, 顺手把监听拉回来 —— 许可与模型都对得上才做, 缺什么下面那两行
            // 会说清 (与「允许唤醒」那一条同一个判断次序)
            note = when {
                powerSave -> null
                !allowWake || !microphone || ready < total -> null
                WakeWordState.listening -> null
                else -> runCatching { LwWakeWord.listen(context) }.exceptionOrNull()
                    ?.let { throwable -> throwable.message ?: throwable.toString() }
            }
        },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_wake_power_window),
        summary = when {
            powerWindow.isEmpty() -> stringResource(
                R.string.settings_wake_power_window_none,
                stringResource(R.string.settings_wake_power_window_hint),
            )

            // 现在生效的是定时那一段 (手动开关关着): 说清"此刻在省电"
            LwWakeWord.powerWindowActive(context) -> stringResource(
                R.string.settings_wake_power_window_now,
                powerWindow,
            )

            else -> stringResource(R.string.settings_wake_power_window_set, powerWindow)
        },
        onClick = { editingWindow = true },
    )
    if (editingWindow) {
        PowerWindowDialog(
            initial = powerWindow,
            onDismissRequest = { editingWindow = false },
            onConfirm = { text ->
                editingWindow = false
                note = runCatching { LwWakeWord.setPowerWindow(context, text) }
                    .getOrElse { throwable -> throwable.message ?: throwable.toString() }
                powerWindow = LwWakeWord.powerWindowText(context)
                powerSave = LwWakeWord.powerSave(context)
                // 定时那一段刚关掉 (或者改成"现在不在里面") 而监听没起: 让它照许可回来
                if (!powerSave && !WakeWordState.listening && allowWake && microphone && ready == total) {
                    runCatching { LwWakeWord.listen(context) }
                }
            },
        )
    }
    // 第三个设置: **命中之后干什么** (批次 4.4)。两条"切模式"走的是与说出来一样的那条命令路 ——
    // 命令词表只有一份, 在宿主插件里 (可行性稿 2.7), 这里再写一遍就成了第二份实现
    val hitActions = listOf(LwWakeWord.HIT_WAKE, LwWakeWord.HIT_VIDEO, LwWakeWord.HIT_PHONE)
    OverlayDropdownPreference(
        title = stringResource(R.string.settings_wake_action),
        summary = stringResource(R.string.settings_wake_action_summary),
        items = listOf(
            stringResource(R.string.settings_wake_action_wake),
            stringResource(R.string.settings_wake_action_video),
            stringResource(R.string.settings_wake_action_phone),
        ),
        selectedIndex = hitActions.indexOf(onHit).coerceAtLeast(0),
        onSelectedIndexChange = { index ->
            onHit = hitActions[index.coerceIn(0, hitActions.lastIndex)]
            LwWakeWord.setHit(context, onHit, vibrate)
            LwWakeWord.refresh(context)
        },
    )
    SwitchPreference(
        title = stringResource(R.string.settings_wake_vibrate),
        summary = stringResource(R.string.settings_wake_vibrate_summary),
        checked = vibrate,
        onCheckedChange = { wanted ->
            vibrate = wanted
            LwWakeWord.setHit(context, onHit, wanted)
            LwWakeWord.refresh(context)
        },
    )
    // 下载那一条: 装完把缺省词表写上, 并**只把唤醒词起起来** —— 常驻语音那半条不跟着开
    // (改动之前这里顺手 listen() 等于把常驻也打开, 是同一个错的第二个入口)
    ArrowPreference(
        title = stringResource(R.string.settings_wake_download),
        summary = when {
            WakeWordDownload.phase == WakeWordDownload.Phase.DOWNLOADING -> stringResource(
                R.string.settings_wake_downloading,
                "%.1f MB".format(WakeWordDownload.received / 1024.0 / 1024.0),
                "%.1f MB".format(WakeWordDownload.total / 1024.0 / 1024.0),
            )

            WakeWordDownload.phase == WakeWordDownload.Phase.FAILED ->
                stringResource(R.string.settings_wake_failed, WakeWordDownload.detail)

            ready == total -> stringResource(R.string.settings_wake_model_ready, total)
            ready == 0 -> stringResource(
                R.string.settings_wake_model_missing,
                "%.1f MB".format(WakeWordDownload.files.sumOf { it.bytes } / 1024.0 / 1024.0),
            )

            else -> stringResource(R.string.settings_wake_model_partial, ready, total)
        },
        onClick = {
            // 下载中再按一下不该开第二趟: 两个线程往同一个 .part 上写就是坏文件
            if (WakeWordDownload.phase == WakeWordDownload.Phase.DOWNLOADING) return@ArrowPreference
            scope.launch {
                note = withContext(Dispatchers.IO) {
                    runCatching {
                        val said = WakeWordDownload.download(context)
                        // 模型没下过时 keywords.txt 也还没写过: 补上**缺省那张词表** (不是偏好里那一份)
                        if (LwWakeWord.names(context).isEmpty()) {
                            LwWakeWord.setWords(context, WakeWordWords.defaultText())
                        }
                        // **许可是前提**: 没允许唤醒就只把模型放好, 不替人把监听起起来
                        // (改动之前这里无条件 listen(), 那是"开关 = 常驻监听"那个错的第二个入口)
                        if (!LwWakeWord.allow(context)) {
                            "$said, 模型已就位; 上面那个「允许唤醒」开着才会开始听"
                        } else if (!WakeWordState.listening) {
                            runCatching { LwWakeWord.listen(context) }
                                .exceptionOrNull()
                                ?.let { "$said, 但是没能开始听: ${it.message ?: it}" }
                                ?: "$said, 已经开始听唤醒词"
                        } else {
                            // 换了模型文件时正在跑的那一份要重新读: 停一下再起
                            LwWakeWord.hush(context)
                            runCatching { LwWakeWord.listen(context) }
                                .exceptionOrNull()
                                ?.let { "$said, 但是没能重新开始听: ${it.message ?: it}" }
                                ?: "$said, 已重新开始听唤醒词"
                        }
                    }.getOrElse { it.message ?: it.toString() }
                }
            }
        },
    )
    ArrowPreference(
        title = stringResource(R.string.settings_wake_words),
        summary = if (names.isEmpty()) {
            stringResource(R.string.settings_wake_words_none, WakeWordWords.displayName(WakeWordWords.DEFAULT))
        } else {
            stringResource(R.string.settings_wake_words_now, names.joinToString(", "))
        },
        onClick = { editing = true },
    )
    if (editing) {
        // stringResource 不能在 runCatching 里调 (它不是组合上下文), 先把要念的两句取出来
        val savedText = stringResource(R.string.settings_wake_words_saved, "")
        val unsavedText = stringResource(R.string.settings_wake_words_unsaved, "")
        WakeWordDialog(
            initial = words,
            onDismissRequest = { editing = false },
            onConfirm = { text ->
                editing = false
                note = runCatching {
                    val saved = LwWakeWord.setWords(context, text)
                    words = text
                    names = saved
                    val said = savedText + saved.joinToString(", ")
                    // 词表换了正在跑的那一份要重新读: 停一下再起才算真的换上了
                    if (WakeWordState.listening) {
                        LwWakeWord.hush(context)
                        runCatching { LwWakeWord.listen(context) }
                            .exceptionOrNull()
                            ?.let { "$said, 但是重启监听失败: ${it.message ?: it}" }
                            ?: said
                    } else {
                        said
                    }
                }.getOrElse { throwable ->
                    unsavedText + (throwable.message ?: throwable.toString())
                }
            },
        )
    }
}

/**
 * 词表那个编辑框
 *
 * 显示的是**人写的那种格式** (`词=带音调数字的拼音`), 不是 keywords.txt 里的 token 序列 —— 后者是
 * 给 sherpa-onnx 看的, 让人编辑等于让人手算声调符号。两种写法由 [LwWakeWord.setWords] 翻译, 翻不
 * 过去时它会说清是哪一个 token 对不上
 */
@Composable
private fun WakeWordDialog(
    initial: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_wake_words),
        summary = stringResource(R.string.settings_wake_words_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        var text by rememberSaveable(initial) { mutableStateOf(initial) }
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismissRequest()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.button_confirm),
                onClick = {
                    haptic.confirm()
                    onConfirm(text)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 省电时段那个编辑框 (主人 2026-10-07: "定时开关…由用户自己定时间")
 *
 * 形状与词表那个一样: 一个文本框 + 取消/确定。**看不懂的写法不写** —— 由
 * [io.github.miuzarte.littlewhale.tool.LwWakeWord.setPowerWindow] 拒绝, 拒绝的那一句由调用方念在
 * 状态那一行上, 所以填错了这里什么都不变 (而不是存下一句谁也不认识的时间)
 */
@Composable
private fun PowerWindowDialog(
    initial: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_wake_power_window),
        summary = stringResource(R.string.settings_wake_power_window_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        var text by rememberSaveable(initial) { mutableStateOf(initial) }
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            label = stringResource(R.string.settings_wake_power_window_hint),
            // 空着时那行提示就是"该长什么样" (这一行本来就允许空 = 关掉定时)
            useLabelAsPlaceholder = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismissRequest()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.button_confirm),
                onClick = {
                    haptic.confirm()
                    onConfirm(text)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 「说话」那一段里要打字的几行: API 的四项 + Edge 的自定义音色 */
private enum class SpeakText { API_URL, API_KEY, API_MODEL, API_VOICE, EDGE_VOICE }

/**
 * 那几行的编辑框
 *
 * 标题与说明按 [field] 取; **密钥那一行用密码变换** —— 它不该明文躺在屏幕上 (存也只存在本应用的
 * 偏好文件里, 回执与日志里只报"有没有", 见 LwSpeak.status)
 */
@Composable
private fun SpeakTextFieldDialog(
    field: SpeakText,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val title = stringResource(
        when (field) {
            SpeakText.API_URL -> R.string.settings_speak_api_url
            SpeakText.API_KEY -> R.string.settings_speak_api_key
            SpeakText.API_MODEL -> R.string.settings_speak_api_model
            SpeakText.API_VOICE -> R.string.settings_speak_api_voice
            SpeakText.EDGE_VOICE -> R.string.settings_speak_edge_custom
        },
    )
    val summary = stringResource(
        when (field) {
            SpeakText.API_URL -> R.string.settings_speak_api_url_dialog
            SpeakText.API_KEY -> R.string.settings_speak_api_key_dialog
            SpeakText.API_MODEL -> R.string.settings_speak_api_model_dialog
            SpeakText.API_VOICE -> R.string.settings_speak_api_voice_dialog
            SpeakText.EDGE_VOICE -> R.string.settings_speak_edge_custom_dialog
        },
    )
    val initial = when (field) {
        SpeakText.API_URL -> SpeakSettings.apiUrl
        SpeakText.API_KEY -> SpeakSettings.apiKey
        SpeakText.API_MODEL -> SpeakSettings.apiModel
        SpeakText.API_VOICE -> SpeakSettings.apiVoice
        SpeakText.EDGE_VOICE -> SpeakSettings.edgeVoice
    }
    val haptic = LocalHapticFeedback.current
    OverlayDialog(
        show = true,
        title = title,
        summary = summary,
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        var text by rememberSaveable(initial) { mutableStateOf(initial) }
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            visualTransformation = if (field == SpeakText.API_KEY) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = {
                    haptic.contextClick()
                    onDismissRequest()
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.button_confirm),
                onClick = {
                    haptic.confirm()
                    onConfirm(text)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 设置页那一段的轮询间隔: 只喂状态文字, 不用更快 */
private const val POLL_MS = 1000L

/**
 * 系统那个「文字转语音输出」页
 *
 * 用字面值而不是 `Settings.ACTION_TTS_SETTINGS`: 与全屏通知那一页同一个理由 —— 那个常量在某些
 * 编译 SDK 上取不到, 而这一页本身是各 ROM 都有的
 */
private const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

/**
 * 跳去系统的「文字转语音输出」
 *
 * 与通知那两条同一个写法: 个别 ROM 上这一页没有接收者 (`startActivity` 会抛, 而这是点击换来的
 * 崩溃), 所以退到应用详情页
 */
private fun openTtsSettings(context: Context) {
    val opened = runCatching {
        context.startActivity(
            Intent(TTS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!opened) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 系统的「声音」页: 响度不在应用手里时那一行点它去这里 (媒体音量在那里) */
private fun openSoundSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * 端侧 OCR: 自绘界面上的字只能从像素里读
 *
 * 状态是**懒加载**来的: 模型要等第一次用到才建 session (NPU 那边第一次还要现场编译图, 约 2 秒),
 * 所以这里平时显示"未加载", 那个按钮既是自检也是预热
 */
@Composable
private fun OcrItems() {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    val backend = LwOcr.backend
    // label 与 note 来自模型那一侧 (桥也把它们交给工具), 所以它们不是这一页的字符串, 只有那一圈
    // 说明文字跟着资源走
    val unit = if (backend == LwOcr.Backend.NPU) {
        "${backend.label} (${Build.SOC_MODEL})"
    } else {
        backend.label
    }
    Column(modifier = Modifier.padding(UiSpacing.Large)) {
        Text(text = stringResource(R.string.settings_ocr_backend, unit))
        if (LwOcr.note.isNotEmpty()) {
            Text(text = LwOcr.note, modifier = Modifier.padding(top = UiSpacing.Medium))
        }
        Button(
            onClick = {
                working = true
                scope.launch {
                    withContext(Dispatchers.IO) { LwOcr.probe(3, null, false, "burst") }
                    working = false
                }
            },
            enabled = !working,
            modifier = Modifier.fillMaxWidth().padding(top = UiSpacing.Medium),
        ) {
            Text(
                text = stringResource(
                    if (working) R.string.settings_ocr_probe_busy else R.string.settings_ocr_probe,
                ),
            )
        }
    }
}
