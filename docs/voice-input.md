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
