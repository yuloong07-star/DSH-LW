# DSH-LW 2.0.0 版本目标

分支：`release/2.0.0`（= `feat/wake-word` 与 `feat/screen-background` 合并之后）

## 一、这一版要成什么事

**让"模型在后台用虚拟屏做事"这件事成立，并把这一线已经做完的输入输出能力一起收口。**

1.0.x 解决的是"能不能建起虚拟屏、能不能操作手机"；2.0.0 解决的是**人在不在看，都不影响模型做事**，
以及**人与模型两种输入（手指 / 说话）都能进得来、模型的回答也能出得去**。

## 二、这一版包含什么

| 内容 | 来源分支 | 状态 |
| :-- | :-- | :-- |
| 虚拟屏在后台可用（没有预览时给屏一块保活面） | `feat/screen-background` | 代码已提交，**未编译验证**，装机验证步骤见 `docs/screen-background.md` |
| 语音输入（sherpa-onnx + SenseVoice，宿主 WebView 补 `onPermissionRequest`） | 随 `feat/wake-word` 并入 | 见 `docs/voice-input.md` |
| 语音输出（系统 TTS 的 `speak` 通道方法） | 同上 | — |
| 浮窗面板 A（把 dsh 的输入框浮在别的应用上面） | 同上 | 见 `docs/floating-input.md` |
| 唤醒词（app 侧关键词监听前台服务 + 宿主插件 `lw_wakeword`） | 同上 | 见 `docs/wake-word.md` |

## 三、已知缺口（发布前必须处理）

1. **`feat/wake-word` 落后于 `main` 24 个提交**：`main` 上已有 1.0.3 的正文与 FORCE STOP 相关改动，
   本分支取的是 1.0.2 的 `build.gradle.kts` 基线。所以 `release/2.0.0` 目前**不含 1.0.3 的内容**，
   发布前必须先把 `main` 合并进来（预计在 `build.gradle.kts` 的版本两行、`HostScreen.kt`、
   `PrivilegedBridge.kt`、`AndroidManifest.xml` 上有冲突需要手工判）。
2. **未编译验证**：这些改动都在手机侧写的，本机没有 JDK / Android SDK。`assembleDebug` 必须在有构建
   环境的机器上先过一遍（`app/build.gradle.kts` 的 manifest merger 也要跑）。
3. **装机验证**：`docs/screen-background.md` 第五节的四条（隐藏预览 / 切后台 / 挂回预览 / 改尺寸）都要
   在真机上走一遍。
4. **1.1.0 的草稿 Release**（`untagged-…`，"DSH-LW 1.1.0 (开发中)"）与这一版是同一线工作的两个说法，
   发 2.0.0 时应把它的内容并进 2.0.0 正文或删掉，别留两个"开发中"。

## 四、版本号

- `versionCode = 4`，`versionName = "2.0.0"`（本分支的声明；与 `main` 合并时以此为准）。
- 低于 1.0.3 的 `versionCode`（3）已经出现过，合并 `main` 之后这一处只保留 2.0.0 一组。

## 五、验收（发布判据）

- [ ] 有构建环境的机器上 `:app:assembleDebug` 通过
- [ ] 真机装机：隐藏预览、切后台两种状态下，`lw_ui` / `lw_screenshot` / `lw_tap` 全部可用
- [ ] 语音输入一条真话能进输入框；`speak` 能出声；唤醒词能唤起
- [ ] `main` 的 1.0.3 内容已并入且未回退
- [ ] `docs/` 下四份文档与本次发布正文一致
