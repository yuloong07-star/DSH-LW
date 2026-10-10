# 批次 5 追加那三条在设备上的实测 (2026-10-06 主人报的三条)
#
# 量的东西有四组, 每组都对应主人点过名的一句话:
#   1. **半隐又失效了**: 状态字落下之后空闲计时只重置一次 —— 取样里 `idleMs` 必须一直往上涨到 5000 之上,
#      然后框真的到了半隐位。加一条现场表的判据: `files/ball-idle.txt` 尾部不许是 `voice off` 与
#      `waiting` 互相接替那副样子 (那正是"每一拍重置一次"的指纹)
#   2. **回复框也跟着空闲自己收**: 宿主推一条回答 → 框自己张出来、球在框开着时不收边 → 20 s 没人碰框
#      自己收 → 再 5 s 球收边
#   3. **"正在想"只写文件就亮 / 双击打断 / 单击不打断** (2026-10-08 起状态来自 `$DSH_HOME/lw/ball-phase.json`,
#      这一条脚本自己写那份文件, 于是同时验了应用那边的 inotify 那条路); 双击之后队列里出现那句命令、
#      **字要留着** (那一轮还没被写成结束), 把文件写成空表之后字才落下; 对照组: 只点一下不打断
#      还有那一档**「失败」**: 文件里的 `last` 是 `error` / `max-tokens` 就写那两个字, `completed`
#      不写; 而它**不随时间落下** —— 点一下球 (顺路把语音开了) 才算认过, `failedAckAt` 跟着记下来
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
$package = 'io.github.yuloong07star.luwi'
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

# 「正在想」那份文件 (2026-10-08 起状态从 `overlay op=phase` 改成了这份文件: 宿主写, 应用用
# inotify 读). 这一条脚本**自己写它** —— 于是"只写文件就能让球在想"这件事本身就是被验的东西,
# 而那也是应用与宿主之间那条约定唯一没有设备也能量到的接口
$phaseFile = "/data/user/0/$package/files/dsh-home/lw/ball-phase.json"

function WritePhase($turns, $last = $null) {
    # $turns: 形如 @( @{ id = 'session-a'; startedAt = 1234 } ) 的数组; 空数组 = 没有在跑的轮
    # $last (2026-10-08 加): 最近结束的那一轮, 形如 @{ id = 'session-a'; at = 1234; kind = 'error' }
    # —— 不给就是"没有失败"那一档 (旧宿主、宿主刚重启写出来的都是这一份)
    $payload = @{ v = 1; at = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); turns = @($turns) }
    if ($null -ne $last) { $payload['last'] = $last }
    $json = $payload | ConvertTo-Json -Compress -Depth 5
    # **JSON 不进 shell 命令行**: 一过 adb shell, 那些引号就被远端 shell 吃掉了 (实测写出来的是一份
    # 没有引号的"JSON", 应用那边报 "不是 JSON" —— 那条排障读数就是这么发现的)。所以先落在本地文件,
    # 推进 /data/local/tmp, 再 `run-as` 拷进去 (那个路径每个脚本都在用, run-as 读得到)
    $local = 'D:\apk\.lwtmp\lw-ball-phase.json'
    New-Item -ItemType Directory -Force -Path (Split-Path $local) | Out-Null
    [IO.File]::WriteAllText($local, $json, (New-Object System.Text.UTF8Encoding($false)))
    & $adb -s $Serial push $local /data/local/tmp/lw-ball-phase.json | Out-Null
    $directory = Split-Path $phaseFile
    Sh "run-as $package sh -c 'mkdir -p $directory && cp /data/local/tmp/lw-ball-phase.json $phaseFile.tmp && mv $phaseFile.tmp $phaseFile'" | Out-Null
    Start-Sleep -Milliseconds 700
}

# 从 `op=state` 的 JSON 里取一个数 / 一个字符串 (拿不到就是 null, 不猜)
#
# **数一律按 [long] 收**: `failedAt` / `failedAckAt` 是 epoch 毫秒 (1.7e12 那个量级), 用 [int] 接
# 会当场溢出 ("Value was either too large or too small for an Int32")
function Field($state, $name) {
    $m = [regex]::Match($state, '"' + $name + '"\s*:\s*(-?\d+|true|false|"[^"]*")')
    if (-not $m.Success) { return $null }
    $value = $m.Groups[1].Value
    if ($value -eq 'true') { return $true }
    if ($value -eq 'false') { return $false }
    if ($value.StartsWith('"')) { return $value.Trim('"') }
    return [long]$value
}

# 球那一块窗 (NOT_FOCUSABLE 把它与菜单 / 通道那两块可获焦的窗分开; 2026-10-09 加的涟漪窗也是
# NOT_FOCUSABLE 的, 而它**非触摸** —— 那一条是它与球的区别, 见 lw-ball-check.ps1)
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

# 通道那块框 (可获焦的那一块 overlay 窗)
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
    # **先等球自己收边 (RESTED)** —— 这一组量的是"碰一下之后 5 s 收边", 而那一档只在"球先收着"时成立:
    # 球已经全露着时碰一下是"进语音输入"(那是设计), 那条链一开就占着 10 s, 于是 5 s 那一条永远不成立。
    # 2026-10-08 在这台模拟器上就是这样假失败的 (前一次测量把球留在了"全露着 + 语音链开着"那一档)
    # 等它安静下来: "正在想 / 正在听 / 正在念"那几档本来就不收边 (那是设计, 也有单测钉着), 所以这一组
    # 要等的是**没有状态**那一档 —— 上一次测量留下的那一轮可能还在跑
    for ($settle = 0; $settle -lt 60; $settle++) {
        if ((Field (State) 'peeked') -eq $true) { break }
        Start-Sleep -Milliseconds 1000
    }
    $ready = (Field (State) 'peeked') -eq $true
    if ($ready) {
        Write-Output '   (等球收边: 已到半隐位)'
    } else {
        $stuck = State
        Write-Output ("   (等球收边: 仍没收边 —— ballWait={0}, peekBlocked={1}, word={2}, 下面这一组会假失败)" -f `
            (Field $stuck 'ballWait'), (Field $stuck 'peekBlocked'), (Field $stuck 'word'))
    }
    if ($null -eq (TapBallOnce)) { throw 'the ball went away between measurements' }
    # **取 11 次 (5.5 s)**: 收边那一档是 5 s, 而每一次取样自己也要花几十毫秒 —— 取 8 次 (4 s) 时最后
    # 那个 idleMs 常常只有 3.9 s, 于是"过了 5 s 那一档"与"真的到半隐位"这两条判据会假失败
    # (2026-10-08 在这台模拟器上就是这么挂的)
    $samples = @()
    foreach ($i in 1..11) {
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

# ── 3. "正在想": 文件说了算 + 双击打断 + 单击不打断 ───────────────────────────
if (-not $NoTap) {
    Write-Output ''
    Write-Output '-- 3. 正在想: 只写文件就该亮 (inotify), 双击打断, 单击不打断'
    # 这一条现在**只写那份文件**, 一个通道调用都不发 —— 于是它同时验了"应用自己在读文件"这条路
    WritePhase @(@{ id = 'session-lw-check-1'; startedAt = 1000 })
    $state = State
    $word = Field $state 'word'
    # 那个字是**本地化过的** (`BallWord.label` 读的是应用自己的字符串), 所以英文环境上是 "Thinking":
    # 把中文写死会让这条判据在英文设备上假失败 (2026-10-08 模拟器上就是这个样子)
    Judge '只写文件就让球写着正在想' ($word -eq '正在想' -or $word -eq 'Thinking') "word=$word"
    Judge '那一场被记下来了' ((Field $state 'session') -eq 'session-lw-check-1') `
        "session=$((Field $state 'session'))"
    $color1 = Field $state 'ringColor'
    Judge '它有一个环色' ($null -ne $color1 -and $color1 -ne 0) "ringColor=$color1"

    # 第二场开始得更晚: 球要跟着换成它 (取最近开始的那一场)
    WritePhase @(
        @{ id = 'session-lw-check-1'; startedAt = 1000 },
        @{ id = 'session-lw-check-2'; startedAt = 2000 }
    )
    $state = State
    Judge '两场在跑时取最近开始的那一场' ((Field $state 'session') -eq 'session-lw-check-2') `
        "session=$((Field $state 'session'))"

    # 回到第一场: 颜色要是**同一个** (按会话固定, 不是每次重发)
    WritePhase @(@{ id = 'session-lw-check-1'; startedAt = 1000 })
    $state = State
    Judge '同一个会话拿回同一个颜色' ((Field $state 'ringColor') -eq $color1) `
        "ringColor=$((Field $state 'ringColor')) 期望=$color1"

    # 双击: 打断那一句要进队列, 而**字要留着** —— 那一轮是文件说在跑的, 打断没成之前不该落
    $before = Field $state 'interrupts'
    TapBallTwice 150 | Out-Null
    Start-Sleep -Milliseconds 2500
    $state = State
    $after = Field $state 'interrupts'
    $inbox = InboxTail 3
    Write-Output "   state: $state"
    Judge '双击那一句进了收件箱' ($inbox -match '打断当前回答') '队列尾部看得到 打断当前回答'
    Judge '打断记了一笔' ($after -eq ($before + 1)) "interrupts $before -> $after"
    Judge '字还在 (那一轮还没被文件写成结束)' (-not [string]::IsNullOrEmpty([string](Field $state 'word'))) `
        "word=$((Field $state 'word'))"

    # 文件说"没有在跑的轮": 字落下 (这是"实时"那一条的判据: 只动文件, 不动通道)
    WritePhase @()
    $state = State
    Judge '文件写成空表之后字落下' ([string]::IsNullOrEmpty([string](Field $state 'word'))) `
        "word=$((Field $state 'word'))"

    # 对照组: 只点一下 —— 那一下等过 [THINKING_TAP_MS] 才做原来的动作 (开语音), 而**绝不**写打断那一句
    WritePhase @(@{ id = 'session-lw-check-3'; startedAt = 3000 })
    $before = Field (State) 'interrupts'
    TapBallOnce | Out-Null
    Start-Sleep -Milliseconds 3000
    $state = State
    Judge '只点一下不打断' ((Field $state 'interrupts') -eq $before) `
        "interrupts 仍然 $((Field $state 'interrupts'))"
    # 那一下确实做了事 (开语音: 这台设备没下模型时会回一句拒绝), 只是比双击窗口慢
    Judge '那一下确实做了事 (不是被吞掉)' (-not [string]::IsNullOrEmpty([string](Field $state 'said'))) `
        "said=$((Field $state 'said'))"
    # 收尾: 这一下是切换, 再点一下把语音窗口收回去 (免得脚本走完还开着麦克风); 然后清掉文件
    TapBallOnce | Out-Null
    Start-Sleep -Milliseconds 1500
    WritePhase @()
    $state = State
    Judge '收尾: 字落下' ([string]::IsNullOrEmpty([string](Field $state 'word'))) "word=$((Field $state 'word'))"

    # ── 「失败」那一档 (2026-10-08): 宿主把 `turn/end` 的原因写进 `last`, 球上写两个字 ——
    #    而且**它不随时间落下**: 只有点一下球才算认过 ─────────────────────────────────
    $failAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    WritePhase @() @{ id = 'session-lw-check-1'; at = $failAt; kind = 'error'; why = 'lw-ball-tap-check' }
    $state = State
    $word = Field $state 'word'
    Judge '文件里有一条失败就该写着失败' ($word -eq '失败' -or $word -eq 'Failed') "word=$word"
    Judge '失败的原因读得到' ((Field $state 'failedKind') -eq 'error') `
        "failedKind=$((Field $state 'failedKind'))"
    Judge '理由只进读数不上球' ((Field $state 'failedWhy') -eq 'lw-ball-tap-check') `
        "failedWhy=$((Field $state 'failedWhy'))"

    # 一条"不算失败"的原因 (正常结束): 那两个字不许亮
    WritePhase @() @{ id = 'session-lw-check-1'; at = $failAt + 1; kind = 'completed' }
    Judge '正常结束不算失败' ([string]::IsNullOrEmpty([string](Field (State) 'word'))) `
        "word=$((Field (State) 'word'))"

    # 被截断那一档也算失败; 然后**点一下球** —— 认过那一笔账要落在 failedAckAt 上
    WritePhase @() @{ id = 'session-lw-check-1'; at = $failAt + 2; kind = 'max-tokens' }
    $word = Field (State) 'word'
    Judge '被截断也算失败' ($word -eq '失败' -or $word -eq 'Failed') "word=$word"
    TapBallOnce | Out-Null
    Start-Sleep -Milliseconds 1200
    $state = State
    $word = Field $state 'word'
    # 落下之后未必是空的: 那一下顺路把语音开了 ("失败"让位给"正在听", 见 BallStatus.wordFor),
    # 所以判据是"那两个字不在了", 不是"球上什么都没有"
    Judge '点一下球就算认过 (那两个字落下)' ($word -ne '失败' -and $word -ne 'Failed') "word=$word"
    Judge '那一笔账记在 failedAckAt 上' ((Field $state 'failedAckAt') -eq ($failAt + 2)) `
        "failedAckAt=$((Field $state 'failedAckAt')) 期望=$($failAt + 2)"
    # 收尾: 那一下把语音开起来了, 再点一下收回去, 然后把文件清回空表
    TapBallOnce | Out-Null
    Start-Sleep -Milliseconds 1500
    WritePhase @()
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
