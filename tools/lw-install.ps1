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
    # **先 push 再本地装**: 351 MB 的流式安装把重装打开的那段写入窗口整个吃掉了 (实测: 装完探针就报
    # "不接受写入"), 而 `pm install` 在设备上就地装只要几秒。所以 APK 先送到 /data/local/tmp, 之后
    # "装 + 写回"在同一次 adb shell 里连着一口气做完, 中间不插任何往返
    $remote = '/data/local/tmp/lw-install.apk'
    # 路径要先解析出来再 push: 上一轮改这段时把这一句弄丢了, 于是 push 收到空路径、装了个寂寞
    $full = (Resolve-Path $Apk).Path
    Write-Host "pushing $full" -ForegroundColor Cyan
    $pushed = (& $adb -s $Serial push $full $remote 2>&1 | Out-String).Trim()
    if ($pushed -notmatch '1 file pushed') { throw "push failed: $pushed" }

    $component = "$Package/$Package.channel.LwAccessibility"
    $pkg = $Package
    # 判"到底装上了没有"的基准: 设备现在记的 lastUpdateTime。装完它必须变新, 否则就是没装成功 ——
    # `pm install` 失败时会把异常栈打到 stderr, 只匹配 "Failure" 会漏掉 (实测真机上一次就是漏了,
    # 脚本还报了成功)
    $before = (& $adb -s $Serial shell "dumpsys package $pkg | grep lastUpdateTime" 2>&1 | Out-String).Trim()
    $stamp = [int][double]::Parse((Get-Date -UFormat %s))

    $script = @"
pm install -r -i com.android.packageinstaller -t $remote 2>&1 | tail -3
cur=`$(settings get secure enabled_accessibility_services)
case "`$cur" in
  *LwAccessibility*) echo "ACCESSIBILITY_ALREADY_LISTED" ;;
  *) if [ -z "`$cur" ] || [ "`$cur" = "null" ]; then next="$component"; else next="`$cur:$component"; fi
     settings put secure accessibility_enabled 1
     settings put secure enabled_accessibility_services "`$next"
     echo "ACCESSIBILITY_WRITTEN" ;;
esac
settings put secure lw_write_probe install-$stamp
echo "PROBE=`$(settings get secure lw_write_probe)"
settings delete secure lw_write_probe >/dev/null 2>&1
settings get secure enabled_accessibility_services
rm -f $remote
"@
    Write-Host "installing on the device, then writing the entry back in the same breath" -ForegroundColor Cyan
    $installed = ($script | & $adb -s $Serial shell "sh -s" 2>&1 | Out-String).Trim()
    Write-Host $installed

    # 判据一: 设备的 lastUpdateTime 变了
    $after = (& $adb -s $Serial shell "dumpsys package $pkg | grep lastUpdateTime" 2>&1 | Out-String).Trim()
    if ($after -eq $before -or $after -eq '') {
        throw "the app was not installed: lastUpdateTime is still '$after' (the install output above says why)"
    }
    Write-Host "installed: $after" -ForegroundColor Green

    # 判据二: 刚才写的那一条有没有被留下 (探针与无障碍写在同一次 shell 里, 所以窗口还是同一个)
    $writesAccepted = $installed -match "PROBE=install-$stamp"
    if ($installed -match 'ACCESSIBILITY_WRITTEN|ACCESSIBILITY_ALREADY_LISTED') {
        $listed = $installed -match 'LwAccessibility'
        if ($listed) {
            Write-Host "accessibility written back" -ForegroundColor Green
            $wrote = $true
        }
    }
    if (-not $wrote -and -not $writesAccepted) {
        Write-Host "the device did not keep the accessibility write" -ForegroundColor Red
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

# 退出码: 只看**这一次要它做的事**成没成, 免得 `-Perms` 因为无障碍那一条而报失败
#
#   - 带了 `-Perms`: 权限那一段每一批都发过一遍就算成功 (它们是逐个打印的, 失败自己会说话)
#   - 没带 `-Perms` 且做了安装: 按无障碍那两条判据判
#   - `-SkipInstall` 又没带 `-Perms`: 按无障碍判据判
if ($Perms) { exit 0 }
if ($ok) { exit 0 } else { exit 1 }
