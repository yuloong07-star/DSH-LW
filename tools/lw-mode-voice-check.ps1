# 切模式那两半 (相机 + 常驻语音) 在设备上的实测脚本 (2026-10-06)
#
# 量的东西: **进视频模式之后常驻语音真的在跑, 切走之后真的收回去** —— 不是"回执说它开了":
#   1. `modes/voice-resident.on` 那个记号在不在 (它是服务认的唯一事实, 见 LwWakeWord.residentWanted)
#   2. `lw_wakeword op=status` 的 allowVoice(记号) / voiceActive(链留没留着) 两个字段
#   3. 唤醒词服务在不在 (常驻那条链长在它的采集中间, 服务不在就起不来 —— 那种半开状态要报出来)
#   4. 设备端那三个脚本 `phone.sh / video.sh / screen.sh` 各是**一个模式的整个切换** (它们与模型调
#      `lw_mode` 走同一份实现): 跑一个脚本, 模式、记号、链条三件事要一起跟着变
#
# 用法: pwsh -File tools/lw-mode-voice-check.ps1 -Serial 10CEB40568000ZB
#       pwsh -File tools/lw-mode-voice-check.ps1 -Serial 10CEB40568000ZB -ScriptOnly   # 只验脚本那条路
#
# 前提: host 在跑 (通道口与令牌从它的环境里读), 唤醒词模型已下载 (常驻链要有 KWS 那一路)
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$ScriptOnly
)

$ErrorActionPreference = 'Stop'
$adb = if ($env:LW_ADB) { $env:LW_ADB } else { 'D:\apk\Sdk\platform-tools\adb.exe' }
$package = 'io.github.miuzarte.littlewhale'
$forward = if ($Serial -eq 'emulator-5554') { 29993 } else { 29992 }

function Sh($command) { & $adb -s $Serial shell $command }

& $adb -s $Serial push tools\lw-device\host-env.sh /data/local/tmp/lw-host-env.sh | Out-Null
$raw = Sh "run-as $package sh /data/local/tmp/lw-host-env.sh"
$endpoint = ($raw | Select-String 'LW_CHANNEL_ENDPOINT=(.+)').Matches.Groups[1].Value
$token = ($raw | Select-String 'LW_CHANNEL_TOKEN=(.+)').Matches.Groups[1].Value
if (-not $endpoint -or -not $token) { throw "no channel on $Serial : $raw" }
& $adb -s $Serial forward "tcp:$forward" "tcp:$(($endpoint -split ':')[-1])" | Out-Null
$env:LW_CHANNEL_ENDPOINT = "127.0.0.1:$forward"
$env:LW_CHANNEL_TOKEN = $token

function Call($method, $json) { (node tools\lw-channel-call.mjs $method $json | Out-String).Trim() }
function Status { Call wakeword '{"op":"status"}' }

# 现在是哪一个模式 (应用读 `modes/.active` 那份记号, 状态里第一个字段就是它)
function ActiveMode {
    $state = Call mode '{"mode":"status"}'
    return [regex]::Match($state, '"mode":"([^"]*)"').Groups[1].Value
}

# 记号那个文件: 它是"这一路要留着"的唯一事实 (服务起来时照它恢复)
function Marker {
    $out = Sh "run-as $package sh -c 'ls files/dsh-home/modes/voice-resident.on 2>/dev/null'"
    return [bool]($out | Select-String 'voice-resident\.on')
}

function Listening {
    $out = Sh "dumpsys activity services $package"
    return ($out | Select-String '\.wake\.WakeWordService').Count -gt 0
}

function Field($state, $name) {
    $m = [regex]::Match($state, '"' + $name + '":\s*(true|false)')
    if ($m.Success) { return $m.Groups[1].Value }
    return '?'
}

$failures = 0
function Judge($name, $ok, $detail) {
    if ($ok) { Write-Output "ok   $name  ($detail)" } else { Write-Output "FAIL $name  ($detail)"; $script:failures += 1 }
}

Write-Output "== $Serial  ($package)"
Write-Output ''

# 0. 先决条件: 唤醒词得在听 —— 常驻那条链是它的第二个消费者, 服务不在就"记号设了而链起不来"
$listening = Listening
Write-Output "唤醒词服务: $(if ($listening) { '在跑' } else { '没在跑' })"
if (-not $listening) {
    Write-Output '提示: 先让它听 (设置页那个「允许唤醒」, 或跑 lw_wakeword op=start), 否则常驻链起不来'
}

# 1. 切进视频模式 (模型调 lw_mode 走的就是这一条)
if (-not $ScriptOnly) {
    Write-Output ''
    Write-Output '-- 进视频模式 (桥: mode op=video)'
    $answer = Call mode '{"mode":"video"}'
    Write-Output "   answer: $answer"
    Start-Sleep -Milliseconds 1500
    $state = Status
    Write-Output "   status: $state"
    Judge '进视频模式之后记号在' (Marker) 'files/dsh-home/modes/voice-resident.on'
    Judge 'status 的 allowVoice 是 true' ((Field $state 'allowVoice') -eq 'true') "allowVoice=$(Field $state 'allowVoice')"
    if (-not $listening) {
        Write-Output '   跳过 voiceActive 那一条: 唤醒词没在跑, 记号设了也起不来 (这正是回执该说的那种半开状态)'
    } elseif ((Field $state 'voiceActive') -eq 'true') {
        Judge '常驻链真的在跑 (voiceActive)' $true "voiceActive=true"
    } else {
        # 记号设了、服务也在跑, 而链没起来 —— 那是缺件, 而缺哪一件只有状态里说得清
        $vad = [regex]::Match($state, '"vadDetail":\s*"([^"]*)"').Groups[1].Value
        $asr = [regex]::Match($state, '"asrDetail":\s*"([^"]*)"').Groups[1].Value
        Judge '常驻链真的在跑 (voiceActive)' $false "voiceActive=false; VAD=$vad; ASR=$asr"
    }

    # 2. 退回手机模式: 记号要没, 链要收
    Write-Output ''
    Write-Output '-- 退回手机模式 (桥: mode op=phone)'
    $answer = Call mode '{"mode":"phone"}'
    Write-Output "   answer: $answer"
    Start-Sleep -Milliseconds 1500
    $state = Status
    Judge '退回手机模式之后记号没了' (-not (Marker)) 'voice-resident.on 已删除'
    Judge 'status 的 allowVoice 是 false' ((Field $state 'allowVoice') -eq 'false') "allowVoice=$(Field $state 'allowVoice')"
    Judge '常驻链收回来了 (voiceActive false)' ((Field $state 'voiceActive') -eq 'false') "voiceActive=$(Field $state 'voiceActive')"
}

# 3. 设备端那三个切换脚本: **一个脚本 = 一个模式的整个切换** (提示词 + 相机 + 常驻语音一次做完)
Write-Output ''
Write-Output '-- 设备端脚本 video.sh (切进视频模式)'
$on = Sh "run-as $package sh -c 'sh files/dsh-home/modes/video.sh'" 2>&1
Write-Output "   -> $on"
Start-Sleep -Milliseconds 1800
$state = Status
Judge 'video.sh 之后在视频模式' ((ActiveMode) -eq 'video') "active=$(ActiveMode)"
Judge 'video.sh 之后记号在' (Marker) 'voice-resident.on'
if ($listening) {
    Judge 'video.sh 之后链在跑' ((Field $state 'voiceActive') -eq 'true') "voiceActive=$(Field $state 'voiceActive')"
} elseif ($Marker) {
    Write-Output '   记号写下了而服务没在跑 —— 回执那句 "the marker is set, but the wake word service is not listening" 就是这一档'
}

Write-Output ''
Write-Output '-- 设备端脚本 screen.sh (切进识屏模式: 那两半都收回去, 而且不碰相机)'
$screen = Sh "run-as $package sh -c 'sh files/dsh-home/modes/screen.sh'" 2>&1
Write-Output "   -> $screen"
Start-Sleep -Milliseconds 1800
$state = Status
Judge 'screen.sh 之后在识屏模式' ((ActiveMode) -eq 'screen') "active=$(ActiveMode)"
Judge 'screen.sh 之后记号没了' (-not (Marker)) 'voice-resident.on 已删除'
Judge 'screen.sh 之后链收了' ((Field $state 'voiceActive') -eq 'false') "voiceActive=$(Field $state 'voiceActive')"

Write-Output ''
Write-Output '-- 设备端脚本 phone.sh (退回手机模式)'
$off = Sh "run-as $package sh -c 'sh files/dsh-home/modes/phone.sh'" 2>&1
Write-Output "   -> $off"
Start-Sleep -Milliseconds 800
$state = Status
Judge 'phone.sh 之后回到手机模式' ((ActiveMode) -eq 'phone') "active=$(ActiveMode)"
Judge 'phone.sh 之后记号没了' (-not (Marker)) 'voice-resident.on 已删除'
Judge 'phone.sh 之后链收了' ((Field $state 'voiceActive') -eq 'false') "voiceActive=$(Field $state 'voiceActive')"

Write-Output ''
if ($failures -eq 0) { Write-Output '几条判据全过' } else { Write-Output "$failures 条判据不过" }
exit $failures
