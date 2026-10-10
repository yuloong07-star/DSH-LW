# 虚拟屏修复计划

目标: 让虚拟屏在**无 root、仅 Shizuku** 的 vivo V2417A (Android 16 / SDK 36) 上能建起来、能在后台跑

现状: 建屏被系统拒绝, 界面上显示一行错误。**没有画面 ≠ 另一个故障, 是同一件事**

---

## 一、实测到的现象

真机 (vivo V2417A, `10CEB40568000ZB`) 上从菜单建一块 720x1280 @320dpi 的屏, 界面显示:

```
虚拟屏: no 720x1280 display at 320dpi:
packageName must match the calling uid
```

## 二、两个症状其实是同一个

用户报了两件事, 查下来是一件:

| 说法 | 实情 |
| :-- | :-- |
| "虚拟屏完全不可用" | 真的, 屏没建起来 |
| "只显示一串字符, 不是画面" | **不是 bug**。那串字符就是上面那条错误 |

那行字来自 `HostScreen.kt` 里的 `lastError` 渲染 (虚拟屏段, `selected == null` 且 `lastError != null` 时显示)。
屏一旦建成, 这行字就不显示, `VirtualScreenPreview` 的 `SurfaceView` 接管那块位置。

**所以不需要修"显示成字符串"——修好建屏, 它自己就没了**

## 三、根因

### 3.1 报错在哪产生

`LwVirtualDisplay.kt` 把系统异常包了一层:

```kotlin
throw IllegalStateException("no ${width}x$height display at ${dpi}dpi: ${error.message}", error)
```

`error.message` 就是 `packageName must match the calling uid`。**这句是 system_server 给的, 不是 app 造的。**

### 3.2 谁的校验

`DisplayManagerService.createVirtualDisplay()` 里:

```java
final int callingUid = Binder.getCallingUid();
if (!validatePackageName(callingUid, packageName)) {
    throw new SecurityException("packageName must match the calling uid");
}
```

`validatePackageName` 的主体 (AOSP 多个 revision 一致):

```java
private boolean validatePackageName(int uid, String packageName) {
    if (packageName != null) {
        String[] packageNames = mContext.getPackageManager().getPackagesForUid(uid);
        ...
    }
}
```

**判据是 `getPackagesForUid(callingUid)` 里有没有这个包名。** 没有 → 抛。

来源: [DisplayManagerService.java (50ebd0232f1a)](https://android.googlesource.com/platform/frameworks/base/+/50ebd0232f1a/services/core/java/com/android/server/display/DisplayManagerService.java), [同文件另一 revision (41299be4)](https://android.googlesource.com/platform/frameworks/base/+/41299be43bf814c9cb9b9545d60f17834199c956/services/core/java/com/android/server/display/DisplayManagerService.java)

### 3.3 为什么撞上

```
Luwi (uid 10384) → Shizuku → IShizukuService.newProcess() → 特权进程 (uid 2000 = SHELL)
                                                                          ↓
                                                        createVirtualDisplay(packageName = "io.github.yuloong07star.luwi")
                                                                          ↓
                              getPackagesForUid(2000) = ["com.android.shell"] ≠ "io.github.yuloong07star.luwi"
                                                                          ↓
                                                                   SecurityException
```

`LwContext.kt` 手工把包名伪装成 app 的:

```kotlin
override fun getPackageName(): String = packageName
override fun getOpPackageName(): String = packageName
```

**这能骗过读 `Context` 的代码, 骗不过 system_server** —— 后者看的是 `Binder.getCallingUid()`, 而那个 uid 是 2000。

### 3.4 关键实测: 权限不是问题

之前怀疑过 "shell 没有 `CAPTURE_VIDEO_OUTPUT`, 所以建不了屏"。**实测否掉了**:

```
$ adb shell dumpsys package com.android.shell | grep -i CAPTURE_VIDEO
  android.permission.CAPTURE_VIDEO_OUTPUT
  android.permission.CAPTURE_VIDEO_OUTPUT: granted=true
  android.permission.ADD_TRUSTED_DISPLAY: granted=true
  android.permission.INTERNAL_SYSTEM_WINDOW: granted=true
```

`com.android.shell` (uid 2000) **已经持有** `CAPTURE_VIDEO_OUTPUT` / `ADD_TRUSTED_DISPLAY` / `INTERNAL_SYSTEM_WINDOW`, 全部 `granted=true`。

**所以卡住的是包名匹配这一道, 不是权限。** 这是本计划最重要的一条结论。

---

## 四、修复方向

只差一件事: **让 `packageName` 参数与 `Binder.getCallingUid()` 对得上。**

uid 2000 对应的包名是 `com.android.shell`。有两条走法:

### 方向 A: 特权进程改报 `com.android.shell`

`createVirtualDisplay` 传的 `packageName` 由当前的 app 包名改为 `com.android.shell`。

- 改动点: `LwVirtualDisplay.kt` 的 `create()` —— 那个 `displays.createVirtualDisplay(name, w, h, dpi, surface, flags())` 之前, 需要换一个 `DisplayManager` 实例 (它内部会取 `mContext.getOpPackageName()`)
- 风险: `LwContext` 现在故意报 app 包名, 是**为了别的东西**(provider 调用、`createPackageContext`)。**不能全局改掉**, 只能给建屏这一条路单独换
- **待核实**: `DisplayManager` 到底从哪里取包名 —— 是 `Context.getOpPackageName()` 还是构造时缓存。这决定改动是"换 context"还是"换 op package"

### 方向 B: 让 app 进程自己建屏

不在特权进程建, 回到 app 进程 (uid 10384) 建, 包名与 uid 天然一致。

- **问题**: app 没有 `CAPTURE_VIDEO_OUTPUT`, 那 `TRUSTED` / `OWN_FOCUS` / `OWN_DISPLAY_GROUP` 这些 flag 会被拒 (或静默降级)
- `LwVirtualDisplay.kt` 顶部注释写明了当初把建屏放进特权进程的理由就是这些 flag
- **待核实**: 那几个 flag 在 `DisplayManagerService` 里具体卡在哪个权限判断上

### 方向取舍

**先做 A。** 理由: A 只动一个参数, 且已知 uid 2000 权限齐备; B 要重新论证一整条链路, 代价大得多。

A 若失败 (比如 `DisplayManager` 的包名来源改不动), 再评估 B。

### 明确不做

- **不引入 root** —— 设备没有, 且用户明确不需要
- **不走 PRoot 容器** —— 那是 DSHA 的路子, 但 PRoot 给不了"操作手机上真实 app"的能力 (容器里是独立 Linux, 看不见宿主 app)。用户当前目标是"虚拟屏能在后台跑", 不是"要一个 Ubuntu", 所以 PRoot 不解决这个问题

---

## 五、分批执行

每批都能独立验证、独立回退。**不要一次改完再测。**

### 批次 1 · 确认包名来源 (只读, 不改码)

**目的**: 在动代码前, 确定 `DisplayManager` 从哪儿取包名。

**做**:
1. 读 `LwVirtualDisplay.kt` 的 `manager()` —— 它反射构造 `DisplayManager(Context)`
2. 查清 `DisplayManager` 内部构造时是否缓存 `mContext.getOpPackageName()`
3. 确认 `LwContext.getOpPackageName()` 现在返回什么

**验收**: 能一句话说清"改哪个对象能让建屏那次调用的包名变成 `com.android.shell`", 且不影响其它路径

**产出**: 一份结论 (可能推翻方向 A)

### 批次 2 · 建屏专用包名

**目的**: 让建屏那一次调用的 `packageName` 与 uid 2000 对得上。

**改动点**: 只碰 `LwVirtualDisplay.kt`, 且只碰建屏路径

**做**:
- 给建屏单独准备一个 context / op package, 报 `com.android.shell`
- **不要动** `LwContext.getPackageName()` 的默认返回值 (它服务于 provider 调用)

**验收**:
- `.\gradlew.bat :app:compileDebugKotlin` 通过
- 装机后从菜单建一块 720x1280 @320dpi 的屏 → **界面不再出现那行错误**
- 菜单里「虚拟屏」子菜单能看到这块屏

**失败时**: 报错若变了 (不再是 `packageName must match`), 把新报错记下来 —— 那是下一批的输入

### 批次 3 · 让画面出来

**目的**: 屏建起来之后, 预览那块 `SurfaceView` 真的显示内容。

**背景**: 建屏时的 surface 传的是 `null` (`VirtualScreen.create()` 里写死的), 真实 surface 靠
`VirtualScreenPreview.kt` 的 `surfaceCreated` 回调经 `VirtualScreen.attach()` 补上。

**要验的点**:
1. `attach` 有没有真的把 surface 交到特权侧 (`setDisplaySurface`)
2. `attach` 里那句 `if (selected?.displayId != screen.displayId) return` 会不会把回调挡掉
3. 屏建好后 `setFixedSize(w, h)` 有没有在 surface 存在之后才调用

**验收**: 预览区出现真实画面 (不是黑块、不是文字)

### 批次 4 · 后台运行

**目的**: 满足用户的核心诉求 —— "能在后台用虚拟屏跑, 不需要显示"。

**要验的点**:
1. 切到后台 / 关掉预览后, 屏是否继续存在 (`VirtualScreen` 的注释说 detach 是一等状态, 屏应当活着)
2. 前台服务 (`DshHostService`) 会不会被系统收掉
3. 不选屏时 (`selected == null`) 建屏能否成功 (这条路径现在是被菜单驱动的)

**验收**: app 退到后台, 模型仍能通过 `lw_screen` / `lw_tap` / `lw_screenshot` 操作那块屏

---

## 六、怎么验证 (用户环境)

**设备**: vivo V2417A, `10CEB40568000ZB` (另有一台 `emulator-5554`, **每条 adb 都要带 `-s`**)

**adb 路径**: `D:\apk\Sdk\platform-tools\adb.exe` (不在 PATH 里)

**构建**:
```powershell
. D:\apk\env.ps1
cd D:\apk\Luwi
.\gradlew.bat :app:compileDebugKotlin     # 先过编译
.\gradlew.bat :app:assembleDebug          # 提交前必跑 (manifest merger)
```

**装机**:
```powershell
& 'D:\apk\Sdk\platform-tools\adb.exe' -s 10CEB40568000ZB install -r -t app\build\outputs\apk\debug\app-debug.apk
```

**日志 —— 注意**: 这台 vivo 的 logcat **是空的**。已实测 `logcat -d`、按 pid 过滤、`-g` 看缓冲、
设备内重定向四种办法, 缓冲区报 readable 但一行都出不来 (系统级日志被关)。
**所以验证只能靠界面上的可见结果, 不要指望日志。**

界面就是判据:
- 出现 `虚拟屏: <错误>` = 还失败
- 出现菜单里能选中那块的屏 = 建屏成功
- 出现画面 = 批次 3 成功

---

## 七、待核实 (写死在计划里, 别当结论用)

| 项 | 状态 |
| :-- | :-- |
| `DisplayManager` 包名来源 (`getOpPackageName` vs 构造缓存) | **未核实**, 批次 1 解决 |
| `TRUSTED` 等 flag 卡在哪个权限判断 (影响方向 B) | **未核实** |
| shell uid 在 `DisplayManagerService` 里是否有免检分支 | **未核实**。找到的 "Allow shell uid without checking the package name" (aosp-mirror `fcef7515`, Bug 230779051) 改的是 **MediaSessionService, 不是 DisplayManagerService**, 不能当依据 |
| 建屏成功后画面能否真的渲染 | **未核实**, 批次 3 |
| 后台存活行为 | **未核实**, 批次 4 |

---

## 八、备份与回退

**动手前先做**: 拷出 app 沙盒里的 `files/dsh-home` (含 `credentials` 与 `sessions`)。
Luwi 的 host 能起来是前面若干轮才拿到的状态, 别弄丢。

**回退**: 本计划只改 `LwVirtualDisplay.kt` (必要时加一个新文件)。
`git -C D:\apk\Luwi status` 应当只有预期内的改动; 不对就 `git checkout -- <file>`。

**不要动**:
- `third_party/deepseek-harness` 的 pin (仍是 `e79ffe35e1`)
- `env.ps1`
- `jniLibs` 里那 18 个 `.so` (在 `.gitignore` 里, 清了要重建)

---

## 九、相关文件

| 文件 | 作用 |
| :-- | :-- |
| `channel/LwVirtualDisplay.kt` | 特权侧建屏, **本计划的主要改动点** |
| `channel/VirtualScreen.kt` | app 侧对屏的handle, `create()` 传 `null` surface |
| `channel/LwPrivilegedProcess.kt` | `LwContext` 的构造处, 包名来源 |
| `channel/LwContext.kt` | 包名伪装 (`getPackageName` / `getOpPackageName`) |
| `channel/LwPrivilegedService.kt` | 特权侧 Binder 服务, 持有 `LwVirtualDisplay` |
| `ui/VirtualScreenPreview.kt` | 预览的 `SurfaceView`, surface 生命周期的来源 |
| `ui/HostScreen.kt` | `lastError` 的显示处 (那行"一串字符") |
