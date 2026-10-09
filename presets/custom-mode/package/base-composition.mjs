/**
 * Where a base mode's composition text comes from — **the third thing the two dsh lines disagree about**.
 *
 * The settings page composes a mode by editing a *shipped* base composition (base mode + row switches).
 * That text has to come from somewhere, and the two dsh lines keep it in different places:
 *
 * | line | where the shipped composition lives |
 * | --- | --- |
 * | 0.1.2 … 0.1.6 | `@deepseek-ai/dsh-agent-presets/presets/<mode>/agent.cordis.yml` (a package that **no longer exists** from 0.1.7) |
 * | 0.1.7 + | inside the host: `agentPresets.readDocument(<mode>).content` — the declared entry-list YAML |
 *
 * At 1.9.12 only the first route existed, so on 0.1.7 every read of a base composition threw
 * (`无法定位出厂基础模式`) and the settings page answered **500**: no rows, no switches, and — worse —
 * the prompt editor never rendered. That is the P0 an adversarial review reproduced on a stock
 * `0.1.7-rc.2` instance (the exact line the official desktop app bundles).
 *
 * ## Resolution order (highest first)
 *
 *  1. `DSH_SHIPPED_PRESETS_DIR` — explicit override, **exclusive**: when set, nothing else is consulted.
 *     Tests use it, and an operator who points it somewhere gets exactly that.
 *  2. the text the host handed over (`setBaseCompositions`, filled from `agentPresets.readDocument`).
 *  3. `setShippedPresetsDir`, then Node/profile discovery of the legacy presets package.
 *  4. `DSH_PRESET_PATCH_DIR`, then discovery of `@deepseek-ai/dsh-web-app/presets/<mode>.patch.yml`
 *     — the same declaration in its packaged form, for a host whose `readDocument` is unavailable.
 *  5. nothing left → {@link BaseCompositionUnavailableError}, which callers turn into a **visible,
 *     typed** state instead of a 500.
 *
 * One mode is not on this chain at all: {@link UNION_MODE_ID} ships nothing, because it is *synthesised*
 * from the four above (in `composition.mjs`, which owns the splitter). Nothing in this file resolves it.
 *
 * Why `readDocument` and not `compositionInventory()`: the inventory is **flattened**
 * (`entryId`/`moduleName`/`enabled`/`condition`), so the `isolate` realms a service row must sit inside
 * are gone (measured 2026-09-25; `parse-composition.mjs` rules the same route out for the same reason).
 * `readDocument` returns the declaration as YAML **including** those groups and `!!js` expressions.
 */

import { existsSync, readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { dshHome } from './paths.mjs'

/** Explicit override for the legacy file layout (a directory holding `<mode>/agent.cordis.yml`). */
export const LEGACY_DIR_ENV = 'DSH_SHIPPED_PRESETS_DIR'

/** Explicit override for the declarative file layout (a directory holding `<mode>.patch.yml`). */
export const PATCH_DIR_ENV = 'DSH_PRESET_PATCH_DIR'

/**
 * The **shipped** base modes — the four the host declares.
 *
 * Kept here so discovery has no opinion about the UI. Everything that means "ask the host" keys off exactly
 * these four: legacy/patch discovery, the CI fixture materialisation, and the `readDocument(<mode>)` sweep in
 * `index.mjs`. The picker offers one more ({@link UNION_MODE_ID}) and it is deliberately **absent** from this
 * list — there is no shipped file or document behind it, so nothing may go looking for one.
 */
export const BASE_MODE_IDS = ['standard', 'ptc', 'minimal', 'cordis']

/**
 * The fifth base mode a user may build on: all four shipped compositions unioned into one.
 *
 * **The id is `all`, not `custom`.** `custom` is the user preset this feature writes
 * (`$DSH_HOME/.agent-presets/custom/`), so a base mode by that name would make the resolver ask the host for
 * `readDocument('custom')` and hand the preset its own composition back — the self-inclusion the picker has
 * always refused. The id is the machine name; the label is the user's (`base.all.label`), exactly as
 * `cordis` is shown as 「创造模式」.
 *
 * Its text is synthesised in `composition.mjs` from the four shipped ones. Only the *name* lives here: the
 * synthesis needs the composition splitter, and importing that would be a cycle.
 */
export const UNION_MODE_ID = 'all'

/**
 * Thrown when no route yields a base composition.
 *
 * Carries `code` so the HTTP layer can answer with a typed, localizable result, and `attempts` so the
 * operator sees *which* places were tried instead of a bare failure.
 */
export class BaseCompositionUnavailableError extends Error {
  /**
   * @param {string} modeId - the base mode that could not be resolved.
   * @param {string[]} attempts - one line per route tried, in order.
   */
  constructor(modeId, attempts) {
    super(
      '无法取得基础模式「' + modeId + '」的出厂组成。已尝试：' +
        (attempts.length > 0 ? attempts.join('；') : '(无可用途径)'),
    )
    this.name = 'BaseCompositionUnavailableError'
    this.code = 'baseCompositionUnavailable'
    this.modeId = modeId
    this.attempts = attempts
  }
}

/** Whether a value is the typed "no base composition anywhere" failure. */
export function isBaseCompositionUnavailable(error) {
  return error !== null && typeof error === 'object' && error.code === 'baseCompositionUnavailable'
}

/** Read one candidate file, reporting a miss instead of throwing. */
function tryReadFile(file, attempts, label) {
  try {
    const text = readFileSync(file, 'utf8')
    if (typeof text === 'string' && text.trim() !== '') return text
    attempts.push(label + '：文件为空')
  } catch (error) {
    const code = error !== null && typeof error === 'object' ? error.code : undefined
    attempts.push(label + '：' + (code === 'ENOENT' ? '不存在' : String((error && error.message) || error)))
  }
  return null
}

// ── route 1 / 3: the legacy file layout ───────────────────────────────────────

/** Directory injected by the host half (resolved from the roster's system row). */
let injectedLegacyDir

/** Point the resolver at a legacy presets directory discovered at runtime. */
export function setShippedPresetsDir(dir) {
  if (typeof dir === 'string' && dir !== '') injectedLegacyDir = dir
}

/** Discovery result, cached **only when it succeeded** (a cold miss must stay retryable). */
let legacyCache

/** Resolve `@deepseek-ai/dsh-agent-presets` the two ways that ever worked on the legacy line. */
function discoverLegacyPresetsDir() {
  const require = createRequire(import.meta.url)
  try {
    const manifest = require.resolve('@deepseek-ai/dsh-agent-presets/package.json')
    return join(dirname(manifest), 'presets')
  } catch {
    /* fall through */
  }
  try {
    const fromProfile = createRequire(join(dshHome(), 'profiles', 'package.json'))
    const manifest = fromProfile.resolve('@deepseek-ai/dsh-agent-presets/package.json')
    return join(dirname(manifest), 'presets')
  } catch {
    /* fall through */
  }
  throw new Error(
    '无法定位出厂基础模式（@deepseek-ai/dsh-agent-presets 是否已安装？）。' +
      '0.1.7 起该包不再发布，那条线由 agentPresets.readDocument() 提供同一份声明。',
  )
}

/**
 * The legacy presets directory.
 *
 * `DSH_SHIPPED_PRESETS_DIR` is **exclusive**: an explicit override must not silently fall back to
 * whatever else happens to be installed on the machine (that is how a test would stop testing).
 *
 * @returns {string} absolute directory.
 */
export function shippedPresetsDir() {
  const override = process.env[LEGACY_DIR_ENV]
  if (typeof override === 'string' && override !== '') return override
  if (injectedLegacyDir !== undefined) return injectedLegacyDir
  if (legacyCache === undefined) legacyCache = discoverLegacyPresetsDir()
  return legacyCache
}

/** Absolute path of one mode's shipped composition in the legacy layout. */
export function baseCompositionPath(modeId, directory = shippedPresetsDir()) {
  return join(directory, modeId, 'agent.cordis.yml')
}

// ── route 2: the text was handed over by the host (dsh ≥ 0.1.7) ───────────────

/** `id → composition text`, as fetched from `agentPresets.readDocument()`. */
let injectedCompositions

/**
 * Hand over the base compositions the host declared.
 *
 * Called on every registry sync, so a host that gains or changes a preset is picked up without a restart.
 * An empty/failed fetch clears the map rather than keeping a stale one — the caller logs the reason.
 *
 * @param {Map<string, string>|Record<string, string>|null} [compositions] - `id → entry-list YAML`.
 */
export function setBaseCompositions(compositions) {
  if (compositions === null || compositions === undefined) {
    injectedCompositions = undefined
    return
  }
  const map = compositions instanceof Map ? compositions : new Map(Object.entries(compositions))
  const clean = new Map()
  for (const [id, text] of map) {
    if (typeof id === 'string' && typeof text === 'string' && text.trim() !== '') clean.set(id, text)
  }
  injectedCompositions = clean.size > 0 ? clean : undefined
}

/** The host-provided text for one mode, when there is one. */
export function injectedBaseComposition(modeId) {
  return injectedCompositions === undefined ? undefined : injectedCompositions.get(modeId)
}

/** Which modes the host handed over — for the startup log and for tests. */
export function injectedBaseCompositionIds() {
  return injectedCompositions === undefined ? [] : [...injectedCompositions.keys()]
}

// ── route 4: the declarative declaration in its packaged form ─────────────────

/** Directory injected for the patch-file layout. */
let injectedPatchDir

/** Point the resolver at a directory holding `<mode>.patch.yml`. */
export function setPatchPresetsDir(dir) {
  if (typeof dir === 'string' && dir !== '') injectedPatchDir = dir
}

/** Discovery result, cached only on success. */
let patchCache

/** `{ prefix, kind }` candidates for a global npm install of dsh, from this process' own node. */
function executablePrefixes() {
  const out = []
  const dir = dirname(process.execPath)
  // POSIX global install: <prefix>/lib/node_modules/@deepseek-ai/dsh
  out.push(join(dir, '..', 'lib', 'node_modules', '@deepseek-ai', 'dsh'))
  // Windows global install: <prefix>/node_modules/@deepseek-ai/dsh
  out.push(join(dir, 'node_modules', '@deepseek-ai', 'dsh'))
  // A node bundled inside an app (desktop shells): keep the app's own tree reachable.
  out.push(join(dir, '..', 'node_modules', '@deepseek-ai', 'dsh'))
  return out
}

/** `dsh` on PATH → its install root, so a non-standard prefix still resolves. */
function pathDiscoveredDshRoots() {
  const out = []
  const entries = String(process.env.PATH ?? '').split(process.platform === 'win32' ? ';' : ':')
  for (const entry of entries) {
    if (entry === '') continue
    for (const name of process.platform === 'win32' ? ['dsh.cmd', 'dsh.exe', 'dsh'] : ['dsh']) {
      const candidate = join(entry, name)
      if (!existsSync(candidate)) continue
      out.push(join(entry, '..', 'lib', 'node_modules', '@deepseek-ai', 'dsh'))
      out.push(join(entry, '..', 'node_modules', '@deepseek-ai', 'dsh'))
    }
  }
  return out
}

/**
 * Where `@deepseek-ai/dsh-web-app` ships `presets/<mode>.patch.yml`.
 *
 * Every candidate is verified by looking for a shipped base patch, so a wrong guess costs a stat call
 * instead of a wrong answer.
 *
 * @returns {string|undefined} the presets directory, or undefined when nothing matched.
 */
export function discoverPatchPresetsDir() {
  const candidates = []
  // (a) Node resolution from this module: hits when the plugin sits in a tree that can see the host
  //     (a source checkout with the host installed beside it, a flat profile, CI).
  for (const spec of ['@deepseek-ai/dsh-web-app/package.json', '@deepseek-ai/dsh/package.json']) {
    try {
      candidates.push(dirname(createRequire(import.meta.url).resolve(spec)))
    } catch {
      /* fall through */
    }
  }
  // (b) Walk up from this module's own directory — the same shape, for trees Node's resolver skips.
  {
    let here = dirname(fileURLToPath(import.meta.url))
    for (let depth = 0; depth < 8; depth += 1) {
      candidates.push(join(here, 'node_modules', '@deepseek-ai', 'dsh-web-app'))
      candidates.push(join(here, 'node_modules', '@deepseek-ai', 'dsh'))
      here = dirname(here)
    }
  }
  // (c) The host's own module tree, reachable from the DSH home when the profile has real deps.
  try {
    const fromProfile = createRequire(join(dshHome(), 'profiles', 'package.json'))
    candidates.push(dirname(fromProfile.resolve('@deepseek-ai/dsh-web-app/package.json')))
    candidates.push(dirname(fromProfile.resolve('@deepseek-ai/dsh/package.json')))
  } catch {
    /* fall through */
  }
  for (const root of [...executablePrefixes(), ...pathDiscoveredDshRoots()]) {
    candidates.push(join(root, 'node_modules', '@deepseek-ai', 'dsh-web-app'))
    candidates.push(root)
  }
  for (const candidate of candidates) {
    const presets = candidate.endsWith('dsh-web-app') ? join(candidate, 'presets') : join(candidate, 'node_modules', '@deepseek-ai', 'dsh-web-app', 'presets')
    if (BASE_MODE_IDS.some((mode) => existsSync(join(presets, mode + '.patch.yml')))) return presets
  }
  return undefined
}

/**
 * The patch-file presets directory.
 *
 * `DSH_PRESET_PATCH_DIR` is exclusive for the same reason as its legacy twin.
 *
 * @returns {string|undefined} undefined when no patch layout is present (not an error by itself).
 */
export function patchPresetsDir() {
  const override = process.env[PATCH_DIR_ENV]
  if (typeof override === 'string' && override !== '') return override
  if (injectedPatchDir !== undefined) return injectedPatchDir
  if (patchCache === undefined) {
    const found = discoverPatchPresetsDir()
    if (found !== undefined) patchCache = found
    return found
  }
  return patchCache
}

/**
 * Lift the `plugins:` sequence out of a declarative preset patch.
 *
 * The patch (`@deepseek-ai/dsh-web-app/presets/<mode>.patch.yml`) wraps the same row list this feature
 * composes against:
 *
 *     - insert:
 *         - id: preset-standard
 *           name: '@deepseek-ai/dsh-agent-preset'
 *           config:
 *             id: standard
 *             plugins:
 *               - id: persona
 *                 ...
 *
 * Text surgery, not a YAML round-trip — exactly like the composer it feeds: dedenting the block keeps
 * comments, quoting and `!!js` byte-for-byte, and the result is already in the shape
 * `splitSegments()`/`collectRows()` expect (top-level rows at column 0, nested rows at four spaces).
 *
 * @param {string} text - the patch file's contents.
 * @returns {string|null} entry-list YAML, or null when the file carries no `plugins:` block.
 */
export function extractPluginsBlock(text) {
  if (typeof text !== 'string' || text === '') return null
  const lines = text.split('\n')
  const start = lines.findIndex((line) => /^\s*plugins:\s*$/.test(line))
  if (start === -1) return null
  const base = lines[start].search(/\S/)
  const out = []
  for (let index = start + 1; index < lines.length; index += 1) {
    const line = lines[index]
    if (line.trim() === '') {
      out.push('')
      continue
    }
    const indent = line.search(/\S/)
    if (indent <= base) break
    out.push(line.slice(Math.min(base + 2, indent)))
  }
  while (out.length > 0 && out[0].trim() === '') out.shift()
  while (out.length > 0 && out[out.length - 1].trim() === '') out.pop()
  return out.length === 0 ? null : out.join('\n')
}

/** Absolute path of one mode's shipped patch, in the declarative layout. */
export function basePatchPath(modeId, directory = patchPresetsDir()) {
  return directory === undefined ? undefined : join(directory, modeId + '.patch.yml')
}

// ── the chain ────────────────────────────────────────────────────────────────

/**
 * Read one base mode's shipped composition, from whichever route this installation has.
 *
 * @param {string} modeId - one of {@link BASE_MODE_IDS}.
 * @returns {{text: string, source: string}} the composition and where it came from (for the log).
 * @throws {BaseCompositionUnavailableError} when no route yields text.
 */
export function readBaseCompositionText(modeId) {
  const attempts = []

  // 1. explicit legacy override: tried first, and it **suppresses discovery** (the point of an explicit
  //    override is that the answer cannot wander to whatever else is installed). It does *not* suppress
  //    step 2: a host that declares the composition knows better than a stale path in the environment.
  const legacyOverride = process.env[LEGACY_DIR_ENV]
  const legacyExplicit = typeof legacyOverride === 'string' && legacyOverride !== ''
  if (legacyExplicit) {
    const file = join(legacyOverride, modeId, 'agent.cordis.yml')
    const text = tryReadFile(file, attempts, LEGACY_DIR_ENV + '=' + legacyOverride)
    if (text !== null) return { text, source: LEGACY_DIR_ENV }
  }

  // 2. text handed over by the host (dsh ≥ 0.1.7): the authoritative answer on that line.
  const injected = injectedBaseComposition(modeId)
  if (injected !== undefined) return { text: injected, source: 'agentPresets.readDocument()' }

  const patchOverride = process.env[PATCH_DIR_ENV]
  const patchExplicit = typeof patchOverride === 'string' && patchOverride !== ''

  if (legacyExplicit === false) {
    // 3. injected legacy dir, then discovery
    if (injectedLegacyDir !== undefined) {
      const text = tryReadFile(join(injectedLegacyDir, modeId, 'agent.cordis.yml'), attempts, 'setShippedPresetsDir()')
      if (text !== null) return { text, source: 'agentPresets.list() → presets 目录' }
    } else {
      try {
        const dir = discoverLegacyPresetsDir()
        const text = tryReadFile(join(dir, modeId, 'agent.cordis.yml'), attempts, '@deepseek-ai/dsh-agent-presets')
        if (text !== null) return { text, source: '@deepseek-ai/dsh-agent-presets' }
      } catch (error) {
        attempts.push('@deepseek-ai/dsh-agent-presets：' + String((error && error.message) || error))
      }
    }
  }

  // 4. the declarative declaration in its packaged form
  const patchDir = patchExplicit ? patchOverride : patchPresetsDir()
  if (patchDir !== undefined) {
    const file = join(patchDir, modeId + '.patch.yml')
    const raw = tryReadFile(file, attempts, PATCH_DIR_ENV + '/发现 dsh-web-app')
    if (raw !== null) {
      const text = extractPluginsBlock(raw)
      if (text !== null) return { text, source: 'dsh-web-app/presets/' + modeId + '.patch.yml' }
      attempts.push(file + '：没有 plugins: 区块')
    }
  } else {
    attempts.push('dsh-web-app/presets：未找到（可用 ' + PATCH_DIR_ENV + ' 指定）')
  }

  throw new BaseCompositionUnavailableError(modeId, attempts)
}

/** The same chain, one-line description of the outcome — for startup logs. */
export function describeBaseCompositionSource(modeId) {
  try {
    return readBaseCompositionText(modeId).source
  } catch (error) {
    return '不可用（' + String((error && error.message) || error) + '）'
  }
}

/** Exported for tests: forget every cached discovery result. */
export function resetBaseCompositionCachesForTests() {
  legacyCache = undefined
  patchCache = undefined
  injectedLegacyDir = undefined
  injectedPatchDir = undefined
  injectedCompositions = undefined
}
