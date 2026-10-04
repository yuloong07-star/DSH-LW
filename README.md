<!-- markdownlint-disable MD033 -->

# DSH-LW

**DSH** for Android, built on **[LittleWhale](https://github.com/Miuzarte/LittleWhale)**

本应用围绕 DSHLW 应用基于 LittleWhale 开发, 部分功能与特点取自 DSHA。

把 [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) (dsh) 装进手机的一个应用: 一台安卓设备同时当 dsh 的**主机**与**受控端**, 界面就是 dsh 自己的 Web GUI (装在 WebView 里), 原生那一半把屏幕与输入能力做成 dsh 原生工具交给模型

不需要另一台电脑, 也不需要 Termux

屏幕那套工具要 **root (KernelSU / Magisk) 或 [Shizuku](https://github.com/RikkaApps/Shizuku) 之一**, 二选一

## 特点

1. **权限与开发**: 应用安全审查度极低, 几乎不做任何审查, 并且可获得手机几乎所有权限, 用户可自行拉取权限进行手机自动化开发
2. **存储机制**: 应用没有虚拟机框架, 手机存储不受沙盒限制
3. **安全评估**: 对于安全方面, 用户需自行评估
4. **启动体验**: 打开即用, 不需要启动运行环境, 点击即进入 DSH 界面, 更方便快捷; 自带虚拟屏支持后台运行

> [!WARNING]
> **这就是上面第 1 条与第 3 条的意思**: 它把权限与屏幕能力几乎原样交给模型, 而且**审批是全部放行的** —— 模型要动主屏时应用自己在后台, 审批框没人点得到, 摆在那里只会让每次点击卡到超时。所以模型点屏幕不会问任何人, 唯一的刹车是**真人一碰屏幕就停手** (只管主屏, 只有软停: 手势中止 + 工具报错让模型收手)
>
> 装之前请自己做完安全评估, 别装在别人也要用的机器上

## 和 LittleWhale 的关系

本项目的**全部基础来自 [LittleWhale](https://github.com/Miuzarte/LittleWhale)** —— 把 dsh 移植到安卓这件事是它做的: 单进程里跑 Node 与 dsh host、Shizuku / root 特权通道、自建虚拟屏、把屏幕与输入做成 dsh 原生工具、无障碍读屏、端侧 OCR, 这些架构与实现都属于它

DSH-LW 是在它之上做的**应用发行版**, 目前相对上游多了:

- **应用名改为 DSH-LW**, 图标换成 DSH 自己那只鲸鱼 (桌面与通知栏都是)
- **常驻浮标**取代原来的顶栏菜单按钮 —— 可拖到应用内任意位置, 按住微微放大、松手回弹, 空闲 5 秒淡到 25%, 碰一下恢复
- **虚拟屏小窗** —— 屏在后台跑着, 一边看它一边用 dsh 界面; 整条标题栏就是拖动把手, 右端减号把整扇窗收掉
- **dsh 升到 0.2.0-rc.2**, 并把在安卓上缺预编译的那个原生件换成 `--expose-internals` 的回退路径
- **返回键不退出应用** —— 第一下提示「再按一次退出」, 第二下把任务放到后台; host 与虚拟屏继续跑, 从最近任务回来还是原样
- **权限与破坏性操作** —— 清单里把能声明的都声明了, 设置页有「权限」段逐条授权; 卸载 / 清数据 / 停应用 / 装包 / 停用这五条要先在屏幕上点一下确认, 没人点就不执行

## 能力

- **dsh 与它的 Web GUI 一起进 APK** —— Node 24 随包发, 前台服务常驻, 关掉界面也活着
- **工作区在你自己的存储里** —— `/sdcard/DSH/`, 文件管理器翻得到; dsh 自己的配置与凭据留在应用沙盒 (`filesDir/dsh-home`)
- **可选局域网访问** —— 打开后别的设备用浏览器就能连; 手机一套小字体、PC 一套大字体, 各存浏览器本地
- **自建虚拟屏** —— 想开几块开几块, 宽高与 dpi 随便给, 建完还能换形状 (`lw_screen_resize` / `lw_screen_rotate`); 预览是合成器直接画进 `SurfaceView` 的, 零 native, 不编码不解码
- **屏幕与输入是一套 dsh 原生工具** —— 列屏 / 建屏 / 换尺寸 / 关屏 / 点 / 拖 / 长按 / 按键 / 组合键 / 打字 / 滚动 / 启动应用 / 起 activity 或打开链接 / 截图 (可分区, 可连拍) / 读无障碍树 / 端侧 OCR / 列应用
- **主屏也看得到动得了** —— `displayId 0` 就是手机自己那块屏, 与虚拟屏走同一套调用; 一摸到真手指就当场停下并抬手
- **读屏两条路** —— 无障碍树 (文字 + 那块屏自己的坐标, 按名字点不需要坐标) 与端侧 OCR (PP-OCRv6 tiny 跑在 NPU 上)
- **截图在产生时按两条预算缩小** —— 像素与字节都在设置页调, 免得一整屏游戏画面把模型请求变成 `TRANSPORT`
- **一段过程看得到** (1.0.3) —— 截图能连拍最多 12 张、两张之间给间隔, 还能把它们拼成**一张网格**: 一次读图就看完整段, 比读十来张图省得多。间隔做不到时答案报的是**量到的**那一刻, 不是要的那个数
- **等一件事发生, 而不是反复读屏** (1.0.3) —— `lw_events_subscribe` / `lw_events_wait`: 说清在等什么 (哪块屏 / 哪类事件 / 哪个包), 等它发生或超时再回答
- **通知栏** (1.0.3) —— `lw_notifications` 读当前通知并清一条 (常驻的那条如实说留着没动); `lw_notify` 现在能要横幅, 而且会说自己到底会不会弹成横幅
- **文件、媒体与照片** (1.0.3) —— `lw_files` 在工作区里列 / 读 / 写, 每一条都带上"手机怎么看这个文件"; `lw_media_scan` 让媒体库看见一个路径; `lw_take_photo` 用系统相机拍一张
- **JPEG / WebP / GIF 现在读得进来** (1.0.3) —— 图片后端换成了真正的 sharp (wasm32), 相册里的图不再一律 `INVALID_IMAGE`
- **不只是屏幕** —— 通知与震动、剪贴板、把文件交给别的应用或下载到手机、电池与存储、音量与媒体、网络、传感器与定位、系统设置读写、多指手势与捏合、等一个控件出现。**破坏性操作 (卸载 / 清数据 / 停应用 / 装包 / 停用) 要先在屏幕上点一下确认**, 没人点就不执行
- **说话就能出字** —— 语音输入走本机的离线引擎 (sherpa-onnx + SenseVoice, 中英日韩粤), 不联网也不要密钥, 音频不出设备; 模型不在包里 (约 240 MB), 第一次用时才下载
- **念得出声** —— 语音输出走系统自带的 TTS 引擎, 同样不联网不要密钥; 页面里也能点朗读
- **输入框可以浮在别的应用上面** —— 那扇窗里装的是同一个 GUI (同 host 的第二个客户端, 会话与 cookie 共享), 所以在别的应用里也能直接跟 dsh 说话
- **手机模式预设** —— 新建会话可以选「手机模式」, 配套技能讲清了本机通道的操作纪律

## 下载

见 [Releases](../../releases)。**1.3.0** 的 APK 叫 `DSH-LW-1.3.0.apk`, 约 **204 MiB**

自带 host 树 (3.2 万个文件 / 292 MB), 装上首次启动要解压几分钟; 那几分钟别让设备息屏

## 已知问题

- **这个仓库没有自动化测试** —— 每一批改动都是在模拟器上逐条量出来的 (命令与结果写在 `docs/dshlw-1.0.3-record.md`), 真机上是 vivo V2417A / Android 16 / arm64 装过跑过。但触摸与手势这类问题**截图看不出来**, 要人点一下才算; 遇到问题请带着 `adb logcat` 说
- **侧载安装的 APK 开无障碍要额外的 app op** —— 应用会自己 best effort 处理, 但**每次重装 APK 都会把无障碍踢掉**。设置页「无障碍」段显示它现在到底怎么样 (在不在设备列表里 / 服务绑没绑 / 能不能写设置); **写不动的机器上要电脑跑 `tools/lw-install.ps1`**, 它会带 `-i` 装完立刻把条目写回去并读回校验, 没过就非零退出。**装完别再用 `am start -S` 强停** —— 它会把系统那个绑定实例摘掉而把条目留着, 现象是"设置里写着开着、服务却没绑上"
- **root 那条第一次要你手动授权** —— 应用侧查不出来有没有 root (没在名单上的 app 连 `su` 都看不见), 只能试; 而且试也不会弹框
- **主屏的刹车只有软停** —— 抬手 1 秒之后就能再动手
- **熄屏是静默失败** —— `screencap` 交的还是最后一帧, 注入的触摸唤不醒屏, 两个都不报错。现在有 `lw_power` 能点亮它
- **录屏不在这一版** —— 模型读不了视频 (它的读图工具只认 PNG / JPEG / WebP / GIF), 所以"录一段"要么是给人看的, 要么中间得先解决抽帧, 真要做得先回答"给谁看"; 现在要看"一段过程"用连拍加网格
- **第一次启动要等几分钟** —— host 树是 3.2 万个文件 / 292 MB, 首启解压, 而且别让设备息屏 (应用一进后台就被冻住, 解压跟着停在原地)
- **读剪贴板要应用在前台** —— Android 10 起只有获得焦点的应用能读, 后台读回来是空的 (写不受限)
- **清单里的权限很多** —— 1.0.2 把能声明的都声明了。其中通讯录 / 短信 / 通话记录 / 日历这一批**应用自己不去读**, 是留给手机端自己利用与开发的; 装的时候授不授权都行
- **语音转写还没在真机上验过** —— 模拟器是 x86_64 靠 ARM 翻译跑 arm64, 而 sherpa 那个原生库在翻译下会段错误 (tombstone 里能看到崩在 `libsherpa-onnx-jni.so` 自己的代码里), 所以这一条只能真机说了算
- **语音模型要下一次** —— 约 240 MB, 从 hf-mirror 拉 (手机上 huggingface.co 连不上), 两个文件都按 sha256 校验; 也可以 `adb push` 放进应用沙盒的 `speech-models/sense-voice/`
- **朗读要自己听一次** —— 回执只说"引擎报告念完了", 是否真出声得听; 中文数据不在时 `lw_speak op=status` 会直说去系统的「文字转语音」里下一个
- **浮窗有三条限制** —— 窗可获焦, 所以落在窗外的触摸不再穿透; 后台起步时 `microphone` 那半个前台服务类型可能被 ROM 拒 (拒了退 `specialUse`); 锁屏之上不碰
- **shell 那把工具没有开** —— `lw_shell` 刻意不做: 通道的 token 在手就等于 shell 身份, 这道墙是留着的

## 构建

需要 JDK 21, Android SDK (`compileSdk 37` / `build-tools 37.0.0`), NDK `29.0.14206865`, 以及 Node + pnpm

```bash
git clone --recursive https://github.com/yuloong07-star/DSH-LW.git
cd DSH-LW
./gradlew assembleDebug
```

已克隆但没有拉取子模块:

```bash
git submodule update --init --recursive
```

**两样东西不在仓库里**, 缺了它们打出来的 APK 跑不起来:

| 缺什么 | 是什么 |
| :-- | :-- |
| `app/src/main/jniLibs/arm64-v8a/` (17 个 `.so`, 109 MiB) | Node 24 / bash 5.3 / ripgrep 15.2 的 Termux bionic 构建 |
| `app/src/main/assets/ocr/` (5.9 MiB) | PP-OCRv6 tiny 的 det 与 rec, ONNX |

所以**想直接用就下 Release 里的 APK**, 不要从源码自己打

## Credits

- **[Miuzarte/LittleWhale](https://github.com/Miuzarte/LittleWhale) (Apache-2.0)** —— **本项目的基础**。把 dsh 移植到安卓的架构、特权通道、自建虚拟屏、dsh 工具、无障碍读屏与端侧 OCR 都是它的工作
- **DSHA** —— 部分功能与特点取自它
- [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) (MIT) —— 被搬过来的东西本身
- [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) —— 界面组件
- [Miuzarte/ScrcpyForAndroid](https://github.com/Miuzarte/ScrcpyForAndroid) (Apache-2.0) —— 界面视觉细节参考
- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) —— 免 root 的特权通道

## License

[Apache-2.0](LICENSE), 与上游 LittleWhale 相同
