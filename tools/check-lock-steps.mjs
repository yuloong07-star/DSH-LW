/**
 * 锁屏那一条的判据表 (2.5.0 批次 5 的需求 2 与 7)
 *
 * 这一批是全版唯一一条"做错了会很糟"的功能, 而它要守的几件事**都能在源码上核**: 坐标是比例还是像素、
 * 密码会不会被写到别处去、失败三次停不停、亮屏那三条路在不在、插件与应用的名单一不一致。设备上才量
 * 得到的那两条 (注入能不能解开锁屏 / Keystore 能不能用) 在 `tools/lw-lock-check.ps1` 里
 *
 * 跑法: node tools/check-lock-steps.mjs
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')

const plugin = await read('host-plugin/index.mjs')
const manifest = await read('app/src/main/AndroidManifest.xml')
const steps = await read('app/src/main/java/io/github/yuloong07star/luwi/lock/LockSteps.kt')
const touch = await read('app/src/main/java/io/github/yuloong07star/luwi/lock/LockTouch.kt')
const secret = await read('app/src/main/java/io/github/yuloong07star/luwi/lock/LockSecret.kt')
const setting = await read('app/src/main/java/io/github/yuloong07star/luwi/lock/LockSetting.kt')
const replay = await read('app/src/main/java/io/github/yuloong07star/luwi/lock/LockReplay.kt')
const record = await read('app/src/main/java/io/github/yuloong07star/luwi/lock/LockRecord.kt')
const tool = await read('app/src/main/java/io/github/yuloong07star/luwi/lock/LwLock.kt')
const recorder = await read('app/src/main/java/io/github/yuloong07star/luwi/channel/LwTouchRecord.kt')
const bridge = await read('app/src/main/java/io/github/yuloong07star/luwi/channel/PrivilegedBridge.kt')
const service = await read('app/src/main/java/io/github/yuloong07star/luwi/wake/WakeWordService.kt')
const script = await read('app/src/main/java/io/github/yuloong07star/luwi/lock/LockScript.kt')
const screen = await read('app/src/main/java/io/github/yuloong07star/luwi/ui/SettingsScreen.kt')
const scriptDoc = await readFile(new URL('../docs/lock-script.md', root), 'utf8')

let failures = 0
let checks = 0
function check(name, got, want) {
  checks += 1
  const same = JSON.stringify(got) === JSON.stringify(want)
  if (!same) failures += 1
  console.log(
    `${same ? 'ok  ' : 'FAIL'} ${name}`
      + (same ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`),
  )
}

/** 从一份源码里切一段出来 */
function slice(source, from, to) {
  const start = source.indexOf(from)
  if (start < 0) throw new Error(`找不到 ${JSON.stringify(from)}`)
  const end = source.indexOf(to, start)
  if (end < 0) throw new Error(`找不到分界 ${JSON.stringify(to)}`)
  return source.slice(start, end)
}

/* ── 一、坐标一律按比例 ─────────────────────────────────────────────────── */

check(
  '比例在写进序列之前先夹到 0~1 (一份被写坏的文件送不到屏外)',
  [
    steps.includes('x = step.x.coerceIn(0f, 1f)'),
    steps.includes('fromX = step.fromX.coerceIn(0f, 1f)'),
    steps.includes('point.getOrElse(0) { 0f }.coerceIn(0f, 1f)'),
  ],
  [true, true, true],
)

check(
  '比例到像素只有 LockSteps.px 这一个出口',
  [
    replay.includes('LockSteps.px('),
    // 重放那一侧不许自己乘宽度: 一处漏了就有一条坐标会按像素存下来
    /\bwidth\s*\*|\*\s*width\b/.test(replay),
  ],
  [true, false],
)

check(
  '录制的坐标进来时是设备自己的数, 出去前先除以量程',
  [
    touch.includes('it.x.toFloat() / xMax'),
    touch.includes('it.y.toFloat() / yMax'),
    !touch.includes('32767f'),
  ],
  [true, true, true],
)

check('一条序列有步数上限', steps.includes('steps.take(MAX_STEPS)'), true)

/* ── 二、密码不落盘、不进日志 ────────────────────────────────────────────── */

check(
  '密码那一步在序列里只是一个没有字段的位置记号',
  [steps.includes('data object Secret : LockStep'), /\bSecret\(/.test(steps)],
  [true, false],
)

check(
  '序列里留下的折线也不会进明文那一份 (它进加密那一格)',
  [steps.includes('val replaced = bounded.map { if (it === stroke) LockStep.Secret else it }'),
    steps.includes('return replaced to stroke.points')],
  [true, true],
)

check(
  '秘密自己那句说明里只有长度 / 点数, 没有内容',
  [secret.includes('a password of ${text.length} characters'), secret.includes('${text}')],
  [true, false],
)

// 这一条是整批最要紧的一条: 日志里出现一次就等于那个密码进了 logcat
const logLines = [...secret.matchAll(/Log\.[a-z]+\([^)]*\)/g)].map((one) => one[0])
check(
  '加密那一份的日志里不出现明文 (只出现异常的类名)',
  [logLines.length > 0, logLines.every((line) => !/text|password|pending/i.test(line))],
  [true, true],
)

check(
  '工作区里那份明文只放步骤序列',
  setting.includes('file.writeText(text)') && setting.includes('val text = LockSteps.encode(bounded)'),
  true,
)

check(
  '填了密码时, 第一次点击起的东西整段丢掉',
  [record.includes('LockSteps.settleTaken(taken, hasPassword = password.isNotEmpty())'),
    steps.includes('val head = bounded.takeWhile { it !is LockStep.Tap && it !is LockStep.Secret }')],
  [true, true],
)

/* ── 三、行为: 三次停 / 每次留痕 / 两条开关的缺省 ───────────────────────── */

check('连着失败三次就停', steps.includes('const val LIMIT = 3'), true)
check(
  '到三次就把自动解锁关掉',
  [replay.includes('if (!result.ok && LockTries.exhausted(after))'),
    replay.includes('LockSetting.setAutoUnlock(app, false)')],
  [true, true],
)
check(
  '每次重放都在通知栏留一行',
  [replay.includes('lastReport = sentence'), replay.includes('post(app, "Unlocking by voice", sentence)')],
  [true, true],
)
check(
  '两条开关的缺省值: 点亮开, 自动解锁关',
  [
    setting.includes('getBoolean(WAKE_SCREEN, true)'),
    setting.includes('getBoolean(AUTO_UNLOCK, false)'),
  ],
  [true, true],
)
check(
  // 2026-10-09: 口径从"没录过就不许开"放宽成"没录过也能开, 只要注入解锁开着而密码格里有一段可注入的
  // 密码" —— 「注入密码」那一行让这条路不必先录手势 (判据在 LockSteps.injected 与 LockSetting.typable)
  '没录过也不许把自动解锁打开, 除非注入解锁开着而密码格里有可注入的密码',
  setting.includes('if (on && !recorded(context) && !(injectUnlock(context) && typable(context)))'),
  true,
)

/* ── 四、亮屏三条路与预算 ───────────────────────────────────────────────── */

check(
  '三条路都在: 特权通道 / 自己的电源锁 / 全屏通知的授权读数',
  [
    replay.includes('LwSystemCommand.run("wake")'),
    replay.includes('ACQUIRE_CAUSES_WAKEUP'),
    replay.includes('canUseFullScreenIntent'),
  ],
  [true, true, true],
)

check(
  '唤醒词那条路上点亮有预算 (它跑在采集线程上)',
  [
    service.includes('HIT_WAKE_BUDGET_MS = 600L'),
    service.includes('LockReplay.onWake(this, HIT_WAKE_BUDGET_MS)'),
  ],
  [true, true],
)

check(
  '第三条路要真的能亮: 提到前台那一页带 setTurnScreenOn',
  (await read('app/src/main/java/io/github/yuloong07star/luwi/MainActivity.kt')).includes(
    'setTurnScreenOn(true)',
  ),
  true,
)

/* ── 五、写在特权那一侧的那一半 ─────────────────────────────────────────── */

check(
  '读真手指只发生在特权那一侧',
  [
    recorder.includes('InputDevices.touchscreen(readInputDevices())'),
    // 应用那一侧一行都不许去开那个节点 (注释里提一句没关系, 开一次才是越界)
    /FileInputStream|File\("\s*\/dev/.test(record),
    /FileInputStream|File\("\s*\/dev/.test(replay),
  ],
  [true, false, false],
)

check(
  '一拍取一次时, 没抬起的那条笔画留在手里 (不切成两半)',
  recorder.includes('val cut = samples.indexOfLast { it.phase == LockTouch.UP }'),
  true,
)

/* ── 六、插件与应用两边对得上 ───────────────────────────────────────────── */

const lockTool = slice(plugin, "'lw_lock'", '\n  ),\n')
const pluginOps = [...lockTool.matchAll(/enum: \[([^\]]*)\]/g)]
  .map((one) => one[1].split(',').map((part) => part.trim().replace(/^'|'$/g, '')))
  .filter((list) => list.length > 0)
const declared = pluginOps[0] ?? []
const handled = [...tool.matchAll(/^\s{8}"(\w+)" ->/gm)].map((one) => one[1])

check(
  '插件里那六个 op 与应用那一侧处理的六个是同一批',
  [declared, handled.slice().sort()],
  [
    ['status', 'unlock', 'steps', 'clear', 'record', 'import'],
    ['clear', 'import', 'record', 'status', 'steps', 'unlock'],
  ],
)

check(
  '插件把这条工具接到通道的 lock 方法上',
  [lockTool.includes("'lock',"), bridge.includes('"lock" -> appContext')],
  [true, true],
)

check(
  '这一批没有偷偷加系统许可 (那条最容易想到的是 DISABLE_KEYGUARD)',
  [/android\.permission\.DISABLE_KEYGUARD/.test(manifest), /WRITE_SECURE_SETTINGS/.test(manifest)],
  [false, false],
)

check(
  '录制被真手指之外的注入影响不了: 注入不进 evdev 这条判据在文档里写清了',
  recorder.includes('注入的事件不会出现在这个节点上'),
  true,
)

/* ── 七、导入脚本那一条 (批次 5 追加) ────────────────────────────────────── */

// 动词表是"代码里认的"与"文档里写的"两份: 漂开了就会有人照文档写却被拒
const codeVerbs = [...script.matchAll(/^\s+val VERBS = listOf\(([^)]*)\)/gm)]
  .map((one) => [...one[1].matchAll(/"([a-z]+)"/g)].map((part) => part[1]))[0] ?? []
const docVerbs = [...scriptDoc.matchAll(/^\|\s*`([a-z]+)`/gm)].map((one) => one[1])
check(
  '脚本动词表: 代码认的那几个与文档写的那几个是同一批',
  [codeVerbs.slice().sort(), [...new Set(docVerbs)].slice().sort()],
  [['key', 'stroke', 'swipe', 'tap', 'text', 'wait'], ['key', 'stroke', 'swipe', 'tap', 'text', 'wait']],
)

check(
  '密码不许写在脚本里 (text 那一步带参数就拒)',
  [
    script.includes('"text" -> if (arguments.isEmpty())'),
    script.includes('the password itself is handed over separately'),
  ],
  [true, true],
)

check(
  '坐标是比例不是像素 (越界的那一句说的是"不是像素")',
  script.includes('coordinates here are 0~1 ratios of the screen - not') &&
    script.includes('pixel counts'),
  true,
)

check(
  '报错要说第几行 (而不是一句"格式不对")',
  script.includes('"line $number: $why"'),
  true,
)

check(
  '设置页那一行与那个对话框都在',
  [
    screen.includes('R.string.settings_lock_script'),
    screen.includes('LockScriptDialog('),
    screen.includes('LockScript.format(current)'),
  ],
  [true, true, true],
)

console.log(`\n锁屏那一条的判据表全过, ${checks} 条`)
if (failures > 0) {
  console.error(`\n${failures} 条没过`)
  process.exit(1)
}
