/**
 * 词表那一份算法的**两份实现不许漂开**
 *
 * `WakeWordWords.kt` (设置页那条路) 与 `host-plugin/index.mjs` 的 `wakeWordLine` (模型那条路,
 * `lw_wakeword op=keywords`) 是同一套算法的两份实现 —— 源码里那句"改了一边记得看另一边"是**口头纪律**,
 * 而口头纪律会漂。这一条把它变成可执行的真值:
 *
 *   1. **判据表只有一份**: 直接从 `WakeWordWordsTest.kt` 里读 (那边的期望值已经由单元测试钉住了,
 *      而且它对过模型自带的例子)。所以这边不抄一遍 —— 抄一遍就是第三份实现
 *   2. **两份实现跑同一张表**, 逐行比对自己吐出来的 token 串
 *   3. **符号表是"取并集再少一个"**: 期望值的并集保证每个合法 token 都在, 再故意去掉一个 ——
 *      这样"表里没有就抛"那一关也是真的在考 (拿实际输出当表会让它变成空转)
 *
 * 跑法: node tools/check-wake-words.mjs   (不需要设备, 也不需要 host)
 */

import { readFile } from 'node:fs/promises'

const plugin = await readFile(new URL('../host-plugin/index.mjs', import.meta.url), 'utf8')
const test = await readFile(
  new URL('../app/src/test/java/io/github/miuzarte/littlewhale/wake/WakeWordWordsTest.kt', import.meta.url),
  'utf8',
)
// 常量 (声母表 / 调号表) 在实现那一份里, 判据表在测试那一份里
const impl = await readFile(
  new URL('../app/src/main/java/io/github/miuzarte/littlewhale/wake/WakeWordWords.kt', import.meta.url),
  'utf8',
)

/** 从插件源码里切一段出来跑, 不复制第二份 */
function slice(from, to) {
  const start = plugin.indexOf(from)
  if (start < 0) throw new Error(`host-plugin/index.mjs 里找不到 ${JSON.stringify(from)}`)
  const end = to === undefined ? plugin.length : plugin.indexOf(to, start)
  if (end < 0) throw new Error(`host-plugin/index.mjs 里找不到分界 ${JSON.stringify(to)}`)
  return plugin.slice(start, end)
}

const body = [
  slice('const WAKEWORD_DEFAULT_WORDS', '/** 模型认得的那张符号表'),
  slice('/** 一个词 + 它的拼音', '/** "大肥鱼大肥鱼=da4'),
].join('\n')

const node = new Function(
  `${body}\nreturn { wakeWordLine, markedSyllable, toneIndex, PINYIN_INITIALS, TONE_MARKS }`,
)()

/* ── 判据表: 从 Kotlin 那份测试里读 ─────────────────────────────────────────── */

const cases = []
for (const raw of test.split('\n')) {
  const line = raw.trim()
  if (!line.startsWith('"')) continue
  const pair = /^"(.+?)"\s+to\s+"(.*)",?$/.exec(line)
  if (pair) cases.push({ pair: pair[1], expected: pair[2] })
}
if (cases.length < 10) throw new Error(`只读到 ${cases.length} 对判据, 解析那一段大概坏了`)

/**
 * 读到的对数必须与 Kotlin 那份 `cases` 的**行数**对上
 *
 * 少了就是"某一条判据在这一侧被安静地跳过", 而这一侧看不出来 —— 一条没被跑的判据比一条红的判据
 * 危险得多 (红的那条会被人看见)
 */
const declared = test
  .slice(test.indexOf('private val cases'), test.indexOf('/** 符号表:'))
  .split('\n')
  .filter((one) => /^\s*"/.test(one) && one.includes(' to '))
  .length
if (declared !== cases.length) {
  throw new Error(`Kotlin 那份写了 ${declared} 对, 这边只读出来 ${cases.length} 对`)
}

/** 每一对拆成 word 与 pinyin (插件那条路收的是分开的两个参数, Kotlin 收的是合起来的一对) */
const split = (pair) => {
  const at = pair.indexOf('=')
  return { word: pair.slice(0, at).trim(), pinyin: pair.slice(at + 1).trim() }
}

const tokensOf = (expected) => expected.split(' @')[0].split(' ').filter(Boolean)
const all = new Set(cases.flatMap((one) => tokensOf(one.expected)))
const oneFewer = new Set(all)
oneFewer.delete('n')

let failures = 0
let checks = 0
function check(name, got, want) {
  checks += 1
  const same = JSON.stringify(got) === JSON.stringify(want)
  if (!same) failures += 1
  console.log(`${same ? 'ok  ' : 'FAIL'} ${name}${same ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`}`)
}

console.log(`判据表: ${cases.length} 对, 期望里出现 ${all.size} 个 token`)

// 1. 两份实现逐行一致 (这是这份检查存在的理由)
const drifted = []
for (const one of cases) {
  const { word, pinyin } = split(one.pair)
  let got
  try {
    got = node.wakeWordLine(word, pinyin, all)
  } catch (error) {
    got = `THREW: ${error.message}`
  }
  if (got !== one.expected) drifted.push({ word, expected: one.expected, got })
}
check('每一对都与 Kotlin 那份一致', drifted, [])

/** 取 Kotlin 里一段源码: 从 from 到 to (不含) */
function kotlinSlice(text, from, to) {
  const start = text.indexOf(from)
  if (start < 0) throw new Error(`Kotlin 里找不到 ${JSON.stringify(from)}`)
  const end = text.indexOf(to, start)
  if (end < 0) throw new Error(`Kotlin 里找不到分界 ${JSON.stringify(to)}`)
  return text.slice(start, end)
}

// 2. 两边认的声母表一样 (顺序也要一样: 长的在前, 否则 zh 会被拆成 z + h)
const kotlinInitials = [
  ...kotlinSlice(impl, 'private val INITIALS', '\n    )').matchAll(/"([a-z]+)"/g),
].map((one) => one[1])
check('声母表两份一模一样', node.PINYIN_INITIALS, kotlinInitials)

// 3. 调号表也一样 (每个元音四个声调, 顺序不能反)
//
// 键那个字符不能写成 `\w`: JS 的 `\w` 只认 ASCII, 而这里有 `ü` 那一个键 —— 写成 `\w` 会安静地少读
// 一行, 于是这条判据变成"五行的表与六行的表相等"然后红 (本轮就踩了一次)
const kotlinTones = [
  ...kotlinSlice(impl, 'private val TONES', '\n    )').matchAll(/'([^']+)' to "([^"]+)"/g),
].map((one) => [one[1], one[2]])
check(
  '调号表两份一模一样',
  Object.entries(node.TONE_MARKS),
  kotlinTones,
)

// 4. "表里没有就抛"那一关真的在考: 少一个 token 就必须抛, 而且点名是哪一个
const missing = (() => {
  const sample = split(cases.find((one) => one.pair.startsWith('女儿=nü3'))?.pair ?? cases[0].pair)
  try {
    node.wakeWordLine(sample.word, sample.pinyin, oneFewer)
    return 'nothing thrown'
  } catch (error) {
    return error.message
  }
})()
check('少一个 token 就抛', /no "n"/.test(missing), true)

// 5. 已知形状的两条边 (它们最容易在一边改对、另一边改错)
check(
  'er2 是 ér 而不是 er',
  node.markedSyllable('er2'),
  'ér',
)
check(
  'nv3 与 nü3 归一到同一个韵母',
  [node.markedSyllable('nv3'), node.markedSyllable('nü3'), node.markedSyllable('nu:3')],
  ['nǚ', 'nǚ', 'nǚ'],
)

console.log(failures === 0 ? `\n两份实现没漂开, ${checks} 条判据全过` : `\n${failures} / ${checks} 条判据不过`)
process.exit(failures === 0 ? 0 : 1)
