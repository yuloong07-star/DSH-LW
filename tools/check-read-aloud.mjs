// 念之前那次清洗的实测: 直接拿 host-plugin/index.mjs 里的那段源码来跑, 不复制一份出来
// (复制出来测的就不是上线的那份了)。用法: node tools/check-read-aloud.mjs
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../host-plugin/index.mjs', import.meta.url), 'utf8')
const start = source.indexOf('function readAloudText(')
if (start < 0) throw new Error('readAloudText is not in host-plugin/index.mjs')
const end = source.indexOf('\n}\n', start)
if (end < 0) throw new Error('readAloudText has no end')
const readAloudText = new Function(`${source.slice(start, end + 2)}; return readAloudText`)()

const cases = [
  // 代码块整块走掉, 段落之间留一个换行 (换行对 TTS 就是一次停顿, 那是要的)
  ['看这里\n\n```js\nconst a = 1\n```\n\n就是这样', '看这里\n就是这样'],
  ['**重点**是他很*快*', '重点是他很快'],
  ['见 [文档](https://x.com/a) 与 ![图](a.png)', '见 文档 与'],
  ['| 名字 | 值 |\n| --- | --- |\n| a | 1 |', ''],
  ['# 标题\n- 第一条\n1. 第二条\n> 引用', '标题\n第一条\n第二条\n引用'],
  ['`code` 与 https://x.com 都别念', 'code 与 都别念'],
  ['snake_case_thing 是标识符', 'snake_case_thing 是标识符'],
  ['---\n正文', '正文'],
  ['', ''],
  ['只有一段话', '只有一段话'],
]

let bad = 0
for (const [input, want] of cases) {
  const got = readAloudText(input)
  const ok = got === want
  if (!ok) bad += 1
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${JSON.stringify(input.slice(0, 40))} -> ${JSON.stringify(got)}`)
  if (!ok) console.log(`     wanted ${JSON.stringify(want)}`)
}
console.log(bad === 0 ? `all ${cases.length} cases match` : `${bad} of ${cases.length} cases differ`)
process.exit(bad === 0 ? 0 : 1)
