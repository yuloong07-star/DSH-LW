/**
 * preset-backend —— 让设置页在两条 dsh 线上跑同一套逻辑。
 *
 * ## 这个目录解决什么
 *
 * preset 机制在 0.1.7 被整体换过：旧线**扫目录**、服务带读改删；新线是**声明式注册表**、
 * 不扫目录、没有 remove。设置页本身（UI、双语、composition 编译、提示词读写）两线一模一样，
 * 差别只在三处：
 *
 *   A 路径     —— 助手目录在哪、prompt/composition 文件在哪
 *   B roster   —— 「有哪些助手」从哪知道
 *   C 出厂组成 —— base mode 的 composition 从哪来
 *
 * 本模块把这三处收成一个后端对象（见 notes/2026-09-22-双线适配设计.md §3）。
 *
 * ## 关键设计：新线上**合成 roster**，而不是让下游到处分支
 *
 * 新线 `list()` 返回的行没有 `trust` / `path`（实测），而 `assistants.mjs` 的
 * `assistantsFromRoster` / `assistantDir` 与 `index.mjs` 的整个路由层都建立在这两个字段上。
 * 与其把线判断散布到十几处调用点，不如在**这一处**补出一个与旧线同形的行列表：
 * 助手本来就是磁盘上的目录（`$DSH_HOME/.agent-presets/<id>/`），扫一遍即可。
 * 这样路由层零改动，两条线走同一段代码。
 */

import { existsSync, readFileSync, statSync } from 'node:fs'
import { basename, join } from 'node:path'
import { scanManagedDirs } from '../assistants.mjs'
import { parseComposition } from './parse-composition.mjs'

export { detectPresetBackend, describeBackend, BACKEND_LEGACY, BACKEND_DECLARATIVE, BACKEND_UNUSABLE } from './detect.mjs'
export { createDeclarativeBackend, toPluginRows, absoluteSpecifier } from './declarative.mjs'
export { parseComposition, CompositionParseError } from './parse-composition.mjs'

/** The managed-assistant marker file, same name `assistants.mjs` uses. */
const SEED_MARKER = '.custom-mode.json'
const COMPOSITION_FILE = 'agent.cordis.yml'
const META_FILE = 'preset.yml'

/**
 * Read `preset.yml` beside a composition.
 *
 * Uses the same small parser as the composition: the file is written by this feature
 * (`yamlScalar`), so a strict subset is enough, and a hand-broken meta must not take the page down.
 *
 * @param {string} dir - assistant directory.
 * @returns {{name?: string, description?: string}} whatever could be read.
 */
export function readPresetMeta(dir) {
  const file = join(dir, META_FILE)
  if (!existsSync(file)) return {}
  try {
    const parsed = parseComposition(readFileSync(file, 'utf8'))
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    const out = {}
    if (typeof parsed.name === 'string') out.name = parsed.name
    if (typeof parsed.description === 'string') out.description = parsed.description
    return out
  } catch {
    // 元数据读不了不是致命问题：目录里还有 composition，助手仍应出现（只是名字退回 id）。
    return {}
  }
}

/**
 * Directories under `root` that this feature manages.
 *
 * Delegates to `assistants.mjs:scanManagedDirs` on purpose. My first version invented its own
 * predicate (look for `.custom-mode.json` inside each assistant directory) and therefore found
 * **nothing** — measured: the preset root held `.agent-presets/.custom-mode.json` (a root-level
 * "this feature owns this root" marker) while the assistant directory had no such file, so
 * `sync()` reported "目标 0 个助手". The real predicate is "has prompt.md, and either ships
 * prompt-reader.mjs or the composition still references it". Reusing it also means the two
 * definitions cannot drift.
 *
 * @param {string} root - user preset root.
 * @returns {string[]} absolute directory paths, sorted for a stable roster order.
 */
export function scanManagedAssistantDirs(root) {
  return scanManagedDirs(root)
}

/**
 * Synthesize roster rows in the shape `assistants.mjs` expects, by scanning the disk.
 *
 * Only needed on the declarative line, whose `list()` does not carry `trust` / `path`.
 * On the legacy line the platform's own rows are richer (they include `broken` diagnostics),
 * so callers should prefer them and fall back to this.
 *
 * @param {string} root - user preset root (`$DSH_HOME/.agent-presets`).
 * @returns {Array<{id: string, trust: 'user', path: string, name: string, description: string}>}
 */
export function synthesizeRosterRows(root) {
  return scanManagedAssistantDirs(root).map((dir) => {
    const meta = readPresetMeta(dir)
    return {
      id: basename(dir),
      trust: 'user',
      path: join(dir, COMPOSITION_FILE),
      name: meta.name ?? '',
      description: meta.description ?? '',
    }
  })
}

/**
 * The roster to drive the settings page with.
 *
 * Legacy line: the platform's rows already describe authored presets, so they are used as-is.
 * Declarative line: its rows are display metadata only, so authored presets are re-derived from disk
 * and **merged** with whatever the registry reported (registry rows win for `broken`, which is a
 * mount diagnostic only the platform can produce).
 *
 * @param {Array<object>} rows - `agentPresets.list()` result.
 * @param {{root: string, backendId: string}} options
 * @returns {Array<object>} rows including every authored assistant.
 */
export function effectiveRosterRows(rows, { root, backendId }) {
  const platform = Array.isArray(rows) ? rows : []
  if (backendId !== 'declarative') return platform

  const byId = new Map()
  for (const synth of synthesizeRosterRows(root)) byId.set(synth.id, synth)

  // 注册表能给出 mount 诊断（broken），合并进合成行；合成行提供 trust/path。
  //
  // ★ name / description **磁盘优先**：注册表里那两份是**上一次注册**留下的副本（我们自己注册的），
  //   让它们覆盖 preset.yml 里的新值，"改名"就永远不生效 —— 实测（2026-09-25，0.1.7-rc.2）：
  //   POST /state 存下 name=写作助手、preset.yml 已是新名、readState 也读到了新名，而助手列表与
  //   新会话选择器里仍是旧名「自定义模式」，直到进程重启才对齐。磁盘是用户可见、可手改、可迁移的
  //   真相（本模块头注释里的 A/B/C 里，磁盘从来就是 A 那一侧），注册表只是它的投影。
  for (const row of platform) {
    if (row === null || typeof row !== 'object' || typeof row.id !== 'string') continue
    const synth = byId.get(row.id)
    if (synth === undefined) continue
    if (typeof row.broken === 'string' && row.broken !== '') synth.broken = row.broken
    if ((synth.name === undefined || synth.name === '') && typeof row.name === 'string') synth.name = row.name
    if ((synth.description === undefined || synth.description === '') && typeof row.description === 'string') {
      synth.description = row.description
    }
  }
  return [...byId.values()]
}

/** True when `dir` looks like a managed assistant directory (used to validate a path). */
export function isManagedAssistantDir(dir) {
  try {
    return statSync(dir).isDirectory() && existsSync(join(dir, SEED_MARKER))
  } catch {
    return false
  }
}
