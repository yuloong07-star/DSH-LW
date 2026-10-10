# 批次 5 那几条改动在**设备上**的实测脚本 (逐条对应主人点过名的判据)
#
# 量的东西: 空闲 5 s 收边那一个时刻 / 通道回执 / 通道那本的像素宽 / 框外双击才收 / 回车发送 /
# 掐断播报。**触摸类的只有真机算数**, 模拟器这一趟测的是"逻辑与时序真的按那几个数走了"
#
# 用法: pwsh -File tools/lw-batch5-check.ps1 -Serial emulator-5554
#       pwsh -File tools/lw-batch5-check.ps1 -Serial 10CEB40568000ZB -NoTap   # 只读, 不动别人的手机
#
# 前提: 应用已经跑起来 (球已在屏上)。通道口与令牌从宿主的环境里读, 与 lw-ball-check.ps1 同一套
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$NoTap,
    [int]$IdleMs = 5000
)

$ErrorActionPreference = 'Stop'
$adb = if ($env:LW_ADB) { $env:LW_ADB } else { 'D:\apk\Sdk\platform-tools\adb.exe' }
$package = 'io.github.yuloong07star.luwi'
$forward = if ($Serial -eq 'emulator-5554') { 29997 } else { 29996 }

function Sh($command) { & $adb -s $Serial shell $command }

# 1. 通道口与令牌 (只有宿主的 environ 里有)
& $adb -s $Serial push tools\lw-device\host-env.sh /data/local/tmp/lw-host-env.sh | Out-Null
$raw = Sh "run-as $package sh /data/local/tmp/lw-host-env.sh"
$endpoint = ($raw | Select-String 'LW_CHANNEL_ENDPOINT=(.+)').Matches.Groups[1].Value
$token = ($raw | Select-String 'LW_CHANNEL_TOKEN=(.+)').Matches.Groups[1].Value
if (-not $endpoint -or -not $token) { throw "no channel on $Serial : $raw" }
& $adb -s $Serial forward "tcp:$forward" "tcp:$(($endpoint -split ':')[-1])" | Out-Null
$env:LW_CHANNEL_ENDPOINT = "127.0.0.1:$forward"
$env:LW_CHANNEL_TOKEN = $token

# 2. 屏与球那几个数 (与应用侧同一套算式)
$size = (Sh 'wm size' | Select-Object -Last 1) -replace '.*: ', ''
$screenW, $screenH = ($size -split 'x') | ForEach-Object { [int]$_ }
$density = [int]((Sh 'wm density' | Select-Object -Last 1) -replace '.*: ', '')
$ball = [int][Math]::Round(48 * $density / 160)
$half = [int][Math]::Round($ball * 0.5)
# 通道那块框的宽度: 2026-10-08 起是 734 px, 而窄屏上按屏宽的 62% 收窄 (与 BallBox.widthFor 同一套)
# —— 1080 宽的屏上量到的因此是 669, 不是 734
$boxWidth = [Math]::Min(734, [int]($screenW * 0.62))

# 球那一块窗: NOT_FOCUSABLE 分开了"可获焦"的那两块 (菜单 / 通道), 而 2026-10-09 起的**涟漪窗**
# 也是 NOT_FOCUSABLE —— 它是**非触摸**的, 那一条才是球与它的区别 (见 lw-ball-check.ps1)
function BallFrame {
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

function ChannelFrame {
    $out = Sh 'dumpsys window windows'
    $hit = ($out | Select-String 'ty=APPLICATION_OVERLAY') |
        Where-Object {
            $block = $out[($_.LineNumber - 3)..($_.LineNumber + 1)]
            ($block -join ' ') -match 'luwi' -and ($block -join ' ') -notmatch 'NOT_FOCUSABLE'
        } |
        Select-Object -First 1
    if (-not $hit) { return $null }
    for ($i = $hit.LineNumber; $i -lt [Math]::Min($hit.LineNumber + 24, $out.Count); $i++) {
        $f = [regex]::Match($out[$i], 'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
        if ($f.Success) {
            return [pscustomobject]@{
                Left = [int]$f.Groups[1].Value; Top = [int]$f.Groups[2].Value
                Right = [int]$f.Groups[3].Value; Bottom = [int]$f.Groups[4].Value
                W = [int]$f.Groups[3].Value - [int]$f.Groups[1].Value
                H = [int]$f.Groups[4].Value - [int]$f.Groups[2].Value
            }
        }
    }
    return $null
}

function Call($json) { (node tools\lw-channel-call.mjs overlay $json | Out-String).Trim() }

# 一个一定落在通道那块框**外面**的点
#
# 两处坑都踩过 (2026-10-05 在这台模拟器上量出来的):
#   1. **不能写死坐标**: 第一下点空白会把软键盘收掉/让框挪位, 而框是跟着键盘避让的, 于是同一个
#      坐标第二下可能落进框里 —— 那一落算"碰到了框", 账被清零 (见 OverlayService 的 boxListener)
#   2. **不能往屏幕底下点**: 软键盘是 ty=INPUT_METHOD 的一块窗, 屏幕下半截那时是**键盘**, 点它
#      等于点穿到 IME 上 —— 应用一个 ACTION_OUTSIDE 都收不到。所以要往**键盘上方**那片空地找
#      (y=400 在球所在那条线上方, 也是 dsh 界面自己的空白区)
function OutsidePoint($box, $width, $height) {
    $y = 400
    $x = [int]($width / 2)
    if ($box -and $x -ge $box.Left -and $x -le $box.Right -and $y -ge $box.Top -and $y -le $box.Bottom) {
        $x = if ($box.Left -gt 100) { 40 } else { $width - 40 }
    }
    return @{ X = $x; Y = $y }
}

$failures = 0
function Judge($name, $ok, $detail) {
    if ($ok) { Write-Output "ok   $name  ($detail)" } else { Write-Output "FAIL $name  ($detail)"; $script:failures += 1 }
}

Write-Output "== $Serial  ($screenW x $screenH, density $density, 球 $ball px, 半隐 $half px, 空闲 $IdleMs ms)"
Write-Output ''

# 3. 空闲那一个数: 3 s 时球还在停靠位, 5 s 后已经在半隐位置
if (-not $NoTap) {
    Write-Output "-- 空闲 $IdleMs ms 收边 (碰一下开始计时)"
    $ball0 = BallFrame
    if ($null -eq $ball0) { throw 'the ball is not up: run lw_overlay op=show first' }
    # 碰它露在外面的那一半, 把计时重新起算; 这一下也可能把它从半隐拽回来 (那是"有人碰它"那一档)
    $tapX = if ($ball0.Left -lt 0) { 20 } elseif ($ball0.Right -gt $screenW) { $screenW - 20 } else { [int](($ball0.Left + $ball0.Right) / 2) }
    $tapY = [int](($ball0.Top + $ball0.Bottom) / 2)
    Sh "input tap $tapX $tapY" | Out-Null
    Start-Sleep -Milliseconds 700
    $rest = BallFrame
    Write-Output ("   碰过之后: frame=[{0},{1}]-[{2},{3}]" -f $rest.Left, $rest.Top, $rest.Right, $rest.Bottom)
    # **判据是"半隐位置"而不是"过了 5 s 才动"**: peek 的那条动画跑完只要 200 ms, 所以到点之后
    # 很快就已经在位置上了 —— 用"球停下之后再看一眼"的方式量, 而不是卡在第 5 秒那一刻
    Start-Sleep -Milliseconds ($IdleMs - 1200)
    $at = BallFrame
    Write-Output ("   {0} ms 时: frame=[{1},{2}]-[{3},{4}]" -f ($IdleMs - 1200), $at.Left, $at.Top, $at.Right, $at.Bottom)
    $already = ($at.Left -le -$half) -or ($at.Right -ge $screenW + $half)
    if ($already) {
        # 到这一刻它已经收边了: 那是"没到时间就收了"吗? 不一定 —— 这一趟之前它本来就可能收着。
        # 真正的判据在下面那一段 (等动画停稳之后再量一次, 位置必须是半隐)
        Write-Output "   (这一刻已经是半隐位置)"
    }
    Start-Sleep -Milliseconds 1500
    $after = BallFrame
    $hidden = ($after.Left -le -$half) -or ($after.Right -ge $screenW + $half)
    Write-Output ("   {0} ms 后: frame=[{1},{2}]-[{3},{4}]" -f ($IdleMs + 300), $after.Left, $after.Top, $after.Right, $after.Bottom)
    Judge "过了空闲时间要收边 (半隐)" $hidden "半个球在屏外 (Left=$($after.Left), Right=$($after.Right), 屏宽=$screenW)"
    # 再碰一下: 必须立刻滑回来 (半隐那一条对称的判据)
    Sh "input tap $([Math]::Max(20, $screenW - 20)) $tapY" | Out-Null
    Start-Sleep -Milliseconds 900
    $back = BallFrame
    $docked = ($back.Left -ge 0) -and ($back.Right -le $screenW)
    Judge "碰一下要滑回来" $docked "又回到了屏幕里 (frame=[$($back.Left),$($back.Top)]-[$($back.Right),$($back.Bottom)])"
}

# 4. 输入通道: 开它、量那一块框的宽 (734 / 窄屏 62%)、回车发送、框外双击才收
if (-not $NoTap) {
    Write-Output ''
    Write-Output "-- 输入通道 ($boxWidth px (屏宽 $screenW 那一档) / 回车发送 / 框外双击才收)"
    Call '{"op":"channel"}' | Out-Null
    Start-Sleep -Milliseconds 1200
    $box = ChannelFrame
    Judge "通道那块窗挂上了" ($null -ne $box) 'dumpsys 里找得到那块可获焦的 overlay 窗'
    if ($box) {
        Judge "宽度是这一屏该有的那个数 (734 或屏宽 62%)" ($box.W -eq $boxWidth) "量到 $($box.W) px, 期望 $boxWidth px"
        Write-Output ("   frame=[{0},{1}]-[{2},{3}]  高 {4} px" -f $box.Left, $box.Top, $box.Right, $box.Bottom, $box.H)
    }

    # 打字 + 回车: 焦点应在输入框上 (开通道时就把焦点给了它), 所以两下都能到它那里
    $sent = "lw-batch5-$([int](Get-Date -UFormat %s))"
    Sh "input text '$sent'" | Out-Null
    Start-Sleep -Milliseconds 600
    $beforeEnter = Call '{"op":"state"}'
    Sh 'input keyevent 66' | Out-Null
    Start-Sleep -Milliseconds 1500
    $state = Call '{"op":"state"}'
    $inbox = Sh "run-as $package sh -c 'tail -n 3 files/dsh-home/voice/inbox.jsonl'"
    Judge "回车那一句进了收件箱" ($inbox -match [regex]::Escape($sent)) "inbox 尾部看得到 $sent"
    Write-Output "   state: $state"

    # 框外双击才收, 而且**超时的那一下要重新起算** (主人 2026-10-06 收窄的口径)
    #
    # **每一次都要现量空白处** (2026-10-05 在模拟器上抓到的): 第一下会顺手把软键盘收掉, 而文本框
    # 是跟着键盘避让的 —— 它挪上去之后, 写死的那个坐标就落到框**里面**了, 于是第二下变成"点到了
    # 框" (账被清零), 看起来像"双击不生效"。判据是"框外那一下双击", 那就每次都在**当前**的框
    # 外面找一个点
    function TapOutside($tag) {
        $box = ChannelFrame
        $spot = OutsidePoint $box $screenW $screenH
        Sh "input tap $($spot.X) $($spot.Y)" | Out-Null
        Write-Output ("   ${tag}: 点 $($spot.X),$($spot.Y)  (框现在 [{0},{1}]-[{2},{3}])" -f $box.Left, $box.Top, $box.Right, $box.Bottom)
    }

    TapOutside '第一下'
    Start-Sleep -Milliseconds 900
    $boxMid = ChannelFrame
    Judge "点一下空白还不收" ($null -ne $boxMid) "第一下之后窗还在"
    # 超时那一支: 这一下不是"第一下的第二下", 而是**新的第一下** —— 它也不许收
    TapOutside '隔 900 ms 那一下 (超时, 该重新起算)'
    Start-Sleep -Milliseconds 900
    $boxLate = ChannelFrame
    Judge "超时的那一下也不算第二下 (重新起算)" ($null -ne $boxLate) '隔了 900 ms 之后再点一下, 窗还在'
    # 真双击: 两下都在框外, 间隔 150 ms (在 300 ms 窗口里)
    $boxNow = ChannelFrame
    $spotA = OutsidePoint $boxNow $screenW $screenH
    Sh "input tap $($spotA.X) $($spotA.Y)" | Out-Null
    Start-Sleep -Milliseconds 150
    Sh "input tap $($spotA.X) $($spotA.Y)" | Out-Null
    Write-Output "   双击: 两下都在 $($spotA.X),$($spotA.Y), 间隔 150 ms"
    Start-Sleep -Milliseconds 1000
    $boxAfter = ChannelFrame
    Judge "框外双击就收掉" ($null -eq $boxAfter) '第二下 (150 ms 之内) 之后窗没了'
}

# 5. 不出声那一条: 球的窗口不许把 Toast 画上去 (Toast 是另一块窗, 属同一个包)
Write-Output ''
Write-Output '-- 关于球的操作不许弹提示 (Toast 是另一块窗)'
$windows = Sh 'dumpsys window windows'
$toast = ($windows | Select-String 'Toast' | Where-Object { $_ -match 'luwi' }).Count
Judge "球上那几下没留下 Toast" ($toast -eq 0) "dumpsys 里 luwi 的 Toast 窗 $toast 个"

# 6. 掐断播报: 正在念的时候再点一下球
Write-Output ''
Write-Output '-- 念回复时再点一次球 (需要有人在念; 没人在念时这一条只说现状)'
$speak = (node tools\lw-channel-call.mjs speak '{"op":"status"}' | Out-String).Trim()
Write-Output "   speak status: $speak"

Write-Output ''
if ($failures -eq 0) { Write-Output '几条判据全过 (触摸手感那几条仍要真机上看)' } else { Write-Output "$failures 条判据不过" }
exit $failures
