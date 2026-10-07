# 批次 5 追加那三条在设备上的实测 (2026-10-06 主人报的三条)
#
# 量的东西有四组, 每组都对应主人点过名的一句话:
#   1. **半隐又失效了**: 状态字落下之后空闲计时只重置一次 —— 取样里 `idleMs` 必须一直往上涨到 5000 之上,
#      然后框真的到了半隐位。加一条现场表的判据: `files/ball-idle.txt` 尾部不许是 `voice off` 与
#      `waiting` 互相接替那副样子 (那正是"每一拍重置一次"的指纹)
#   2. **回复框也跟着空闲自己收**: 宿主推一条回答 → 框自己张出来、球在框开着时不收边 → 20 s 没人碰框
#      自己收 → 再 5 s 球收边
#   3. **"正在想"双击打断**: 双击之后队列里出现那句命令、`phase` 落回 idle、`interrupts` 加一;
#      对照组: 只点一下**不**打断
#   4. **文本框 7 行**: 一行的高度与塞满之后的高度之比要落在 7 行那一档 (5 行 ≈3.3 倍, 7 行 ≈4.4 倍),
#      而且再多打一段高度不再涨
#
# 用法: pwsh -File tools/lw-ball-tap-check.ps1 -Serial 10CEB40568000ZB
#       pwsh -File tools/lw-ball-tap-check.ps1 -Serial emulator-5554 -NoTap -NoBox   # 只读, 不动别的手机
#
# 前提: 应用已经跑起来 (球已在屏上)。通道口与令牌从宿主的环境里读, 与 lw-batch5-check.ps1 同一套。
# **触摸那两组只有真机算数** (注入的 tap 走 input reader, 与手点同一条路); 模拟器上跑的是时序与状态
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$NoTap,
    [switch]$NoBox,
    [int]$BoxIdleMs = 20000
)

$ErrorActionPreference = 'Stop'
$adb = if ($env:LW_ADB) { $env:LW_ADB } else { 'D:\apk\Sdk\platform-tools\adb.exe' }
$package = 'io.github.miuzarte.littlewhale'
$forward = if ($Serial -eq 'emulator-5554') { 29993 } else { 29992 }

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

function Call($json) { (node tools\lw-channel-call.mjs overlay $json | Out-String).Trim() }

function State { Call '{"op":"state"}' }

# 从 `op=state` 的 JSON 里取一个数 / 一个字符串 (拿不到就是 null, 不猜)
function Field($state, $name) {
    $m = [regex]::Match($state, '"' + $name + '"\s*:\s*(-?\d+|true|false|"[^"]*")')
    if (-not $m.Success) { return $null }
    $value = $m.Groups[1].Value
    if ($value -eq 'true') { return $true }
    if ($value -eq 'false') { return $false }
    if ($value.StartsWith('"')) { return $value.Trim('"') }
    return [int]$value
}

# 球那一块窗 (NOT_FOCUSABLE 把它与菜单 / 通道那两块可获焦的窗分开)
function BallFrame {
    $out = Sh 'dumpsys window windows'
    $hit = ($out | Select-String 'ty=APPLICATION_OVERLAY') |
        Where-Object {
            $block = $out[($_.LineNumber - 3)..($_.LineNumber + 1)]
            ($block -join ' ') -match 'littlewhale' -and ($block -join ' ') -match 'NOT_FOCUSABLE'
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

# 通道那块框 (可获焦的那一块 overlay 窗)
function ChannelFrame {
    $out = Sh 'dumpsys window windows'
    $hit = ($out | Select-String 'ty=APPLICATION_OVERLAY') |
        Where-Object {
            $block = $out[($_.LineNumber - 3)..($_.LineNumber + 1)]
            ($block -join ' ') -match 'littlewhale' -and ($block -join ' ') -notmatch 'NOT_FOCUSABLE'
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

# 球在那块窗上**看得见**的那一点: 半隐着的球有一半在屏外, 所以起点要钳进露出来的那一半
function BallPoint {
    $frame = BallFrame
    if ($null -eq $frame) { return $null }
    $x = [int](($frame.Left + $frame.Right) / 2)
    if ($frame.Left -lt 0) { $x = [Math]::Min($frame.Right - 20, 20) }
    elseif ($frame.Right -gt $screenW) { $x = [Math]::Max($frame.Left + 20, $screenW - 20) }
    $y = [int](($frame.Top + $frame.Bottom) / 2)
    return @{ X = [int]$x; Y = [int]$y; Frame = $frame }
}

function TapBallOnce {
    $at = BallPoint
    if ($null -eq $at) { return $null }
    Sh "input tap $($at.X) $($at.Y)" | Out-Null
    return $at
}

# 连点两下 (间隔 ms): 双击那一支要的就是它
function TapBallTwice($gapMs) {
    $at = BallPoint
    if ($null -eq $at) { return $null }
    Sh "input tap $($at.X) $($at.Y)" | Out-Null
    Start-Sleep -Milliseconds $gapMs
    Sh "input tap $($at.X) $($at.Y)" | Out-Null
    return $at
}

# 队列尾部几行 (双击写出去的那句命令要从这里看)
function InboxTail($lines = 4) {
    return (Sh "run-as $package sh -c 'tail -n $lines files/dsh-home/voice/inbox.jsonl'") -join "`n"
}

# 现场表尾部几行 (空闲计时那一笔账的指纹)
function TraceTail($lines = 40) {
    return (Sh "run-as $package sh -c 'tail -n $lines files/ball-idle.txt'") -join "`n"
}

$failures = 0
function Judge($name, $ok, $detail) {
    if ($ok) { Write-Output "ok   $name  ($detail)" } else { Write-Output "FAIL $name  ($detail)"; $script:failures += 1 }
}

Write-Output "== $Serial  ($screenW x $screenH, density $density, 球 $ball px, 半隐 $half px)"
Write-Output ''

Call '{"op":"show"}' | Out-Null
Start-Sleep -Milliseconds 1200
if ($null -eq (BallFrame)) { throw "the ball is not up on $Serial : run lw_overlay op=show first" }

# ── 1. 空闲计时必须是"状态字落下只重置一次" ─────────────────────────────────
#
# 主人 2026-10-06 报的第一条: ball 半隐藏又失效了。真病根是空闲计时被每一拍 (400 ms) 重置, 所以这一组
# 量的是**那个数一直往上涨**: 碰一下球 → 每 500 ms 读一次 idleMs (读 8 次 = 4 s) → 再看它到底收没收
if (-not $NoTap) {
    Write-Output '-- 1. 碰一下之后 idleMs 要一直涨 (它涨不上去就是"每拍重置")'
    if ($null -eq (TapBallOnce)) { throw 'the ball went away between measurements' }
    $samples = @()
    foreach ($i in 1..8) {
        Start-Sleep -Milliseconds 500
        $state = State
        $samples += [pscustomobject]@{
            Idle = (Field $state 'idleMs'); Wait = (Field $state 'ballWait'); Peeked = (Field $state 'peeked')
        }
        $last = $samples[-1]
        Write-Output ("   #$i  idleMs=$($last.Idle)  ballWait=$($last.Wait)  peeked=$($last.Peeked)")
    }
    $rising = $true
    for ($i = 1; $i -lt $samples.Count; $i++) {
        if ($samples[$i].Idle -le $samples[$i - 1].Idle) { $rising = $false }
    }
    Judge 'idleMs 每一次取样都在涨' $rising "最后一次 $($samples[-1].Idle) ms"
    Judge '取样结束时已经过了 5 s 那一档' ($samples[-1].Idle -gt 4000) "idleMs=$($samples[-1].Idle)"
    # 收边那一条用坐标量, 不看状态里的字: 贴边半隐就是"左边界 <= -半隐"或"右边界 >= 屏宽 + 半隐"
    $peeked = BallFrame
    $hidden = ($peeked.Left -le -$half) -or ($peeked.Right -ge $screenW + $half)
    Judge '5 s 之后真的到了半隐位' $hidden "frame=[$($peeked.Left),$($peeked.Top)]-[$($peeked.Right),$($peeked.Bottom)]"
    # 现场表那一副"指纹": 病根在的时候 `voice off` 与 `waiting` 每隔一拍就互相接替 (40 行里能有一半)
    $trace = TraceTail 40
    $voiceOff = ([regex]::Matches($trace, 'voice off')).Count
    Judge '现场表尾部没有"每拍重置"那副样子' ($voiceOff -le 3) "尾部 40 行里 voice off 出现 $voiceOff 次"
} else {
    Write-Output '-- 1. 跳过 (只读那一档: -NoTap)'
}

# ── 2. 回复框也跟着空闲自己收, 收完球再收边 ──────────────────────────────────
if (-not $NoBox) {
    Write-Output ''
    Write-Output "-- 2. 回复到了框自己张, 没人碰 $BoxIdleMs ms 自己收, 再 5 s 球收边"
    $stamp = "lw-ball-tap-$([int](Get-Date -UFormat %s))"
    Call "{`"op`":`"reply`",`"text`":`"$stamp`"}" | Out-Null
    Start-Sleep -Milliseconds 1500
    $state = State
    $box = ChannelFrame
    Judge '回复把框张出来了' ($null -ne $box) "通道那块窗挂上了 (frame=[$($box.Left),$($box.Top)]-[$($box.Right),$($box.Bottom)])"
    Judge '状态里 channel 是 true' ((Field $state 'channel') -eq $true) 'OverlayState.channel'
    Judge '框开着时球不收边 (channel 那道闸)' ((Field $state 'ballWait') -eq 'channel') "ballWait=$((Field $state 'ballWait'))"
    Judge '框的闲置读数在涨' ($null -ne (Field $state 'boxIdleMs')) "boxIdleMs=$((Field $state 'boxIdleMs'))"

    Write-Output "   等 $($BoxIdleMs + 4000) ms (框那一笔账 20 s 到点 + 一拍)..."
    Start-Sleep -Milliseconds ($BoxIdleMs + 4000)
    $state = State
    $boxAfter = ChannelFrame
    $goneDetail = "channel=$((Field $state 'channel')) 窗=$([bool]($null -ne $boxAfter))"
    Judge '没人碰 20 s 之后框自己收了' (($null -eq $boxAfter) -and ((Field $state 'channel') -ne $true)) $goneDetail

    Start-Sleep -Milliseconds 6500
    $ballAfter = BallFrame
    $hidden = ($ballAfter.Left -le -$half) -or ($ballAfter.Right -ge $screenW + $half)
    Judge '框收掉之后球收边了 (再 5 s)' $hidden "frame=[$($ballAfter.Left),$($ballAfter.Top)]-[$($ballAfter.Right),$($ballAfter.Bottom)]"
} else {
    Write-Output ''
    Write-Output '-- 2. 跳过 (只读那一档: -NoBox)'
}

# ── 3. "正在想"双击打断 (对照组: 只点一下不打断) ─────────────────────────────
if (-not $NoTap) {
    Write-Output ''
    Write-Output '-- 3. 正在想: 双击打断 (状态与那一轮), 单点不打断'
    Call '{"op":"phase","phase":"thinking"}' | Out-Null
    Start-Sleep -Milliseconds 1200
    $state = State
    $word = Field $state 'word'
    Judge '球上写着正在想 (先决条件)' ($word -eq '正在想') "word=$word"
    $before = Field $state 'interrupts'

    TapBallTwice 150 | Out-Null
    Start-Sleep -Milliseconds 2500
    $state = State
    $after = Field $state 'interrupts'
    $inbox = InboxTail 3
    Write-Output "   state: $state"
    Judge '双击那一句进了收件箱' ($inbox -match '打断当前回答') '队列尾部看得到 打断当前回答'
    Judge '状态落回 idle' ((Field $state 'phase') -eq 'idle') "phase=$((Field $state 'phase'))"
    Judge '球上那三个字落下了' ([string]::IsNullOrEmpty([string](Field $state 'word'))) "word=$((Field $state 'word'))"
    Judge '打断记了一笔' ($after -eq ($before + 1)) "interrupts $before -> $after"

    # 对照组: 只点一下 —— 它要等过 0.3 s 那双击窗口才做原来那一支, 而**绝不**写打断那一句
    Call '{"op":"phase","phase":"thinking"}' | Out-Null
    Start-Sleep -Milliseconds 1000
    TapBallOnce | Out-Null
    Start-Sleep -Milliseconds 3000
    $state = State
    $single = Field $state 'interrupts'
    Judge '只点一下不打断' ($single -eq $after) "interrupts 仍然 $single"
    # 单点那一支落到了"收/开语音窗口"上 (正在想这一档): 把它收回去, 免得脚本走完还开着麦克风
    Start-Sleep -Milliseconds 1200
    TapBallOnce | Out-Null
    Start-Sleep -Milliseconds 800
    Write-Output "   收尾: $(State)"
} else {
    Write-Output ''
    Write-Output '-- 3. 跳过 (只读那一档: -NoTap)'
}

# ── 4. 文本框 7 行 (一行那一档 ≈1 倍, 塞满之后 ≈4.4 倍; 5 行那一档只有 ≈3.3 倍) ──
if (-not $NoBox) {
    Write-Output ''
    Write-Output '-- 4. 文本框: 行数上限从 5 加到 7'
    # 先把框里的字清掉 (回车发送会清空并顺手把框收起来), 再张开量"一行那一个高度"
    Call '{"op":"channel"}' | Out-Null
    Start-Sleep -Milliseconds 1200
    Sh "input text 'lw-tap-check'" | Out-Null
    Start-Sleep -Milliseconds 500
    Sh 'input keyevent 66' | Out-Null
    Start-Sleep -Milliseconds 1200
    Call '{"op":"channel"}' | Out-Null
    Start-Sleep -Milliseconds 1200
    $one = ChannelFrame
    if ($null -eq $one) { Write-Output 'FAIL 框没能再张出来, 这一条量不了'; $failures += 1 }
    else {
        Write-Output "   一行时: 高 $($one.H) px"
        $chunk = 'aaaaaaaaaaaaaaaaaaaaaaaaaa'
        foreach ($i in 1..6) { Sh "input text '$chunk'" | Out-Null; Start-Sleep -Milliseconds 400 }
        Start-Sleep -Milliseconds 800
        $full = ChannelFrame
        Write-Output "   六段之后: 高 $($full.H) px"
        foreach ($i in 1..3) { Sh "input text '$chunk'" | Out-Null; Start-Sleep -Milliseconds 400 }
        Start-Sleep -Milliseconds 800
        $more = ChannelFrame
        Write-Output "   再多三段: 高 $($more.H) px"
        $ratio = [math]::Round($full.H / $one.H, 2)
        Judge '塞满之后的高度是 7 行那一档 (≈4.4 倍, 5 行只有 ≈3.3)' (($ratio -ge 4.1) -and ($ratio -le 4.9)) "比值 $ratio"
        Judge '再多打也不长了 (到顶就在里面滚)' ($more.H -eq $full.H) "$($full.H) -> $($more.H) px"
    }
    # 收尾: 把框收掉 (框外双击那一条账由 lw-batch5-check.ps1 量, 这里只把它请下去)
    $frame = ChannelFrame
    if ($null -ne $frame) {
        $y = [Math]::Max([Math]::Min($frame.Top - 40, $screenH - 40), 40)
        Sh "input tap 30 $y" | Out-Null
        Start-Sleep -Milliseconds 150
        Sh "input tap 30 $y" | Out-Null
        Start-Sleep -Milliseconds 800
        Write-Output "   收尾: 框 $([bool]($null -ne (ChannelFrame)))"
    }
    Write-Output "   收尾 state: $(State)"
} else {
    Write-Output ''
    Write-Output '-- 4. 跳过 (只读那一档: -NoBox)'
}

Write-Output ''
if ($failures -eq 0) { Write-Output '几条判据全过' } else { Write-Output "$failures 条判据不过" }
exit $failures
