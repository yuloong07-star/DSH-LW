// 临时 (不入库): 在**手机上**把插件那一层真的跑一遍
//
// 为什么要在手机上跑而不是在开发机上: 插件的下载是自己 fetch + 自己写文件, 在 PC 上跑那个 harness
// 时路径是手机的 (files/wake-word/…), 于是字节落在 PC 上、手机那侧照样报"没下" —— 那验不出网络与
// sha256。这个脚本用 app 自己的 node 与自己的 host 树, 所以与模型调用时走的是同一条路
//
// 用法 (在 run-as 里):
//   LD_LIBRARY_PATH=<nativeLibraryDir> <nativeLibraryDir>/libnode.so /data/local/tmp/lw-prepare.mjs
// 环境: LW_CHANNEL_ENDPOINT / LW_CHANNEL_TOKEN (app 那侧正在跑的桥)

const HOST = '/data/data/io.github.miuzarte.littlewhale/files/host'

const registered = []
const ctx = {
  tools: { register: (definition) => registered.push(definition) },
  on() {},
  logger: { warn: (...args) => console.error('[warn]', ...args) },
}

const plugin = await import(`${HOST}/node_modules/littlewhale-channel/index.mjs`)
plugin.apply(ctx)
console.log(`tools registered: ${registered.length}`)

const tool = registered.find((candidate) => candidate.name === 'lw_wakeword')
if (!tool) {
  console.error('lw_wakeword is not registered')
  process.exit(2)
}

import { readFileSync } from 'node:fs'

const planFile = '/data/local/tmp/lw-plan.json'
const fallback = [
  { op: 'status' },
  { op: 'keywords', words: ['大肥鱼大肥鱼=da4 fei2 yu2 da4 fei2 yu2'] },
  { op: 'status' },
]
let plan = fallback
try {
  plan = JSON.parse(readFileSync(planFile, 'utf8'))
} catch {
  console.log(`(${planFile} not readable, using the built-in plan)`)
}

for (const args of plan) {
  try {
    const value = await tool.execute(args)
    console.log(`\n=== ${JSON.stringify(args)} ===\n${value}`)
  } catch (error) {
    console.error(`\n=== ${JSON.stringify(args)} threw ===\n${error?.message ?? error}`)
  }
}
