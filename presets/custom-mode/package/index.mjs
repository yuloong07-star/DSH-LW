/**
 * Host half: the 「自定义模式」 settings page — an ASSISTANT MANAGER.
 *
 * It registers seven exact routes on the platform's shared `/api` channel; the browser half calls
 * them with `fetch`:
 *
 *   GET  /api/custom-mode              — every assistant this feature manages.
 *   GET  /api/custom-mode/state?id=…   — one assistant: base mode, rows, switches, prompt.
 *   GET  /api/custom-mode/history?id=…&n=… — one prompt revision (metadata comes with the state).
 *   POST /api/custom-mode/state        — { id, mode, overrides, prompt, name, description }:
 *                                        validate, render a fresh `agent.cordis.yml`, write both files.
 *   POST /api/custom-mode/create       — { name, description, posture?, locale? }: seed a new
 *                                        assistant. Omitting `posture` (or sending `develop`) is the
 *                                        historical path: the packaged prompt and the standard base,
 *                                        with shipped row states. Other postures still use an official
 *                                        base; they only change the starter prompt and the initial switches.
 *   POST /api/custom-mode/delete       — { id }: remove a locally authored assistant.
 *   POST /api/custom-mode/reorder      — { id, direction }: move one assistant up/down in the
 *                                        picker order by writing `order` into each `preset.yml`.
 *   POST /api/custom-mode/repair       — { id }: turn off the rows this dsh line cannot resolve.
 *
 * **Why `/api` and not the raw `webServer` table**: the carrier that owns the `/api` channel applies the
 * platform's trust and authentication policy — loopback/`trustedHosts` Host check, `Sec-Fetch-Site`,
 * `Origin`, and the signed browser-session cookie — *before* dispatching to a route. Registering there
 * makes the fence part of the structure: a route cannot exist without it. Registering on the raw table
 * instead puts the route outside that policy and leaves "check the request first" as a rule a human has
 * to remember — and an earlier version of this plugin, doing exactly that, let an unauthenticated GET
 * read the whole system prompt and an unauthenticated cross-site POST rewrite `prompt.md` (every official
 * route answered 401; a plain form post needs no preflight, so CORS would not have helped either). The
 * registered paths are absolute *including* `/api`, which is what the platform's own packages pass.
 *
 * **Why exact routes rather than one prefix**: the Fetch registry matches exact paths. Seven registrations
 * cost nothing and each declares the methods it owns, so the method table doubles as the guarantee that a
 * prefetched `GET …/create` cannot create anything — that method is simply not registered for that path.
 *
 * Why a private route instead of a Remote namespace or `dsh-settings`: this plugin then owns no Cordis
 * service name and cannot collide with anything, and it stays independent of the settings API whose helper
 * names differ between dsh releases (see docs/ARCHITECTURE.md §5).
 *
 * Storage is deliberately file-only and stateless: the composition file IS the
 * saved state, so no second document can drift from it. The page derives which
 * rows the user changed by diffing against the same shipped base mode.
 */

import { restoreBackups, writeAtomic, writeAtomicPair } from './atomic.mjs'
import {
  BACKEND_DECLARATIVE,
  BACKEND_UNUSABLE,
  createDeclarativeBackend,
  describeBackend,
  detectPresetBackend,
  effectiveRosterRows,
} from './preset-backend/index.mjs'
import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { PROMPT_PATH, COMPOSITION_PATH, ROUTE_PATH, PRESET_DIR } from './paths.mjs'
import {
  allowlistOverrides,
  applyRowExclusivity,
  BASE_MODE_IDS,
  BASE_MODES,
  collectRows,
  disableRowsInPlace,
  exclusiveSetsActive,
  hasFailedPredicate,
  isBaseCompositionUnavailable,
  modeOf,
  overridesOf,
  readBaseComposition,
  setKnownModuleNames,
  renderComposition,
  setBaseCompositions,
  setShippedPresetsDir,
  unresolvableRows,
} from './composition.mjs'
import { presetMetaText, readPresetMeta, writePresetMeta, presetMetaPath, PRESET_META_PATH } from './meta.mjs'
import { listHistory, readVersion, recordExternalChange, recordPrompt, HISTORY_SOURCE } from './journal.mjs'
import { packagedPresetDir, starterComposition } from './seed.mjs'
import { postureById, postureLocale, posturePrompt, publicPostures } from './postures.mjs'
import {
  allocateId,
  assistantDir,
  assistantsFromRoster,
  COMPOSITION_FILE,
  createAssistantDir,
  reorderAssistant,
  seedOnActivation,
  LEGACY_ID,
  userPresetRoot,
} from './assistants.mjs'

export { PROMPT_PATH, COMPOSITION_PATH, ROUTE_PATH, API_PREFIX, PRESET_DIR, PRESET_META_PATH }

/** The list endpoint is the route path itself; the rest hang off it. */
const STATE_PATH = ROUTE_PATH + '/state'
const CREATE_PATH = ROUTE_PATH + '/create'
const DELETE_PATH = ROUTE_PATH + '/delete'
const REORDER_PATH = ROUTE_PATH + '/reorder'
const REPAIR_PATH = ROUTE_PATH + '/repair'
const HISTORY_PATH = ROUTE_PATH + '/history'

/** Which verbs each endpoint answers. `undefined` for a path means 404. */
const METHODS = {
  [ROUTE_PATH]: ['GET'],
  [STATE_PATH]: ['GET', 'POST'],
  [HISTORY_PATH]: ['GET'],
  [CREATE_PATH]: ['POST'],
  [DELETE_PATH]: ['POST'],
  [REORDER_PATH]: ['POST'],
  [REPAIR_PATH]: ['POST'],
}

/** 只告警一次：避免每个请求都刷同一行日志。 */
let warnedRosterShape = false

/** 应用层请求体上限；平台的 buffered cap 是第一道，这个是我们自己的兜底（见 handler 里的注释）。 */
const MAX_BODY_BYTES = 4 * 1024 * 1024

/**
 * 本插件的版本。
 *
 * 页面把它显示出来，是因为**用户很难自己判断装到的是哪一版**：pnpm 的发布冷却期（默认 24 小时）会让
 * 不钉版本的安装落到"超过 24 小时的最新版"，实测在一台干净机器上 `dsh plugin add dsh-custom-mode`
 * 装到的是 1.0.1 而不是最新的 1.9.x。看见版本号，用户才知道要不要按 README 的钉版本命令重装。
 */
const PLUGIN_VERSION = (() => {
  try {
    return JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')).version
  } catch {
    return 'unknown'
  }
})()

/** Longest display name / description the page accepts, so one paste cannot bloat every picker. */
const MAX_NAME = 80
const MAX_DESCRIPTION = 400

/**
 * Variable names the prompt renderer accepts, mirroring
 * `VARIABLE_NAME = /^[a-z][a-z0-9_]*$/` in `@deepseek-ai/dsh-system-prompt`.
 */
const VARIABLE_NAME = /^[a-z][a-z0-9_]*$/

/**
 * Variables this deployment registers for every agent (`dsh-agent-loop`).
 *
 * The persona section renders with strict interpolation: an unknown `{{name}}`
 * or a malformed group makes the renderer THROW, failing every model request in
 * this mode. Rejecting it at the write is what keeps a typo from bricking it.
 *
 * Mirrors `checkPromptText` in the preset's `prompt-tool.mjs`; the duplication is
 * deliberate so neither side depends on the other's install location.
 */
const KNOWN_VARIABLES = ['model', 'cwd', 'provider']

/**
 * Check prompt text against the renderer's interpolation rules.
 *
 * A complete `{{...}}` group must hold a valid, registered name; a lone `{{`
 * with no later `}}` is literal prose and passes.
 */
export function checkPromptText(text) {
  let index = 0
  for (;;) {
    const open = text.indexOf('{{', index)
    if (open === -1) return { ok: true }
    const close = text.indexOf('}}', open + 2)
    if (close === -1) return { ok: true }
    const variable = text.slice(open + 2, close)
    if (!VARIABLE_NAME.test(variable)) {
      return {
        ok: false,
        code: 'badVariableName',
        params: { variable: '{{' + variable + '}}' },
        error:
          '保存被拒绝：{{' +
          variable +
          '}} 不是合法的变量引用（合法名只能用小写字母、数字、下划线且以字母开头）。' +
          '若只想要字面量花括号，请用单个 { 或不闭合的 {{。',
      }
    }
    if (!KNOWN_VARIABLES.includes(variable)) {
      return {
        ok: false,
        code: 'unknownVariable',
        params: { variable: '{{' + variable + '}}', known: KNOWN_VARIABLES.map((item) => '{{' + item + '}}').join(', ') },
        error:
          '保存被拒绝：{{' +
          variable +
          '}} 不是已注册的变量，渲染时会报错并让本模式每个请求都失败。可用：' +
          KNOWN_VARIABLES.map((item) => '{{' + item + '}}').join('、') +
          '。',
      }
    }
    index = close + 2
  }
}

/** Absolute path of one preset directory's editable prompt file. */
export function promptFile(directory) {
  return join(directory, 'prompt.md')
}

/**
 * The prompt this assistant was created with.
 *
 * Kept beside `prompt.md` and never updated by a later save, so "restore the
 * starter" means that text on Windows and Linux alike. Assistants created
 * before this file existed fall back to the packaged coding prompt, which is
 * what they were seeded with.
 */
export const STARTER_PROMPT_FILE = 'prompt.starter.md'

/** @param {string} directory */
export function starterPromptFile(directory) {
  return join(directory, STARTER_PROMPT_FILE)
}

/**
 * @param {string} directory
 * @returns {string | null}
 */
function readStarterPrompt(directory) {
  try {
    return readFileSync(starterPromptFile(directory), 'utf8')
  } catch {
    return null
  }
}

/** Absolute path of one preset directory's composition file. */
export function compositionFile(directory) {
  return join(directory, COMPOSITION_FILE)
}

/** Read one assistant's prompt file, or report a typed failure the page can show. */
export function readPrompt(directory) {
  const path = promptFile(directory)
  try {
    return { ok: true, path, text: readFileSync(path, 'utf8') }
  } catch (error) {
    return { ok: false, code: 'promptReadFailed', params: { detail: describe(error) }, error: '读取提示词失败：' + describe(error) }
  }
}

/** The isolation message for an id the roster does not describe as a managed assistant. */
function unknownAssistant(id) {
  // 带 `code`：两个标签页同时开着、在 B 里删掉这个助手、回到 A 点保存时走的就是这条 ——
  // 没有 code 时英文界面会显示下面这串中文，而词典里的 `api.unknownAssistant` 反而永远用不上。
  return {
    ok: false,
    code: 'unknownAssistant',
    params: { id: String(id) },
    error:
      '找不到助手「' +
      String(id) +
      '」。设置页只管理本工具创建的模式（目录里有 prompt.md，且组成文件用 prompt-reader.mjs 注入身份）；' +
      '手工编写的其它模式不会被改动。',
  }
}

/**
 * Everything the settings page renders for ONE assistant.
 *
 * `mode` and `overrides` are derived from the composition file rather than
 * stored separately, so the file stays the single source of truth.
 *
 * @param {Array<object>} rows - the current roster.
 * @param {string} id - the assistant's preset id.
 * @returns {object} the payload the browser half reads.
 */
/**
 * 出厂提示词：新建助手时拿到的那一份（打包在包里的 preset 模板）。
 *
 * 设置页用它实现「恢复出厂提示词」—— 在此之前，用户改坏了提示词只能把整个助手删掉重建。
 * 读不到就返回 null（页面会把那个按钮置灰，而不是给一个会写坏文件的按钮）。
 */
let packagedPromptCache
function packagedPrompt() {
  if (packagedPromptCache === undefined) {
    try {
      packagedPromptCache = readFileSync(join(packagedPresetDir(), 'prompt.md'), 'utf8')
    } catch (error) {
      packagedPromptCache = null
      console.error('custom-mode: 读不到出厂提示词模板（' + describe(error) + '），设置页将不提供「恢复出厂提示词」。')
    }
  }
  return packagedPromptCache
}

/**
 * 「配置了，但不生效」的告警码。
 *
 * 这一条借自同生态的 whale-persona：它会主动点名"配了却没起作用"的项。这里对应四种**静默**的
 * 自相矛盾 —— 每一种此前都只会让人以为"我明明写了提示词/描述，怎么没效果"：
 *
 *   - `personaOffWithPrompt`：身份行被关掉，`prompt.md` 根本不会被注入（最坑的一种）；
 *   - `toolOff`：`custom_prompt` 工具行关掉 → 会话内改提示词不可用（设置页照旧）；
 *   - `noDescription`：描述为空 → 新建会话的模式选择器里显示「暂无描述」；
 *   - `noName`：名字为空 → 选择器里显示成裸目录 id。
 *
 * 只返回**码**，文案由页面按语言渲染（双语文案的真值源在 locales.mjs 一处）。
 *
 * @param {string} text - the composition text.
 * @param {string} prompt - the prompt file's content.
 * @param {{name?: string, description?: string}} meta - `preset.yml`.
 * @returns {string[]} warning codes, stable order.
 */
export function configWarnings(text, prompt, meta) {
  const warnings = []
  const rows = collectRows(text)
  const find = (id) => rows.find((row) => row.id === id)
  if (find('persona')?.disabled === true && typeof prompt === 'string' && prompt.trim() !== '') {
    warnings.push('personaOffWithPrompt')
  }
  if (find('custom-prompt-tool')?.disabled === true) warnings.push('toolOff')
  // **不报「没有描述」**：1.11.2 起模板故意不再播种描述（它是产品数据，壳没法按界面语言本地化 ——
  // 写一种语言在另一种界面里就是外语，两种都写就成了"中英拼接"）。默认留空却弹一条 ⚠ 是自相矛盾的，
  // 而且描述框的占位符本来就写着「可留空」。填不填是用户的自由，不该被一直念。
  if (typeof meta?.name !== 'string' || meta.name.trim() === '') warnings.push('noName')
  // 两条互斥的路同时开着 ⇒ 平台会把整个模式判 broken 并**从所有选择器里静默丢弃**。
  // 保存路径已经会自动避让（见 applyRowExclusivity），所以走到这里只可能是手工编辑、直接调 HTTP API，
  // 或者上一次是别的工具写的 —— 三种都该被点名（"配置了却不生效"是本页明面上的承诺）。
  if (exclusiveSetsActive(text).length > 0) warnings.push('exclusiveRowsActive')
  const seenIds = new Set()
  let duplicate = false
  const walk = (list) => {
    for (const row of list) {
      if (seenIds.has(row.id)) duplicate = true
      else seenIds.add(row.id)
      if (Array.isArray(row.children) && row.children.length > 0) walk(row.children)
    }
  }
  walk(rows)
  if (duplicate) warnings.push('duplicateRowIds')
  if (hasFailedPredicate(text)) warnings.push('predicateFailed')
  if (typeof prompt === 'string' && prompt.trim() !== '' && checkPromptText(prompt).ok !== true) {
    warnings.push('badPromptVariable')
  }
  return warnings
}

/**
 * 从宿主给的那条 `broken` 里抠出**行 id**。
 *
 * 平台把"这个预设为什么起不来"写成一行行 `<rowId> (<module>): <reason>`，例如
 *
 *     workflow-worker-thread (@deepseek-ai/dsh-workflow-worker-thread): never started
 *     tool-workflow (@deepseek-ai/dsh-tool-workflow): waiting for workflowEngine
 *
 * 这是**唯一**能知道"是哪一行害的"的来源：`unresolvableRows()` 只答"模块装没装"，答不了
 * "上游服务等不到"。实测（2026-10-01，官方桌面端 0.2.0-rc.2）：关掉那个装不上的引擎行之后
 * `unresolvable` 变空、页面**全绿**，而预设**仍然 broken**（依赖引擎的两个工具在等它）——
 * 于是用户再也走不到可用状态，模式一直不出现。见 docs/MEASUREMENTS.md §34。
 *
 * @param {unknown} message - the roster row's `broken` string.
 * @returns {string[]} row ids, de-duplicated, in the order they appear.
 */
export function brokenRowIds(message) {
  if (typeof message !== 'string') return []
  const ids = []
  for (const line of message.split('\n')) {
    const match = /^\s*([A-Za-z0-9][A-Za-z0-9_-]*)\s+\(/.exec(line)
    if (match !== null) ids.push(match[1])
  }
  return [...new Set(ids)]
}

export function readState(rows, id, options = {}) {
  const directory = assistantDir(rows, id)
  if (directory === undefined) return unknownAssistant(id)
  restoreBackups(directory)
  const composition = compositionFile(directory)
  if (!existsSync(composition)) return { ok: false, code: 'compositionMissing', params: { path: composition }, error: '找不到组成文件：' + composition }
  const text = readFileSync(composition, 'utf8')
  const mode = modeOf(text)
  const prompt = readPrompt(directory)
  const meta = readPresetMeta(directory)
  // 页面要打开时顺便对账：磁盘上的文本若与日志末条不同，说明它在设置页之外被改过
  // （会话内的 custom_prompt 工具、手工编辑、别处同步）—— 补记一条 external，于是"被改过"看得见。
  //
  // **必须包起来**：这是一次"写透"（recordPrompt → writeAtomic），而日志路径本身可能是坏的
  // （被换成目录、被 chmod 000、一次 sudo 跑 dsh 留下的 root 属主）。1.9.7 给 readEntries 加的
  // 守卫只覆盖了**读**那一半，于是同一个损坏场景会在这里重新把整个 GET state 打成 500 ——
  // 页面打不开这个助手，用户既看不到也改不了自己的提示词（外部实测：EISDIR on rename）。
  // 留痕失败降级为"这一版没记上"，与 saveState 里已经做过的处置一致。
  if (prompt.ok === true) {
    try {
      recordExternalChange(directory, prompt.text)
    } catch {
      /* 审计留痕失败不影响读取 */
    }
  }
  // 本机这条线给不给得出「出厂组成」？给不出来时**降级**而不是把整页打成 500：
  // 提示词与名字照常可读可写，只有基础模式与插件开关不可编辑（0.1.7 上曾经整页不可用）。
  let rowTree = []
  let overrides = {}
  let baseUnavailable = null
  try {
    rowTree = collectRows(text)
    overrides = overridesOf(text, mode)
  } catch (error) {
    if (!isBaseCompositionUnavailable(error)) throw error
    baseUnavailable = { code: error.code, params: { mode, detail: describe(error) } }
  }
  // 宿主给的那条 broken（若这条线上有）。**必须在 return 之前算**：warnings 要用它。
  const selfRow = Array.isArray(rows) ? rows.find((item) => item !== null && typeof item === 'object' && item.id === id) : undefined
  const hostBroken = typeof selfRow?.broken === 'string' ? selfRow.broken.trim() : ''
  const unresolvable = unresolvableRows(text)
  const knownUnresolvable = new Set(unresolvable.map((row) => row.id))
  const hostBrokenIds = brokenRowIds(hostBroken)
  // 宿主说坏了，而我们**解释不了**（不是"模块装不上"）—— 那就要单独点名，否则页面全绿、
  // 模式却在选择器里不出现。抠不出行 id 时（宿主换了措辞）也照样点名：宁可笼统，不可沉默。
  const unexplainedBroken = hostBroken !== '' && (hostBrokenIds.length === 0 || hostBrokenIds.some((rowId) => knownUnresolvable.has(rowId) === false))
  return {
    ok: true,
    id,
    mode,
    modes: BASE_MODES,
    rows: rowTree,
    overrides,
    // 页面据此把「基础模式 / 插件开关」两块显示成不可用，并把原因说成人话。
    ...baseUnavailable === null ? {} : { baseUnavailable },
    prompt: prompt.ok === true ? prompt.text : '',
    ...prompt.ok === true ? {} : { promptError: prompt.error },
    name: meta.name,
    description: meta.description,
    promptPath: promptFile(directory),
    compositionPath: composition,
    presetMetaPath: presetMetaPath(directory),
    // 回退用的出厂文本。它不是"当前值"，页面只把它填进编辑器，保存前不落盘 —— 所以
    // 一次误点不会破坏任何东西（重新读取即可丢弃）。
    // 回退用的起步文本。优先是这个助手新建时写下的 prompt.starter.md；
    // 没有该文件的旧助手用打包的开发提示词，那就是它们当初得到的文本。
    factoryPrompt: readStarterPrompt(directory) ?? (typeof options.factoryPrompt === 'string' ? options.factoryPrompt : null),
    // 改动历史（只有元数据，正文按需取：见 GET /custom-mode/history）。
    history: listHistory(directory),
    // 本插件版本 + 本机装的那条 dsh 线上无法解析的行（页面据此显示版本与"按本线修复"）。
    version: PLUGIN_VERSION,
    unresolvable,
    // 宿主自己给的「这个预设为什么起不来」原文。它比 `unresolvable` 宽：除了"模块装不上"，
    // 还包括"上游服务等不到"这类**装配期**失败。页面据此点名，而不是在坏着的时候显示全绿。
    broken: hostBroken === '' ? null : hostBroken,
    // 「配置了却不生效」的告警码（文案在页面侧按语言渲染）。
    warnings: [
      ...configWarnings(text, prompt.ok === true ? prompt.text : '', meta),
      // 审批闸门缺失：由预置侧在注册失败时留下标记文件（宿主半看不到那个事件是否真的有人监听）。
      // 诚实地把它变成页面上的告警，而不是只留在 console.error 里。
      ...(existsSync(join(directory, 'approval-gate-missing')) ? ['approvalGateMissing'] : []),
      // 本线无法解析的启用行 = 平台会把整个预设判为 broken 并从选择器里丢掉（曾经的 P0）。
      ...(unresolvable.length > 0 ? ['unresolvableRows'] : []),
      // ★ 宿主报了 broken，而**我们自己的检查解释不了**（不是"模块装不上"，而是"服务等不到"之类）。
      //   不点名的话，页面上 `unresolvable` 空、告警空 —— 一切看起来正常，而模式**根本没出现在
      //   选择器里**。这正是本仓记录过两次的那种"静默失败"，只是换了个触发面。
      ...(unexplainedBroken ? ['presetBroken'] : []),
      // 出厂组成取不到：提示词能改，基础模式与插件开关不能。
      ...(baseUnavailable === null ? [] : ['baseCompositionUnavailable']),
    ],
  }
}

/**
 * The list payload: every managed assistant, plus the root they live in.
 *
 * @param {Array<object>} rows - the current roster.
 */
export function readList(rows) {
  // `brokenRows` 是给**页面**用的：只说「这个模式有问题」既看不出严重程度、也看不出修完会怎样。
  // 宿主那条 `broken` 里点名了是哪几行，抠出来交给页面，标签就能写成「N 行在本机不可用」。
  const list = assistantsFromRoster(rows).map((item) =>
    typeof item.broken === 'string' ? { ...item, brokenRows: brokenRowIds(item.broken) } : item,
  )
  return { ok: true, assistants: list, postures: publicPostures(), root: userPresetRoot(rows) }
}

/**
 * Apply one save: validate the prompt, render the composition, write both files.
 *
 * The render always carries a fresh timestamp, and `agent-presets` re-mounts a
 * preset when the composition file's `mtimeMs`/`size` differ — so the new
 * configuration reaches the next session without a process restart.
 *
 * @param {Array<object>} rows - the current roster.
 * @param {object} input - `{ id, mode, overrides, prompt, name, description }`.
 */
/**
 * 进程内按助手串行化写入。
 *
 * 实测（外部审阅，Windows）：即使有 rename 重试，同一助手 10 个并发保存里仍有 1 次 EPERM —— 因为两个
 * 处理器在**同一时刻**准备并改名同一对文件。重试覆盖的是跨进程窗口；这里消掉的是本进程内的竞争，也就是
 * 我们能真正保证的那一半。（两个实例共用同一个 DSH_HOME 的跨进程竞争，插件无法串行化，那里靠重试。）
 */
const writeChains = new Map()

function serializedWrite(key, work) {
  const previous = writeChains.get(key) ?? Promise.resolve()
  const next = previous.then(work, work)
  // 链本身必须永远处于 fulfilled 状态，否则一次失败会卡死后续所有写入。
  writeChains.set(key, next.then(() => undefined, () => undefined))
  return next
}

/**
 * 降级保存：只写提示词与元数据，组成文件一字不动。
 *
 * 什么时候会走到这里：本机这条 dsh 线既没有旧线的 presets 目录，也没有可用的
 * `readDocument()`（见 base-composition.mjs 的解析链）。此时「基础模式 / 插件开关」在页面上已经
 * 不可编辑，而用户真正想保存的那段提示词没有任何理由跟着一起失败 —— 组成文件不动，模式在平台上
 * 的行集合也就不变，这是所有选项里破坏性最小的一个。
 *
 * @param {string} directory - the assistant's preset directory.
 * @param {{id: string, mode: string, prompt: string, name: string, description: string, displayName?: string}} input
 *   - the validated save; `name` decides whether `preset.yml` is written, `displayName` is what the response
 *   reports (the disk name when the request omitted one — issue #9).
 * @returns {object} the shape `saveState` returns, with `code: 'savedPromptOnly'`.
 */
export function savePromptOnly(directory, { id, mode, prompt, name, description, displayName }) {
  // 与正常保存同一条纪律：提示词与 preset.yml 一起换名，失败则两个都不动。
  let metaText = null
  if (name !== '') {
    const rendered = presetMetaText(name, description, directory)
    if (rendered.ok !== true) return rendered
    metaText = rendered.text
  }
  try {
    writeAtomicPair([
      [promptFile(directory), prompt],
      ...metaText === null ? [] : [[presetMetaPath(directory), metaText]],
    ])
  } catch (error) {
    return { ok: false, code: 'writeFailed', params: { detail: describe(error) }, error: '写入失败：' + describe(error) }
  }
  try {
    recordPrompt(directory, prompt, HISTORY_SOURCE.settings)
  } catch {
    /* 审计记录失败不影响保存本身 */
  }
  return {
    ok: true,
    id,
    mode,
    code: 'savedPromptOnly',
    // 响应里的名字用**磁盘上的那个**（issue #9）：请求省略 name 时不该把 params.name 报成裸目录 id。
    params: { name: (displayName === undefined || displayName === '' ? name : displayName) === '' ? id : (displayName === undefined || displayName === '' ? name : displayName), mode },
    note: '已保存系统提示词（本机取不到基础模式的出厂组成，插件开关与基础模式未改动）。新建会话即生效。',
  }
}

export function saveState(rows, input) {
  const id = input !== null && typeof input === 'object' && typeof input.id === 'string' ? input.id : ''
  const directory = assistantDir(rows, id)
  if (directory === undefined) return unknownAssistant(id)

  const mode = input !== null && typeof input === 'object' && typeof input.mode === 'string' ? input.mode : ''
  if (!BASE_MODES.some((entry) => entry.id === mode)) {
    return { ok: false, code: 'badMode', params: { mode }, error: '未知的基础模式：' + mode }
  }
  const prompt = input !== null && typeof input === 'object' && typeof input.prompt === 'string' ? input.prompt : ''
  if (prompt.trim() === '') {
    return { ok: false, code: 'promptEmpty', error: '保存被拒绝：系统提示词为空。留空不会清空身份，读取器会沿用上一版。' }
  }
  const verdict = checkPromptText(prompt)
  if (verdict.ok !== true) {
    // 带上 code/params：英文界面由页面自己的词典渲染；中文串保留给直接调 HTTP API 的调用方。
    return { ok: false, code: verdict.code, params: verdict.params, error: verdict.error }
  }

  const rawName = input !== null && typeof input === 'object' && typeof input.name === 'string' ? input.name : ''
  const name = rawName.replace(/\r?\n/g, ' ').trim()
  if (name.length > MAX_NAME) {
    return { ok: false, code: 'nameTooLong', params: { max: MAX_NAME }, error: '保存被拒绝：模式名称过长（上限 ' + String(MAX_NAME) + ' 个字符）。' }
  }
  // 请求里**没带** description 时保留原值：API 调用方只改提示词，不该顺手把描述清空（外部评审实测）。
  const meta = readPresetMeta(directory)
  // ★ 请求里没带 name（或页面清空）时，渲染组成仍要用**磁盘上那个名字**（issue #9）。
  //   组成里 custom-prompt-tool 行的 config.modeName 是"会话内工具知道自己在改哪个助手"的唯一凭据；
  //   用空串渲染会把它整块删掉，而 preset.yml 又（有意）保留旧名字 —— 两处就此不一致，
  //   同时成功响应还会把 params.name 报成裸目录 id。请求**没带** name 与"用户把名字清空"在这里
  //   是同一个语义：保持现状，而不是把它抹掉。
  const effectiveName = name !== '' ? name : typeof meta.name === 'string' ? meta.name : ''
  const rawDescription =
    input !== null && typeof input === 'object' && typeof input.description === 'string'
      ? input.description
      : (typeof meta.description === 'string' ? meta.description : '')
  const description = rawDescription.replace(/\r?\n/g, ' ').trim()
  if (description.length > MAX_DESCRIPTION) {
    return { ok: false, code: 'descriptionTooLong', params: { max: MAX_DESCRIPTION }, error: '保存被拒绝：模式描述过长（上限 ' + String(MAX_DESCRIPTION) + ' 个字符）。' }
  }

  const overrides = new Map()
  const raw = input !== null && typeof input === 'object' ? input.overrides : undefined
  if (raw !== null && typeof raw === 'object') {
    for (const [rowId, value] of Object.entries(raw)) {
      if (typeof value === 'boolean') overrides.set(rowId, value)
    }
  }

  // ★ 自动互斥（见 applyRowExclusivity 的实测依据）：两套壳注册同名工具，同时启用会让平台把整个模式
  //   判 broken，并**从所有选择器里静默丢掉** —— 设置页却全绿。开关自己动一下用户能接受，模式凭空
  //   消失不能，所以这里替他做掉那一步，并在响应里如实说出被关掉的是哪几行。
  //   取不到出厂组成时会抛：交给下面同一条降级路径，不额外编一套错误。
  let autoOff = []
  try {
    const adjusted = applyRowExclusivity(mode, overrides)
    autoOff = adjusted.moved
    overrides.clear()
    for (const [rowId, enabled] of adjusted.overrides) overrides.set(rowId, enabled)
  } catch (error) {
    if (!isBaseCompositionUnavailable(error)) throw error
  }

  let composition
  try {
    composition = renderComposition(mode, overrides, { modeName: effectiveName, assistantId: id })
  } catch (error) {
    // 拿不到出厂组成：这一页**降级**但不能全废 —— 提示词仍可单独保存（组成文件原样不动）。
    // 页面在降级态下把基础模式与开关都设为不可编辑，所以开关集合必为空；HTTP API 直接调用
    // 还想改开关时明确拒绝，而不是悄悄丢掉用户的意图。
    if (isBaseCompositionUnavailable(error)) {
      if (overrides.size === 0 && existsSync(compositionFile(directory))) {
        return savePromptOnly(directory, { id, mode, prompt, name, description, displayName: effectiveName })
      }
      return {
        ok: false,
        code: error.code,
        params: { mode, detail: describe(error) },
        error:
          '本机取不到基础模式「' + mode + '」的出厂组成，无法改动插件开关或基础模式（提示词可以单独保存）。',
      }
    }
    return { ok: false, code: 'renderFailed', params: { detail: describe(error) }, error: '生成组成文件失败：' + describe(error) }
  }

  // Self-check our own output before publishing it: a composition that lost its
  // rows would break the mode on its next mount, and the cause would be opaque.
  try {
    if (collectRows(composition).length === 0) throw new Error('生成的组成文件没有任何行')
    readBaseComposition(mode)
  } catch (error) {
    return { ok: false, code: 'selfCheckFailed', params: { detail: describe(error) }, error: '生成结果自检失败，已放弃写入：' + describe(error) }
  }

  // 三个文件必须一起换名（issue #5）：组成、提示词、preset.yml。此前 preset.yml 是**单独**写的，
  // 它失败时磁盘上已经是"新提示词 + 旧名字"，而页面报"保存失败"—— 页面又不会重读，于是它永远停在
  // 旧草稿上，用户以为没保存成功。现在三份先各自写进临时文件，再一起 rename；准备阶段失败则一个都不动。
  // 名字为空时**不动** preset.yml：空的 name 标量会让这个模式在所有选择器里退化成裸目录 id。
  let metaText = null
  if (name !== '') {
    const rendered = presetMetaText(name, description, directory)
    if (rendered.ok !== true) return rendered
    metaText = rendered.text
  }
  try {
    writeAtomicPair([
      [compositionFile(directory), composition],
      [promptFile(directory), prompt],
      ...metaText === null ? [] : [[presetMetaPath(directory), metaText]],
    ])
  } catch (error) {
    return { ok: false, code: 'writeFailed', params: { detail: describe(error) }, error: '写入失败：' + describe(error) }
  }

  // 改动留痕：三个改动路径（设置页 / 会话内工具 / 手工编辑）里，只有设置页是"当场知道"的。
  // 另外两条由 readState 对比补记（见 journal.mjs 的单写者说明）。
  //
  // **在写入 try 之外**：这是一条审计记录，它失败不该让一次已经落盘的保存报 writeFailed
  // ——外部评审复现过：日志文件被 chmod 000 时，prompt.md 已经写成功而页面显示"保存失败"，
  // 页面又不会重新读取，于是它永远停在旧草稿上。留痕失败只降级为"这一版没记上"。
  try {
    recordPrompt(directory, prompt, HISTORY_SOURCE.settings)
  } catch {
    /* 审计记录失败不影响保存本身 */
  }

  const displayName = effectiveName === '' ? id : effectiveName
  if (autoOff.length > 0) {
    return {
      ok: true,
      id,
      mode,
      code: 'savedWithExclusiveRows',
      // `rows` 用行 id：页面自己按 `row.<id>.label` 渲染，英文界面不会因为这条掉回中文。
      params: { name: displayName, mode, rows: autoOff.join(', ') },
      note:
        '已保存（' + displayName + '，基础模式 ' + mode + '）。这两套壳注册同名工具、不能同时启用 —— ' +
        '已自动关掉：' + autoOff.join('、') + '。新建会话即生效，当前会话保持原配置。',
    }
  }
  return {
    ok: true,
    id,
    mode,
    code: 'saved',
    params: { name: displayName, mode },
    note: '已保存（' + displayName + '，基础模式 ' + mode + '）。新建会话即生效，当前会话保持原配置。',
  }
}

/**
 * Create one assistant from the packaged template — or duplicate an existing one.
 *
 * Nothing here copies an existing DIRECTORY: a copy would inherit whatever extra
 * files its source had accumulated, and creation would break as soon as the user
 * deleted the last one. Seeding the template makes "new" mean new, and a duplicate
 * (`from`) then replays the source's prompt, base mode and row switches on top.
 *
 * @param {Array<object>} rows - the current roster.
 * @param {object} input - `{ name, description, from }`; `from` names an existing
 *   assistant to duplicate.
 * @param {string} [templateDir] - packaged template (tests inject their own).
 */
export function createAssistant(rows, input, templateDir = packagedPresetDir()) {
  const rawName = input !== null && typeof input === 'object' && typeof input.name === 'string' ? input.name : ''
  const name = rawName.replace(/\r?\n/g, ' ').trim()
  if (name === '') return { ok: false, code: 'nameRequired', error: '请先给新助手起个名字。' }
  if (name.length > MAX_NAME) {
    return { ok: false, code: 'nameTooLong', params: { max: MAX_NAME }, error: '名字太长了（上限 ' + String(MAX_NAME) + ' 个字符）。' }
  }
  const rawDescription =
    input !== null && typeof input === 'object' && typeof input.description === 'string' ? input.description : ''
  const description = rawDescription.replace(/\r?\n/g, ' ').trim()
  if (description.length > MAX_DESCRIPTION) {
    return { ok: false, code: 'descriptionTooLong', params: { max: MAX_DESCRIPTION }, error: '描述太长了（上限 ' + String(MAX_DESCRIPTION) + ' 个字符）。' }
  }

  const root = userPresetRoot(rows)
  const taken = new Set()
  for (const row of rows) {
    if (row !== null && typeof row === 'object' && typeof row.id === 'string') taken.add(row.id)
  }
  const id = allocateId(name, taken)
  // The roster can lag a directory it skipped (a hand-made one with no
  // composition). Refuse the name rather than half-own that directory.
  if (existsSync(join(root, id))) {
    return { ok: false, code: 'dirExists', params: { path: join(root, id) }, error: '目录已存在，请换一个名字：' + join(root, id) }
  }

  // A "duplicate" carries the source's prompt, base mode and row switches into a
  // NEW assistant — read here, before anything is created, so a failure leaves no
  // half-made directory behind. The composition is NOT copied verbatim: it is
  // re-rendered from the source's base mode and overrides, because the generated
  // file embeds the new assistant's own name and id.
  let source = null
  const from = input !== null && typeof input === 'object' && typeof input.from === 'string' ? input.from : ''
  // 显示名在分支里计算，但返回语句在分支外 —— 所以声明在外层。
  let fromDisplayName = from
  if (from !== '') {
    const fromDir = assistantDir(rows, from)
    if (fromDir === undefined) return unknownAssistant(from)
    const fromComposition = compositionFile(fromDir)
    if (!existsSync(fromComposition)) return { ok: false, code: 'compositionMissing', params: { path: fromComposition }, error: '找不到组成文件：' + fromComposition }
    // 显示名（`rows` 里的 name）优先于内部 id —— 与删除一致。
    const fromRow = rows.find((item) => item !== null && typeof item === 'object' && item.id === from)
    if (typeof fromRow?.name === 'string' && fromRow.name.trim() !== '') fromDisplayName = fromRow.name
    // 与 readState 同一条纪律：**坏的源文件要给类型化错误，不能把异常抛到路由层**
    // （抛上去就变成 500 internalError，页面只能显示 Node 的原始错误串）。
    let text
    try {
      text = readFileSync(fromComposition, 'utf8')
    } catch (error) {
      return { ok: false, code: 'compositionMissing', params: { path: fromComposition, detail: describe(error) }, error: '读不到源助手的组成文件：' + describe(error) }
    }
    const sourceMode = modeOf(text)
    const sourcePrompt = readPrompt(fromDir)
    // 与上面组成文件**同一条纪律**：源提示词读不出来时返回类型化错误，不要静默用空串。
    //
    // 实测（`probes/probe-io.mjs` N）：旧代码写 `sourcePrompt.ok === true ? sourcePrompt.text : ''`，
    // 于是一次复制会返回 `ok / duplicated`，而新助手的 `prompt.md` 长度是 **0** ——
    // 用户以为自己复制出了一个助手，实际拿到的是**空的系统提示词**，而源里那份提示词还好端端躺着。
    // 保存路径有 `promptEmpty` 这道闸，复制路径没有；这个不对称就是缺陷本身。
    //
    // `readPrompt` 的失败形状已经是带 code 的类型化错误（页面按词典渲染），所以直接透传。
    if (sourcePrompt.ok !== true) return sourcePrompt
    // 复制走的是"照抄源助手的开关再重渲染"，所以源里若踩着互斥，抄过来还是踩 —— 与保存路径同一条
    // 自动避让（见 applyRowExclusivity）。不这么做的话，从这里能造出一个**必然 broken** 的新助手，
    // 而 broken 的模式会被静默丢掉。取不到出厂组成时保持原样：渲染那一步会走既有的降级/自检。
    let sourceOverrides = overridesOf(text, sourceMode)
    try {
      sourceOverrides = Object.fromEntries(applyRowExclusivity(sourceMode, new Map(Object.entries(sourceOverrides))).overrides)
    } catch (error) {
      if (!isBaseCompositionUnavailable(error)) throw error
    }
    source = {
      mode: sourceMode,
      overrides: sourceOverrides,
      prompt: sourcePrompt.text,
      description: readPresetMeta(fromDir).description,
    }
  }

  // 复制沿用源助手，不套姿态。省略姿态或显式 `develop` 与旧版创建相同：标准底、出厂开关、出厂提示词。
  const requestedPosture = source !== null
    ? ''
    : input !== null && typeof input === 'object' && typeof input.posture === 'string'
      ? input.posture.trim()
      : ''
  const posture = requestedPosture === '' || requestedPosture === 'develop' ? null : postureById(requestedPosture)
  if (requestedPosture !== '' && requestedPosture !== 'develop' && posture === null) {
    return {
      ok: false,
      code: 'postureUnknown',
      params: { posture: requestedPosture },
      error: '没有这个姿态：' + requestedPosture,
    }
  }
  if (posture !== null && posture.hands !== 'allow') {
    return {
      ok: false,
      code: 'postureUnknown',
      params: { posture: posture.id },
      error: '没有这个姿态：' + posture.id,
    }
  }
  const createMode = source !== null ? source.mode : posture === null ? 'standard' : posture.base

  let composition
  try {
    if (source !== null) {
      composition = renderComposition(source.mode, source.overrides, { modeName: name, assistantId: id })
    } else if (posture !== null && posture.hands === 'allow') {
      const exclusive = applyRowExclusivity(posture.base, allowlistOverrides(posture.base, posture.allow))
      composition = renderComposition(posture.base, exclusive.overrides, { modeName: name, assistantId: id })
    } else {
      composition = renderComposition('standard', new Map(), { modeName: name, assistantId: id })
    }
  } catch (error) {
    // 与保存路径的 `renderFailed` 同一套：**每个**用户可见的结果都要带 `code`，否则页面无从本地化
    // —— client.js 的 apiText 在没有 code 时直接退回下面这串中文，英文界面会在这一刻掉回中文
    // （AGENTS.md 第 13 条）。这条曾经是宿主半唯一漏掉 code 的普通返回。
    if (isBaseCompositionUnavailable(error)) {
      return {
        ok: false,
        code: error.code,
        params: { mode: createMode, detail: describe(error) },
        error: '本机取不到出厂组成，暂时无法新建助手。',
      }
    }
    return { ok: false, code: 'renderFailed', params: { detail: describe(error) }, error: '生成组成文件失败：' + describe(error) }
  }

  const created = createAssistantDir({ root, id, composition, templateDir })
  if (created.ok !== true) return created
  const abortCreate = (result) => {
    try {
      rmSync(created.dir, { recursive: true, force: true })
    } catch (error) {
      return {
        ...result,
        error: result.error + '；且未能清理半成品目录 ' + created.dir + '（' + describe(error) + '）',
      }
    }
    return result
  }

  // The template ships a starter prompt; a duplicate replaces it with the source's.
  // 姿态只替换草稿，不改模板目录里的 prompt.md。`develop` 与省略姿态停在模板那一份。
  if (source !== null) {
    try {
      writeAtomic(promptFile(created.dir), source.prompt)
    } catch (error) {
      return abortCreate({ ok: false, code: 'promptWriteFailed', params: { detail: describe(error) }, error: '写入提示词失败：' + describe(error) })
    }
  } else if (posture !== null) {
    let starter
    try {
      starter = posturePrompt(posture.id, postureLocale(input.locale))
    } catch (error) {
      return abortCreate({
        ok: false,
        code: 'posturePromptMissing',
        params: { posture: posture.id, detail: describe(error) },
        error: '读不到这个姿态的起步提示词：' + describe(error),
      })
    }
    try {
      writeAtomic(promptFile(created.dir), starter)
    } catch (error) {
      return abortCreate({ ok: false, code: 'promptWriteFailed', params: { detail: describe(error) }, error: '写入提示词失败：' + describe(error) })
    }
  }
  const metaResult = writePresetMeta(name, description === '' && source !== null ? source.description : description, created.dir)
  if (metaResult.ok !== true) return abortCreate(metaResult)
  try {
    writeAtomic(starterPromptFile(created.dir), readFileSync(promptFile(created.dir), 'utf8'))
  } catch (error) {
    return abortCreate({ ok: false, code: 'promptWriteFailed', params: { detail: describe(error) }, error: '写入提示词失败：' + describe(error) })
  }

  return {
    ok: true,
    id,
    name,
    code: source === null ? 'created' : 'duplicated',
    // D5：文案里用**显示名**而不是内部目录 id。
    params: source === null ? { name } : { name, from: fromDisplayName },
    note: source === null
      ? '已创建「' + name + '」。它的系统提示词现在是模板默认文本；写好后新建会话即可选择它。'
      : '已复制出「' + name + '」：提示词、基础模式与插件开关都来自「' + from + '」，之后各改各的，互不影响。',
  }
}

/**
 * Delete one assistant.
 *
 * Deletion goes through the platform's own `agentPresets.remove`, which is what
 * refuses a shipped preset and re-checks that the directory really lives under the
 * writable root. A live session that mounted the preset keeps running: its
 * composition was read at creation and is never re-read.
 *
 * @param {Array<object>} rows - the current roster.
 * @param {object} input - `{ id }`.
 * @param {{remove: (id: string) => Promise<void>}} agentPresets - the roster service.
 */
/**
 * 把"本机这条 dsh 线上无法解析的启用行"关掉，让预设重新健康。
 *
 * 为什么需要它：老版本创建（或老版本播种）的组成文件会一直留着 —— 播种只补缺失文件、从不覆盖用户数据。
 * 如果那份文件里有一行启用了本线不提供的插件，平台会把整个预设判为 broken 并从所有选择器里**静默丢弃**
 * （设置页照常能开，所以用户完全不知道）。这里复用与保存同一条排版手术：读出现有 overrides，把那几行显式
 * 关闭后重新渲染。用户的其它选择一字不动。
 */
export function repairComposition(rows, input) {
  const id = input !== null && typeof input === 'object' && typeof input.id === 'string' ? input.id : ''
  const directory = assistantDir(rows, id)
  if (directory === undefined) return unknownAssistant(id)
  const file = compositionFile(directory)
  if (!existsSync(file)) return { ok: false, code: 'compositionMissing', params: { path: file }, error: '找不到组成文件：' + file }
  const text = readFileSync(file, 'utf8')
  // 两类"这一行在本机起不来"，必须**都**关掉：
  //  ① 模块装不上 —— 我们自己在 node_modules 里查得出来（`unresolvableRows`）；
  //  ② 装配期失败（"服务等不到"之类）—— 只有宿主知道，写在它给的 `broken` 里。
  // 只修 ① 会留下一个**页面全绿、预设却仍然 broken**的状态（桌面端实测，见 brokenRowIds 的注释）：
  // `unresolvable` 变空 ⇒ 告警消失 ⇒ 用户以为修好了，而模式始终没出现在选择器里。
  const selfRow = Array.isArray(rows) ? rows.find((item) => item !== null && typeof item === 'object' && item.id === id) : undefined
  // 只关**这份文本里真的有**的行：宿主报的可能是别处的行，而 `disableRowsInPlace` 对不存在的 id
  // 是无操作 —— 计数会因此说谎（说关了 3 个其实只关了 1 个）。
  const present = new Set()
  const collectIds = (list) => {
    for (const row of list) {
      present.add(row.id)
      if (Array.isArray(row.children)) collectIds(row.children)
    }
  }
  try {
    collectIds(collectRows(text))
  } catch {
    /* 文本解析不了就只按 unresolvableRows 走（它自己会兜底） */
  }
  const wanted = [...new Set([...unresolvableRows(text).map((row) => row.id), ...brokenRowIds(selfRow?.broken)])]
  const bad = wanted.map((rowId) => ({ id: rowId })).filter((row) => present.has(row.id))
  if (bad.length === 0) {
    return { ok: true, id, code: 'repairNotNeeded', note: '这个助手在本机没有起不来的行，无需修复。' }
  }
  // **就地**关闭那几行，不重渲染：重渲染会顺手丢掉 base 之外的自有行，而用户的数据不该被这样动。
  const rendered = disableRowsInPlace(text, bad.map((row) => row.id))
  try {
    writeAtomic(file, rendered)
  } catch (error) {
    return { ok: false, code: 'writeFailed', params: { detail: describe(error) }, error: '写入失败：' + describe(error) }
  }
  const ids = bad.map((row) => row.id).join('、')
  return {
    ok: true,
    id,
    code: 'repaired',
    // `ids` 是给人看的一串；`repairedIds` 是给**页面**用的数组 —— 页面必须把这几行写进它自己的
    // 草稿，否则下一次保存会按草稿重渲染，把刚才的修复原样撤销（issue #8 实测：
    // 修复写进了磁盘，但草稿里那几行仍是启用的，于是保存后 ghost 行又回来了）。
    params: { count: bad.length, ids, repairedIds: bad.map((row) => row.id) },
    note: '已按本机这条 dsh 线关闭 ' + String(bad.length) + ' 个起不来的行（' + ids + '）。现在这个模式能重新出现在选择器里。',
  }
}

export async function deleteAssistant(rows, input, remover) {
  const id = input !== null && typeof input === 'object' && typeof input.id === 'string' ? input.id : ''
  const directory = assistantDir(rows, id)
  if (directory === undefined) return unknownAssistant(id)
  // `remover` 是**按后端选出来的**删除入口（见 {@link presetRemover}）：旧线是平台的
  // `agentPresets.remove()`，新线是声明式后端的 dispose + 删目录。两边都没有时才是真的做不到。
  if (remover === undefined || remover === null || typeof remover.remove !== 'function') {
    return { ok: false, code: 'noRemoveApi', error: '当前 DSH 版本没有可用的删除入口（agentPresets.remove() 与声明式后端都不可用），无法删除。' }
  }
  // 文案用**显示名**，不用内部目录 id（复制/删除的状态行曾把 id 暴露给用户，审阅点名）。
  const displayName = (() => {
    const row = Array.isArray(rows) ? rows.find((item) => item !== null && typeof item === 'object' && item.id === id) : undefined
    return typeof row?.name === 'string' && row.name.trim() !== '' ? row.name : id
  })()
  try {
    const outcome = await remover.remove(id, directory)
    if (outcome !== null && typeof outcome === 'object' && outcome.ok === false) {
      // 字面量 code（不用三元表达式）：test/editor-route.test.mjs 会扫 `code:` 后面的字符串字面量，
      // 要求每个都在词典里中英各一条 —— 三元里的 'string' 会被它当成一个 code。
      if (outcome.code === 'noDirectory') {
        return { ok: false, code: 'noDirectory', params: { path: directory }, error: '删除失败：找不到「' + displayName + '」的目录。' }
      }
      return { ok: false, code: 'deleteFailed', params: { detail: describe(outcome.code ?? 'unknown'), path: directory }, error: '删除失败：' + describe(outcome.code ?? 'unknown') }
    }
  } catch (error) {
    return { ok: false, code: 'deleteFailed', params: { detail: describe(error), path: directory }, error: '删除失败：' + describe(error) }
  }
  return { ok: true, id, code: 'deleted', params: { name: displayName }, note: '已删除「' + displayName + '」。正在使用它的会话不受影响；新建会话时不再出现。' }
}

/**
 * The plugin's HTTP surface, registered on the platform's **shared `/api` channel**.
 *
 * `ctx.connection.fetch.register({ path, methods, requestBody, fetch })` mounts one exact route under
 * `/api`, and the carrier applies its trust and authentication policy **before** dispatch. That is a
 * structural guarantee, not a convention: a route cannot exist without the fence, so "did you remember
 * to check?" is not a question anyone has to answer. An earlier version of this plugin registered on the
 * raw `ctx.webServer` table and called `ctx.connection.requestRejection` by hand — measured then, an
 * unauthenticated GET returned the whole system prompt and an unauthenticated cross-site POST rewrote
 * `prompt.md`, while every official route answered 401.
 *
 * Measured on 0.1.6-alpha.2 with a throwaway probe plugin (see `docs/MEASUREMENTS.md` §16):
 *
 *     GET  /api/<route>                                  no session cookie → 401 unauthorized
 *     GET  /api/<route>   Origin: https://evil.example   cross-site        → 403
 *     GET  /api/<route>   Host: evil.example             DNS-rebinding shape → 403
 *     GET  /api/<route>                                  session cookie    → 200
 *     POST /api/<route>   on a route that owns GET only                    → 404 (never dispatched)
 *
 * The paths are absolute *including* `/api` — that is what the platform's own packages pass
 * (`/api/present.host`, `/api/changes.summary`), despite the type saying "below /api".
 */
const API_PREFIX = '/api'

/**
 * Where the settings page can exist at all.
 *
 * The page is a WEB page: without `connection` there is no `/api` channel to register on, and without
 * `agentPresets` the assistant list cannot be built. The tui profile has neither.
 *
 * These must NOT go into the row's own `inject`. Measured on 0.1.6-alpha.1:
 * `./install.sh --profile tui` — a usage both `install.sh --help` and the READMEs
 * advertise — installs this web-only bundle into a profile with no web server, and a
 * row-level `inject` then parks the whole entry forever:
 *
 *     dsh: warning: 1 entry did not activate
 *     custom-mode (dsh-custom-mode): pending (waiting for services: webServer, agentPresets)
 *
 * That is the SAME line a broken installation prints, so it teaches users to ignore the
 * one warning that matters. Instead the row always activates, and the routes are
 * registered from a scoped fiber that waits for those two services (`ctx.inject`),
 * which is the dynamic form of the same declaration.
 */
const WEB_SERVICES = ['connection', 'agentPresets']

export function apply(ctx) {
  // 助手目录的根（`$DSH_HOME/.agent-presets`）。
  // 两条线共用它：旧线由平台扫描这个目录，新线不再扫描但**插件仍然把 composition 写在这里**
  // —— 它是用户可见、可迁移、可手改的真相，也是旧线唯一的输入。
  const assistantRoot = dirname(PRESET_DIR)

  // Before anything else: make sure the preset tree on disk is complete.
  //
  // A storefront install is a single command (`dsh plugin --profile web add
  // dsh-custom-mode`), and the npm package is all that command carries. Without this,
  // such an install produces a settings page whose composition file does not exist: the
  // page opens on an error and no mode can be picked for a new session.
  //
  // Synchronous on purpose: this runs on EVERY activation, and profiles without a web
  // server (tui) never reach the scoped fiber below, so the seed cannot live there.
  // `seedOnActivation` fills only MISSING files (never overwriting a prompt or a
  // generated composition) and creates the legacy assistant only on a first run — see
  // the assistant registry for why a deleted assistant must not come back.
  try {
    seedOnActivation({
      root: assistantRoot,
      templateDir: packagedPresetDir(),
      // 组成文件**不照搬包内模板**，而是按本机装的那条 dsh 线派生：模板是某一条线渲染出来的，
      // 另一条线可能根本没有它的某些行（实测：预览线的 workflow-ptc 在稳定线上不存在，
      // 平台会把整个预设判为 broken 并从所有选择器里静默丢弃）。
      composition: starterComposition({ assistantId: LEGACY_ID }),
    })
  } catch (error) {
    console.error('custom-mode: 初始化 preset 目录时出现意外错误（已忽略）: ' + describe(error))
  }

  try {
    ctx.inject(WEB_SERVICES, (scope) => {
    // Compatibility guard: this plugin reads host APIs that a future DSH release could
    // reshape. Check them once and say so plainly, instead of letting every request
    // fail with an opaque 500.
    // 数据表形式：以后新增一处耦合点，只要在这里加一行 —— README 的耦合点清单与本表同源，
    // 让"上游改了 API 形状"在启动日志里就能看见，而不是等用户报"设置页白屏"。
    //
    // ★ 分两级，而不是一张"缺一即死"的表。理由是一次实测：`agentPresets.remove()` 在
    //   dsh 0.1.7 上**不存在**（preset 定义改在内存里，删除要走 register() 返回的 disposer），
    //   而本插件早已为它写了 noRemoveApi 降级文案 —— 把它列进必需项，会让**整个设置页**
    //   在 0.1.7 上被自己关掉（实测 stderr：'设置页将不可用'）。
    //   能力缺失该逐个降级，不该一票否决整页。
    const CORE_APIS = [
      ['agentPresets.list()', () => typeof scope.agentPresets?.list === 'function'],
      ['connection.fetch.register()', () => typeof scope.connection?.fetch?.register === 'function'],
    ]
    const OPTIONAL_APIS = [
      // 出厂组成的来源（新线）：由宿主交出声明 YAML。缺了它插件仍能注册/同步，只是设置页里
      // 「基础模式 / 插件开关」会降级为不可编辑（提示词照常）—— 所以它是可选能力。
      ['agentPresets.readDocument()', () => typeof scope.agentPresets?.readDocument === 'function'],
      // 旧线的文件系统语义；新线由 preset-backend 的 declarative 后端用 disposer 补上。
      ['agentPresets.remove()', () => typeof scope.agentPresets?.remove === 'function'],
      ['agentPresets.copy()', () => typeof scope.agentPresets?.copy === 'function'],
    ]
    const missingCore = CORE_APIS.filter(([, probe]) => !probe()).map(([name]) => name)
    if (missingCore.length > 0) {
      console.error(
        'custom-mode: 当前 DSH 版本缺少必需 API：' +
          missingCore.join('、') +
          '。设置页将不可用，请核对 DSH 版本或提 issue。',
      )
      return
    }
    const absentOptional = OPTIONAL_APIS.filter(([, probe]) => !probe()).map(([name]) => name)

    // 判据是**能力**不是版本号：0.1.7 仍是 alpha，按版本号写的分支会在下一次发布时静默失配。
    const backendVerdict = detectPresetBackend(scope)
    console.log(
      'custom-mode: ' +
        describeBackend(backendVerdict) +
        (absentOptional.length > 0 ? ' · 可选能力缺失: ' + absentOptional.join('、') : ''),
    )
    if (backendVerdict.id === BACKEND_UNUSABLE) {
      for (const reason of backendVerdict.reasons) console.error('custom-mode: ' + reason)
      // 显式不可用，好过用错后端去写用户的 preset。
      console.error('custom-mode: 设置页将不可用（本插件不会写入任何 preset）。')
      return
    }

    /**
     * Resolve the shipped-preset directory through the roster, which reports each
     * preset's absolute path and is therefore independent of install layout.
     */
    let shippedReady = null
    const ensureShipped = () => {
      if (shippedReady === null) {
        const attempt = (async () => {
          try {
            const rows = await scope.agentPresets.list()
            const system = rows.find((row) => row.trust === 'system' && typeof row.path === 'string')
            // <presets>/<id>/agent.cordis.yml -> <presets>
            if (system !== undefined) setShippedPresetsDir(dirname(dirname(system.path)))
          } catch (error) {
            console.error('custom-mode: 无法从 roster 解析出厂预设目录：' + describe(error))
          }
        })()
        // 失败不缓存：旧线之外这条路本就可能没有 system 行，但一次**抛错**不该变成
        // "整个进程生命周期内都不再解析"。
        attempt.catch(() => { shippedReady = null })
        shippedReady = attempt
      }
      return shippedReady
    }

    /**
     * 声明式注册表（dsh ≥ 0.1.7）下 preset 必须由插件自己注册；旧线只需把文件写到磁盘、平台自己去扫。
     * 这里是后端的唯一实例（旧线为 null，所有相关分支都是空操作）。
     */
    const declarative =
      backendVerdict.id === BACKEND_DECLARATIVE
        ? createDeclarativeBackend({ scope, log: console.log, warn: console.error })
        : null

    /**
     * 删除入口，按后端选。新线上服务**没有** `remove()`（唯一手段是 `register()` 的 disposer），
     * 所以由声明式后端自己 dispose + 删目录；旧线继续用平台的文件系统语义。
     *
     * 这里返回 `undefined` 只发生在"两套都没有"的线上，那时才允许报 `noRemoveApi`。
     */
    const presetRemover = () => {
      if (declarative !== null) return { remove: (id, dir) => declarative.remove(id, dir) }
      if (typeof scope.agentPresets?.remove === 'function') return { remove: (id) => scope.agentPresets.remove(id) }
      return undefined
    }

    /** 原始 roster：只读、无副作用。同步逻辑走它，避免与 {@link roster} 互相递归。 */
    const rawRoster = async () => {
      const rows = await scope.agentPresets.list()
      if (!Array.isArray(rows)) {
        // 形状变了：静默返回空列表会让页面显示"一个助手都没有"，比报错更难查（曾经就因为
        // 缺少这种告警，一个 API 形状变化以"设置页白屏"的形式出现）。
        if (!warnedRosterShape) {
          warnedRosterShape = true
          console.error('custom-mode: agentPresets.list() 没有返回数组（DSH 版本不匹配？），助手列表将为空。')
        }
        return []
      }
      // 新线的 list() 只有显示元数据（没有 trust / path），而 roster 的消费者（assistants.mjs、
      // 整个路由层）都建立在这两个字段上。在这里从磁盘补出来，下游就不必做线判断。
      return effectiveRosterRows(rows, { root: assistantRoot, backendId: backendVerdict.id })
    }

    /**
     * 把磁盘上的助手同步进注册表 —— **仅新线**（旧线上 `declarative` 为 null，整体是空操作）。
     *
     * 新线没有目录扫描，注册与注销都由本插件负责：不跑这一步，用户在设置页里新建/改名/删除的
     * 助手就不会出现在选择器里。所以每次**写操作之后**都要重来一遍。
     */
    const doSync = async () => {
      // 先把"本机有哪些模块"喂给 composition 层。
      //
      // 它原本靠文件系统探测出厂目录（`shippedPresetsDir()` 往上推三层找 node_modules），
      // 而 **0.1.7 没有那个目录**：探测抛错、catch 返回 []、于是"本行能否在本机运行"的检查
      // **静默失效**（不报错，也不报警）。新线上有更好的来源 ——注册表自己就知道每个已注册
      // preset 的每一行的 moduleName。
      // 放在这里而不是外面：`ctx.inject` 的回调不是 async（写成 await 会让模块直接语法错误）。
      if (declarative !== null) {
        try {
          const inventory = await scope.agentPresets.compositionInventory()
          const rows = (Array.isArray(inventory) ? inventory : []).flatMap((preset) => preset?.rows ?? [])
          const names = rows.map((row) => row.moduleName).filter((name) => typeof name === 'string')
          if (names.length > 0) {
            setKnownModuleNames(names)
            console.log(`custom-mode: 已注入 ${names.length} 个出厂行模块名（compositionInventory），供"本行能否在本机运行"的判定使用`)
          }
        } catch (error) {
          // 注入不了就退回文件系统判定 —— 那是旧线的正常路径，不是错误状态。
          console.error('custom-mode: 读取 compositionInventory 失败（回退文件系统判定）: ' + describe(error))
        }
      }
      // 出厂组成：新线上唯一的来源是宿主自己（readDocument）。取到就交给 composer；取不到就交给
      // 降级路径。两种情况都不阻塞下面的注册表同步 —— 模式可用优先于设置页完整。
      if (declarative !== null) {
        try {
          // 只问**出厂那四位**：并集模式（BASE_MODES 的第五项）在宿主那里没有文档，
          // 拿它去 readDocument 只会每次都换来一条"取不到"的错误日志，把真正的故障淹没掉。
          const bases = await declarative.fetchBaseCompositions(BASE_MODE_IDS)
          setBaseCompositions(bases.fetched)
          if (bases.fetched.size > 0) {
            console.log(
              'custom-mode: 已取回 ' + String(bases.fetched.size) + ' 个基础模式的出厂组成（' +
                [...bases.fetched.keys()].join('、') + '）',
            )
          }
          if (bases.problems.length > 0) {
            console.error(
              'custom-mode: 取不到这些基础模式的出厂组成 —— ' + bases.problems.join('；') +
                '。对应模式仍可编辑提示词，基础模式与插件开关会显示为不可用。',
            )
          }
        } catch (error) {
          console.error('custom-mode: 取回出厂组成时出错（已忽略）: ' + describe(error))
        }
      }
      if (declarative === null) return
      try {
        const rows = await rawRoster()
        const targets = []
        for (const assistant of assistantsFromRoster(rows)) {
          const dir = assistantDir(rows, assistant.id)
          if (dir !== undefined) targets.push({ ...assistant, dir })
        }
        const results = await declarative.sync(targets)

        // 核对每个助手的 composition：有没有"本机装不了的启用行"。
        //
        // 为什么必须由我们说出来：新线没有出厂 composition 可派生（`seedOnActivation` 只能回落到
        // 包内模板），而模板可能来自**另一条** dsh 线。平台对含未知行的 preset 的处置是
        // **静默把整个模式从所有选择器里丢掉**，设置页却照常工作 —— 用户只会看到"我的模式不见了"。
        // 这一步把它变成一条指名道姓的日志。
        for (const target of targets) {
          try {
            const text = readFileSync(join(target.dir, 'agent.cordis.yml'), 'utf8')
            const bad = unresolvableRows(text)
            if (bad.length > 0) {
              console.error(
                `custom-mode: 助手 "${target.id}" 有 ${bad.length} 行在本机装不了 —— ` +
                  bad.map((b) => `${b.id}(${b.name})`).join('、') +
                  '。含这种行的模式会被平台静默地从选择器里丢掉；可在设置页用"按本线修复"关掉它们。',
              )
            }
          } catch (error) {
            // 读不到就没法判 —— 不猜。
            console.error(`custom-mode: 无法核对助手 "${target.id}" 的 composition: ${describe(error)}`)
          }
        }
        const ok = results.filter((r) => r.ok === true)
        const failed = results.filter((r) => r.ok === false)
        // 成功也要说：新线上「设置页里能看到助手」完全取决于这一步，
        // 而它此前是静默的 —— 出问题时没有任何一行日志能说明"到底同步了几个"。
        console.log(
          `custom-mode: 声明式注册表同步完成 —— 目标 ${targets.length} 个助手，成功 ${ok.length} 个` +
            (failed.length > 0 ? `，失败 ${failed.length} 个` : '') +
            `（${ok.map((r) => r.id).join(', ') || '无'}）`,
        )
        if (failed.length > 0) {
          console.error(`custom-mode: ${failed.length} 个助手的 preset 未能挂载 —— 见上面的具体原因。`)
        }
      } catch (error) {
        console.error('custom-mode: 同步 preset 到注册表失败（已忽略）: ' + describe(error))
      }
    }

    /**
     * 路由层用的 roster：首次调用会等**启动同步**完成，之后不再等。
     *
     * 为什么不是简单地 await 一次：`ctx.inject` 的回调不是 async（实测写成 `await` 会让模块
     * 直接语法错误、插件加载失败），所以启动同步只能在这里补等；而它只该等一次 ——
     * 写操作之后的重同步由 `resyncPresets` 负责，不该让每个请求都等一遍。
     */
    let startupSync = null
    const roster = async () => {
      if (startupSync !== null) {
        const pending = startupSync
        startupSync = null
        await pending
      }
      return rawRoster()
    }

    /** 写操作之后调用：重新对齐注册表。失败已在内部记录，不抛出。 */
    const resyncPresets = async () => {
      startupSync = null
      await doSync()
    }

    // 启动即同步：新线不是"写盘即生效"，第一次进来必须先注册。
    startupSync = doSync()

    /**
     * One Fetch-shaped handler for the whole surface.
     *
     * `pathname` is the logical path (without the `/api` prefix) so the method table above stays the
     * single place where the surface is described.
     */
    /**
     * JSON response with the page's caching policy attached.
     *
     * `no-store` because every one of these answers is state the page then displays: a cached
     * `GET /api/custom-mode/state` would show the user rows they already changed. (The old
     * hand-rolled response helper set the same header; dropping it here would have been a silent
     * regression.)
     */
    const json = (value, status = 200) =>
      Response.json(value, { status, headers: { 'cache-control': 'no-store' } })

    const handle = async (request, pathname) => {
      try {
        const url = new URL(request.url)
        if (request.method === 'GET' && pathname === ROUTE_PATH) {
          await ensureShipped()
          return json(readList(await roster()))
        }
        if (request.method === 'GET' && pathname === STATE_PATH) {
          await ensureShipped()
          return json(readState(await roster(), url.searchParams.get('id') ?? '', { factoryPrompt: packagedPrompt() }))
        }

        if (request.method === 'GET' && pathname === HISTORY_PATH) {
          await ensureShipped()
          const id = url.searchParams.get('id') ?? ''
          const directory = assistantDir(await roster(), id)
          if (directory === undefined) return json(unknownAssistant(id), 404)
          const text = readVersion(directory, url.searchParams.get('n') ?? '')
          if (text === null) return json({ ok: false, code: 'versionMissing', error: '找不到这个版本（历史可能已被上限裁剪）。' }, 404)
          return json({ ok: true, id, n: url.searchParams.get('n'), text })
        }

        // Backstop on request size. `requestBody: 'buffered'` means the platform applies its own
        // JSON cap, but that cap is the host's configuration, not a contract — measured on Windows,
        // a 5 MB body reached us happily. This keeps one authenticated request from making us buffer
        // an unbounded amount; the platform's cap remains the primary guard.
        const declared = Number(request.headers.get('content-length') ?? '')
        if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) {
          return json({ ok: false, code: 'bodyTooLarge', params: { max: MAX_BODY_BYTES }, error: `请求体过大（上限 ${String(MAX_BODY_BYTES)} 字节）` }, 413)
        }

        // Everything below writes, so the body is read and parsed exactly once.
        let parsed
        try {
          // 只信 `content-length` 会被 chunked（或不带该头）的请求绕过 —— 所以读完再按**实际字节**判一次。
          // 平台自己的 buffered cap 仍是第一道；这一道保证"我们绝不 buffer 一个无上限的请求体"。
          const raw = await request.text()
          if (Buffer.byteLength(raw, 'utf8') > MAX_BODY_BYTES) {
            return json({ ok: false, code: 'bodyTooLarge', params: { max: MAX_BODY_BYTES }, error: `请求体过大（上限 ${String(MAX_BODY_BYTES)} 字节）` }, 413)
          }
          parsed = JSON.parse(raw)
        } catch {
          return json({ ok: false, code: 'badJson', error: '请求体不是合法 JSON' }, 400)
        }
        await ensureShipped()
        const targetId = parsed !== null && typeof parsed === 'object' && typeof parsed.id === 'string' ? parsed.id : ''
        // 一把锁覆盖同一棵预设树。分钥匙会让「删除」和「保存」交错，把刚删的目录再写成半成品。
        if (pathname === STATE_PATH) {
          const result = await serializedWrite('preset-write', async () => saveState(await roster(), parsed))
          return json(result, result.ok === true ? 200 : 400)
        }
        if (pathname === CREATE_PATH) {
          const result = await serializedWrite('preset-write', async () => createAssistant(await roster(), parsed))
          return json(result, result.ok === true ? 200 : 400)
        }
        if (pathname === REPAIR_PATH) {
          const result = await serializedWrite('preset-write', async () => repairComposition(await roster(), parsed))
          return json(result, result.ok === true ? 200 : 400)
        }
        if (pathname === REORDER_PATH) {
          const result = await serializedWrite('preset-write', async () => reorderAssistant(await roster(), parsed))
          return json(result, result.ok === true ? 200 : 400)
        }
        const result = await serializedWrite('preset-write', async () => deleteAssistant(await roster(), parsed, presetRemover()))
        return json(result, result.ok === true ? 200 : 400)
      } catch (error) {
        // 兜底也要带 code：这是**意料之外**的异常，页面拿到的是 Node 的原始错误串，方向反着也一样糟
        // ——中文界面里会蹦出一句英文。带 code 后页面用自己的词典渲染，原始串只作为 `detail` 参数。
        return json({ ok: false, code: 'internalError', params: { detail: describe(error) }, error: describe(error) }, 500)
      }
    }

    // One registration per exact path — the platform's Fetch registry matches exact paths, not prefixes —
    // each declaring the methods it owns. The method table doubles as the guarantee that a prefetched
    // `GET /api/custom-mode/create` cannot create anything: that method is not registered for that path.
    for (const [pathname, methods] of Object.entries(METHODS)) {
      scope.effect(
        () => scope.connection.fetch.register({
          path: API_PREFIX + pathname,
          methods: [...methods],
          requestBody: 'buffered',
          fetch: async (request) => {
            const response = await handle(request, pathname)
            // 写成功之后，把注册表同步到磁盘的新状态。
            // 旧线上 `declarative` 是 null，这里恒为空操作；新线上没有它，用户在设置页
            // 新建/改名/删除的助手就不会出现在选择器里 —— 是本插件最容易被漏掉的一步。
            if (declarative !== null && request.method !== 'GET' && response.ok) await resyncPresets()
            return response
          },
        }),
        'custom-mode.route' + pathname,
      )
    }
    })
  } catch (error) {
    // ctx.inject 自身抛错（例如宿主改了它的签名）会让整段装配静默消失：没有路由、没有日志。
    // 这一层是把它变成一条可读的错误。
    console.error('custom-mode: ctx.inject 注册失败（这是个 bug，请提 issue）: ' + describe(error))
  }
}

/**
 * 写文件：先写同目录的临时文件，再 rename 覆盖。
 *
 * `rename(2)` 在同一文件系统内是原子的，所以读者（提示词读取器按 mtime+size 缓存）要么看到旧内容、
 * 要么看到新内容，不会读到写了一半的文件。原来连续两次 writeFileSync 在极端时序下可能被读成撕裂的
 * prompt，而这个文件正是"用户的提示词"。
 */
// 原子写与重试只有一份实现（见 atomic.mjs 的说明）；这里再导出 renameWithRetry，
// 让既有的路由测试继续能从 index.mjs 取到它。
export { renameWithRetry } from './atomic.mjs'

/** `error` as a readable string, without assuming it is an Error. */
function describe(error) {
  return String((error && error.message) || error)
}
