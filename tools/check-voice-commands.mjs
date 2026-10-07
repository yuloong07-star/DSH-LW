/**
 * 命令句那一份词表的**两份实现不许漂开**
 *
 * 这一批的"一句话全开"有两只手碰同一张表: 宿主插件里那一张 (`VOICE_COMMANDS`, 真正认句子的人) 与
 * 应用侧那几个规范句子 (`voice/VoiceCommands.kt`, 浮标菜单、浮标上那一记双击与「叫醒之后」开关写
 * 出去的就是它们)。源码里写一句"改了一边记得看另一边"是口头纪律, 而口头纪律会漂 —— 这一条把它变成
 * 可执行的真值:
 *
 *   1. **Kotlin 那五个句子必须在插件那张表里**, 而且落在同一个类上 (视频 / 识屏 / 手机 / 打断):
 *      `SCREEN_OFF`("退出识屏模式", 浮标菜单那一行关的方向) 与 `PHONE` 是同一个模式, 所以它落在
 *      插件那张表的 `phone` 那一支里; `INTERRUPT`("打断当前回答", 2026-10-06 双击球写的那一句) 落在
 *      `interrupt` 那一支里, 而那一支**没有 `mode`** —— 它不是切模式
 *   2. **模式名与 `LwModes` 的常量一致** (`phone` / `video` / `screen`): 插件认的是它, 应用写的是它,
 *      漂了就是"命令认出来了但切到了别处"
 *   3. **归一化那几种写法** (句末句号 / 中间空白 / 前后空白) 都要匹配 —— 识别出来的句子会带标点
 *   4. **整句相等, 不做包含匹配**: "视频模式怎么改" 这种句子必须照旧进会话, 那是这一处最容易写错的
 *      地方 (命令表认错了人, 主人会看到自己的问题没了)
 *   5. **表里自己不许自相矛盾**: 同一个说法不能同时属于两个类
 *
 * 跑法: node tools/check-voice-commands.mjs   (不需要设备, 也不需要 host)
 */

import { readFile } from 'node:fs/promises'

const plugin = await readFile(new URL('../host-plugin/index.mjs', import.meta.url), 'utf8')
const commands = await readFile(
  new URL('../app/src/main/java/io/github/miuzarte/littlewhale/voice/VoiceCommands.kt', import.meta.url),
  'utf8',
)
const modes = await readFile(
  new URL('../app/src/main/java/io/github/miuzarte/littlewhale/channel/LwModes.kt', import.meta.url),
  'utf8',
)

/** 从插件源码里切一段出来跑, 不复制第二份 (与 check-wake-words.mjs 同一个办法) */
function slice(from, to) {
  const start = plugin.indexOf(from)
  if (start < 0) throw new Error(`host-plugin/index.mjs 里找不到 ${JSON.stringify(from)}`)
  const end = plugin.indexOf(to, start)
  if (end < 0) throw new Error(`host-plugin/index.mjs 里找不到分界 ${JSON.stringify(to)}`)
  return plugin.slice(start, end)
}

const node = new Function(
  `${slice('const VOICE_COMMANDS', '/** 被当成命令吃掉的句子')}\n`
    + 'return { VOICE_COMMANDS, normalizeCommand, matchVoiceCommand }',
)()

/* ── 两份源码里的常量 ─────────────────────────────────────────────────────── */

/** Kotlin 那一侧的规范句子: `const val VIDEO = "打开视频模式"` */
const kotlinCommands = [...commands.matchAll(/const val (VIDEO|SCREEN_OFF|SCREEN|PHONE|INTERRUPT) = "([^"]+)"/g)]
  .map((one) => ({ name: one[1], say: one[2] }))
if (kotlinCommands.length !== 5) {
  throw new Error(`VoiceCommands.kt 里只读到 ${kotlinCommands.length} 个句子, 解析那一段大概坏了`)
}

/** 插件那张表里每一条属于哪一类: 切模式的用 `mode`, 打断那一支**没有 mode** (它不是模式) */
function commandClass(command) {
  return command.mode === undefined ? 'interrupt' : command.mode
}

/** 表里切模式的那几条 (判模式名时要把打断那一支滤掉) */
const modeCommands = node.VOICE_COMMANDS.filter((one) => one.mode !== undefined)

/** `LwModes` 那三个模式名 (插件那张表的 mode 字段必须是它们) */
const kotlinModes = Object.fromEntries(
  [...modes.matchAll(/const val (PHONE|VIDEO|SCREEN) = "([^"]+)"/g)].map((one) => [one[1], one[2]]),
)
if (!kotlinModes.PHONE || !kotlinModes.VIDEO || !kotlinModes.SCREEN) {
  throw new Error('LwModes.kt 里读不到 PHONE / VIDEO / SCREEN 三个常量')
}

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

console.log(
  `命令表: ${modeCommands.length} 个模式 + ${node.VOICE_COMMANDS.length - modeCommands.length} 个打断那一支,`
    + ` 说法 ${node.VOICE_COMMANDS.reduce((n, one) => n + one.say.length, 0)} 条`,
)

// 1. 插件那张表认得 Kotlin 写出去的那五个句子, 且类对得上
const expectedClass = {
  VIDEO: kotlinModes.VIDEO,
  SCREEN: kotlinModes.SCREEN,
  // 识屏那一档的"关"与"回到手机模式"是同一件事 (同一个模式), 所以它认的是 phone
  SCREEN_OFF: kotlinModes.PHONE,
  PHONE: kotlinModes.PHONE,
  // 打断那一支没有 mode: 它不是切模式, 而是"取消浮标那一场正在跑的轮"
  INTERRUPT: 'interrupt',
}
const unread = kotlinCommands
  .map((one) => ({
    name: one.name,
    say: one.say,
    matched: node.matchVoiceCommand(one.say) === null ? null : commandClass(node.matchVoiceCommand(one.say)),
    want: expectedClass[one.name],
  }))
  .filter((one) => one.matched !== one.want)
check('Kotlin 那五个句子都在表里, 且落在对的类上', unread, [])

// 2. 表里那三个模式名就是 LwModes 的常量 (顺序也照 Kotlin 那边)
check(
  '三个模式名与 LwModes 一致',
  modeCommands.map((one) => one.mode),
  [kotlinModes.VIDEO, kotlinModes.SCREEN, kotlinModes.PHONE],
)

// 3. 归一化: 识别出来的句子会带标点与空白
check('句末句号照旧认得出', node.matchVoiceCommand('打开视频模式。')?.mode ?? null, kotlinModes.VIDEO)
check('中间与前后空白照旧认得出', node.matchVoiceCommand('  切到 视频模式  ')?.mode ?? null, kotlinModes.VIDEO)
check('识屏那一句也认得出', node.matchVoiceCommand('切到识屏模式！')?.mode ?? null, kotlinModes.SCREEN)
check('识屏关闭那一句也认得出', node.matchVoiceCommand('退出识屏模式！')?.mode ?? null, kotlinModes.PHONE)
check('手机那一句也认得出', node.matchVoiceCommand('退出视频模式！')?.mode ?? null, kotlinModes.PHONE)
check('打断那一句也认得出', (node.matchVoiceCommand('打断当前回答。')?.interrupt ?? null), true)

// 4. **整句相等**: 含这几个字的长句是一句要进会话的话, 不是命令
const notCommands = [
  '视频模式怎么改',
  '我想打开视频模式的开关在哪',
  '刚才那个视频模式挺好',
  '识屏模式能做什么',
  '回到手机模式之后会怎样',
  '打断当前回答之后会怎样',
  '帮我打断一下那个回答',
  '',
  '   ',
]
check(
  '长句与空句都不算命令',
  notCommands.filter((one) => node.matchVoiceCommand(one) !== null),
  [],
)

// 5. 表自己不许自相矛盾: 同一个说法只许属于一个类 (模式 / 打断)
const seen = new Map()
const crossed = []
for (const command of node.VOICE_COMMANDS) {
  for (const say of command.say) {
    const key = node.normalizeCommand(say)
    if (seen.has(key) && seen.get(key) !== commandClass(command)) crossed.push(say)
    seen.set(key, commandClass(command))
  }
}
check('没有一句话同时属于两个类', crossed, [])

// 6. 表里每一条说法都真的匹配得上它自己那个类 (手滑写错一个字的兜底)
const selfMissed = []
for (const command of node.VOICE_COMMANDS) {
  for (const say of command.say) {
    const matched = node.matchVoiceCommand(say)
    if (matched === null || commandClass(matched) !== commandClass(command)) selfMissed.push(say)
  }
}
check('表里每一条说法都能匹配到自己', selfMissed, [])

console.log(failures === 0 ? `\n两份实现没漂开, ${checks} 条判据全过` : `\n${failures} / ${checks} 条判据不过`)
process.exit(failures === 0 ? 0 : 1)
