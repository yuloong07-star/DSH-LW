# 唤醒词 (本机离线)

「喊一声大肥鱼大肥鱼, 把 dsh 叫起来」这条路的落点。这份文档记的是它由哪几块拼成, 模型与关键词的
格式为什么是那样, 以及哪些地方验过、哪些还没有。

## 一、四块分工

| 在哪 | 做什么 | 文件 |
|---|---|---|
| 宿主 (app 里那个 Node) | 取模型、写词表、把「开始听」交给 app | `host-plugin/index.mjs` 里的 `lw_wakeword` 与 `WAKEWORD_*` |
| app 进程 (Kotlin) | `KeywordSpotter` 建识别器、读麦克风、命中之后的四件事 | `wake/WakeWordService.kt`、`wake/WakeWordModel.kt` |
| app 进程 (Kotlin) | 设置页那个按钮背后的下载 (逐文件 sha256 + 进度) | `wake/WakeWordDownload.kt`、`wake/WakeWordWords.kt` |
| 通道方法 | 把上面那些暴露成 `wakeword` 一件事 | `tool/LwWakeWord.kt` + `channel/PrivilegedBridge.kt` 一行 |
| 设置页 | 状态、下载、改词、那个开关 | `ui/SettingsScreen.kt` 的 `WakeItems` |
| 页面上的指示器 | 输入框上沿那个麦克风, 在听的时候才出来, 点一下停 | `ui/HostScreen.kt` 的 `WakeBridge` 与 `WAKE_BADGE_JS` |

识别引擎与语音输入是**同一份 sherpa-onnx AAR** (`app/build.gradle.kts` 里那个取 AAR 的任务):
语音输入用 `OfflineRecognizer` (SenseVoice), 这里用 `KeywordSpotter` (zipformer KWS), 所以这一块
不新增依赖, 只多一个模型目录。

## 二、模型

中文的 zipformer KWS, 3.3M 参数, 放在 `filesDir/wake-word/kws-zipformer-wenetspeech-3.3M/`。**只留
int8 那一套** (4 个文件, 5 519 884 B, 约 5.3 MB):

| 文件 | 字节 | sha256 |
|---|---|---|
| `encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx` | 4777666 | `dd784973fc9d2fabb3b800d6dcd20fc3b0ca84f8e2415afe54b032878e447f4d` |
| `decoder-epoch-12-avg-2-chunk-16-left-64.onnx` | 675349 | `fb581d6734511676e246e0dff2fea01b31b0913176cb3ca64576dbab0a177774` |
| `joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx` | 65242 | `f79760052b87239e325f0567c752ad3130b30d92effb847d4307743c20c59a24` |
| `tokens.txt` | 1627 | `72316508d9119696145abc6f1f8cdc46287535c34e5ce7e595f845cb1499cf2e` |

- 来源是**我们自己的 Release**: `yuloong07-star/DSH-LW` 的标签 `models-kws-2024-01-01`, 四个文件都
  在那一版里。字节数与 sha256 都是 GitHub 自己算的那份 (`assets[].digest`), 与本地文件逐个核过。
- **镜像优先**: `https://ghfast.top/https://github.com/...`。这台手机上 `github.com` 直连只回 302, 真正
  的字节在 `objects.githubusercontent.com` 那一跳上, 出不去, 而镜像那一层实测 200 且逐字节一致。
- **为什么不从上游取**: ModelScope 与 sherpa-onnx 自己 Release 里同名的文件是**另外几次构建**, 字节数
  差几百个、sha256 自然不同 (2026-10-05 逐文件比过)。既然手机上跑通的是我们自己这一份, 清单、下载与
  运行时就要对同一份文件说话。
- **装完清掉另一套**: 目录里两套并存时 `wakeWordModelOf` 按 `.int8.` 优先取, 于是"取到哪一套"变成
  "谁先下的算谁的"。所以两边 (插件的 `wakeWordPrune` 与 app 的 `prune`) 都会把不属于这张表的 `.onnx`
  删掉。
- 这份表有**两处实现**: `host-plugin/index.mjs` 的 `WAKEWORD_FILES` 与 `WakeWordDownload.files`。宿主
  那条是给模型用的 (`lw_wakeword op=prepare`), app 那条是给设置页那个按钮用的 (app 里没有一条"叫一次
  宿主工具"的路 —— 回环通道是 host → app 方向的)。改一边就要改另一边。
- 模型不进 APK, 也不进仓库。

## 三、关键词为什么要写成 token

sherpa-onnx 的 keywords 文件每一行是**模型的 token 序列**加一个 `@显示名`, 例如模型自带的那份:

```
n ǐ h ǎo j ūn g ē @你好军哥
x iǎo ài t óng x ué @小爱同学
```

中文原文写进去**不会报错**, 只是那一行被静默丢掉 (`EncodeKeywords` 只认 `tokens.txt` 里的符号, 一个
不认就整行作废)。那等于「照做了, 但永远不触发」, 所以写词表之前先对一遍符号表: 宿主侧把「词=拼音」
拆成声母加带调韵母逐个核对, app 侧 (设置页) 走的是同一套算法的另一份实现。

缺省那一句是:

```
大肥鱼大肥鱼=da4 fei2 yu2 da4 fei2 yu2     ->     d à f éi y ú d à f éi y ú @大肥鱼大肥鱼
```

用法 (`op=keywords`):

```
lw_wakeword op=keywords words=["大肥鱼大肥鱼=da4 fei2 yu2 da4 fei2 yu2","小爱同学=xiao3 ai4 tong2 xue2"]
```

拼音写声调数字 (`da4`) 或直接写带调号的 (`dà`) 都行, 声调落在哪个元音上按普通话的规则 (a/o/e 优先,
`iu` 落 u, `ui` 落 i, 其余落最后一个元音)。这个拆法不是猜的: 拿同样的函数跑模型自带的例子逐字节对上
了上游那几行 —— `你好军哥 → n ǐ h ǎo j ūn g ē`、`小爱同学 → x iǎo ài t óng x ué`、`周望军 → zh ōu w
àng j ūn`、`女儿 → n ǚ ér`。

## 四、听着的时候是什么样

- 一个前台服务 (`foregroundServiceType="microphone|specialUse"`), 16 kHz 单声道, 每 100 ms 一段送进
  识别器, `isReady` 为真就解码, 命中即复位。
- 通知栏留一条常驻, 文字随状态走 (在听哪几个词、刚听到什么、哪条链没就绪), 上面一个「停止」。**一直
  开着的麦克风必须有一眼看得见、一下就关得掉的地方**, 这是那一条通知存在的理由。
- 命中之后的四件事: 震动 (默认 200 ms)、一声短提示音 (120 ms 的 `TONE_PROP_BEEP`)、更新通知、唤起
  —— `onWake=app` 把应用提到前面, `onWake=overlay` 把浮窗叫起来 (见 `docs/floating-input.md`)。
- 参数: `threshold` (默认 0.25, 越低越容易触发、误报越多)、`score` (默认 1.5)、`vibrateMs` (默认 200,
  0 就是不振)。

## 五、设置页那一段 (「唤醒词」)

四行, 各自说一件事:

| 行 | 是什么 |
|---|---|
| 状态文字 | 在听哪几个词 / 没在听、命中过几次、麦克风权限缺没缺、上一次出的问题 |
| 下载唤醒词模型 | 缺哪个下哪个 (逐文件 sha256, 界面上一秒刷一次进度), 下完补上缺省词表并把监听起起来, 顺手清掉另一套 |
| 唤醒词 | 弹出的编辑框里写 `词=带音调数字拼音`, 一行一个词。**逐 token 核对后才写**, 对不上就把是哪一个 token 说出来 |
| 一直听着 | 那个开关。缺权限或缺模型时**不替人按那个 5 MB 的下载**, 只把原因说出来 |

改完词表或换完模型, 正在跑的那一份必须重启才会读新的, 所以这两条路都会"停一下再起"。

## 六、输入框旁边那个麦克风

- **在听的时候才出来**: 输入框上沿一个胶囊, 里面五根跳动的绿柱加一个「正在听」, 点一下就是停
  (`LittleWhale.stop()`), 停了就淡出。它对应的是"一直开着的麦克风"这件事在看着会话时的可见处。
- 实现是 `addJavascriptInterface(WakeBridge, "LittleWhale")` 加一段每次 `onPageFinished` 注入的 JS。
  **不是 dsh 的 client 插件加一条私有路由**: 那个指示器要的状态 (服务在不在听、命中几次) 只有 app
  这一侧知道, 而点它要停的也是 app 里那个前台服务 —— 插件跑在浏览器 JS 里两个都拿不到, 还得再连一
  条回 app 的通道。桥与路由的信任边界是一样的: 这个 WebView 只加载本机 host 那一个页面, 而暴露出去
  的两个方法都没有参数。
- 位置是**算出来的**: 找到页面里的 `textarea` / `contenteditable`, 贴在它上沿的左上角; 找不到就退回
  右下角一个固定位置。所以不碰输入框自己的控件, 也不依赖 dsh 的类名。
- 样式一律用 CSSOM (`element.style.x = ...`) 与 JS 计时器, **不插样式表也不插 keyframes**: 页面哪天
  带上 `style-src` 的 CSP 时内联样式表会被挡掉, 这样写不受影响。波形每 220 ms 跳一次, 而问 app 是
  一秒一次 (它那侧要读一次词表与四个文件的大小)。

## 七、判它成了没有

```
lw_wakeword op=status     # 模型在不在、词表是什么、权限、监听状态、命中几次、上一次听到什么
lw_wakeword op=prepare    # 取模型 (逐个对 sha256) + 清掉另一套 + 写缺省词表
lw_wakeword op=keywords   # 换词表
lw_wakeword op=start      # 开始听
lw_wakeword op=stop       # 停止
```

`op=status` 的 `hits` / `lastKeyword` / `lastHitAt` 就是"有没有听到"的证据; 通知栏那条常驻也能一眼
看出服务在不在跑; 设置页那一段与输入框上沿那个胶囊是给人看的同一件事。

真机上验过的 (2026-10-05, vivo V2417A `10CEB40568000ZB`, Android 16):

- **模型那一趟是真从手机上走通的**: `op=prepare` 从镜像下到 `encoder…int8.onnx` (4 777 666 B) 与
  `joiner…int8.onnx` (65 242 B), 两个 sha256 都对, 同一次里删掉了目录里那两个非 int8 的 `.onnx`, 之后
  `op=status` 报"4 个文件都在"且字节数一个不差。
- **词表写入也过了符号表那一关**: `大肥鱼大肥鱼=da4 fei2 yu2 da4 fei2 yu2` 写成了 `d à f éi y ú d à
  f éi y ú @大肥鱼大肥鱼`。
- 剩下要人自己试的: 喊一句触发一次 (命中会震一下、响一声、通知栏改字), 以及设置页那四行的手感。

## 八、已知边界与风险

- **误触发看词的长短**。「大肥鱼大肥鱼」是六个音节, 比两字词稳得多 (两字词与同音的日常词太近), 但阈值
  仍值得真机调。三到六字的词都建议先实测一遍 `threshold` / `score`。
- **后台起麦克风前台服务是受限的** (Android 14 起): 带 `microphone` 类型从后台启动需要豁免, 持有
  `SYSTEM_ALERT_WINDOW` 是官方豁免之一, 但不是所有 ROM 都认。被拒时退到 `specialUse` 并把这件事记在
  `status.microphoneForeground` 上 —— **前台时照常, 退到后台可能就听不到了**。稳妥的用法是打开应用
  时按下设置页那个开关。
- **电与热**: 模型常驻内存、麦克风一直开, 会持续吃 CPU。不用的时候把那个开关关掉。
- **静默失败只有一处**: 中文原文、错音的 token 会整行被丢, 所以词表写入前必须过符号表那一关 (两边都
  在做)。
- **换词之后不重启等于没换**: 识别器是启动时读的词表, 所以设置页改词、换模型都会自己重启监听。

## 九、这一块改了什么

```
host-plugin/index.mjs                             lw_wakeword 工具、模型清单 (重钉到自建 Release)、镜像优先、装完清另一套
app/src/main/java/.../wake/WakeWordService.kt     前台服务、麦克风循环、命中之后的四件事 (含提示音)
app/src/main/java/.../wake/WakeWordModel.kt       按前缀找模型、符号表核对
app/src/main/java/.../wake/WakeWordDownload.kt    设置页那个按钮背后的下载 (逐文件 sha256 + 进度 + 清理)
app/src/main/java/.../wake/WakeWordWords.kt       「词=带音调数字拼音」转 token 行
app/src/main/java/.../tool/LwWakeWord.kt          通道方法 wakeword 的四个动作, 外加给设置页用的那几个入口
app/src/main/java/.../channel/PrivilegedBridge.kt 一行: "wakeword" 进方法表
app/src/main/java/.../ui/SettingsScreen.kt        「唤醒词」那一段 (状态 / 下载 / 改词 / 开关)
app/src/main/java/.../ui/HostScreen.kt            输入框上沿那个麦克风 (JS 桥 + 注入的脚本)
app/src/main/AndroidManifest.xml                  一个前台服务声明
docs/wake-word.md                                 本文
```
