/**
 * 命令句那一份词表的**两份实现不许漂开**
 *
 * 这一批的"一句话全开"有两只手碰同一张表: 宿主插件里那一张 (`VOICE_COMMANDS`, 真正认句子的人) 与
 * 应用侧那两个规范句子 (`voice/VoiceCommands.kt`, 浮标菜单与「叫醒之后」开关写出去的就是它们)。
 * 源码里写一句"改了一边记得看另一边"是口头纪律, 而口头纪律会漂 —— 这一条把它变成可执行的真值:
 *
 *   1. **Kotlin 那两个句子必须在插件那张表里**, 而且映射到同一个模式 (视频 / 手机)
 *   2. **模式名与 `LwModes` 的常量一致** (`phone` / `video`): 插件认的是它, 应用写的是它, 漂了就是
 *      "命令认出来了但切到了别处"
 *   3. **归一化那几种写法** (句末句号 / 中间空白 / 前后空白) 都要匹配 —— 识别出来的句子会带标点
 *   4. **整句相等, 不做包含匹配**: "视频模式怎么改" 这种句子必须照旧进会话, 那是这一处最容易写错的
 *      地方 (命令表认错了人, 主人会看到自己的问题没了)
 *   5. **表里自己不许自相矛盾**: 同一个说法不能同时属于两个模式
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
const kotlinCommands = [...commands.matchAll(/const val (VIDEO|PHONE) = "([^"]+)"/g)]
  .map((one) => ({ name: one[1], say: one[2] }))
if (kotlinCommands.length !== 2) {
  throw new Error(`VoiceCommands.kt 里只读到 ${kotlinCommands.length} 个句子, 解析那一段大概坏了`)
}

/** `LwModes` 那两个模式名 (插件那张表的 mode 字段必须是它们) */
const kotlinModes = Object.fromEntries(
  [...modes.matchAll(/const val (PHONE|VIDEO) = "([^"]+)"/g)].map((one) => [one[1], one[2]]),
)
if (!kotlinModes.PHONE || !kotlinModes.VIDEO) {
  throw new Error('LwModes.kt 里读不到 PHONE / VIDEO 两个常量')
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
  `命令表: ${node.VOICE_COMMANDS.length} 个模式, 说法 ${node.VOICE_COMMANDS.reduce((n, one) => n + one.say.length, 0)} 条`,
)

// 1. 插件那张表认得 Kotlin 写出去的那两个句子, 且模式对得上
const expectedMode = { VIDEO: kotlinModes.VIDEO, PHONE: kotlinModes.PHONE }
const unread = kotlinCommands
  .map((one) => ({
    name: one.name,
    say: one.say,
    matched: node.matchVoiceCommand(one.say)?.mode ?? null,
    want: expectedMode[one.name],
  }))
  .filter((one) => one.matched !== one.want)
check('Kotlin 那两个句子都在表里, 且切到对的模式', unread, [])

// 2. 表里那两个模式名就是 LwModes 的常量
check(
  '两个模式名与 LwModes 一致',
  node.VOICE_COMMANDS.map((one) => one.mode),
  [kotlinModes.VIDEO, kotlinModes.PHONE],
)

// 3. 归一化: 识别出来的句子会带标点与空白
check('句末句号照旧认得出', node.matchVoiceCommand('打开视频模式。')?.mode ?? null, kotlinModes.VIDEO)
check('中间与前后空白照旧认得出', node.matchVoiceCommand('  切到 视频模式  ')?.mode ?? null, kotlinModes.VIDEO)
check('手机那一句也认得出', node.matchVoiceCommand('退出视频模式！')?.mode ?? null, kotlinModes.PHONE)

// 4. **整句相等**: 含这几个字的长句是一句要进会话的话, 不是命令
const notCommands = [
  '视频模式怎么改',
  '我想打开视频模式的开关在哪',
  '刚才那个视频模式挺好',
  '回到手机模式之后会怎样',
  '',
  '   ',
]
check(
  '长句与空句都不算命令',
  notCommands.filter((one) => node.matchVoiceCommand(one) !== null),
  [],
)

// 5. 表自己不许自相矛盾: 同一个说法只许属于一个模式
const seen = new Map()
const crossed = []
for (const command of node.VOICE_COMMANDS) {
  for (const say of command.say) {
    const key = node.normalizeCommand(say)
    if (seen.has(key) && seen.get(key) !== command.mode) crossed.push(say)
    seen.set(key, command.mode)
  }
}
check('没有一句话同时属于两个模式', crossed, [])

// 6. 表里每一条说法都真的匹配得上它自己那个模式 (手滑写错一个字的兜底)
const selfMissed = []
for (const command of node.VOICE_COMMANDS) {
  for (const say of command.say) {
    if (node.matchVoiceCommand(say)?.mode !== command.mode) selfMissed.push(say)
  }
}
check('表里每一条说法都能匹配到自己', selfMissed, [])

console.log(failures === 0 ? `\n两份实现没漂开, ${checks} 条判据全过` : `\n${failures} / ${checks} 条判据不过`)
process.exit(failures === 0 ? 0 : 1)
