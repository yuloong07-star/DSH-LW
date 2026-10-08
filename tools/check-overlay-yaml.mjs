/**
 * 发之前先把 app 会写出来的那份 overlay 解析一遍
 *
 * 为什么值得单独一步: 那份 YAML 是 Kotlin 用字符串拼的, 而**拼错了的代价是整台 host 起不来**
 * —— 2026-10-05 就是这样: `name:` 的值没加引号, 而 `@` 开头在 YAML 里是保留字符, 于是
 * "bad indentation of a mapping entry", 真机上 host 直接起不来。这一步不需要设备, 只要 js-yaml
 *
 * 判据: 两种形态 (官方 bundle 开着 / 关着) 都要能被 js-yaml 解析, 且解析出来的结构与预期一致
 *
 * 用法: node tools/check-overlay-yaml.mjs
 */

import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
let yaml = null
for (const candidate of ['js-yaml', 'yaml']) {
  try {
    yaml = require(candidate)
    break
  } catch {
    // 换下一个
  }
}
// 本仓那棵树 (`app/build/host-tree`) 里带着 `yaml`, 而它不在 tools/ 的解析路径上 —— 打好的树在的
// 时候直接取它, 这样这一步不用先装一个包才能跑 (它量的就是"那份 overlay 拼出来能不能解析")
if (yaml === null) {
  const tree = new URL('../app/build/host-tree/node_modules/yaml/package.json', import.meta.url)
  if (existsSync(fileURLToPath(tree))) {
    try {
      yaml = createRequire(fileURLToPath(tree))('yaml')
    } catch {
      yaml = null
    }
  }
}
if (yaml === null) {
  console.error('neither js-yaml nor yaml is installed; run this from the host tree or the repo root')
  process.exit(2)
}

const parse = (text) => (typeof yaml.load === 'function' ? yaml.load(text) : yaml.parse(text))

/** 与 PluginOverlay.write 拼出来的东西逐字对应: 两种形态各一份 */
const bundleOff = `# Written by LittleWhale on every host start, edits are overwritten
- insert:
    - id: littlewhale-channel
      name: '/data/user/0/pkg/files/host/node_modules/littlewhale-channel/index.mjs'
    - id: dsh-web-mobile
      name: '/data/user/0/pkg/files/host/node_modules/dsh-web-mobile/lib/index.js'
    - id: imagegen
      name: '/data/user/0/pkg/files/host/node_modules/@dickpy/dsh-imagegen/lib/index.js'
    - id: speech-to-text
      name: '/data/user/0/pkg/files/host/node_modules/@deepseek-ai/dsh-experimental-speech-to-text/lib/index.js'
      config:
        defaultProvider: lw-native
        language: auto
    - id: speech-to-text-api
      name: '/data/user/0/pkg/files/host/node_modules/@deepseek-ai/dsh-experimental-api-speech-to-text/lib/index.js'
    - id: voice-input
      name: '/data/user/0/pkg/files/host/node_modules/@deepseek-ai/dsh-experimental-client-ui-voice-input/lib/index.js'
`

const bundleOn = `# Written by LittleWhale on every host start, edits are overwritten
# The official voice-input bundle is on, so its rows already exist:
# this only points that provider at this app's own engine
- id: speech-to-text
  name: '@deepseek-ai/dsh-experimental-speech-to-text'
  config:
    defaultProvider: lw-native
    language: auto
- insert:
    - id: littlewhale-channel
      name: '/data/user/0/pkg/files/host/node_modules/littlewhale-channel/index.mjs'
    - id: dsh-web-mobile
      name: '/data/user/0/pkg/files/host/node_modules/dsh-web-mobile/lib/index.js'
    - id: imagegen
      name: '/data/user/0/pkg/files/host/node_modules/@dickpy/dsh-imagegen/lib/index.js'
`

/** 曾经发出去过的那一版: 少了一对引号, 就是它把真机的 host 弄挂的 —— 这一条必须被判为坏 */
const broken = bundleOn.replace("name: '@deepseek-ai/dsh-experimental-speech-to-text'", 'name: @deepseek-ai/dsh-experimental-speech-to-text')

let failures = 0
function check(name, condition, detail = '') {
  if (!condition) failures += 1
  console.log(`${condition ? 'ok  ' : 'FAIL'} ${name}${condition ? '' : `  ${detail}`}`)
}

for (const [name, text, wantRows, wantOverride] of [
  ['bundle 关着: 六行 insert', bundleOff, 6, false],
  ['bundle 开着: 一条覆盖 + 三行 insert', bundleOn, 3, true],
]) {
  let doc = null
  try {
    doc = parse(text)
  } catch (error) {
    check(name, false, `解析失败: ${error.message}`)
    continue
  }
  const override = doc.filter((entry) => entry.insert === undefined)
  const inserted = doc.flatMap((entry) => entry.insert ?? [])
  check(name, inserted.length === wantRows && override.length === (wantOverride ? 1 : 0),
    `insert 行 ${inserted.length} 条 (想要 ${wantRows}), 覆盖行 ${override.length} 条`)
  check(`${name} · lw-native 指向没丢`,
    JSON.stringify(doc).includes('lw-native'),
    '解析结果里找不到 defaultProvider: lw-native')
}

let brokenRejected = false
try {
  parse(broken)
} catch {
  brokenRejected = true
}
check('少了引号的那一版会被判为坏 (这就是它当初的代价)', brokenRejected)

console.log(failures === 0 ? '\n两种形态都能解析, 且坏形态确实被拒' : `\n${failures} 条判据不过`)
process.exit(failures === 0 ? 0 : 1)
