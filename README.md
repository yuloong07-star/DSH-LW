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

- **应用名改为 DSH-LW**, 图标换成 DSH 自己那只鲸鱼 (桌面与通知栏都是)
- **常驻浮标**取代原来的顶栏菜单按钮 —— 可拖到应用内任意位置, 按住微微放大、松手回弹, 空闲 5 秒淡到 25%, 碰一下恢复
- **虚拟屏小窗** —— 屏在后台跑着, 一边看它一边用 dsh 界面; 整条标题栏就是拖动把手, 右端减号把整扇窗收掉
- **dsh 升到 0.2.0-rc.2**, 并把在安卓上缺预编译的那个原生件换成 `--expose-internals` 的回退路径
- **返回键不退出应用** —— 第一下提示「再按一次退出」, 第二下把任务放到后台; host 与虚拟屏继续跑, 从最近任务回来还是原样
- **权限与破坏性操作** —— 清单里把能声明的都声明了, 设置页有「权限」段逐条授权; 卸载 / 清数据 / 停应用 / 装包这四条要先在屏幕上点一下确认

## 能力

- **dsh 与它的 Web GUI 一起进 APK** —— Node 24 随包发, 前台服务常驻, 关掉界面也活着
- **工作区在你自己的存储里** —— `/sdcard/DSH/`, 文件管理器翻得到; dsh 自己的配置与凭据留在应用沙盒 (`filesDir/dsh-home`)
- **可选局域网访问** —— 打开后别的设备用浏览器就能连; 手机一套小字体、PC 一套大字体, 各存浏览器本地
- **自建虚拟屏** —— 想开几块开几块, 宽高与 dpi 随便给, 建完还能换形状 (`lw_screen_resize` / `lw_screen_rotate`); 预览是合成器直接画进 `SurfaceView` 的, 零 native, 不编码不解码
- **屏幕与输入是一套 dsh 原生工具** —— 列屏 / 建屏 / 换尺寸 / 关屏 / 点 / 拖 / 长按 / 按键 / 打字 / 启动应用 / 截图 / 读无障碍树 / 端侧 OCR / 列应用
- **主屏也看得到动得了** —— `displayId 0` 就是手机自己那块屏, 与虚拟屏走同一套调用; 一摸到真手指就当场停下并抬手
- **读屏两条路** —— 无障碍树 (文字 + 那块屏自己的坐标, 按名字点不需要坐标) 与端侧 OCR (PP-OCRv6 tiny 跑在 NPU 上)
- **截图在产生时按两条预算缩小** —— 像素与字节都在设置页调, 免得一整屏游戏画面把模型请求变成 `TRANSPORT`
- **不只是屏幕** —— 通知与震动、剪贴板、把文件交给别的应用或下载到手机、电池与存储、音量与媒体、网络、传感器与定位、系统设置读写、多指手势与捏合、等一个控件出现。**破坏性操作 (卸载 / 清数据 / 停应用 / 装包) 要先在屏幕上点一下确认**, 没人点就不执行

## 下载

见 [Releases](../../releases)。APK 约 350 MB, 自带 host 树 (3.3 万多个文件 / 334 MB), 装上首次启动要解压几分钟; 那几分钟别让设备息屏

## 已知问题

- **交互那条线只在模拟器上验过** —— Android 16 / arm64 (x86_64 镜像带 ARM 翻译, 跑得起来)。真机只验到"装得上、跑得起来", 触摸与手势这类问题截图看不出来, 要人点一下才算

- **侧载安装的 APK 开无障碍要额外的 app op** —— 应用会自己 best effort 处理, 但**每次重装 APK 都会把无障碍踢掉**。设置页「无障碍」段会显示它现在到底怎么样 (在不在设备列表里 / 服务绑没绑 / 能不能写设置); **写不动的机器上要电脑跑 `tools/lw-install.ps1`**, 它会带 `-i` 装完立刻把条目写回去并读回校验, 没过就非零退出
- **root 那条第一次要你手动授权** —— 应用侧查不出来有没有 root (没在名单上的 app 连 `su` 都看不见), 只能试; 而且试也不会弹框
- **主屏的刹车只有软停** —— 抬手 1 秒之后就能再动手
- **熄屏是静默失败** —— `screencap` 交的还是最后一帧, 注入的触摸唤不醒屏, 两个都不报错。现在有 `lw_power` 能点亮它
- **设备上没有图片编码器** —— 所以只有 PNG 且不超预算的图能进模型, JPEG 读不了
- **第一次启动要等几分钟** —— host 树是 3 万多个文件 / 334 MB, 首启解压, 而且别让设备息屏 (应用一进后台就被冻住, 解压跟着停在原地)
- **读剪贴板要应用在前台** —— Android 10 起只有获得焦点的应用能读, 后台读回来是空的 (写不受限)
- **清单里的权限很多** —— 1.0.2 把能声明的都声明了, 其中通讯录 / 短信 / 通话记录 / 日历这一批**这一版没有功能用**, 装的时候可以不授权
- **组合键、录屏与通知栏读取不在这一版** —— 留给下一版

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
