# 随仓库发的插件包

这一层放**签好名、可以直接装的那几份 `.lwp`** (源在 `samples/*/plugin/`, 由 `tools/lw-plugin-sign.mjs`
签出来)。放这里的理由只有一个: 它们需要一个**稳定的 https 链接** —— 协议第 13 节那条"从链接安装"
要的就是一个能下到字节的地址, 而仓库里的文件天然有 (raw / jsDelivr / Release 都指向同一份)。

## 怎么装

Luwi → 设置 → 插件 → **从链接装入**, 把下面那一条粘进去。

| 包 | 它是什么 | 链接 |
| :-- | :-- | :-- |
| `whale-widget-1.1.0.lwp` | 鲸鱼娘桌面小组件 (需要先装同版本的伴侣 APK) | `https://cdn.jsdelivr.net/gh/yuloong07-star/Luwi@main/plugins/whale-widget-1.1.0.lwp` |

**从链接装不比从本地装少验一样东西**: 下到 app 私有目录之后走的是同一条链 —— 验发布者签名与逐文件
哈希、查 protocol / `minLw` / 工具前缀冲突 / 未知能力, 全过才落地; 而**装完还要在设置页勾能力、
再启用**, 光装上什么都不做。

## 一条纪律

**私钥不进仓库** (它在开发机 `backups/` 里)。这一层只放签好的结果; 想重签一份就照
`samples/whale-widget/plugin/README.md` 里那三条命令走一遍。
