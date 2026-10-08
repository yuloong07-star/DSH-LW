# 取景与截图的数字（口径表）

**这一份是唯一的权威。** 同一个数在仓库里出现好几处（插件的工具描述、应用那几份 Kotlin 常量、四份
提示词、技能 `android-device-control`、视频模式那个预置的副本），它们以前各写各的，`video.md` 第 22 条
还专门写过一句"本模式与技能冲突时以本模式为准" —— 那句话本身就是口径漂了的证据。

改数字的顺序是固定的：先改这一份，再跑 `node tools/check-shot-params.mjs`，它会逐处告诉你还差哪几个。

<!-- shot-params:begin -->
```json
{
  "look": {
    "framesMin": 1,
    "framesMax": 12,
    "framesDefault": 4,
    "secondLook": 9,
    "movingFrames": 12,
    "groups": 2,
    "intervalMinMs": 100,
    "intervalMaxMs": 2000,
    "intervalDefaultMs": 200,
    "qualityPixels": [307200, 921600, 2073600]
  },
  "shot": {
    "countMin": 1,
    "countMax": 12,
    "intervalMinMs": 50,
    "intervalMaxMs": 5000,
    "intervalDefaultMs": 120,
    "qualityPixels": [262144, 640000, 1690000],
    "byteMinKiB": 256,
    "byteSliderMaxKiB": 1024,
    "byteTypedMaxKiB": 4096
  }
}
```
<!-- shot-params:end -->

## 两套账（别混）

| | 取景 `lw_look`（视频模式那台相机） | 截屏 `lw_screenshot`（屏幕：主屏 display 0 / 虚拟屏） |
| :-- | :-- | :-- |
| 一次几张 | 缺省 4，上限 12；一组不够再来一组（第二组 9），**最多两组** | 缺省 1，连拍上限 12 |
| 东西在动 | 点名 12 | 点名 12 并一起要 `sheet` |
| 间隔 | 100–2000 ms，缺省 200 | 50–5000 ms，缺省 120 |
| 清晰度 | 三档 307200 / 921600 / 2073600 像素（480p / 720p / 1080p） | 三档 262144 / 640000 / 1690000 像素 |
| 字节预算 | —（相机那一档就是上限） | 滑块 256–1024 KiB，打字最多 4096 KiB |
| 拼网格 | 设置页开关决定，本次调用可以用 `sheet` 覆盖 | 同上 |
| 谁点名张数 | 只有用户明确说了数才在调用里给 `frames` | 只有要"一段过程"时才给 `count` |

**两条路共用的一条纪律：量到的间隔才算数。** 一次抓帧本身要两三百毫秒，所以回执报的是量到的
`offsets`，而不是要的那个数 —— 模型拿它当时间轴，报"要的那个"就是一句假话。

## 模式那一条口径（顺带写在这里）

**视频模式里"屏幕"指镜头画面。** 那里的屏幕类工具（读屏 / 在屏上动手 / 起应用 / 抢相机）一律被拒 ——
判据在插件的 `cameraAndModeGate`（`host-plugin/index.mjs`），回执会说明"要么用 `lw_look` 取景，要么先
`lw_mode {mode:"phone"}` 切回手机模式，再在那一边截图"。要看手机自己那块屏就**切模式**，不在视频模式里
破例：一个模式一个口径才守得住。（识屏模式在批次 4 摘掉了 —— 手机自己那块屏归手机模式管，`screen`
那个名字会被明确拒掉。）
