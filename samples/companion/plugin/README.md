# 你好伴侣 (样例伴侣插件)

这一份是批次 9 的 P1 样例, 演示一个**伴侣 APK 形态**的 LW 插件长什么样

## 三样东西

| 在哪 | 是什么 |
| :-- | :-- |
| `samples/companion/` | 伴侣 APK 自己那个 Gradle 模块 (`:sample-companion`), 用与主 APK 同一把 debug keystore 签 |
| `samples/companion/plugin/` | 装进 LW 的那一份包: `plugin.json` + 这份 README |
| `lwplugin-api/` | 两侧共用的接口 (`ILwPlugin` / `ILwPluginContext`), 手写 Binder, 没有 AIDL |

## 怎么装

```powershell
# 1. 先装伴侣 APK (同签名那一档, 系统会自动给 signature 级权限)
pwsh -File D:\apk\dev.ps1 gradle -Task ':sample-companion:assembleDebug'
D:\apk\Sdk\platform-tools\adb.exe -s emulator-5554 install -r `
  D:\apk\Luwi\samples\companion\build\outputs\apk\debug\sample-companion-debug.apk

# 2. 签一份包出来 (私钥落在 .lwtmp 里, 不进仓库)
node D:\apk\Luwi\tools\lw-plugin-sign.mjs keygen --out D:\apk\.lwtmp\plugins9\sample.key
node D:\apk\Luwi\tools\lw-plugin-sign.mjs sign --dir D:\apk\Luwi\samples\companion\plugin `
  --key D:\apk\.lwtmp\plugins9\sample.key --name 样例发布者 `
  --out D:\apk\.lwtmp\plugins9\sample-hello --zip D:\apk\.lwtmp\plugins9\hello.lwp

# 3. 先把包推到**手机**上 (install 那条 op 认的是手机上的路径), 再交给 LW
D:\apk\Sdk\platform-tools\adb.exe -s emulator-5554 push D:\apk\.lwtmp\plugins9\sample-hello /sdcard/DSH/plugins-hello
pwsh -File D:\apk\dev.ps1 bridge -Method plugin -Params '{"op":"install","path":"/sdcard/DSH/plugins-hello"}'

# 4. 勾能力 (设置页「插件」那一段逐条勾, 或者桥上这一条), 再启用
pwsh -File D:\apk\dev.ps1 bridge -Method plugin -Params '{"op":"grant","id":"io.github.yuloong07star.luwi.sample.companion","capability":"device.read","allowed":true}'
pwsh -File D:\apk\dev.ps1 bridge -Method plugin -Params '{"op":"enable","id":"io.github.yuloong07star.luwi.sample.companion"}'
```

`minLw` 写的是 `2.5.0`: 这一批开发期的 `versionName` 还是 2.5.0, 正式发版那一步会把它抬到 2.5.1 ——
**抬的时候这份包也要跟着改**, 否则一台 2.5.1 的手机装上它会被"这一份要 2.5.0 及以上"拦下来 (那是它该拦,
但方向反了: 报的是"太低", 而实际是"这份包写老了")

## 三条工具

- `hello_ping` —— 纯算的, 回一句它现在挂在哪、拿到几条授权
- `hello_battery` —— 借 LW 的 `device.read` 念一遍电量 (普通档能力, 装的时候一次确认)
- `hello_ask` —— 要 `session.post` 这一条**敏感能力**, 默认不勾; 没勾时 LW 会拒它并说清缺哪一条
