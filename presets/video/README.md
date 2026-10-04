# dsh-preset-video（本机版「视频模式」预设）

实时识图预设：用虚拟屏打开系统相机、顶格连拍取帧，看图回答「镜头前是什么」。输出会被念出来，所以回复限几句话。

## 行为

- 识图：`lw_screen` 认屏 → `lw_screen_create` 建虚拟屏 → `lw_launch(displayId, "com.android.camera")` 打开相机 → `lw_screenshot(displayId, count=12, sheet=true, quality="high")`。
- 连拍顶格：张数最大（`count=12`）、清晰度最高（`quality=high`）、`sheet=true`；组间约 1 秒，**最多 3 组**，第 3 组仍无法确认即停手反问一句，不再重拍。
- 拍前确认前台是 `com.android.camera`；相机是单实例，已在别处运行时先 `lw_ui` 找窗口，不重复 launch。
- 说话：只回 1–3 句，先结论后细节；不提工具名与过程，不罗列可能；没听清或指向不明时直接问一句，不猜、不硬答。
- 收尾：用户说关闭虚拟屏时 → `lw_screen_release` 回收本模式建的屏（相机随之关闭）+ 清空本次全部截图（每帧 `.png` 与 `.model.png`，以及 sheet 拼图），回一句「已关闭并清空截图」。

## 运行期载体与安装

- 预设声明落在 `<DSH_HOME>/profiles/web/cordis.patch.yml`（`<DSH_HOME>` = `/data/data/io.github.miuzarte.littlewhale/files/dsh-home`），行 id `preset-video`，`config.id = video`，显示名「视频模式」，`order: 6`。
- 安装：把本目录 `cordis.patch.yml` 的内容追加到 profile patch；`dsh-hmr` 监听该文件，保存即 reconcile 热生效，不必重启 dsh web（GUI 的预设选择器刷新页面后可见）。
- 也支持作为 bundle 安装（`dsh.bundle.patch` 已声明）；本机 `plugin_manager install_bundle` 被 `DSHA_NATIVE_PLUGIN_MANAGER` 策略拦截，走既定直改安装范式。

## 与手机模式（`presets/mobile-use`）的关系

plugins 列表逐字相同（19 行），仅替换 `id / name / description / order` 与 persona。配套技能见 `skills/android-device-control/SKILL.md` 的七·五节（识物取图通用纪律：`count=4`、最多 5 组）；本预设按语音实时场景收紧为 `count=12`、最多 3 组，并在 persona 第 20 条声明冲突时以本预设为准。

## 已知边界

- 相机单实例：已在别处运行时 `lw_launch` 只把 intent 递给既有实例（回 WARNING），窗口不在本屏。
- 每次截图落两档文件：全尺寸孪生 `.png` 与给模型的 `.model.png`，删一张要两张一起删；连拍另有 sheet 拼图。
- 逐张读图比只读回执重要；到上限仍看不清就如实说看不清，不编画面。
- 虚拟屏跨会话共享：不是自己建的屏不要 `lw_screen_release`。

许可：MIT（与 `presets/mobile-use` 同）。
