// 临时 (不入库): 在**手机上**把插件那一层真的跑一遍
//
// 为什么要在手机上跑而不是在开发机上: 插件的下载/建屏/关屏都是自己 fetch 与自己调桥, 在 PC 上跑那个
// harness 时路径是手机的 (files/wake-word/…), 于是字节落在 PC 上、手机那侧照样报"没下" —— 那验不出
// 网络、sha256 与屏。这个脚本用 app 自己的 node 与自己的 host 树, 所以与模型调用时走的是同一条路
//
// 用法 (在 run-as 里):
//   LD_LIBRARY_PATH=<nativeLibraryDir> <nativeLibraryDir>/libnode.so /data/local/tmp/lw-prepare.mjs
// 环境: LW_CHANNEL_ENDPOINT / LW_CHANNEL_TOKEN (app 那侧正在跑的桥)
// 输入: /data/local/tmp/lw-tool.txt 一行工具名 (缺省 lw_wakeword)
//       /data/local/tmp/lw-plan.json 一个参数对象数组 (缺省走内置那一套)

import { readFileSync } from 'node:fs'

const HOST = '/data/data/io.github.yuloong07star.luwi/files/host'

const readText = (path, fallback) => {
  try {
    return readFileSync(path, 'utf8').trim()
  } catch {
    return fallback
  }
}

const toolName = readText('/data/local/tmp/lw-tool.txt', '') || 'lw_wakeword'
const planFile = '/data/local/tmp/lw-plan.json'
const fallback = [
  { op: 'status' },
  { op: 'keywords', words: [
    '肥鱼肥鱼=fei2 yu2 fei2 yu2',
    '肥鱼肥鱼=hui2 yu2 hui2 yu2',
    '肥鱼肥鱼=fei2 yi2 fei2 yi2',
    '肥鱼肥鱼=hui2 yi2 hui2 yi2',
  ] },
  { op: 'status' },
]
let plan = fallback
try {
  plan = JSON.parse(readFileSync(planFile, 'utf8'))
} catch {
  console.log(`(${planFile} not readable, using the built-in plan)`)
}
if (!Array.isArray(plan)) plan = [plan]

const registered = []
const ctx = {
  tools: { register: (definition) => registered.push(definition) },
  on() {},
  logger: { warn: (...args) => console.error('[warn]', ...args) },
}

const plugin = await import(`${HOST}/node_modules/luwi-channel/index.mjs`)
plugin.apply(ctx)
console.log(`tools registered: ${registered.length}`)

const tool = registered.find((candidate) => candidate.name === toolName)
if (!tool) {
  console.error(`${toolName} is not registered`)
  process.exit(2)
}

for (const args of plan) {
  try {
    const value = await tool.execute(args)
    console.log(`\n=== ${toolName} ${JSON.stringify(args)} ===\n${value}`)
  } catch (error) {
    console.error(`\n=== ${toolName} ${JSON.stringify(args)} threw ===\n${error?.message ?? error}`)
  }
}
