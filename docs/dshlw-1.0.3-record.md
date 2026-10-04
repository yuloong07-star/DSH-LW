DSH-LW 1.0.3 开发计划
=====================

基线: `dshlw/main` = `e81cfde` (1.0.2 发布点 `a431c77` + 那条通知修复), versionCode 2 / versionName "1.0.2"
目标: versionCode 3 / versionName "1.0.3" (D1 已定)
主体: 先把「跑得起来」变成「用得下去」(草稿里那几处硬伤), 再补齐 1.0.2 欠下的四/五级能力

**补丁已经上传到远端** (D5 已定): `dshlw/main` 到 `e81cfde`, 另有一条 `dshlw/patch/scroll-and-shell` = `cf5db194`, 六个提交 (scroll 三个 + shell 三个)。所以批次 0.3 是**落地**而不是重写

**这份是计划, 也是执行回执**。每一批独立提交、独立验证、独立回退

## 进度 (2026-10-04 开工当天)

| 批次 | 状态 | 提交 |
| :-- | :-- | :-- |
| 0.1 本地对齐到 `e81cfde` | 完成 | 快进, 无提交 |
| 0.2 版本号 → 3 / 1.0.3 | 完成 | `9b8c524` |
| 0.3 落地滚动补丁 (挑三个提交) | 完成 | `575a6c3` `4d35ce5` `30d3741` |
| 0.4 AAPM 排查 | 完成 | 结论见第 5.1 节; 要动的那行等 D6 |
| 0 验收: `:app:compileDebugKotlin` | **通过** | 25 秒, `BUILD SUCCESSFUL`, 只有两条既有的 deprecation 警告 |
| 1.1 插件 schema (整包 UNSUPPORTED_SCHEMA) | 完成 | `487a2e8` |
| 1.2 pack 之后的自检 | 完成 | `487a2e8` (`tools/check-host-plugin.mjs` + `pack-host.mjs` 里那一步) |
| 1.3 第三方插件的 import 自检 | 完成 | `6d90a51` (pack-host 装完 `dsh-web-mobile` 之后 import 一次它的入口) |
| 1.4 OCR 模型进包 | 完成 | `ffb9553`; 模型本身在 `.gitignore` 里, 不进仓库但进 APK |
| 1.5 图片后端换成 sharp wasm32 | 完成 | `8d396ad` (替身删掉, `image-backend/README.md` 留史) |
| 1.6 报错指向下一步 | 完成 | `6d90a51` (建屏/无障碍/截图三条) |
| 1.7 `lw_launch` 之后自查窗口 | 完成 | `6d90a51` |
| 1.8 截图的落点与两份文件的清理口径 | 完成 | `6d90a51` (落点本来就在工作区, 缺的是"两份是一对"那句) |
| 1 验收: 出 APK + 装模拟器 | 完成 | 363.8 MB, 装 emulator-5554, 两个判据都过 (见下) |
| 3 四/五级 A (应用控制 / keepAwake / 组合键 / 截图分区连拍) | 完成 | `fb87459`; 又修了三处见下 |
| 4 四/五级 B (文件与媒体读写 / 拍照) | 完成 | `3ea6a8f`; 4.1 的形状改了, 见下 |
| 5 通知 (读数与取消 + 横幅) | 完成 | `e4cc04b`; 授权那条路与计划书写的**不一样**, 见下 |
| 6 事件订阅 `events/subscribe` | 完成 | 见下 (第三处"没照计划做"的地方) |
| 7 连拍做深 (原「录屏」, D3 已定: 不录屏) | 完成 | `2f47556` (第四处"没照计划做"的地方) |
| 9 文档与发版 | 待做 | |

### 批次 6 (2026-10-04, 在模拟器上逐条验过)

**这一批改的不是模型能做什么, 是它怎么读**: 按一下之后与其每 200ms 读一次无障碍树 (每次过 binder,
而且读到的是同一个界面), 不如先说清"我在等什么", 再等它发生。计划书里的 6.1 / 6.2 / 6.3 三条都落了,
形状上只有一处与计划书不同: **等待是轮询队列 (150ms 一次) 而不是让服务反向通知** —— 队列读的是内存,
而系统那边 `notificationTimeout=100` 已经把事件压到每秒十条上下, 让服务反向通知反而要引入一套线程协议

| 项 | 结果 |
| :-- | :-- |
| `op=list` (空) | `no subscriptions are live: start one with lw_events_subscribe` |
| `start` | 回看队列尾部几条 + `id e1, expires in 15m0s` + `the device's own buffer holds 18 of 200 events (0 dropped so far in this session)` |
| **另一块屏上的事** | 只订 `displayId 6` (虚拟屏) 再往那块屏起相机, `wait` **7.7 秒**拿到 `window com.android.camera2 "Remember photo locations?"` —— 而 display 0 上一直在刷的事件一条都没漏进来 |
| 什么都没发生 | 5.1s 与 25.1s 两次都如实回 "nothing matching happened … the screen did not change in that time", 并指到 `lw_ui` |
| 合并计数 | 答案里带着 `(6 changes collapsed into them, 0 dropped)` 与行尾的 `(x5)` / `(x2)` |
| `op=stop` | `stopped watching any display · …`; 再用那个 id 调 wait → `there is no subscription called e1 any more …` 并给出两条出路 |
| 那次 stop 再 stop | `there was no subscription called e1 any more` (不假装刚停了一个) |
| 屏那一列 | 订阅是"任意屏"时每一行都印 `display 0` / `display 6`; 指定了屏就不印 (那是废话) |
| 插件那一层 | 不带 `displayId` 也能调 (修复前后各试一次), `op=list` 不列临时订阅 |

四处值得记下来的:

1. **事件上没有 displayId**: `AccessibilityEvent` 只有 `windowId` (拿 `javap` 对着 `android.jar` 查过),
   所以屏是**反查**出来的 —— windowId 去 `windowsOnAllDisplays()` 里找, 结果缓存一秒; 查不到就是 -1,
   不猜 0 (0 是别人手里那台手机)
2. **刚出现的窗口会被那一秒的缓存挡住**, 于是"某个应用起来了"这条最要紧的事件反而报 `display unknown`
   (实测: 应用启动时那几条全是)。两处修: 没命中时用一个 200ms 的短间隔重试; 而**记的时候没查出来的,
   读的时候再试一次** (那一刻窗口表里可能还没有它)
3. **`DISPLAY_ID` 那个共用常量是 `required: true`**, 而"不给屏"恰恰是事件订阅的默认用法 —— 桥那一侧
   不校验 schema, 所以这个错只有插件层能抓到; 加了一个可省略的 `WATCHED_DISPLAY`
4. **常量写在 `TOOLS` 数组之后 = 整包死掉**: `const` 不提升, 而那张表是模块级的, 于是模块求值期抛
   `Cannot access 'WATCHED_DISPLAY' before initialization` —— 插件的自检当场报出来 (它存在的理由)


### 批次 7 (2026-10-04, 在模拟器上逐条验过)

**这一批没照计划书做, 而且改动比计划书小**: 原本的「录屏」被两条量出来的事实否掉了 —— 模型读不了视频
(`read_image` 只认 PNG / JPEG / WebP / GIF), 而"要真人点授权"是 **MediaProjection** 的性质而不是录屏的
性质 (特权进程以 shell 身份跑 `screenrecord --time-limit 2`, 落下 40,092 字节的 MP4, 不弹任何框)。所以这
一批做的是**连拍做深**: 上限 12 张、`intervalMs`、以及一张网格

| 项 | 结果 |
| :-- | :-- |
| 6 张每 500ms | `offsets [0, 501, 1003, 1504, 2005, 2506]` —— 每一档都落在要的 500 上 |
| 8 张每 400ms, 期间按 HOME 与最近任务 | 画面真的变了 (第 1 张 48,204 / 第 8 张 50,016 字节), 网格 **3 行 3 列 + 第 9 格留黑**, 顺序一眼读得出来: 应用 → 桌面 → 最近任务 |
| 网格的体积 | 8 张拼出来 142,459 字节, 4 张拼出来 44,102 字节 —— **一张网格比一张原图还小** (单张 48 KB), 而它顶掉 12 张图 |
| 网格自己卡在预算内 | 4 张 → 536x1192 (638,912 像素), 8 张 → 657x974, 都在 640,000 那个预算之内, 所以 `Picture.fit` 走的是"已经装得下"那条捷径 (给模型的与给人看的是同一份) |
| `count: 99` | 收到 **12** (`count: 12`, 12 行) |
| `intervalMs: 60000` | 收到 **5000** |
| `intervalMs: 50` (做不到) | 实测一档 **260ms 上下** (offsets 272 / 540 / 801 …), 而答案里写的是 "one every 50 ms **as far as this device keeps up**", **并且**多一句 "They are further apart than that in practice … the times below are what happened, not what was asked for" |
| 只要一张却要了网格 | `no grid was made: a grid needs at least two pictures and only 1 came back` |
| 插件那一层 (`lw-plugin-call.mjs`) | 三条都过: 12 张那答案是 12 行时间 + 那句说明; 网格那条把行列数与"用来看动起来的"都说清了 |

两处值得记下来的:

1. **诚实判据差点写反**: 第一版是"跨度 ≥ 要的跨度就说明跟得上", 而设备慢下来只会让跨度**更大** —— 那个
   判据永远成立, 于是 `intervalMs: 50` 实测 260ms 一档却一句不说。改成"跨度 ≤ 要的跨度 × 1.1"之后它才
   开口。**这是验出来的, 不是想出来的**
2. **单张那条路会吞掉网格的理由**: `sheet: true` 配 `count: 1` 时应用那一侧已经把理由写好了, 而单张那条
   formatter 不念它 —— 一个静默的空操作。补一句 `if (result.sheetError)` 才说出来

一条连带的: 12 张那一版里每一行都重复了同一句"图上的点乘 2.01", 现在它只说一次, 后面每一张只在**与第一张
不同**时才写自己那一句

### 批次 5 (2026-10-04, 在模拟器上逐条验过)

**这一批最值钱的产出不是"能读通知栏", 而是量出了那道授权的真实形状**: 无障碍那条路上总结的做法
(写 secure setting + 写完读回) **搬到通知上不成立**。直写 `enabled_notification_listeners` 之后:
值写进去了、`settings get` 读回来也在、设备留着 —— 而**系统根本不理它**, 服务不会被绑上, 系统那份
"用户设过"的名单里也没有它 (通知那一页会把这个应用列在 **Not allowed** 下面)。正解是
`cmd notification allow_listener` / `disallow_listener`, 也就是那块设置页上「允许」按钮走的同一条路

| 项 | 结果 |
| :-- | :-- |
| 没授权时 `lw_notifications op=list` | 被拒: "this app is not in the device's list of notification listeners. Turn it on in DSH-LW's own Settings -> 通知…" |
| `lw_probe` 的 notifications 块 | 授权前 `listed/running/granted` 三个都 false 且理由是"不在名单里"; 授权后三个都 true 且理由带横幅那一句 |
| 直写名单 (读出来改, 保留别人的两条) | 名单里有了, **而系统不认** —— `running: false`, 页面把它列在 Not allowed |
| 系统那页走一遍 (打开 DSH-LW → 开关 → 确认框 Allow) | 系统随后认了: `Allowed notification listeners` 里有它, 我们的 `running/granted` 都变 true |
| `cmd notification disallow_listener` / `allow_listener` | 双向都对: disallow 后 `running: false` (服务真解绑), allow 后 `running: true` |
| 设置页那个开关 (双向) | 关: 名单里少一个 + 日志 `disconnected` + probe `running: false` + 开关变灰; 开: 名单里回来 + `connected` + 开关点亮。**别人的两条监听一直在** |
| `op=list` 的格式 | 一行一条, 开头就是 key, 带应用 / 重要度与渠道 / 能不能清 / 标题正文 / 多久之前 |
| `op=cancel` 一个 key (别人的可清通知) | `cancelled the notification from com.google.android.apps.wellbeing`, 再 list 少了一条 |
| `op=cancel` 自己那条常驻 | 被拒并说明: "is an ongoing notification … those are the app's to take back rather than anyone else's: it was left alone" |
| `op=cancel` 一个包名 | 同一应用两条 (一条可清一条常驻) 分别处置: 清掉一条, 另一条留着并说明为什么 |
| `lw_notify` 的横幅 | appop deny → "The banner was asked for but this app may not use full-screen intents…"; 恢复 → "comes up as a banner over whatever is on the screen" |
| 渠道 | `lw-tools-high` `mImportance=4`, 旧的 `lw-tools` 已删 (实测查不到了) |
| 装机脚本 | 判定里多了 "listener listed" 一条; 命令为先、写名单为退路 |

四处值得记下来的:

1. **直写 secure setting 不够** (上面那段): 所以 `setNotificationListener` 先走 `cmd notification
   allow_listener`, 直写只当退路; 装机脚本同样
2. **首次授权要系统那个确认框**: 一个从没被允许过的应用, 命令与直写都可能不被采纳 —— 正解是设置页
   「通知」那一段的入口点进去打开它并点「允许」。所以那一段的说明里**直接写了横幅那句**, 而两个
   `ArrowPreference` 都带了"退到应用详情页"的回退 (那一页在个别 ROM 上没有接收者, 点击换来的崩溃
   最不该有)
3. **`StatusBarNotification.getRanking()` 不是公开 API**: 重要度与渠道要从 `currentRanking` 那个
   `RankingMap` 里点名取, 取不到就报"没说"而不是某个默认值
4. **模拟器上 `cmd notification post` 投不出通知**: 它打印 `posting: Notification(...)` 而系统那份
   `NotificationRecord` 列表里一条都没有 —— 所以验这个工具别拿它造数据, 用栏里本来就有的别人的通知
   (或 `lw_notify` 自己发的, 它 id 固定)


### 批次 4 (2026-10-04, 在模拟器上逐条验过)

**4.1 的形状与计划书不一样, 这是这一批唯一一处"没照计划做"**: 计划书写的是三个工具
(`lw_list_files` / `lw_read_file` / `lw_write_file`), 而开工前先核了一遍模型手上有什么 ——
dsh 自己就带着 `read` / `write` / `edit` / `glob` / `grep` / `bash` (`packages/fs/*` +
`packages/shell/*`), 也带着 `read_image`, 而工作区就是那套工具的家 (host 的 cwd 与 home 都在
那儿)。所以"在工作区里读写一个文件"模型本来就会做, 三个同名的工具只会让它在两个都行的选择
之间犹豫。落地成**一条 `lw_files`** (`list` / `read` / `write`), 只做 app 这一侧拿得到的三件
事: 手机怎么看这个文件 (媒体库有没有它、系统认的 mime、一张图多大)、一个不随会话目录漂移的
锚、以及写完顺手让系统看见。**围栏照计划书一个字没改**: 只认工作区里的路径, 越界一律拒

| 项 | 结果 |
| :-- | :-- |
| `lw_files` `list` | 12 张截图逐条给出大小 / `image/png` / `1080x2400` / `media library: listed` |
| `lw_files` `list` 里那个"应用没写过"的文件 | `pushed.png` → `media library: not listed` (如实, 因为这台设备给了 `READ_MEDIA_IMAGES`) |
| `lw_files` `write` | `wrote 72 characters … (a new file); read back the same 72 characters` |
| `lw_files` `write` 覆盖 | `(replaced what was there)` |
| `lw_files` `read` | 抬头是路径 + 大小 + 类型, 下面是正文 |
| `lw_files` `read` 一个 PNG | 被拒: 二进制 (前 4096 字节里有 NUL), 并指到 `read_image` / `lw_open_file` / `lw_share` |
| `lw_files` 越界 | `/sdcard/Download` 被拒, 理由里给出"该用会话自己的文件工具" |
| `lw_media_scan` | `pushed.png` 从"不在库里"变成 `content://media/external_primary/images/media/82`, 再 list 就是 `listed` |
| `lw_media_scan` 一个目录 | `notes/` 两个文件都进库 (`.txt` 也有 `…/file/84` 那种行) |
| `lw_media_scan` 相对路径 | 一开始只认绝对路径, 插件那一层试出 `notes/pic.svg` 回"没有这个文件" —— 已改成相对的按工作区算 |
| `lw_take_photo` 端到端 | 相机起在主屏 (`topResumedActivity=com.android.camera2/.CaptureActivity`), 按快门 + Done 之后 **40.8s** 落下 `1392x1856 / 74.0 KiB` 的 JPEG 并进库 |
| `lw_take_photo` 超时 (相机还开着) | 保住文件并说明它会写到哪; 随后在**同一个路径**上真拍成了 (那句"nothing was undone"是真的) |
| `lw_take_photo` 超时 (相机被关) | 空文件收掉, 并说明为什么这一次可以收 |
| `lw_take_photo` 权限闸 | 撤掉 `CAMERA` 后拒, 并说清"相机应用去拍, 而这个应用自己也得握着它" |
| 插件那一层 | 新加的 `tools/lw-plugin-call.mjs` 直接 `execute` 过一遍 (不花模型的钱) |

三处值得记下来的:

1. **`queryIntentActivities` 会骗人**: 清单的 `<queries>` 里只有 MAIN/LAUNCHER, 于是
   `IMAGE_CAPTURE` 的查询**空手而归而相机就在那儿** —— "这台设备不能拍照"会是一句假话。修法
   两条一起: 加一条 `IMAGE_CAPTURE` 的 `<queries>` 声明 (窄声明, 不是 `QUERY_ALL_PACKAGES`),
   **并且**查不到也照样去 `startActivity`
2. **媒体库的"查不到"有两层**: 查询抛异常是一层, 而**查询不抛异常、只回这个应用自己贡献过的
   行**是另一层 —— 后者会把别人的照片安安静静地说成"不在库里"。所以答"不在"之前先问一句
   "这一类文件我看得全吗" (`READ_MEDIA_*` 是按类型分的, 所有文件访问才一次盖掉)
3. **这套相机按了快门不算完**: 它停在审核屏, 文件要等人点 `Done` 才写下来 (实测 45 秒里有
   40.8 秒花在那一下上), 所以默认那 20 秒会正好卡在审核屏上超时。现在相机还在屏上就多等一
   次, 而**超时也不删那个文件** —— 人再点一下 `Done`, 写的就是它


### 批次 3 (2026-10-04, 在模拟器上逐条验过)

| 项 | 结果 |
| :-- | :-- |
| `lw_keep_awake` | on → "holding it…"; status → "holding a partial wake lock (held for 0s)"; off → "let go…" |
| `lw_key_combo` | `CTRL_LEFT + A` → `held KEYCODE_CTRL_LEFT + KEYCODE_A`; **不存在的键被拒**, 并把知道的键名列出来 |
| `lw_app_control` `enable` | `Package com.android.settings new state: enabled` |
| `lw_app_control` `setHome` | `made …NexusLauncherActivity the home app: Success` |
| `lw_intent` `openUrl` | `opened https://example.com: Starting: Intent { act=VIEW dat=… }` |
| `lw_intent` `intent` | 只给动作 / 只给组件都成功; **两样都不给被拒** ("an intent needs an action or a component") |
| `lw_screenshot` 分区 | `picture=248x298 scale=2.016 left=200 top=400` —— 裁剪生效, 而且答案里带原点 |
| `lw_screenshot` 连拍 | `count=3`, 三个文件 `screen-0-1/2/3.model.png` |
| `lw_type` 带坐标 | `via=field`, 树里的 `EditText` 读回文字 (见下, 这一条修过一次才成) |

**这一批自己抓到的三处 (都已修)**:

1. **`intent` 整条是死的** (`4dea2fa`): `appControl` 一进来就读 `package`, 而 `string()` 缺字段直接抛 ——
   不组件件的 intent 死在 "has to name package", 带组件的死在下一个必填字段
2. **"先按再打字"有竞态** (`2173e20`): 按下去常常是打开新页面 (设置搜索框就是), 而字段要等页面画出来
   才在树里。第一版于是退回按键 —— 按键落进空气, 答案却只说"打了几个字"。现在等最多 1.5 秒找字段
3. **分区截图的两份文件不是一对**: 只要一块时, 全尺寸那份是**整屏**, 把它说成"孪生"会让人以为删一张
   就够。现在分开措辞

### 模拟器那一轮 (2026-10-04, emulator-5554 / Android 16)

做的是"装上去, 让它自己说实话", 结果抓到两个真问题:

| 项 | 结果 |
| :-- | :-- |
| `tools/lw-install.ps1 -Serial emulator-5554` | 装上, 无障碍当场写回并读回 (`writes accepted: yes` / `component listed: yes` / `service bound: yes`) |
| host 就绪行 | `DshHost: dsh web: http://127.0.0.1:3080/?token=…` |
| GUI 渲染 | `DshWebView: shell {…"rootChildren":1…"vh":839.2…}` |
| `probe` 的无障碍段 | 修好之后: `listed: true` / `masterSwitch: true` / `running: true` / `healthy: true`; `advancedProtection: null` (API 36 问不到, 如实说); `writeChannel: refused` |
| `ui` (读主屏树) | 正常, 3 个节点 (WebView 那一棵) |
| `ocr` | **按设计失败**: 抓屏走特权通道, 模拟器上应用拿不到 root/Shizuku。回的那句正是 1.6 改过的措辞 |
| 截图上屏 | `虚拟屏:` 那一段直接把新的通道报错显示出来了 (1.6a 的效果看得见) |

**抓到的两个问题 (都已修并提交)**:

1. **应用读 secure settings 一直是空的** (`69d02b0`): `/system/bin/settings` 是一条 **shell 命令**,
   Android 14 起对非 shell 的 uid 直接 `SecurityException: getCurrentUser() ... requires
   INTERACT_ACROSS_USERS`。`get` 与 `put` 都被拒 —— 于是设置页与 `lw_probe` 把"组件在列表里"说成
   "不在"、把主开关说成关着, 而设备上明明都是好的。现在读走 ContentResolver (应用里读
   `Settings.System` / `Settings.Global` 本来就是这条路), 命令留给特权进程当兜底
2. **写入探针只有真假两态** (`69d02b0`): "设备丢弃写入"与"应用根本不许写"是两回事, 后者不是设备的
   毛病, 而是这条写入本该由特权进程做。现在分成 `open / discarded / refused` 三种, 报错也跟着分

还有一处是我自己写错又立刻改掉的: 缩放那份的后缀是 `.model` 而不是 `-small` (`965b008`)。

**这台机器上验不了的 (要真机)**: 原来列了五条, 而用户指出**模拟器里装了 Shizuku** —— 把 server 拉起来、
给应用授权之后, 四条当场验掉了 (含批次的 `lw_scroll`)。**现在只剩一条要真机: JPEG 进模型**, 因为它要一次
真的模型请求 (设备上的凭据 + 一次调用), 而那件事在手机上做才作数。真机上还该点的: 触摸手感、以及那台
vivo 上特有的重装窗口行为

#### 特权通道第二轮 (Shizuku 装上之后)

拉起方式: `pm path moe.shizuku.privileged.api` 找到 APK 目录 → 跑它的 `lib/x86_64/libshizuku.so`
(这台是 x86_64, 与记忆里那条 arm64 路径不同) → `pm grant <pkg> moe.shizuku.manager.permission.API_V23`。

| 项 | 结果 |
| :-- | :-- |
| 通道 | `probe.channel` = `connected: true, backend: shizuku, uid: 0, pid: 5614, version: 13` |
| `ocr` | **出字了**: 24 行, 全部命中 (`Search Settings` / `Network & internet` / `Connected devices` …), `captureMs=203 detMs=191 recMs=22` |
| `launch` + 1.7 自查 | `started=true code=0 windowOnDisplay=true` —— 自查确实在查窗口 |
| `screenshot` | 落进工作区: `…/DSH/screenshots/screen-0.png` (103 KB) + `screen-0.model.png` (50 KB), 536x1192, scale 2.01; 修好之后答案里 `path` 与 `fullPath` 两份都给 |
| **`scroll` (批次 2)** | **验通**: `outcome=scrolled, scrolled=2`, 原话 `scrolled displayId 0 forward 2 screenful(s) through ScrollView: read the screen again, the rows have moved` —— 这就是那份补丁的 `ACTION_SCROLL_FORWARD` 路, 在真的列表上滚动了 |
| `create` + 立刻截图 | 见下, 措辞改过 |
| `release` 之后再截图 | `{"ok":false,"error":"…was closed by an agent calling lw_screen_release … do not make a replacement without asking"}` |
| `scroll` (主屏没有可滚容器时) | 回 `nothing on display 0 scrolls` (诚实的"没滚成", 不是假装成功) |

**这一轮又抓到三处, 其中两处是我自己写错的** (都已修):

1. `LwCapture` 说"合成器名单里没有这个屏名"就等于"屏没了"(`4dd9cb7`)。实测: 刚建出来的屏**不在**那份
   名单里 (没有 surface 附着), 于是刚建的屏被说成"已经没了"。现在两种可能都写出来
2. 截图答案里的 `path` **是给模型那份 (缩过的)**, 而我照它去推全尺寸那份 → `screen-0.model.model.png`,
   一个从来没有过的文件名 (`965b008` 与后来的 `Shot` 那次)。现在由应用把**两份路径都给出来**,
   插件不再做字符串手术
3. `screen` 这个调用会把一句**陈旧的** `VirtualScreen.lastError` 当成当下的错误报出来 (通道刚连上时
   就发生过): 通道明明通了, 答案里却挂着"需要 root 或 Shizuku"

还有一个观察, **只记录不改**: 模拟器上 `ocrProbe` 报 `soc: ranchu / abi: x86_64` 而 `backend: NPU` ——
这台机器没有 HTP, 那个标签在这儿不可信 (真机 SM8550 上才有意义)。它不构成一个可判定的缺陷, 所以留到
真机上看

### D6 / D7 的回执

| 决定 | 落地 | 提交 |
| :-- | :-- | :-- |
| D6 加 `isAccessibilityTool="true"` | `res/xml/lw_accessibility.xml` 加了那一行, 并把 AAPM 状态读成第七件事实 (API 37 以下回 `null`, 而不是 `false`) | `b84dfcb` |
| D7 从 HuggingFace 拉模型 | det 1,755,289 / rec 4,449,685 / 词表 6904 字; 顺带修了构建脚本 (默认路径写死 `B:\`, 且漏产词表) | `ffb9553` |

1.1 的证据 (那条错误是真的, 不是抄草稿):

```
$ node tools/check-host-plugin.mjs
插件加载失败: unsupported JSON schema: parameters.paths.items.additionalProperties must be explicitly true or false
退出码: 1
```

补上 `additionalProperties: false` 之后: `39 个工具`, 退出码 0。规则出自 dsh 自己的
`packages/core/tools/src/schema.ts` 的 `ObjectValueSchemaSpec` —— 那个键是**必填**, 只有参数根是隐式
开放的, 这一条以前只写在草稿里, 现在是本机可复现的判据


0 · 这一版的依据
----------------

三份输入, 口径以第一份为主:

| 来源 | 是什么 |
| :-- | :-- |
| GitHub 草稿 | repo `yuloong07-star/DSH-LW` 的 draft release (id `402942831`, 标题 `DSH-LW 1.1.0 (开发中)`, 未打 tag)。内容 = 真机上用下来踩的 17 条坎 + 8 条建议 + 已经写好的补丁 + 还没验的 |
| 1.0.2 的承诺 | `D:\apk\DSH-LW-1.0.2-版本目标.txt` 第四节「不做的事 (1.0.3)」, 这是上一版对着用户写的欠条 |
| 代码现状 | 本仓库实测 (见第 1 节), 用来判断哪些要新写、哪些已经有补丁可以落地 |

草稿的定位写在它自己最后一行: 「这一稿是草稿: 版本号与内容都可以改, 发版前按实际改了什么删减」。所以版本号那一处冲突 (它叫 1.1.0, 用户要 1.0.3) 按用户口径走, 见第 5 节的 D1


1 · 基线事实 (全部实测, 不是推断)
----------------------------------

| # | 事实 | 证据 |
| :-- | :-- | :-- |
| 1 | 本地 `main` 停在 1.0.2 发布点, 远端已经往前走了一个提交 | 本地 `git log --oneline -1` = `a431c77`; `dshlw/main` = `e81cfde`; `app/build.gradle.kts:88-89` = `versionCode 2` / `versionName "1.0.2"` |
| 2 | 草稿里的补丁**现在都在远端**, 但混在同一条分支上: `e81cfde` (通知) 已在 `main`, `patch/scroll-and-shell` 里 `a3c3072` / `c2907c0` / `4673c1b` 是滚动, `a3f52e0` / `1ac2f3b` / `cf5db19` 是 shell | `git ls-remote --heads dshlw` + `git log --oneline e81cfde..dshlw/patch/scroll-and-shell`; 整支 diff = 5 文件 / +232 行 |
| 3 | 插件里 `additionalProperties` 出现 **0 次** | `Select-String host-plugin\index.mjs` 计数 = 0 (草稿说这是让整包 `UNSUPPORTED_SCHEMA`、一个 `lw_*` 都没有的那条) |
| 4 | 无障碍服务**没有**滚动动作, 也没有 `dispatchGesture` | `channel/LwAccessibility.kt` 里只有 `ACTION_CLICK` (:210) / `ACTION_LONG_CLICK` (:208) / `ACTION_SET_TEXT` (:270) / `ACTION_SET_SELECTION` (:309) |
| 5 | APK 里**没有** OCR 模型 | `app/src/main/assets` 目录不存在 (草稿第 5 条: `files/ocr` 空、`det.onnx` 不在 assets) |
| 6 | `sharp` 还是 PNG-only 替身 | `image-backend/sharp/package.json` = `0.0.0-littlewhale`, 描述里自己写着 "enough of its API ... to verify a PNG" |
| 7 | 通知走 `IMPORTANCE_DEFAULT`, 点通知没带 `NEW_TASK` | `tool/LwNotify.kt:94-98` (`NotificationChannel`), `:64-68` (`PendingIntent`, 只有 `FLAG_IMMUTABLE`) |
| 8 | 清单已经替 1.0.3 预埋了两条 | `AndroidManifest.xml` 里 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 注释明写 "(1.0.3)", `REQUEST_INSTALL_PACKAGES` / `DELETE_PACKAGES` 同理 |
| 9 | `lw_app_control` 只到"列"和"起" | `channel/LwApps.kt` 只有 `launchable()` / `resolve()` |
| 10 | 特权白名单 10 条, **没有 shell** | `channel/LwSystemCommandTable.kt` 的 `entries` = forceStop / clearData / uninstall / install / airplane / data / wifi / bluetooth / wake / sleep |
| 11 | 无障碍事件掩码已经是草稿想要的那四个 | `res/xml/lw_accessibility.xml:7` = `typeWindowStateChanged|typeWindowContentChanged|typeViewFocused|typeViewScrolled`; 该文件**没有** `android:isAccessibilityTool` |
| 12 | 1.0.2 的工具都在, 共 40 个 `lw_*` 名字 | `host-plugin/index.mjs` (74921 字节) 提取去重 |

**结论 (对执行方式的影响)**: 第 2 条现在是一个**挑选**的问题, 不是"东西在哪"的问题 —— 补丁都在, 但滚动与 shell 同支, 只拿滚动那三个提交 (`a3c3072` / `c2907c0` / `4673c1b`), 整支 merge 会把 D2 已否决的 shell 一起带进来。第 3/4/5/6 条是纯新增, 与补丁无关


2 · 范围
--------

进来的:

- 草稿的 8 条建议里, 不涉及安全取舍的那 7 条 (滚动 / OCR 进包 / sharp wasm32 / 报错指向下一步 / launch 自查窗口 / 插件自检 / 截图落工作区)
- 1.0.2 欠条里承诺的四/五级能力: 应用控制其余几条、通知栏读取与取消、文件与媒体、keepAwake、组合键、事件订阅、截图分区与连拍 (「录屏」这一条**换成连拍做深**, 理由见批次 7 —— 它欠的是一个模型用不上的东西)
- 通知横幅 (草稿建议 9), 因为它是"通知这条能力到底成不成立"的判据

不进来的 (1.0.3 边界, 写死在这里免得中途膨胀):

- 通讯录 / 短信 / 通话记录 / 日历的实际功能 —— 清单里声明着, 这一版仍然没有工具读它们
- 硬停刹车 (`exec.agent.cancel`), 主屏仍然只有软停 + 触摸刹车
- 端侧 OCR 的三件尾巴 (rec 批量重导 / ORT 自己导 ctx / det 换非正方形输入)
- `lw_shell` 默认不做, 见第 5 节 D2
- 导出走页面内 `blob:` 的 JS 桥、jniLibs 挂 Release、体积裁剪


3 · 批次总表
------------

| 批次 | 内容 | 依赖 | 验在哪 | 回退 |
| :-- | :-- | :-- | :-- | :-- |
| 0 | 基线对齐 / 版本号 / 落地补丁 / AAPM 排查 | 无 | 编译 | 单提交 revert |
| 1 | **先修「一个工具都用不上」的 8 处** | 0 | 模拟器 | 各自独立提交 |
| 2 | `lw_scroll` 无障碍滚动 (补丁落地 + 编译验证) | 1 | 模拟器 + 真机手感 | revert 三个提交 |
| 3 | 四/五级 A: 应用控制 / keepAwake / 组合键 / 截图分区连拍 | 1 | 模拟器 | 按能力拆提交 |
| 4 | 四/五级 B: 文件与媒体读写 / 拍照 | 3 | 模拟器 | 同上 |
| 5 | 通知: 读数与取消 + 横幅弹窗 | 3 | 模拟器 + 真机 | 同上 |
| 6 | **事件订阅 `events/subscribe`** (最大一块) | 2, 5 | 模拟器 | 同上 |
| 7 | 连拍做深 (原「录屏」, 见批次 7) | 0, 5 | 模拟器 | 同上 |
| 8 | ~~`lw_shell`~~ **已否决 (D2), 这一版不做** | — | — | 分支上留着不删 |
| 9 | 文档 / 打包 / Release | 全部 | 装机 | 打 tag 前都可退 |

**顺序的理由**: 批次 1 必须最先, 因为它修的是"插件整包加载失败"和"OCR 没有模型"—— 这两件事不修, 后面每一批的验收都在一个不完整的环境里做。批次 6 放最后, 因为订阅的事件流要依赖前面树读取与滚动都稳定了才有意义


4 · 每批的详细内容
------------------

### 批次 0 · 基线对齐与落地补丁

| 项 | 动作 | 落点 |
| :-- | :-- | :-- |
| 0.1 | 本地 `main` 对齐到 `e81cfde` (快进一个提交, 就是那条通知修复), 从它开 1.0.3 | `dshlw/main` |
| 0.2 | `versionCode 2 → 3`, `versionName "1.0.2" → "1.0.3"` | `app/build.gradle.kts:88-89` |
| 0.3 | 只挑滚动那三个提交落地: `git cherry-pick a3c3072 c2907c0 4673c1b` (改的是 `LwAccessibility.kt` / `PrivilegedBridge.kt` / `host-plugin/index.mjs`)。**不整支 merge** —— 同支上还有 shell 那三个, 而 D2 已否决 | `dshlw/patch/scroll-and-shell` |
| 0.4 | AAPM 排查: `lw_accessibility.xml` 当前没有 `android:isAccessibilityTool`, targetSdk 是 37 —— 查 Android 17 对未声明者的收紧是否真的存在 | `res/xml/lw_accessibility.xml` |

验收: `.\gradlew.bat :app:compileDebugKotlin` 过 (草稿自己写明: **那六个提交的 Kotlin 编译从来没验过**, 只在手机上做过括号平衡与插入点核对); 插件能被 dsh 加载 (工具数 = 40 + 新增)

补丁的形态已经看过, 是干净的: 滚动那三个提交只加不减 (`+111` 行无障碍, `+31` 行桥, `+60` 行插件), 桥那条路由带了 `requireAcceptsControl` 与 `requireUserNotDriving` 两道既有闸, 上限 `MAX_SCROLL_TIMES = 10` 两侧对齐

### 批次 1 · 先修「一个工具都用不上」的 8 处

草稿的第一痛不是"某个功能不好用", 而是"整台设备上所有工具消失"。这一批照那个优先级排:

| 项 | 动作 | 落点 |
| :-- | :-- | :-- |
| 1.1 | 每个嵌套 `items` 补 `additionalProperties: false` —— 重点是 `lw_gesture` 的 `paths.items` 与 `paths[].points.items` | `host-plugin/index.mjs` (目前全文件 0 次) |
| 1.2 | pack 之后加一步"import 一次插件, 把注册到的工具名念出来", 少一个就非零退出 | `tools/pack-host.mjs` |
| 1.3 | 跨包依赖写进 profile 声明 (草稿第 14 条: `dsh-automation` 漏 `rrule` 导致定时任务全停) | `host-plugin/package.json` + pack 侧 |
| 1.4 | OCR 模型进 APK: `app/src/main/assets/ocr/` 带 det 与 rec (fp16 那两份, 取值见 `docs/step8-record.md`); 缺模型时 `lw_ocr` 回「这台机器没有 OCR 模型, 改用截图」, 不再报 `tools/ocr/build-models.ps1` 的路径 | 新增 `assets/ocr/`, `channel/LwOcr.kt` |
| 1.5 | `image-backend/sharp` 换成官方 `sharp 0.35.5` + `@img/sharp-wasm32 0.35.5` + `@emnapi/runtime 1.11.3`, linux-arm64 找不到原生 binding 时自动走 wasm32 | `image-backend/sharp/` |
| 1.6 | 报错指向下一步: 建屏失败 → 去应用里授权设备能力; 无障碍不在列表 → `lw-install.ps1` 那条命令; 截图无帧 → 分清"屏在而无帧"与"屏已不在" | `channel/LwVirtualDisplay.kt`, `ui/SettingsScreen.kt`, `channel/LwCapture.kt` |
| 1.7 | `lw_launch` 之后自查一次窗口, 目标屏没有窗口就自动新建屏重投, 至少回一句"这个应用已经在跑, 它的窗口不在这块屏上" | `channel/PrivilegedBridge.kt` (`launch` 分支) + 工具描述 |
| 1.8 | 截图落到工作区 (现在是 `/storage/emulated/0/DSH/screenshots/`, 工作区是 `.../Android/media/<pkg>/DSH`), 全尺寸与缩放两份合成一条清理口径 | `channel/LwCapture.kt` |

验收 (模拟器 emulator-5554): 插件自检通过且工具数对得上; `lw_ocr` 在模型存在时真出字; 相册里的 JPG 能进模型 (草稿实测 4608x3456 约 2s); 截图与 `lw_ui_dump` 都落在工作区同一处

**风险**: 1.4 与 1.5 都加体积 (模型 + wasm32), 而 APK 已经约 350 MB。这一批结束时量一次, 定一个上限 (建议 ≤ 400 MB), 超了就回到"模型不留包内、首次用时下载"那条路

### 批次 2 · `lw_scroll`

补丁已经写好 (批次 0.3 落地), 这一批是**把它验到能用**:

| 项 | 动作 | 落点 |
| :-- | :-- | :-- |
| 2.1 | 编译验证: `LwAccessibility.scroll(...)` / `UiScroll` / `areaOf` / `scrollTarget` / `MAX_SCROLL_TIMES` | `channel/LwAccessibility.kt` (+111) |
| 2.2 | 桥路验证: `"scroll"` 分支的两道闸与 `direction` / `text` / `times` 解析 | `channel/PrivilegedBridge.kt` (+31) |
| 2.3 | 工具描述与参数验证: `lw_scroll` 的 `enum: forward/backward` 与 `times` 上界 | `host-plugin/index.mjs` (+60, 与 `lw_shell` 同处一块, 挑提交时要看清) |

验: 模拟器上滚时钟的闹钟列表与日历日程列表 (草稿里滚不动的那两处); 真机手感由用户点

**这一批的意义**: 草稿把它排第一是有理由的 —— 走 `ACTION_SCROLL_FORWARD / BACKWARD` 不注入触摸, 比现有的 `lw_swipe` 稳, 解开的是整片"长列表读不到、表单滚不到"。它同时绕开草稿第 1 条里那条更麻烦的事实: 节点树带 `scrollable` 标记时**桥只是把它带出来显示**, 现在才真的用上

**风险**: 草稿自己列的"没验的"第一条就是它 —— 无障碍滚动动作在那台 ROM 上是不是真吃得动, 只有装机才知道。模拟器上先过, 真机如果 `refused`, 那说明控件不吃滚动动作, 届时退回"分区截图 + 坐标拖动"而不是继续加码无障碍

### 批次 3 · 四/五级 A

| 项 | 动作 |
| :-- | :-- |
| 3.1 | `lw_app_control` 加 `enable/disable` / `setDefaultApp` / `openUrl` / 通用 `intent`; 破坏性那四条继续走 `ui/DestructiveConfirm` (100 秒没人点就不做) |
| 3.2 | `lw_keep_awake`: 显式工具 + 开关 (`WAKE_LOCK` 已在清单里, 现在就能实现) |
| 3.3 | 组合键 `lw_key_combo` 与 `lw_type` 的 `pasteAt` |
| 3.4 | `lw_screenshot` 加分区 (`region`) 与连拍 |

验收: 模拟器上逐条走过; 3.1 里"禁用别的应用"这类动作在模拟器上没有可复现的副作用评估, 只验"确认框出现 + 拒绝时不执行"

### 批次 4 · 四/五级 B

| 项 | 动作 |
| :-- | :-- |
| 4.1 | `lw_list_files` / `lw_read_file` / `lw_write_file`: **限定工作区内**, 越界一律拒 (与 dsh 自己的 fs 策略对齐, 不是"安卓层面隔离") |
| 4.2 | `lw_media_scan`: 写完之后让媒体库看见 |
| 4.3 | `lw_take_photo`: 走 intent + `FileProvider` (不引 CameraX), 先过 `CAMERA` 权限闸 |

### 批次 5 · 通知的读数与横幅

| 项 | 动作 |
| :-- | :-- |
| 5.1 | `NotificationListenerService` + 设置页一条"通知使用权"入口 (系统不接受运行时申请) + 授权状态读回 |
| 5.2 | `lw_notifications`: 列出与取消 |
| 5.3 | 横幅: **新建**一个 `IMPORTANCE_HIGH` 渠道 (必须换 channel id, 顺手 `deleteNotificationChannel` 掉旧的 `lw-tools` —— 渠道重要性创建之后应用改不动), 加 `setFullScreenIntent` (Android 14 起 `USE_FULL_SCREEN_INTENT` 对非闹钟/通话类默认不给, 要人手动授权) |

### 批次 6 · 事件订阅 `events/subscribe`

草稿说这是"那一版最大的一块", 目标是把 agent 从**轮询**变成**被通知**。

| 项 | 动作 |
| :-- | :-- |
| 6.1 | 掩码就用 `lw_accessibility.xml` 现在那四个 (`typeWindowStateChanged / typeWindowContentChanged / typeViewFocused / typeViewScrolled`, 不用 `typeAllMask`), 服务侧把事件投进一条有界队列 |
| 6.2 | 桥加 `subscribe` / `unsubscribe`, 带屏过滤与超时 |
| 6.3 | 工具 `lw_events_subscribe` / `lw_events_wait`: 等到某类事件或超时, 回一句"发生了什么"而不是让模型自己反复 `lw_ui` |

验收: 模拟器上开订阅, 在另一块屏里切页面, 事件能出来; 队列不会无限涨 (有界 + 丢弃计数)

### 批次 7 · 连拍做深 (原「录屏」, 改过)

**这一批原本是录屏 (MediaProjection), 开工前先量了一轮, 然后改了做法 (D3 已定)**。两条事实合起来否掉了原方案:

1. **模型看不见视频**: dsh 的 `read_image` 只认 PNG / JPEG / WebP / GIF (源码里就是这句), 所以一段 MP4 对
   模型是一条**它打不开的路径** —— 要用得上, 中间得再抽帧, 而抽出来那一帧就是 PNG, 也就是把录屏绕回
   截图。原方案真正买到的是"人手里多一段能回看的记录", 不是"模型看得更好"
2. **那套授权其实可以绕开**: 录屏要真人点框是 **MediaProjection** 的性质, 不是录屏的性质 —— 应用本来就
   在用 shell 身份跑 `screencap`, 同一个身份下 `screenrecord` 直接可用 (模拟器实测: 以 shell uid 跑
   `--time-limit 2`, 落下 40,092 字节的 MP4, 不弹任何框; `--help` 里也有 `--display-id`, 与我们截图那套
   合成器 id 是同一个东西)。所以 D3 那个"与审批全放行冲突"的死结根本不存在

| 项 | 动作 |
| :-- | :-- |
| 7.1 | `lw_screenshot` 的 `count` 上限 5 → 12: 现在"一段过程"只有 480ms 的窗口 (5 张 × 120ms) |
| 7.2 | 新增 `intervalMs` (50..5000, 默认 120): **墙钟上两张之间隔多久**, 不是"拍完再歇多久" —— 后者会让真实间隔随设备忙闲漂移, 而答案报的必须是量到的真实间隔 |
| 7.3 | 新增 `sheet`: 把这几张拼成一张网格 (应用侧 `VirtualScreen.contactSheet`), 一次读图看完整段。**它用来看动起来的, 不用来量坐标读小字** —— 每一格都是小副本 |

验收: 模拟器上拍 6 张, 每张的时间与落点都对得上; 间隔做不到时报的是量到的数而不是要的那个数; 网格的行列数与顺序都对

**录屏 (无论哪条路) 留到以后**, 真要做得先回答"给谁看": 给人看就做成设置页里人手动按的一个记录 (与模型解耦, 也不占第 49 个工具), 给模型看就得先解决抽帧那一步

### 批次 8 · `lw_shell` (已否决, 不做)

**D2 已定: 这一版不做**。草稿的规格是白名单加一条 `/system/bin/sh -c` (参数最多 4 个、每条 512 字、有超时、只回输出尾 20 行), 而它**已经写好了** —— 就在 `patch/scroll-and-shell` 的后三个提交里 (`a3f52e0` / `1ac2f3b` / `cf5db19`, 改 `LwSystemCommandTable.kt` + `LwSystemCommand.kt` + 插件)。

处置:

- 那三个提交**留在分支上不删**, 也不合进 1.0.3
- 以后要开, 一条命令就够: `git cherry-pick a3f52e0 1ac2f3b cf5db19`
- 开的时候那句代价必须一起写进 Release 说明 (草稿原话): 通道 token 在手即等于 shell 身份, 这一条是设计上刻意留着的那道墙
- 附带的一条: 分了这一支之后, **批次 0.3 的 cherry-pick 范围就更要写清楚**, 因为 `lw_scroll` 与 `lw_shell` 在 `index.mjs` 里是挨着加的两块

### 批次 9 · 文档、打包与发布

| 项 | 动作 |
| :-- | :-- |
| 9.1 | `README.md` 的能力与已知问题按实际改了什么改; `AGENTS.md` 的工具清单同步 |
| 9.2 | 这一版的目标文档收一份进 `docs/` 当执行记录 (含实测数字) |
| 9.3 | Release notes (开头句沿用定稿: 「本应用围绕 DSHLW 应用基于 LittleWhale 开发, 部分功能与特点取自 DSHA。」) |
| 9.4 | `:app:assembleDebug` → 哈希 → tag → Release (草稿的"1.1.0"名号按 D1) |
| 9.5 | 装机: `tools/lw-install.ps1` **紧接着装完就跑** (重装会踢掉无障碍, 写入只在那段短窗口里被接受) |


5 · 决策闸
----------

已定的 (2026-10-04):

| # | 问题 | 结论 |
| :-- | :-- | :-- |
| D1 | 版本号: 草稿标题是 1.1.0, 用户要 1.0.3 | **已定: 1.0.3 / versionCode 3**。草稿那篇正文改个标题就可以留给 1.1.0 |
| D2 | `lw_shell` 开不开 | **已定: 1.0.3 不开**, 批次 8 整批不做; 补丁留在分支上, 以后一条 cherry-pick 就够 |
| D5 | 草稿补丁从哪来 | **已定: 用户已经让他推上去了**。`dshlw/main` = `e81cfde`, `patch/scroll-and-shell` = `cf5db194`; 落地方式是挑提交 (见批次 0.3), 不是重写 |

已定的 (2026-10-04, 批次 5 开工时):

| # | 问题 | 结论 |
| :-- | :-- | :-- |
| D4 | 通知横幅要不要再往前走一步 (自绘悬浮小窗, 要 `SYSTEM_ALERT_WINDOW`) | **已定: 不做悬浮小窗**, 只做"高重要度渠道 + 全屏 intent"。理由照旧: 它多一个敏感权限, 且与主屏那道刹车的关系要重新定义一次 |

还等你拍板的 (到对应批次再问一次也行):

| # | 问题 | 我的建议 |
| :-- | :-- | :-- |
| D3 | 录屏要真人点授权, 与"审批全放行、模型动手时应用在后台"冲突 | **已定 (批次 7 开工时): 不录屏, 这一批改成连拍做深**。两条理由: 模型看不了视频 (`read_image` 只认那四种图), 而"要人点授权"是 MediaProjection 的性质不是录屏的性质 (特权进程跑 `screenrecord` 不需要任何授权, 实测)。真要做录屏时, 它是"给人看"的能力, 该做成设置页里手动按的一个记录 |
| D6 | AAPM 那条要不要用 `isAccessibilityTool="true"` 换豁免 (见下) | 建议加: 平台读的就是这个标志, 小米的 Android 17 适配指南也是这一条。代价要写进文档 —— 它是一句"本应用是无障碍工具"的声明, 而本应用是 agent 工具, 侧载没事, 上 Google Play 会有审核问题 |
| D7 | OCR 模型从哪来 (1.4 卡在这) | 从 HuggingFace 拉 PP-OCRv6 tiny 的 det + rec (约 6 MB), 用仓库里的 `tools/ocr/rewrite_onnx.py` 钉 shape、`extract_dict.py` 取词表。**不需要** QAIRT SDK 那 1.7 GB —— QNN 是设备上 JIT 编译的, 那 70 MB 的 `libQnnHtpPrepare.so` 已经在包里 |


5.1 · AAPM 那条查清了 (批次 0.4 的结论)
---------------------------------------

- **它是什么**: Android 17 的 Advanced Protection Mode (AAPM, 高级保护模式), **用户自己开**的一个模式, 不能由 IT 或 API 集中开关
- **它做什么**: 开启后, 系统的 `AccessibilityService` 访问**只留给被标为无障碍工具的应用**; 已经拿到权限的非无障碍应用会被**自动撤销**, 而且在模式开着期间用户也没法手动授予
- **豁免怎么拿**: 在服务元数据里声明 `android:isAccessibilityTool="true"` —— Google 官方支持论坛的原话是 "Apps must carry the `isAccessibilityTool="true"` flag to bypass this restriction", 小米的 Android 17 适配指南给的也正是这一行
- **对我们什么时候生效**: 只有"运行在 Android 17 设备上 **且** 用户开了 AAPM"这两个条件同时成立。今天的两台设备都是 Android 16, 所以**现在不会发作**; 但 targetSdk 已经是 37, 这个包会先于设备到达那一天
- **要动的文件**: `app/src/main/res/xml/lw_accessibility.xml` (现在这个文件里没有这个属性)。**要不要加是 D6**
- **顺带该做的一件事** (与标志无关): 用 `AdvancedProtectionManager.isAdvancedProtectionEnabled` 读一下状态, 在设置页与 `lw_probe` 里直说"用户开了高级保护模式, 无障碍在这个模式下拿不到", 而不是继续把六件事实并列 —— 含糊的提示误导过一次, 这条与 1.6 是同一个主题

来源: [Android 高级保护模式 (官方文档)](https://developer.android.google.cn/privacy-and-security/advanced-protection-mode), [Google 支持论坛线程](https://support.google.com/android/thread/445201968/accessibility-services-are-broken-with-advanced-protection), [The Hacker News 报道](https://thehackernews.com/2026/10/android-17-advanced-protection-locks.html), [小米澎湃 OS Android 17 适配指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2297)


6 · 风险
--------

| 风险 | 说明 | 处置 |
| :-- | :-- | :-- |
| 插件整包被判 `UNSUPPORTED_SCHEMA` | 一处嵌套 schema 不合规 = 会话里一个 `lw_*` 都没有 (草稿第 14 条) | 批次 1.1 + 每次都跑 1.2 那条自检 |
| Android 17 / AAPM 收紧无障碍 | **已查清 (批次 0.4)**: 用户开 AAPM 后, 未声明 `isAccessibilityTool="true"` 的服务拿不到无障碍, 已拿到的会被撤销。两台设备都是 Android 16, 今天不发作 | 结论见第 5.1 节; 加不加那行 = D6; 另外把 AAPM 状态读出来直说 (与 1.6 同主题) |
| 体积继续涨 | 350 MB + OCR 模型 (约 6 MB) + wasm32 sharp | 批次 1 结束量一次, 定 ≤ 400 MB, 超了改"模型不留包内" |
| OCR 模型不在本机 | 1.0.2 的 APK 里**没有** `assets/ocr` (只有 `host.zip` 与 `host-version.txt`), 而 `.gitignore` 把模型当构建产物, 生成它要 HuggingFace + Python 的 onnx 工具链 | D7 定了之后从 HuggingFace 拉; 拉不到就这一版继续报"没有 OCR 模型, 改用截图", 不假装有 |
| 每次重装踢掉无障碍 | 已知事实, 且有窗口期 | 装机固定用 `tools/lw-install.ps1`, 紧接装完就跑 |
| 新能力扩大爆炸半径 | 通知读取是能看见别人东西的能力, 而审批是全放行的 | 通知读取一律「系统授权 + 把状态报出来」; shell 这一版不做 (D2); 录屏这一版不做 (D3) |
| 补丁与 1.0.3 后续改动撞车 | 滚动那三个提交动了 `LwAccessibility.kt` / `PrivilegedBridge.kt` / `index.mjs`, 而批次 1.6 与 1.7 也要动后两个 | 批次 0 先落地补丁, 再往上叠批次 1 的改动, 不反过来 |
| 草稿的断言不等于现状 | 草稿说「`e81cfde` 已合进 main」时本仓库确实没有, 那是两棵树 | 每批开始前重新核对一遍 (第 1 节那 12 条就是这次的核对结果) |
| 草稿与本仓库不是同一棵树 | 第 1 节事实 2 已经证明 (草稿说的"已合进 main"在本仓库没有) | 每批开始前重新核对, 不拿草稿的断言当现状 |


7 · 执行节奏 (按已定的分工)
----------------------------

- 每批: 改代码 → `.\gradlew.bat :app:compileDebugKotlin` 自检 → 动了依赖/manifest 再加 `:app:assembleDebug` → 装到 **emulator-5554** 给用户过目 → **真机由用户自己装、自己点**
- 触摸/命中测试这一类**截图看不出来**, 只有用户真机点一下才算过
- 每批一个提交, 信息照 1.0.2 的写法: `DSH-LW 1.0.3 part N: <做了什么>`
- 批次之间停下来等用户看, 不连着往下推


8 · 回执模板 (批次 9 用)
------------------------

| 提交 | 内容 |
| :-- | :-- |
| | |

**已验 (模拟器 emulator-5554)**: <逐条>

**待你真机验**: <逐条>


> 这份计划写的是"打算怎么做", 不是"已经做了"。D1 / D2 / D4 / D5 已定, D3 在批次 7 开工时定了 (不录屏)
