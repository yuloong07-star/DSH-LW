package io.github.yuloong07star.luwi.scaffolds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import io.github.yuloong07star.luwi.constants.UiSpacing
import io.github.yuloong07star.luwi.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 段标题加一句摘要 (2026-10-08, 设置页排版那一条): "取景"、"OCR" 这种段名对新用户来说什么都不说明
 *
 * **字号与缩进照着 [SectionSmallTitle] 那一份来**: 段名用 `subtitle` (14sp, 与 Miuix 的 `SmallTitle`
 * 用的同一个样式), 缩进也是 16dp —— 这样"有摘要的段"与"没摘要的段"在同一条竖线上
 *
 * 摘要是**同一行**跟在后面而不是另起一行: 它的作用是"补一句", 不是第二层标题; 另起一行会把每一段
 * 都抬高十来 dp, 一页十几段就是两屏
 *
 * **整行可点**, 点它就是收起 / 展开这一段 (主人 2026-10-08: "把各设置版块设置为可折叠的"): 右侧那个
 * 折角跟着换方向, 而收起来的那几段只留下标题那一行 —— 一页十几段的设置页因此可以压成一张目录
 */
@Composable
fun SectionTitle(
    title: String,
    summary: String? = null,
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                // 没有 [onToggle] 时它只是一行文字 (别的页面也能用它, 不必自带折叠)
                if (onToggle == null) Modifier else Modifier.clickable { onToggle() },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        // 摘要换行时折角居中对着那两行 (顶部对齐会看着像"飘在上面")
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.subtitle,
            color = colorScheme.onBackgroundVariant,
        )
        if (!summary.isNullOrEmpty()) {
            Text(
                text = summary,
                style = MiuixTheme.textStyles.footnote1,
                color = colorScheme.onSurfaceVariantActions,
                // 摘要占了整行的时候它会自己换行, 而折角要留在最后一次换行的那一行右边 —— 所以
                // 摘要是 `weight(1f)` 的那一个, 折角跟在它后面 (weight 会把剩下的宽度全吃掉)
                modifier = Modifier.padding(start = UiSpacing.Medium).weight(1f),
            )
        } else if (onToggle != null) {
            // 没有摘要也要把折角推到最右边 (空一个撑开的盒子)
            Box(modifier = Modifier.weight(1f))
        }
        if (onToggle != null) {
            Icon(
                // 一颗"朝右"的折角转 90 度当"朝下 / 朝上"用 (Miuix 那套图标里能选的就是
                // ChevronForward / ChevronBackward / ExpandLess / ExpandMore 四个, 而后两个在
                // 2026-10-08 的模拟器上量出来是"两个断开的角"那样一颗认不出的图)
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = stringResource(
                    if (collapsed) R.string.settings_section_expand else R.string.settings_section_collapse,
                ),
                // **尺寸要自己给**: 不给的话这颗图标只拿到"剩下多少画多少"的约束, 而那两个折角就是
                // 这么被裁出来的 (2026-10-08 在模拟器上量到: 一颗 24 dp 的图标只画出两个角)
                modifier = Modifier
                    .padding(start = UiSpacing.Medium)
                    .size(20.dp)
                    .rotate(if (collapsed) 90f else -90f),
            )
        }
    }
}

/**
 * 一级分组标题: 上面那一层 ("常用" / "能力")
 *
 * 比段标题大一档 (`title3` = 20sp, 段名是 14sp), 颜色用正文字色而段名用次级色 —— 于是"分组 > 段"
 * 这一层关系不用边框也不用色块就看得出。它自己带一段上方留白, 让上一组与这一组之间空得比"段与段"
 * 更明显
 */
@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.title3,
        color = colorScheme.onBackground,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = UiSpacing.Large, bottom = 4.dp),
    )
}
