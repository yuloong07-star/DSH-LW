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
