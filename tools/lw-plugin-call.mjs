/**
 * 临时脚本 (不入库): 让插件那一层真的调一次桥
 *
 * 桥验的是应用侧, 插件那一层 (defineTool 的参数表 -> drop(args) -> 一行 JSON -> answerOf) 只有在
 * 模型调用时才走到。这里用同一个注册表把工具取出来, 直接 execute 一遍, 不花模型的钱
 *
 * 用法: node tools/lw-plugin-call.mjs <工具名> '<参数 JSON>'
 * 环境: LW_CHANNEL_ENDPOINT / LW_CHANNEL_TOKEN 指向已经 forward 到本机的桥
 */

import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const [name, argsJson = '{}'] = process.argv.slice(2)
if (!name) {
  console.error('usage: node tools/lw-plugin-call.mjs <tool> [json args]')
  process.exit(2)
}

const registered = []
const ctx = { tools: { register: (definition) => registered.push(definition) }, on() {} }

const plugin = await import(pathToFileURL(resolve('host-plugin/index.mjs')).href)
plugin.apply(ctx)

const tool = registered.find((candidate) => candidate.name === name)
if (!tool) {
  console.error(`no tool called ${name}`)
  process.exit(2)
}

try {
  const value = await tool.execute(JSON.parse(argsJson))
  console.log(`[${name}] ${JSON.stringify(value)}`)
} catch (error) {
  console.log(`[${name}] threw: ${error?.message ?? error}`)
}
