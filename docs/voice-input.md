# 语音输入 (本机离线)

主人的手机上「说话 → 出字」这条路要经过三个地方，缺一处都不通。这份文档记的是这三处的
分工、为什么这么分、以及出事时先看哪一层。

## 一、三处分工

| 在哪 | 做什么 | 改动 |
|---|---|---|
| 页面 (WebView 里的 dsh GUI) | 按麦克风录音，录成 16 kHz 单声道 PCM16 WAV | 官方 `dsh-experimental-voice-input-bundle` 提供的录音按钮，不归本仓库 |
| 宿主 (app 里那个 Node) | 把录音交给选中的 provider；本机 provider 再把音频交给 app 进程 | `host-plugin/index.mjs` 新增 `lw-native` provider 与 `lw_speech` 工具 |
| app 进程 (Kotlin) | 用 sherpa-onnx + SenseVoice 在本地出字 | `tool/LwSpeech.kt` + 通道方法 `speech`，链接 sherpa-onnx 的 AAR |

## 二、为什么转写落在 app 这一侧

官方的本地 provider 是 `dsh-experimental-speech-to-text-sensevoice`，它靠 npm 包
`sherpa-onnx-node` 的原生 addon 跑推理。那个包的可选依赖只有 darwin / linux / win，
npm 上也没有 `sherpa-onnx-android-arm64`（实测 registry 404），宿主侧的 Node 因此在
Android 上转不了。所以本机这份把推理放回 app 进程：APK 里静态链接 sherpa-onnx，
通道方法 `speech` 就是它的门。

## 三、麦克风那一处是两件事

1. **WebView 要肯把麦克风交给页面**。`ui/HostScreen.kt` 的 `HostWebView` 里，`webChromeClient`
   原先只覆写了 console / 弹窗 / 文件选择三条，没有 `onPermissionRequest` —— WebView 不覆写
   这一条就按拒绝处理，页面上的 `getUserMedia` 必然失败。这一条已经补上（见本分支第一个提交），
   只放 `RESOURCE_AUDIO_CAPTURE` 且限 `127.0.0.1` 来源。
2. **运行时权限要真的给**。清单里 `RECORD_AUDIO` 早就声明了，但装完要在应用设置页的
   「权限」里把「麦克风」授出去，否则页面请求到 app 这一层仍然拿不到音频。

## 四、模型

- 用的是官方 bundle 指定的同一套权重：
  `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` 的 `model.int8.onnx`（约 228 MiB）
  与 `tokens.txt`，大小与 sha256 都写死在 `host-plugin/index.mjs`。
- 模型不进 APK，也不进仓库：第一次用之前下载到 app 私有目录
  `<filesDir>/speech-models/sense-voice/`。
- 这台手机连不上 `huggingface.co`（实测超时），`hf-mirror.com` 通，所以镜像排在前面；
  两个来源都按 sha256 校验，镜像换包也认不出来是不可能的。
- 下载方式二选一：在 GUI 的语音设置里点准备，或者让 agent 跑一次 `lw_speech op=prepare`。

## 五、编译与安装

1. sherpa-onnx 不在 Maven 上，构建时由 `fetchSherpaOnnxAar` 任务取上游 Release 里的
   `sherpa-onnx-static-link-onnxruntime-1.13.8.aar`（sha256 写死在 `app/build.gradle.kts`）。
   要离线或用自己编译的版本，把 AAR 放到 `app/libs/sherpa-onnx.aar`，构建优先用本地那份。
   取 **static-link-onnxruntime** 那一版是因为本应用已经带了 `onnxruntime-android-qnn`（OCR
   用），普通版 AAR 会再带一份同名的 `libonnxruntime.so`，打包时撞名。
2. APK 因此大约增大 15–20 MB（只打 arm64-v8a 的 `libsherpa-onnx-jni.so`）。
3. 覆盖安装必须用同一个 debug keystore，否则只能卸载重装，`files/dsh-home` 里的会话与记忆
   会一起没。

## 六、怎么判它成了

- `lw_speech op=status`：应报出 sherpa-onnx 版本、模型目录，以及 `present` 与否。
- `lw_speech op=prepare`：下载完再 `status`，`present` 应为真。
- GUI 里点录音按钮：`adb logcat -s DshWebView` 上应看到 `granting ... RESOURCE_AUDIO_CAPTURE`，
  说完话草稿框里出现文字。
- 只看 app 这一侧，`lw_speech op=transcribe wav=<一段 16k 单声道 WAV>` 就应该出字，
  不依赖页面、不依赖网络。

## 七、已知边界

- 一次录音的限制来自官方传输层：WAV ≤ 4 MiB、时长 ≤ 120 秒。
- 识别器建起来之后常驻内存（模型 240 MB 量级，推理时还要一份工作内存）。长时间不用可以
  `lw_speech` 不带、直接走通道 `speech op=release` 放掉；下次转写会重建。
- SenseVoice 只认中英日韩粤；别的语言要另配模型（`LwSpeech.kt` 里的 `MODEL_NAME` 与
  `modelDirectory` 就是留给换模型的接口）。

## 八、启用（不算在 APK 里）

语音输入那一套是 dsh 的插件，装好 APK 之后还要在 profile 里挂上。三个包都已经在 host 的
node_modules 里，不需要另外下载：

| 包 | 作用 | 配置 |
|---|---|---|
| `@deepseek-ai/dsh-experimental-speech-to-text` | 转写服务的注册表与路由 | `defaultProvider: lw-native`, `language: auto` |
| `@deepseek-ai/dsh-experimental-api-speech-to-text` | 页面到宿主那条带鉴权的传输 | 无 |
| `@deepseek-ai/dsh-experimental-client-ui-voice-input` | 草稿框旁边那个录音按钮 | 无 |

官方 bundle `@deepseek-ai/dsh-experimental-voice-input-bundle` 会一次把这四个都挂上，其中
第四个是本地 SenseVoice provider，并把 `defaultProvider` 设成 `sensevoice-local` —— 那一条
在本机是坏的（原因见第二节）。所以两条路：单独挂上面三个，或者挂 bundle 之后用 profile 的
patch 层把 `speech-to-text` 那条的 `defaultProvider` 覆盖成 `lw-native`。

## 八点五、第二档引擎：GLM-ASR-Nano（更准，也更慢）

SenseVoice 是 234 M 的那一档，认不准的时候（口音、安静说话、长句）没有别的办法，所以又接了
智谱的 **GLM-ASR-Nano-2512**（1.5 B，MIT）。它比 SenseVoice 强得多，代价是慢一个数量级 ——
**两档并存，谁快谁准由调用方选**。

| | SenseVoice | GLM-ASR-Nano |
|---|---|---|
| 参数 | 234 M | 1.5 B（音频编码器 32 层 + Llama 式解码器 28 层） |
| 跑在哪 | sherpa-onnx（app 进程内，JNI） | llama.cpp + mtmd（app fork 出来的常驻进程） |
| 权重 | 240 MB（在 APK 外，第一次用之前下） | 1.6 GB（Q4_K 主模型 980 MB + Q8_0 音频编码器 720 MB） |
| 常驻内存 | 240 MB 量级 | 1.8 GB 量级（KV cache 另算） |
| 实测耗时 | 一句话不到 1 秒 | 两三个字约 4 秒，8 秒的话约 10 秒，19 秒的话约 22 秒 |
| 语言 | 中英日韩粤 | 中文（含粤语与方言）、英文 |

那三个数字是**天玑 9300（vivo V2417A）上实测的**，8 线程、`--pad-seconds 4`、机器已经跑热了；
同一台机器在冷的时候快三四成。

### 为什么它落在一个单独的可执行文件里

和 `liblauncher.so` 一样的理由：安卓 10 以后 app 不能执行自己 data 目录里的东西，而
`nativeLibraryDir` 里的可以。所以 `app/src/main/native/glmasr/` 编出来的
**`libglmasr.so` 是一个程序**（静态链了 llama.cpp 与 mtmd），app 侧 `fork + exec` 它，模型只在
进程启动时读一次，之后每段录音走 stdin/stdout 上的一行 JSON：

```
daemon -> app   {"ready":true,"padSeconds":4,"threads":6,...}
app    -> daemon {"wav":"/absolute/path/to/a.wav"}
daemon -> app   {"ok":true,"text":"...","ms":10642,"tokens":19}
app    -> daemon {"quit":true}
```

这一层只有 3.7 MB（strip 之后），进程与模型的生命周期由 `tool/GlmAsr.kt` 管：第一次转写时起，
`lw_speech op=release engine=glm` 或者 `release` 不带引擎时放掉。

### 那 30 秒窗口（这份集成里唯一改上游的地方）

llama.cpp 的 mtmd 走的是 whisper 那套预处理：**不管录音多长，先补 30 秒静音**，于是「打开设置」
与一段 30 秒的话在编码器那里一样贵 —— 实测都是 12 秒上下，这才是它慢的**全部**原因。编码器本身
是变长的（mel 长度决定 token 数），所以这里把那个常数换成 `LW_ASR_PAD_SECONDS`（由
`--pad-seconds` 放进环境，缺省 4 秒），改动在 `patch-short-window.cmake` 里，**FetchContent
拉下源码之后打**：

```
app/src/main/native/glmasr/CMakeLists.txt      拉 llama.cpp（pin 版本 + sha256）+ 打补丁
app/src/main/native/glmasr/patch-short-window.cmake  mel 长度可调 + 两处只在整窗下成立的假设
app/src/main/native/glmasr/glmasr_daemon.cpp   协议与推理本体
```

补丁里第二件事容易被忽略：**mel 长度要对齐到 8 帧的倍数**。图里按向上取整算输出 token 数，而
`clip_n_output_tokens` 按向下取整，两者只在 8 的倍数上相等；不对齐就是
`clip_encode: expected output N tokens, got N+1` 之后 `GGML_ABORT`（实测踩过）。第三件事是最后
那一小段 mel 也要发出去（上游在这里整段丢掉），否则超过一个窗口的录音会缺尾巴。

### 质量实测（合成语音，逐字对比）

| 样本 | 30 秒窗口 | 4 秒窗口 |
|---|---|---|
| 中文 1.5 s「打开设置」 | 正确 | 正确 |
| 中文 3.6 s 亮度指令 | 正确 | 正确（数字规整成 70%） |
| 中文 7.8 s 会议改期 | 正确 | 正确 |
| 中文 3.6 s（18% 音量） | 正确 | 正确 |
| 中文 19 s 长句（含人名、数字） | 多一个逗号 | **逐字正确** |
| 英文 3.5 s | 正确 | 正确 |
| 中文 11 s 数字串（会议号 / 密码 / 金额） | 错（「会议号是」听成「会一号十」） | 同样错 |

最后那一条在两种窗口下都错，说明它不是窗口改小带来的（TTS 念的数字串对任何模型都难）。
**这一档的结论**：窗口从 30 秒改到 4 秒没有掉质量，但样本只有七条，真实的方言与噪声环境还得
自己听。

### 怎么用

```
lw_speech op=status                          # 两档都报: 模型在不在、内存里有没有
lw_speech op=prepare engine=glm              # 下 1.6 GB 的那一套（hf-mirror 优先）
lw_speech op=transcribe engine=glm wav=/path/to/16k-mono.wav
lw_speech op=release engine=glm              # 把那 1.8 GB 还回去
```

- **GUI 的录音按钮默认走 GLM**（`host-plugin/index.mjs` 里的 `SPEECH_ENGINE_DEFAULT`）：按那个
  按钮是一个明确的动作，等几秒换准确率是划算的。GLM 的模型不在时它退回 SenseVoice，而不是把
  按钮做成死的。
- **常驻语音链仍走 SenseVoice**（唤醒词命中之后那一路），因为它要的是"说完就出字"。
- 引擎名不认识的（比如打错的）一律落回 SenseVoice。

### 这一档还没做的

- **设置页没有引擎开关**：现在由插件里的常量与 `engine=` 参数决定，做一个开关要动
  `ui/SettingsScreen.kt` 与两份 strings.xml
- **下载不能续传**：断在第 1.2 GB 就得从头再来（`.part` 会被删掉），1.6 GB 的账不能不算
- 没试过 GPU（天玑的 Immortalis 走 Vulkan 后端）与进一步压窗口（`--pad-seconds 2/3`）

### 装机之后实测（2026-10-06，vivo V2417A）

装的是同一份 APK，走的是 app 里那条路（`speech op=transcribe engine=glm`，也就是 GUI 录音
按钮走的同一条），模型常驻之后：

| 样本 | app 里认一次 |
|---|---|
| 中文 1.5 s「打开设置」 | 5.2 s |
| 中文 3.5 s 英文提醒 | 5.6 s |
| 中文 3.6 s（18% 音量） | 8.0 s |
| 中文 7.8 s 会议改期 | 22.6 s |
| 中文 11 s 数字串 | 20.8 s（内容与 30 秒窗口下同样错） |
| 中文 19 s 长句 | 27.0 s |

比在 `/data/local/tmp` 里用 shell 跑的同一份二进制慢一倍上下（那是 3.7 / 10.6 s），原因不是
cgroup：常驻进程落在 `cpuset:/top-app`、八核全给、`VmSwap` 为 0，但整机内存已经见底
（15.3 GB 里 15.1 GB 在用），app 自己还带着 Node 与 WebView，再加上连续跑了二十来分钟的热降频。

常驻内存 **2.76 GB RSS**（权重 1.6 GB + KV cache 与编码器的工作区），所以它是个"用之前想一下"
的开关，`lw_speech op=release engine=glm` 会把这笔内存还回去。

**下载那条路是通的**：用 app 自己的 node 从设备这一侧请求 hf-mirror，4 MB 用了 1.5 s（2.6 MB/s）、
16 MB 用了 1.9 s（8.6 MB/s），也就是 1.6 GB 大约 3 到 10 分钟。测试这次是把两个 GGUF 用
`adb push` 放进 `speech-models/glm-asr/` 的（省一次 1.6 GB 的等待），所以 `op=prepare` 里那两条
"大小对得上就跳过"的分支走过了，**真的从零下 1.6 GB 这一步没走**。

### 那个把人坑了半小时的坑：`-O0`

APK 里的 `libglmasr.so` 是 AGP 的 **Debug** 变体编的，而 AGP 给 externalNativeBuild 传的就是
`CMAKE_BUILD_TYPE=Debug` —— 也就是**一个 `-O` 都没有**。同样的二进制、同样的权重、同一台设备：

```
改之前 (Debug, -O0)   1.5 秒的音频认了 137 秒, app 里那条路 225 秒
改之后 (Debug, -O3)   同一条 3.7 秒, app 里那条路 5.2 秒
```

修法在 `app/src/main/native/CMakeLists.txt`：Debug 时给 C 与 CXX 的 `*_FLAGS_DEBUG` 各补一个
`-O3`。这件事只栽在"那份二进制是黑盒"上 —— 它在 `/data/local/tmp` 里用自己的 `-O3` 构建跑得
好好的，一进 APK 就慢四十倍，而 app 里没有任何一处会喊慢。

## 九、这一分支改了什么

```
app/build.gradle.kts                 取 sherpa-onnx AAR + 依赖
app/.../tool/LwSpeech.kt             新增: app 进程里的识别器与三个动作
app/.../channel/PrivilegedBridge.kt  方法表加一行 speech
app/.../ui/HostScreen.kt             WebView 的麦克风授权 (第一步, 已在分支上)
host-plugin/index.mjs                lw-native provider + lw_speech 工具 + 模型下载
docs/voice-input.md                  本文
```

## 十、读出（语音输出）

读入是页面录音、宿主转写；读出反过来：文字在 app 这一侧变成声音。

### 为什么用系统引擎

这台手机上不需要密钥、也不需要联网的语音输出只有一条：ROM 自带的 TTS 引擎。实测扫过 261 个
系统 APK 的清单，`AIService`、`AiAgent`（vivo）声明了 `android.intent.action.TTS_SERVICE`
并且 dex 里引用了 `android/speech/tts/TextToSpeechService`（即提供引擎），`TalkBack` 与
`SwitchAccess` 只是 `<queries>` 查询、不提供。所以 `android.speech.tts.TextToSpeech` 在本机
可用。

**清单里必须声明可见性**：Android 11 起不声明就看不见 TTS 引擎，所以
`AndroidManifest.xml` 的 `<queries>` 里补了一条 `TTS_SERVICE`（与 Shizuku、launcher 那两条
并列）。

### 通道方法 `speak`

`tool/LwSpeak.kt`，四个动作：

| op | 做什么 |
|---|---|
| `status` | 引擎包名、音色数、中文是否可用（`LANG_MISSING_DATA` 会明说要下语音包）、当前是否在念 |
| `speak` | 念一段；超长按句切（引擎单次上限 `getMaxSpeechInputLength()`）；默认打断前一句；等到最后一片念完才回话 |
| `stop` | 掐断正在念的与排队的 |
| `release` | 把引擎还回去（初始化要几百毫秒，平时留着复用） |

TextToSpeech 必须在有 Looper 的线程上建，而通道的应答跑在工作线程，所以 LwSpeak 把创建这一步
post 到主线程再等它回话（8 秒预算，超时如实报错）。

### 谁在什么时候念

- **任务收尾**：`lw_speak op=speak text="一句话"` —— 与已有的完成通知并列，通知走通知栏、朗读
  走喇叭，两条互不影响。
- **GUI 内的朗读按钮**：那是另一条路，见下面。

### GUI 里的「朗读」按钮（可选）

装 `dsh-xiaomi-tts`（3.0.8，零依赖、客户端插件：助手消息操作栏里的朗读按钮 + 设置面板；MiMo
流式 PCM，用本机已有的 `XIAOMI_API_KEY`；另有「本地优先 / 仅 MiMo / MiMo 优先」三档，本地那档
走浏览器语音）。它要求 dsh `0.2.0-rc.2` 与 Node 22+，本机两条都满足。

**它需要 APK 里多一行**：`HostWebView` 补上
`settings.mediaPlaybackRequiresUserGesture = false` —— WebView 这项默认是 `true`，意思是
没有用户手势就不许放音频，「消息到了自动朗读」会被这条拦掉（在系统浏览器里不受此限）。

### 验证判据

- `lw_speak op=status`：报出引擎包名与中文可用性；`chinese` 若是 `missing data`，去系统的
  「文字转语音」设置里下一个中文语音包。
- `lw_speak op=speak text="测试"`：回执说「engine took N characters … reported it finished」。
  **回执不等于有声**——是否真出声要真人确认一次，这一条与通知/震动同一个道理。
- GUI 里：点助手消息的朗读按钮。

## 十一、乱码那一课（2026-10-07）

主人报的是「输入框上麦克风语音识别 bug，识别时会出现乱码」，给的两个例子是 `ä½ å¥½` 与
`คุณยุติสุดท้าย`。

### 先取证，再动手（两条都做了）

**通路是干净的**：把手机上的 dsh host 通过 `adb forward` 拉到电脑，用 WebView 里那份信任 cookie
打开同一个 GUI，再让浏览器用一段「假麦克风」（`--use-file-for-fake-audio-capture` 喂
`zh-short.wav`）走一次录音按钮 —— 页面 → base64 → 宿主 → 应用（GLM-ASR）→ 回页面 → 插进草稿框，
**原样回的是中文**「打开设置，打开设置，打开设」。也就是说这一整条通路上没有哪一跳把 UTF-8 解错。

**模型对坏音频是稳的**：拿应用里同一份 `libglmasr.so` 与同一份权重，在设备上直接喂了九段音频 ——
静音 / 白噪声 / 240 Hz 电流声 / 音乐（琶音） / 削顶 40 倍 / 三倍慢放 / 三倍快放 / 0.15 s 与
0.4 s 的截断。结果：全部要么回**空串**，要么照样认对（`-40 dB` 的轻声也认对了「打开设置」）。

剩下的解释只剩「模型自己偶尔吐出来的那点东西」：LLM 型识别器在难音频上会吐外文（那句泰文），而
`ä½ å¥½` 正是 `你好` 的 UTF-8 字节被当 Latin-1 读出来的样子（E4 BD A0 E5 A5 BD 逐字节可复现）。

### 处置：出字之后过一遍清洁口

`voice/TranscriptClean.kt` 是那一个口（纯函数，`TranscriptCleanTest` 钉着），两件事：

| 情形 | 做什么 | 判据 |
|---|---|---|
| 整串都在 `U+00FF` 以内、按 Latin-1 取字节再**严格**解码 UTF-8 成功 | 用修出来的那一句 | `ä½ å¥½` → `你好`；`café` / `Müller` 因为不是合法 UTF-8 而原样留下 |
| 出现这个应用没声明的文字（泰文 / 西里尔 / 阿拉伯文 …），或者一串满是 Latin-1 符号的伪文本（两个以上 `½ ¿ ¡ ¤ §` 那种） | 回**空串** | 页面显示「没有识别到内容」，而不是把那串东西插进草稿框 |

清洁口挂在 `LwSpeech` 的**出字那一处**：`transcribe`（GUI 录音按钮，两套引擎都走）与 `recognize`
（唤醒词那条链）各一个调用点，所以两条链说的是同一门话。

### 现场留档

原来那段 WAV 在 `finally` 里就被删了，事后只剩「我见过一串怪字」。现在 `host-plugin` 的
`speechKeep` 把最近 **5 段**录音落到 `files/speech-models/recordings/`，并把每次结果写一行进
`files/speech-models/transcripts.log`（只留最近 200 行）。下一次再出乱码就能拿同一段音频喂回
`lw_speech op=transcribe wav=…` 对账：是模型听错了，还是别处把它写坏了。
