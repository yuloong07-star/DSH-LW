# 浮窗输入（把 dsh 的输入框浮在别的应用上面）

主人要的不是"整个应用浮上来"，而是**在别的应用里也能直接跟 dsh 说话**。这份文档记的是第一版
（面板 A）：窗里装整个 GUI，能拖、能收起、能打字，会话与主界面共享；下一版（面板 B）再由客户端
插件把聊天区收掉、只留输入条。

## 一、为什么这么做

- **浮在别人上面是 Android 的事**：`WindowManager` + `TYPE_APPLICATION_OVERLAY`，需要
  `SYSTEM_ALERT_WINDOW`。本机这条特殊访问早就给了（`lw_permissions` 的「悬浮窗: 已允许」），
  清单里 `MANAGE_OVERLAY_PERMISSION` 也声明着，缺的只是代码。
- **"输入框"是页面的事**：dsh 的输入框活在 GUI 页面里。所以第一版直接把那个页面搬进浮窗 ——
  同一个 host 的第二个客户端，会话、cookie、localStorage 与主界面共享，页面上的输入框、麦克风
  按钮（语音输入）、朗读按钮（语音输出）在窗里照常可用。

## 二、这一版有什么

| 部件 | 行为 |
|---|---|
| 标题栏 | 按住拖动整个窗；左边写着「素云」 |
| 收起 | 只剩标题栏，展开回到原来高度 |
| 应用 | 把主界面拉到最前（`CLEAR_TOP`+`SINGLE_TOP`，不另开一页） |
| × | 关掉浮窗（服务停、通知一起收） |
| 页面 | 指向 host 报出来的那个带 token 的地址，与主界面是同一个 GUI |

通道方法 `overlay` 三个动作：`show`（可带 `width`/`height`/`x`/`y`，单位像素）、`hide`、`state`；
工具侧是 `lw_overlay`。

## 三、三条必须记住的限制

1. **窗是可获焦的，所以窗外不再穿透**。要收系统输入法，就不能给窗口加 `FLAG_NOT_FOCUSABLE`；
   代价是手指落在窗外会被窗口吃掉。窗做小一点是唯一的补偿（面板 B 会更小）。
2. **浮窗挂在长跑的前台服务上**，通知栏有一条常驻通知（点它回应用，通知上的按钮直接关浮窗）。
   后台起步时前台服务类型里带 `microphone` 那一半可能被系统拒（持有 `SYSTEM_ALERT_WINDOW` 是
   官方豁免之一，但不是每个 ROM 都认），拒了就退到 `specialUse` —— 前台时麦克风照常，后台可能
   拿不到音频，这一点如实写在 `OverlayService.fend()` 里。
3. **锁屏之上不要指望它**：keyguard 上画可交互的窗另有条件，这一版没去碰。

## 四、验证判据

- `lw_overlay op=state`：`overlay permission: granted`、`host:` 是那个带 token 的地址。
- `lw_overlay op=show`：屏幕上出现浮窗，页面是 dsh 的界面；拖标题栏能挪；收起/展开能切。
- 窗里点麦克风说话：草稿框出现文字（这条依赖语音输入那一支：`feat/voice-output` 上的
  `lw-native` provider 与模型已就绪）。
- `lw_overlay op=hide`：窗与通知一起消失。

## 五、与其它分支的关系

```
main
└── feat/voice-input    麦克风授权 + 本机离线转写 (sherpa-onnx/SenseVoice)
    └── feat/voice-output 读出 (系统 TTS) + 页面朗读那一行
        └── feat/overlay  本文件: 浮窗 (面板 A)
```

编译这一条分支就同时有读入、读出与浮窗。装机仍用同一个 debug keystore 覆盖安装
(`pwsh -File tools\lw-install.ps1`)，否则只能卸载重装、`files/dsh-home` 里的会话与记忆会一起没。

## 六、下一步（面板 B）

B 不换地基：还是这个窗、这个服务，只是**加载带参数的地址**，另加一个 DSH 客户端插件：在那个
参数下把聊天区藏掉、只渲染输入条（+ 麦克风按钮），窗也跟着变成一条。这样做的好处是输入条由
页面自己渲染，输入法、语音、发送全走同一条链路，不需要在原生里重接 DSH 的会话协议。
