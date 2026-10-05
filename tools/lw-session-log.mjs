/**
 * 读一份会话日志
 *
 * 会话日志是 `session.v4.jsonl.zstd` —— **一段一段的 zstd 帧拼起来的** (一次追加一帧), 所以
 * `grep` 在设备上什么也搜不到, 而 node 的 `zstdDecompressSync` 只认第一帧 (实测: 69 KB 只解出
 * 248 字节的头)。这里按魔数把文件切成帧逐帧解, 再接成 JSON Lines
 *
 * 用法:
 *   adb exec-out run-as <包名> cat <会话目录>/session.v4.jsonl.zstd > D:\apk\lw-build-alt\s.zstd
 *   node tools/lw-session-log.mjs D:\apk\lw-build-alt\s.zstd                  # 事件类型统计 + 行数
 *   node tools/lw-session-log.mjs D:\apk\lw-build-alt\s.zstd --grep via        # 只看含某个串的行
 *   node tools/lw-session-log.mjs <文件> --type user/message                   # 只看某一类事件
 *
 * 判"语音投递"用的是 `--grep '"via":"voice"'`: 投进来的那条消息来源是
 * `{"kind":"user","via":"voice"}`, 落在 `agent/inbox/spliced` 与 `user/message` 两个事件里
 */

import { readFileSync } from 'node:fs'
import { zstdDecompressSync } from 'node:zlib'

const [path, ...rest] = process.argv.slice(2)
if (!path) {
  console.error('usage: node tools/lw-session-log.mjs <session.v4.jsonl.zstd> [--grep <text>] [--type <event type>]')
  process.exit(2)
}

/** zstd 帧的魔数: 28 B5 2F FD */
const MAGIC = [0x28, 0xb5, 0x2f, 0xfd]

function frameOffsets(bytes) {
  const offsets = []
  for (let at = 0; at + 4 <= bytes.length; at += 1) {
    if (MAGIC.every((byte, index) => bytes[at + index] === byte)) offsets.push(at)
  }
  return offsets
}

/** 一帧一帧解, 末尾的半帧丢掉 (进程被杀时会有) */
function decode(bytes) {
  const offsets = frameOffsets(bytes)
  let text = ''
  let broken = 0
  for (let index = 0; index < offsets.length; index += 1) {
    const piece = bytes.subarray(offsets[index], offsets[index + 1] ?? bytes.length)
    try {
      text += zstdDecompressSync(piece).toString('utf8')
    } catch {
      broken += 1
    }
  }
  return { text, frames: offsets.length, broken }
}

const grepAt = rest.indexOf('--grep')
const typeAt = rest.indexOf('--type')
const needle = grepAt >= 0 ? rest[grepAt + 1] : undefined
const wanted = typeAt >= 0 ? rest[typeAt + 1] : undefined

const bytes = readFileSync(path)
const { text, frames, broken } = decode(bytes)
const lines = text.split('\n').filter((line) => line.trim())

const counts = new Map()
for (const line of lines) {
  let type = '(unparsable)'
  try {
    type = JSON.parse(line).type ?? '(no type)'
  } catch {
    // 半行就是没有 type
  }
  counts.set(type, (counts.get(type) ?? 0) + 1)
}

console.log(`${path}`)
console.log(`${bytes.length} 字节 / ${frames} 帧 (坏帧 ${broken}) -> ${lines.length} 行, ${text.length} 字符`)
console.log(Array.from(counts).sort((left, right) => right[1] - left[1])
  .map(([type, count]) => `${type} x${count}`).join('  '))

const shown = lines.filter((line) => {
  if (needle !== undefined && !line.includes(needle)) return false
  if (wanted === undefined) return true
  try {
    return JSON.parse(line).type === wanted
  } catch {
    return false
  }
})
if (needle !== undefined || wanted !== undefined) {
  console.log(`\n匹配 ${shown.length} 行:`)
  for (const line of shown) console.log(line)
}
