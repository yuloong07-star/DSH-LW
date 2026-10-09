/**
 * Composition compiler for the 自定义模式 settings page.
 *
 * Turns「base mode + per-row switches」into an `agent.cordis.yml`, by rewriting a
 * shipped preset's composition text rather than reserialising parsed YAML.
 *
 * Why text surgery instead of a YAML round-trip:
 *
 *  - The shipped compositions carry explanatory comments that are genuinely
 *    useful; a parse/serialise round-trip destroys all of them.
 *  - Rows carry `!!js` expressions (`disabled: !!js process.platform === 'win32'`).
 *    A round-trip would either lose them or re-emit them differently, silently
 *    changing platform behaviour.
 *
 * So a row is treated as an opaque text segment: we keep it byte-for-byte and
 * only ever insert or replace its `disabled:` line. An untouched row therefore
 * stays EXACTLY as shipped, including its platform condition.
 *
 * Timestamp: `agent-presets` decides whether to re-mount a preset by comparing
 * only `mtimeMs` and `size` of the composition file. A regeneration that
 * happened to produce identical bytes would not take effect, so the rendered
 * output always carries a timestamp comment.
 */

import { readFileSync, existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

// 出厂组成「从哪来」是两条 dsh 线唯一还没收进后端的一处：旧线是文件，0.1.7 是
// agentPresets.readDocument()。解析链整个搬到了 base-composition.mjs（含它的理由与实测），
// 本模块只消费它 —— 合成与排版手术仍然全部发生在这里。
import {
  baseCompositionPath,
  BASE_MODE_IDS,
  BaseCompositionUnavailableError,
  readBaseCompositionText,
  setShippedPresetsDir,
  shippedPresetsDir,
  UNION_MODE_ID,
} from './base-composition.mjs'

export { baseCompositionPath, setShippedPresetsDir, shippedPresetsDir } from './base-composition.mjs'
export { BASE_MODE_IDS, UNION_MODE_ID } from './base-composition.mjs'
export {
  BaseCompositionUnavailableError,
  isBaseCompositionUnavailable,
  setBaseCompositions,
} from './base-composition.mjs'

/**
 * 出厂组成的解析在 base-composition.mjs（见那里的解析顺序与实测依据）。
 *
 * 这里曾经只有一条路：`require.resolve('@deepseek-ai/dsh-agent-presets')`。0.1.7 起那个包不再发布，
 * 于是这条路必然抛错，而调用方把它变成了整页 500 —— 设置页在官方桌面端内置的那条线上整体不可用。
 */

/**
 * Base modes a user may build on — the picker's own list, in order.
 *
 * The first four are the **shipped** modes (`BASE_MODE_IDS`); a fifth is synthesised here
 * ({@link UNION_MODE_ID}), so "which rows can I switch at all" stops being decided by whichever single
 * shipped mode the user happened to pick.
 *
 * `custom` is still deliberately absent, and for the same reason as before: it is this feature's own
 * output, and a base mode by that name would make a preset recursively include itself. The union mode
 * sidesteps that by being a **different id** (`all`) whose text is built from the four *shipped* modes —
 * it never asks the host for the user's own preset.
 */
export const BASE_MODES = [
  { id: 'standard', label: '标准模式', note: '完整编码能力：Shell、文件、检索、技能、计划、目标、子代理、工作流' },
  { id: 'ptc', label: 'PTC 模式', note: '在标准模式基础上启用 PTC 工具呈现（tool-presentation）' },
  { id: 'minimal', label: '极简模式', note: '只有 Shell 与终端，共 7 行；没有文件、检索、技能、子代理' },
  { id: 'cordis', label: 'Cordis 模式', note: '标准模式 + 读写运行时的 Cordis 工具集，可让 agent 自己改 harness' },
  {
    id: UNION_MODE_ID,
    label: '自定义模式',
    note: '不继承任何单一原生模式：以标准模式的全套行为底，补上只有 PTC / 极简 / 创造模式才有的行，全部都能逐行开关',
  },
]

/**
 * Row display metadata: a friendly label, and whether switching it off costs a
 * basic capability. Rows absent from this table still render; they simply show
 * their raw id.
 */
export const ROW_META = {
  persona: { label: '身份（系统提示词）', essential: true, note: '提示词注入点；关掉后本模式用回部署默认身份' },
  'agent-instructions': { label: '项目指令 AGENTS.md', note: '读取 AGENTS.md / CLAUDE.md' },
  // 只出现在**最新预览线**（0.2.1-alpha.1）的 standard / cordis / ptc 三个模式里；
  // 0.2.0-rc.2 及更早**没有**这两行（其 dsh-web-app README 原话："出厂组合不含 time-context、
  // schedule 和 ui-schedule 行"）。加进来是为了不让它们以裸 id 出现在设置页 ——
  // 上游每次新增行都会让"每个出厂行都能查到标签"那条断言变红，这正是双线矩阵的价值。
  'time-context': {
    label: '时间上下文（模型看得到时间）',
    note: '在合适的步骤里附上当前时间、浏览器时区与距上一条消息的间隔；默认 10 分钟内不重复注入',
  },
  'tool-schedule': {
    label: '定时提醒工具',
    note: '让 agent 创建/列出/修改/删除持久提醒；存储与投递由宿主的 schedule 服务负责',
  },
  'tool-bash': { label: 'Shell（bash）', essential: true },
  'tool-pwsh': { label: 'Shell（pwsh）', essential: true },
  'tool-fs': { label: '文件读写', essential: true, note: '关掉后 agent 无法读写文件' },
  'tool-fs-search': { label: '文件搜索（glob/grep）' },
  'tool-jobs': { label: '后台任务' },
  'tool-todo': { label: '待办清单' },
  'tool-ask-user': { label: '向用户提问' },
  'tool-goal': { label: '目标' },
  'command-goal': { label: '目标命令' },
  planning: { label: '计划模式（分组）', note: '含 isolate realm，关掉等于移除整个计划能力' },
  'plan-mode': { label: '计划模式实现' },
  compaction: { label: '上下文压缩（分组）', note: '含 isolate realm' },
  'compaction-basic': { label: '基础压缩' },
  'command-compact': { label: '/compact 命令' },
  'tool-result-pruner': { label: '工具结果裁剪' },
  delegation: { label: '委派与工作流（分组）', note: '含 isolate realm；关掉等于移除子代理与工作流' },
  'tool-subagent': { label: '子代理（spawn）' },
  'tool-subagent-fork': { label: '子代理（fork）' },
  'tool-subagent-control': { label: '子代理控制' },
  'tool-subagent-list-agents': { label: '列出子代理' },
  'tool-subagent-codex': { label: 'Codex 子代理', note: '默认关闭：需要先安装对应 Bundle' },
  'tool-subagent-claude-code': { label: 'Claude Code 子代理', note: '默认关闭：需要先安装对应 Bundle' },
  'workflow-ptc': { label: '工作流引擎' },
  // 只出现在**稳定线**（0.1.5-rc.2）的委派分组里；预览线没有这一行。CI 的稳定线任务抓到了它
  // 缺标签（"每个出厂行都能查到标签"），这正是双线矩阵的价值。
  'workflow-worker-thread': { label: '工作流 Worker 线程', note: '把工作流跑在独立的 worker 线程里' },
  'tool-workflow': { label: '工作流工具' },
  'tool-ralph': { label: 'Ralph 工作流', note: '默认关闭' },
  'tool-web': { label: '网页检索与抓取' },
  'tool-skill': { label: '技能工具' },
  'skill-filesystem': { label: '技能发现' },
  'tool-cordis': { label: 'Cordis 运行时工具', note: '可读写 harness 运行时' },
  'tool-plugin-manager': {
    label: '插件管理（安装 / 启停）',
    note: '模型侧可安装/启停插件；标准与 PTC 模式里出厂即关闭（只有创造模式默认开），本模式可以显式打开它',
  },
  'tool-presentation': { label: 'PTC 工具呈现' },
  present: { label: '交付文件（present）' },
  'custom-prompt-tool': { label: 'custom_prompt 工具', note: '关掉后无法用对话改提示词（设置页仍可用）' },
  'persistent-shell': { label: '持久 Shell' },
  pty: { label: 'PTY 终端' },
  'terminal-bash': { label: '终端（bash）' },
  'persistent-bash': { label: '持久 bash' },
  'terminal-pwsh': { label: '终端（pwsh）' },
  'persistent-pwsh': { label: '持久 pwsh' },
}

/**
 * 出厂行里的**互斥组**：同一份组成里同时启用，会让挂载方把整个预设判为 **broken**，
 * 而 broken 的预设被从**所有**选择器里静默丢掉 —— 设置页却照常工作（本仓记录过两次的最阴的一种失败）。
 *
 * 实测依据（2026-10-01，dsh `0.2.0-rc.2`）：把并集组成真的写进 `.agent-presets/custom/agent.cordis.yml`
 * 再启动，然后读实例自己的 `/api/custom-mode`：
 *
 *     broken = "persistent-bash (@deepseek-ai/dsh-tool-bash-persistent):
 *               tool \"bash\" is already registered in this scope"
 *
 * 两套壳注册的工具名相同（`bash` / `pwsh`），所以它们是**二选一**，不是叠加。极简模式里不冲突，
 * 只是因为它**没有**标准那一套。逐行开关本身表达不了"二选一"，所以互斥必须在这里显式声明。
 *
 * 同一轮实测的对照（每行都是"写组成 → 真启动 → 读 broken"）：
 *
 * | 组成 | broken |
 * | --- | --- |
 * | 并集原样 | ✗（上面那条） |
 * | 只关掉 `persistent-shell` | ✓ null |
 * | 只关掉 `tool-bash` + `tool-pwsh` | ✓ null |
 * | 把 `persistent-shell` 整组删掉 | ✓ null |
 * | 删掉 `tool-presentation` / `tool-cordis`（两个附加行） | ✗ 仍 broken ⇒ 附加行无罪 |
 *
 * ★ 这条表**必须被维护**：上游调整行时它就是唯一的真相。`composition.test.mjs` 会断言表里每个 id
 * 都真的出现在某份出厂组成里 —— 上游把它删了，CI 就红，而不是让这里静默失效。
 */
export const EXCLUSIVE_ROW_SETS = [
  {
    id: 'shell',
    sides: [
      { label: '标准壳', rows: ['tool-bash', 'tool-pwsh'] },
      { label: '持久终端壳', rows: ['persistent-shell'] },
    ],
    /**
     * 并集模式里默认关掉哪一侧。
     *
     * 必须**显式关**，不能指望"取那位模式的出厂默认"：`persistent-shell` 在极简模式里出厂就是**启用**的
     * （那正是极简模式的全部内容），所以照搬出厂状态合出来的并集是**非法**的。实测：`overrides: {}`
     * 保存并集模式，写完之后不重启，roster 里立刻就是 `broken`。
     */
    unionDefaultOff: ['persistent-shell'],
  },
]

/** 出厂组成的路径与解析链都在 base-composition.mjs（下面按名字再导出，调用点不必分支）。 */

/**
 * Read one base mode's shipped composition text.
 *
 * The chain — explicit override → text handed over by the host (`readDocument`) → legacy presets
 * directory → packaged `dsh-web-app` patch — lives in base-composition.mjs, so every route can be
 * tested on its own and the failure carries a `code` instead of a bare message.
 *
 * {@link UNION_MODE_ID} is the one mode with no route: it is synthesised here (see
 * {@link unionCompositionText}), so this function stays the single entry point every caller uses and no
 * caller has to know which modes ship a file.
 *
 * @param {string} modeId - one of {@link BASE_MODES}.
 * @returns {string} the entry-list YAML of that base mode.
 * @throws {BaseCompositionUnavailableError} when no route yields text (callers degrade, see index.mjs).
 */
export function readBaseComposition(modeId) {
  if (modeId === UNION_MODE_ID) return unionCompositionText()
  return readBaseCompositionText(modeId).text
}

/**
 * The union of the four shipped compositions — the text behind {@link UNION_MODE_ID}.
 *
 * ## Why this exists
 *
 * The row list used to be whatever the *single* shipped mode the user picked happened to contain, and a
 * switch can only rewrite rows the base text already has — so a row declared by one mode and not by the
 * chosen one was not "hidden in the UI", it was **unreachable through the composer**. Measured on
 * `0.2.0-rc.2`: `standard` / `ptc` / `cordis` each declare 33 rows and `minimal` declares 7, but their
 * union is 40. Picking `standard` therefore leaves `tool-presentation` (only in ptc), the whole
 * `persistent-shell` group (only in minimal) and `tool-cordis` (only in cordis) permanently unswitchable.
 *
 * ## How it is built
 *
 * Text surgery, exactly like the rest of this module: the four shipped texts are split at the TOP level and
 * concatenated, skipping a row id that is already present. A group therefore travels **whole** — children,
 * its `isolate` realm, its comments and its `!!js` conditions included. That is the only way a service row
 * can be carried into this mode without inventing a realm for it, and inventing one is not cosmetic:
 * `dsh-agent-presets` rejects a service row that has none, and the platform then drops the entire preset
 * from every picker.
 *
 * Only top-level ids are compared. Children ride along inside their group, so a nested id can never be
 * half-merged; if two modes both declare a group, the group is taken from whichever mode wins below, and
 * its children come with it.
 *
 * ## Which mode wins
 *
 * First writer, in `BASE_MODE_IDS` order (`standard` → `ptc` → `minimal` → `cordis`). The order is not
 * cosmetic: `standard` is the mode this product's own preset is derived from, so a row declared in more
 * than one mode keeps **standard's text and standard's shipped default**. The visible consequence is
 * deliberate — `tool-plugin-manager` ships disabled in `standard` and enabled in `cordis`, and the union
 * keeps it disabled (the user turns it on per assistant if they want it). The alternative rule, "enabled
 * if any mode enables it", would silently arm the model's plugin manager in a mode the user never chose.
 *
 * ## Nothing is cached
 *
 * The four source texts are not stable for the lifetime of a process: the tests swap
 * `DSH_SHIPPED_PRESETS_DIR` between cases, and the host may re-declare a preset on any registry sync. A
 * cache here would be a stale answer that looks authoritative. Four extra reads per call is the honest
 * price, and this is not a hot path.
 *
 * ## One source failing is fatal, not skipped
 *
 * A union that quietly dropped `minimal` when that one route broke would still render, still look complete,
 * and be missing the rows the user was told they could switch — precisely the silent lie this module's
 * typed failure exists to prevent. So a failure propagates as {@link BaseCompositionUnavailableError} with
 * the failing mode named, and the page degrades honestly: the prompt stays editable while the base mode and
 * the switches are marked unavailable.
 *
 * @returns {string} entry-list YAML: the union, behind a banner recording where each part came from.
 * @throws {BaseCompositionUnavailableError} when any shipped mode cannot be read.
 */
function unionCompositionText() {
  const seen = new Set()
  const parts = []
  const sources = []
  for (const modeId of BASE_MODE_IDS) {
    let resolved
    try {
      resolved = readBaseCompositionText(modeId)
    } catch (error) {
      // 逐条记下**是哪一位**读不到，再抛类型化失败 —— 页面据此说出具体原因，而不是笼统的"取不到"。
      throw new BaseCompositionUnavailableError(UNION_MODE_ID, [
        ...sources.map((each) => each.modeId + '：可用（' + each.source + '）'),
        modeId + '：' + String((error && error.message) || error),
      ])
    }
    sources.push({ modeId, source: resolved.source })
    for (const segment of splitSegments(resolved.text, true).segments) {
      if (seen.has(segment.id)) continue
      seen.add(segment.id)
      parts.push(segment.text)
    }
  }
  if (parts.length === 0) {
    throw new BaseCompositionUnavailableError(UNION_MODE_ID, ['四个出厂模式都没有任何行'])
  }
  const banner = [
    '# 本段是四个出厂组成的并集（' + BASE_MODE_IDS.join(' → ') + '）。',
    '# 同一行在多个模式里都有时，取**先出现的那个模式**的原文与出厂默认（顺序即上面这一行）。',
    '# 分组是整体搬运的，isolate realm 与 !!js 平台条件因此原样保留。',
    ...sources.map((each) => '#   ' + each.modeId + ' ← ' + each.source),
    '',
  ].join('\n')
  // 分段自带尾换行、拼接时不加分隔符（splitSegments 的不变量），所以这里同样用 '' 连接。
  const merged = banner + parts.join('')
  // ★ 同名工具的两套壳不能同时启用（见 EXCLUSIVE_ROW_SETS 的实测）：并集把"另一侧"**显式关掉**，
  //   让默认组成是合法的。不关的后果实测过 —— 预设判 broken、从所有选择器里消失，而设置页全绿。
  const off = EXCLUSIVE_ROW_SETS.flatMap((set) => set.unionDefaultOff)
  return off.length === 0 ? merged : disableRowsInPlace(merged, off)
}

/**
 * Whether a segment line declares a row id at the expected indentation.
 *
 * A top-level row is recognised only at column 0. A nested row is recognised at **the indentation
 * that level actually uses** (`indent`), not at a hard-coded depth — see {@link nestedIndent} for
 * why that distinction is load-bearing.
 */
function rowIdAt(line, topLevel, indent) {
  const pattern = topLevel ? /^- id: (.+?)\s*$/ : new RegExp(`^ {${String(indent)}}- id: (.+?)\\s*$`)
  const match = pattern.exec(line)
  return match === null ? undefined : match[1].replace(/^['"]|['"]$/g, '')
}

/**
 * The indentation a nested level actually uses, measured from its first row line.
 *
 * The shipped compositions indent nested rows by four spaces, and this module used to hard-code
 * that. **YAML does not require it** — any consistent indentation is legal. Measured
 * (`probes/probe-fuzz.mjs` T): on a file indented by two spaces, every child of a group became
 * **invisible in the page and impossible to toggle**, while the lines were still in the file. The
 * user sees rows they cannot reach; the file keeps rows the page denies exist.
 *
 * Measuring costs one pass over the level's lines and removes the assumption.
 */
function nestedIndent(lines) {
  for (const line of lines) {
    const match = /^( +)- id: /.exec(line)
    if (match !== null) return match[1].length
  }
  // No nested row in this level at all: the value is never used (nothing matches), so return the
  // shipped shape rather than inventing one.
  return 4
}

/**
 * Split composition text into a leading preamble plus one segment per row.
 *
 * A segment runs from its `- id:` line to just before the next row line, and —
 * critically — the separating newline is INCLUDED in the segment it follows.
 * Segments therefore concatenate back to the original text with NO separator:
 * `lead + segments.join('') === text` exactly. Joining with `'\n'` instead would
 * lose one newline at every boundary, because the last line of a `slice` has no
 * trailing newline of its own.
 *
 * The `lead` keeps everything before the first row (the shipped header comment),
 * and also has no trailing newline of its own — the first segment starts with
 * one.
 */
function splitSegments(text, topLevel) {
  const lines = text.split('\n')
  const indent = topLevel ? 0 : nestedIndent(lines)
  const starts = []
  for (let i = 0; i < lines.length; i += 1) {
    const id = rowIdAt(lines[i], topLevel, indent)
    if (id !== undefined) starts.push({ index: i, id })
  }
  if (starts.length === 0) return { lead: text, segments: [] }
  /**
   * Text from line `from` up to (not including) line `to`, carrying the newline
   * that ended the last included line.
   *
   * The empty range is the case that matters: a nested level often begins AT
   * line 0, so `slice(0, 0)` must yield `''`. Returning `'\n'` there injects a
   * blank line before every group's first child.
   */
  const slice = (from, to) => {
    if (from >= to) return ''
    if (to >= lines.length) return lines.slice(from).join('\n')
    return `${lines.slice(from, to).join('\n')}\n`
  }
  const lead = slice(0, starts[0].index)
  const segments = starts.map((start, position) => {
    const end = position + 1 < starts.length ? starts[position + 1].index : lines.length
    return { id: start.id, text: slice(start.index, end) }
  })
  return { lead, segments }
}

/**
 * Whether a row segment carries its OWN `disabled` key, and its raw value.
 *
 * Scoped to the row's own indentation (see {@link ownKeyIndent}) for the same
 * reason {@link setDisabled} is: a group whose children ship disabled must not
 * read as a disabled group.
 */
function disabledOf(segmentText) {
  const own = disabledLineAt(ownKeyIndent(segmentText))
  for (const line of segmentText.split('\n')) {
    const match = own.exec(line)
    if (match !== null) return { present: true, value: match[1].trim() }
  }
  return { present: false, value: '' }
}

/**
 * Indentation of a row's OWN keys, for a row segment or a whole row tree.
 *
 * The `disabled` key we may set belongs at `- id:`'s indentation plus two
 * spaces. That distinction is load-bearing: group rows contain child rows that
 * carry their own `disabled:` keys, so a naive "first `disabled:` line in this
 * segment" search edits a CHILD's key and silently leaves the group itself
 * unchanged — which is exactly the bug this function exists to prevent.
 *
 * For a whole-tree input (used by the UI description path) the minimum `- id:`
 * indentation identifies the outermost row.
 *
 * @param {string} segmentText - one row segment, or a whole composition.
 * @returns {number} the column the row's own keys start at.
 */
function ownKeyIndent(segmentText) {
  let minIndent = Infinity
  for (const line of segmentText.split('\n')) {
    const match = /^(\s*)- id:/.exec(line)
    if (match !== null) minIndent = Math.min(minIndent, match[1].length)
  }
  return minIndent === Infinity ? 0 : minIndent + 2
}

/** Regex matching a `disabled:` key at exactly one indentation depth. */
function disabledLineAt(indent) {
  return new RegExp(`^ {${String(indent)}}disabled:\\s*(.*)$`)
}

/**
 * Rewrite the `disabled` key of one row segment.
 *
 * Only the row's OWN key is touched (see {@link ownKeyIndent}).
 *
 * Three states, and the distinction matters:
 *
 *  - `undefined` — the user did not touch this row. The segment is returned
 *    untouched, which is what preserves a shipped `!!js` platform condition and
 *    the rows that ship switched off (`tool-subagent-codex`, …).
 *  - `false` — the user explicitly enabled the row. Any `disabled:` line at the
 *    row's own depth is removed, INCLUDING a platform expression: an explicit
 *    choice beats the platform default.
 *  - `true` — the user disabled it. The value is written as a literal `true`,
 *    replacing a platform expression, again because the explicit choice wins.
 */
function setDisabled(segmentText, disabled) {
  if (disabled === undefined) return segmentText
  const lines = segmentText.split('\n')
  const indent = ownKeyIndent(segmentText)
  const own = disabledLineAt(indent)
  const index = lines.findIndex((line) => own.test(line))
  // ★ 行尾必须跟着输入走。
  //
  // `lines` 来自 `split('\n')`，所以 CRLF 文件里每一行的末尾都还带着 `\r`。这个函数**替换**
  // 或**插入**的行如果只以 `\n` 结尾，就会在文件里留下一个**裸 LF** —— 整个文件从此混行尾，
  // 下一个读它的工具会看到不一致的内容（实测 `probes/probe-fuzz.mjs` R：266 个 CRLF 里混进 1 个裸 LF）。
  // 同样的理由适用于下面两处替换：原行的 `\r` 会随替换一起丢掉。
  const carriage = segmentText.includes('\r\n') ? '\r' : ''
  if (index !== -1) {
    const shipped = lines[index].trim().replace(/^disabled:\s*/, '')
    if (disabled) {
      lines[index] = `${' '.repeat(indent)}disabled: true${carriage}`
    } else if (shipped.startsWith('!!js') && evalDisabledExpression(shipped) !== true) {
      // 显式打开，但出厂那行是平台表达式、且它在本机求值就是"开"：这不是覆盖，而是**撤销覆盖** ——
      // 保持出厂表达式原样，这一行就回到"跟随平台"。（原先这里删掉整行，结果是文件既不是出厂原样、
      // 也不是显式覆盖，页面却显示"未拨过" —— 文件与显示同时失真。）
      return segmentText
    } else {
      lines[index] = `${' '.repeat(indent)}disabled: false${carriage}`
    }
    return lines.join('\n')
  }
  if (!disabled) return segmentText
  const nameIndex = lines.findIndex((line) => new RegExp(`^ {${String(indent)}}name:\\s`).test(line))
  if (nameIndex === -1) return segmentText
  lines.splice(nameIndex + 1, 0, `${' '.repeat(indent)}disabled: true${carriage}`)
  return lines.join('\n')
}

/**
 * Evaluate a row's shipped `!!js` disable predicate against THIS process.
 *
 * Rows carry conditions like `!!js process.platform === 'win32'`. Reporting such
 * a row as "disabled" purely because a `disabled:` key exists would show every
 * platform row as off on every platform, and would hide the user's own toggle of
 * it (the diff against the shipped state comes out empty).
 *
 * Evaluating the predicate is what lets the page show the state that is actually
 * in force here. The expression comes from a composition file installed on this
 * machine, which the deployment already executes as a Cordis plugin, so this adds
 * no trust that the file did not already have.
 *
 * @param {string} expression - the raw value after `disabled:`, starting with `!!js`.
 * @returns {boolean|undefined} the evaluated result, or undefined when it cannot be evaluated.
 */
function evalDisabledExpression(expression) {
  if (typeof expression !== 'string' || !expression.startsWith('!!js')) return undefined
  try {
    // eslint-disable-next-line no-new-func -- evaluating the composition's own predicate is the point
    const fn = new Function('process', `return (${expression.slice(4).trim()});`)
    return fn(process) === true
  } catch {
    // 求值失败与「表达式不存在」都是 undefined。调用方若已经确认这是 `!!js`，
    // 必须把 undefined 当成关闭：挂载侧同样按关闭处理，两边不能一个开一个关。
    return undefined
  }
}

/**
 * 组成文本里是否有求值失败的 `disabled: !!js`。
 *
 * @param {string} text
 * @returns {boolean}
 */
export function hasFailedPredicate(text) {
  if (typeof text !== 'string' || text === '') return false
  const pattern = /^[ \t]*disabled:[ \t]*(!!js\s+\S.*?)\s*$/gm
  for (const match of text.matchAll(pattern)) {
    if (evalDisabledExpression(match[1]) === undefined) return true
  }
  return false
}

/** Build one row's UI description from its segment text. */
/**
 * A row's module specifier: the `name:` line.
 *
 * Needed by the declarative backend (dsh ≥ 0.1.7), which must hand `register()` a real row list
 * rather than a composition file on disk. It is also where the **relative specifiers this feature
 * emits** (`'./prompt-reader.mjs'`, `'./prompt-tool.mjs'`) surface: on the file-scanning line those
 * resolve against the preset directory, but `register()` mounts under the *profile's* baseUrl, so the
 * caller has to rewrite them to absolute `file://` URLs. Measured on 0.1.7-alpha.2: a relative
 * specifier yields `broken: … never started`, an absolute one mounts.
 *
 * @param {string} segmentText - one row's text.
 * @returns {string|null} the specifier with surrounding quotes stripped, or null when the row has none.
 */
export function moduleNameOf(segmentText) {
  const match = /^\s*name:\s*(.+?)\s*$/m.exec(segmentText)
  if (match === null) return null
  return match[1].replace(/^['"]|['"]$/g, '')
}

/**
 * A row's `config:` block, as a plain object.
 *
 * Deliberately a **small, strict YAML subset**, not a general parser: the only files this reads are
 * the ones this feature writes (`renderComposition`), whose config blocks are flat scalars. Anything
 * it cannot represent is skipped rather than guessed — a wrong value here would silently change what
 * the model sees. Callers that need certainty should compare against {@link moduleNameOf}.
 *
 * @param {string} segmentText - one row's text.
 * @returns {object|null} the config object, or null when the row has no `config:` block.
 */
export function configOf(segmentText) {
  const lines = segmentText.split('\n')
  const start = lines.findIndex((line) => /^\s*config:\s*$/.test(line))
  if (start === -1) return null
  const baseIndent = lines[start].search(/\S/)
  const out = {}
  let any = false
  for (let i = start + 1; i < lines.length; i += 1) {
    const line = lines[i]
    if (line.trim() === '') continue
    const indent = line.search(/\S/)
    if (indent <= baseIndent) break
    const match = /^\s*([A-Za-z_][\w-]*):\s*(.*?)\s*$/.exec(line)
    if (match === null) continue
    out[match[1]] = parseScalar(match[2])
    any = true
  }
  return any ? out : null
}

/** Parse one YAML scalar into a JS value, staying conservative: unknown shapes stay strings. */
function parseScalar(raw) {
  const value = raw.trim()
  if (value === '') return ''
  if (value === 'true') return true
  if (value === 'false') return false
  if (value === 'null' || value === '~') return null
  if (/^-?\d+$/.test(value)) return Number(value)
  if (/^"(?:[^"\\]|\\.)*"$/.test(value) || /^'(?:[^']|'')*'$/.test(value)) {
    try {
      return JSON.parse(value[0] === '"' ? value : `"${value.slice(1, -1).replace(/'/g, "\\'")}"`)
    } catch {
      return value.slice(1, -1)
    }
  }
  return value
}

function describeRow(id, segmentText, children) {
  const own = disabledOf(segmentText)
  const meta = ROW_META[id] ?? {}
  // A literal `disabled: true` is off. A `!!js` predicate is resolved for THIS
  // machine, so the page shows the state actually in force rather than "has a key".
  // 只看"有没有 disabled 键"是不够的：`disabled: false` 也是字面量，但它表示**开着**。
  const literalOff = own.present && !own.value.startsWith('!!js') && own.value === 'true'
  const fromExpression = own.present && own.value.startsWith('!!js') ? evalDisabledExpression(own.value) : undefined
  // `!!js` 抛错时求值器返回 undefined。页面按关闭显示，与声明式挂载的 catch 一致。
  const expressionFailed = own.present && own.value.startsWith('!!js') && fromExpression === undefined
  return {
    id,
    group: children.length > 0,
    disabled: literalOff || fromExpression === true || expressionFailed,
    disabledExpression: own.present && own.value.startsWith('!!js') ? own.value : null,
    // 新线（声明式注册表）要把行交给 register()，需要模块说明符与 config —— 见 moduleNameOf。
    moduleName: moduleNameOf(segmentText),
    config: configOf(segmentText),
    label: meta.label ?? id,
    essential: meta.essential === true,
    note: meta.note ?? null,
    children,
  }
}

/**
 * Collect the row tree of a composition, for rendering the settings UI.
 *
 * @param {string} text - composition file text.
 * @returns {Array<object>} rows, groups carrying their nested rows.
 */
export function collectRows(text) {
  const { segments } = splitSegments(text, true)
  return segments.map((segment) => {
    const isGroup = /^\s*group:\s*true\s*$/m.test(segment.text)
    const nested = isGroup ? splitSegments(segment.text, false).segments : []
    const children = nested.map((child) => describeRow(child.id, child.text, []))
    return describeRow(segment.id, segment.text, children)
  })
}

/**
 * Rewrite one level of rows, applying disabled overrides by id.
 *
 * `overrides` maps a row id to its explicit **enabled** state (`true` = on, `false` = off). A row absent is
 * left byte-for-byte as shipped — that is how an untouched `!!js` platform
 * condition and the rows that ship disabled survive a regeneration.
 *
 * @param {string} text - the level to rewrite.
 * @param {boolean} topLevel - level selector for {@link splitSegments}.
 * @param {Map<string, boolean>} overrides - explicit per-row states.
 * @param {boolean} nested - whether this call rewrites a group's contents.
 * @param {boolean} replacePersona - whether the persona row may be replaced wholesale.
 * @param {Set<string>} consumed - ids already applied at this call site; see below.
 * @returns {string} the rewritten level.
 */
function applyLevel(text, topLevel, overrides, nested, replacePersona = true, consumed = new Set()) {
  const { lead, segments } = splitSegments(text, topLevel)
  if (segments.length === 0) return text
  const rendered = segments.map((segment) => {
    // ★ 一个 id 只作用于**第一个**匹配的行。
    //
    // 组成文件里出现重复 id 时（手写、或两次手术叠加），旧实现按 id 查 `overrides`，
    // 于是两个同 id 的行**一起被改**。实测（`probes/probe-fuzz.mjs` S）：3 行
    // （alpha, alpha, beta）里关掉 alpha 会关掉**两处** —— 用户拨的是一个开关，
    // 文件里被改的是两处。那不是"多改了一点"，是**改了他没指的那一行**。
    //
    // 取第一个匹配（页面渲染的也是第一处），其余同 id 行保持原样。
    // `consumed` 跨层共享：分组内的子行与外层同名时同理，只有第一处被改。
    const state = consumed.has(segment.id) ? undefined : overrides.get(segment.id)
    if (state !== undefined) consumed.add(segment.id)

    // The persona row is always replaced by this feature's own reader row: the
    // shipped one is a static-string persona whose text cannot be edited, so
    // keeping it would silently disable the editable prompt.
    // `replacePersona` 只有**重新渲染**时才为真：那时整段 persona 换成我们的读取器行是对的。
    // 但"按本线修复"是**就地**手术，它必须连 persona 段里的注释都原样保留 ——
    // 之前这里无条件替换，导致每次修复都会丢注释 / 或多复制一行身份注释（外部评审实测）。
    if (!nested && segment.id === 'persona' && replacePersona === true) {
      return setDisabled(PERSONA_ROW, state)
    }
    let body = segment.text
    if (nested) {
      // Nested rows: rewrite only the row's own `disabled`, never recurse.
      return setDisabled(body, state)
    }
    const isGroup = /^\s*group:\s*true\s*$/m.test(body)
    if (isGroup) {
      const children = splitSegments(body, false).segments
      if (children.length > 0) {
        // Rewrite the group's nested block, then the group's own `disabled`.
        //
        // 缩进**用量出来的**，不是写死的 4 —— 同 {@link nestedIndent} 的理由：
        // 写死时，缩进不是 4 格的合法组成文件里 `cut` 恒为 -1，整个分组的子行**一次都不会被重写**，
        // 而函数照样返回一份"看起来改过了"的文本。
        const pad = ' '.repeat(nestedIndent(body.split('\n')))
        const cut = body.indexOf(`\n${pad}- id: ${children[0].id}`)
        if (cut !== -1) {
          const head = body.slice(0, cut + 1)
          const tail = body.slice(cut + 1)
          body = head + applyLevel(tail, false, overrides, true, true, consumed)
        }
      }
    }
    return setDisabled(body, state)
  })
  // Empty join: each segment already carries the newline that followed it, so
  // `lead + rendered.join('')` reproduces the input byte-for-byte.
  return `${lead}${rendered.join('')}`
}

/**
 * Normalise a caller's override input into `Map<rowId, boolean>`.
 *
 * A `Map` is the real input: only ids present in it are touched. A `Set` or
 * array is accepted as shorthand for "these ids are off" (which is what a test
 * or a quick script usually wants), and an empty one therefore means "leave
 * every row exactly as shipped".
 */
function normaliseOverrides(input) {
  const disabled = new Map()
  // Shorthand first: an array or Set means "these ids are off". Checked before
  // the object branch because an array IS an object.
  if (input instanceof Set || Array.isArray(input)) {
    for (const id of input) disabled.set(id, true)
    return disabled
  }
  // Encoding form: true means ENABLED, matching the settings checkbox.
  const entries = input instanceof Map ? [...input.entries()] : input !== null && typeof input === 'object' ? Object.entries(input) : []
  for (const [id, enabled] of entries) {
    if (typeof enabled === 'boolean') disabled.set(id, enabled !== true)
  }
  return disabled
}

/**
 * Render a complete `agent.cordis.yml` for one base mode and switch set.
 *
 * @param {string} modeId - one of {@link BASE_MODES}.
 * @param {Map<string, boolean>|Set<string>|string[]} overrides - explicit per-row
 *   states; ids absent from a Map keep their shipped value.
 * @param {{modeName?: string, assistantId?: string}} [options] - the assistant this
 *   composition belongs to. Recorded in the header (so a stray file is traceable)
 *   and passed to the `custom_prompt` row's config, which lets the tool name the
 *   assistant it edits. Optional: every existing caller keeps working, and an
 *   assistant created before this existed keeps its old module, which ignores it.
 * @returns {string} the composition text to install.
 */
export function renderComposition(modeId, overrides, options = {}) {
  if (!BASE_MODES.some((mode) => mode.id === modeId)) throw new Error(`未知基础模式: ${modeId}`)
  const modeName = typeof options?.modeName === 'string' ? options.modeName.trim() : ''
  const assistantId = typeof options?.assistantId === 'string' ? options.assistantId.trim() : ''
  const explicit = normaliseOverrides(overrides)
  const base = readBaseComposition(modeId)
  const rewritten = applyLevel(base, true, explicit, false)
  const header = [
    '# 本文件由「自定义模式」设置页生成，请勿手工编辑——下次保存会覆盖。',
    ...modeName === '' && assistantId === ''
      ? []
      : [`# 助手: ${modeName === '' ? assistantId : modeName}${assistantId === '' ? '' : ` (${assistantId})`}`],
    `# 基础模式: ${modeId}`,
    `# 生成时间: ${new Date().toISOString()}`,
    '#',
    '# 每一行都是原样复制的官方 preset 行，只有 disable 状态会被改写；',
    '# 未被你切换过的行保持出厂状态（含 !!js 平台条件与默认关闭行）。',
    '# 生成时间戳同时用于让 agent-presets 检测到变化并重新挂载（它只比对 mtime 与 size）。',
    '',
  ].join('\n')
  // Rows this feature owns are appended after the base mode's rows, so a
  // regeneration cannot drop them.
  const extras = EXTRA_ROWS.map((extra) => setDisabled(extra.text(modeName), explicit.get(extra.id))).join('')
  return `${header}${rewritten}${extras}`
}

/**
 * The persona row this feature REQUIRES, substituted for whatever the base mode
 * ships.
 *
 * Without this substitution, regenerating from a shipped mode would restore
 * `@deepseek-ai/dsh-persona` — whose `prefix` is a static string resolved at
 * mount — and the editable prompt would silently stop working.
 */
const PERSONA_ROW = [
  '# 本模式的身份来自 prompt.md（由本插件的 prompt-reader.mjs 每步重新读取）。',
  "- id: persona",
  "  name: './prompt-reader.mjs'",
  '  config:',
  '    # Omitted `path` defaults to prompt.md beside the module.',
  '    complete: false',
  '',
].join('\n')

/**
 * Rows this feature adds on top of any base mode: preset-relative modules that
 * no shipped mode contains.
 *
 * They are always emitted, so a regeneration cannot drop them. `custom-prompt-tool`
 * is the durable editing path for sessions with no browser.
 *
 * Its text is a function of the assistant's display name because this feature now
 * manages several assistants and the tool description is what tells the model which
 * one it is editing. A module from before that change ignores `config:` entirely,
 * so the extra key is additive rather than a compatibility break.
 */
const EXTRA_ROWS = [
  {
    id: 'custom-prompt-tool',
    text: (modeName) => {
      const lines = [
        '# 无浏览器时的改提示词通道（模型工具 custom_prompt）。',
        '- id: custom-prompt-tool',
        "  name: './prompt-tool.mjs'",
      ]
      if (modeName !== '') {
        lines.push('  config:', '    # 让工具描述报出本助手的名字；不认识这一行的旧版模块会忽略它。', '    modeName: ' + yamlScalar(modeName))
      }
      lines.push('')
      return lines.join('\n')
    },
  },
]

/**
 * Quote a value into a single-line YAML scalar.
 *
 * JSON string syntax is valid YAML flow-scalar syntax, and it is the one escaping
 * this repository already trusts (`meta.mjs` writes `preset.yml` the same way): a
 * colon, a leading dash or a newline in an assistant's name must not restructure
 * the document, because a composition that stops parsing makes the mode vanish.
 *
 * @param {string} value - the raw value.
 * @returns {string} a double-quoted scalar.
 */
function yamlScalar(value) {
  return JSON.stringify(String(value).replace(/\r?\n/g, ' ').trim())
}

/** Read the base mode recorded in a generated composition, defaulting to standard. */
/**
 * Enabled rows whose plugin package cannot be resolved in **this** installation.
 *
 * Why this exists (measured): a preset with an enabled row whose package this dsh line does not ship is marked
 * broken by the platform and **silently dropped from every picker**, while the settings page keeps working — the
 * P0 an external review found. Fresh installs are safe because seeding derives the composition from the installed
 * line, but an assistant created by an **older** version keeps its old file forever (seeding never overwrites user
 * data), so this is how the page can tell the user instead of leaving them with an invisible mode.
 *
 * Relative modules (`./prompt-reader.mjs`, ours) and `cordis:` pseudo-packages (the runtime's) are skipped.
 *
 * @param {string} text - a composition.
 * @returns {Array<{id: string, name: string}>} enabled rows that cannot resolve.
 */
/**
 * Turn the named rows **off in place**, leaving every other byte of the file alone.
 *
 * Used by the "fix for this line" action: the composition a user has may contain rows this dsh line cannot
 * resolve (typically written by an older version of this plugin), and the platform then drops the whole preset
 * from every picker. Re-rendering the file from the base would also fix it — but it would silently discard any
 * row the user (or a future version) added outside the base. This edits only the offending rows.
 *
 * @param {string} text - the composition.
 * @param {Iterable<string>} ids - row ids to disable.
 * @returns {string} the rewritten composition.
 */
export function disableRowsInPlace(text, ids) {
  const wanted = [...new Set(ids)]
  if (wanted.length === 0) return text
  // `applyLevel` 把 Map 的值直接交给 `setDisabled(...)`，所以值是**关闭**布尔（true = 关闭）。
  const off = new Map(wanted.map((id) => [id, true]))
  // **只跑顶级这一遍。** 它已经通过 applyLevel 的 `isGroup` 分支递归进每个分组，
  // 所以原先跟在后面的那次扁平扫描（`applyLevel(top, false, …)`）纯属重复 —— 而且有害：
  // 它以「下一个 4 空格 `- id:`」界定片段，于是**分组的最后一个子行会吞掉后续的顶层内容**
  // （包括下一个分组的 `name:` 头），`ownKeyIndent` 随即算出缩进 2，把 `disabled` 写进了
  // **下一个分组**。实测（2026-09-21，4 个模式 × 每个行 id 全扫）：连带损害 7 例
  // （关 plan-mode 会连带关掉整个 compaction 分组；关 tool-result-pruner 会连带关掉整个
  // delegation 分组）、行数 Δ=+2 的结构异常 6 例、以及若干"目标行根本没被改到"的空操作。
  // 删掉这一遍之后：连带 0 例、结构异常 0 例，正确生效 81 → 87。
  return applyLevel(text, true, off, false, false)
}

/**
 * Module names this installation actually has, injected by the host half.
 *
 * Why this exists: {@link unresolvableRows} decides "can this line run here?" by walking the install's
 * `node_modules`. That works on the file-scanning dsh line, but **0.1.7 has no `dsh-agent-presets`
 * package and therefore no directory for `shippedPresetsDir()` to find** — the lookup threw, the catch
 * returned `[]`, and the check silently stopped checking anything. On that line the host half has a
 * better source: `agentPresets.compositionInventory()` reports every row of every registered preset with
 * its `moduleName`, which is exactly this set.
 *
 * When a set is injected it **replaces** the filesystem probe (it is strictly better information on the
 * line that needs it). `null` means "not injected" — legacy behaviour.
 */
let knownModuleNames = null

/** @param {Iterable<string>|null} names - module specifiers known to be installable here. */
export function setKnownModuleNames(names) {
  knownModuleNames = names === null || names === undefined ? null : new Set(names)
}

/** The injected set, or null when the filesystem probe is in charge. Exported for tests/diagnostics. */
export function knownModuleNamesInjected() {
  return knownModuleNames
}

export function unresolvableRows(text) {
  /** 可能装着生态包的那些 `node_modules`（等价于根，见下面的嵌套形态）。 */
  let roots = []
  let fromFilesystem = false
  if (knownModuleNames === null) {
    let root
    try {
      root = join(shippedPresetsDir(), '..', '..', '..')
      fromFilesystem = true
    } catch {
      return []
    }
    // **判断不了就不要报警**：如果这个根下根本没有 node_modules（例如测试用的是一个临时出厂目录），
    // 那么"查不到某个包"只说明我们不知道，不说明那行坏了。误报的代价是用户被引导去关掉本来正常的行。
    if (existsSync(join(root, '@deepseek-ai')) === false) return []
    roots = [root]
    // ★ 依赖**嵌套**安装时（实测：npm 装 @deepseek-ai/dsh@0.1.7-rc.2，81 个生态包落在
    //   `node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/…` 而不是仓库根），只看根会把
    //   **每一行**都报成"本机装不了" —— 一个把健康预设说成 broken、并引导用户关掉正常行的假警报。
    //   所以把根下每个包自己的 node_modules 也算进去。
    try {
      for (const entry of readdirSync(join(root, '@deepseek-ai'))) {
        const nested = join(root, '@deepseek-ai', entry, 'node_modules')
        if (existsSync(join(nested, '@deepseek-ai'))) roots.push(nested)
      }
    } catch {
      /* 读不了目录就只用根，与从前一致 */
    }
  }
  const out = []
  const lines = text.split('\n')
  for (let index = 0; index < lines.length; index += 1) {
    const row = /^( {0,4})- id: (.+?)\s*$/.exec(lines[index])
    if (row === null) continue
    const indent = row[1].length
    // 这一行**自己的键**所在的列（`- id:` 再进两格）。只有这一列上的 `name:` / `disabled:`
    // 才属于它 —— 否则会读到子树里的键。
    const keyIndent = indent + 2
    let name
    let disabled = false
    for (let next = index + 1; next < lines.length; next += 1) {
      const line = lines[next]
      if (line.trim() === '') continue
      const column = line.search(/\S/)
      if (column <= indent) break
      // **跳过比自己深的行**（子行的键、子行的键的子行……）。
      // 原先这里没有这一层过滤，于是顶层分组会一路走完整个子树，`name` 被**最后一个子行**覆盖：
      // 一个健康的分组因此被报成"解析不了"（外部实测：`grp` 被报成坏行，name 取自 child-last），
      // 后果是页面点名它、启动日志喊它、而"按本线修复"会**关掉整个分组**。
      if (column !== keyIndent) continue
      if (/^name: ['"]?(.+?)['"]?\s*$/.test(line.slice(keyIndent))) name = /^name: ['"]?(.+?)['"]?\s*$/.exec(line.slice(keyIndent))[1]
      // 平台条件行（`disabled: !!js …`）必须**求值**判定：只看有没有字面 `true` 会把"本平台已启用"的行
      // 误判成关闭，也会把"本平台本来就关闭"的行（如 Windows 专用的 pwsh）误报成"无法解析"。
      const flag = /^disabled:\s*(.+?)\s*$/.exec(line.slice(keyIndent))
      if (flag !== null) {
        // 字面量直接读，`!!js` 一类交给求值器（它对字面量不做布尔化）。
        const raw = flag[1].replace(/^['"]|['"]$/g, '')
        const evaluated = evalDisabledExpression(flag[1])
        const failedJs = flag[1].trim().startsWith('!!js') && evaluated === undefined
        disabled = raw === 'true' ? true : raw === 'false' ? false : failedJs || evaluated === true
      }
    }
    if (name === undefined || disabled) continue
    if (name.startsWith('.') || name.startsWith('cordis:')) continue
    const parts = name.split('/')
    const pkg = name.startsWith('@') ? parts.slice(0, 2).join('/') : parts[0]
    // 注入了集合就用集合（新线：来自 compositionInventory 的 moduleName）——
    // 子路径说明符（`@scope/pkg/sub`）与它的包名都算命中，否则会把健康行误报成坏行。
    const present = fromFilesystem
      ? roots.some((each) => existsSync(join(each, pkg)))
      : knownModuleNames.has(name) || knownModuleNames.has(pkg)
    if (present === false) out.push({ id: row[2], name })
  }
  return out
}

export function modeOf(text) {
  const match = /^# 基础模式: (\S+)\s*$/m.exec(text)
  const id = match === null ? undefined : match[1]
  return BASE_MODES.some((mode) => mode.id === id) ? id : 'standard'
}

/** Flatten a row tree into `Map<id, disabled>`. */
function flattenRows(rows, into = new Map()) {
  for (const row of rows) {
    // 重复 id 只认文档顺序的第一处，与 applyLevel 的写入方向一致。
    if (into.has(row.id) === false) into.set(row.id, row.disabled)
    flattenRows(row.children, into)
  }
  return into
}

/** 同 {@link flattenRows}，但保留整行对象：判断"未触碰"要看**文本形态**，不只是求值结果。 */
function flattenRowObjects(rows, into = new Map()) {
  for (const row of rows) {
    if (into.has(row.id) === false) into.set(row.id, row)
    flattenRowObjects(row.children, into)
  }
  return into
}

/**
 * Derive the explicit overrides that turned `base` into `text`.
 *
 * The composition file is the single source of truth: instead of keeping a
 * separate settings document that can drift, the page recomputes which rows the
 * user has deviated from by diffing against the same base mode. A row whose
 * state matches the shipped one is simply "not overridden", which is what keeps
 * its `!!js` platform condition intact.
 *
 * @param {string} text - the installed composition.
 * @param {string} modeId - its base mode.
 * @returns {Record<string, boolean>} row id -> enabled.
 */
export function overridesOf(text, modeId) {
  const base = flattenRowObjects(collectRows(readBaseComposition(modeId)))
  for (const extra of EXTRA_ROWS) base.set(extra.id, { id: extra.id, disabled: false, disabledExpression: null })
  const current = flattenRowObjects(collectRows(text))
  const overrides = {}
  for (const [id, row] of current) {
    const baseRow = base.get(id)
    // "未触碰"的判据是**文本形态相同**：出厂行可能是 `!!js` 平台表达式，而它在本机的求值结果恰好与
    // 某个字面量一致 —— 只看求值结果，会把"表达式被换成字面量"误判成"没动过"。
    const sameForm = baseRow !== undefined
      && baseRow.disabled === row.disabled
      && baseRow.disabledExpression === row.disabledExpression
    if (sameForm) continue
    overrides[id] = row.disabled !== true
  }
  return overrides
}

/**
 * 按允许名单生成显式开关。
 *
 * 只写下和出厂状态不同的行，这样没被点名的行仍保持 `!!js` 平台条件。名单里没有、而出厂是开的行，
 * 写成关闭。上游以后新增的行不在名单里，因此对「写作 / 聊天」默认关闭。
 *
 * @param {string} modeId - one of {@link BASE_MODES}.
 * @param {string[]} allowIds - 希望保持可用的行 id。`persona` 与 `custom-prompt-tool` 总会被加进去。
 * @returns {Map<string, boolean>}
 */
export function allowlistOverrides(modeId, allowIds) {
  const allow = new Set(Array.isArray(allowIds) ? allowIds : [])
  allow.add('persona')
  allow.add('custom-prompt-tool')
  const rows = flattenRowObjects(collectRows(readBaseComposition(modeId)))
  for (const extra of EXTRA_ROWS) {
    if (rows.has(extra.id) === false) {
      rows.set(extra.id, { id: extra.id, disabled: false, disabledExpression: null })
    }
  }
  const overrides = new Map()
  for (const [id, row] of rows) {
    const want = allow.has(id)
    const shippedOn = row.disabled !== true
    if (want !== shippedOn) overrides.set(id, want)
  }
  return overrides
}

/**
 * Rows this feature always adds, for the settings UI to show alongside the base
 * mode's own rows.
 */
export function extraRowIds() {
  return EXTRA_ROWS.map((extra) => extra.id)
}

/**
 * 把一组开关调到**每组互斥里只剩一侧启用** —— 保存路径上的「自动互斥」。
 *
 * 为什么必须做这一步（实测 2026-10-01，`0.2.0-rc.2`）：两套壳注册同名工具，同时启用会让挂载方把整个
 * 预设判为 **broken**，而 broken 的预设被从所有选择器里静默丢掉 —— 设置页却全绿。用户可以容忍开关
 * 自己动一下，不能容忍模式凭空消失，所以这里替他做掉那一步，而不是事后报错。
 *
 * 判定的是**生效状态**（显式开关优先，否则取该基础模式的出厂态），不是"请求里带了什么"：
 * 用户没碰过的行也该参与互斥判断。
 *
 * 保留哪一侧，按这个次序：
 *  1. 这一次**被显式打开**的那一侧 —— 用户要的就是它（正常情况就落在这里）；
 *  2. 同一次请求里显式打开了多侧（或都没显式开）⇒ 退回避让 `unionDefaultOff` 的那一侧。
 *
 * 只把**当前确实启用着**的行写成 `false`。给本来就关着的行写一遍，会在 `overridesOf` 里变成一条
 * 用户从未做过的"改动"，历史和往返都会说谎。
 *
 * @param {string} modeId - one of {@link BASE_MODES}.
 * @param {Map<string, boolean>} overrides - `rowId → enabled`，会被当作不可变输入。
 * @returns {{overrides: Map<string, boolean>, moved: string[]}} 调整后的开关，以及被自动关掉的行 id。
 * @throws {BaseCompositionUnavailableError} 取不到出厂组成时（调用方走既有的降级路径）。
 */
export function applyRowExclusivity(modeId, overrides) {
  const rows = flattenRowObjects(collectRows(readBaseComposition(modeId)))
  const effective = (id) => {
    if (overrides.has(id)) return overrides.get(id) === true
    const row = rows.get(id)
    return row === undefined ? false : row.disabled !== true
  }
  const next = new Map(overrides)
  const moved = []
  for (const set of EXCLUSIVE_ROW_SETS) {
    const live = set.sides.filter((side) => side.rows.some((id) => effective(id)))
    if (live.length <= 1) continue
    const explicit = live.filter((side) => side.rows.some((id) => overrides.get(id) === true))
    const keep =
      explicit.length === 1
        ? explicit[0]
        : live.find((side) => side.rows.some((id) => set.unionDefaultOff.includes(id)) === false) ?? live[0]
    for (const side of live) {
      if (side === keep) continue
      for (const id of side.rows) {
        if (effective(id)) {
          next.set(id, false)
          moved.push(id)
        }
      }
    }
  }
  return { overrides: next, moved }
}

/**
 * 一份**已经写在磁盘上**的组成有没有踩着互斥（两条路同时启用）。
 *
 * 保存路径已经自动避让了，所以这条只可能由三种情况命中：手工编辑、直接调 HTTP API、或者上一次是用
 * 本插件之外的什么东西写的。它们的共同后果都是**模式从选择器里静默消失**，所以必须被点名而不是沉默。
 *
 * @param {string} text - an installed composition.
 * @returns {string[]} 同时启用了多侧的互斥组 id。
 */
export function exclusiveSetsActive(text) {
  const rows = flattenRowObjects(collectRows(text))
  return EXCLUSIVE_ROW_SETS.filter((set) => {
    const on = set.sides.filter((side) =>
      side.rows.some((id) => {
        const row = rows.get(id)
        return row !== undefined && row.disabled !== true
      }),
    )
    return on.length > 1
  }).map((set) => set.id)
}
