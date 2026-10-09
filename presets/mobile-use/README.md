# dsh-preset-mobile-use-local（本机版「手机模式」预设）

本目录是 [AcidGr/dsh-preset-mobile-use](https://github.com/AcidGr/dsh-preset-mobile-use)（MIT）设计意图的**本机（LittleWhale Android 构建）适配副本**：同样是一个让 DSH 在**后台虚拟屏静默操机**的 Agent 预设，但后端从上游的 KSU 内核模块 `vd_server@127.0.0.1:3070` 换成 **LittleWhale 原生 `lw_*` 通道**（本机无 root，3070 实测 `ECONNREFUSED`），规则正文换成本机工作区 `AGENTS.md` 的操作规范 + 技能 `android-device-control` + 主人 2026-10-04 的授权。

## 与上游的差异

| 项目 | 上游 dsh-preset-mobile-use | 本目录（本机版） |
|---|---|---|
| 后端 | KSU/APatch/Magisk 模块 `agent-mobile-use`，`vd_server@127.0.0.1:3070` | 原生 `lw_*`：`lw_probe / lw_apps / lw_screen* / lw_launch / lw_ui / lw_ocr / lw_screenshot / lw_tap / lw_swipe / lw_type / lw_key` |
| 前提 | Root + Xposed/LSPosed，Android 15/16 | 无需 root；只需在手机 App 内授予设备能力（特权通道） |
| 工具 | 自研统一工具 `mobile`（observe/click/swipe/set_value/key/wait/launch_app/list_apps/switch_mode） | 无自研插件；能力由 host 层 `littlewhale-channel` 的 `lw_*` 提供，任何预设都可见 |
| 安装 | `./install.sh` → `${DSH_HOME}/.agent-presets/mobile-use/` | 本版 DSH 已不读 `.agent-presets/`；改由 profile patch 的 preset 声明行挂载（见下） |
| 模式 | Foreground / Background / Idle 三态（含触控光标） | 虚拟屏优先、display 0 例外条款；不设三态切换 |
| 感知 | 扁平控件元素列表 + JPEG 快照双模 | `lw_ui` 优先（精确文本+坐标），`lw_ocr` 兜底，**`lw_screenshot` 作验证** |
| 授权 | 支付/验证码/隐私一律停下问用户 | 继承本机规范，并按主人 2026-10-04 授权放开：自动解锁脚本、文件移动、发送消息、账号变更 |

## 运行期载体与安装

运行期以 **profile patch** 为准，本目录只是可分享副本，两者需同步：

- 预设声明落在 `<DSH_HOME>/profiles/web/cordis.patch.yml`（`<DSH_HOME>` = `/data/data/io.github.miuzarte.littlewhale/files/dsh-home`）末尾的 `- insert:` 段，行 id `preset-mobile-use`，`config.id = mobile-use`，显示名「手机模式」，`order: 5`。
- **2026-10-09 起这一步由应用自己做**（`app/src/main/java/.../host/CustomPresets.kt`）：host spawn 之前把本目录 `cordis.patch.yml` 开头那段 `- insert:` 按行 id 追加进 profile patch（随 host 树走的那一份在 `lw-presets/mobile-use.patch.yml`），`dsh-hmr` 监听该文件，保存即 `reconcileProfilePatches` 热生效，**不必重启 dsh web**。手工装仍然可行，但先按行 id 看一眼有没有，免得重复追加。
- 也支持作为 bundle 安装（`dsh.bundle.patch` 已声明）：本机 `plugin_manager install_bundle` 被 `DSHA_NATIVE_PLUGIN_MANAGER` 策略拦截，走既定直改安装范式（拷包 → `profiles/web/node_modules` 软链 → `package.json` 的 dependencies 与 `dsh.profile.bundles`）。

## 配套技能 android-device-control

本分支同时携带该技能本体：[`skills/android-device-control/SKILL.md`](../../skills/android-device-control/SKILL.md)。预设 persona 第 1 句就要求「动手前先 skill 加载 android-device-control 技能」，那份技能指定了本机通道事实（只用 `lw_*`，没有 `/app/*`、没有 adb、没有 root 模块）、虚拟屏建屏与回收纪律、无障碍树与 OCR 的取用顺序、截图坐标换算、锁屏等待与安全红线；缺它时预设仍可用，但规则只剩 persona 里那 23 条。

安装：把 `skills/android-device-control/` 整个目录拷进 `<DSH_HOME>/skills/`（或 DSH 的 `~/.agents/skills/`），技能库会即时列出，无需重启。

## 卸载 / 回滚

还原备份 `profiles/web/cordis.patch.yml.bak-before-mobile-use`，或删掉 `- insert:` 那一段；hmr 会自动 reconcile。已开会话保留其启动时的预设版本，验证改动请新开会话。

## 验证（只读冒烟）

新建会话选「手机模式」后，按 UI 优先、截图验证的顺序跑：

1. `lw_probe`（通道状态；未授权不阻断，继续）
2. `lw_screen()`（认屏）
3. `lw_screen_create(name="smoke")`（建虚拟屏）
4. `lw_launch(displayId, "设置")`
5. `lw_ui(displayId)`（主观察：文本+坐标）
6. `lw_screenshot(displayId)`（截图验证，与树互证）
7. `lw_screen_release(displayId)`（回收）
8. 删除本次截图（`/storage/emulated/0/DSH/screenshots/`）

全程不购物、不发消息、不改账号、不结束应用。

## 已知边界

- **子插件行的必填 Config**：预设里每个插件行都要满足各自 Config 的必填项。实测踩坑：`@deepseek-ai/dsh-plan-mode` 少了非空 `config.section` 会直接抛 `PlanModeConfig needs a string section`，表现为选择器里该预设带红色「加载失败」徽章（roster 的 `broken` 诊断），且 mount 失败是终态——修好保存后 `dsh-hmr` 自动 reconcile 重新激活，不必重启。
- `lw_screen_create` 报 `root was not granted` = 设备能力未授权：如实告知主人去 App 内授予，不要改用 display 0 硬做、不要反复重建屏。
- 虚拟屏跨会话共享：不是自己建的屏不要 `lw_screen_release`；主人关闭/暂停该屏时停手询问。
- 自动解锁脚本用的 PIN 放在 `<DSH_HOME>/lock-pin.txt`（权限 600），不写进会话正文、日志与记忆。
- 本副本不实现解锁脚本本体与上游的 `mobile` 工具；主人吩咐再写。

许可：上游与本副本同为 MIT。
