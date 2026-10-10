# 一次把「环境 + 设备 + 关键权限」读完 —— 新会话、换设备、装机失败之后的第一条命令
#
# 为什么要有它: `D:\apk\dev.ps1 doctor` 只管工作区那一半 (工具链在不在), 而"为什么模型调不动屏幕"
# 这类问题落在另一半 (设备上的悬浮窗 / 无障碍 / 通知监听 / 麦克风 / 特权通道)。这两半以前要问三个
# 脚本, 于是排查的第一步常常是"先想想该问谁"
#
# 用法: pwsh -File D:\apk\Luwi\tools\lw-doctor.ps1
#       pwsh -File D:\apk\Luwi\tools\lw-doctor.ps1 -Serial emulator-5554
#       pwsh -File D:\apk\Luwi\tools\lw-doctor.ps1 -EnvOnly      # 只看工具链
#
# 它是**只读**的: 一个字节都不往设备上写 (要改是 lw-install.ps1 的事), 所以随时可以跑
# 退出码: 0 = 工具链齐 (设备那半缺什么都不算失败, 它只是如实报); 1 = 工具链缺东西

param(
    [string]$Serial,
    [switch]$EnvOnly
)

$ErrorActionPreference = 'Stop'
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

# 本脚本在 Luwi\tools\ 下, 而工具链与 env.ps1 在它上面两层 (D:\apk)
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Package = 'io.github.yuloong07star.luwi'
$AccessibilityComponent = "$Package/io.github.yuloong07star.luwi.channel.LwAccessibility"
$ListenerComponent = "$Package/io.github.yuloong07star.luwi.channel.LwNotificationListener"

function Line {
    param([string]$Mark, [string]$Text, [string]$Color = 'Gray')
    Write-Host ("[{0}] {1}" -f $Mark, $Text) -ForegroundColor $Color
}

function Ok { param([string]$Text) Line 'OK' $Text 'Green' }
function Missing { param([string]$Text) Line '缺' $Text 'Red' }
function Info { param([string]$Text) Line '--' $Text 'DarkGray' }

# ── 一、工具链 ─────────────────────────────────────────────────────────────

Write-Host '==== 工具链 ====' -ForegroundColor Cyan

$items = [ordered]@{
    'tools\node\node.exe'          = "$Root\tools\node\node.exe"
    'tools\jdk-25\bin\java.exe'    = "$Root\tools\jdk-25\bin\java.exe"
    'tools\jdk-21\bin\java.exe'    = "$Root\tools\jdk-21\bin\java.exe"
    'Sdk\platform-tools\adb.exe'   = "$Root\Sdk\platform-tools\adb.exe"
    'Sdk\emulator\emulator.exe'    = "$Root\Sdk\emulator\emulator.exe"
    'gradle-home\wrapper\dists'    = "$Root\gradle-home\wrapper\dists"
    '.android\avd\Luwi_API36.ini' = "$Root\.android\avd\Luwi_API36.ini"
    'Luwi\gradlew.bat'      = "$Root\Luwi\gradlew.bat"
    'Luwi\third_party\deepseek-harness' = "$Root\Luwi\third_party\deepseek-harness"
}

$missing = 0
foreach ($name in $items.Keys) {
    if (Test-Path $items[$name]) { Ok $name } else { $missing++; Missing $name }
}

# 环境变量只在激活之后才是对的: 不激活会拿到系统的 Node / JDK (症状见工作区 AGENTS.md 第一条)
# 那个脚本会自己打一段横幅, 这里把它收掉 —— 下面这四行就是同几件事, 而且能让本脚本对齐成一样的列
. "$Root\env.ps1" *> $null
Info "node      : $(node -v)"
Info "java      : $((& java -version 2>&1 | Select-Object -First 1))"
Info "ANDROID_HOME      : $env:ANDROID_HOME"
Info "GRADLE_USER_HOME  : $env:GRADLE_USER_HOME"

if ($missing -gt 0) {
    Write-Host "工具链缺 $missing 项: 先修它, 别的都不用看" -ForegroundColor Red
    exit 1
}
if ($EnvOnly) { exit 0 }

# ── 二、设备 ───────────────────────────────────────────────────────────────

Write-Host ''
Write-Host '==== 设备 ====' -ForegroundColor Cyan

$adb = if ($env:LW_ADB) { $env:LW_ADB } else { "$Root\Sdk\platform-tools\adb.exe" }
$devices = @(
    & $adb devices 2>$null |
        Select-Object -Skip 1 |
        Where-Object { $_ -match '^\S+\s+device' } |
        ForEach-Object { ($_ -split '\s+')[0] }
)

if (-not $Serial) {
    if ($env:ANDROID_SERIAL) { $Serial = $env:ANDROID_SERIAL }
    elseif ($devices.Count -eq 1) { $Serial = $devices[0] }
}

if (-not $Serial) {
    if ($devices.Count -eq 0) {
        Info '没有在线设备: 先起模拟器 (dev.ps1 emu), 或 adb connect <ip>:5555 之后重试'
    } else {
        Info "有 $($devices.Count) 台设备在线 ($($devices -join ', ')): 用 -Serial 点名一台"
    }
    exit 0
}

function Shell { param([string]$Command) (& $adb -s $Serial shell $Command 2>$null | Out-String).Trim() }

if ($devices -notcontains $Serial) { Missing "设备 $Serial 不在线 (在线的是: $($devices -join ', '))"; exit 0 }

Ok "目标设备: $Serial"
Info "型号     : $(Shell 'getprop ro.product.model')"
Info "系统     : Android $(Shell 'getprop ro.build.version.release') (API $(Shell 'getprop ro.build.version.sdk'))"
Info "ABI      : $(Shell 'getprop ro.product.cpu.abi')"
Info "屏幕     : $(Shell 'wm size' | Select-String -Pattern '\d+x\d+' | ForEach-Object { $_.Matches.Value } | Select-Object -Last 1)"

$installed = Shell "pm list packages $Package"
if ($installed -match [regex]::Escape($Package)) {
    $version = (Shell "dumpsys package $Package" | Select-String -Pattern 'versionName=(\S+)' |
        ForEach-Object { $_.Matches.Groups[1].Value } | Select-Object -First 1)
    Ok "已装    : $Package $version"
} else {
    Missing "没装: $Package (用 tools\lw-install.ps1 -Serial $Serial)"
    exit 0
}

# 变量名别写 $pid: 那是 PowerShell 自己的只读自动变量 (2026-10-08 在这条上栽过一次)
$appPid = Shell "pidof $Package"
if ($appPid) { Ok "进程    : 在跑 (pid $appPid)" } else { Info '进程    : 不在跑 (装完先手动打开一次)' }

$services = Shell "dumpsys activity services $Package"
foreach ($name in @('DshHostService', 'OverlayService', 'WakeWordService')) {
    if ($services -match $name) { Ok "服务    : $name" } else { Info "服务    : $name 没在跑" }
}

# ── 三、关键权限 ───────────────────────────────────────────────────────────

Write-Host ''
Write-Host '==== 关键权限 ====' -ForegroundColor Cyan

$overlay = Shell "appops get $Package SYSTEM_ALERT_WINDOW"
if ($overlay -match 'allow') { Ok '悬浮窗  : 已允许 (球与浮标要它)' }
else { Missing '悬浮窗  : 没给 —— 设置页「浮标」那一段会说缺哪一条, 或 lw-install.ps1 -Perms' }

# "设置里写着"与"服务活着"是两件事: 只有前者时系统没绑上, 那正是重装之后的典型样子
$a11y = Shell 'settings get secure enabled_accessibility_services'
$bound = (Shell "dumpsys activity services $Package" | Select-String -Pattern 'LwAccessibility')
if ($a11y -match 'LwAccessibility') {
    if ($bound) { Ok '无障碍  : 名单里有, 系统也绑上了 (服务活着)' }
    else { Missing '无障碍  : 名单里有但系统没绑上 —— 设置页那个开关关掉再打开一次' }
} else {
    Missing '无障碍  : 组件不在名单里 (重装 APK 会把它抹掉, 用 lw-install.ps1 写回)'
}

$listeners = Shell 'settings get secure enabled_notification_listeners'
if ($listeners -match 'LwNotificationListener') { Ok '通知监听: 在名单里' } else { Missing '通知监听: 不在名单里 (lw_notify 与读通知要它)' }

$runtime = Shell "dumpsys package $Package"
$checks = [ordered]@{
    '麦克风  : RECORD_AUDIO'      = 'android.permission.RECORD_AUDIO'
    '相机    : CAMERA'            = 'android.permission.CAMERA'
    '定位    : ACCESS_FINE_LOCATION' = 'android.permission.ACCESS_FINE_LOCATION'
    '日历    : READ_CALENDAR'     = 'android.permission.READ_CALENDAR'
}
foreach ($label in $checks.Keys) {
    $permission = $checks[$label]
    # dumpsys 里每一条运行时权限后面跟一行 granted=true/false
    $granted = ($runtime -split "`n" | Select-String -Pattern ([regex]::Escape($permission) + '$') -Context 0, 1 |
        ForEach-Object { $_.Context.PostContext } | Select-String -Pattern 'granted=true' | Select-Object -First 1)
    if ($granted) { Ok $label } else { Info "$label 没给 (用 lw-install.ps1 -Perms)" }
}

$shizuku = Shell 'pm list packages moe.shizuku.privileged.api'
$kernelsu = Shell 'pm list packages me.weishu.kernelsu'
$magisk = Shell 'pm list packages com.topjohnwu.magisk'
$privileged = @()
if ($shizuku -match 'moe.shizuku') { $privileged += 'Shizuku 已装' }
if ($kernelsu -match 'kernelsu') { $privileged += 'KernelSU 已装' }
if ($magisk -match 'magisk') { $privileged += 'Magisk 已装' }
if ($privileged.Count) { Ok "特权通道: $($privileged -join ' / ')" }
else { Missing '特权通道: 三者都没有 —— 屏幕操作与虚拟屏要 Shizuku 或 root 之一' }

$whitelist = Shell 'dumpsys deviceidle whitelist'
if ($whitelist -match [regex]::Escape($Package)) { Ok '电池优化: 在白名单里' } else { Info '电池优化: 不在白名单里 (后台会被收, 用 lw-install.ps1 -Perms)' }

Write-Host ''
Write-Host '读完了: 缺哪一条就照它后面那句话去补' -ForegroundColor Cyan
exit 0
