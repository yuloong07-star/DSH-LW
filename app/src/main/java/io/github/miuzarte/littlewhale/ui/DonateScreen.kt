package io.github.miuzarte.littlewhale.ui

import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import io.github.miuzarte.littlewhale.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 捐赠那一页: **随包发进来的那一张** (`assets/donate.html`)
 *
 * 为什么随包而不是开一条外链 (主人 2026-10-08 定的): 那一页在 GitHub 上只有 `/blob/` 地址能用, 而
 * GitHub 对 `.html` 一律按**源码**显示 —— 点开看到的是一屏 HTML 文本, 二维码根本不在. 想让它渲染
 * 只有开这个仓库的 GitHub Pages, 而那要多一份仓库设置; 随包这一份不依赖网络、不依赖仓库设置, 二维码
 * 在应用里当场就能扫, 于是仓库里那份 `docs/donate.html` 也跟着删了 (只留这一份源)
 *
 * 这一页是**纯静态的** (没有 `<script>`, 两张二维码是内联的 data URI), 所以这里**不开 JS**: 一张只用来
 * 看的页面没有理由拿到脚本执行权
 *
 * 「关于」里仍然留着"捐赠页地址"那一行: 填过就用你自己那条 (走系统浏览器打开), 留空就用这一页
 */
@Composable
fun DonateScreen() {
    val navigator = LocalRootNavigator.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_donate_title),
                navigationIcon = {
                    IconButton(onClick = navigator.pop) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    // 只读: 没有脚本、不许跳转、不许放大 (那一页自己带 viewport, 缩放交给它)
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    // **必须显式给 MATCH_PARENT**: Compose 只按 modifier 摆 AndroidView, 留给它的
                    // layoutParams 是 WRAP_CONTENT, 而那种状态下 WebView 把每个 viewport 单位都算成 0
                    // (这一页用 `100vh` 撑高度, 少了这一行就是一块空面板 —— 见 AGENTS 里那条坑)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // 那一页自己有渐变底, 而 WebView 默认是白的: 露出来的一圈会是白边
                    setBackgroundColor(Color.TRANSPARENT)
                    loadUrl(DONATE_ASSET_URL)
                }
            },
        )
    }
}

/** `assets/` 里那一张 (路径写死: 它就是随包发进来的那一份) */
internal const val DONATE_ASSET_URL = "file:///android_asset/donate.html"
