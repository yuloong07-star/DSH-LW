# 虚拟屏在后台可用：隐藏预览不再让屏"消失"

目标：**屏是给模型用的，人看不看预览都不该影响它**。收起预览小窗、把 DSH 切到后台、窗口被系统拆掉，
这三种情况下模型都还要能 `lw_ui` / `lw_screenshot` / `lw_tap`。

## 一、现象（本机实测，vivo V2417A / Android 16）

用户按下预览小窗上的减号（描述为「隐藏」）之后：

| 调用 | 结果 |
| :-- | :-- |
| `lw_ui(40)` | `display 40 has no readable window content` |
| `lw_screenshot(40)` | `screencap said: Display Id '11529215048511573661' is not valid` |
| `lw_screen()` | 仍然列出 displayId 40，且 `acceptsControl: true` |

用户再点「显示」，44 个控件原样回来，截图的字节数与隐藏前**完全相同**（101261 B）——屏与 App 都没
被销毁，变的只是它有没有输出面。

## 二、链路

1. `ui/HostScreen.kt`：减号只把 `windowHidden` 置 true，**不取消选中**（`selected` 不变）。
2. `FloatingScreenWindow` 因此离开组合，里面的 `SurfaceView` 被销毁。
3. `ui/VirtualScreenPreview.kt` 的 `surfaceDestroyed` 回调报 `VirtualScreen.attach(screen, null)`。
4. 改动前：`attach` 拿这个 null 去调 `setDisplaySurface(displayId, null)` —— 屏从此没有输出面。

同一个状态还有两个入口，改动前一并修掉：

- `VirtualScreen.select()` 切屏/取消选中时对旧屏 `setDisplaySurface(…, null)`；
- `VirtualScreen.create()` 建屏时传给特权侧的 surface 本来就是 null，要等预览挂上才补——建屏之后、
  预览挂上之前（或一直没人看预览）同样是"看不见的屏"。

## 三、为什么没有输出面就等于屏没了

- 无障碍读树走 `AccessibilityService.windowsOnAllDisplays`，再按 `displayId` 挑窗口（见
  `channel/LwAccessibility.kt` 的 `windowOn`）。没有输出面的屏，窗口列表里没有它 → `lw_ui` 那句话。
- 截图走 `screencap -d <id>`（见 `channel/LwCapture.kt`）。合成器不为没有输出面的屏合成 → 那个
  display id 不合法。

两条路一起断，所以"屏还在，但模型既看不见也点不动"。旧注释里"没有 surface 是正常状态"只在
"屏还活着"这层成立，在"工具能操它"这层不成立。

## 四、改法：保活面（keeper）

新增 `channel/ScreenKeeper.kt`：一个 `w×h` 的 `ImageReader`，它就是"没有人看预览"时这块屏的输出面。

- 尺寸**必须**是屏自己的尺寸：合成器不缩放，给小了就是左上角的裁剪（与预览那块 `SurfaceView` 的
  `setFixedSize` 同一个道理）。
- 帧没人要也不能不取，否则队列满、屏卡在最后一帧。取帧挂在 reader 自己的回调线程上，拿到就关掉，
  空闲时线程睡着。
- `VirtualScreen` 里新增 `surfaceFor(screen)`：有预览就是预览的面，没有就是 keeper 的面，**永不交
  null**。`create` / `select` / `attach` / `resize` 四处都改用它；`release` 时把 keeper 关掉。
- 尺寸变了要换新 keeper（旧面只装得下新屏的左上角）。

代价：一块屏在没有预览时固定占 2 × w×h×4 字节（1260×2800 约 28 MB）。有预览时不占。

## 五、验证（装机后）

1. 建屏 → 屏上起一个应用 → 收起预览小窗（减号）：`lw_ui` 应能读到控件、`lw_screenshot` 应能出图、
   `lw_tap` 应能生效。
2. 把 DSH 切到后台（窗口被系统拆掉）再重复第 1 条。
3. 点「显示」把预览挂回来后重复第 1 条（预览的面应把 keeper 顶掉，画面正常）。
4. `lw_screen_resize` 改尺寸后重复第 1 条（keeper 应已按新尺寸换过）。

## 六、未验证项（诚实标注）

- **本分支的 Kotlin 改动没有编译验证**：本机没有 JDK / Android SDK，改不了也编不了 APK。签名与调用
  逐处对过现有代码（`ImageReader.newInstance(int,int,int,int)`、`setOnImageAvailableListener`、
  `HandlerThread(Looper)`、`ScreenState` 的 width/height），但 `assembleDebug` 必须在有构建环境的机器
  上跑一遍。
- keeper 在真实设备上的行为（特别是"预览挂回时面被顶掉、旧 keeper 闲置"这一段）只经过代码推演，
  需要按上面第五节实测。
