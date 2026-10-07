/**
 * Materialise the dsh host tree that the APK ships
 *
 * The host cannot run from the pnpm workspace: workspace packages are linked into
 * `node_modules` as symlinks that only exist in a checkout, and the APK carries neither the
 * checkout nor the links. dsh already owns the supported alternative - pack every published
 * package into an npm tarball, then install those tarballs into an empty consumer, which is
 * what `scripts/release/verify-packed-install.ts` exercises in upstream CI - so this script
 * drives exactly that and leaves a plain, symlink-free `node_modules` behind
 *
 * usage: node tools/pack-host.mjs --dsh <checkout> --out <dir> [--install] [--skip-build]
 */

import { execFileSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'

/** Where the two release families are packed, relative to the checkout */
const PACK_ROOT = 'dist/lw-host-pack'

/**
 * Build output directories that outlive their sources
 *
 * tsdown runs with `clean: false`, so a chunk from an earlier build stays in `lib/` after its
 * source changed, and every package's `files` glob picks up `lib/*.js` wholesale - a stale chunk
 * can ship an eager native import this repository exists to remove
 */
const BUILD_OUTPUTS = [
  'packages/*/*/lib',
  'apps/*/lib',
  'vendor/*/lib',
  'native/system/packages/*/lib',
]

/**
 * Workspace tiers tsdown treats as packages
 *
 * tsdown globs these paths, reads each match's manifest, and falls back to the *repository* manifest
 * when one is missing - so a directory left behind by a removed package (node_modules only, no
 * package.json) is mistaken for a member named `@deepseek-ai/dsh-root` and the build dies on the
 * root entry glob. That is exactly what happened when 0.2.1-alpha.1 deleted
 * `packages/experimental/schedule-bundle` and `packages/runtime-diagnostics/invariants`: the
 * upgrade left both directories behind and `build:lib:host` failed with
 * `[@deepseek-ai/dsh-root] Cannot find entry: ["lib/types/{index,startup}.js"]`.
 *
 * A real package always has its manifest, so dropping a manifest-less directory is safe and keeps a
 * checkout that carried a package across an upgrade buildable
 */
const PACKAGE_TIERS = ['packages/*/*', 'vendor/*']

/** Delete package directories that lost their manifest (see [PACKAGE_TIERS]) */
function dropOrphanPackages(root) {
  let removed = 0
  for (const pattern of PACKAGE_TIERS) {
    for (const directory of expand(root, pattern)) {
      if (existsSync(join(directory, 'package.json'))) continue
      rmSync(directory, { recursive: true, force: true })
      removed += 1
    }
  }
  if (removed > 0) {
    console.log(`pack-host: dropped ${String(removed)} orphan package directory/ies under ${root}`)
  }
}

/**
 * Incremental records that outlive the outputs they describe
 *
 * `tsc -b` records what it has built in these two files. A package's own record lives inside its
 * `lib/`, so deleting that directory takes the record with it - but these two sit beside the root
 * tsconfigs and survive. Left in place they make tsc conclude the deleted packages are still built,
 * so it emits nothing and the tsdown pass that follows dies on the first package whose entry
 * JavaScript is missing. That failure names the repository root, because the root tsdown config is
 * what supplies the `lib/types/{index,invariant,startup}.js` default every package inherits
 */
const ROOT_BUILD_STATE = ['tsconfig.host.tsbuildinfo', 'tsconfig.client.tsbuildinfo']

/**
 * The image backend the host tree carries, and why it is the WebAssembly build
 *
 * The attachment store asks sharp two questions about every image that comes in: is this really an
 * image, and how big is it. sharp's ordinary route is closed on this device - its platform packages
 * wrap libvips, which is built against glibc, and `--omit=optional` above drops them regardless.
 * `@img/sharp-wasm32` needs no native binding of any kind: it is one WebAssembly module, and sharp
 * picks it on its own once `@img/sharp-linux-arm64` is not installed
 *
 * Measured with no native binding present at all (a scratch `--omit=optional` install): a JPEG it
 * encoded itself came back as `format jpeg, 64x48, 3 channels` and decoded to pixels, PNG did not
 * regress. Before this the tree carried a stand-in that answered only from the PNG header, so every
 * JPEG, WebP and GIF the user picked was refused with INVALID_IMAGE
 *
 * @see image-backend/README.md for what the stand-in was and what replaced it
 */
const IMAGE_BACKEND = { sharp: '0.35.5', '@img/sharp-wasm32': '0.35.5' }

/**
 * Run one command in the checkout, inheriting stdio so build progress stays visible
 *
 * **Windows 上不能给每条命令都套 shell** (2026-10-05 实测): `shell: true` 时 Node 把命令行交给
 * `cmd.exe`, 而它按**空格**切词 —— `C:\Program Files\nodejs\node.exe --import tsx/esm …` 于是被
 * 读成"运行 `C:\Program`", 报的是一句 `'C:\Program' is not recognized as an internal or external
 * command`。所以这里分两条路: node 自己那些调用**不走 shell** (execFileSync 会正确加引号), 只有
 * pnpm 那个 `.cmd` 壳需要 shell (Windows 上 `.cmd` 不能直接 exec)
 */
function run(command, args, cwd) {
  const invocation = commandInvocation(command, args)
  execFileSync(invocation.command, invocation.args, {
    cwd,
    stdio: 'inherit',
    env: pnpmEnvironment(),
  })
}

/**
 * 一条命令怎么真的跑起来 (Windows 上那两种坑各踩过一次)
 *
 * - **不许套 `shell: true`**: 它把命令行交给 `cmd.exe`, 而 cmd 按空格切词 ——
 *   `C:\Program Files\nodejs\node.exe --import tsx/esm …` 于是变成"运行 `C:\Program`"
 * - **`.cmd` 壳也不能直接 exec**: Node 24 上 `spawnSync(…\pnpm.cmd)` 直接 `EINVAL`。所以 pnpm 一律
 *   走它自己的 **`.mjs` 入口** (工作区那份, 见 [findPnpm]), 用 node 跑它 —— dsh 的
 *   `scripts/pnpm-invocation.ts` 正好认这一个形状: `npm_execpath` 以 `.mjs` 结尾就用
 *   `process.execPath` 去跑它 (它对 `.cmd` 那条路在 Node 24 上同样是 EINVAL)
 */
function commandInvocation(command, args) {
  if (command === process.execPath) return { command, args }
  if (/\.mjs$/iu.test(command)) return { command: process.execPath, args: [command, ...args] }
  if (process.platform === 'win32') return { command: 'cmd.exe', args: ['/d', '/s', '/c', command, ...args] }
  return { command, args }
}

/**
 * 找一个能用的 pnpm, 以及 dsh 那两个脚本要求的环境
 *
 * **为什么需要这一步** (2026-10-05 实测): dsh 的 `scripts/build.ts` 第一件事是 `runScript`, 而它
 * 从 `npm_execpath` 里取包管理器 —— 那个变量**只在 `pnpm run` 下才存在**。这个脚本直接用
 * `node --import tsx` 调它, 于是构建在第一步就死:
 *
 * ```
 * Error: pnpm invocation: npm_execpath is unavailable; invoke the script through pnpm run.
 * ```
 *
 * 于是这里把两件事补齐 —— **一个真的 pnpm** (从 PATH 找 `pnpm`, 找不到退到 Windows 上 npm 的全局
 * 安装目录; 只在找不到时才报错, 而且报的是"装一个 pnpm 或者给 LW_PNPM 指路"), 以及
 * **`npm_execpath` 指向它**。pnpm 自己认得出 `.cmd` / `.ps1` 壳 (它是读文件内容而不是 exec 它),
 * 所以 Windows 上指 `.cmd` 那一份就是对的
 *
 * 另外: dsh 是 pnpm workspace, `node_modules` 里只有 workspace 链接, 各包自己的依赖**没装** ——
 * 而它 root 的 `pnpm run check` 要 tsc / tsdown / eslint, 所以那个 install 是必须的 (Gradle 那一侧
 * 传 `--install`; 没传时这里补一句警告, 免得构建死在"某个包缺 typescript"上)
 */
function pnpmEnvironment() {
  if (pnpmEnv !== null) return pnpmEnv
  const found = findPnpm()
  if (found === null && !process.env.npm_execpath) {
    throw new Error(
      'pnpm was not found: install it (npm i -g pnpm) or point LW_PNPM at it. The dsh build runs'
        + ' itself through pnpm and refuses to start without npm_execpath',
    )
  }
  if (found !== null) console.log(`pack-host: pnpm is ${found}`)
  pnpmEnv = {
    ...process.env,
    ...(process.env.npm_execpath ? {} : { npm_execpath: found }),
    // dsh 的 `postinstall` 会去装 lefthook 的 git 钩子, 而这一仓的 submodule 是 linked worktree:
    // 它的 `core.worktree` 写在 common config 里, 于是那一步必然失败
    // ("cannot enable extensions.worktreeConfig while core.worktree is in the common config"),
    // 而 **pnpm 会因此整个 install 退出码非 0** —— 构建还没开始就停在这里。那一步与"把 host 树
    // 打出来"毫无关系 (它只是给开发者装提交钩子), 所以这里让它按 dsh 自己的 CI 分支跳过:
    // `install-lefthook.mjs` 第一行就是 `if (process.env.CI === 'true' || GITHUB_ACTIONS === 'true') return`
    GITHUB_ACTIONS: process.env.GITHUB_ACTIONS ?? 'true',
  }
  return pnpmEnv
}

/** 上面那份环境只算一次, 之后 install 与 build 共用 */
let pnpmEnv = null

/** pnpm 在哪: 环境变量 -> PATH 上的可执行 -> npm 的全局目录 -> 工作区自带的那份 .mjs */
function findPnpm() {
  if (process.env.LW_PNPM && existsSync(process.env.LW_PNPM)) return process.env.LW_PNPM
  const bundled = process.env.LW_PNPM_BUNDLED ?? DEFAULT_BUNDLED_PNPM
  if (existsSync(bundled)) return bundled
  const names = process.platform === 'win32' ? ['pnpm.cmd', 'pnpm.exe'] : ['pnpm']
  for (const directory of (process.env.PATH ?? '').split(process.platform === 'win32' ? ';' : ':')) {
    if (!directory) continue
    for (const name of names) {
      const candidate = join(directory, name)
      if (existsSync(candidate)) return candidate
    }
  }
  const global = join(process.env.APPDATA ?? '', 'npm')
  for (const name of names) {
    const candidate = join(global, name)
    if (existsSync(candidate)) return candidate
  }
  return null
}

/**
 * 工作区自带的那一份 pnpm (dsh 的运行时目录里就有)
 *
 * **优先用它而不是 PATH 上那个 `.cmd`**: Node 24 上 exec 一个 `.cmd` 会直接 EINVAL, 而这一份是
 * `.mjs` —— `npm_execpath` 指它, dsh 的 `pnpmInvocation` 就会用 `process.execPath` 去跑, 两边都
 * 不需要 shell。路径可以用 `LW_PNPM_BUNDLED` 覆盖
 */
const DEFAULT_BUNDLED_PNPM = join(
  process.env.USERPROFILE ?? process.env.HOME ?? '',
  '.dsh',
  'dsh-runtimes',
  'dsh-primary-runtime',
  'dependencies',
  'pnpm',
  'bin',
  'pnpm.mjs',
)

/** Expand a pattern whose only wildcard is a whole path segment, keeping directories that exist */
function expand(root, pattern) {
  let current = [root]
  for (const segment of pattern.split('/')) {
    const next = []
    for (const directory of current) {
      if (segment !== '*') {
        next.push(join(directory, segment))
        continue
      }
      if (!existsSync(directory)) continue
      for (const entry of readdirSync(directory, { withFileTypes: true })) {
        if (entry.isDirectory()) next.push(join(directory, entry.name))
      }
    }
    current = next
  }
  return current.filter(existsSync)
}

/** Delete every stale build output so the rebuild cannot repack a chunk from a previous one */
function cleanBuildOutputs(root) {
  let removed = 0
  for (const pattern of BUILD_OUTPUTS) {
    for (const directory of expand(root, pattern)) {
      rmSync(directory, { recursive: true, force: true })
      removed += 1
    }
  }
  for (const name of ROOT_BUILD_STATE) {
    const record = join(root, name)
    // Only count what was actually there, so the line below still means something on a clean tree
    if (!existsSync(record)) continue
    rmSync(record, { force: true })
    removed += 1
  }
  console.log(`pack-host: removed ${String(removed)} stale build output(s) under ${root}`)
}

/** Every tarball under one directory, keyed by the package name it declares */
function packedDependencies(directory) {
  const packages = new Map()
  for (const filename of readdirSync(directory).filter(name => name.endsWith('.tgz')).sort()) {
    const manifest = execFileSync('tar', ['-xOzf', join(directory, filename), 'package/package.json'], {
      encoding: 'utf8',
    })
    const { name, version } = JSON.parse(manifest)
    packages.set(name, { url: pathToFileURL(join(directory, filename)).href, version })
  }
  return packages
}

/** Recursive file count and byte total, for the size report */
function measure(root) {
  let files = 0
  let bytes = 0
  const walk = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name)
      if (entry.isDirectory()) walk(path)
      else {
        files += 1
        bytes += statSync(path).size
      }
    }
  }
  walk(root)
  return { files, bytes }
}

const { values } = parseArgs({
  options: {
    dsh: { type: 'string' },
    out: { type: 'string' },
    install: { type: 'boolean', default: false },
    'skip-build': { type: 'boolean', default: false },
  },
  allowPositionals: false,
})
if (values.dsh === undefined || values.out === undefined) {
  throw new Error('usage: node tools/pack-host.mjs --dsh <checkout> --out <dir> [--install] [--skip-build]')
}

const dsh = resolve(values.dsh)
const out = resolve(values.out)
if (!existsSync(join(dsh, 'pnpm-workspace.yaml'))) throw new Error(`${dsh} is not a dsh checkout`)
if (values.install) {
  run('pnpm', ['install', '--frozen-lockfile'], dsh)
} else if (!existsSync(join(dsh, 'node_modules', 'typescript'))) {
  // 上面那段长注释里的第三件事: 没有这个 install, root 的 `pnpm run check` 一步都跑不动
  console.warn(
    'pack-host: the dsh checkout has no workspace install (node_modules/typescript is missing);'
      + ' pass --install (Gradle does) or the official build will fail on its first script',
  )
}

// The pack step refuses artifacts that were not produced by an official client build, so the
// build profile is not optional here even though a plain `pnpm run build` would boot locally
if (!values['skip-build']) {
  cleanBuildOutputs(dsh)
  dropOrphanPackages(dsh)
  run(process.execPath, ['--import', 'tsx/esm', 'scripts/build.ts', '--profile', 'official'], dsh)
}

const packs = {}
for (const family of ['dsh', 'vendor']) {
  const directory = join(dsh, PACK_ROOT, family)
  // pack.ts clears its own output directory, so the two families must not share one
  rmSync(directory, { recursive: true, force: true })
  run(
    process.execPath,
    ['--import', 'tsx/esm', 'scripts/release/pack.ts', '--family', family, '--out', join(PACK_ROOT, family), '--concurrency', '8'],
    dsh,
  )
  packs[family] = packedDependencies(directory)
}

const packed = new Map([...packs.vendor, ...packs.dsh])
rmSync(out, { recursive: true, force: true })
mkdirSync(out, { recursive: true })
writeFileSync(join(out, 'package.json'), `${JSON.stringify({
  name: 'littlewhale-host',
  version: '0.0.0',
  private: true,
  dependencies: {
    ...Object.fromEntries([...packed].map(([name, entry]) => [name, entry.url])),
    ...IMAGE_BACKEND,
  },
}, null, 2)}\n`)

// Optional dependencies stay out for the same reason the release job omits them: the Landlock
// platform packages need one native build per architecture, and a consumer that cannot install
// them has to start anyway
//
// Windows 上要 `npm.cmd`: `cmd /c npm` 找不到那个壳 (真名是 npm.cmd), POSIX 上就是 `npm`
//
// **`--ignore-scripts` 是必须的** (2026-10-05 实测): 树里 `koffi` 那个包的 postinstall 会
// `node ./cnoke.cjs --prebuild`, 找不到预编译件就**当场改成从源码编**, 于是这台 Windows 机器上
// 报 `CMake does not seem to be available` 并把整个 install 打成非零退出 —— 而这一棵树是**给安卓
// 装的**: 本机编出来的 `.node` 一个都用不上 (安卓那一份是包里自带的 prebuilt), 装了也只会把
// Windows 的二进制塞进 APK。所以这一步只解包、不跑任何 lifecycle
const installArgs = ['install', '--no-audit', '--no-fund', '--package-lock=false', '--omit=optional']
if (process.platform === 'win32') installArgs.push('--ignore-scripts')
run(process.platform === 'win32' ? 'npm.cmd' : 'npm', installArgs, out)

// 裁掉这台设备上永远用不到的构建期 / 浏览器自动化包 (2026-10-05, 1.2.0 的瘦身那一步)
//
// 依据是"运行时真正挂了什么": profile 只挂 `dsh-base` + `dsh-web-app` 两个 bundle, 把它们的
// package.json 依赖闭包算出来 (`tools/host-reach.py`), 树里有 **206 个包不在那份闭包里**, 解压后
// 145 MiB。但**不可达不等于能删**: `@img/sharp-wasm32` 与 `dsh-web-mobile` 都是运行时按名字找的,
// 删了会静默坏掉。所以这里只裁"安卓上根本没有对应物"的那一类 —— 浏览器自动化 (设备上没有
// playwright 要的浏览器二进制)、端到端测试与打包工具链 (vitest / vite / testing-library)。
// 裁完必须跑一遍真机/模拟器冒烟: host 起得来、GUI 渲染、工具数 51、读屏 / 通知 / OCR / 朗读
const deadWeight = [
  'playwright', 'playwright-core', '@puppeteer/browsers',
  'chrome-devtools-mcp', '@browserbasehq/stagehand', '@browserbasehq/sdk',
  'vitest', 'vite',
  '@testing-library/dom', '@testing-library/react', 'react-dom', 'react',
]
let pruned = 0
for (const name of deadWeight) {
  const path = join(out, 'node_modules', ...name.split('/'))
  if (!existsSync(path)) continue
  rmSync(path, { recursive: true, force: true })
  pruned += 1
}
console.log(`pack-host: pruned ${String(pruned)} package(s) this device cannot run`)

// The mobile web-ui plugin is fetched into a scratch directory and copied in, rather than declared
// as a dependency of the tree. Declaring it would drag npm's peer resolution over the whole install:
// its peer range admits released 0.1.x and 0.2.0-rc.1+, but not the prerelease this tree is built
// from (0.1.5-rc.2), so the install would only pass with --legacy-peer-deps - a flag that changes
// how the ENTIRE dsh closure resolves, to accommodate one package that needs nothing from it. Here
// the relaxed resolution is confined to a throwaway directory
const webui = 'dsh-web-mobile'
const webuiStage = join(out, '.webui-stage')
mkdirSync(webuiStage, { recursive: true })
writeFileSync(join(webuiStage, 'package.json'), `${JSON.stringify({ name: 'lw-webui-stage', private: true }, null, 2)}\n`)
run(
  'npm',
  ['install', `${webui}@3.0.4`, '--no-audit', '--no-fund', '--package-lock=false', '--omit=optional', '--legacy-peer-deps'],
  webuiStage,
)
cpSync(join(webuiStage, 'node_modules', webui), join(out, 'node_modules', webui), { recursive: true })
rmSync(webuiStage, { recursive: true, force: true })
console.log(`pack-host: installed the ${webui} plugin from the registry`)

// 第三方插件与它自己声明的依赖对不对得上, 只有真 import 一次才知道
//
// 漏一个依赖时, npm 不会说话 —— 包照样装进来, 而它在**加载时**才抛, 于是设备上的表现是"这个功能
// 整块不见了", 而不是一句安装错误。手机上中过一次同类的: 打包时漏了 `rrule`, 定时任务全停
// (2026-10-04 的真机记录)。这里 import 一次它的入口: 模块求值期就把这种问题暴露出来的地方
const webuiManifest = JSON.parse(
  readFileSync(join(out, 'node_modules', webui, 'package.json'), 'utf8'),
)
const webuiEntry = typeof webuiManifest.main === 'string' ? webuiManifest.main : 'index.js'
try {
  await import(pathToFileURL(join(out, 'node_modules', webui, webuiEntry)).href)
} catch (error) {
  throw new Error(
    `${webui} cannot be imported after installation (${error?.message ?? error}): it is missing a`
    + ' dependency it actually imports. Declare it in the host tree\'s dependencies, or drop the plugin',
  )
}
console.log(`pack-host: ${webui} imports cleanly`)

// LittleWhale's own host plugin is copied in rather than packed: it is a few hundred lines of
// plain ESM with no build step, and it has to sit under node_modules so that its import of
// @deepseek-ai/dsh-tools resolves to the same copy the host itself uses
const plugin = fileURLToPath(new URL('../host-plugin', import.meta.url))
const installed = join(out, 'node_modules', 'littlewhale-channel')
cpSync(plugin, installed, { recursive: true })
console.log(`pack-host: installed the LittleWhale plugin from ${plugin}`)

// 真把这个包 import 一次, 数一数注册出来几个工具
//
// `defineTool` 在模块求值的时候就编译一遍参数 schema, 而插件的 TOOLS 是模块级的常量数组: 一张不
// 合规的 schema 抛在 import 上, 整包一起死, 表现是"会话里一个 lw_ 工具都没有"。装完之后立刻验,
// 比装上手机再发现便宜得多。这里不用上面那个 run(): 它带 shell, 而参数里的路径可能带空格
execFileSync(process.execPath, [
  fileURLToPath(new URL('./check-host-plugin.mjs', import.meta.url)),
  join(installed, 'index.mjs'),
], { cwd: out, stdio: 'inherit' })

// sharp 随树装进来 (见 IMAGE_BACKEND), 这里不再覆盖任何东西 —— 以前那一步是把一个只认 PNG 的
// 替身盖在 sharp 上, 代价是相册里的照片一律进不来

const { files, bytes } = measure(join(out, 'node_modules'))
console.log(`pack-host: ${String(packed.size)} tarball(s), ${String(files)} file(s), ${(bytes / 1024 / 1024).toFixed(1)} MB in ${out}`)
