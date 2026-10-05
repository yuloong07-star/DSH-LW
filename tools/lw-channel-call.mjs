/**
 * 临时 (不入库): 直接往 app 的通道里发一次调用, 不经插件也不经模型
 *
 * 为什么要有它: `tools/lw-bridge.ps1` 走的是 `su -c` 读宿主环境, 而这台设备上没有 root 授权 (只有
 * Shizuku)。这一条用 run-as 读同一份环境 (/proc/<host pid>/environ), 端口 forward 到本机之后按
 * "一连接一请求一行 JSON" 那套发一条, 所以验的是**应用那一侧**, 与插件那一层分开
 *
 * 用法: LW_CHANNEL_ENDPOINT=127.0.0.1:<forwarded> LW_CHANNEL_TOKEN=<token> \
 *         node tools/lw-channel-call.mjs <method> '<params json>'
 */

import { connect } from 'node:net'

const [method, paramsJson = '{}'] = process.argv.slice(2)
if (!method) {
  console.error('usage: node tools/lw-channel-call.mjs <method> [json params]')
  process.exit(2)
}

const endpoint = process.env.LW_CHANNEL_ENDPOINT
const token = process.env.LW_CHANNEL_TOKEN
if (!endpoint || !token) {
  console.error('LW_CHANNEL_ENDPOINT and LW_CHANNEL_TOKEN both have to be set')
  process.exit(2)
}

const [host, port] = [endpoint.slice(0, endpoint.lastIndexOf(':')), Number(endpoint.slice(endpoint.lastIndexOf(':') + 1))]
const line = `${JSON.stringify({ method, token, ...JSON.parse(paramsJson) })}\n`

const socket = connect({ host, port })
let received = ''
const timeout = setTimeout(() => {
  console.error('no answer within 180s')
  process.exit(1)
}, 180_000)

socket.on('connect', () => socket.write(line))
socket.on('data', (chunk) => {
  received += chunk.toString('utf8')
  if (received.includes('\n')) {
    clearTimeout(timeout)
    const answer = received.split('\n')[0]
    try {
      console.log(JSON.stringify(JSON.parse(answer), null, 2))
    } catch {
      console.log(answer)
    }
    socket.end()
  }
})
socket.on('error', (error) => {
  clearTimeout(timeout)
  console.error(`channel error: ${error.message}`)
  process.exit(1)
})
socket.on('close', () => {
  clearTimeout(timeout)
  if (!received) {
    console.error('the channel closed without an answer')
    process.exit(1)
  }
})
