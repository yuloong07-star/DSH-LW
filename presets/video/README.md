# dsh-preset-video（本机版「视频模式」预设）

实时识图预设：用**手机自己的摄像头**（Camera2 直连，相机开在应用进程里，预览画在一块悬浮小窗上）取帧，看图回答「镜头前是什么」。输出会被念出来，所以回复限几句话。

## 行为

- 识图：`lw_look(frames=4)` —— 一次调用就把 4 张帧当附件交回来，不建虚拟屏、不起相机应用、也不截屏；`lw_mode {mode:"video"}` 那一步已经把相机开好了。
- 分组递进：第一组 `frames=4`（默认），仍不确定时第二组 `frames=9`；**最多两组**、一张不多于 12，第二组还认不出来就停手反问一句，不再重拍。东西在动时用 `frames=12`。
- 屏幕口径：本预设里「屏幕」指镜头画面。屏幕那一条路（截图 / 控件树 / OCR / 点击 / 起应用）在视频模式里被工具一律拒掉，要看手机自己那块屏先切回手机模式（`lw_mode {mode:"phone"}`；识屏模式已经取消）。相机同时只许一场会话用：另一场占着时取景会被拒，让主人去球菜单或设置页点「释放视频模式」。
- 换镜头：`lw_look {lens:"front"}` 看用户自己、`{lens:"back"}`（默认）看前面；镜头是**粘的**，切过去之后下一次取景还在那一头。前后摄是两个设备，所以换一头就是一次开关（几百毫秒）。人在设备上也可以直接跑 `$DSH_HOME/modes/camera.sh front|back|status|off`（走同一条回环桥、同一次开关）；那一头设备上没有时如实说没有，不拿另一头顶上。
- 相机是**独占**的：识图期间别去开系统相机应用；真被抢走时工具会说「被占用 / 断开了」，照实转告并停下，不反复重试。
- 说话：只回 1–3 句，先结论后细节；不提工具名与过程，不罗列可能；没听清或指向不明时直接问一句，不猜、不硬答。
- 收尾：切回手机模式（`lw_mode {mode:"phone"}`）**自己收工** —— 摄像头还给系统、预览小窗收掉、这一趟抓的帧文件删掉，回一句「相机关了」。截图那条路已经不用了：不用再删 `/storage/emulated/0/DSH/screenshots/`，也不用 `lw_screen_release`。

## 运行期载体与安装

- 预设声明落在 `<DSH_HOME>/profiles/web/cordis.patch.yml`（`<DSH_HOME>` = `/data/data/io.github.miuzarte.littlewhale/files/dsh-home`），行 id `preset-video`，`config.id = video`，显示名「视频模式」，`order: 6`。
- **2026-10-09 起这一步由应用自己做**（`app/src/main/java/.../host/CustomPresets.kt`）：host spawn 之前把本目录 `cordis.patch.yml` 那段 `- insert:` 按行 id 追加进 profile patch（随 host 树走的那一份在 `lw-presets/video.patch.yml`）；`dsh-hmr` 监听该文件，保存即 reconcile 热生效，不必重启 dsh web（GUI 的预设选择器刷新页面后可见）。手工装仍然可行，但先按行 id 看一眼有没有。
- 也支持作为 bundle 安装（`dsh.bundle.patch` 已声明）；本机 `plugin_manager install_bundle` 被 `DSHA_NATIVE_PLUGIN_MANAGER` 策略拦截，走既定直改安装范式。

## 与手机模式（`presets/mobile-use`）的关系

plugins 列表逐字相同（19 行），仅替换 `id / name / description / order` 与 persona。取景收敛为第一组 4 张、第二组 9 张、最多两组，并在 persona 第 20 条声明与技能 `android-device-control` 冲突时以本预设为准。

## 已知边界

- 相机单实例：这条链开着的时候系统相机打不开（相机是独占的），反过来也一样；切回手机模式会把它还回去。
- 抓下来的帧落在工作区的 `photos/`（`cam-<时间戳>-<序号>.jpg`），与截图那条路（`screenshots/`）不是一处；这一趟抓的帧在收工时由 `close clean` 删掉。
- 逐张读图比只读回执重要；到上限仍看不清就如实说看不清，不编画面。
- 开相机失败会如实报原因（没给相机权限 / 应用在后台而安卓不许后台用相机 / 相机被别的应用占着 / 没有这个方向的摄像头）：那是真话，不要当成"再看一次就有了"。

许可：MIT（与 `presets/mobile-use` 同）。
