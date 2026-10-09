/**
 * LW 插件的判据表 (2.5.1 批次 9 的 P0 / P1)
 *
 * 这一批有六处**漂开了也不会当场报错**, 只会在设备上变成"看起来能用"的样子, 所以在这里核:
 *
 *   1. **能力表三处一致**: Kotlin (`PluginCapabilities.kt`) / 协议文档第 5 节 / "接通"那一列的取法。
 *      漂开的样子是"界面上有一条能力, 插件的调用却回'表里没有它'"
 *   2. **手写 Binder 的那几个常量**: 描述符、transaction code、权限名。手写意味着没有编译器帮忙对,
 *      而错了以后设备上只会说"绑不上"或"没回应"
 *   3. **桥那一条与工具那一张脸**: `plugin` 这个桥方法两侧都在、`lw_plugin` 的 op 名单与 Kotlin
 *      那一行一致, 且 `snapshot` 只在桥上
 *   4. **样例包**: 能过协议里那几条规则 (协议 / kind / 前缀 / 工具名 / 能力 / 不许有浮点), 而且它的
 *      工具名与样例伴侣 `describe()` 里那三个常量对得上
 *   5. **界面那一段**: 两份 strings 的键一致、设置页里有 `key = "plugin"` 那一段, 而且详情对话框的
 *      正文封了高度并挂上 `verticalScroll` (不封顶时超出一屏的那一截被窗裁掉, 「卸载」那两个按钮点不到)
 *   6. **没有 .aidl**: 冻结时那条决定 (第 0 节第 11 条) —— app 模块里出现 AIDL 就会把 Java 编译
 *      拉回构建
 *
 * 协议与施工单在**工作区**那一层 (`../docs/`), 按仓库单独 clone 出来时读不到: 那几条会跳过并打一行
 * 说明, 不是失败
 *
 * 跑法: node tools/check-plugins.mjs   (不需要设备, 也不需要 host)
 */

import { readFile, readdir } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')

let failures = 0
let checks = 0
function check(name, got, want) {
  checks += 1
  const same = JSON.stringify(got) === JSON.stringify(want)
  if (!same) failures += 1
  console.log(
    `${same ? 'ok  ' : 'FAIL'} ${name}`
      + (same ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`),
  )
}

/** 工作区那一层的文档: 没有就跳过依赖它的那几条 */
async function optional(path) {
  try {
    return await read(path)
  } catch {
    return null
  }
}

const api = await read('lwplugin-api/src/main/java/io/github/miuzarte/littlewhale/plugin/api/LwPluginApi.kt')
const pluginIface = await read('lwplugin-api/src/main/java/io/github/miuzarte/littlewhale/plugin/api/ILwPlugin.kt')
const contextIface = await read('lwplugin-api/src/main/java/io/github/miuzarte/littlewhale/plugin/api/ILwPluginContext.kt')
const capabilities = await read('app/src/main/java/io/github/miuzarte/littlewhale/plugin/PluginCapabilities.kt')
const manifestRules = await read('app/src/main/java/io/github/miuzarte/littlewhale/plugin/PluginManifest.kt')
const lwPlugin = await read('app/src/main/java/io/github/miuzarte/littlewhale/tool/LwPlugin.kt')
const bridge = await read('app/src/main/java/io/github/miuzarte/littlewhale/channel/PrivilegedBridge.kt')
const manifest = await read('app/src/main/AndroidManifest.xml')
const host = await read('host-plugin/index.mjs')
const hostCheck = await read('tools/check-host-plugin.mjs')
const settings = await read('app/src/main/java/io/github/miuzarte/littlewhale/ui/SettingsScreen.kt')
const appGradle = await read('app/build.gradle.kts')
const sampleGradle = await read('samples/companion/build.gradle.kts')
const sampleManifest = await read('samples/companion/src/main/AndroidManifest.xml')
const sampleService = await read('samples/companion/src/main/java/io/github/miuzarte/littlewhale/sample/companion/CompanionPluginService.kt')
const samplePlugin = JSON.parse(await read('samples/companion/plugin/plugin.json'))
const rootGradle = await read('settings.gradle.kts')
const stringsZh = await read('app/src/main/res/values-zh/strings.xml')
const stringsEn = await read('app/src/main/res/values/strings.xml')
const protocol = await optional('../docs/LW-软件插件协议.md')

/** Kotlin 里一处 `const val NAME = "value"` */
function kotlinConst(source, name) {
  const match = new RegExp(`const val\\s+${name}\\s*=\\s*"([^"]+)"`).exec(source)
  if (match === null) throw new Error(`在源码里找不到 ${name}`)
  return match[1]
}

/** Kotlin 里一处 `const val NAME = 3` */
function kotlinNumber(source, name) {
  const match = new RegExp(`const val\\s+${name}\\s*=\\s*(\\d+)`).exec(source)
  if (match === null) throw new Error(`在源码里找不到 ${name}`)
  return Number(match[1])
}

/** 能力表: 每条一行 `Capability("名字", Level.X, "…", wired),` */
const table = [...capabilities.matchAll(/Capability\("([^"]+)",\s*Level\.(\w+),[\s\S]*?,\s*(true|false)\),/g)]
  .map((one) => ({ name: one[1], level: one[2], wired: one[3] === 'true' }))
/** 带参数的那两条在表里以冒号收尾 (`files.read:`), 文档里写成 `files.read:<路径前缀>` */
const decorated = (name) => {
  const bare = name.endsWith(':') ? name.slice(0, -1) : name
  return bare === 'files.read' || bare === 'net.http' ? `${bare}:` : bare
}
const byName = Object.fromEntries(table.map((one) => [decorated(one.name), one]))

/* ── 一、能力表 ─────────────────────────────────────────────────────────── */

check('能力表 22 条 (11 普通 + 11 敏感)', [table.length, table.filter((one) => one.level === 'NORMAL').length], [22, 11])
check(
  '带参数的那两条在源里以冒号结尾',
  table.filter((one) => one.name.endsWith(':')).map((one) => one.name).sort(),
  ['files.read:', 'net.http:'],
)

if (protocol === null) {
  console.log('note 工作区里没有 ../docs/LW-软件插件协议.md, 跳过与文档对照的那几条')
} else {
  const section = /## 5 · 能力表([\s\S]*?)## 6 ·/.exec(protocol)
  const rows = (section === null ? '' : section[1])
    .split('\n')
    .map((line) => /^\| `([^`]+)` \| [^|]* \| ([^|]*)\|$/.exec(line))
    .filter((one) => one !== null)
    // "接通"两个字在别的行里也出现过 (那些写的是"调用即如实拒 (P2 接通)"), 所以只认以它开头的那一格
    .map((one) => ({ name: decorated(one[1].replace(/<[^>]*>/g, '')), wired: one[2].trim().startsWith('**接通**') }))
  check('协议第 5 节那张表与 Kotlin 的能力表一样长', rows.length, table.length)
  check(
    '两张表的名字一一对上',
    rows.map((one) => one.name).sort(),
    table.map((one) => decorated(one.name)).sort(),
  )
  check(
    '接通那几条 (P0/P1) 两侧一致',
    rows.filter((one) => one.wired).map((one) => one.name).sort(),
    table.filter((one) => one.wired).map((one) => decorated(one.name)).sort(),
  )
  check(
    '文档里的接通数是 7 条',
    rows.filter((one) => one.wired).length,
    7,
  )
}

const reserved = ['lw', 'dsh', 'agent']
check('保留前缀写在 Kotlin 那一份里', reserved.every((one) => manifestRules.includes(`"${one}"`)), true)
if (protocol !== null) {
  const reservedLine = protocol.split('\n').find((line) => line.includes('三个前缀是保留字'))
  check('保留前缀在协议里点了名', reserved.every((one) => (reservedLine ?? '').includes(one)), true)
}

/* ── 二、手写 Binder 的那几个常量 ───────────────────────────────────────── */

check(
  '描述符两个 (与协议第 9 节同名)',
  [kotlinConst(api, 'DESCRIPTOR_PLUGIN'), kotlinConst(api, 'DESCRIPTOR_CONTEXT')],
  [
    'io.github.miuzarte.littlewhale.plugin.ILwPlugin',
    'io.github.miuzarte.littlewhale.plugin.ILwPluginContext',
  ],
)
const descriptors = [kotlinConst(api, 'DESCRIPTOR_PLUGIN'), kotlinConst(api, 'DESCRIPTOR_CONTEXT')]
if (protocol !== null) {
  check('两个描述符在协议里也写了一遍', descriptors.every((one) => protocol.includes(one)), true)
}
check(
  'ILwPlugin 的 transaction code 是 1 / 2 / 3',
  [kotlinNumber(pluginIface, 'CODE_DESCRIBE'), kotlinNumber(pluginIface, 'CODE_LIFECYCLE'), kotlinNumber(pluginIface, 'CODE_INVOKE')],
  [1, 2, 3],
)
check(
  'ILwPluginContext 的 transaction code 是 1..5',
  [
    kotlinNumber(contextIface, 'CODE_CALL'),
    kotlinNumber(contextIface, 'CODE_SETTINGS'),
    kotlinNumber(contextIface, 'CODE_STORE'),
    kotlinNumber(contextIface, 'CODE_LOG'),
    kotlinNumber(contextIface, 'CODE_API'),
  ],
  [1, 2, 3, 4, 5],
)
check('协议版本与 api 版本', [kotlinConst(api, 'PROTOCOL'), String(kotlinNumber(api, 'API_VERSION'))], ['lw-plugin/1', '1'])

const permission = kotlinConst(api, 'PLUGIN_PERMISSION')
check('权限名在 app 的清单里声明了', manifest.includes(`android:name="${permission}"`), true)
check('app 自己也 uses-permission 了它', manifest.includes(`<uses-permission android:name="${permission}" />`), true)
check('样例伴侣的 Service 用它护住了', sampleManifest.includes(`android:permission="${permission}"`), true)

/* ── 三、桥、工具与界面 ─────────────────────────────────────────────────── */

check('桥上多了一条 plugin', bridge.includes('"plugin" -> appContext { LwPlugin.dispatch(it, request) }'), true)
check('宿主两侧都在调它 (snapshot 与 invoke)', (host.match(/call\('plugin'/g) ?? []).length >= 2, true)

const operations = [.../val OPERATIONS = listOf\(([^)]*)\)/.exec(lwPlugin)[1].matchAll(/"([^"]+)"/g)].map((one) => one[1])
const hostEnums = [.../enum: \['list', 'read', 'install', 'enable', 'disable', 'uninstall', 'audit'\]/.exec(host)[0].matchAll(/'([a-z]+)'/g)]
  .map((one) => one[1])
check('lw_plugin 的 op 名单两侧一致', hostEnums, operations)
check('snapshot 只在桥上', [lwPlugin.includes('const val OP_SNAPSHOT = "snapshot"'), operations.includes('snapshot')], [true, false])

check('工具数的下限抬到 61', Number(/(?:const FLOOR = )(\d+)/.exec(hostCheck)[1]), 61)
check('宿主里注册了 lw_plugin', host.includes("'lw_plugin',"), true)
check('设置页有插件那一段', settings.includes('key = "plugin"'), true)
check(
  '插件详情对话框的正文封了高度并挂上 verticalScroll',
  // 2026-10-08 在设备上量到的那条毛病: 声明的能力多起来 (最多 22 条) 内容会长过一屏, 而 Miuix 的
  // `OverlayDialog` **自己不滚也没有 maxHeight** (参数只有 `maxWidth`) —— 不封顶时超出一屏的那一截
  // 被窗裁掉, 底下那两个「卸载」按钮点都点不到。改 `PluginDialog` 时别把这两行拆掉
  [settings.includes('.heightIn(max = (screenHeight * 0.6f).dp)'), settings.includes('.verticalScroll(rememberScrollState())')],
  [true, true],
)

const keysOf = (xml) => [...xml.matchAll(/<string name="(settings_plugin_[a-z_]+)"/g)].map((one) => one[1]).sort()
check('两份 strings 的 settings_plugin_* 键一致', keysOf(stringsZh), keysOf(stringsEn))
check('插件那一段的字符串有 20 条以上', keysOf(stringsZh).length >= 20, true)

/* ── 四、样例包 ─────────────────────────────────────────────────────────── */

check('样例的 protocol', samplePlugin.protocol, 'lw-plugin/1')
check('样例的 kind 是这一版收的那一个', samplePlugin.kind, 'companion')
check('样例的前缀合规且不是保留字', /^[a-z][a-z0-9_]{1,15}$/.test(samplePlugin.toolPrefix) && !reserved.includes(samplePlugin.toolPrefix), true)
check(
  '样例的工具名都带自己的前缀',
  samplePlugin.tools.every((one) => one.name.startsWith(`${samplePlugin.toolPrefix}_`)),
  true,
)
check(
  '样例声明的能力都在表里',
  [...samplePlugin.capabilities.requested, ...samplePlugin.capabilities.dangerous].every((one) => byName[one] !== undefined),
  true,
)
check(
  '样例把敏感档写在 dangerous 里',
  samplePlugin.capabilities.dangerous.every((one) => byName[one].level === 'DANGEROUS'),
  true,
)
check('样例的 api 与这一版一致', samplePlugin.api, String(kotlinNumber(api, 'API_VERSION')))

/** 协议里不许出现浮点: 两种语言的规范化只对整数印得一模一样 */
function floatsIn(value, path = '') {
  if (typeof value === 'number') return Number.isInteger(value) ? [] : [path]
  if (Array.isArray(value)) return value.flatMap((one, index) => floatsIn(one, `${path}[${index}]`))
  if (value !== null && typeof value === 'object') {
    return Object.entries(value).flatMap(([key, one]) => floatsIn(one, path === '' ? key : `${path}.${key}`))
  }
  return []
}
check('样例的 plugin.json 里一个浮点都没有', floatsIn(samplePlugin), [])

const sampleTools = [...sampleService.matchAll(/const val TOOL_[A-Z]+ = "([^"]+)"/g)].map((one) => one[1]).sort()
check('样例包里的工具名与它 describe() 里那三个常量一致', samplePlugin.tools.map((one) => one.name).sort(), sampleTools)
check(
  '样例的服务组件与包里 entry 对得上',
  samplePlugin.entry.service === `${samplePlugin.entry.package}.CompanionPluginService`
    && sampleManifest.includes('android:name=".CompanionPluginService"')
    && sampleGradle.includes(`namespace = "${samplePlugin.entry.package}"`),
  true,
)

const appVersion = /versionName = "([^"]+)"/.exec(await read('app/build.gradle.kts'))[1]
const [major, minor, patch] = samplePlugin.minLw.split('.').map(Number)
const [appMajor, appMinor, appPatch] = appVersion.split('.').map(Number)
const newer = major - appMajor || minor - appMinor || patch - appPatch
check(`样例的 minLw (${samplePlugin.minLw}) 不比这一版 (${appVersion}) 高`, newer <= 0, true)

/* ── 五、模块与那条"不许有 AIDL" ────────────────────────────────────────── */

check('两个新模块都在 settings.gradle.kts 里', [rootGradle.includes('include(":lwplugin-api")'), rootGradle.includes('include(":sample-companion")')], [true, true])
check('app 引了接口那一份', appGradle.includes('implementation(project(":lwplugin-api"))'), true)
check('样例也引了同一份', sampleGradle.includes('implementation(project(":lwplugin-api"))'), true)

async function aidlFiles(directory) {
  const found = []
  async function walk(current) {
    for (const entry of await readdir(current, { withFileTypes: true })) {
      if (entry.name === 'build' || entry.name === '.git') continue
      const full = new URL(`${entry.name}${entry.isDirectory() ? '/' : ''}`, current)
      if (entry.isDirectory()) await walk(full)
      else if (entry.name.endsWith('.aidl')) found.push(full.pathname)
    }
  }
  await walk(directory)
  return found
}
check('仓库里没有 .aidl (冻结时那条决定)', await aidlFiles(new URL('.', root)), [])

console.log(`\n${checks - failures} / ${checks} 条通过`)
if (failures > 0) process.exit(1)
