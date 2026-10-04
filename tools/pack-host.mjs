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

/** Run one command in the checkout, inheriting stdio so build progress stays visible */
function run(command, args, cwd) {
  execFileSync(command, args, { cwd, stdio: 'inherit', shell: process.platform === 'win32' })
}

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
if (values.install) run('pnpm', ['install', '--frozen-lockfile'], dsh)

// The pack step refuses artifacts that were not produced by an official client build, so the
// build profile is not optional here even though a plain `pnpm run build` would boot locally
if (!values['skip-build']) {
  cleanBuildOutputs(dsh)
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
run('npm', ['install', '--no-audit', '--no-fund', '--package-lock=false', '--omit=optional'], out)

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
