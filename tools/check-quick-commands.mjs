/**
 * 快捷指令与那几份技能的判据表 (2.5.0 批次 7, 批次 9 起多一份 photo-edit)
 *
 * 这一批有四件东西**漂开了也不会当场报错**, 只会在设备上变成"看起来能用"的样子, 所以在这里核:
 *
 *   1. **两个新工具的 op 名单两侧一致**: 应用那侧 (`LwCalendar.OPERATIONS` / `LwQuick.MODEL_OPERATIONS`)
 *      与插件那侧 (`enum:` 那一行) 是两份实现 —— 漂开的样子是"工具说明里有一个 op 其实没实现"
 *   2. **随包那几项都在**: 四份技能与两条样例 (源在仓库 `skills/` 与 `quick-commands/`)
 *   3. **技能 frontmatter 合规**: dsh 的 skill-filesystem 只认 kebab-case 的 `name` 与非空
 *      `description`, 写错了整份技能会被静默跳过 (模型目录里连一行提示都没有)
 *   4. **build.gradle.kts 那张随包表与仓库里的文件对得上**: 表里多一个名字就是构建失败, 少一个就是
 *      "主人装完发现少了一条"
 *
 * 跑法: node tools/check-quick-commands.mjs   (不需要设备, 也不需要 host)
 */

import { readFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const read = (path) => readFile(new URL(path, root), 'utf8')

const plugin = await read('host-plugin/index.mjs')
const calendar = await read('app/src/main/java/io/github/miuzarte/littlewhale/tool/LwCalendar.kt')
const quick = await read('app/src/main/java/io/github/miuzarte/littlewhale/tool/LwQuick.kt')
const gradle = await read('app/build.gradle.kts')

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

/** 一个文件在不在 */
async function exists(path) {
  try {
    await readFile(new URL(path, root), 'utf8')
    return true
  } catch {
    return false
  }
}

/** Kotlin 里 `val X = listOf("a", "b")` 那张表 */
function kotlinList(source, name) {
  const match = new RegExp(`val\\s+${name}\\s*=\\s*listOf\\(([^)]*)\\)`).exec(source)
  if (match === null) throw new Error(`在源码里找不到 ${name}`)
  return [...match[1].matchAll(/"([^"]+)"/g)].map((one) => one[1])
}

/** 插件里某个工具的 `enum: [...]` (从工具名那一行往后切到下一个工具) */
function pluginEnum(tool) {
  const start = plugin.indexOf(`'${tool}',`)
  if (start < 0) throw new Error(`插件里找不到 ${tool}`)
  const rest = plugin.slice(start)
  const next = rest.indexOf('simpleTool(', 1)
  const body = next < 0 ? rest : rest.slice(0, next)
  const match = /enum:\s*\[([^\]]*)\]/.exec(body)
  if (match === null) throw new Error(`${tool} 的参数表里找不到 enum`)
  return [...match[1].matchAll(/'([^']+)'/g)].map((one) => one[1])
}

/* ── 一、两个新工具的 op 名单 ────────────────────────────────────────────── */

check(
  'lw_calendar 的 op: 应用那侧与插件那侧一致',
  kotlinList(calendar, 'OPERATIONS'),
  pluginEnum('lw_calendar'),
)

check(
  'lw_quick 的 op: 插件只给模型那三个 (delete 只在桥上有)',
  pluginEnum('lw_quick'),
  kotlinList(quick, 'MODEL_OPERATIONS'),
)

check(
  'lw_quick 的桥上有 delete, 而且它不在给模型的那三个里',
  [kotlinList(quick, 'OPERATIONS').includes('delete'), pluginEnum('lw_quick').includes('delete')],
  [true, false],
)

check(
  '两个新工具都挂在桥上 (PrivilegedBridge 有 calendar 与 quick 两条分支)',
  [
    (await read('app/src/main/java/io/github/miuzarte/littlewhale/channel/PrivilegedBridge.kt'))
      .includes('"calendar" -> appContext'),
    (await read('app/src/main/java/io/github/miuzarte/littlewhale/channel/PrivilegedBridge.kt'))
      .includes('"quick" -> appContext'),
  ],
  [true, true],
)

/* ── 二、随包那五项 ─────────────────────────────────────────────────────── */

/** build.gradle.kts 里那两张表 */
const shippedSkills = kotlinList(gradle, 'shippedSkills')
const shippedCommands = kotlinList(gradle, 'shippedQuickCommands')

check('随包的技能是那四份', shippedSkills, ['web-search', 'weather', 'calendar', 'photo-edit'])
check('随包的样例快捷指令是那两条', shippedCommands, ['制定旅游计划', '今天要做什么'])

for (const name of shippedSkills) {
  check(`技能 ${name} 在仓库里 (skills/${name}/SKILL.md)`, await exists(`skills/${name}/SKILL.md`), true)
}
for (const name of shippedCommands) {
  check(`样例 ${name} 在仓库里 (quick-commands/${name}.md)`, await exists(`quick-commands/${name}.md`), true)
}

/* ── 三、技能 frontmatter ───────────────────────────────────────────────── */

/** dsh 只认 kebab-case 的 name, 写错了整份技能被静默跳过 */
const kebab = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

for (const name of shippedSkills) {
  const body = await read(`skills/${name}/SKILL.md`)
  const front = /^---\n([\s\S]*?)\n---/.exec(body)
  const fields = front === null ? {} : Object.fromEntries(
    front[1]
      .split('\n')
      .map((line) => /^([a-zA-Z-]+):\s*(.*)$/.exec(line))
      .filter((one) => one !== null)
      .map((one) => [one[1], one[2].trim()]),
  )
  check(
    `技能 ${name} 的 frontmatter (name 与目录同名且是 kebab-case, description 非空)`,
    [fields.name, kebab.test(fields.name ?? ''), (fields.description ?? '').length > 0],
    [name, true, true],
  )
}

/* ── 四、样例文件名与正文 ───────────────────────────────────────────────── */

const badName = /[/\\]/
for (const name of shippedCommands) {
  const body = await read(`quick-commands/${name}.md`)
  check(
    `样例 ${name}: 名字能当文件名, 正文里有步骤`,
    [badName.test(name), name.startsWith('.'), /^\s*\d+\.\s/m.test(body)],
    [false, false, true],
  )
}

console.log(`\n${checks - failures} / ${checks} 条通过`)
if (failures > 0) process.exit(1)
