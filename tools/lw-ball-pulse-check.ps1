# 「说/听那两档的呼吸与涟漪」实测脚本 (2026-10-09 加的)
#
# 动效是"看着对"的那一类, 而真正会坏的是**窗口那一层**, 那几件事都能量:
#   1. 念的时候 `overlay op=state` 的 `pulse` 是 `out`, 球那一档写着状态词
#   2. 那块涟漪窗真的挂在窗口管理器上: 96 dp 见方, 圆心与球心对得上, 而**球窗仍是 48 dp**
#   3. 它是**非触摸**的 (`NOT_TOUCHABLE`): 球"只吃自己那 48 dp"那条性质全靠它, 少了这一条
#      球周围会多出一圈 96 dp 见方的吃手指死区 (那正是这次要防的回归)
#   4. 那一档过去之后它自己摘掉 (屏上不许留一块看不见的常驻窗)
#   5. 「正在听」那一档 `pulse` 是 `in` —— 与说相反的那一套 (主人 2026-10-09 追加的)
#
# 为什么要有它: `lw-ball-check.ps1` 量拖拽与半隐、`lw-ball-tap-check.ps1` 量手势, 而"多挂了一块
# 比球大的窗"这件事没有任何一条量过
#
# 用法: pwsh -File tools/lw-ball-pulse-check.ps1 -Serial emulator-5554
#       pwsh -File tools/lw-ball-pulse-check.ps1 -Serial emulator-5554 -SkipSay   # 只量听那半边
#
# 通道那两行 (endpoint / token) 只在宿主的 environ 里: 这份脚本按 root -> su -> run-as 依次试,
# 三条都读不到就直说"这台设备量不了", 不当成通过
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$SkipSay
)

$ErrorActionPreference = 'Stop'
$adb = if ($env:LW_ADB) { $env:LW_ADB } else { 'D:\apk\Sdk\platform-tools\adb.exe' }
$package = 'io.github.miuzarte.littlewhale'
$forward = if ($Serial -eq 'emulator-5554') { 29997 } else { 29998 }

function Sh($command) { & $adb -s $Serial shell $command }

& $adb -s $Serial push tools\lw-device\host-env.sh /data/local/tmp/lw-host-env.sh | Out-Null
$raw = ''
foreach ($try in @('sh /data/local/tmp/lw-host-env.sh', "su -c 'sh /data/local/tmp/lw-host-env.sh'", "run-as $package sh /data/local/tmp/lw-host-env.sh")) {
    $got = (Sh "$try 2>/dev/null" | Out-String)
    if ($got -match 'LW_CHANNEL_ENDPOINT') { $raw = $got; break }
}
# **要 Trim**: adb shell 回来的每一行带着 `\r`, 不剪掉的话 `adb forward` 会报 "Invalid destination port"
$endpoint = (($raw | Select-String 'LW_CHANNEL_ENDPOINT=(.+)').Matches.Groups[1].Value).Trim()
$token = (($raw | Select-String 'LW_CHANNEL_TOKEN=(.+)').Matches.Groups[1].Value).Trim()
if (-not $endpoint -or -not $token) {
    Write-Output "FAIL 读不到通道 (endpoint=$endpoint token=$([bool]$token)) —— 这台设备量不了"
    exit 1
}
& $adb -s $Serial forward "tcp:$forward" "tcp:$(($endpoint -split ':')[-1])" | Out-Null
$env:LW_CHANNEL_ENDPOINT = "127.0.0.1:$forward"
$env:LW_CHANNEL_TOKEN = $token

function Call($method, $json) { (node tools\lw-channel-call.mjs $method $json | Out-String).Trim() }

$density = [int]((Sh 'wm density' | Select-Object -Last 1) -replace '.*: ', '')
$ballPx = [int][Math]::Round(48 * $density / 160)
$pulsePx = [int][Math]::Round(96 * $density / 160)

function WindowRect($mode) {
    # $mode: 'ball' = 不吃焦**且可触摸**那一块 (NOT_FOCUSABLE 且没有 NOT_TOUCHABLE)
    #        'pulse' = 非触摸那一块 (2026-10-09 加的涟漪窗)
    $out = Sh 'dumpsys window windows'
    $hit = ($out | Select-String 'ty=APPLICATION_OVERLAY') |
        Where-Object {
            $block = $out[($_.LineNumber - 3)..($_.LineNumber + 1)] -join ' '
            if ($block -notmatch 'littlewhale') { return $false }
            if ($mode -eq 'pulse') { return $block -match 'NOT_TOUCHABLE' }
            return $block -match 'NOT_FOCUSABLE' -and $block -notmatch 'NOT_TOUCHABLE'
        } |
        Select-Object -First 1
    if (-not $hit) { return $null }
    # **标题与 flags 按内容找, 不按行号**: `Select-String` 报的是 1 基行号, 而数组是 0 基的 ——
    # 差这一格就会把 `bhv=DEFAULT` 当成 flags (第一次跑就是这么读错的)
    $head = $out[[Math]::Max(0, $hit.LineNumber - 8)..($hit.LineNumber - 1)]
    $title = ($head | Where-Object { $_ -match 'Window #\d+ Window\{' } | Select-Object -Last 1)
    $tail = $out[$hit.LineNumber..([Math]::Min($hit.LineNumber + 6, $out.Count - 1))]
    $flags = ($tail | Where-Object { $_ -match '^\s*fl=' } | Select-Object -First 1)
    if (-not $title) { $title = '' }
    if (-not $flags) { $flags = '' }
    for ($i = $hit.LineNumber; $i -lt [Math]::Min($hit.LineNumber + 24, $out.Count); $i++) {
        $f = [regex]::Match($out[$i], 'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
        if ($f.Success) {
            return [pscustomobject]@{
                Left = [int]$f.Groups[1].Value; Top = [int]$f.Groups[2].Value
                Right = [int]$f.Groups[3].Value; Bottom = [int]$f.Groups[4].Value
                Width = [int]$f.Groups[3].Value - [int]$f.Groups[1].Value
                Height = [int]$f.Groups[4].Value - [int]$f.Groups[2].Value
                Title = $title.Trim(); Flags = $flags.Trim()
            }
        }
    }
    return $null
}

function BallWindow { WindowRect 'ball' }
function PulseWindow { WindowRect 'pulse' }

function StateField($key) {
    $state = Call 'overlay' '{"op":"state"}'
    # 前面那一个字符一起管住: 不锚住的话 `phase` 会命中 `ballPhase`
    $m = [regex]::Match($state, '(?:^|[{,\s])"' + $key + '":\s*"?([^",\r\n]*)"?')
    if ($m.Success) { return $m.Groups[1].Value.Trim() }
    return '<missing>'
}

$failures = 0
function Judge($name, $ok, $detail) {
    if ($ok) { Write-Output "ok   $name  ($detail)" } else { Write-Output "FAIL $name  ($detail)"; $script:failures += 1 }
}

Write-Output "== $Serial  ($package, density ${density}: 球 $ballPx px, 涟漪 $pulsePx px)"
Write-Output ''

# 0. 授权与球: 缺一样后面都无从谈起
if ((Call 'overlay' '{"op":"state"}') -notmatch '"permission":\s*true') {
    Write-Output 'FAIL 这台设备没给「显示在其他应用上层」'
    Write-Output "     给法: adb -s $Serial shell appops set $package SYSTEM_ALERT_WINDOW allow"
    exit 1
}
if ($null -eq (BallWindow)) { Call 'overlay' '{"op":"show"}' | Out-Null; Start-Sleep -Milliseconds 1500 }
$ballFrame = BallWindow
$ballDetail = if ($ballFrame) {
    "frame=[$($ballFrame.Left),$($ballFrame.Top)]-[$($ballFrame.Right),$($ballFrame.Bottom)] $($ballFrame.Width)x$($ballFrame.Height)"
}
else { '窗没挂上' }
Judge '球起得来 (先决条件)' ($null -ne $ballFrame) $ballDetail
if ($null -eq $ballFrame) { Write-Output ''; Write-Output "$failures 条判据不过"; exit $failures }
Judge '球窗还是 48 dp' ($ballFrame.Width -eq $ballPx -and $ballFrame.Height -eq $ballPx) `
    "$($ballFrame.Width)x$($ballFrame.Height) px (要 $ballPx)"
Judge '没在说/听时没有涟漪窗' ($null -eq (PulseWindow)) '这一档不该挂那块窗'

# 1. 「正在说」: 让引擎念一句长的, 念的同时读 state 与窗口
if (-not $SkipSay) {
    Write-Output ''
    Write-Output '-- 正在说: pulse=out + 涟漪窗在、非触摸、96 dp、与球心对齐, 念完自己摘掉'
    $text = '涟漪测试, 这一句故意说长一点, 好让球上那三个字和外面那一圈有时间动起来, 说完它就该自己收回去'
    # **念那一条要放在后台**: 它一直等到引擎念完才回话 (几秒到几十秒), 而这一条要量的是"念的那些秒
    # 里"屏上有什么。**不经过 node 的 argv**: PowerShell 往 node 传一段带引号的 JSON 会被剥掉引号
    # (第一次跑就是这么坏的), 所以这里直接按协议发那一条, 与环境里那两个变量同一个来源
    $payload = '{"op":"speak","text":"' + $text + '"}'
    $say = Start-Job -ScriptBlock {
        param($port, $token, $json)
        $body = @{ method = 'speak'; token = $token } + ($json | ConvertFrom-Json -AsHashtable)
        $line = ($body | ConvertTo-Json -Compress -Depth 8) + "`n"
        $client = [System.Net.Sockets.TcpClient]::new('127.0.0.1', $port)
        try {
            $stream = $client.GetStream()
            $writer = [System.IO.StreamWriter]::new($stream, [System.Text.UTF8Encoding]::new($false))
            $writer.NewLine = "`n"
            $writer.AutoFlush = $true
            $writer.Write($line)
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.UTF8Encoding]::new($false))
            $reader.ReadLine()
        }
        finally { $client.Close() }
    } -ArgumentList $forward, $token, $payload

    $sawOut = $false
    $sawPulse = $null
    $sawBall = $null
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline -and -not $sawOut) {
        if ((StateField 'pulse') -eq 'out') {
            $sawOut = $true
            # 球与涟漪**同一刻各读一次**: 说话时球会从半隐滑回来, 拿几十秒前那个坐标比对是错的
            $sawBall = BallWindow
            $sawPulse = PulseWindow
            $word = StateField 'word'
            Write-Output "   pulse=out, word=$word"
        }
        Start-Sleep -Milliseconds 250
    }
    Judge '念的时候 pulse 是 out' $sawOut '`overlay op=state` 的 pulse'
    Judge '念的时候球上有状态词' ((StateField 'word').Length -gt 0) ('word=' + (StateField 'word'))
    if ($sawPulse) {
        Write-Output "   涟漪窗: $($sawPulse.Title)"
        Write-Output "      frame=[$($sawPulse.Left),$($sawPulse.Top)]-[$($sawPulse.Right),$($sawPulse.Bottom)] $($sawPulse.Width)x$($sawPulse.Height)"
        Write-Output "      $($sawPulse.Flags)"
        Judge '涟漪窗是 96 dp 见方' ($sawPulse.Width -eq $pulsePx -and $sawPulse.Height -eq $pulsePx) `
            "$($sawPulse.Width)x$($sawPulse.Height) px (要 $pulsePx)"
        $dcx = ($sawPulse.Left + $sawPulse.Right) / 2
        $dcy = ($sawPulse.Top + $sawPulse.Bottom) / 2
        if ($sawBall) {
            $bcx = ($sawBall.Left + $sawBall.Right) / 2
            $bcy = ($sawBall.Top + $sawBall.Bottom) / 2
            Judge '圆心与球心对齐' ([Math]::Abs($dcx - $bcx) -le 2 -and [Math]::Abs($dcy - $bcy) -le 2) `
                "涟漪心($dcx,$dcy) 球心($bcx,$bcy) 球窗=[$($sawBall.Left),$($sawBall.Top)]-[$($sawBall.Right),$($sawBall.Bottom)]"
        }
        else { Judge '同一刻读得到球窗' $false '这一拍球窗没读到' }
        Judge '涟漪窗非触摸 (NOT_TOUCHABLE)' ($sawPulse.Flags -match 'NOT_TOUCHABLE') $sawPulse.Flags
    }
    else {
        Judge '涟漪窗在念的时候挂上来了' $false 'dumpsys 里找不到那块非触摸的 overlay 窗'
    }
    $sayAnswer = ($say | Wait-Job -Timeout 40 | Receive-Job) -join ' '
    Remove-Job $say -Force -ErrorAction SilentlyContinue
    Write-Output "   念那一条的回话: $sayAnswer"
    if ($sayAnswer -match '"spoken":\s*false') { Write-Output '   引擎没念出来 (上面那句 detail 说了为什么)' }

    Start-Sleep -Milliseconds 1200
    Judge '念完之后 pulse 落回空' ((StateField 'pulse') -eq '') ('pulse=' + (StateField 'pulse'))
    Judge '念完之后涟漪窗自己摘了' ($null -eq (PulseWindow)) '那一档过去就不该再留着那块窗'
}

# 2. 「正在听」: 点两下球把语音窗口打开那一档, 方向与说相反
Write-Output ''
Write-Output '-- 正在听: pulse=in (与说相反的那一套)'
# **语音窗口起不来的设备要如实说**: 唤醒词模型不在盘上时开麦那一下会被拒, `VoiceState.capturing`
# 永远不为真 —— 那不是"涟漪坏了", 所以这一条按"这台设备量不了"报, 不当成不过
$wake = Call 'wakeword' '{"op":"status"}'
$up = BallWindow
if ($wake -match 'model on disk: no') {
    Write-Output '   跳过: 这台设备的唤醒词模型还没下载, 语音窗口起不来 (那一条属 lw-mode-voice-check)'
}
elseif ($null -eq $up) { Write-Output '   球不在, 这一条量不了'; $failures += 1 }
else {
    $cx = [int](($up.Left + $up.Right) / 2)
    $cy = [int](($up.Top + $up.Bottom) / 2)
    # 第一下只召出, 第二下才开麦 (与手势那一套一致): 两下隔过防连击窗口
    Sh "input tap $cx $cy" | Out-Null
    Start-Sleep -Milliseconds 700
    Sh "input tap $cx $cy" | Out-Null
    $sawIn = $false
    $deadline = (Get-Date).AddSeconds(8)
    while ((Get-Date) -lt $deadline -and -not $sawIn) {
        if ((StateField 'pulse') -eq 'in') { $sawIn = $true }
        Start-Sleep -Milliseconds 250
    }
    Judge '听的时候 pulse 是 in' $sawIn ('pulse=' + (StateField 'pulse') + ' word=' + (StateField 'word'))
    if ($sawIn) {
        $pulseFrame = PulseWindow
        Judge '听的时候涟漪窗在' ($null -ne $pulseFrame) '非触摸那一块'
    }
    else {
        Write-Output '   (这台设备可能没开语音窗口: 唤醒词与麦克风授权都在它后面; 那一条属 lw-mode-voice-check)'
    }
}

Write-Output ''
if ($failures -eq 0) { Write-Output '几条判据全过' } else { Write-Output "$failures 条判据不过" }
exit $failures
