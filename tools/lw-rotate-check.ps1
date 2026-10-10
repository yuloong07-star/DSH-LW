# 转屏那一件事在设备上的实测 (批次 1, 2026-10-08)
#
# 量的是 `overlay op=state` 里那四个读数 (screen / ballRect / stripRect / boxRect): 同一块屏转过来之后,
# 三块窗都必须按**新屏**重摆完还在屏里。主人 2026-10-08 报的是"横竖屏切换时文本框和 ball 会发生极大
# 偏移, ball 消失", 而"偏移"这件事只有坐标说得清 —— 眼睛只能看出"不对"
#
# 用法: pwsh -File tools/lw-rotate-check.ps1 -Serial emulator-5554
#       pwsh -File tools/lw-rotate-check.ps1 -Serial emulator-5554 -Rounds 3
#       pwsh -File tools/lw-rotate-check.ps1 -Serial emulator-5554 -SkipPanels   # 只看球
#
# 前提: host 在跑 (通道口与令牌从它的环境里读), 球在屏上 (缺了会自己 op=show 一次)
# 它会改系统的旋转设置 (accelerometer_rotation / user_rotation), 跑完恢复原值
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [int]$Rounds = 2,
    [switch]$SkipPanels
)

$ErrorActionPreference = 'Stop'
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$adb = if ($env:LW_ADB) { $env:LW_ADB } else { 'D:\apk\Sdk\platform-tools\adb.exe' }
$package = 'io.github.yuloong07star.luwi'
$forward = if ($Serial -eq 'emulator-5554') { 29991 } else { 29990 }

function Sh($command) { & $adb -s $Serial shell $command }
function Setting($key) { (Sh "settings get system $key" | Out-String).Trim() }

# ── 1. 通道口与令牌 (与 lw-ball-check.ps1 同一套) ─────────────────────────

& $adb -s $Serial push tools\lw-device\host-env.sh /data/local/tmp/lw-host-env.sh | Out-Null
$raw = Sh "run-as $package sh /data/local/tmp/lw-host-env.sh"
$endpoint = ($raw | Select-String 'LW_CHANNEL_ENDPOINT=(.+)').Matches.Groups[1].Value
$token = ($raw | Select-String 'LW_CHANNEL_TOKEN=(.+)').Matches.Groups[1].Value
if (-not $endpoint -or -not $token) { throw "no channel on $Serial : $raw" }
& $adb -s $Serial forward "tcp:$forward" "tcp:$(($endpoint -split ':')[-1])" | Out-Null
$env:LW_CHANNEL_ENDPOINT = "127.0.0.1:$forward"
$env:LW_CHANNEL_TOKEN = $token

# 通道的回执是 `{ok, result}` 那一层包着的: 读数在 `.result` 里, 不在根上 (第一次跑就是拿错了这一层)
function Call($method, $params = '{}') { (node tools\lw-channel-call.mjs $method $params | Out-String) }
function State { (Call 'overlay' '{"op":"state"}' | ConvertFrom-Json).result }

# ── 2. 把三块窗准备好 ────────────────────────────────────────────────────

$before = State
if (-not $before.showing) { Call 'overlay' '{"op":"show"}' | Out-Null; Start-Sleep -Seconds 2 }
if (-not $SkipPanels) {
    # 输入条 (那条 WebView) 与文本框 (通道那块框): 主人报的那条正是它们跟着球一起偏
    try { Call 'overlay' '{"op":"expand"}' | Out-Null; Start-Sleep -Seconds 2 } catch { Write-Output "输入条没能打开: $_" }
    try { Call 'overlay' '{"op":"channel"}' | Out-Null; Start-Sleep -Seconds 1 } catch { Write-Output "文本框没能打开: $_" }
}

# 原来的旋转设置要留着: 跑完还回去 (这台设备可能是别人的手机)
$wasAuto = Setting 'accelerometer_rotation'
$wasUser = Setting 'user_rotation'

function Rect($text) {
    if (-not $text) { return $null }
    $parts = $text -split ';'
    if ($parts.Count -ne 4) { return $null }
    return [pscustomobject]@{
        X = [int]$parts[0]; Y = [int]$parts[1]; W = [int]$parts[2]; H = [int]$parts[3]
    }
}

function Inside($rect, $w, $h) {
    if ($null -eq $rect) { return $true }          # 那块窗没开: 不参与这一轮
    if ($rect.W -le 0 -or $rect.H -le 0) { return $false }
    # 允许一点点越界 (半隐是**故意**出屏一半的), 这里要的是"没有整块跑出去"
    $slack = [Math]::Max(4, [int]($rect.W / 2))
    return ($rect.X -ge -$slack) -and ($rect.Y -ge -4) -and
        ($rect.X + $rect.W -le $w + $slack) -and ($rect.Y + $rect.H -le $h + 4)
}

# 注意: 函数里那几行说明必须走 Write-Host —— 用 Write-Output 的话它们会被调用处的赋值/判断吃掉
# (第一次跑就是这么"全过"的: `if (-not (Round ...))` 把整段输出收进了表达式, 判据看着过了其实没看)
function Round($label, $rotation) {
    Sh "settings put system accelerometer_rotation 0" | Out-Null
    Sh "settings put system user_rotation $rotation" | Out-Null
    # 转屏是异步的, 而我们自己补的那一拍在 250 ms 之后: 等够两拍再看
    Start-Sleep -Milliseconds 1800
    $state = State
    $screen = $state.screen
    if (-not $screen) { Write-Host "  $label : 没有 screen 读数 (应用是旧包?)"; return $false }
    $wh = $screen -split 'x'
    $w = [int]$wh[0]; $h = [int]$wh[1]
    $ball = Rect $state.ballRect
    $strip = Rect $state.stripRect
    $box = Rect $state.boxRect
    $wantLandscape = $rotation -ne '0'
    $isLandscape = $w -gt $h
    # 一行拼好再打: `Write-Host "a" + "b"` 里那个 + 会被当成**下一个参数**, 打出来是 "a + b" (踩过一次)
    $ballText = if ($ball) { "$($ball.X),$($ball.Y) $($ball.W)x$($ball.H)" } else { '—' }
    $stripText = if ($strip) { "$($strip.X),$($strip.Y) $($strip.W)x$($strip.H)" } else { '—' }
    $boxText = if ($box) { "$($box.X),$($box.Y) $($box.W)x$($box.H)" } else { '—' }
    Write-Host ("  {0} : 屏 {1}, 球 [{2}], 输入条 [{3}], 文本框 [{4}]" -f $label, $screen, $ballText, $stripText, $boxText)
    $ok = $true
    if ($wantLandscape -ne $isLandscape) { Write-Host "     ✗ 朝向不对 (要横屏: $wantLandscape)"; $ok = $false }
    if (-not (Inside $ball $w $h)) { Write-Host "     ✗ 球跑到屏外了"; $ok = $false }
    if (-not (Inside $strip $w $h)) { Write-Host "     ✗ 输入条跑到屏外了"; $ok = $false }
    if (-not (Inside $box $w $h)) { Write-Host "     ✗ 文本框跑到屏外了"; $ok = $false }
    if ($strip -and ($strip.Y + $strip.H -gt $h)) { Write-Host "     ✗ 输入条高过屏幕 (竖屏那两个数留下来了)"; $ok = $false }
    if ($ok) { Write-Host "     ✓ 三块窗都在屏里" }
    return $ok
}

Write-Output "== $Serial 的转屏检查 (${Rounds} 轮, 每轮 竖 → 横 → 竖)"
$all = $true
for ($i = 1; $i -le $Rounds; $i++) {
    Write-Output "-- 第 $i 轮"
    $upright = Round "竖屏" '0'
    if (-not $upright) { $all = $false }
    $sideways = Round "横屏" '1'
    if (-not $sideways) { $all = $false }
}

# ── 3. 收尾: 恢复原来的旋转设置 ──────────────────────────────────────────

Sh "settings put system accelerometer_rotation $wasAuto" | Out-Null
Sh "settings put system user_rotation $wasUser" | Out-Null
if (-not $SkipPanels) { try { Call 'overlay' '{"op":"collapse"}' | Out-Null } catch {} }

Write-Output ''
if ($all) { Write-Output '转屏检查: 全过' } else { Write-Output '转屏检查: 有失败项 (见上面那几行 ✗)' }
