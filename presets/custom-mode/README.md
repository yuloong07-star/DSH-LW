# dsh-custom-mode（内置的 vendored 副本）

本目录是 [BOWLUNA/dsh-custom-mode](https://github.com/BOWLUNA/dsh-custom-mode) **v2.0.1**（MIT）的
仓库内副本，目的只有一个：**让新装的手机开箱就有那个 `custom` 预设** —— 本应用的「手机模式 / 视频
模式」是写进 `$DSH_HOME/.agent-presets/custom/prompt.md` 的，而把它注册进 dsh 预设注册表的正是这个
插件；少了它，新机上 `custom` 预设根本不存在，语音开新会话就会失败（2026-10-09 主人报的那条）。

## 两份东西

| 路径 | 是什么 |
| :-- | :-- |
| `package/` | 上游 npm 包的原样副本（`index.mjs` / `client.js` / `preset-backend/` / `preset/` …），`tools/pack-host.mjs` 把它拷进 host 树的 `node_modules/dsh-custom-mode` |
| 本文件 | 只讲"这一份从哪来、怎么进设备"，不改上游任何一行 |

上游那个包**没有任何外部依赖**（`package.json` 里只有可选的 peer `@deepseek-ai/dsh`），也没有
`postinstall` 之类的安装脚本，所以 vendored 下来是自洽的：拷进树里就能 import。

## 它怎么进设备（全自动，见 `app/src/main/java/.../host/CustomPresets.kt`）

首启与每次 host 起来之前，应用那一侧按 2026-10-05 手工装通的那三步把它落位：

1. 耐久副本 `files/plugins/dsh-custom-mode`（host 树换版本会被整个重解压，所以树外要留一份）
2. `profiles/node_modules/dsh-custom-mode` → host 树里那一份（符号链接；建不出来就退回拷贝）
3. `profiles/web/package.json` 的 `dependencies` 与 `dsh.profile.bundles` 各加一行（改写前留
   `package.json.bak-before-custom-mode`）

顺手还会把 `.agent-presets/custom/` 那五个文件补齐（缺什么补什么，**已有的 `prompt.md` 一个字节都
不动** —— 那是主人自己的人设），以及把 `presets/mobile-use` 与 `presets/video` 两段 `- insert:`
声明追加进 profile patch。

## 升级

换版本就是换 `package/` 这一整份（上游 `package.json` 的 `version` 跟着变），然后重新出包；应用那一侧
只认"包在不在、bundle 登记有没有"，不钉版本号。

许可：MIT（与上游同，`package/LICENSE` 就是那一份）。
