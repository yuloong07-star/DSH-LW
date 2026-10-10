# 临时 (不入库): 相机链路的三类真实失败分支 —— 权限缺失 / 应用在后台 / 相机被别的应用占着
#
# 为什么这么绕: `pm revoke` 一个运行时权限会把应用进程**杀掉**, 桥跟着没了 —— 所以"缺权限"这一条要
# 先撤销再冷启动应用, 而不是撤销了直接问。每一次冷启动端口都会换, 所以找桥那一步要重来
#
# 用法: . D:\apk\env.ps1; .\tools\lw-camera-failures.ps1 [-Serial emulator-5554]
param([string]$Serial = 'emulator-5554')

$ErrorActionPreference = 'Stop'
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$pkg = 'io.github.yuloong07star.luwi'
$caller = Join-Path $PSScriptRoot 'lw-channel-call.mjs'
$probe = 'for d in /proc/[0-9]*; do if [ -r "$d/environ" ]; then e=$(tr "\0" "\n" < "$d/environ" 2>/dev/null | grep -m1 "^LW_CHANNEL_ENDPOINT="); if [ -n "$e" ]; then echo "${e#LW_CHANNEL_ENDPOINT=}"; tr "\0" "\n" < "$d/environ" | grep -m1 "^LW_CHANNEL_TOKEN="; break; fi; fi; done'

function Invoke-Adb { param([string[]]$A) return ((& $adb -s $Serial @A 2>&1) -join "`n") }

function Connect-Channel {
    for ($i = 0; $i -lt 40; $i++) {
        $found = (Invoke-Adb @('shell', "run-as $pkg sh -c '$probe'")).Trim() -split "`n"
        if ($found.Count -ge 2 -and $found[0] -match ':') { break }
        Start-Sleep -Seconds 3
    }
    if ($found.Count -lt 2) { throw 'no channel: the host never came up' }
    $env:LW_CHANNEL_ENDPOINT = $found[0].Trim()
    $env:LW_CHANNEL_TOKEN = $found[1].Trim().Replace('LW_CHANNEL_TOKEN=', '')
    $port = [int]($env:LW_CHANNEL_ENDPOINT -split ':')[-1]
    $null = Invoke-Adb @('forward', "tcp:$port", "tcp:$port")
    return $env:LW_CHANNEL_ENDPOINT
}

function Call {
    param([string]$Method, [string]$Params = '{}')
    $text = (& node $caller $Method $Params 2>&1) -join "`n"
    return $text
}

function Restart-App {
    $null = Invoke-Adb @('shell', "am start -n $pkg/.MainActivity")
    Start-Sleep -Seconds 4
    return Connect-Channel
}

function Importance {
    # $PID 是 PowerShell 的自动变量 (只读), 所以下面那个进程号不能叫 pid
    return (Invoke-Adb @('shell', "dumpsys activity processes | grep -A 14 'ProcessRecord.*luwi' | grep -m1 -i importance")).Trim()
}

Write-Host '### (a) 权限缺失: 先撤销 CAMERA, 再冷启动应用'
$null = Invoke-Adb @('shell', "pm revoke $pkg android.permission.CAMERA")
Start-Sleep -Seconds 2
$null = Restart-App
Write-Host (Call 'camera' '{"op":"open"}')
Write-Host '--- 恢复权限 (授权不会杀进程) ---'
$null = Invoke-Adb @('shell', "pm grant $pkg android.permission.CAMERA")
Write-Host (Call 'camera' '{"op":"open"}')

Write-Host "`n### (b) 应用在后台 (前台服务还在, 但类型是 specialUse 不是 camera)"
$null = Call 'camera' '{"op":"close","clean":true}'
$null = Invoke-Adb @('shell', 'input keyevent KEYCODE_HOME')
Start-Sleep -Seconds 3
Write-Host "importance: $(Importance)"
Write-Host (Call 'camera' '{"op":"open"}')

Write-Host "`n### (c) 相机被别的应用占着: 先回前台开我们自己的, 再从 adb 起系统相机"
$null = Restart-App
Write-Host (Call 'camera' '{"op":"open"}')
$null = Invoke-Adb @('shell', 'am start -n com.android.camera2/com.android.camera.CameraLauncher')
Start-Sleep -Seconds 4
Write-Host '--- 被抢之后我们这一侧的 status 与一次取景 ---'
Write-Host (Call 'camera' '{"op":"status"}')
Write-Host (Call 'camera' '{"op":"snapshot","count":1}')

Write-Host "`n### (d) 反向: 系统相机还占着的时候我们 open"
$null = Invoke-Adb @('shell', 'am start -n com.android.camera2/com.android.camera.CameraLauncher')
Start-Sleep -Seconds 2
Write-Host (Call 'camera' '{"op":"close","clean":true}')
Write-Host (Call 'camera' '{"op":"open"}')

Write-Host "`n### (e) 收尾之后有没有留下占着的相机 / 活着的相机线程 / 小窗"
Write-Host (Call 'camera' '{"op":"close","clean":true}')
$null = Invoke-Adb @('shell', 'am force-stop com.android.camera2')
Write-Host (Invoke-Adb @('shell', 'dumpsys media.camera | grep -A3 "Active Camera Clients"'))
$appPid = (Invoke-Adb @('shell', "pidof $pkg")).Trim()
Write-Host "app pid: $appPid"
Write-Host ("lw-camera threads left: " + (Invoke-Adb @('shell', "ps -T -p $appPid | grep -c lw-camera")))
