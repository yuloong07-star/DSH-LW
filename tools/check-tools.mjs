/**
 * 扫一遍 tools/ 自己: 命名 / 用法段 / 根目录落位
 *
 * 为什么要有这一步: 这个目录里三十来个脚本是三种语言混着的, 而"约定"写在 `tools/README.md` 里 ——
 * 写在文档里的约定会漂, 除非有一条命令能把它变成可执行的真值。这个脚本就是那一条, 它量的三件事
 * 每一条都对应一次真实返工:
 *
 *   1. **命名** 决定人能不能猜出脚本是干什么的 (`lw-` 是设备脚本, `check-` 是静态校验)
 *   2. **用法段** 决定半年后还调得动它 (中英文都认: 用法 / 跑法 / usage)
 *   3. **根目录落位** 决定 `D:\apk` 根不会被一堆一次性产物慢慢占满 (那正是 `apipush\` 的来历)
 *
 * 用法: node tools/check-tools.mjs
 * 退出码: 0 = 三条都过; 1 = 有 ERROR (每个都印出文件、规则、怎么改)
 */

import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

/** 本目录 (tools/), 后面所有路径都相对它 */
const ROOT = fileURLToPath(new URL('.', import.meta.url))

/** 认的脚本后缀, 别的文件 (README / 数据) 不进检查 */
const EXTENSIONS = ['.ps1', '.mjs', '.py', '.sh']

/**
 * 命名白名单: 前缀决定这个脚本属于哪一类
 *
 * `lw-` 设备与工作流 / `check-` 静态校验 / 其余是构建与产物那几类 (见 README 的分组)
 */
const PREFIXES = [
  'lw-', 'check-', 'pack-', 'zip-', 'push-', 'gen-', 'make-', 'vector-', 'apk-', 'host-',
]

/** 子目录里不按前缀管 (它们是同一件事的几半, 放在一起比多一个前缀清楚) */
const PREFIX_EXEMPT_DIRS = ['lw-device/', 'ocr/']

/**
 * `D:\apk` 根下允许出现的名字
 *
 * 这份表就是工作区 `AGENTS.md` 的目录地图 —— 多出来的名字意味着有个脚本在根下开了新目录
 */
const ALLOWED_ROOT_NAMES = new Set([
  // 工具链 (不要动)
  'Sdk', 'AndroidStudio', 'android-studio-data', 'gradle-home', '.android', 'tools', 'downloads',
  // 项目与产物
  'Luwi', 'logs', 'shots', 'backups', '.lwtmp', 'docs', 'archive',
  // 历次专题留下的 (归档计划见 archive/README.md)
  'lw-build-alt', 'verify', 'dsha-recon', 'build-jniLibs', 'node_modules', 'dsh-020.git',
  'shizuku',
  // 根下那几个入口文件 (它们不是目录, 但同样在被引用的位置上出现)
  'env.ps1', 'dev.ps1', 'lw.ps1', 'AGENTS.md', 'README.md', '启动AndroidStudio.cmd',
])

/** 走一遍 tools/, 相对路径按 `/` 给 (在 Windows 上也一样) */
function walk(directory) {
  const found = []
  for (const entry of readdirSync(directory)) {
    const full = join(directory, entry)
    if (statSync(full).isDirectory()) {
      found.push(...walk(full))
    } else if (EXTENSIONS.includes(entry.slice(entry.lastIndexOf('.')))) {
      found.push(full)
    }
  }
  return found
}

const problems = []

function report(rule, file, detail, fix) {
  problems.push({ rule, file, detail, fix })
}

for (const full of walk(ROOT).sort()) {
  const name = relative(ROOT, full).split('\\').join('/')
  const text = readFileSync(full, 'utf8')
  const lines = text.split('\n')
  const head = lines.slice(0, 40).join('\n')
  const extension = name.slice(name.lastIndexOf('.'))

  // 规则 1: 命名
  const exempt = PREFIX_EXEMPT_DIRS.some((directory) => name.startsWith(directory))
  if (!exempt && !PREFIXES.some((prefix) => name.startsWith(prefix))) {
    report(
      'R1 命名',
      name,
      '前缀不在约定里',
      `改成 ${PREFIXES.slice(0, 2).join(' / ')} 之类的前缀 (见 tools/README.md 第一条)`,
    )
  }

  // 规则 2: 用法段
  //
  // **不要求 `-Help`**: 这些 PowerShell 脚本大多数把 -Serial 标成 Mandatory, 而 Mandatory 参数会在
  // 脚本体之前就把人拦下 —— 于是 -Help 根本走不到, 要支持它得先松掉 Mandatory, 那是另一件事
  // (会改掉"忘了给 -Serial 就报错"这条既有行为)。所以这一条只管"开头有没有一段能看懂怎么调的字"
  if (!/用法|跑法|usage/i.test(head)) {
    report('R2 用法段', name, '开头 40 行里没有用法说明', '补一行 "用法: ..." / "跑法: ..." / "usage: ..."')
  }

  // 规则 3: 根目录落位 —— 只认 D:\apk\<名字> 与 $Root\<名字> 这两种写法
  const rootPattern = /(?:D:[\\/]apk|\$Root)[\\/]([\w.\-\u4e00-\u9fa5]+)/gi
  for (const match of text.matchAll(rootPattern)) {
    const target = match[1]
    if (!ALLOWED_ROOT_NAMES.has(target)) {
      report(
        'R3 落位',
        name,
        `引用了根下的 "${target}"`,
        '临时产物写进 .lwtmp\\ , 日志写进 logs\\ , 别在根下开新目录',
      )
    }
  }
}

if (problems.length === 0) {
  console.log(`tools/ 扫完: ${walk(ROOT).length} 个脚本, 三条约定都过`)
  process.exit(0)
}

for (const problem of problems) {
  console.log(`${problem.rule}  ${problem.file}`)
  console.log(`    ${problem.detail} —— ${problem.fix}`)
}
console.log(`\n${problems.length} 处要改 (见 tools/README.md 那三条约定)`)
process.exit(1)
