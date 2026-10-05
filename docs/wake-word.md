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

## 三、词表那一份算法也有两处实现, 而它们不许漂

`词=拼音` 那套拆法 (声母先长后短、调号落在哪个元音、`v` 与 `u:` 归一到 `ü`) 同样是**两份实现**:

| 在哪 | 谁走这条路 |
| :-- | :-- |
| `wake/WakeWordWords.kt` | 设置页那个编辑框 (`LwWakeWord.setWords`) |
| `host-plugin/index.mjs` 的 `wakeWordLine` + `markedSyllable` + `toneIndex` | 模型那条路 `lw_wakeword op=keywords` |

源码里那句"改了一边记得看另一边"是口头纪律, 而口头纪律会漂。所以有一条可执行的检查:

```
node tools/check-wake-words.mjs      # 不需要设备, 也不需要 host
```

它做四件事, 而第 1 件是它存在的理由:

1. **两份实现跑同一张判据表, 逐行比对**。表是直接从 `WakeWordWordsTest.kt` 里读的 —— 那边已经由单元
   测试钉住、而且对过模型自带的例子, 所以**不抄第二遍**(抄一遍就是第三份实现)
2. **声母表与调号表逐项比对** (顺序也要一样: 长的在前, 否则 `zh` 会被拆成 `z` + `h`)
3. **符号表取"期望值并集再少一个"**, 于是"表里没有就抛"那一关是真的在考 —— 拿实际输出当表会让它空转
4. **读到的判据条数必须与 Kotlin 那份写的条数对上**: 一条没被跑的判据比一条红的判据危险 (红的那条
   会被人看见)

这份检查是**自己验过会红的**: 故意把一条期望值改坏 (`ch ī` → `ch i`) 之后它立刻报出来并印出两边,
还原之后又绿 —— 一条没见过它红的判据可能是死代码。

写这两份判据时顺带核清了三处容易看错的地方 (以实测为准, 不是读代码猜的):

- **`er2` 是 `ér` 而不是 `er`**: 零声母的音节整个当一个 token, 但那个 token 本身带调号
- **`nv3` 与 `nü3` 得到同一行 `n ǚ`**: `v` 在拆声母**之前**就变成了 `ü`, 所以 `v` 不会单独成为一个
  token (`INITIALS` 里那个 `v` 实际上永远匹配不到 —— 留着无害, 但别指望它)
- **JS 的 `\w` 不认 `ü`**: 用 `\w` 去读那张调号表的键会安静地少读一行 (`ü` 那一行), 于是判据变成
  "五行的表与六行的表相等"然后红 —— 读源码的脚本要按字符类写, 别用 `\w`

## 四、关键词为什么要写成 token

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

## 五、听着的时候是什么样

- 一个前台服务 (`foregroundServiceType="microphone|specialUse"`), 16 kHz 单声道, 每 100 ms 一段送进
  识别器, `isReady` 为真就解码, 命中即复位。
- **服务里是两层, 不是一层**: 唤醒词那一路一直在守 (低功耗守门人), 而常驻语音那一路 (silero VAD
  切段 + SenseVoice 出字) **只在命中之后**、或者主人把「允许常驻语音」那个许可打开时才铺开, 静
  10 秒没有新的出字就收回去 (`VOICE_IDLE_MS`)。分工与理由见 `docs/wake-voice-states.md`
- 通知栏留一条常驻, 文字随状态走, 而且**按两条链分别说**: 只有唤醒词在守时说"正在听「…」· 常驻
  语音没开", 常驻语音也在跑时才说"常驻语音在跑", 正在念回答那一小段说"正在念回答, 先不听"
  (半双工那道闸是合着的, 那时叫不醒)。上面一个「停止」, 关的是整件事。**一直开着的麦克风必须有
  一眼看得见、一下就关得掉的地方**, 这是那一条通知存在的理由。
- 命中之后的七件事: 震动 (默认 200 ms)、一声短提示音 (120 ms 的 `TONE_PROP_BEEP`, 它占住半双工那
  一小段所以不会被录进去)、更新通知、**开常驻语音** (受那个许可管)、预热识别器、把"唤醒窗口"打开
  (随后头一句会开一个新对话), 以及唤起 —— `onWake=app` 把应用提到前面, `onWake=overlay` 把浮窗叫
  起来 (见 `docs/floating-input.md`)。
- 参数: `threshold` (默认 0.25, 越低越容易触发、误报越多)、`score` (默认 1.5)、`vibrateMs` (默认 200,
  0 就是不振)。
- **一句话什么时候算说完由 silero VAD 那个静音窗定**: `SpeechSegmenter.MIN_SILENCE_SECONDS` 现在是
  **0.8 s** (2026-10-05 从 3.0 s 收下来的, 主人反馈"说话结束到发送等得太久")。它同时定两件事: 等够
  它才把段交出去, 而段尾就落在"最后一段人声 + 它" —— 所以它多长, 主人就多等多久。说话中间停顿长的
  人会被切成两段, 真机上真被切了就把它调回 1.0-1.2 s (那一处改一行)
- 同一批里另两条与延迟有关的改动: 命中那一下另起线程**预热识别器** (`LwSpeech.warmUp`, 把 240 MB
  的加载挪到主人还在说话的那几秒里), 以及宿主侧读队列的间隔从 500 ms 收到 **150 ms**
  (`VOICE_POLL_MS`)。三处加起来大约把"说完到发出去"从 3-4 秒压到 1 秒出头

## 六、设置页那一段 (「唤醒词」)

五行, 各自说一件事。**关键在最后两行是两个许可, 不是一个开关** (主人 2026-10-05 定的状态机,
细节见 `docs/wake-voice-states.md`):

| 行 | 是什么 |
|---|---|
| 状态文字 | 三种组合分开说: 只有唤醒词在守 / 常驻语音也在跑 / 许可开着而服务没起; 另加命中过几次、麦克风权限缺没缺、上一次出的问题 |
| 下载唤醒词模型 | 缺哪个下哪个 (逐文件 sha256, 界面上一秒刷一次进度), 下完补上缺省词表。**只在「允许唤醒」开着时才顺手起监听**, 而且起的只是唤醒词那一路 |
| 唤醒词 | 弹出的编辑框里写 `词=带音调数字拼音`, 一行一个词。**逐 token 核对后才写**, 对不上就把是哪一个 token 说出来 |
| **允许唤醒** | 允不允许这个应用一直听着唤醒词 (缺省**开**)。它只决定服务起不起来 —— 这是"设置项只作前置许可"的第一半 |
| **允许常驻语音** | 命中之后要不要真的把切段与出字铺开 (缺省**关**)。改完会告诉正在跑的服务 (`ACTION_REFRESH`), 不必重启 |

改动之前这里只有一个开关「一直听着」, 而它直接等于常驻监听 (`checked = WakeWordState.listening`):
一打开设置页那个开关, 麦克风、silero VAD 与那 240 MB 的识别模型全跟着起来。现在这两个开关**只写
偏好、什么都不启动** (除了"允许唤醒"打开时把唤醒词那一路起起来), 常驻语音那一路只在**命中唤醒词**
之后才铺开, 静 10 秒自动收回去。

缺权限或缺模型时不替人按下那个 5 MB 的下载, 只把原因说出来。改完词表或换完模型, 正在跑的那一份
必须重启才会读新的, 所以这两条路都会"停一下再起"。

**许可是存盘的**, 所以重启应用时 `MainActivity.onCreate` 会照着它把监听恢复起来 (`LwWakeWord.ensure`)
—— 否则"允许唤醒"只是个记号, 服务不会自己起。通知栏那个「停止」关的是**整件事** (服务停掉),
会话界面那个胶囊关的是**常驻语音那半条** (`ACTION_STOP_VOICE`), 唤醒词接着守。

## 七、输入框旁边那个麦克风

- **常驻语音在跑的时候才出来**: 输入框上沿一个胶囊, 里面五根跳动的绿柱加一个「正在听」, 点一下就是
  把**常驻语音那半条**关掉 (`ACTION_STOP_VOICE`), 关了就被收回, 而唤醒词继续守着 —— 想再要一次
  "开口说话", 喊一声就回来了。整件事收工是通知栏那个「停止」。**纯唤醒词守着时它不出现**: 那是常驻
  状态, 一直挂着只会让人以为麦克风在被吃 (桥给页面的是 `voice`, 不是 `listening`)
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

## 八、判它成了没有

```
lw_wakeword op=status     # 模型在不在、词表是什么、权限、监听状态、命中几次、上一次听到什么
lw_wakeword op=prepare    # 取模型 (逐个对 sha256) + 清掉另一套 + 写缺省词表
lw_wakeword op=keywords   # 换词表
lw_wakeword op=start      # 开始听
lw_wakeword op=stop       # 停止
```

`op=status` 的 `hits` / `lastKeyword` / `lastHitAt` 就是"有没有听到"的证据; 通知栏那条常驻也能一眼
看出服务在不在跑; 设置页那一段与输入框上沿那个胶囊是给人看的同一件事。

### 出 APK 之前就能跑的两条 (不需要设备)

词表那一半最阴的失败面是"**词表存下来了、界面也说保存好了, 而那个词永远不触发**", 所以它有两条检查,
一条钉正确性、一条钉两份实现不许漂:

```
.\gradlew.bat :app:testDebugUnitTest --tests "io.github.miuzarte.littlewhale.wake.WakeWordWordsTest"
node tools/check-wake-words.mjs
```

第一条是 15 对判据 (含模型自带的那几个例子) 加"对不上的 token 必须抛且点名是哪一个"。它的符号表是
**从期望值推出来的**而不是手抄的 —— 手抄一张表会把"抄漏一个韵母"误报成代码错 (确实踩过: 漏了 `ōu` /
`éi` / `iǎo` / `ér`, 八条判据一起红而代码一行没错)。

第二条把第三节那张"两份实现"的表变成可执行的, 而且**自己验过会红**: 故意把一条期望值改坏
(`ch ī` → `ch i`) 之后它立刻报出来并印出两边, 还原之后又绿。

真机上验过的 (2026-10-05, vivo V2417A `10CEB40568000ZB`, Android 16):

- **模型那一趟是真从手机上走通的**: `op=prepare` 从镜像下到 `encoder…int8.onnx` (4 777 666 B) 与
  `joiner…int8.onnx` (65 242 B), 两个 sha256 都对, 同一次里删掉了目录里那两个非 int8 的 `.onnx`, 之后
  `op=status` 报"4 个文件都在"且字节数一个不差。
- **词表写入也过了符号表那一关**: `大肥鱼大肥鱼=da4 fei2 yu2 da4 fei2 yu2` 写成了 `d à f éi y ú d à
  f éi y ú @大肥鱼大肥鱼`。
- 剩下要人自己试的: 喊一句触发一次 (命中会震一下、响一声、通知栏改字, 随后那句话落在一个**新对话**
  里并被念出来), 以及设置页那五行的手感。

## 九、已知边界与风险

- **误触发看词的长短**。「大肥鱼大肥鱼」是六个音节, 比两字词稳得多 (两字词与同音的日常词太近), 但阈值
  仍值得真机调。三到六字的词都建议先实测一遍 `threshold` / `score`。
- **后台起麦克风前台服务是受限的** (Android 14 起): 带 `microphone` 类型从后台启动需要豁免, 持有
  `SYSTEM_ALERT_WINDOW` 是官方豁免之一, 但不是所有 ROM 都认。被拒时退到 `specialUse` 并把这件事记在
  `status.microphoneForeground` 上 —— **前台时照常, 退到后台可能就听不到了**。稳妥的用法是把设置页的
  「允许唤醒」打开之后让应用在前台起一次 (应用启动时会自己 `ensure` 一次)。
- **电与热**: 唤醒词那一路一直开着的代价是"麦克风与一份 3.3M 的模型"; 真正的耗电大头是常驻语音那一路
  (240 MB 的识别器 + VAD), 而它只在命中之后开着、静 10 秒收回。不想听唤醒词就把「允许唤醒」关掉。
- **静默失败只有一处**: 中文原文、错音的 token 会整行被丢, 所以词表写入前必须过符号表那一关 (两边都
  在做)。
- **换词之后不重启等于没换**: 识别器是启动时读的词表, 所以设置页改词、换模型都会自己重启监听。
- **唤醒开的新对话不会把界面切过去**: 会话是在宿主那侧 `sessionController.create` 建的, 而"在看哪
  一个"是浏览器自己的路由状态 —— 回答会念出来, 但人可能正看着另一个会话 (主人选的"改动最小"那一档)。
- **`allowVoice` 与 `voiceAllowed` 会短暂不一致**: 前者是设置页存的, 后者是服务读到的; 改完许可要发
  一条 `ACTION_REFRESH` 才同步, 所以 `op=status` 把两个都报出来。

## 十、叫醒之后做什么 + 那两句命令（批次 4.4 / 4.5，2026-10-05）

### 「叫醒之后」是一个设置，不是一个写死的动作

命中那一下原本写死三件（震动 / 通知 / 唤起）。现在设置页那一段多了两行：

| 行 | 是什么 |
|---|---|
| **叫醒之后** | 只叫醒（缺省，与改动之前一致）/ 顺带切到视频模式 / 顺带切回手机模式 |
| **叫醒时震动** | 命中那一下震 200 ms；关掉只剩那一声短提示音（界面照旧叫起来） |

后两条**不在服务里切模式**：`WakeWordService.afterHit()` 只往收件箱写一句规范命令
（`VoiceCommands.VIDEO` / `VoiceCommands.PHONE`），由宿主插件那张表去认并执行 —— 命令词表只有
一份。插件按 `seq` 顺序读，所以这条命令一定比主人随后说的那句话先落地：切模式发生在投递之前。

改完这两个值会发一条 `ACTION_REFRESH`，正在跑的服务立刻读新的（不必重启监听）。

### 命令词表在宿主那一侧

主人说的哪几句话是**命令**、哪几句是"要投进会话的话"，由 `host-plugin/index.mjs` 的
`VOICE_COMMANDS` 一张表说了算（可行性稿 2.7：改词表不必重下关键词表、也不必重建 APK）：

| 模式 | 说法（整句归一化之后相等才算） |
|---|---|
| `video` | 打开视频模式 / 进入视频模式 / 切到视频模式 / 换成视频模式 / 视频模式 |
| `phone` | 回到手机模式 / 退出视频模式 / 关闭视频模式 / 关掉视频模式 / 手机模式 |

- **归一化只吃空白与标点**（说出来的句子末尾会带句号），还认大小写；其余一个字都不许差
- **整句相等，不做包含匹配**：`"视频模式怎么改"` 是一句要进会话的话，表认错了人主人就会发现自己的
  问题没了。这一条有 `tools/check-voice-commands.mjs` 里的反例钉着
- 命中之后**不进会话**（既不插正在跑的那一轮，也不开新对话，更不理会 `wake` 记号）：它是对这台手机
  说的，不是对助手说的一句话。`lw_voice op=inbox` 把"投递了几条"与"吃掉了几条命令"分开报，
  `op=say` 遇到命令句会直接说"这是一句命令，切成了没成"
- 表里认不出来的说法照旧当普通一句话投进会话 —— 宁可多一句对话，也不要因为"猜它想切模式"而吃掉
  主人真正说的一句

### 三个触发口现在都在

`lw_mode`（模型自己调）/ 唤醒词命中（`onHit`）/ 浮标菜单那两个模式项 —— 后两个**都走这一张表**，
所以不会有两份实现（`tools/check-voice-commands.mjs` 拿 Kotlin 那两个规范句子与插件那张表对着核）。

## 十一、这一块改了什么

```
host-plugin/index.mjs                             lw_wakeword 工具、模型清单 (重钉到自建 Release)、镜像优先、装完清另一套; voiceDeliver 认 wake 记号开新对话; VOICE_COMMANDS 那张命令表 + applyMode (与 lw_mode 共用); startBallPhase 推"正在想"
app/src/main/java/.../wake/WakeWordService.kt     前台服务、两层 (唤醒词一直守 / 常驻语音按命中开)、命中之后那几件事、闲置超时、半双工闸、ACTION_LISTEN_NOW (浮标点一下) 与 afterHit (叫醒之后那两句命令)
app/src/main/java/.../voice/SpeechSegmenter.kt    silero VAD 切段、abandon() 丢半句话、静音窗 0.8 s
app/src/main/java/.../voice/VoiceState.kt         capturing 的含义收窄成"常驻语音在跑"
app/src/main/java/.../voice/VoiceInbox.kt         投递队列, 头一句带 wake 记号
app/src/main/java/.../voice/VoiceCommands.kt      两个规范命令句 (与插件那张表对着核)
app/src/main/java/.../wake/WakeWordModel.kt       按前缀找模型、符号表核对
app/src/main/java/.../wake/WakeWordDownload.kt    设置页那个按钮背后的下载 (逐文件 sha256 + 进度 + 清理)
app/src/main/java/.../wake/WakeWordWords.kt       「词=带音调数字拼音」转 token 行
app/src/main/java/.../tool/LwSpeech.kt            端侧识别; warmUp() 只加载不出字 (命中时预热)
app/src/main/java/.../tool/LwWakeWord.kt          通道方法 wakeword 的四个动作; 两个许可 + onHit / vibrate; speakNow (浮标点一下那条路)
app/src/main/java/.../channel/LwModes.kt          视频模式给常驻语音许可 (不再直接起整条链), 手机模式收回
app/src/main/java/.../channel/PrivilegedBridge.kt 一行: "wakeword" 进方法表
app/src/main/java/.../ui/SettingsScreen.kt        「唤醒词」那一段 (状态 / 下载 / 改词 / 两个许可 / 叫醒之后 / 震动) 与「浮标」那一段
app/src/main/java/.../ui/HostScreen.kt            输入框上沿那个麦克风 (JS 桥 + 注入的脚本, 只在常驻语音跑时出现)
app/src/main/java/.../MainActivity.kt             启动时照着许可把监听恢复起来 (LwWakeWord.ensure) 并按存盘开关放球 (LwOverlay.ensure)
app/src/main/AndroidManifest.xml                  一个前台服务声明
tools/check-voice-commands.mjs                    命令表两份实现不许漂 (8 条判据)
docs/wake-word.md                                 本文
```
