# 锁屏那一条在设备上的实测 (批次 5, 2026-10-08)
#
# 量的是四组判据 (开发计划第 6 节批次 5 里那四组): ① 熄屏能不能点亮 (三条路各自成不成) ② 录一遍能不能
# 重放到桌面 ③ 故意失败一次会不会留痕与 +1 ④ 连着三次失败会不会自己关掉
#
# **第 2~4 组只对模拟器做**: 它们要造出真正的锁屏 (`locksettings set-pin`), 那是 root 才有的写法, 而且
# 会动这台设备主人的锁屏 —— 所以真机上只跑第 1 组, 而且会先把原来的值打出来
#
# 假手指走 `sendevent` (写内核那个输入节点): 它与真手指同一条路, 而**应用自己那套注入** (`input tap`
# / `input swipe`) **不会出现在这个节点上** —— 第 2 组里那一条控制判据量的就是这个 (脚本里量了两次:
# 一次假手指, 一次注入)。模拟器的控制台 (`adb emu event send`) 在 Android 16 这版镜像上写不到任何节点,
# 2026-10-08 实测过: 发出去 0 帧, 所以这里不用它
#
# 与 `lw-fake-touch.sh` 的分工: 那个是 1.0.3 那只"按下去 / 抬起来"的手 (给触摸刹车用的, 只有 down /
# move / up), 而这里要的是一条**走完的笔画** —— 而且模拟器的 virtio 节点还要求 `ABS_MT_TOUCH_MAJOR`
# 与 `ABS_MT_PRESSURE` (少这两条它不算一根手指), 所以这一份自己写那一串。节点用 `-TouchNode` 指
# (真机上那个是 `lw_probe` 报出来的, 与模拟器不是同一个)
#
# 用法: pwsh -File tools/lw-lock-check.ps1 -Serial emulator-5554             # 只跑第 1 组 (只读)
#       pwsh -File tools/lw-lock-check.ps1 -Serial emulator-5554 -FakeWalk   # 四组全跑
#       pwsh -File tools/lw-lock-check.ps1 -Serial emulator-5554 -FakeWalk -Pin 4321
#
# 前提: 应用在跑, host 在跑 (通道口与令牌从它的环境里读), 模拟器上 adb 是 root
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$FakeWalk,
    [string]$Pin = '1234',
    [string]$TouchNode = '/dev/input/event2',
    [switch]$KeepLock
)

$ErrorActionPreference = 'Stop'
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$adb = if ($env:LW_ADB) { $env:LW_ADB } else { 'D:\apk\Sdk\platform-tools\adb.exe' }
$package = 'io.github.yuloong07star.luwi'
$forward = if ($Serial -eq 'emulator-5554') { 29994 } else { 29993 }
$touchNode = $TouchNode

function Sh($command) { & $adb -s $Serial shell $command }
function Wait-For($seconds = 2) { Start-Sleep -Seconds $seconds }

$passed = 0
$failed = 0
function Judge($name, $ok, $detail) {
    if ($ok) { $script:passed++ } else { $script:failed++ }
    Write-Output "$(if ($ok) { 'ok  ' } else { 'FAIL' }) $name"
    if ($detail) { Write-Output "     $detail" }
}

# ── 通道口与令牌 (与 lw-ball-check.ps1 / lw-rotate-check.ps1 同一套) ────────

& $adb -s $Serial push tools\lw-device\host-env.sh /data/local/tmp/lw-host-env.sh | Out-Null
$raw = Sh "run-as $package sh /data/local/tmp/lw-host-env.sh"
$endpoint = ($raw | Select-String 'LW_CHANNEL_ENDPOINT=(.+)').Matches.Groups[1].Value
$token = ($raw | Select-String 'LW_CHANNEL_TOKEN=(.+)').Matches.Groups[1].Value
if (-not $endpoint -or -not $token) { throw "no channel on $Serial : $raw" }
& $adb -s $Serial forward "tcp:$forward" "tcp:$(($endpoint -split ':')[-1])" | Out-Null
$env:LW_CHANNEL_ENDPOINT = "127.0.0.1:$forward"
$env:LW_CHANNEL_TOKEN = $token

function Call($method, $params = '{}') { ($(node tools\lw-channel-call.mjs $method $params) | Out-String) }
function Result($method, $params = '{}') { ($(Call $method $params) | ConvertFrom-Json).result }
function LockStatus { Result 'lock' '{"op":"status"}' }

# ── 真手指那个假动作, 与"注入不算手指"那条控制判据 ─────────────────────────

function Send-Finger {
    param([int]$FromY, [int]$ToY, [int]$Steps = 10)
    # 一帧一帧写内核: 这些数就是那个节点的量程 (0~32767), 与真手指写进去的是一模一样的字节
    $lines = @(
        "sendevent $touchNode 3 47 0",           # ABS_MT_SLOT
        "sendevent $touchNode 3 57 7",           # ABS_MT_TRACKING_ID (>= 0 = 一根手指来了)
        "sendevent $touchNode 3 53 16384",       # ABS_MT_POSITION_X (屏中间)
        "sendevent $touchNode 3 54 $FromY",      # ABS_MT_POSITION_Y
        "sendevent $touchNode 3 48 600",         # ABS_MT_TOUCH_MAJOR
        "sendevent $touchNode 3 58 500",         # ABS_MT_PRESSURE
        "sendevent $touchNode 0 0 0"
    )
    for ($i = 1; $i -le $Steps; $i++) {
        $y = [int]($FromY + ($ToY - $FromY) * $i / $Steps)
        $lines += "sendevent $touchNode 3 54 $y"
        $lines += "sendevent $touchNode 0 0 0"
    }
    $lines += @(
        "sendevent $touchNode 3 48 0",
        "sendevent $touchNode 3 58 0",
        "sendevent $touchNode 3 57 -1",          # -1 = 这根手指走了
        "sendevent $touchNode 0 0 0"
    )
    $script = New-Item -Force -Path "$env:TEMP\lw-finger-$PID.sh" -ItemType File
    Set-Content -Path $script -Value $lines -Encoding ASCII
    & $adb -s $Serial push $script.FullName /data/local/tmp/lw-finger.sh | Out-Null
    Remove-Item -Force $script
    Sh 'sh /data/local/tmp/lw-finger.sh'
}

function Watch-Node($seconds, $script) {
    Sh "rm -f /data/local/tmp/lw-ge.txt; nohup timeout $seconds getevent -lt $touchNode > /data/local/tmp/lw-ge.txt 2>&1 &" | Out-Null
    Start-Sleep -Seconds 1
    & $script
    Start-Sleep -Seconds ($seconds - 1)
    $text = Sh 'grep -c EV_ABS /data/local/tmp/lw-ge.txt'
    [int](("$text" | Out-String).Trim())
}

# ── 0. 现在的锁屏设置 (原样打出来, 收尾要还原) ─────────────────────────────

$wasDisabled = (Sh 'locksettings get-disabled' | Out-String).Trim()
$wasType = (Sh 'settings get secure lockscreen.password_type' | Out-String).Trim()
$root = (Sh 'id' | Out-String).Trim()
Write-Output "这台设备的锁屏: get-disabled=$wasDisabled password_type=$wasType"
Write-Output "adb 身份: $root"

$before = LockStatus
Write-Output "现在的状态: $($before.text)"

# ── 1. 熄屏 → 点亮 (哪台机器都能跑的那一组) ────────────────────────────────

Result 'power' '{"op":"off"}' | Out-Null
Wait-For 2
$off = Result 'power' '{"op":"state"}'
Judge '熄屏那一步真的灭了' ($off.text -match '^off') "读到的: $($off.text)"

$lit = Result 'lock' '{"op":"unlock"}'
Judge '点亮那一句说出了走的是哪一条路' (
    $lit.woke -match 'privileged channel|wake lock|already on'
) "woke: $($lit.woke)"

$on = Result 'power' '{"op":"state"}'
Judge '屏幕真的亮了' ($on.text -match '^on') "读到的: $($on.text)"

if (-not $FakeWalk) {
    Write-Output ''
    Write-Output "只跑了第 1 组 (第 2~4 组加 -FakeWalk, 只对模拟器): $passed 过 / $failed 未过"
    if ($failed -gt 0) { exit 1 }
    exit 0
}

if ($Serial -notlike 'emulator-*') {
    throw '-FakeWalk 会改这台设备的锁屏 (set-pin), 只在模拟器上做'
}

function Set-Pin($value) {
    Sh "locksettings set-pin $value" | Out-Null
    Sh 'locksettings set-disabled false' | Out-Null
    Wait-For 1
}

function Clear-Pin($value) {
    if ($value) { Sh "locksettings clear --old $value" | Out-Null } else { Sh 'locksettings clear' | Out-Null }
    Wait-For 1
}

try {
    # ── 2. 控制判据: 注入的点击不算手指 (录制能分清真假手指的那一条) ─────────

    Set-Pin $Pin
    # 先把应用从最前面收掉: 它开着 `setShowWhenLocked`, 压着锁屏时锁屏那几屏根本不出来 (PIN 键盘也
    # 就不出来), 于是一条本该"划开键盘"的手指会落在应用自己身上
    Sh 'input keyevent 3' | Out-Null
    # 再熄一次屏: 刚设完 PIN 时设备还是"开着且没锁"那一档 (锁屏要等下一次熄屏才真的出现)
    Sh 'input keyevent 26' | Out-Null
    Wait-For 2
    $locked = LockStatus
    Judge '锁屏真的上了 (读数是 locked)' ($locked.locked -eq $true) "读到的: $($locked.text)"

    $injected = Watch-Node 5 {
        Sh 'input tap 540 1200; input swipe 540 1800 540 600 300' | Out-Null
    }
    Judge '应用自己那套注入在触屏节点上 0 帧 (所以它录不进主人那一段)' ($injected -eq 0) "读到 $injected 帧"

    $finger = Watch-Node 6 { Send-Finger -FromY 28000 -ToY 6000 }
    Judge '假手指在触屏节点上真的留下了一串坐标' ($finger -ge 8) "读到 $finger 帧"

    # ── 2b. 录一遍: 起录 → 走一遍 → 自己收工 ────────────────────────────────

    $started = Result 'lock' ('{"op":"record","action":"start","password":"' + $Pin + '"}')
    Judge '起录那一步没报错' (
        -not ($started.text -match 'not available|already running|could not')
    ) $started.text
    Wait-For 2
    Sh 'input keyevent 224' | Out-Null          # 主人走到锁屏跟前那一下 (KEYCODE_WAKEUP)
    Wait-For 1
    Send-Finger -FromY 28000 -ToY 6000
    Wait-For 1
    # 这两条是**注入**: 它们只是替主人把锁屏解开, 于是录制自己收工 (它们不进 evdev)
    Sh "input text $Pin" | Out-Null
    Wait-For 1
    Sh 'input keyevent 66' | Out-Null
    Wait-For 3

    $after = LockStatus
    Judge '录到了一条序列' ($after.steps -ge 1) "steps=$($after.steps) stepList=$($after.stepList)"
    Judge '密码进了加密那一格' ($after.secret -match 'password') "secret=$($after.secret)"

    $plain = Sh "run-as $package cat files/dsh-home/lock/unlock.json" | Out-String
    Judge '明文那一份里只有 secret 这个记号, 没有密码那几个字' (
        ($plain -match 'secret') -and (-not ($plain -match [regex]::Escape($Pin)))
    ) ($plain.Trim() -replace "`r?`n", ' ')

    # ── 3. 重放一遍: 到不到桌面 ─────────────────────────────────────────────

    Result 'power' '{"op":"off"}' | Out-Null
    Wait-For 2
    $replay = Result 'lock' '{"op":"unlock"}'
    Judge '重放走到了桌面' ($replay.unlocked -eq $true) $replay.detail
    Judge '那一次重放留下了它的读数' ($replay.detail -match 'home screen|lock screen') $replay.detail

    # ── 4. 故意失败三次: 计数 +1, 然后自己关掉 ──────────────────────────────

    Clear-Pin $Pin
    Set-Pin '9999'                              # 设备密码换了, 录的那一份于是解不开
    Result 'power' '{"op":"off"}' | Out-Null
    Wait-For 2
    $one = Result 'lock' '{"op":"unlock"}'
    Judge '第一次失败如实说了停在哪儿' (-not $one.unlocked -and $one.detail -match 'attempt 1') $one.detail
    $two = Result 'lock' '{"op":"unlock"}'
    $three = Result 'lock' '{"op":"unlock"}'
    Write-Output "     第二次: $($two.detail)"
    Write-Output "     第三次: $($three.detail)"
    $tries = LockStatus
    Judge '连着三次失败计到了三次' ($tries.tries -eq 3) "tries=$($tries.tries)"
    Judge '三次之后自动解锁是关着的' ($tries.autoUnlock -eq $false) "autoUnlock=$($tries.autoUnlock)"
    Judge '留痕里写着它自己关掉了' ($tries.lastReplay -match 'now off') "$($tries.lastReplay)"
} finally {
    if (-not $KeepLock) {
        Clear-Pin '9999'
        Sh "locksettings set-disabled $wasDisabled" | Out-Null
        Write-Output "锁屏设置已经还原: get-disabled=$wasDisabled password_type=$wasType"
    } else {
        Write-Output '-KeepLock: 锁屏设置没有还原'
    }
}

Write-Output ''
Write-Output "四组跑完: $passed 过 / $failed 未过"
if ($failed -gt 0) { exit 1 }
