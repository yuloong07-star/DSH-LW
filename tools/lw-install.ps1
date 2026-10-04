# DSH-LW 装机: 装 APK, 顺手把能给的权限给掉
#
# 从真机上手工做这件事有两个坑, 这个脚本就是为了绕开它们:
#
#   1. **必须带 `-i`**: 不带 installer 身份的普通 `install -r` 会把 installerPackageName 打回
#      null, 而 Android 13 起 installerPackageName 为 null 的应用不许开无障碍 (中间隔着
#      ACCESS_RESTRICTED_SETTINGS 那道 app op)
#   2. **重装会把无障碍配置清掉**: 装完要重新把我们的组件写回 enabled_accessibility_services,
#      **而且要读出来改** (设备上还有别人的无障碍服务)。vivo 那台机器上还多一条: secure settings
#      的写入只在重装之后很短的一段时间里被接受, 所以"装"与"写回"要连着做, 中间不要插别的事
#
# 用法:
#   pwsh -File tools\lw-install.ps1                                  # 默认机型与 APK, 装完写回无障碍
#   pwsh -File tools\lw-install.ps1 -Serial emulator-5554            # 模拟器
#   pwsh -File tools\lw-install.ps1 -Perms                           # 顺带批量授权 (见下)
#   pwsh -File tools\lw-install.ps1 -SkipInstall                     # 只重跑权限与无障碍那几步
#
# 权限那一步给的是两类东西, 都只能从 adb 给:
#   - 运行时权限 (`pm grant`): 相机 / 定位 / 媒体读取 / 蓝牙 / 麦克风
#   - 特殊访问 (`appops set`): 修改系统设置 / 使用情况访问 / 悬浮窗 / 忽略电池优化
# **失败不报错**: 有的 ROM 不认某条 op, 有的权限在这一版里本来就没声明, 所以逐条打印结果
param(
    [string]$Serial = '10CEB40568000ZB',
    [string]$Apk = "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk",
    [string]$Package = 'io.github.miuzarte.littlewhale',
    [switch]$Perms,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$adb = 'D:\apk\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

# 参数名不能叫 $Args: 那是 PowerShell 的自动变量, 拿它当参数名会让调用在展开时打转
function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CommandArgs)
    $output = & $adb -s $Serial @CommandArgs 2>&1
    return ($output | Out-String).Trim()
}

# 设备必须在, 而且必须是 device 状态 (unauthorized 会在这里就停下, 而不是等到 install 才失败)
$state = Adb get-state
if ($state -ne 'device') { throw "device $Serial is $state, not device" }
Write-Host "device $Serial is up" -ForegroundColor Green

# 装 : 带 installer 身份, 允许测试包, 覆盖安装
$component = "$Package/$Package.channel.LwAccessibility"
$wrote = $false
if (-not $SkipInstall) {
    $full = (Resolve-Path $Apk).Path
    Write-Host "installing $full" -ForegroundColor Cyan
    # 这一步不走 Adb 那个包装: adb 自己的旗标 (`-i` / `-r` / `-t`) 会被 PowerShell 当成参数名去解析
    # (`-i` 撞上 -InformationAction), 所以直接调可执行文件, 让后面的词原样过去
    $installed = (& $adb -s $Serial install -i com.android.packageinstaller -r -t $full 2>&1 | Out-String).Trim()
    Write-Host $installed
    # 判据用失败标记, 不判"有没有 Success 这个词": adb 成功时打的是两行 (`Performing Streamed
    # Install` 加 `Success`), 拼成一句话之后那个匹配并不总是成立
    if ($installed -match 'Failure|error|Exception') { throw "install failed: $installed" }

    # 装完立刻把无障碍写回: 有些 ROM 上那个窗口很短, 所以这一步紧挨着装, 中间不插别的事
    $listing = Adb shell "settings get secure enabled_accessibility_services"
    $before = if ($listing -eq 'null' -or $listing -eq '') { '' } else { $listing }
    if ($before -split ':' | Where-Object { $_ -eq $component }) {
        Write-Host "accessibility is already listed, leaving it alone" -ForegroundColor Yellow
        $wrote = $true
    } else {
        $next = if ($before -eq '') { $component } else { "$before`:$component" }
        Adb shell "settings put secure accessibility_enabled 1" | Out-Null
        Adb shell "settings put secure enabled_accessibility_services $next" | Out-Null
        # 读回来对照, 不看退出码: 这台设备上退出码 0 而值不变是常态
        $after = Adb shell "settings get secure enabled_accessibility_services"
        if ($after -split ':' | Where-Object { $_ -eq $component }) {
            Write-Host "accessibility written back: $after" -ForegroundColor Green
            $wrote = $true
        } else {
            Write-Host "the device did not keep the accessibility write" -ForegroundColor Red
            Write-Host "  it reads back as: $after" -ForegroundColor DarkGray
        }
    }
}

# 验收 : 两条判据, 缺一条就说清缺哪一条
#
#   1. **写入通路通不通** —— 往一个自己的探针 key 写一次再读回。有些 ROM 上 `settings put` 对任何 key
#      都返回 0 而值不变, 所以"退出码"永远不能当判据; 探针是唯一可信的回答, 而且它同时解释了上面
#      那一步为什么失败
#   2. **系统有没有真的绑上** —— 设置里写着不等于服务活着。`dumpsys activity services` 里那条
#      ServiceRecord 才是证据; 它的输出格式各 ROM 略有差别, 所以找不到时只标"未确认"而不判失败
#
# 位置说明: 放在 `-Perms` **之后**, 因为 pm grant 与 appops 各要几次 adb 往返, 而走这些往返的时候
# 正是重装打开的那段写入窗口 —— 权限是随时都能给的, 窗口不是
Write-Host ""
Write-Host "checking" -ForegroundColor Cyan

$probeKey = 'lw_write_probe'
Adb shell "settings put secure $probeKey lw-probe" | Out-Null
$probe = Adb shell "settings get secure $probeKey"
Adb shell "settings delete secure $probeKey" | Out-Null
$writesAccepted = ($probe -eq 'lw-probe')
if ($writesAccepted) {
    Write-Host "  writes accepted: yes" -ForegroundColor Green
} else {
    Write-Host "  writes accepted: no (the probe reads back as '$probe')" -ForegroundColor Red
}

$listed = Adb shell "settings get secure enabled_accessibility_services"
$isListed = ($listed -split ':' | Where-Object { $_ -eq $component }).Count -gt 0
Write-Host "  component listed: $(if ($isListed) { 'yes' } else { 'no' })" -ForegroundColor $(if ($isListed) { 'Green' } else { 'Red' })

$services = Adb shell "dumpsys activity services $Package"
$bound = $services -match 'LwAccessibility'
if ($bound) {
    Write-Host "  service bound: yes (there is an LwAccessibility record)" -ForegroundColor Green
} else {
    Write-Host "  service bound: not confirmed (no LwAccessibility record in dumpsys activity services)" -ForegroundColor Yellow
}

# 什么时候算过: 组件在列表里、而且系统真的绑着。写入通路不通时这两条都不可能成立, 所以要分开说
$ok = $isListed -and $bound
Write-Host ""
if ($ok) {
    Write-Host "acceptance: the service is listed and bound" -ForegroundColor Green
    Write-Host "  重启手机之后再确认一次: adb -s $Serial shell dumpsys activity services $Package"
} else {
    Write-Host "acceptance: not done yet" -ForegroundColor Red
    if (-not $writesAccepted) {
        Write-Host "  这台设备现在不接受写入 (探针没留住), 也就是重装打开的那段窗口已经关了。" -ForegroundColor Red
        Write-Host "  再跑一次脚本 (不要带 -SkipInstall):" -ForegroundColor Red
        Write-Host "    pwsh -File $PSCommandPath -Serial $Serial"
    } elseif (-not $isListed) {
        Write-Host "  写入过了但组件不在列表里: 系统或别的应用把它摘了。看一眼那道 op:" -ForegroundColor Red
        Write-Host "    adb -s $Serial shell appops get $Package ACCESS_RESTRICTED_SETTINGS"
    } else {
        Write-Host "  组件在列表里但系统没绑上: 用应用内那个开关 (它会先摘掉、等一下、再放回," -ForegroundColor Red
        Write-Host "  那一下是让系统重新评估), 或者重启手机。" -ForegroundColor Red
    }
}

# 权限 : 运行时权限用 pm grant, 特殊访问用 appops
if ($Perms) {
    $runtime = @(
        'android.permission.CAMERA',
        'android.permission.RECORD_AUDIO',
        'android.permission.ACCESS_FINE_LOCATION',
        'android.permission.ACCESS_COARSE_LOCATION',
        'android.permission.READ_MEDIA_IMAGES',
        'android.permission.READ_MEDIA_VIDEO',
        'android.permission.READ_MEDIA_AUDIO',
        'android.permission.BLUETOOTH_CONNECT',
        'android.permission.BLUETOOTH_SCAN',
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.ACTIVITY_RECOGNITION',
        'android.permission.BODY_SENSORS'
    )
    foreach ($permission in $runtime) {
        $answer = Adb shell "pm grant $Package $permission"
        $short = $permission -replace '^android\.permission\.', ''
        if ($answer -eq '') {
            Write-Host "  granted $short" -ForegroundColor Green
        } else {
            Write-Host "  $short : $answer" -ForegroundColor DarkGray
        }
    }

    # 特殊访问那几条走 appops: 应用侧查的是同一个 op, 所以这里设了它就显示"已允许"
    $ops = @(
        'WRITE_SETTINGS',
        'GET_USAGE_STATS',
        'SYSTEM_ALERT_WINDOW',
        'REQUEST_INSTALL_PACKAGES'
    )
    foreach ($op in $ops) {
        Adb shell "appops set $Package $op allow" | Out-Null
        $kept = Adb shell "appops get $Package $op"
        Write-Host "  $op -> $kept"
    }
    # 忽略电池优化: host 要一直活着, 不然系统会在后台把它冻住
    Adb shell "dumpsys deviceidle whitelist +$Package" | Out-Null
    Write-Host "  battery optimisation: whitelisted"
}

Write-Host ""
Write-Host "剩下这些只能人去系统页里点 (脚本给不了):" -ForegroundColor Cyan
Write-Host "  - 通知使用权 / 录屏 (以后要用到时再说)"
Write-Host "  - 桌面图标与通知栏图标现在是 DSH 自己的那只鲸鱼 (见 tools/make-icons.py)"

# 退出码按验收那两条给: 这样 CI 或者一条命令链能看到成没成, 而不是只靠读最后一屏文字
if ($ok) { exit 0 } else { exit 1 }
