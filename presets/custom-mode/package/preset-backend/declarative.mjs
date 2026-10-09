/**
 * declarative 后端：dsh ≥ 0.1.7 的「声明式注册表」。
 *
 * ## 与 legacy 后端的差别，一句话
 *
 * 旧线上，插件只是**把文件写到磁盘**（`$DSH_HOME/.agent-presets/<id>/`），平台自己去扫、去挂；
 * 新线上没有目录扫描，插件必须**自己把 preset 注册进注册表**，并持有返回的 disposer。
 *
 * ## 三条由实测定下来的硬约束（0.1.7-alpha.2）
 *
 *  1. **相对模块说明符不工作。** 本插件的 composition 里有 `name: './prompt-reader.mjs'` 这类
 *     相对路径；旧线上它相对 preset 目录解析，而 `register()` 在 profile 的 baseUrl 下挂载。
 *     实测：相对路径 → `broken: … never started`；绝对 `file://` URL → 正常 mount。
 *     ⇒ `toPluginRows()` 必须把相对说明符改写成绝对 URL。
 *  2. **`register()` 的返回值是 disposer**，注销唯一手段；服务上没有 `remove()` —— 所以"删除助手"
 *     由本后端的 {@link remove} 自己做（dispose + 删目录），宿主那半不再要求平台提供 `remove()`。
 *  3. **同一个 id 重复注册会抛 `Duplicate agent preset`**
 *     ⇒ 重新挂载前必须先 dispose 旧的。
 *
 * ## 不写任何文件
 *
 * 新线不需要把 preset「落到磁盘」才生效，所以这里只读 composition、只动内存注册表。
 * `agent.cordis.yml` 仍然写盘 —— 它是用户可见、可迁移、可手改的真相，也是旧线唯一的输入。
 */

import { existsSync, readFileSync, renameSync, rmSync } from 'node:fs'
import { basename, dirname, join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { parseComposition } from './parse-composition.mjs'

export const BACKEND_ID = 'declarative'

/** Evaluate a `!!js` predicate. Only ever applied to text this feature itself wrote. */
function resolveDisabled(value) {
  if (value !== null && typeof value === 'object' && typeof value.__js === 'string') {
    try {
      // eslint-disable-next-line no-new-func
      return Boolean(new Function(`"use strict"; return (${value.__js});`)())
    } catch {
      // 表达式求值不了时按"关掉"处理：宁可少一行能力，也不要让整条 preset 挂载失败。
      return true
    }
  }
  return value === true
}

/** A `./x.mjs` specifier becomes an absolute file:// URL; package specifiers pass through. */
export function absoluteSpecifier(specifier, presetDir) {
  if (typeof specifier !== 'string' || !specifier.startsWith('.')) return specifier
  return pathToFileURL(join(presetDir, specifier)).href
}

/**
 * Turn parsed composition rows into the row objects `register()` takes.
 *
 * Group rows keep their `isolate` realm and their children: the composition's own comments require a
 * service row to sit inside such a group, or it publishes into the root realm and collides.
 *
 * @param {Array<object>} rows - rows from {@link parseComposition}.
 * @param {string} presetDir - the assistant's directory, the base for relative specifiers.
 * @returns {Array<object>} plugin rows.
 */
export function toPluginRows(rows, presetDir) {
  if (!Array.isArray(rows)) return []
  const out = []
  for (const row of rows) {
    if (row === null || typeof row !== 'object' || typeof row.id !== 'string') continue
    const plugin = { id: row.id }
    if (typeof row.name === 'string') plugin.name = absoluteSpecifier(row.name, presetDir)
    if (row.group === true) {
      plugin.group = true
      if (row.isolate !== undefined && row.isolate !== null) plugin.isolate = row.isolate
      plugin.config = toPluginRows(row.config, presetDir)
    } else if (row.config !== undefined && row.config !== null) {
      plugin.config = row.config
    }
    if (row.disabled !== undefined) plugin.disabled = resolveDisabled(row.disabled)
    out.push(plugin)
  }
  return out
}

/**
 * @param {{
 *   scope: object,
 *   log?: (m: string) => void,
 *   warn?: (m: string) => void,
 * }} deps
 */
export function createDeclarativeBackend({ scope, log = console.log, warn = console.error }) {
  /** presetId → { dispose, dir } —— 当前挂在注册表里的东西，注销的唯一凭据。 */
  const mounted = new Map()

  async function unmount(id) {
    const entry = mounted.get(id)
    if (entry === undefined) return
    mounted.delete(id)
    try {
      await entry.dispose()
    } catch (error) {
      warn(`custom-mode: 注销 preset "${id}" 时出错（已忽略）: ${describe(error)}`)
    }
  }

  /** Build the definition for one assistant directory, or null when it has no composition. */
  function buildDefinition(assistant) {
    const file = join(assistant.dir, 'agent.cordis.yml')
    if (!existsSync(file)) return null
    const rows = parseComposition(readFileSync(file, 'utf8'))
    const plugins = toPluginRows(rows, assistant.dir)
    if (plugins.length === 0) return null
    const definition = { id: assistant.id, plugins }
    if (assistant.name !== undefined && assistant.name !== '') definition.name = assistant.name
    if (assistant.description !== undefined && assistant.description !== '') definition.description = assistant.description
    if (Number.isFinite(assistant.order)) definition.order = assistant.order
    return definition
  }

  /**
   * (Re)mount one assistant. Idempotent: an already-mounted id is disposed first, because the
   * registry refuses a duplicate id outright.
   */
  async function mountOne(assistant) {
    let definition
    try {
      definition = buildDefinition(assistant)
    } catch (error) {
      // 解析失败是**用户可见**的问题（手改坏了 composition），要显式说，不能静默跳过。
      warn(`custom-mode: 预设 "${assistant.id}" 的 composition 解析失败 —— ${describe(error)}。该助手不会出现在选择器里。`)
      await unmount(assistant.id)
      return { ok: false, error: describe(error) }
    }
    if (definition === null) {
      await unmount(assistant.id)
      return { ok: false, error: 'no composition' }
    }
    await unmount(assistant.id)
    try {
      const dispose = await scope.agentPresets.register(definition)
      mounted.set(assistant.id, { dispose, dir: assistant.dir })
      return { ok: true }
    } catch (error) {
      // register 会抛（Duplicate / id 空 / 已存在）：说清是哪个助手、哪条错误。
      warn(`custom-mode: 注册预设 "${assistant.id}" 失败 —— ${describe(error)}`)
      return { ok: false, error: describe(error) }
    }
  }

  /** Bring the registry in line with the given assistant list: mount all, drop the rest. */
  async function sync(assistants) {
    const results = []
    for (const assistant of assistants) results.push({ id: assistant.id, ...(await mountOne(assistant)) })
    const wanted = new Set(assistants.map((a) => a.id))
    for (const id of [...mounted.keys()]) {
      if (!wanted.has(id)) {
        await unmount(id)
        results.push({ id, ok: true, unmounted: true })
      }
    }
    return results
  }

  async function disposeAll() {
    for (const id of [...mounted.keys()]) await unmount(id)
  }

  /**
   * 删除一个助手：**先注销，再删目录**。
   *
   * 这条线上服务没有 `remove()`（唯一手段是 `register()` 返回的 disposer），所以"删除"必须由本插件
   * 自己完成 —— 而这正是设置页在 0.1.7 上**根本删不掉助手**的原因：宿主那半只认 `agentPresets.remove()`，
   * 拿不到就回一个类型化的 `noRemoveApi`，页面弹完确认框只显示"删除失败"。
   *
   * 先把目录挪到同盘的隐藏名，再注销，最后删掉隐藏名。挪不动就不注销。删目录失败时**如实抛出**（调用方转成
   * `deleteFailed` 并把路径带给用户），而不是报告"已删除"却把目录留在磁盘上 —— 那样下次同步
   * 会把它重新挂回来，用户看到的是"删了又回来了"。
   *
   * @param {string} id - assistant id.
   * @param {string} [dir] - its directory; when omitted, the one remembered at mount time is used.
   */
  async function remove(id, dir) {
    const remembered = mounted.get(id)?.dir
    const target = typeof dir === 'string' && dir !== '' ? dir : remembered
    if (typeof target !== 'string' || target === '') return { ok: false, code: 'noDirectory', id }
    // 先挪走目录再注销。删目录失败时选择器不会把原 id 挂回来；注销失败则把目录移回。
    const trash = join(dirname(target), '.' + basename(target) + '.removing-' + String(process.pid))
    try {
      renameSync(target, trash)
    } catch (error) {
      if (error !== null && typeof error === 'object' && error.code === 'ENOENT') {
        return { ok: false, code: 'noDirectory', id }
      }
      throw error
    }
    try {
      await unmount(id)
    } catch (error) {
      try {
        renameSync(trash, target)
      } catch {
        /* 移回失败时目录留在隐藏名下，下次不会以原 id 复活 */
      }
      throw error
    }
    rmSync(trash, { recursive: true, force: true })
    return { ok: true, id, dir: target }
  }

  /**
   * Fetch the shipped base compositions the host declares, as entry-list YAML.
   *
   * `readDocument()` is the 0.1.7 replacement for the presets directory the composer used to read:
   * it returns the declaration **including** the `isolate` groups and `!!js` predicates that
   * `compositionInventory()` flattens away (measured 2026-09-25 on 0.1.7-rc.2:
   * `readDocument('standard').content` is the same row list, verbatim YAML).
   *
   * Missing API, or a mode that fails to read, is **not** fatal: the host half falls back to the
   * packaged patch files and, if those are missing too, to a typed "unavailable" state in which the
   * prompt stays editable. So this never throws — it reports.
   *
   * @param {Iterable<string>} modeIds - the base modes to fetch.
   * @returns {{ok: boolean, fetched: Map<string,string>, problems: string[]}}
   */
  async function fetchBaseCompositions(modeIds) {
    const fetched = new Map()
    const problems = []
    if (typeof scope.agentPresets?.readDocument !== 'function') {
      return { ok: false, fetched, problems: ['agentPresets.readDocument() 不可用'] }
    }
    for (const id of modeIds) {
      try {
        const document = await scope.agentPresets.readDocument(id)
        const text = document !== null && typeof document === 'object' ? document.content : undefined
        if (typeof text !== 'string' || text.trim() === '') problems.push(`${id}: 声明为空`)
        else fetched.set(id, text)
      } catch (error) {
        // 不为某一条基础模式的成功而重试整轮：读不到的交给降级路径，并指名它。
        problems.push(`${id}: ${describe(error)}`)
      }
    }
    return { ok: problems.length === 0, fetched, problems }
  }

  return {
    id: BACKEND_ID,
    /** 新线不把 preset 落到磁盘，注册表就是唯一真相。 */
    writesPresetFiles: false,
    sync,
    mountOne,
    unmount,
    remove,
    disposeAll,
    fetchBaseCompositions,
    mountedIds: () => [...mounted.keys()],
  }
}

function describe(error) {
  return String((error && error.message) || error)
}
