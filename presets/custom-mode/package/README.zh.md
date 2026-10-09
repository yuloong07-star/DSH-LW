# dsh-custom-mode

[English](README.md) | 中文

**DeepSeek Harness（dsh）自定义模式插件** —— 在设置页编辑系统提示词、选择基础模式、逐行开关插件，可并存多个
助手（多模式 / 多角色扮演 RP）。英文关键词：dsh custom mode / custom prompt / system prompt editor /
base mode / plugin switches / multi-assistant / multi-persona。

![dsh-custom-mode —— 给 dsh 模式用的设置页](https://raw.githubusercontent.com/BOWLUNA/dsh-custom-mode/main/docs/images/header.png)

[![Star this repo](https://img.shields.io/badge/Star-this%20repo-1f2430?style=flat-square&logo=github&logoColor=white&labelColor=1f2430)](https://github.com/BOWLUNA/dsh-custom-mode/stargazers) [![npm](https://img.shields.io/npm/v/dsh-custom-mode?label=npm&style=flat-square&logo=npm&logoColor=white&labelColor=1f2430)](https://www.npmjs.com/package/dsh-custom-mode) [![CI](https://img.shields.io/github/actions/workflow/status/BOWLUNA/dsh-custom-mode/test.yml?label=CI&style=flat-square&logo=githubactions&logoColor=white&labelColor=1f2430)](https://github.com/BOWLUNA/dsh-custom-mode/actions/workflows/test.yml) [![license](https://img.shields.io/badge/license-MIT-97ca00?style=flat-square&logo=opensourceinitiative&logoColor=white&labelColor=1f2430)](LICENSE) [![dsh](https://img.shields.io/badge/dsh-%E2%89%A50.1.5--rc.2-4d6bfe?style=flat-square&logo=deepseek&logoColor=white&labelColor=1f2430)](https://github.com/BOWLUNA/dsh-custom-mode#readme)

[![bilibili](https://img.shields.io/badge/bilibili-视频-%2300A1D6?style=flat-square&logo=bilibili&logoColor=white&labelColor=1f2430)](https://b23.tv/qJ4Ev0W) [![抖音](https://img.shields.io/badge/抖音-短视频-%23FE2C55?style=flat-square&logo=tiktok&logoColor=white&labelColor=1f2430)](https://v.douyin.com/VWh0M03Fa4Y/) [![小红书](https://img.shields.io/badge/小红书-笔记-%23FF2442?style=flat-square&logo=xiaohongshu&logoColor=white&labelColor=1f2430)](https://xhslink.cn/o/A7QtXmePBBF) [![Discord](https://img.shields.io/badge/Discord-群组-%235865F2?style=flat-square&logo=discord&logoColor=white&labelColor=1f2430)](https://discord.gg/pz97SfAfSy) [![GitHub](https://img.shields.io/github/discussions/BOWLUNA/dsh-custom-mode?label=GitHub&style=flat-square&logo=github&logoColor=white&labelColor=1f2430)](https://github.com/BOWLUNA/dsh-custom-mode/discussions)

[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（dsh）用的自定义模式。它的系统提示词是一个普通文件，可以在 Web 设置页里编辑，**改完下一步模型调用即生效** —— 不用重启，也不用新建会话。

一句话：**给 dsh 的 agent 模式用的设置页** —— 选这个模式的基础组成、逐行开关它挂载的插件、并编辑它的系统提示词；提示词每个模型调用前重新读取。可以并存多个模式（「助手」），各自的提示词互不影响。

常见的叫法：自定义模式 · 自定义提示词 · 系统提示词编辑 · 多助手／多模式 · 多个 Agent 模式 ·
角色扮演／聊天人格（RP）。

**单元测试覆盖三条线，渲染闸门只跑 `0.2.0-rc.2`。** npm 的 `latest` 与 `next` 自 2026-09-29 起都指向 `0.2.0-rc.2`：

| dsh | 定位 | 状态 |
| --- | --- | --- |
| `0.2.0-rc.2` | **最新线** —— npm 的 `latest` 与 `next` 都指向它，官方桌面端也在这一条线上（与 dsh 同版本号） | ✅ CI（ubuntu node 20/24 + **windows**）+ 真机：这条线上安装、组合树与播种都验过（整道渲染闸门跑在这里）|
| `0.2.1-alpha.1` | 预览线 —— npm 的 `alpha` 指向它。单元测试覆盖；渲染闸门不在这条线上 | ✅ CI |
| `0.1.7-rc.2` | 上一个正式版 —— 仍在声明范围内，现役安装大多在它上面 | ✅ CI。设置页在这条线上会退回壳没提供的控件；渲染闸门不在这里 |

更早的线（`0.1.5-rc.3`、`0.1.6-alpha.*`）共用同一套机制，peer 范围仍然接纳，但不再单独占 CI 腿。

官方四个模式（`standard` / `ptc` / `minimal` / `cordis`）不受影响。

<p align="center">
<img src="https://raw.githubusercontent.com/BOWLUNA/dsh-custom-mode/main/docs/images/01-mode-switch.png" width="360" alt="基础模式与插件开关">
<img src="https://raw.githubusercontent.com/BOWLUNA/dsh-custom-mode/main/docs/images/02-plugin-switches.png" width="360" alt="插件开关"><br>
<img src="https://raw.githubusercontent.com/BOWLUNA/dsh-custom-mode/main/docs/images/03-system-prompt.png" width="360" alt="系统提示词">
<img src="https://raw.githubusercontent.com/BOWLUNA/dsh-custom-mode/main/docs/images/04-preset-picker.png" width="360" alt="模式选择器">
</p>

<p align="center">
<img src="https://raw.githubusercontent.com/BOWLUNA/dsh-custom-mode/main/docs/images/05-assistant-manager.png" width="360" alt="助手">
</p>

上面四张是一组 2×2。助手管理器单独居中。五张都是英文，文件都是 800×800，用一个没有会话记录的一次性 `DSH_HOME` 拍出。

## 安装

一条命令装完——设置页插件，以及它在首次激活时自动播种的 preset：

```sh
dsh plugin --profile web add dsh-custom-mode@2.0.1   # 钉版本才能确定拿到这一版
# 不带版本号会受 pnpm 的发布冷却期影响（`minimumReleaseAge`，默认一天）：发布后数小时内按名安装
# 可能**静默装到旧版** —— 实测 1.3.0 发布 38 分钟后按名安装装到了 1.0.3。用 profile 里的
# `npm ls dsh-custom-mode` 核对实际装到的版本，或像上面那样钉版本。
```

装完重启 dsh，新建会话时选「自定义模式」。preset 会被写到 `$DSH_HOME/.agent-presets/custom/`；
**那里已有的文件一律不动**，所以你自己写过的 `prompt.md` 不会被覆盖。

**平台**：测试套件每次 push 都在 Ubuntu（Node 20 与 24）**和 Windows（Node 24）**上跑 —— 加 Windows
任务是因为那个平台有自己的失败模式（`install.sh` 里的 MSYS 路径、并发保存时 `rename` 的目标锁、平台表达式
求值方向相反）。两个 shell 脚本在任何 POSIX shell 下都能用，含 Git Bash。

### 图形界面安装（不用终端）

dsh `0.1.6-alpha.2` 起有插件管理页：**侧边栏 → 插件 → 添加插件**。它接受三种输入，本插件对应如下：

| 输入 | 填什么 | 说明 |
| --- | --- | --- |
| **包名** | `dsh-custom-mode` | 受 pnpm 发布冷却期影响，可能装到旧版。要这一版请钉 `@2.0.1` |
| **GitHub 仓库地址** | `https://github.com/BOWLUNA/dsh-custom-mode` | 指向**仓库根**即可 |
| **本地插件目录** | `<你 clone 的路径>` | 就是仓库根 —— 仓库根**本身**就是发布包 |

想逛整个生态还有一个**应用内市场**：装 `dshmarket`（`dsh plugin --profile web add dshmarket`），
打开 **设置 → 插件市场**，搜 `dsh-custom-mode` —— 卡片会读本插件声明的 `engines.dsh` 范围，
以及仓库里 `screenshots.json` 列出的精选截图（五张；它们就是 README 用的那几张
`docs/images/*.png`，所以每张图在仓库里只有一份）。

三条都可用，因为**仓库根的 `package.json` 就是发布包**：`dsh.bundle` / `main` /
`exports["./client"]` 都声明在仓库根，所以 npm 安装与"GitHub 地址"安装拿到的是同一批文件。
1.10.0 之前这个仓库有**两份清单**——根部的 `private: true` 包装清单加 `editor/package.json`——
那正是第三方目录把本插件显示成 `dsh-custom-mode#editor`、以及 dshfind 这类站点读到包装清单的
`private: true` 后报"作者尚未发布到 npm"的原因。现在只有根清单一份，`test/manifests.test.mjs` 盯着它不被加回来。

### 桌面端

桌面端就在 dsh 仓库里（`apps/desktop`），**与 dsh 同版本号发布**（Electron 与 `@deepseek-ai/dsh`
永远同一个精确版本），而且它就是**套了 Electron 壳的完整 Web 应用** —— 所以本插件的页面在那边原样渲染。
安装只有一条路，就是上面那条：

- **在应用里装**：侧栏 → **插件** → 添加插件 → 搜 `dsh-custom-mode`。应用自带 pnpm，走的是同一套插件管理器。
- **不要试着用独立的 CLI 装 —— dsh 自己就会拒绝**（0.1.7-rc.2 实测，0.2.0-rc.2 复测同样）：
  ```
  $ dsh plugin --profile desktop add .
  error: profile "desktop" is managed exclusively by the Electron application
  ```
  桌面端独占 `$DSH_HOME/profiles/desktop`：那里的包操作持有 profile 事务锁，启动恢复还会重命名
  `cordis.patch.yml`。所以 `install.sh` 也**拒绝** `--profile desktop` 并指向应用内安装 —— 两边一致，
  而不是某一方偷偷绕过去。（只想在本机模拟 profile 形状时可设 `DSH_ALLOW_DESKTOP_PROFILE=1`。）
  **应用自带的捆绑 CLI 是例外**：`<安装目录>\resources\runtime\cli\bin\dsh.cmd` 可以操作那个 profile
  （1.11.1 实测时，命令写的版本和装上的版本对不上，这次记录作废）。它是启动器而不是 dsh 的
  新功能 —— 它用 `ELECTRON_RUN_AS_NODE=1` 起 Electron 去跑 desktop-host 的 CLI，所以那是**应用在跟自己的
  profile 说话**，不是从外面驱动它。
- **预设那一侧不用做任何事**：`$DSH_HOME/.agent-presets/` 是桌面端与 CLI 共享的产品数据，
  预设由插件首次激活时落盘。
- **模式没出现在选择器里，就去「设置 → 自定义模式」读那条告警。** 行的启动条件在本机不满足时，平台会把整个
  预设判为 broken 并**从所有选择器里丢掉**；设置页会点名那几行，并给出**「按本线修复」**。在桌面端那条线上，
  这通常是一次点击 + **重启应用** —— 选择器只在应用启动时重读注册表（1.11.1 实测，见 `docs/MEASUREMENTS.md` §34）。
- 万一某个第三方 bundle 让应用起不来：原生恢复对话框提供**「禁用第三方插件」**，已安装的包与插件数据都留在磁盘上。

**兼容性是声明出来的，不是猜的。** dsh 会拿每个插件的 `peerDependencies["@deepseek-ai/dsh"]` 与运行时
版本比对（含预发布版）；而且自 0.2.0 起这项检查从"警告"升级成了**安装闸门**：范围不覆盖运行时就
直接装不进去（`installation rejected: Plugin … is incompatible with dsh 0.2.0-rc.2`）。所以那个范围
**就是**支持声明本身，只有在真实安装并测过新线之后才会动——"大概能用"的范围正是会变成支持工单的那种声明。

想同时留下源码（或者不用 npm 安装），就 clone 下来跑脚本，它把同样两件事显式做一遍：

```sh
git clone https://github.com/BOWLUNA/dsh-custom-mode
cd dsh-custom-mode
./install.sh            # 复制 preset/ 到 $DSH_HOME/.agent-presets/custom/，并安装插件
./uninstall.sh          # 卸载插件；默认保留你的提示词，加 --purge 一并删除
```

模式需要带 `agent-presets` 的 profile：`web` 有，`tui` 与 `headless` 没有。

### 不装插件也能先试

每个 release 都带一个 **`dsh-custom-mode.dshpreset`**
（[最新版](https://github.com/BOWLUNA/dsh-custom-mode/releases/latest/download/dsh-custom-mode.dshpreset)）。
它是一个 zip，里面是一个**开箱可用的助手**：`manifest.json` 加 `preset/` —— 组成文件、`prompt.md`、
每次模型调用前重读它的读取器，以及一个**模型可见的工具**（读写这份提示词）。在桌面端导入它，
**不用装任何包**就能得到一个可用的「自定义模式」：

| | |
| --- | --- |
| **你得到** | 一个助手，它的系统提示词就是一份普通文件。用任何编辑器改 —— agent loop 每次模型调用前重读，所以下一步就生效，不用重启。也可以**直接让模型改**：预设里带的就是这个工具。 |
| **你没有** | 设置页。那是插件提供的东西 —— 页面本身，以及**多个助手并存**、新建会话时从菜单里挑一个。 |

⇒ 预设是「30 秒看看这是什么」，插件是「长期用它」。导入预设与之后装插件**不冲突**：
插件把自己的预设写在 `$DSH_HOME/.agent-presets/` 下，且**从不覆盖**你自己写的 `prompt.md`。

## 使用

设置页（设置 → 「自定义模式」）是一个**助手管理器**：上面列出你所有的自定义模式，可以新增、切换、删除，下面四个区块（模式名称 / 基础模式 / 插件开关 / 系统提示词）编辑的是**当前选中的那一个**。

- **排序** —— 选中助手后「上移 / 下移」，顺序写在每个助手的 `preset.yml` 里（`order`，也就是 roster 自己的排序键），所以重启后仍然生效，新建会话的选择器按这个顺序排列。
- **导入 / 导出提示词** —— 「导出提示词」把当前文本存成 `.md`；「导入提示词」把文件读进**编辑器**（不会直接落盘，仍需点保存），因此导入同样要过 `{{…}}` 校验。
- **让 agent 改自己的提示词要经过你批准** —— 会话内的 `custom_prompt` 工具走平台的审批缝
  （`tools/pre-execute` 返回 `ask`）：请求会停在「等待审批」，面板里写明**要写什么、写到哪**，你点「允许一次」
  才会执行。实测：审批策略为 `ask` 时弹面板且批准后真的写入；为 `never`（完全权限）时不弹窗、**直接拒绝**。
  所以最坏情况是"改不成"，从不是"悄悄改成了"。
- **「配置了却不生效」会被点名** —— 设置页会指出那些不生效的配置：「身份（系统提示词）」这一行关着而
  `prompt.md` 还有内容（你写的提示词被静默忽略）、`custom_prompt` 工具行关着、助手没有名字或描述
  （选择器里会显示成裸 id 或「暂无描述」）。
- **agent 能改自己的提示词，但要你批准** —— 会话内的 `custom_prompt` 工具可以读、整体替换，或者**追加**。
  追加的意义在于**不必把整段提示词重新写一遍**：想记住一条规则时，不可能因为重写而丢掉已有内容
  （实测：模型可能仍会先读一次，看看自己要加到什么内容后面）。两种写入动作都会先过平台的审批面板。
- **改动历史** —— 每次保存，以及**在设置页之外**发生的任何改动（会话内的 `custom_prompt` 工具、手工编辑 `prompt.md`），都会在提示词框下面留下一版，并标明时间与来源。载入某一版**只改草稿**：不点保存不会落盘，所以翻旧版本不会毁掉当前这版。在此之前，提示词被会话内改掉是**看不见的** —— 页面永远只显示"当前文本"。
- **恢复出厂提示词** —— 一键把**出厂模板**（新建助手时得到的那份文本）填回编辑器。它和页面上其它改动一样只改**草稿**：不点保存不会落盘，「重新读取」即可撤销。在此之前，把提示词改坏了只能删掉助手重建。
- **新增助手** —— 填个名字点「新增助手」。新助手从模板生成：标准模式的全部行 + 一份默认提示词，写好后在新建会话的选择器里就能选到它 —— **已经打开的页面里，选择器的列表是加载时的快照，要看到新助手需刷新一次（F5）**（实测；roster 本身已是最新）。
- **复制一份** —— 把当前助手的提示词、基础模式、逐行开关整个复制到新助手，之后各改各的。
- **每个助手各自独立** —— 提示词、基础模式、逐行开关都属于它自己，改一个不影响别的。
- **基础模式是"行集合"，不是提示词** —— 底子决定有哪些行、这个模式有哪些工具；本插件始终把底子的
  `persona` 行替换成自己的读取器（`complete: false`），所以底子的**提示词语义不会被继承**。最明显的是
  「极简模式」：你拿到的是极简的工具集，不是极简的提示词。
- **第五个基础模式：自定义模式（全部出厂行的并集）** —— 前四个是 dsh 出厂的模式，第五个是**本插件自己合成**的。
  它的行集合是那四个模式的并集；同一行在多个模式里都有时，取**先出现的那个模式**的原文与出厂默认
  （顺序：标准 → PTC → 极简 → 创造），所以出厂状态以标准模式为准。
  为什么需要它：开关只能作用在**基础文本里已经有的行**上，于是选「标准模式」时，只有 PTC 才有的
  `tool-presentation`、只有极简才有的持久终端组、只有创造模式才有的 `tool-cordis` 是**拨不到的**
  —— 不是界面藏起来了，是合成器里根本没有那条通路。0.2.0-rc.2 上实测：标准 32 行、PTC 33 行、创造 33 行、
  极简 7 行，**并集 40 行**。取并集时分组整体搬运，`isolate` realm 与 `!!js` 平台条件原样保留，
  因此不会凭空造出一个会让 `dsh-agent-presets` 拒绝挂载的行。
- **两套壳不能同时开 —— 插件替你避让。** 并集里有一组互斥的行：极简的「持久终端壳」和标准的
  `tool-bash`/`tool-pwsh` 是同一件东西的两种实现，都注册一个叫 `bash` 的工具。同时启用会让
  `dsh-agent-presets` 把整个模式判为 **broken**，而 broken 的模式会被**从所有选择器里静默丢掉** ——
  设置页却全绿。所以当选「自定义模式」时，打开其中一侧会自动关掉另一侧，并在保存提示里说明动了哪一行。
  页面之外写进去的冲突组合（手工编辑、别的工具）会在告警里被点名。
- **切换助手不会丢草稿** —— 每个助手各自留着未保存的修改，列表上用「未保存」标出来；唯一会放弃修改的是「放弃修改并重新读取」（有草稿时按钮会改名说明）。
- **删除** —— 先确认，再由插件自己动手删。确认形态取决于这条线：壳把 `RiskConfirmation` 交给第三方客户端插件时，是壳自己的弹窗（需勾选）；而 `0.1.7-rc.2` 的客户端种子表只暴露 `Button/Input/Switch/Tag/Pill`，于是退化为原生 `confirm`（护栏不变，少一个勾选框）。删除两条线都能用：`0.1.6` 及更早有 `agentPresets.remove()`，`0.1.7`+ 根本没有这个调用 —— 那里由插件自己注销注册表项并删掉目录。删除只移除磁盘上的模式目录：**正在使用它的会话不受影响**（组成在会话创建时就已读取），新建会话时不再出现。
- **直接对 agent 说** —— 每个助手都自带 `custom_prompt` 工具，会话里可以读取或改写**它自己**的提示词。
- **直接改文件** —— `$DSH_HOME/.agent-presets/<助手 id>/prompt.md` 是那个助手的唯一事实来源。

插值变量只有 `{{model}}`、`{{cwd}}`、`{{provider}}`。出现未知的 `{{…}}` 会在保存时被拒绝：渲染器对它抛错，那会让该模式每个请求都失败。

界面控件（按钮、输入框、开关、标签、确认弹窗、图标）全部来自壳自己的
`@deepseek-ai/dsh-client-ui-primitives`，因此主题、深浅色与后续改版都会自动作用到本页；老壳没有提供这组
原子组件时会退回内置的朴素控件，功能不变。

「助手」= 用户预设根目录（默认 `$DSH_HOME/.agent-presets/`）下的**一个目录**，目录名就是它的内部 id。设置页只管理**本工具创建的模式**（判据：目录里有 `prompt.md`，且组成文件用 `prompt-reader.mjs` 注入身份）。手工编写的其它 preset 不会被列进来，更不会被改写 —— 页面会按基础模式重新生成组成文件，对一份手写的组成文件做这件事等于毁掉它。

### 与官方「插件管理」页的分工

dsh `0.1.6-alpha.2` 起自带了插件管理页，可以实时启用/停用插件。它和本模式的逐行开关**管的是不同层级**：

| | 官方插件管理页 | 本模式的逐行开关 |
| --- | --- | --- |
| 作用范围 | **这套 profile**：这台机器装了哪些插件 | **这一个 agent 模式**：它的组成文件里挂哪些行 |
| 典型用法 | 全局关掉某个插件 | 让标准模式全开，而这个模式只留必要行 |

两者并存，不冲突：官方决定「这台机器有什么」，本模式决定「这个模式用其中的哪些」。

而且界限是**官方划的**：插件管理页的说明写着它管理「本 profile 的 bundle 与其中可寻址的行」，并明确
**agent-preset 行保持只读**。也就是说**官方管不到 preset 这一层**——本模式补的正是这一块。

一个可以当场演示的例子：`tool-plugin-manager`（模型侧的插件安装/启停工具）在**标准模式与 PTC 模式里
出厂就是关闭的**（只有创造模式默认打开）。官方模式不给你改它的入口，而在这里**拨一下就能打开**。

## 工作原理

dsh 的系统提示词通常来自 preset 的 YAML，而官方 `@deepseek-ai/dsh-persona` 的 `prefix` 只在挂载时解析一次。本 preset 注册同名的 `deployment:persona-prefix` section，但把 `text` 写成**函数**，agent loop 在每次模型调用前都会调它。

因此两件事的生效时机不同：

- **提示词文本**每步重新读取，所以改动对**正在运行**的会话立即生效。
- **开关与基础模式**会重写组成文件。`agent-presets` 在该文件的 `mtimeMs` 与 `size` 变化时重新挂载，所以由**新会话**生效；已开着的会话保持它启动时的配置 —— 这是刻意的，中途换工具集会出问题。

行开关是三态。没碰过的行与出厂行逐字节相同，包括 `!!js` 平台条件与出厂 `disabled`；显式开或关才会把那个条件替换成布尔值。平台表达式在宿主端求值，所以页面显示的是这台机器上实际生效的状态，而不是"有没有这个键"。

多助手不需要机制上的新能力：`dsh-agent-presets` 本来就会扫描用户预设根目录下的**每一个**目录，而且每次读 roster 都重新扫盘，所以一个刚建的目录在下一次选会话时就可见。每个助手的 `prompt-reader.mjs` / `prompt-tool.mjs` 都是按**自己模块位置**解析 `prompt.md` 的，N 份拷贝等于 N 套互不干扰的提示词。新增用包内模板播种；删除在有 `agentPresets.remove()` 的线上交给平台（它会拒绝删出厂 preset，并再确认目录确实在可写根目录下），在 `0.1.7`+ 上由本插件自己注销注册表项并删目录。

## 版本

**支持三条线：最新正式线（`0.2.0-rc.2`，npm 的 `latest` 与 `next` 自 2026-09-29 起都指向它，也是桌面端
所在的那条线）、最新预览线（`0.2.1-alpha.1`，npm 的 `alpha` 指向它）与上一个正式版（`0.1.7-rc.2`，
仍在声明范围内，现役安装大多在它上面）** —— 声明为
`>=0.1.5-rc.2 <0.2.0-0 || >=0.1.6-alpha.1 <0.2.0-0 || >=0.1.7-alpha.1 <0.2.0-0 || >=0.2.0-0 <0.3.0-0 || >=0.2.1-alpha.1 <0.3.0-0`。
官方一键安装读的是 `peerDependencies`，并且**预发布版参与比较**（`dsh-app-boot` 里的 `semver.satisfies(version, range, { includePrerelease: true })`）。因此当前稳定线 `0.2.0-rc.2` 与预览线 `0.2.1-alpha.1` 都能装上。范围里仍点名预发布版本，是因为 npm / pnpm 的默认比较更严，插件市场走那套语义时也不会把预览线拒掉。CI 对这三条线各装一次并跑完全部测试。渲染闸门只跑 `0.2.0-rc.2`，壳的界面改动先落在这条线上。更早的 `0.1.5` / `0.1.6` 仍在 peer 范围内，不再单独占一条 CI。
**官方桌面端（DeepSeek Harness Desktop）也在覆盖范围内**：它与 dsh 同版本号，当前即为 `0.2.0-rc.2`，
正是这条矩阵钉住的组合；在应用内（侧栏 → Plugins）安装即可，CLI 那条路 dsh 自己会拒。
`0.2.0-rc.2` 实测（2026-09-30，干净的一次性 `DSH_HOME`）：`dsh plugin --profile web add dsh-custom-mode`
640ms 解析成功、组合树里有本插件的行、启动时五个预设文件全部播种、声明式注册表同步出助手，
且 `agentPresets` 能力面不变（`list, register, inventory, select, document`）。

包版本走**自己的线** —— `1.0.0`、`1.0.1` …… 它不镜像 DSH 的版本号。本插件支持哪些 dsh，由
根 `package.json` 的 `engines.dsh` 与 `@deepseek-ai/dsh` peer 范围声明，并由
`tools/verify-version-consistency.mjs`（CI 里执行）断言"CI 实际安装并测试的 dsh 版本落在这些范围内"。

拆开有两个原因。一是目录与市场要求裸 `x.y.z` 才自动安装 —— 有的会解析 npm `latest` 并拒绝任何带
预发布标签的版本；二是版本号字符串本来就不是一个可校验的声明，声明式的范围才是，而且它才是官方改动时
会过期的那一个。实际决定兼容性的仍然是下面这些 API 是否还在 —— 那些范围就是为它们写的。

<details>
<summary>耦合点清单（升级 dsh 时逐个核对）</summary>

| 依赖 | 变化后的后果 |
| --- | --- |
| `ctx.systemPrompt.section()` 且 `text` 支持**函数** | 提示词不再热更新 —— 整个项目的立足点 |
| `agentPresets` 依据组成文件的 `mtimeMs`+`size` 重挂载 | 开关要重启进程才生效 |
| `ctx.tools.register()` | 失去 `custom_prompt` 工具 |
| `ctx.connection.fetch.register({ path, methods, requestBody, fetch })` | 设置页 404 —— 什么都没注册 |
| `kind: 'prefix'` 同时匹配 `path` 与 `path/…` | 只有列表能打开，`/state`、`/create`、`/delete` 全部 404 |
| `agentPresets.list()` 行里有 `id` / `trust` / `path`，`preset.yml` 提供 `name` / `description` | 助手列表为空或认不出助手 |
| `agentPresets.remove(id)`（仅旧线），且拒绝 `trust: 'system'` | 0.1.7+ 的删除不再需要它 —— 声明式后端自己注销注册表项并删目录 |
| `ctx.connection.requestRejection(req)` | 设置页失败关闭（503），不再提供服务 |
| `ctx.inject(deps, cb)`（作用域化等待） | 在没有 web 服务器的 profile 里，整行会停在 `pending` |
| `dsh.client` + `exports["./client"]`，且客户端 bundle id 等于包名 | 浏览器半不会被发现 |
| `settings.section` 插槽（`id` / `order` / `label`） | 设置项位置与标签 |
| **`settings.section` 不再提供 `locale:`**（0.1.6-alpha.2 起） | 壳不会递进绑定到本命名空间的 `t`；页面自带词典兜底，见 ARCHITECTURE §15 |
| `preset.yml` 的 `order` 参与 roster 排序 | 「上移 / 下移」不生效 |
| `ctx.locale.register/bind` | 回退中文 |
| **出厂组成从哪来** —— 0.1.7+ 是 `agentPresets.readDocument(<mode>).content`，更早是 `@deepseek-ai/dsh-agent-presets` 的文件 | 基础模式与插件开关变为只读（提示词仍可保存），见 `base-composition.mjs` |
| 出厂布局 `<presets>/<id>/agent.cordis.yml` 与行的文本形状 | 基础模式切换失效 |
| `!!js` 平台表达式 | 平台行显示错误状态 |

</details>

## 文档

- [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.zh.md) —— 在真机上复现过的失败，含症状、原因与自救方法。
- [`docs/MEASUREMENTS.md`](docs/MEASUREMENTS.zh.md) —— 每条结论背后的命令与原始输出。
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.zh.md) —— 为什么必须是两个产物，以及依赖了哪些宿主 API。
- [`docs/PUBLISHING.md`](docs/PUBLISHING.zh.md) —— npm 包的发布方式。
- [`AGENTS.md`](AGENTS.md) —— 给 agent 的工作说明，含「实验机上做真浏览器验证」的完整配方（`tools/browser-verify.mjs`）。
- [`CHANGELOG.md`](CHANGELOG.zh.md) · [`SECURITY.md`](SECURITY.zh.md) · [`CONTRIBUTING.zh.md`](CONTRIBUTING.zh.md)

## 开发

```sh
node test/run.mjs        # 15 个套件；自己解析出厂 preset 目录（0.1.7+ 从宿主声明派生）
```

改 `client.js` 会被 `@deepseek-ai/dsh-client-hmr` 在约 1 秒后热替换；改宿主半（`index.mjs`、`composition.mjs`、`meta.mjs`、`paths.mjs`）需要重启。每个套件在防什么见 [`test/README.md`](test/README.zh.md)，改行为之前先读 [`CONTRIBUTING.zh.md`](CONTRIBUTING.zh.md)。

## 如果它有用

在 GitHub 上点个 ⭐，是让一个插件在几千个条目的目录里被看见的唯一办法 —— 它不花你什么，却正是这个项目
还在被维护的理由。如果它在你的 dsh 线上**不好用**，带上 `dsh --version` 提个 issue：那是最快的修法，
上面那张兼容表就是上一次这么来的。

## License

MIT

## 更新

插件装在 profile 的 `node_modules` 里，安装由平台负责，所以"更新"就是**再装一次** —— 你的数据不会被碰：

```sh
# 钉版本的写法：要哪版就是哪版
dsh plugin --profile web add dsh-custom-mode@2.0.1
# 然后重启为该 profile 提供服务的 DSH 进程
```

**不要按裸名安装，也不要用 `@latest`。** pnpm 有发布冷却期（`minimumReleaseAge`，默认 24 小时）。
`dsh plugin add dsh-custom-mode` 会解析成**超过 24 小时的最新版**。实测：npm 上 `latest` 已是发布 6 分钟的 1.12.2 时，`@latest` 装成了 1.11.6。请钉精确版本。

每次钉精确版本，pnpm 都会在 profile 的 `pnpm-workspace.yaml` 里追加一条 `minimumReleaseAgeExclude`。pnpm 11.7.0 只认第一条命中包名的记录（[pnpm#12463](https://github.com/pnpm/pnpm/issues/12463)）。这个包只留一条 `dsh-custom-mode`，不要留一串版本号。本插件不改这个文件。若安装报 `ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION`，把列表收成那一条再装一次：`node_modules` 里可能已经是新版本，而 `package.json` 仍写着旧版本。

**怎样算升级成功**：重启 dsh，再看本设置页底部的版本号。

**更新不会碰的东西**：`$DSH_HOME/.agent-presets/<你的助手>/` —— `prompt.md`、`preset.yml` 与你逐行拨过的开关都属于你。
播种只补**缺失**的文件，你写过的提示词永远不会被覆盖。

**如果基础模式与插件开关变灰、并带一行提示**：这条 dsh 线没有向本插件交出出厂组成（解析器依次试过宿主的
`readDocument()`、旧线的 presets 包、打包的 `dsh-web-app` patch，宿主日志里会写清每一次尝试）。系统提示词
仍可单独保存，模式本身不受影响。0.1.7 上这在 1.9.13 之前是一个硬 500。

**如果更新后某个模式不再出现在选择器里**：老版本创建的助手会保留它自己的组成文件；如果那份文件启用了一行本机
这条 dsh 线不提供的插件，平台会把整个预设判为 broken 并从选择器里静默丢弃（设置页照常能开）。打开设置页：
它会明确告诉你，并提供**「按本线修复」** —— 只关掉那几行，其它选择一字不动。
