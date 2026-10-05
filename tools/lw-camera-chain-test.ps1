# 临时 (不入库): 相机链路在设备上的实测 —— 新链路 (Camera2 直连) 与旧链路 (建虚拟屏 + 起相机应用 + 截屏) 打表
#
# 为什么要有它: 替换相机链路要回答的是"快了多少", 而两边都得在**同一台设备、同一时刻**量才算数。新链路
# 直接打通道方法 `camera`; 旧链路那条代码已经摘掉了, 所以按它当年的步骤手工复现 (create -> launch ->
# screenshot), 走的是同一座桥、同一批通道方法
#
# 用法: . D:\apk\env.ps1; .\tools\lw-camera-chain-test.ps1 [-Serial emulator-5554] [-Frames 4]
param(
    [string]$Serial = 'emulator-5554',
    [int]$Frames = 4
)

$ErrorActionPreference = 'Stop'
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$pkg = 'io.github.miuzarte.littlewhale'
$caller = Join-Path $PSScriptRoot 'lw-channel-call.mjs'

if (-not (Test-Path $adb)) { throw "adb not found at $adb (dot-source D:\apk\env.ps1 first)" }
if (-not (Test-Path $caller)) { throw "lw-channel-call.mjs not found at $caller" }

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$Raw)
    $output = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $Raw) { throw "adb $($Arguments -join ' ') failed: $output" }
    return ($output -join "`n")
}

# 桥上那一对地址与 token 在宿主进程的环境里; 宿主是我们自己的 uid, 所以 run-as 读得到
function Find-Channel {
    $probe = 'for d in /proc/[0-9]*; do if [ -r "$d/environ" ]; then e=$(tr "\0" "\n" < "$d/environ" 2>/dev/null | grep -m1 "^LW_CHANNEL_ENDPOINT="); if [ -n "$e" ]; then echo "${e#LW_CHANNEL_ENDPOINT=}"; tr "\0" "\n" < "$d/environ" | grep -m1 "^LW_CHANNEL_TOKEN="; break; fi; fi; done'
    $found = (Invoke-Adb @('shell', "run-as $pkg sh -c '$probe'")).Trim() -split "`n"
    if ($found.Count -lt 2) { throw "no channel: the host is not running (LW_CHANNEL_ENDPOINT is in no process)" }
    $endpoint = $found[0].Trim()
    $token = $found[1].Trim().Replace('LW_CHANNEL_TOKEN=', '')
    return @{ endpoint = $endpoint; token = $token }
}

# 一次桥调用: 回 { ok, ms, result } —— ms 是墙钟 (含 node 启动), result 是应用那一侧答的 JSON
function Invoke-Channel {
    param([string]$Method, [string]$Params = '{}')
    $env:LW_CHANNEL_ENDPOINT = $channel.endpoint
    $env:LW_CHANNEL_TOKEN = $channel.token
    $started = Get-Date
    $output = & node $caller $Method $Params 2>&1
    $ms = [int]((Get-Date) - $started).TotalMilliseconds
    $text = $output -join "`n"
    $parsed = $null
    try { $parsed = $text | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ method = $Method; params = $Params; ms = $ms; result = $parsed; raw = $text }
}

function Show {
    param($Step, [string]$What)
    $summary = if ($null -eq $Step.result) { $Step.raw } else { ($Step.result | ConvertTo-Json -Compress -Depth 4) }
    Write-Host ("{0,-9} {1,6}ms  {2}" -f $What, $Step.ms, $summary)
}

# 桥上那一层信封是 { ok, result }: 真正的内容在 result 里
function Payload {
    param($Step)
    if ($null -eq $Step.result) { return $null }
    if ($null -ne $Step.result.result) { return $Step.result.result }
    return $Step.result
}

$channel = Find-Channel
$port = [int]($channel.endpoint -split ':')[-1]
$null = Invoke-Adb @('forward', "tcp:$port", "tcp:$port") -Raw
Write-Host "channel: $($channel.endpoint) (forwarded); frames per look: $Frames`n"

Write-Host '--- 新链路: Camera2 直连 (open -> ImageReader -> session -> capture) ---'
$new = @{}
# 先收一次工, 让下面那次 open 是**冷的** (不然上一轮留下的相机会让 open 只剩几十毫秒, 那个数不可比)
$null = Invoke-Channel 'camera' '{"op":"close","clean":true}'
$new.status = Invoke-Channel 'camera' '{"op":"status"}'
Show $new.status 'status'
$new.open = Invoke-Channel 'camera' '{"op":"open"}'
Show $new.open 'open'
$new.first = Invoke-Channel 'camera' ("{`"op`":`"snapshot`",`"count`":$Frames}")
Show $new.first 'look#1'
$new.second = Invoke-Channel 'camera' ("{`"op`":`"snapshot`",`"count`":$Frames}")
Show $new.second 'look#2'
$new.close = Invoke-Channel 'camera' '{"op":"close","clean":true}'
Show $new.close 'close'
Write-Host ("frames: {0}" -f (Payload $new.first).paths)

Write-Host "`n--- 旧链路 (按当年步骤手工复现): create -> launch -> screenshot ---"
$old = @{}
$old.create = Invoke-Channel 'create' '{"name":"video","width":720,"height":1280,"dpi":320}'
Show $old.create 'create'
$displayId = (Payload $old.create).displayId
$old.launch = Invoke-Channel 'launch' ("{`"displayId`":$displayId,`"package`":`"com.android.camera2`"}")
Show $old.launch 'launch'
$old.first = Invoke-Channel 'screenshot' ("{`"displayId`":$displayId,`"count`":$Frames,`"quality`":`"medium`"}")
Show $old.first 'look#1'
$old.second = Invoke-Channel 'screenshot' ("{`"displayId`":$displayId,`"count`":$Frames,`"quality`":`"medium`"}")
Show $old.second 'look#2'
$old.release = Invoke-Channel 'release' ("{`"displayId`":$displayId}")
Show $old.release 'release'

$newElapsed = (Payload $new.first).elapsedMs
$oldElapsed = (Payload $old.first).elapsedMs
Write-Host "`n--- 对比 (应用量到的那一段: 从发起到帧落盘) ---"
Write-Host ("新链路 冷: open {0}ms + 一次取景 {1}ms = {2}ms" -f $new.open.ms, $newElapsed, ($new.open.ms + $newElapsed))
Write-Host ("旧链路 冷: create {0}ms + launch {1}ms + 一次取景 {2}ms = {3}ms" -f $old.create.ms, $old.launch.ms, $oldElapsed, ($old.create.ms + $old.launch.ms + $oldElapsed))
Write-Host ("取景 (热): 新 {0}ms / 旧 {1}ms" -f (Payload $new.second).elapsedMs, (Payload $old.second).elapsedMs)
