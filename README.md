<!-- markdownlint-disable MD033 -->

# DSH-LW

**DSH** for Android, built on **[LittleWhale](https://github.com/Miuzarte/LittleWhale)**

把 [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) (dsh) 装进手机的一个应用: 一台安卓设备同时当 dsh 的**主机**与**受控端**, 界面就是 dsh 自己的 Web GUI (装在 WebView 里), 原生那一半把屏幕与输入能力做成 dsh 原生工具交给模型

不需要另一台电脑, 也不需要 Termux

屏幕那套工具要 **root (KernelSU / Magisk) 或 [Shizuku](https://github.com/RikkaApps/Shizuku) 之一**, 二选一

> [!WARNING]
> **审批是全部放行的, 而且这是有意的决定**: 模型要动主屏时应用自己在后台, 审批框没人点得到, 摆在那里只会让每次点击卡到超时。所以模型点屏幕不会问任何人, 唯一的刹车是**真人一碰屏幕就停手** —— 只管主屏, 而且只有软停 (手势中止 + 工具报错让模型收手), 没有硬停
>
> 这是给自愿把设备交给 agent 的人用的, 别装在别人也要用的机器上

## 和 LittleWhale 的关系

本项目的**全部基础来自 [LittleWhale](https://github.com/Miuzarte/LittleWhale)** —— 把 dsh 移植到安卓这件事是它做的: 单进程里跑 Node 与 dsh host、Shizuku / root 特权通道、自建虚拟屏、把屏幕与输入做成 dsh 原生工具、无障碍读屏、端侧 OCR, 这些架构与实现都属于它

DSH-LW 是在它之上做的**应用发行版**, 目前相对上游多了:

- **应用名改为 DSH-LW**
- **常驻浮标**取代原来的顶栏菜单按钮 —— 可拖到应用内任意位置, 按住微微放大、松手回弹, 空闲 5 秒淡到 25%, 碰一下恢复
- **虚拟屏小窗** —— 屏在后台跑着, 一边看它一边用 dsh 界面; 整条标题栏就是拖动把手, 右端减号把整扇窗收掉
- **dsh 升到 0.2.0-rc.2**, 并把在安卓上缺预编译的那个原生件换成 `--expose-internals` 的回退路径

## 能力

- **dsh 与它的 Web GUI 一起进 APK** —— Node 24 随包发, 前台服务常驻, 关掉界面也活着
- **工作区在你自己的存储里** —— `/sdcard/DSH/`, 文件管理器翻得到; dsh 自己的配置与凭据留在应用沙盒 (`filesDir/dsh-home`)
- **可选局域网访问** —— 打开后别的设备用浏览器就能连; 手机一套小字体、PC 一套大字体, 各存浏览器本地
- **自建虚拟屏** —— 想开几块开几块, 宽高与 dpi 随便给, 建完还能换形状 (`lw_screen_resize` / `lw_screen_rotate`); 预览是合成器直接画进 `SurfaceView` 的, 零 native, 不编码不解码
- **屏幕与输入是一套 dsh 原生工具** —— 列屏 / 建屏 / 换尺寸 / 关屏 / 点 / 拖 / 长按 / 按键 / 打字 / 启动应用 / 截图 / 读无障碍树 / 端侧 OCR / 列应用
- **主屏也看得到动得了** —— `displayId 0` 就是手机自己那块屏, 与虚拟屏走同一套调用; 一摸到真手指就当场停下并抬手
- **读屏两条路** —— 无障碍树 (文字 + 那块屏自己的坐标, 按名字点不需要坐标) 与端侧 OCR (PP-OCRv6 tiny 跑在 NPU 上)
- **截图在产生时按两条预算缩小** —— 像素与字节都在设置页调, 免得一整屏游戏画面把模型请求变成 `TRANSPORT`

## 下载

见 [Releases](../../releases)。APK 约 330 MB, 自带 host 树, 装上首次启动要解压几分钟

> 仓库目前是私有的, Release 也要登录才能下; 转公开之后才能直接下载

## 已知问题

- **只在模拟器上验过** —— Android 16 / arm64; 真机待验证
- **侧载安装的 APK 开无障碍要额外的 app op** —— 应用会自己 best effort 处理, 但**每次重装 APK 都会把无障碍踢掉**, 要去设置页再拨一下那个开关
- **root 那条第一次要你手动授权** —— 应用侧查不出来有没有 root (没在名单上的 app 连 `su` 都看不见), 只能试; 而且试也不会弹框
- **主屏的刹车只有软停** —— 抬手 1 秒之后就能再动手
- **熄屏是静默失败** —— `screencap` 交的还是最后一帧, 注入的触摸唤不醒屏, 两个都不报错
- **设备上没有图片编码器** —— 所以只有 PNG 且不超预算的图能进模型, JPEG 读不了
- **第一次启动要等几分钟** —— host 树是 3 万多个文件 / 334 MB, 首启解压, 而且别让设备息屏 (应用一进后台就被冻住, 解压跟着停在原地)

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
- [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) (MIT) —— 被搬过来的东西本身
- [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) —— 界面组件
- [Miuzarte/ScrcpyForAndroid](https://github.com/Miuzarte/ScrcpyForAndroid) (Apache-2.0) —— 界面视觉细节参考
- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) —— 免 root 的特权通道

## License

[Apache-2.0](LICENSE), 与上游 LittleWhale 相同
