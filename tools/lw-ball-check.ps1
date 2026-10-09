# 浮标那几处判据的实测脚本 (批次 4 追加)
#
# 量的是窗口坐标, 不是"看着像": 半隐在左边必须是负 x, 在右边必须超出右边界; 唤回之后必须回到停靠位
#
# 用法: pwsh -File tools/lw-ball-check.ps1 -Serial emulator-5554
#       pwsh -File tools/lw-ball-check.ps1 -Serial 10CEB40568000ZB -NoDrag   # 只读, 不动别人的手机
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$NoDrag,
    [switch]$Shots
)

$ErrorActionPreference = 'Stop'
$adb = if ($env:LW_ADB) { $env:LW_ADB } else { 'D:\apk\Sdk\platform-tools\adb.exe' }
$package = 'io.github.miuzarte.littlewhale'
$forward = if ($Serial -eq 'emulator-5554') { 29997 } else { 29996 }

function Sh($command) { & $adb -s $Serial shell $command }

# 1. 通道口与令牌只在宿主的 environ 里
Sh "push /dev/null /data/local/tmp/.keep" | Out-Null
& $adb -s $Serial push tools\lw-device\host-env.sh /data/local/tmp/lw-host-env.sh | Out-Null
$raw = Sh "run-as $package sh /data/local/tmp/lw-host-env.sh"
$endpoint = ($raw | Select-String 'LW_CHANNEL_ENDPOINT=(.+)').Matches.Groups[1].Value
$token = ($raw | Select-String 'LW_CHANNEL_TOKEN=(.+)').Matches.Groups[1].Value
if (-not $endpoint -or -not $token) { throw "no channel on $Serial : $raw" }
& $adb -s $Serial forward "tcp:$forward" "tcp:$(($endpoint -split ':')[-1])" | Out-Null
$env:LW_CHANNEL_ENDPOINT = "127.0.0.1:$forward"
$env:LW_CHANNEL_TOKEN = $token

# 2. 屏幕与球的窗口
$size = (Sh 'wm size' | Select-Object -Last 1) -replace '.*: ', ''
$screenW, $screenH = ($size -split 'x') | ForEach-Object { [int]$_ }
$density = [int]((Sh 'wm density' | Select-Object -Last 1) -replace '.*: ', '')
$ball = [int][Math]::Round(48 * $density / 160)
$half = [int][Math]::Round($ball * 0.5)

function BallAttrs {
    $out = Sh 'dumpsys window windows'
    # 我们这个包在屏上可能有**三块** overlay 窗: 球 (NOT_FOCUSABLE)、长按出来的菜单 (可获焦),
    # 以及 2026-10-09 加的**涟漪窗** (说/听两档才挂上)。只认球那一块 —— 取错窗会量到菜单或涟漪的
    # 坐标, 那是自己骗自己。涟漪窗是**非触摸**的 (FLAG_NOT_TOUCHABLE, 它比球大, 可触摸就会在球周围
    # 多出一圈吃手指的死区), 而球不吃焦但**可触摸** —— 这两个标志就是它们的区别
    $hit = ($out | Select-String 'ty=APPLICATION_OVERLAY') |
        Where-Object {
            $block = $out[($_.LineNumber - 3)..($_.LineNumber + 1)]
            ($block -join ' ') -match 'littlewhale' -and
            ($block -join ' ') -match 'NOT_FOCUSABLE' -and
            ($block -join ' ') -notmatch 'NOT_TOUCHABLE'
        } |
        Select-Object -First 1
    if (-not $hit) { return $null }
    $attrs = $out[$hit.LineNumber - 1]
    $flags = $out[$hit.LineNumber]
    $xy = [regex]::Match($attrs, '\((-?\d+),(-?\d+)\)')
    # "Requested w=… h=…" 离 mAttrs 有几行 (看 ROM 上那几个 pfl/fitTypes 行有几条), 所以往后找
    $size = $null
    # **真实几何只有 Frames 那一行说了算**: mAttrs 是"申请的位置", 窗口管理器会把越界的申请钳回
    # 边界 (2026-10-05 就是拿 mAttrs 当判据, 于是"球已经在屏外"这句话是假的)
    $frame = $null
    for ($i = $hit.LineNumber; $i -lt [Math]::Min($hit.LineNumber + 24, $out.Count); $i++) {
        if (-not $size) {
            $m = [regex]::Match($out[$i], 'Requested w=(\d+) h=(\d+)')
            if ($m.Success) { $size = $m }
        }
        if (-not $frame) {
            $f = [regex]::Match($out[$i], 'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
            if ($f.Success) { $frame = $f }
        }
        if ($size -and $frame) { break }
    }
    return [pscustomobject]@{
        WantX = [int]$xy.Groups[1].Value
        WantY = [int]$xy.Groups[2].Value
        Left = if ($frame) { [int]$frame.Groups[1].Value } else { 0 }
        Top = if ($frame) { [int]$frame.Groups[2].Value } else { 0 }
        Right = if ($frame) { [int]$frame.Groups[3].Value } else { 0 }
        Bottom = if ($frame) { [int]$frame.Groups[4].Value } else { 0 }
        W = if ($size) { [int]$size.Groups[1].Value } else { 0 }
        H = if ($size) { [int]$size.Groups[2].Value } else { 0 }
        Flags = ($flags -replace '.*fl=', '').Trim()
        Raw = $attrs.Trim()
    }
}

# 一行报告: 申请的位置与真实几何一起印, 后者才是判据 (PowerShell 没有块注释, 这里用 #)
function ReportBall($tag, $ball) {
    Write-Output ("   {0} frame=[{1},{2}]-[{3},{4}] (宽 {5})  申请 x={6} y={7}" -f $tag, $ball.Left, $ball.Top, $ball.Right, $ball.Bottom, $ball.W, $ball.WantX, $ball.WantY)
}

function DismissMenu {
    # 长按会把菜单挂出来 (它可获焦, 而且开着的时候球不收边 —— 那是设计), 所以每一步之前先点一下
    # **菜单外面**把它关掉: 菜单挂在球旁边, 所以点屏幕下方那片空地
    Sh "input tap $([int]($screenW / 2)) $($screenH - 200)" | Out-Null
    Start-Sleep -Milliseconds 600
}

function State { (node tools\lw-channel-call.mjs overlay '{"op":"state"}' | Out-String) }

Write-Output "== $Serial  ($screenW x $screenH, density $density, 球 $ball px, 半隐 $half px)"

# 3. 把球摆到左边: 拖到 x≈0 再松手
DismissMenu
$ballAt = BallAttrs
if ($null -eq $ballAt) { throw 'the ball is not up: run lw_overlay op=show first' }
Write-Output '球现在:'
ReportBall '     ' $ballAt
Write-Output "flags : $($ballAt.Flags)"

if (-not $NoDrag) {
    # 半隐着的球有一半在屏幕外, 所以起点要钳进"看得见的那一半"里; 终点也要离起点足够远 ——
    # 零长度的 swipe 在系统看来是一次**长按**, 那会把菜单挂出来 (这一条踩过一次)
    $cx = [Math]::Max([Math]::Min($ballAt.Right - 20, $screenW - 60), 60)
    $cy = [Math]::Min($ballAt.Top + [int]($ballAt.H / 2), $screenH - 60)
    Write-Output "`n-- 拖到左边 (从 $cx,$cy 划到 20,$cy, 800 ms)"
    Sh "input swipe $cx $cy 20 $cy 800" | Out-Null
    Start-Sleep -Milliseconds 900
    $left = BallAttrs
    ReportBall '   松手后:' $left
    Write-Output "   → 贴左停靠位是 frame 左边界 0; 停靠即收边, 所以这里就该 <= -$half"
    Start-Sleep -Seconds 5
    $peekL = BallAttrs
    ReportBall '   5 s 后:' $peekL
    Write-Output "   → 期望左边界 <= -$half (左贴边出屏一半)"
    if ($Shots) { Sh 'screencap -p /sdcard/ball-peek-left.png' | Out-Null; & $adb -s $Serial pull /sdcard/ball-peek-left.png "D:\apk\shots\ball-$Serial-peek-left.png" | Out-Null }

    # 唤回: 在它露在外面的那一条上碰一下
    $tapX = [Math]::Max(10, [Math]::Min($peekL.Right - 20, $screenW - 20))
    Write-Output "`n-- 碰一下唤回 (点 $tapX,$cy)"
    Sh "input tap $tapX $cy" | Out-Null
    Start-Sleep -Milliseconds 250
    $back = BallAttrs
    ReportBall '   0.25 s 后:' $back
    Write-Output '   → 期望已经在回停靠位的路上 (200 ms 档)'
    Start-Sleep -Milliseconds 500
    $back2 = BallAttrs
    ReportBall '   0.75 s 后:' $back2
    Write-Output '   → 期望左边界 = 0'

    # 拖到右边, 看是否越出右边界
    $cx2 = [Math]::Max([Math]::Min($back2.Left + 40, $screenW - 60), 60)
    Write-Output "`n-- 拖到右边 (从 $cx2,$cy 划到 $($screenW - 20),$cy, 800 ms)"
    Sh "input swipe $cx2 $cy $($screenW - 20) $cy 800" | Out-Null
    Start-Sleep -Milliseconds 900
    $right = BallAttrs
    ReportBall '   松手后:' $right
    Write-Output "   → 贴右停靠位是右边界 $screenW; 停靠即收边, 所以这里就该 >= $($screenW + $half)"
    Start-Sleep -Seconds 5
    $peekR = BallAttrs
    ReportBall '   5 s 后:' $peekR
    Write-Output "   → 期望右边界 >= $($screenW + $half) (右贴边越出右边界)"
    if ($Shots) { Sh 'screencap -p /sdcard/ball-peek-right.png' | Out-Null; & $adb -s $Serial pull /sdcard/ball-peek-right.png "D:\apk\shots\ball-$Serial-peek-right.png" | Out-Null }
}

Write-Output "`n-- 状态 (通道侧)"
Write-Output (State)
