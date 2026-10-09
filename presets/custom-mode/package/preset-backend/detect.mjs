/**
 * 能力探测：判定当前 dsh 线用的是哪一种 agent-preset 机制。
 *
 * ## 为什么按能力探测，而不是按版本号
 *
 * preset 机制在 0.1.7 被整体换过（见 notes/2026-09-22-双线适配设计.md §1）：
 *
 *   0.1.2 … 0.1.6   `@deepseek-ai/dsh-agent-presets`（复数）—— **扫目录** `$DSH_HOME/.agent-presets/`，
 *                   服务带读改删三件套 `read` / `copy` / `remove`，`list()` 的行带 `trust` 与 `path`。
 *   0.1.7 +         `@deepseek-ai/dsh-agent-preset`（单数）+ `-registry` —— **声明式注册表**，
 *                   不扫目录，服务有 `register` / `compositionInventory` / `select`，但**没有** remove。
 *
 * 0.1.7 仍在 alpha（已到 alpha.2），接口还会动。按版本号写分支会在下一次发布时**静默失配**，
 * 而按能力探测天然面向未来。
 *
 * ## 判据的选取
 *
 * 主判据是 **`remove` 的有无**：它是"文件系统语义"的直接标志 —— 旧线能删磁盘上的 preset 目录，
 * 新线的定义在内存里、只能靠 `register()` 返回的 disposer 注销。
 * 辅以 `register` / `compositionInventory` 作交叉验证；**信号矛盾时不猜**，落到只读降级态并显式报告。
 */

/** 后端标识。字符串而不是布尔，便于日志与测试断言。 */
export const BACKEND_LEGACY = 'legacy-dir'
export const BACKEND_DECLARATIVE = 'declarative'
export const BACKEND_UNUSABLE = 'unusable'

/**
 * Probe the agent-preset service and decide which backend to drive it with.
 *
 * @param {{agentPresets?: object}} scope - the scoped context carrying `agentPresets`.
 * @returns {{
 *   id: string,
 *   capabilities: {list: boolean, register: boolean, remove: boolean, copy: boolean, read: boolean,
 *                  inventory: boolean, select: boolean},
 *   reasons: string[],
 *   conflict: boolean,
 * }}
 */
export function detectPresetBackend(scope) {
  const svc = scope?.agentPresets
  const capabilities = {
    list: typeof svc?.list === 'function',
    register: typeof svc?.register === 'function',
    remove: typeof svc?.remove === 'function',
    copy: typeof svc?.copy === 'function',
    read: typeof svc?.read === 'function',
    inventory: typeof svc?.compositionInventory === 'function',
    select: typeof svc?.select === 'function',
    // 出厂组成的来源：旧线读文件，新线由这个 API 交出同一份声明（缺了它设置页仍可用，但
    // 基础模式与插件开关必须降级为不可编辑 —— 所以它是**可选**能力，不是必需）。
    document: typeof svc?.readDocument === 'function',
  }
  const reasons = []

  // 核心前提：没有 list() 就既读不到 roster，也没法判断任何事。
  if (!capabilities.list) {
    reasons.push('agentPresets.list() 不可用 —— 无法读取 preset roster')
    return { id: BACKEND_UNUSABLE, capabilities, reasons, conflict: false }
  }

  // 旧线的标志：文件系统语义齐全（remove 是其中最不会误报的一个 —— 新线确实没有它）。
  const looksLegacy = capabilities.remove
  // 新线的标志：声明式注册面存在。
  const looksDeclarative = capabilities.register && capabilities.inventory

  if (looksLegacy && looksDeclarative) {
    // 两条线的特征同时出现：可能 upstream 把两套并了，也可能是我们判错了。
    // **不猜** —— 显式报告并走只读，避免用错后端写坏用户的 preset。
    reasons.push(
      '同时探测到两套机制的特征（既有 remove()，又有 register() + compositionInventory()）。' +
        '这可能是 dsh 换用了一套兼容层；本插件不在这种情况下写入任何 preset，' +
        '只做只读展示。请提 issue 并附上 dsh --version。',
    )
    return { id: BACKEND_UNUSABLE, capabilities, reasons, conflict: true }
  }

  if (looksLegacy) {
    reasons.push('agentPresets.remove() 存在 —— 文件系统语义的旧线（扫 $DSH_HOME/.agent-presets/）')
    return { id: BACKEND_LEGACY, capabilities, reasons, conflict: false }
  }

  if (looksDeclarative) {
    reasons.push(
      'agentPresets.remove() 不存在，但 register() + compositionInventory() 存在 —— 声明式注册表的新线' +
        (capabilities.document
          ? '（readDocument() 可用：出厂组成由宿主交出）'
          : '（**readDocument() 不可用**：基础模式与插件开关将降级为不可编辑）'),
    )
    return { id: BACKEND_DECLARATIVE, capabilities, reasons, conflict: false }
  }

  reasons.push(
    '既没有 remove()（旧线的文件系统语义），也没有 register() + compositionInventory()（新线的声明式注册表）。' +
      '这不像任何一条已知的 dsh 线。',
  )
  return { id: BACKEND_UNUSABLE, capabilities, reasons, conflict: false }
}

/** 人类可读的一行摘要，供启动日志使用。 */
export function describeBackend(verdict) {
  const on = Object.entries(verdict.capabilities)
    .filter(([, v]) => v)
    .map(([k]) => k)
  const off = Object.entries(verdict.capabilities)
    .filter(([, v]) => !v)
    .map(([k]) => k)
  return `backend=${verdict.id} · 有: ${on.join(',') || '(无)'} · 无: ${off.join(',') || '(无)'}`
}
