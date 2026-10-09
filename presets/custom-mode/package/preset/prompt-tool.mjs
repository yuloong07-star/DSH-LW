/**
 * Model-facing row for a custom agent preset: read and rewrite the system
 * prompt this preset uses.
 *
 * One copy of this file lives inside EVERY assistant this feature creates; each
 * copy resolves `prompt.md` relative to ITS OWN location, so N assistants edit N
 * independent prompt files with no cross-talk. The assistant's display name comes
 * from the composition row's `config.modeName` (written by the settings page on
 * every save) and falls back to reading `preset.yml` beside this module, then to a
 * generic label — an older assistant's copy of this module ignores the config
 * entirely, which is why the fallback chain has to work without it.
 *
 * This exists because the graphical editor in Settings is a dynamic Cordis
 * plugin and therefore vanishes when the process restarts. The prompt FILE and
 * this preset survive; this row is what makes the prompt durably editable
 * without any ephemeral UI — in a fresh session you can simply say
 * "把系统提示词改成……" and the agent calls this tool.
 *
 * It writes the same file `prompt-reader.mjs` reads, so a write here takes
 * effect on the next model step of the current session, exactly like a save
 * from the Settings page.
 *
 * Scope: registered in the preset's own scope via `ctx.tools.register`, which
 * is how every shipped tool row contributes. It provides no service, so it
 * needs no isolate realm.
 */

import { dirname, join } from 'node:path'
import { randomBytes } from 'node:crypto'
import { mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/** Absolute path of the prompt file this preset reads. */
const PROMPT_PATH = fileURLToPath(new URL('./prompt.md', import.meta.url))

/** Absolute path of this preset's display metadata, used only to name the tool. */
const META_PATH = fileURLToPath(new URL('./preset.yml', import.meta.url))

/** Missing-file text the model gets, so a read never looks like an empty prompt. */
const MISSING = '（prompt.md 不存在，当前模式会退回上一次成功的提示词文本）'

/** Name used when neither the composition nor `preset.yml` supplies one. */
const TOOL_NAME = 'custom_prompt'
const FALLBACK_MODE_NAME = '自定义模式'

/**
 * Variable names the prompt renderer accepts, mirroring
 * `VARIABLE_NAME = /^[a-z][a-z0-9_]*$/` in `@deepseek-ai/dsh-system-prompt`.
 */
const VARIABLE_NAME = /^[a-z][a-z0-9_]*$/

/**
 * Variables this deployment registers for every agent (`dsh-agent-loop`).
 *
 * The persona section renders with strict interpolation: an unknown `{{name}}`
 * or a malformed group makes the renderer THROW, so one typo here would fail
 * every model request in this mode. Refusing it at the write is the guard.
 *
 * This mirrors `checkPromptText` in the editor package's `index.mjs`, which
 * guards the Settings-page write path to the same file. The two must stay in
 * step; the duplication is deliberate so neither side depends on the other's
 * install location.
 */
const KNOWN_VARIABLES = ['model', 'cwd', 'provider']

/**
 * Check prompt text against the renderer's interpolation rules.
 *
 * A complete `{{...}}` group must hold a valid, registered name; a lone `{{`
 * with no later `}}` is literal prose and passes.
 *
 * @param {string} text - the candidate prompt text.
 * @returns {{ok: true} | {ok: false, error: string}} the verdict.
 */
function checkPromptText(text) {
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
        error:
          '写入被拒绝：{{' +
          variable +
          '}} 不是合法的变量引用（合法名只能用小写字母、数字、下划线且以字母开头）。' +
          '若只想要字面量花括号，请用单个 { 或不闭合的 {{。',
      }
    }
    if (!KNOWN_VARIABLES.includes(variable)) {
      return {
        ok: false,
        error:
          '写入被拒绝：{{' +
          variable +
          '}} 不是已注册的变量，渲染时会报错并让本模式每个请求都失败。可用：' +
          KNOWN_VARIABLES.map((item) => '{{' + item + '}}').join('、') +
          '。',
      }
    }
    index = close + 2
  }
}

/**
 * The assistant's display name, for the tool description the model reads.
 *
 * Three sources, best first: the composition row's config (authoritative, written
 * on every settings-page save), this directory's `preset.yml` (what the pickers
 * show), and a generic label. Never throws — a tool description is not worth
 * failing a registration over.
 *
 * @param {object} [config] - the row's `config:` block.
 * @returns {string} a non-empty display name.
 */
function resolveModeName(config) {
  const fromConfig = config !== null && typeof config === 'object' && typeof config.modeName === 'string'
    ? config.modeName.replace(/\r?\n/g, ' ').trim()
    : ''
  if (fromConfig !== '') return fromConfig
  try {
    for (const line of readFileSync(META_PATH, 'utf8').split('\n')) {
      if (!line.startsWith('name:')) continue
      const value = line.slice('name:'.length).trim().replace(/^["']|["']$/g, '').trim()
      if (value !== '') return value
    }
  } catch {
    /* no metadata is the normal case for a hand-made preset */
  }
  return FALLBACK_MODE_NAME
}

/**
 * The tool definition, bound to one assistant's name.
 *
 * @param {string} modeName - the display name to report.
 */
function makeDefinition(modeName) {
  return {
    name: 'custom_prompt',
    description:
      'Read, replace or append to the system prompt of the 「' +
      modeName +
      '」 custom agent preset. ' +
      'The prompt is a plain file; this tool reads it (action "read", the default), ' +
      'overwrites it wholesale (action "write" with text) or adds text at the end ' +
      '(action "append" with text — one call, instead of reading the whole prompt and ' +
      'writing it back to add a line). Any change takes effect ' +
      "on this session's next model step — no restart needed — and only affects " +
      'sessions running this preset. Both "write" and "append" ask the user for approval ' +
      'first. Use it when the user asks to change their system prompt, to remember a rule, ' +
      'or wants to see what it currently says.',
    parameters: {
      type: 'object',
      properties: {
        action: {
          type: 'string',
          enum: ['read', 'write', 'append'],
          description: 'Read the current prompt (default), replace it, or append text to it.',
        },
        text: {
          type: 'string',
          description:
            'Required when action is "write" (the complete new prompt) or "append" (the text to add at the end).',
        },
      },
    },
    output: {
      schema: { type: 'string' },
      render(_args, value) {
        return [{ type: 'text', text: String(value) }]
      },
    },
    async execute(args) {
      const action = args !== null && typeof args === 'object' && typeof args.action === 'string'
        ? args.action
        : 'read'

      if (action === 'read') {
        try {
          return '当前系统提示词（' + PROMPT_PATH + '）：\n\n' + readFileSync(PROMPT_PATH, 'utf8')
        } catch {
          return MISSING
        }
      }

      if (action === 'append') {
        if (typeof args.text !== 'string' || args.text.trim() === '') {
          return '追加被拒绝：action 为 "append" 时必须提供非空的 text。'
        }
        let current = ''
        try {
          current = readFileSync(PROMPT_PATH, 'utf8')
        } catch {
          // 文件不存在就当作从空开始：读取器本来就对缺失文件有回退，追加没有理由不工作。
        }
        // 分隔：保证两块之间恰好一个换行，不在文件里堆空行。
        const base = current === '' || current.endsWith('\n') ? current : current + '\n'
        const added = args.text.endsWith('\n') ? args.text : args.text + '\n'
        const combined = base + added
        // 校验的是**合并之后**的完整文本：追加同样不能把未注册变量带进文件。
        const verdict = checkPromptText(combined)
        if (verdict.ok !== true) return '追加被拒绝（合并后的提示词未通过校验）：' + verdict.error
        try {
          mkdirSync(dirname(PROMPT_PATH), { recursive: true })
          writeAtomic(PROMPT_PATH, combined)
          return (
            '已追加到 ' + PROMPT_PATH + '（新增 ' + String(added.length) + ' 字符，现共 ' +
            String(combined.length) + ' 字符）。本会话下一步模型调用即使用新提示词。'
          )
        } catch (error) {
          return '追加失败：' + String((error && error.message) || error)
        }
      }

      // **显式白名单，不再靠"落到最后就当写入"。** 这段代码到这儿只剩整体替换，此前它对任何
      // 不认识的 action（`replace` / `Write` / `overwrite` / 空串 / `append ` 末尾多一个空格）
      // 都静默照做，而审批闸门只认识 write/append —— 一个词的拼写差异就能把用户的提示词整体
      // 换掉且不弹审批（外部评审实测）。不认识的动词现在一律拒绝并说明，模型据此改对再调。
      if (action !== 'write') {
        return (
          '写入被拒绝：未知的 action「' + action + '」。只接受 read / write / append' +
          '（未指定 action 按 read 处理）；append 必须精确拼写，末尾多一个空格不算。'
        )
      }

      if (typeof args.text !== 'string' || args.text.trim() === '') {
        return '写入被拒绝：action 为 "write" 时必须提供非空的 text。'
      }
      const verdict = checkPromptText(args.text)
      if (verdict.ok !== true) return verdict.error
      try {
        mkdirSync(dirname(PROMPT_PATH), { recursive: true })
        // 与设置页同一条纪律：临时文件 + rename。读取器（prompt-reader.mjs）按 mtime+size 缓存，
        // 非原子写会让它有机会读到写了一半的提示词 —— 设置页早已改用原子写，这里原先还是
        // 直接 writeFileSync，属于"自我标准不一致"。
        writeAtomic(PROMPT_PATH, args.text)
        return '已写入 ' + PROMPT_PATH + '（' + String(args.text.length) + ' 字符）。本会话下一步模型调用即使用新提示词。'
      } catch (error) {
        return '写入失败：' + String((error && error.message) || error)
      }
    },
  }
}

/**
 * 自我改提示词的审批闸门。
 *
 * `action: "write"` 会**覆盖整个系统提示词**，而调用它的可能是模型自己 —— 一次提示词注入就足以
 * 让它重写自己的身份。此前这条路径没有任何门：工具直接落盘。
 *
 * 现在它走平台的审批缝：`tools/pre-execute` 是一个 waterfall，返回 `{kind:'ask'}` 会由审批策略决定
 * 怎么处理。**实测（0.1.6-alpha.2，一次性实例）**：
 *
 *   - 审批策略为 `ask`（权限预设 `workspace-write`）：页面出现「等待审批」行，带这里的 reason，
 *     按钮为「拒绝 / 允许一次」；点「允许一次」后工具真的执行，prompt.md 变成写入的内容；
 *   - 审批策略为 `never`（权限预设 `danger-full-access`）：**不弹窗，直接判为拒绝**
 *     （轨迹里是 `Error: the user rejected tool "custom_prompt"`，文件未被修改）。
 *
 * 也就是说这条闸门**最坏情况是"改不成"而不是"悄悄改成了"** —— 与平台的默认语义一致
 * （类型注释原话：missing approval support turns `ask` into denial）。
 *
 * 只拦 `write`：`read` 不改变任何东西，弹窗只会让人麻木。
 *
 * @param {object} ctx - the preset row's scope.
 * @returns {boolean} whether a gate was registered.
 */
function registerApprovalGate(ctx, config) {
  if (typeof ctx.on !== 'function') return false
  ctx.effect(
    () => ctx.on('tools/pre-execute', (exec, next) => {
      if (exec === null || typeof exec !== 'object' || exec.name !== TOOL_NAME) return next()
      const args = exec.arguments
      // **只放行真正的读取。** 未指定 action 与 execute 一样按 read 处理；其余一律发问。
      //
      // 此前这里只拦 `write` / `append` 两个字面量，而 execute 把"既不是 read 也不是 append"的
      // **任何** action 都当成整体替换 —— 于是 `replace` / `Write` / 空串 / 末尾多一个空格都能
      // 绕过审批面板直接改掉用户的提示词（外部评审实测复现）。闸门要盖住"一切可能改文件的调用"，
      // 不能只认识它恰好想到的那两个词。execute 那边同时加了显式白名单，两层各自独立。
      const raw = args !== null && typeof args === 'object' ? args.action : undefined
      // 只有"没提供 action"才等同 read（execute 也是这样兜的）；**提供了但不认识**（含空串）
      // 一律发问 —— 闸门的判据是"这次调用会不会改文件"，不是"这个动词我认不认识"。
      const action = typeof raw === 'string' ? raw : 'read'
      if (action === 'read') return next()
      const verb = action === 'append' ? '追加到' : '整体替换为'
      const text = typeof args.text === 'string' ? args.text : ''
      const firstLine = text.split('\n').find((line) => line.trim() !== '') ?? ''
      return {
        kind: 'ask',
        // **双语**：这是安全决策界面 —— 用哪种界面语言的用户都必须读懂自己要批准什么。
        // （页面其它文案走词典，但审批面板由平台渲染，插件侧拿不到当前界面语言。）
        reason:
          '把「' + resolveModeName(config) + '」的系统提示词' + verb + ' ' + String(text.length) + ' 字符' +
          (firstLine === '' ? '' : '：' + firstLine.trim().slice(0, 60)) +
          '（写入 ' + PROMPT_PATH + '）' +
          " ／ Change this mode's system prompt: " + (action === 'append' ? 'append ' : 'replace with ') +
          String(text.length) + ' characters (writes ' + PROMPT_PATH + ')',
      }
    }),
    'custom-prompt.approval-gate',
  )
  return true
}

/**
 * Marker file the host half reads to surface a MISSING approval gate in the settings page.
 *
 * The gate lives on the preset side, which is loaded per session, so the host half cannot see whether it
 * registered. A review called the previous behaviour ("only console.error") a silent gap; this turns it into a
 * visible warning. The marker is removed as soon as registration succeeds.
 */
export const GATE_MARKER = 'approval-gate-missing'

/** The tool registry is a hard dependency; without it there is no tool. */
export const inject = ['tools']

export function apply(ctx, config = {}) {
  const definition = makeDefinition(resolveModeName(config))
  ctx.effect(() => ctx.tools.register(definition), 'custom-prompt.tool')

  // 审批闸门。宿主若不支持 `tools/pre-execute`（比本插件声明的下限还老的构建），这里会**明确**
  // 说一声再继续 —— 降级是有的，但不许静默。
  const gateReady = registerApprovalGate(ctx, config) === true
  try {
    if (gateReady) rmSync(join(dirname(PROMPT_PATH), GATE_MARKER), { force: true })
    else writeFileSync(join(dirname(PROMPT_PATH), GATE_MARKER), 'this host has no tools/pre-execute event\n', 'utf8')
  } catch {
    /* 标记写不进去不能影响会话；下面的 console.error 仍然是兜底 */
  }
  if (gateReady !== true) {
    console.error(
      'custom-mode: 这个宿主没有 tools/pre-execute 事件，会话内改写系统提示词的审批闸门**未启用**' +
        '（设置页不受影响）。请升级 DSH，或把「custom_prompt 工具」这一行关掉。',
    )
  }
}

/**
 * 写文件：同目录临时文件 + rename（rename 在同一文件系统内原子）。
 *
 * 临时名带 pid 与随机后缀：同一进程内的并发写必须各用各的临时名，否则 Windows 上两个
 * rename 指向同一目标会以 EPERM 失败。
 */
/**
 * `rename` 带重试 —— 与设置页同一条纪律（原先这里只有裸 rename，注释却自称"同一条纪律"，审阅点名）。
 * Windows 上并发改名到同一目标会短暂 EPERM/EBUSY；退避几毫秒即可跨过。
 */
function renameWithRetry(from, to, attempts = 8) {
  for (let attempt = 1; ; attempt += 1) {
    try {
      renameSync(from, to)
      return
    } catch (error) {
      const code = error !== null && typeof error === 'object' ? error.code : undefined
      const retryable = code === 'EPERM' || code === 'EBUSY' || code === 'EACCES'
      if (retryable !== true || attempt >= attempts) throw error
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 20 * attempt)
    }
  }
}

function writeAtomic(file, text) {
  const temporary = `${file}.tmp-${String(process.pid)}-${randomBytes(4).toString('hex')}`
  try {
    writeFileSync(temporary, text, 'utf8')
    renameWithRetry(temporary, file)
  } catch (error) {
    try {
      rmSync(temporary, { force: true })
    } catch {
      /* 不掩盖原始错误 */
    }
    throw error
  }
}
