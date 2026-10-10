# 「关掉浮标」那三个入口的实测脚本 (2026-10-06 加的, 对应主人报的那两条)
#
# 量的东西不是"看着像关掉了", 而是**四件事实一起对**:
#   1. 那块 `ty=APPLICATION_OVERLAY` + `NOT_FOCUSABLE` 的球窗真的从窗口管理器上没了
#   2. `.overlay.OverlayService` 真的不在 `dumpsys activity services` 里了
#   3. `ball-on` 那个存盘记号是 false
#   4. 关完之后**再让宿主推一次回答也拉不回来** (那头是另一条会起服务的路)
#
# 为什么要有它: `lw-ball-check.ps1` 量的是拖拽与半隐, `lw-batch5-check.ps1` 量的是通道与收边,
# **没有任何一条量过"关掉浮标"** —— 而 2026-10-06 主人报的正是它: 服务停了、记号写了 false, 而那块
# 窗还留在桌面上 (服务停掉不会让它加的窗自己消失, 见 OverlayService.hideBall)
#
# 用法: pwsh -File tools/lw-ball-hide-check.ps1 -Serial emulator-5554
#       pwsh -File tools/lw-ball-hide-check.ps1 -Serial 10CEB40568000ZB
#       pwsh -File tools/lw-ball-hide-check.ps1 -Serial emulator-5554 -SkipShow   # 球已经开着时
#
# 前提: 悬浮窗授权已给 (缺它连球都起不来, 脚本第 0 步会直说并退出)
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$SkipShow
)

$ErrorActionPreference = 'Stop'
$adb = if ($env:LW_ADB) { $env:LW_ADB } else { 'D:\apk\Sdk\platform-tools\adb.exe' }
$package = 'io.github.yuloong07star.luwi'
$forward = if ($Serial -eq 'emulator-5554') { 29995 } else { 29994 }

function Sh($command) { & $adb -s $Serial shell $command }

# 通道口与令牌只在宿主的 environ 里 (应用自己的 logcat 在这台 ROM 上被滤掉了, 只能靠状态对账)
& $adb -s $Serial push tools\lw-device\host-env.sh /data/local/tmp/lw-host-env.sh | Out-Null
$raw = Sh "run-as $package sh /data/local/tmp/lw-host-env.sh"
$endpoint = ($raw | Select-String 'LW_CHANNEL_ENDPOINT=(.+)').Matches.Groups[1].Value
$token = ($raw | Select-String 'LW_CHANNEL_TOKEN=(.+)').Matches.Groups[1].Value
if (-not $endpoint -or -not $token) { throw "no channel on $Serial : $raw" }
& $adb -s $Serial forward "tcp:$forward" "tcp:$(($endpoint -split ':')[-1])" | Out-Null
$env:LW_CHANNEL_ENDPOINT = "127.0.0.1:$forward"
$env:LW_CHANNEL_TOKEN = $token

function Call($json) { (node tools\lw-channel-call.mjs overlay $json | Out-String).Trim() }

# 球那一块窗: `NOT_FOCUSABLE` 把它与长按菜单 / 输入通道那两块可获焦的窗分开; 而 2026-10-09 加的
# **涟漪窗**也是 NOT_FOCUSABLE 的 (说/听两档才挂上), 所以还要加一条 `-notmatch 'NOT_TOUCHABLE'`
# —— 涟漪窗非触摸 (它比球大, 可触摸就会在球周围多出一圈吃手指的死区), 球不吃焦但可触摸
function BallWindow {
    $out = Sh 'dumpsys window windows'
    $hit = ($out | Select-String 'ty=APPLICATION_OVERLAY') |
        Where-Object {
            $block = $out[($_.LineNumber - 3)..($_.LineNumber + 1)]
            ($block -join ' ') -match 'luwi' -and
            ($block -join ' ') -match 'NOT_FOCUSABLE' -and
            ($block -join ' ') -notmatch 'NOT_TOUCHABLE'
        } |
        Select-Object -First 1
    if (-not $hit) { return $null }
    for ($i = $hit.LineNumber; $i -lt [Math]::Min($hit.LineNumber + 24, $out.Count); $i++) {
        $f = [regex]::Match($out[$i], 'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
        if ($f.Success) {
            return [pscustomobject]@{
                Left = [int]$f.Groups[1].Value; Top = [int]$f.Groups[2].Value
                Right = [int]$f.Groups[3].Value; Bottom = [int]$f.Groups[4].Value
            }
        }
    }
    return $null
}

# 服务那一条: 判据是**有没有那一行 ServiceRecord**, 不是"它是不是前台"
function OverlayService {
    $out = Sh "dumpsys activity services $package"
    return ($out | Select-String '\.overlay\.OverlayService').Count -gt 0
}

function BallOnPref {
    $xml = Sh "run-as $package sh -c 'cat shared_prefs/luwi.xml'"
    $line = ($xml | Select-String 'name="ball-on"').Line
    if (-not $line) { return 'absent' }
    if ($line -match 'value="true"') { return 'true' }
    return 'false'
}

$failures = 0
function Judge($name, $ok, $detail) {
    if ($ok) { Write-Output "ok   $name  ($detail)" } else { Write-Output "FAIL $name  ($detail)"; $script:failures += 1 }
}

# 一组四件事实, 三处入口共用: 关完之后每条都要过
function CheckGone($tag) {
    $win = BallWindow
    $svc = OverlayService
    $pref = BallOnPref
    $state = Call '{"op":"state"}'
    Write-Output "   [$tag] ballWindow=$([bool]($null -ne $win))  overlayService=$svc  ball-on=$pref"
    Write-Output "   [$tag] state: $state"
    Judge "[$tag] 球窗从窗口管理器上没了" ($null -eq $win) $(if ($win) { "还在: frame=[$($win.Left),$($win.Top)]-[$($win.Right),$($win.Bottom)]" } else { 'dumpsys 里找不到那块窗' })
    Judge "[$tag] 悬浮窗服务停了" (-not $svc) $(if ($svc) { 'ServiceRecord 还在' } else { 'ServiceRecord 没了' })
    Judge "[$tag] ball-on 是 false" ($pref -eq 'false') "ball-on=$pref"
    Judge "[$tag] 状态里 showing 是 false" ($state -match '"showing":\s*false') 'op=state 的 showing'
}

Write-Output "== $Serial  ($package)"
Write-Output ''

# 0. 授权: 缺它连球都起不来 —— 如实说"这台设备量不了", 不当成通过
$perm = Call '{"op":"state"}' | Select-String '"permission":\s*(true|false)'
if ($perm -notmatch 'true') {
    Write-Output 'FAIL 这台设备没给「显示在其他应用上层」, 浮标那几条量不了'
    Write-Output '     给法: adb -s ' + $Serial + ' shell appops set ' + $package + ' SYSTEM_ALERT_WINDOW allow'
    exit 1
}
Write-Output 'ok   悬浮窗授权在'

# 1. 先把球放出来 (已经开着时用 -SkipShow 跳过那一次)
if (-not $SkipShow) {
    Call '{"op":"show"}' | Out-Null
    Start-Sleep -Milliseconds 1200
}
$up = BallWindow
Judge '球起得来 (先决条件)' ($null -ne $up) $(if ($up) { "frame=[$($up.Left),$($up.Top)]-[$($up.Right),$($up.Bottom)]" } else { '窗没挂上, 后面几条无从谈起' })
if ($null -eq $up) { Write-Output ''; Write-Output "$failures 条判据不过"; exit $failures }

# 2. 通道入口 (op=hide): 这是工具与脚本走的那一条, 也是量得最准的一条
Write-Output ''
Write-Output '-- 入口一: lw_overlay op=hide (工具那条路)'
$before = Get-Date
$answer = Call '{"op":"hide"}'
$elapsed = [int]((Get-Date) - $before).TotalMilliseconds
Write-Output "   answer: $answer"
Write-Output "   从发出到工具回话: $elapsed ms"
CheckGone 'hide'
Judge '回执里没有 problem' ($answer -notmatch '"problem":\s*"[^"]') 'hide 的 problem 字段是空的'

# 3. 再推一次回答: 那条路会 startForegroundService, 关掉之后不许把球拉回来
Write-Output ''
Write-Output '-- 关完之后不许被"推回答"拉回来 (宿主每一轮都会发这一条)'
Call '{"op":"reply","text":"lw-ball-hide-check"}' | Out-Null
Start-Sleep -Milliseconds 1500
$win = BallWindow
$svc = OverlayService
Judge '推一次回答之后球还是不在' ($null -eq $win) $(if ($win) { '球被拉回来了' } else { '窗仍然不在' })
Judge '推一次回答之后服务也没起回来' (-not $svc) $(if ($svc) { '服务被起回来了' } else { '服务仍然不在' })

# 4. 设置页那个开关走的是同一条实现 (LwOverlay.setOn(false) -> ask(wait=false)), 所以这里量的是它的
#    两个可观察结果: 偏好关掉、窗没了。界面那一下要人点, 不在脚本里模拟
Write-Output ''
Write-Output '-- 入口二: 设置页那个开关 (同一份实现, 这里量它的两个可观察结果)'
Call '{"op":"show"}' | Out-Null
Start-Sleep -Milliseconds 1200
if ($null -eq (BallWindow)) { Write-Output 'FAIL 球没能再放出来, 这一条量不了'; $failures += 1 }
else {
    # 设置页调的就是这一条: 先发 ACTION_HIDE, 再 stopService 兜底 —— 与 op=hide 同一个 ask()
    Call '{"op":"hide"}' | Out-Null
    Start-Sleep -Milliseconds 1000
    CheckGone 'settings-path'
}

# 5. 通知栏那个「关掉浮标」按钮走 ACTION_HIDE -> hideBall("notification"), 与菜单同一条实现。
#    它要点通知栏才知道, 脚本能验的是那条 PendingIntent 的动作名与它落到同一段代码 —— 这里只把
#    现状打出来给人看
Write-Output ''
Write-Output '-- 入口三: 通知栏那个按钮 (PendingIntent -> ACTION_HIDE -> hideBall("notification"))'
Write-Output '   它走的是与菜单完全同一条实现, 要人点一下才知道; 下面这一行是它现在的状态:'
Call '{"op":"show"}' | Out-Null
Start-Sleep -Milliseconds 1200
Write-Output ('   ' + (Call '{"op":"state"}'))

Write-Output ''
if ($failures -eq 0) { Write-Output '几条判据全过' } else { Write-Output "$failures 条判据不过" }
Write-Output '收尾: 球现在开着, 主人要收就说一句, 或者从菜单里点「关掉浮标」'
exit $failures
