# 唤醒词（本机离线）

「喊一声素云，把 dsh 叫起来」这条路的落点。这份文档记的是它由哪几块拼成、模型与关键词的
格式为什么是那样、以及哪些地方还没有被真机验证过。

分支：`feat/wake-word`（从 `feat/overlay` 上开出——那一支已经有 sherpa-onnx 的 AAR、语音输入
与浮窗）。**这一支没有经过编译**，原因见第七节。

## 一、四块分工

| 在哪 | 做什么 | 文件 |
|---|---|---|
| 宿主（app 里那个 Node） | 取模型、写词表、把「开始听」交给 app | `host-plugin/index.mjs` 里 `lw_wakeword` 与 `WAKEWORD_*` 那一段 |
| app 进程（Kotlin） | `KeywordSpotter` 建识别器、读麦克风、命中之后震动/通知/唤起 | `wake/WakeWordService.kt`、`wake/WakeWordModel.kt` |
| 通道方法 | 把上面那些暴露成 `wakeword` 一件事 | `tool/LwWakeWord.kt` + `channel/PrivilegedBridge.kt` 一行 |
| 清单 | 前台服务与它的类型 | `AndroidManifest.xml` 里 `.wake.WakeWordService` |

识别引擎与语音输入是**同一份 sherpa-onnx AAR**（`app/build.gradle.kts` 里那个取 AAR 的任务）：
语音输入用 `OfflineRecognizer`（SenseVoice），这里用 `KeywordSpotter`（zipformer KWS）。所以
这一支不新增依赖，只多一个模型目录。

## 二、模型

中文的 zipformer KWS，3.3M 参数，放在 `filesDir/wake-word/kws-zipformer-wenetspeech-3.3M/`：

| 文件 | 字节 | sha256（本机实测） |
|---|---|---|
| `encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx` | 4807159 | `017af32f…c834fb` |
| `decoder-epoch-12-avg-2-chunk-16-left-64.onnx` | 675349 | `bb3d8640…d0339f` |
| `joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx` | 65208 | `431de10b…2c9c3d` |
| `tokens.txt` | 1627 | `72316508…499cf2e` |

- 来源是 ModelScope 上 `pkufool/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01`，按文件取、
  逐个对 sha256（`lw_wakeword op=prepare`）。
- 上游在 GitHub Release 的 `kws-models` 标签下也有一份：`…-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2`
  15 292 881 字节，sha256 `b812a043…de2b`。**两个来源的 onnx 不是同样的字节**（量化方式不同），
  所以哈希只对各自的来源成立——想换用 GitHub 那份 mobile 版（更小、更省 CPU），把文件放进同一
  目录、并在 `lw_wakeword` 里传 `model=<目录>` 即可；app 那一侧是按前缀找文件、`.int8.` 优先。
- 模型不进 APK，也不进仓库。
- 这台手机连不上 `huggingface.co`，但 `github.com`（含 Release 资源）、`modelscope.cn`、
  `repo1.maven.org` 都通（2026-10-04 实测）。

## 三、关键词为什么要写成 token

sherpa-onnx 的 keywords 文件每一行是**模型的 token 序列**加一个 `@显示名`，例如模型自带的那份：

```
n ǐ h ǎo j ūn g ē @你好军哥
x iǎo ài t óng x ué @小爱同学
```

中文原文写进去**不会报错**，只是那一行被静默丢掉（`EncodeKeywords` 只认 `tokens.txt` 里的符号，
一个不认就整行作废）。那等于「照做了，但永远不触发」，所以这一支在写词表之前先对一遍符号表：

- 宿主侧把「词=拼音」拆成声母 + 带调韵母，逐个核对 `tokens.txt`；
- app 侧收到词表时再核一遍（`unknownTokens`），对不上就整批不写，并把对不上的 token 报出来。

「素云」那一行是：

```
s ù y ún @素云
```

`s`（`tokens.txt` 第 27 行）、`ù`（第 9 行）、`y`（第 11 行）、`ún`（第 109 行）都在符号表里。
这个拆法不是猜的：拿同样的函数跑模型自带的例子，逐字节对上了上游那几行——
`你好军哥 → n ǐ h ǎo j ūn g ē`、`小爱同学 → x iǎo ài t óng x ué`、`周望军 → zh ōu w àng j ūn`、
`女儿 → n ǚ ér`。

用法（`op=keywords`）：

```
lw_wakeword op=keywords words=["素云=su4 yun2","小爱同学=xiao3 ai4 tong2 xue2"]
```

拼音写声调数字（`su4`）或直接写带调号的（`sù`）都行；声调落在哪个元音上按普通话的规则
（a/o/e 优先，`iu` 落 u、`ui` 落 i，其余落最后一个元音）。

## 四、听着的时候是什么样

- 一个前台服务（`foregroundServiceType="microphone|specialUse"`），16 kHz 单声道，
  每 100 ms 一段送进识别器，`isReady` 为真就解码，命中即复位并处理。
- 通知栏留一条常驻，文字随状态走，上面一个「停止」。**一直开着的麦克风必须有一眼看得见、
  一下就关得掉的地方**，这是这一条通知存在的理由。
- 命中之后三件事：震动（默认 200 ms）、更新通知、唤起——`onWake=app` 把应用提到前面，
  `onWake=overlay` 把浮窗叫起来（浮窗那一套见 `docs/floating-input.md`）。
- 参数：`threshold`（默认 0.25，越低越容易触发、误报越多）、`score`（默认 1.5）、
  `vibrateMs`（默认 200，0 就是纯静默）。

## 五、判它成了没有

```
lw_wakeword op=status     # 模型在不在、词表是什么、权限、监听状态、命中几次、上一次听到什么
lw_wakeword op=prepare    # 取模型 + 写缺省词表
lw_wakeword op=start      # 开始听
lw_wakeword op=stop       # 停止
```

`op=status` 的 `hits` / `lastKeyword` / `lastHitAt` 就是"有没有听到"的证据；通知栏那条常驻
也能一眼看出服务在不在跑。

## 六、已知边界与风险

- **两字短词会误触发**。「素云」与宿云、苏云、速运这一类的音很像，误报率必须真机实测后调
  `threshold`/`score`。三到四字的词（如「小爱同学」）稳得多。
- **后台起麦克风前台服务是受限的**（Android 14 起）：带 `microphone` 类型从后台启动需要豁免，
  持有 `SYSTEM_ALERT_WINDOW` 是官方豁免之一，但不是所有 ROM 都认。被拒时这一支退到
  `specialUse` 并把这件事记在 `status.microphoneForeground` 上——**前台时照常，退到后台可能
  就听不到了**。稳妥的用法是打开应用时点一次 `op=start`。
- **电与热**：模型常驻内存、麦克风一直开，会持续吃 CPU。想省一点就用 `op=stop`。
- **静默失败**：中文原文、错音的 token 会整行被丢，所以词表写入前必须过符号表这一关
  （已经在做了）。
- 平台侧的限制（Android 16）没有试过：如果系统对长时间麦克风前台服务有更严的处置，`status`
  里的 `lastError` 会给出线索。

## 七、没有验证过的部分

- **这份 Kotlin 没有编译过**：本机没有 JDK 与 Android SDK，改动无法本地出包。用到的 API 是逐个
  对着上游 `sherpa-onnx/kotlin-api/KeywordSpotter.kt`、`OnlineStream.kt`、`FeatureConfig.kt` 与
  官方样例 `android/SherpaOnnxKws` 核对的（`KeywordSpotterConfig` 的七个字段、`createStream`、
  `isReady`/`decode`/`getResult`/`reset`、`acceptWaveform(FloatArray, Int)`）。
- **识别效果没有在真机上跑过**：模型能触发与否、阈值多少合适，要等在构建机上出一次包。
- 出包要在构建机上做（要能取到 sherpa-onnx 的 AAR），并且必须**用同一个 debug keystore 覆盖
  安装**，否则会连 `files/dsh-home` 里的会话与记忆一起丢掉。

## 八、这一分支改了什么

```
app/src/main/java/io/github/miuzarte/littlewhale/wake/WakeWordService.kt   新增: 前台服务、麦克风循环、命中后的三件事
app/src/main/java/io/github/miuzarte/littlewhale/wake/WakeWordModel.kt     新增: 按前缀找模型、符号表核对
app/src/main/java/io/github/miuzarte/littlewhale/tool/LwWakeWord.kt        新增: 通道方法 wakeword 的四个动作
app/src/main/java/io/github/miuzarte/littlewhale/channel/PrivilegedBridge.kt  一行: "wakeword" 进方法表
app/src/main/AndroidManifest.xml                                           一个前台服务声明
host-plugin/index.mjs                                                      lw_wakeword 工具、模型清单与拼音转 token
docs/wake-word.md                                                          本文
```
