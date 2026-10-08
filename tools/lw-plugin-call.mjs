/**
 * 临时脚本 (不入库): 让插件那一层真的调一次桥
 *
 * 桥验的是应用侧, 插件那一层 (defineTool 的参数表 -> drop(args) -> 一行 JSON -> answerOf) 只有在
 * 模型调用时才走到。这里用同一个注册表把工具取出来, 直接 execute 一遍, 不花模型的钱
 *
 * **批次 3 起还多一档 `--guard`**: 只问那道闸 (模式闸与相机占用表) 怎么说, 一次桥都不调。闸是同步
 * 判据, 所以这里能像宿主那样, 拿一个假的 `exec` 直接问 —— 而 `exec.agent.id` 就是 dsh 交到工具手里的
 * SessionId, 也就是"这一场" (用环境变量 `LW_SESSION_ID` 指定, 缺省 `dev-session`)
 *
 * 用法:
 *   node tools/lw-plugin-call.mjs <工具名> '<参数 JSON>'
 *   node tools/lw-plugin-call.mjs --guard <工具名> '<参数 JSON>'
 * 环境: LW_CHANNEL_ENDPOINT / LW_CHANNEL_TOKEN 指向已经 forward 到本机的桥
 *       LW_SESSION_ID 这一次算哪一场 (缺省 dev-session)
 *       DSH_HOME      闸读的那两个文件在它下面的 modes/ (.active 与 camera-owner.json)
 */

import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const argv = process.argv.slice(2)
const guardOnly = argv[0] === '--guard'
const [name, argsJson = '{}'] = guardOnly ? argv.slice(1) : argv
if (!name) {
  console.error('usage: node tools/lw-plugin-call.mjs [--guard] <tool> [json args]')
  process.exit(2)
}

const registered = []
const guards = []
const ctx = {
  tools: {
    register: (definition) => registered.push(definition),
    guard: (check) => guards.push(check),
  },
  on() {},
  // 这个桩只要不抛就够了: 插件在 apply() 里挂的那几条定时链 (语音队列) 与这里要问的事无关
  effect: () => ({ dispose() {} }),
}

const plugin = await import(pathToFileURL(resolve('host-plugin/index.mjs')).href)
plugin.apply(ctx)

/** 这一次调用算哪一场: 闸认的就是这一个字段 (`Agent.id` 就是 SessionId) */
const session = process.env.LW_SESSION_ID || 'dev-session'
const args = JSON.parse(argsJson)
const exec = { name, arguments: args, agent: { id: session } }

if (guardOnly) {
  if (guards.length === 0) {
    console.error('this plugin registered no guard')
    process.exit(2)
  }
  const reason = guards.map((check) => check(exec)).find((one) => one !== undefined)
  console.log(reason === undefined ? `[${name}] guard: allowed` : `[${name}] guard: refused -> ${reason}`)
  process.exit(0)
}

const tool = registered.find((candidate) => candidate.name === name)
if (!tool) {
  console.error(`no tool called ${name}`)
  process.exit(2)
}

try {
  const value = await tool.execute(args, exec)
  console.log(`[${name}] ${JSON.stringify(value)}`)
} catch (error) {
  console.log(`[${name}] threw: ${error?.message ?? error}`)
}
