package io.github.miuzarte.littlewhale.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.scaffolds.SuperTextField
import io.github.miuzarte.littlewhale.update.AboutSettings
import io.github.miuzarte.littlewhale.update.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * ⋮ 菜单里那三件事的界面: 检测更新 / 捐赠 / 关于 (2026-10-08, 第 4 条)
 *
 * 四个对话框排成一个"页" ([AboutPage]), 而**同一时刻只开一个** —— 由调用方 (设置页) 拿着那一个
 * [AboutPage] 变量决定画哪一个。这样从"关于"里点进"更新源"再关掉时不会叠两层窗, 也免得两个
 * `OverlayDialog` 同时挂在树上 (它们的窗口层级不是我们能摆的)
 *
 * 三条口径与开发计划 2.4 一致: **只检测不代装** (去下载交给系统浏览器), **捐赠页由主人自己填**
 * (没有缺省值, 没填就直说), **更新源可改** (缺省是 GitHub 的 Release API, 另有一条镜像)
 */
internal enum class AboutPage {
    /** 关于: 版本号 + 那三条入口 */
    ABOUT,

    /** 检测更新: 查一次, 把结果念出来 */
    UPDATE,

    /** 更新源: 改那个地址 */
    SOURCE,
}

/**
 * 「关于」: 版本号与三条入口
 *
 * 版本号那两项直接问系统 (`PackageManager`) 而不是 `BuildConfig` —— 后者要开 `buildConfig` 那个
 * 生成开关, 而这个应用本来一个地方都没用到它
 */
@Composable
internal fun AboutDialog(
    onDismissRequest: () -> Unit,
    onOpen: (AboutPage) -> Unit,
    onDonate: () -> Unit,
) {
    val context = LocalContext.current
    val version = remember { UpdateCheck.localVersionOf(context) }
    val code = remember { UpdateCheck.localCodeOf(context) }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_about_title),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.settings_about_version, version, code))
            ArrowPreference(
                title = stringResource(R.string.settings_about_update),
                summary = stringResource(R.string.settings_about_update_summary),
                onClick = { onOpen(AboutPage.UPDATE) },
            )
            ArrowPreference(
                title = stringResource(R.string.settings_about_source),
                summary = AboutSettings.source(context),
                onClick = { onOpen(AboutPage.SOURCE) },
            )
            ArrowPreference(
                title = stringResource(R.string.settings_about_donate),
                summary = stringResource(R.string.settings_menu_donate_summary),
                onClick = onDonate,
            )
        }
        DialogButtons(
            other = null,
            main = stringResource(R.string.settings_close) to onDismissRequest,
        )
    }
}

/**
 * 「检测更新」: 打开就查一次, 结果那一行直接念
 *
 * 网络那一步放在 IO 上 (这里从来不阻塞界面线程), 而**结果那一行会跟着状态重画** —— 查的时候是
 * "正在查 …", 查完了换成"有新版本"或者"没查到: 为什么"。失败时那一行说的是**每一路各自的原因**,
 * 而不是"连不上"三个字 (见 [UpdateCheck.check])
 */
@Composable
internal fun UpdateDialog(onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phase = UpdateCheck.phase
    val release = UpdateCheck.release
    val source = remember { AboutSettings.source(context) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { UpdateCheck.check(context, source) }
    }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_update_title),
        summary = stringResource(R.string.settings_update_summary),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = when (phase) {
                    UpdateCheck.Phase.IDLE,
                    UpdateCheck.Phase.CHECKING,
                    -> stringResource(R.string.settings_update_checking, source)

                    UpdateCheck.Phase.LATEST -> stringResource(
                        R.string.settings_update_latest,
                        UpdateCheck.localVersion,
                    )

                    UpdateCheck.Phase.NEWER -> stringResource(
                        R.string.settings_update_newer,
                        release?.version.orEmpty(),
                        UpdateCheck.localVersion,
                    )

                    UpdateCheck.Phase.FAILED -> stringResource(
                        R.string.settings_update_failed,
                        UpdateCheck.detail,
                    )
                },
            )
            problem?.let { reason ->
                Text(
                    text = reason,
                    color = colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // 有新版本时把它的两个数也报出来: 发布于哪天、包多大 —— 那正是"要不要现在下"的判据
            if (phase == UpdateCheck.Phase.NEWER && release != null) {
                val size = if (release.apkBytes > 0L) {
                    stringResource(
                        R.string.settings_update_apk,
                        release.apkName.orEmpty(),
                        megabytes(release.apkBytes),
                    )
                } else {
                    ""
                }
                val line = listOf(
                    release.published.takeIf { it.isNotEmpty() }
                        ?.let { stringResource(R.string.settings_update_published, it) },
                    size.takeIf { it.isNotEmpty() },
                    release.notes.takeIf { it.isNotEmpty() },
                ).filterNotNull().joinToString("\n")
                if (line.isNotEmpty()) {
                    Text(
                        text = line,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        // 第二动作只有查完之后才有: 有新版本是"去下载", 查完没新的 (或者没查到) 是"重试"
        // 这一句要在 `when` 外面先取好: 那个 `to` 右边的 lambda 不是组合上下文, 里面取不到字符串
        val noAddress = stringResource(R.string.settings_update_failed, "那条 Release 没给地址")
        val action: Pair<String, () -> Unit>? = when (phase) {
            UpdateCheck.Phase.NEWER -> stringResource(R.string.settings_update_open) to {
                // 有 APK 那一条就直连它, 否则去 Release 页面 —— 两者都没有时只报一句, 不做别的
                val target = release?.apkUrl?.takeIf { it.isNotEmpty() }
                    ?: release?.pageUrl?.takeIf { it.isNotEmpty() }
                problem = if (target == null) noAddress else openPage(context, target)
            }

            UpdateCheck.Phase.FAILED, UpdateCheck.Phase.LATEST -> stringResource(
                R.string.settings_update_retry,
            ) to {
                // "重试"就是再查一次: 状态换成正在查之后上面那一行会自己跟着变
                problem = null
                scope.launch(Dispatchers.IO) { UpdateCheck.check(context, source) }
            }

            // 正在查的那一档没有第二个动作, 只留"关闭"
            else -> null
        }
        DialogButtons(
            other = action?.let { stringResource(R.string.settings_close) to onDismissRequest },
            main = action ?: (stringResource(R.string.settings_close) to onDismissRequest),
        )
    }
}

/** 「更新源」: 那一个地址 */
@Composable
internal fun SourceDialog(onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val initial = remember { AboutSettings.source(context) }
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.settings_source_title),
        summary = stringResource(R.string.settings_source_summary, AboutSettings.DEFAULT_SOURCE),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        SuperTextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = stringResource(R.string.settings_source_title),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        DialogButtons(
            other = stringResource(R.string.button_cancel) to {
                haptic.contextClick()
                onDismissRequest()
            },
            main = stringResource(R.string.button_confirm) to {
                haptic.confirm()
                AboutSettings.setSource(context, text)
                // 地址换了, 上一次的结果就不作数了
                UpdateCheck.reset()
                onDismissRequest()
            },
        )
    }
}

/**
 * 打开一条外链: 起不来 (没有浏览器 / 地址不合法) 时回一句人话, 而不是静默什么都不发生
 *
 * 对话框里那一处把它念在窗里 (那一句看得见), 而 ⋮ 菜单里那一处**盒子已经关掉了**, 所以它把这一句
 * 交给 Toast (见 [SettingsScreen] 的捐赠那一项)
 */
internal fun openPage(context: Context, url: String): String? = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}.exceptionOrNull()?.let { "打不开 $url: ${it.message ?: it}" }

/** 底下那一对按钮: [other] 为空时只画 [main], 而且它撑满整行 */
@Composable
private fun DialogButtons(
    main: Pair<String, () -> Unit>,
    other: Pair<String, () -> Unit>?,
) {
    // 主按钮 (确定 / 打开 / 去下载 / 重试) 用主色, 次要那一个不染色 —— 与这一页别的对话框同一个排法
    if (other == null) {
        TextButton(
            text = main.first,
            onClick = { main.second() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        return
    }
    Row(horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(
            text = other.first,
            onClick = { other.second() },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(20.dp))
        TextButton(
            text = main.first,
            onClick = { main.second() },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

/** 字节数报成 MB (与唤醒词那一份同一个写法) */
private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)
