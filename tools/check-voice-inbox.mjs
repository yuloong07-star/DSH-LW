/**
 * 投递队列那半边逻辑的实测: 谁被投出去、谁不许被投第二遍
 *
 * 这一段的真值只有三条, 而三条都不是"看起来对"就够的:
 *   1. **第一次跑不许把历史倒进会话** (游标文件不在时从当前尾部开始)
 *   2. **投递成功才前移** (失败的那句留在下游, 下次接着投, 不是静默丢掉)
 *   3. **文件被轮转 / 截断 / 半行都不许重放** (去重靠 seq, 不靠字节游标)
 *
 * 跑法: node tools/check-voice-inbox.mjs
 * 它**直接取 host-plugin/index.mjs 里的那段源码**来跑 (不复制第二份), 所以测的是上线那份
 */

import { mkdir, readFile, stat, writeFile } from 'node:fs/promises'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'

const source = await readFile(new URL('../host-plugin/index.mjs', import.meta.url), 'utf8')
const start = source.indexOf('/** 投递队列的位置')
const stateAt = source.indexOf('async function voiceInboxState(')
const end = source.indexOf('\n}\n', stateAt)
if (start < 0 || stateAt < 0 || end < 0) throw new Error('the voice inbox block is not in host-plugin/index.mjs')
const body = source.slice(start, end + 2)

const api = new Function(
  'readFile', 'mkdir', 'stat', 'writeFile', 'join', 'dirname',
  `${body}\nreturn { voiceInboxPath, voiceReadNew, voiceCursorStore, voiceInboxState }`,
)(readFile, mkdir, stat, writeFile, join, dirname)

const home = mkdtempSync(join(tmpdir(), 'lw-inbox-'))
process.env.DSH_HOME = home
const inbox = api.voiceInboxPath()
await mkdir(dirname(inbox), { recursive: true })

const line = (seq, text) => `${JSON.stringify({ seq, at: 1759600000000 + seq, text, source: 'voice' })}\n`

let failures = 0
let checks = 0
function check(name, got, want) {
  checks += 1
  const same = JSON.stringify(got) === JSON.stringify(want)
  if (!same) failures += 1
  console.log(`${same ? 'ok  ' : 'FAIL'} ${name}${same ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`}`)
}

// 1. 第一次跑: 文件里已经有 3 句 (装之前说的), 一句都不许投出去
await writeFile(inbox, line(1, '旧话一') + line(2, '旧话二') + line(3, '旧话三'))
check('第一次跑不倒历史', await api.voiceReadNew(inbox), [])

// 2. 新来的两句按顺序投出去
await writeFile(inbox, await readFile(inbox, 'utf8') + line(4, '新话四') + line(5, '新话五'))
const fresh = await api.voiceReadNew(inbox)
check('只投新的两句', fresh.map((item) => item.text), ['新话四', '新话五'])
check('序号是文件里的那个', fresh.map((item) => item.seq), [4, 5])

// 3. 没前移游标就是重投 (投递失败时的那条路), 前移之后就不再出现
check('没前移就是重投', (await api.voiceReadNew(inbox)).length, 2)
await api.voiceCursorStore(inbox, 4)
check('前移一条之后只剩另一条', (await api.voiceReadNew(inbox)).map((item) => item.seq), [5])
await api.voiceCursorStore(inbox, 5)
check('都投过就没有了', await api.voiceReadNew(inbox), [])

// 4. 轮转: 文件从尾部重写, 只留最后一行 —— 不许因为"文件短了"而重放
await writeFile(inbox, line(5, '新话五'))
check('轮转之后不重放', await api.voiceReadNew(inbox), [])

// 5. 末尾留着半行 (进程被杀): 跳过它, 不抛, 也不影响前面那行
await writeFile(inbox, line(6, '新话六') + '{"seq":7,"at":1759600')
check('半行被跳过, 前面那行照投', (await api.voiceReadNew(inbox)).map((item) => item.text), ['新话六'])
await api.voiceCursorStore(inbox, 6)

// 6. 半行补齐成完整的一行之后它是新的, 该投
await writeFile(inbox, line(6, '新话六') + line(7, '新话七'))
check('补齐之后投出来', (await api.voiceReadNew(inbox)).map((item) => item.text), ['新话七'])

// 7. 没有正文的行 (空串) 不当它是一句话
await writeFile(inbox, line(8, '   ') + line(9, '新话九'))
check('空正文不算一句', (await api.voiceReadNew(inbox)).map((item) => item.text), ['新话九'])

// 8. 状态报得出队列与游标
const state = await api.voiceInboxState()
check('状态里的游标', state.cursor, 6)

rmSync(home, { recursive: true, force: true })
console.log(failures === 0 ? `\n投递队列的 ${checks} 条判据全过` : `\n${failures} / ${checks} 条判据不过`)
process.exit(failures === 0 ? 0 : 1)
