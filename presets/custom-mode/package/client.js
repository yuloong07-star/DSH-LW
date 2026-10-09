/**
 * Browser half: 「自定义模式」settings page — the assistant manager.
 *
 * Blocks:
 *   0. 助手        — every custom mode this feature manages: pick one, create one, duplicate one, delete one.
 *   1. 模式名称    — the selected assistant's display name and description.
 *   2. 基础模式    — standard / ptc / minimal / cordis, per assistant.
 *   3. 插件开关    — one switch per composition row; groups show their children.
 *   4. 系统提示词  — the selected assistant's editable prompt text.
 *
 * Three decisions worth knowing before editing this file:
 *
 *  1. **Controls come from the shell, not from here.** `@deepseek-ai/dsh-client-ui-primitives`
 *     is in the shell's seed table (`require` resolves it like `react`), so Button / Input /
 *     Switch / Tag / Pill / RiskConfirmation / icons are the same atoms every official page
 *     uses. They are styled by the shell's own CSS through `--dsw-*` tokens, which is what makes
 *     this page follow the theme, density and future restyling instead of drifting from it.
 *     The atoms are probed, never assumed: a shell that does not seed the module falls back to
 *     plain elements below, so an older dsh gets a plainer page rather than a missing one.
 *
 *  2. **Drafts are per assistant and never discarded by switching.** `entries[id]` holds the
 *     loaded payload, the saved baseline and the current value; selecting another assistant
 *     keeps the old draft, and the list marks it 「未保存」. Losing a hand-written system prompt
 *     to a stray click is the one failure this page must not have.
 *
 *  3. **Deleting goes through the shell's RiskConfirmation.** It removes a directory and a
 *     prompt permanently, so it asks for an explicit acknowledgement instead of a second click
 *     next to the primary button.
 *
 * Hand-written in the client bundle format the module system expects
 * (`window.__ModuleLoader__.load({ id, factory })`). It talks to its host half over one private
 * HTTP route and its sub-paths with `fetch`, so it needs no Remote namespace.
 *
 * Failure policy: everything is wrapped so a broken editor can never take the page down. Worst
 * case is that this settings page does not appear.
 */

try {
  window.__ModuleLoader__.load({
    id: "dsh-custom-mode",
    factory: (require) => {
      var module = { exports: {} }
      var exports = module.exports
      Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" })

      const react = require("react")

      // 路由注册在平台的共享 `/api` 频道上（由载体在分发前施加信任与鉴权），
      // 所以这里的路径必须带 `/api` 前缀。
      const ROUTE = "/api/custom-mode"

      const NS = "settings.customMode"

      /** The shell's shared atom library. See decision 1 in the module comment. */
      const ATOMS_MODULE = "@deepseek-ai/dsh-client-ui-primitives"

      /**
       * Copy for this page, generated from locales.mjs (single source of
       * truth, checked for key parity by test/locales.test.mjs).
       */
      const ZH = {
        "nav": "自定义模式",
        "assistant.heading": "助手",
        "assistant.hint": "每个助手单独保存系统提示词、基础模式和插件开关。",
        "posture.hint": "只用于新建，不改变当前助手。",
        "posture.develop.label": "开发",
        "posture.develop.note": "新建后：标准模式，工具按出厂。",
        "posture.write.label": "写作",
        "posture.write.note": "新建后：终端关闭，文件读写打开。",
        "posture.chat.label": "聊天",
        "posture.chat.note": "新建后：文件和终端关闭。",
        "assistant.empty": "还没有助手，用下面的输入框新建一个。",
        "assistant.loadingList": "正在读取助手列表…",
        "assistant.newPlaceholder": "新助手的名字（例如：写作助手）",
        "assistant.copyName": "「{name}」副本",
        "assistant.broken": "本机无法启动",
        "assistant.brokenRows": "{count} 行在本机不可用",
        "assistant.brokenHint": "此模式在本机无法启动，不会出现在新会话菜单里。点「按本线修复」只关闭那些行。",
        "assistant.switchHint": "切换助手保留各自的未保存草稿；列表中的「未保存」标记即为此。",
        "btn.create": "新增助手",
        "create.heading": "新建",
        "btn.creating": "创建中…",
        "btn.duplicate": "复制",
        "btn.moveUp": "上移",
        "btn.moveDown": "下移",
        "btn.import": "导入",
        "btn.export": "导出",
        "btn.reset": "恢复出厂",
        "btn.resetHint": "把编辑器换回此助手新建时保存的提示词。只改草稿，保存后才写入。",
        "btn.delete": "删除",
        "btn.cancel": "取消",
        "msg.created": "已创建。刷新后，新会话的模式菜单里会出现它。",
        "msg.duplicated": "已复制。两份配置彼此独立。",
        "msg.deleted": "已删除。",
        "msg.createFailed": "创建失败",
        "msg.deleteFailed": "删除失败",
        "msg.nameRequired": "请先给新助手起个名字。",
        "msg.readOnlyHint": "仅管理本插件创建的助手；手写 preset 不计入，也不会被改写。",
        "msg.reordered": "顺序已保存。新会话的模式菜单按此排列。",
        "msg.reorderFailed": "调整顺序失败",
        "msg.imported": "已导入到编辑器。尚未保存。",
        "msg.importFailed": "导入失败",
        "api.badDirection": "未知的排序方向：{direction}",
        "api.unknownAssistant": "找不到助手「{id}」。此页只管理本插件创建的助手。",
        "api.alreadyFirst": "「{name}」已经在最前面。",
        "api.alreadyLast": "「{name}」已经在最后面。",
        "api.badVariableName": "变量 {variable} 不合法。名字用小写字母、数字和下划线，且以字母开头。",
        "api.unknownVariable": "{variable} 不能使用。可用：{known}。",
        "mode.unavailable": "本机读不到出厂组成，基础模式暂不能切换。",
        "rows.unavailable": "本机读不到出厂组成，插件开关暂不能改。磁盘上的现有设置仍然有效。",
        "mode.pendingRows": "基础模式已改为「{mode}」。保存后，工具底换成这套官方预设。",
        "aria.expand": "展开详情",
        "aria.collapse": "收起详情",
        "detail.id": "行 id",
        "detail.hostReason": "平台原始信息：",
        "detail.shipped": "出厂状态",
        "detail.shippedOn": "启用",
        "detail.shippedOff": "关闭",
        "detail.note": "说明",
        "detail.state": "开关状态",
        "detail.explicitOn": "已手动启用",
        "detail.explicitOff": "已手动停用",
        "detail.untouched": "未改动（跟随官方默认）",
        "detail.platform": "平台条件",
        "btn.repair": "按本线修复",
        "msg.repaired": "已修复",
        "meta.version": "插件版本",
        "meta.versionHint": "未钉版本时，可能装到较旧的发布。要换版本，按 README 钉版本重装，然后重启。",
        "api.saved": "已保存（{name}，{mode}）。新会话使用新配置，当前会话不变。",
        "api.savedWithExclusiveRows": "已保存（{name}，{mode}）。已关闭不能同时启用的行：{rows}。",
        "api.created": "已创建「{name}」。刷新后，新会话的模式菜单里会出现它。",
        "api.savedPrompt": "已保存系统提示词（{name}）。下一步模型调用即生效。",
        "api.duplicated": "已从「{from}」复制。两份互不影响。",
        "api.deleted": "已删除「{name}」。正在使用它的会话不受影响。",
        "api.reordered": "顺序已保存。新会话的模式菜单按此排列。",
        "api.nameRequired": "请先给新助手起个名字。",
        "api.postureUnknown": "没有这个起步：{posture}。",
        "api.posturePromptMissing": "读不到「{posture}」的起步提示词。",
        "api.nameTooLong": "名字太长了（上限 {max} 个字符）。",
        "api.descriptionTooLong": "描述太长了（上限 {max} 个字符）。",
        "api.promptEmpty": "不能保存空的系统提示词。",
        "api.badMode": "未知的基础模式：{mode}",
        "api.dirExists": "目录已存在，请换一个名字：{path}",
        "api.compositionMissing": "找不到组成文件：{path}",
        "api.writeFailed": "写入失败：{detail}",
        "api.promptWriteFailed": "写入提示词失败：{detail}",
        "api.renderFailed": "生成组成文件失败：{detail}",
        "api.selfCheckFailed": "生成结果自检失败，已放弃写入：{detail}",
        "api.internalError": "宿主内部错误：{detail}",
        "api.bodyTooLarge": "请求体过大（上限 {max} 字节）",
        "api.repairNotNeeded": "没有需要修复的行。",
        "api.repaired": "已关闭 {count} 个本机无法启动的行（{ids}）。",
        "api.badAssistantId": "助手标识不合法：{id}（只能用 a-z、0-9 与连字符）",
        "api.seedFailed": "复制模式模板失败：{detail}",
        "api.metaWriteFailed": "写入 preset.yml 失败：{detail}",
        "api.promptReadFailed": "读取提示词失败：{detail}",
        "api.noRemoveApi": "当前版本无法删除助手。",
        "api.noDirectory": "删除失败：磁盘上已没有这个目录。刷新后再试。",
        "api.deleteFailed": "删除失败：{detail}",
        "api.versionMissing": "找不到这个版本（历史可能已被上限裁剪）。",
        "api.badJson": "请求体不是合法 JSON",
        "api.savedPromptOnly": "已保存系统提示词。基础模式和插件开关未改。",
        "api.baseCompositionUnavailable": "读不到基础模式「{mode}」的出厂组成。开关未改。系统提示词可以单独保存。",
        "warn.approvalGateMissing": "当前版本没有审批事件。会话内改写提示词不会请求确认。",
        "warn.unresolvableRows": "有的行在本机无法启动，此模式不会出现在新会话菜单里。点「按本线修复」只关闭那些行。",
        "warn.presetBroken": "此模式无法启动，不会出现在新会话菜单里。点「按本线修复」。原因见下一行。",
        "warn.badPromptVariable": "提示词里有不可用的变量。改掉后再保存，否则此模式的请求会失败。",
        "warn.duplicateRowIds": "组成文件里有重复的行 id。开关只作用于第一处。",
        "warn.predicateFailed": "有一行的平台条件无法计算，已按关闭处理。",
        "warn.exclusiveRowsActive": "两套终端不能同时打开，否则此模式无法启动。保存时会关掉其中一套。",
        "warn.baseCompositionUnavailable": "读不到出厂组成。基础模式和插件开关暂不能改。系统提示词仍可保存。",
        "warn.personaOffWithPrompt": "「身份」已关闭，这段提示词不会生效。",
        "warn.toolOff": "custom_prompt 已关闭。会话里不能改提示词，只能在此页改。",
        "warn.noDescription": "没有描述时，模式菜单显示「暂无描述」。",
        "warn.noName": "没有名字时，模式菜单显示目录 id。",
        "history.label": "改动历史",
        "history.pick": "选择版本…",
        "history.load": "载入",
        "history.hint": "载入只改草稿。保存后才写入。",
        "history.by.settings": "此页保存",
        "history.by.external": "外部改动",
        "msg.loadFailed": "载入这一版失败",
        "msg.importEmpty": "这个文件是空的。",
        "msg.importTooLarge": "文件太大（上限 1MB）。",
        "msg.exported": "已导出为文件。",
        "msg.exportFailed": "导出失败",
        "delete.title": "删除「{name}」？",
        "delete.description": "将删除此助手的目录和系统提示词，不能撤销。正在使用它的会话不受影响。",
        "delete.acknowledge": "我知道提示词会被删除",
        "delete.confirm": "删除",
        "delete.close": "关闭",
        "delete.plainConfirm": "删除「{name}」？不能撤销。",
        "name.heading": "名称",
        "name.hint": "显示在模式菜单里。内部标识不变。",
        "name.placeholder": "自定义模式",
        "name.descriptionPlaceholder": "描述，显示在模式菜单里，可留空",
        "mode.heading": "基础模式",
        "mode.hint": "选一套官方预设作为工具底。提示词以本页为准。",
        "rows.heading": "插件开关",
        "rows.filterPlaceholder": "按名称或 id 筛选",
        "cap.heading": "插件开关",
        "cap.expand": "显示插件开关",
        "cap.collapse": "隐藏插件开关",
        "cap.hint": "关闭的行，这个助手不会使用。",
        "rows.hint": "关闭的行，这个助手不会使用。",
        "prompt.heading": "系统提示词",
        "prompt.hint": "保存后，下一步模型调用即生效。",
        "status.enabled": "已启用",
        "status.disabled": "已停用",
        "status.changed": "已改",
        "tag.essential": "核心",
        "tag.followPlatform": "随平台",
        "btn.save": "保存",
        "btn.saving": "处理中…",
        "btn.reload": "重新读取",
        "btn.reloadDiscard": "放弃修改并重新读取",
        "btn.reloadConfirm": "放弃未保存的修改并重新读取？",
        "msg.unsaved": "有未保存的修改",
        "msg.readFailed": "读取失败",
        "msg.saveFailed": "保存失败",
        "msg.saved": "已保存。新会话使用新配置，当前会话不变。",
        "status.detail": "{message}：{detail}",
        "row.persona.label": "身份（系统提示词）",
        "row.persona.note": "关闭后，此提示词不会生效。",
        "row.custom-prompt-tool.label": "custom_prompt 工具",
        "row.custom-prompt-tool.note": "关掉后无法用对话改提示词（设置页仍可用）",
        "row.agent-instructions.label": "项目指令 AGENTS.md",
        "row.agent-instructions.note": "读取 AGENTS.md / CLAUDE.md",
        "row.tool-bash.label": "Shell（bash）",
        "row.tool-pwsh.label": "Shell（pwsh）",
        "row.tool-fs.label": "文件读写",
        "row.tool-fs.note": "关掉后 agent 无法读写文件",
        "row.tool-fs-search.label": "文件搜索（glob/grep）",
        "row.tool-jobs.label": "后台任务",
        "row.planning.label": "计划模式（分组）",
        "row.planning.note": "关闭后，计划能力一并去掉。",
        "row.plan-mode.label": "计划模式实现",
        "row.compaction.label": "上下文压缩（分组）",
        "row.compaction.note": "含 isolate realm",
        "row.compaction-basic.label": "基础压缩",
        "row.command-compact.label": "/compact 命令",
        "row.tool-result-pruner.label": "工具结果裁剪",
        "row.delegation.label": "委派与工作流（分组）",
        "row.delegation.note": "关闭后，子代理和工作流一并去掉。",
        "row.tool-subagent.label": "子代理（spawn）",
        "row.tool-subagent-fork.label": "子代理（fork）",
        "row.tool-subagent-control.label": "子代理控制",
        "row.tool-subagent-list-agents.label": "列出子代理",
        "row.tool-subagent-codex.label": "Codex 子代理",
        "row.tool-subagent-codex.note": "需要先安装对应 Bundle 才能用；出厂状态见「详情」",
        "row.tool-subagent-claude-code.label": "Claude Code 子代理",
        "row.tool-subagent-claude-code.note": "需要先安装对应 Bundle 才能用；出厂状态见「详情」",
        "row.time-context.label": "时间上下文（模型看得到时间）",
        "row.time-context.note": "在部分步骤附上当前时间。",
        "row.tool-schedule.label": "定时提醒工具",
        "row.tool-schedule.note": "让 agent 创建/列出/修改/删除持久提醒；存储与投递由宿主的 schedule 服务负责",
        "row.workflow-ptc.label": "工作流引擎",
        "row.workflow-worker-thread.label": "工作流 Worker 线程",
        "row.workflow-worker-thread.note": "把工作流跑在独立的 worker 线程里",
        "row.tool-workflow.label": "工作流工具",
        "row.tool-ralph.label": "Ralph 工作流",
        "row.tool-ralph.note": "Ralph 工作流工具；出厂状态见「详情」",
        "row.tool-web.label": "网页检索与抓取",
        "row.tool-skill.label": "技能工具",
        "row.skill-filesystem.label": "技能发现",
        "row.tool-cordis.label": "Cordis 运行时工具",
        "row.tool-cordis.note": "可读写 harness 运行时",
        "row.tool-plugin-manager.label": "插件管理（安装 / 启停）",
        "row.tool-plugin-manager.note": "供模型安装或开关插件。",
        "row.tool-presentation.label": "PTC 工具呈现",
        "row.present.label": "交付文件（present）",
        "row.command-goal.label": "目标命令",
        "row.tool-goal.label": "目标",
        "row.tool-todo.label": "待办清单",
        "row.tool-ask-user.label": "向用户提问",
        "row.persistent-shell.label": "持久 Shell",
        "row.pty.label": "PTY 终端",
        "row.terminal-bash.label": "终端（bash）",
        "row.persistent-bash.label": "持久 bash",
        "row.terminal-pwsh.label": "终端（pwsh）",
        "row.persistent-pwsh.label": "持久 pwsh",
        "base.standard.label": "标准模式",
        "base.standard.note": "出厂的完整工具集。",
        "base.ptc.label": "PTC 模式",
        "base.ptc.note": "标准模式，另开 PTC 工具呈现。",
        "base.minimal.label": "极简模式",
        "base.minimal.note": "只有 Shell 与终端。提示词仍以本页为准。",
        "base.cordis.label": "创造模式",
        "base.cordis.note": "标准模式，另开 Cordis 运行时工具。",
        "base.all.label": "全部出厂行",
        "base.all.note": "四套官方预设的行合在一处，均可开关。"
      }

      const EN = {
        "nav": "Custom mode",
        "assistant.heading": "Assistants",
        "assistant.hint": "Each assistant keeps its own system prompt, base mode and plugin switches.",
        "posture.hint": "Applies only to the assistant you are about to create.",
        "posture.develop.label": "Coding",
        "posture.develop.note": "After create: Standard, tools as shipped.",
        "posture.write.label": "Writing",
        "posture.write.note": "After create: terminal off, file access on.",
        "posture.chat.label": "Chat",
        "posture.chat.note": "After create: files and terminal off.",
        "assistant.empty": "No assistants yet — create one with the field below.",
        "assistant.loadingList": "Loading assistants…",
        "assistant.newPlaceholder": "Name of the new assistant (e.g. Writing assistant)",
        "assistant.copyName": "{name} (copy)",
        "assistant.broken": "Will not start here",
        "assistant.brokenRows": "{count} row(s) unusable here",
        "assistant.brokenHint": "This mode cannot start here, so it will not appear for a new session. Fix for this line turns off only those rows.",
        "assistant.switchHint": "Switching assistants keeps each draft; the \"Unsaved\" marker in the list is exactly that.",
        "btn.create": "New assistant",
        "create.heading": "New assistant",
        "btn.creating": "Creating…",
        "btn.duplicate": "Duplicate",
        "btn.moveUp": "Move up",
        "btn.moveDown": "Move down",
        "btn.import": "Import",
        "btn.export": "Export",
        "btn.reset": "Factory prompt",
        "btn.resetHint": "Restores the prompt saved when this assistant was created. The editor changes. Save writes it.",
        "btn.delete": "Delete",
        "btn.cancel": "Cancel",
        "msg.created": "Created. Refresh once, then it appears in the mode menu for a new session.",
        "msg.duplicated": "Duplicated. The two are independent from here on.",
        "msg.deleted": "Deleted.",
        "msg.createFailed": "Could not create",
        "msg.deleteFailed": "Could not delete",
        "msg.nameRequired": "Give the new assistant a name first.",
        "msg.readOnlyHint": "Manages only the assistants this plugin created; a hand-written preset is not listed and is never rewritten.",
        "msg.reordered": "Order saved. New sessions list modes in this order.",
        "msg.reorderFailed": "Could not reorder",
        "msg.imported": "Imported into the editor. Not saved yet.",
        "msg.importFailed": "Import failed",
        "api.badDirection": "Unknown reorder direction: {direction}",
        "api.unknownAssistant": "No assistant \"{id}\". This page only manages assistants it created.",
        "api.alreadyFirst": "{name} is already first.",
        "api.alreadyLast": "{name} is already last.",
        "api.badVariableName": "{variable} is not a valid variable. Use a lower-case name.",
        "api.unknownVariable": "{variable} cannot be used. Available: {known}.",
        "mode.unavailable": "No shipped composition is available, so the base mode cannot be changed.",
        "rows.unavailable": "No shipped composition is available, so the plugin switches cannot be edited right now; the row states already on disk still apply.",
        "mode.pendingRows": "Base mode is now {mode}. On save, the tool base becomes this shipped preset.",
        "aria.expand": "Show details",
        "aria.collapse": "Hide details",
        "detail.id": "Row id",
        "detail.hostReason": "Host message: ",
        "detail.shipped": "Shipped",
        "detail.shippedOn": "enabled",
        "detail.shippedOff": "disabled",
        "detail.note": "Note",
        "detail.state": "Switch",
        "detail.explicitOn": "Set to on by you",
        "detail.explicitOff": "Set to off by you",
        "detail.untouched": "Untouched (follows the shipped default)",
        "detail.platform": "Platform condition",
        "btn.repair": "Fix for this line",
        "msg.repaired": "Repaired",
        "meta.version": "Plugin version",
        "meta.versionHint": "Without a pinned version, an older release may be installed. To change it, reinstall with the version pin from the README, then restart.",
        "api.saved": "Saved ({name}, {mode}). New sessions use it. The current session does not change.",
        "api.savedWithExclusiveRows": "Saved ({name}, {mode}). Turned off rows that cannot be on together: {rows}.",
        "api.created": "Created {name}. Refresh once, then it appears in the mode menu for a new session.",
        "api.savedPrompt": "System prompt saved ({name}). It applies on the next model step.",
        "api.duplicated": "Copied from {from}. The two are independent from now on.",
        "api.deleted": "Deleted {name}. Sessions already using it are unchanged.",
        "api.reordered": "Order saved. New sessions list modes in this order.",
        "api.nameRequired": "Give the new assistant a name first.",
        "api.postureUnknown": "No such starter: {posture}.",
        "api.posturePromptMissing": "Could not read the starter prompt for {posture}.",
        "api.nameTooLong": "That name is too long (limit {max} characters).",
        "api.descriptionTooLong": "That description is too long (limit {max} characters).",
        "api.promptEmpty": "An empty system prompt cannot be saved.",
        "api.badMode": "Unknown base mode: {mode}",
        "api.dirExists": "That directory already exists — pick another name: {path}",
        "api.compositionMissing": "Composition file not found: {path}",
        "api.writeFailed": "Write failed: {detail}",
        "api.promptWriteFailed": "Writing the prompt failed: {detail}",
        "api.renderFailed": "Rendering the composition failed: {detail}",
        "api.selfCheckFailed": "The generated composition failed its self-check, so nothing was written: {detail}",
        "api.internalError": "Unexpected host error: {detail}",
        "api.bodyTooLarge": "Request body too large (limit {max} bytes)",
        "api.repairNotNeeded": "Nothing to repair.",
        "api.repaired": "Turned off {count} row(s) that cannot start here ({ids}).",
        "api.badAssistantId": "Invalid assistant id: {id} (a-z, 0-9 and hyphens only)",
        "api.seedFailed": "Copying the mode template failed: {detail}",
        "api.metaWriteFailed": "Writing preset.yml failed: {detail}",
        "api.promptReadFailed": "Reading the prompt failed: {detail}",
        "api.noRemoveApi": "This version cannot delete an assistant.",
        "api.noDirectory": "Delete failed: this assistant's directory is gone from disk, so the list on screen is stale. Refresh and try again.",
        "api.deleteFailed": "Delete failed: {detail}",
        "api.versionMissing": "That version is gone (the history is capped).",
        "api.badJson": "The request body is not valid JSON",
        "api.savedPromptOnly": "System prompt saved. Base mode and plugin switches were not changed.",
        "api.baseCompositionUnavailable": "No shipped composition for base mode \"{mode}\" on this machine, so its plugin switches and base mode cannot be changed (the system prompt can still be saved on its own).",
        "warn.personaOffWithPrompt": "Identity is off, so this prompt has no effect.",
        "warn.toolOff": "custom_prompt is off. The prompt can be edited on this page only.",
        "warn.noDescription": "Without a description, the mode menu shows \"no description yet\".",
        "warn.approvalGateMissing": "This version has no approval event. In-session prompt edits are not confirmed.",
        "warn.unresolvableRows": "Some rows cannot start here, so this mode is absent from the new-session menu. Fix for this line turns off only those rows.",
        "warn.presetBroken": "This mode cannot start, so it will not appear for a new session. Use Fix for this line. The reason is on the next line.",
        "warn.badPromptVariable": "The prompt uses a variable that is not available. Fix it before saving.",
        "warn.duplicateRowIds": "A row id is repeated. A switch applies only to the first one.",
        "warn.predicateFailed": "A platform condition could not be evaluated. That row is treated as off.",
        "warn.exclusiveRowsActive": "The two terminals cannot both be on, or this mode will not start. Saving turns one of them off.",
        "warn.noName": "Without a name, the mode menu shows the directory id.",
        "warn.baseCompositionUnavailable": "No shipped composition is available. Base mode and plugin switches cannot be changed. The system prompt can still be saved.",
        "history.label": "Change history",
        "history.pick": "Choose a version…",
        "history.load": "Load",
        "history.hint": "Loading edits the draft only. Save writes it.",
        "history.by.settings": "saved here",
        "history.by.external": "external edit",
        "msg.loadFailed": "Could not load that version",
        "msg.importEmpty": "That file is empty.",
        "msg.importTooLarge": "That file is too large (1 MB limit).",
        "msg.exported": "Exported to a file.",
        "msg.exportFailed": "Export failed",
        "delete.title": "Delete \"{name}\"?",
        "delete.description": "This deletes the assistant directory and its system prompt. It cannot be undone. Sessions already using it are unchanged.",
        "delete.acknowledge": "I understand this assistant's prompt will be deleted permanently",
        "delete.confirm": "Delete",
        "delete.close": "Close",
        "delete.plainConfirm": "Delete \"{name}\"? This cannot be undone.",
        "name.heading": "Name",
        "name.hint": "Shown in the mode menu. The internal id stays the same.",
        "name.placeholder": "Custom mode",
        "name.descriptionPlaceholder": "Description, shown in the mode menu. Optional.",
        "mode.heading": "Base mode",
        "mode.hint": "Pick a shipped preset as the tool base. The prompt on this page still applies.",
        "rows.heading": "Plugin switches",
        "rows.filterPlaceholder": "Filter by name or id",
        "cap.heading": "Plugin switches",
        "cap.expand": "Show plugin switches",
        "cap.collapse": "Hide plugin switches",
        "cap.hint": "A row that is off is not used by this assistant.",
        "rows.hint": "A row that is off is not used by this assistant.",
        "prompt.heading": "System prompt",
        "prompt.hint": "After you save, the next model step uses this text.",
        "status.enabled": "Enabled",
        "status.disabled": "Disabled",
        "status.changed": "changed",
        "tag.essential": "Core",
        "tag.followPlatform": "Platform",
        "btn.save": "Save",
        "btn.saving": "Working…",
        "btn.reload": "Reload",
        "btn.reloadDiscard": "Discard edits and reload",
        "btn.reloadConfirm": "Discard unsaved edits and reload?",
        "msg.unsaved": "Unsaved changes",
        "msg.readFailed": "Load failed",
        "msg.saveFailed": "Save failed",
        "msg.saved": "Saved. New sessions use it. The current session does not change.",
        "status.detail": "{message}: {detail}",
        "row.persona.label": "Identity (system prompt)",
        "row.persona.note": "Off: this prompt has no effect.",
        "row.custom-prompt-tool.label": "custom_prompt tool",
        "row.custom-prompt-tool.note": "Without it the prompt can still be edited here, but not by asking the agent",
        "row.agent-instructions.label": "Project instructions (AGENTS.md)",
        "row.agent-instructions.note": "Reads AGENTS.md / CLAUDE.md",
        "row.tool-bash.label": "Shell (bash)",
        "row.tool-pwsh.label": "Shell (pwsh)",
        "row.tool-fs.label": "File access",
        "row.tool-fs.note": "Without it the agent cannot read or write files",
        "row.tool-fs-search.label": "File search (glob/grep)",
        "row.tool-jobs.label": "Background jobs",
        "row.planning.label": "Plan mode (group)",
        "row.planning.note": "Off: planning is removed with it.",
        "row.plan-mode.label": "Plan mode implementation",
        "row.compaction.label": "Context compaction (group)",
        "row.compaction.note": "Carries an isolate realm",
        "row.compaction-basic.label": "Basic compaction",
        "row.command-compact.label": "/compact command",
        "row.tool-result-pruner.label": "Tool-result pruning",
        "row.delegation.label": "Delegation and workflows (group)",
        "row.delegation.note": "Off: subagents and workflows are removed with it.",
        "row.tool-subagent.label": "Subagent (spawn)",
        "row.tool-subagent-fork.label": "Subagent (fork)",
        "row.tool-subagent-control.label": "Subagent control",
        "row.tool-subagent-list-agents.label": "List subagents",
        "row.tool-subagent-codex.label": "Codex subagent",
        "row.tool-subagent-codex.note": "Needs its Bundle installed first; the shipped state is in the details",
        "row.tool-subagent-claude-code.label": "Claude Code subagent",
        "row.tool-subagent-claude-code.note": "Needs its Bundle installed first; the shipped state is in the details",
        "row.time-context.label": "Time context (the model can see the clock)",
        "row.time-context.note": "Adds the current time on some steps.",
        "row.tool-schedule.label": "Scheduled reminders tool",
        "row.tool-schedule.note": "Lets the agent create, list, edit and delete durable reminders; the host schedule service owns storage and delivery",
        "row.workflow-ptc.label": "Workflow engine",
        "row.workflow-worker-thread.label": "Workflow worker thread",
        "row.workflow-worker-thread.note": "Runs workflows on a separate worker thread",
        "row.tool-workflow.label": "Workflow tool",
        "row.tool-ralph.label": "Ralph workflow",
        "row.tool-ralph.note": "The Ralph workflow tool; the shipped state is in the details",
        "row.tool-web.label": "Web search and fetch",
        "row.tool-skill.label": "Skill tool",
        "row.skill-filesystem.label": "Skill discovery",
        "row.tool-cordis.label": "Cordis runtime tools",
        "row.tool-cordis.note": "Can read and modify the running harness",
        "row.tool-plugin-manager.label": "Plugin manager (install / toggle)",
        "row.tool-plugin-manager.note": "Lets the model install or toggle plugins.",
        "row.tool-presentation.label": "PTC tool presentation",
        "row.present.label": "Deliverables (present)",
        "row.command-goal.label": "Goal command",
        "row.tool-goal.label": "Goals",
        "row.tool-todo.label": "Todo list",
        "row.tool-ask-user.label": "Ask the user",
        "row.persistent-shell.label": "Persistent shell",
        "row.pty.label": "PTY terminal",
        "row.terminal-bash.label": "Terminal (bash)",
        "row.persistent-bash.label": "Persistent bash",
        "row.terminal-pwsh.label": "Terminal (pwsh)",
        "row.persistent-pwsh.label": "Persistent pwsh",
        "base.standard.label": "Standard mode",
        "base.standard.note": "The shipped tool set.",
        "base.ptc.label": "PTC mode",
        "base.ptc.note": "Standard, plus PTC tool presentation.",
        "base.minimal.label": "Minimal mode",
        "base.minimal.note": "Shell and terminal only. The prompt on this page still applies.",
        "base.cordis.label": "Creator mode",
        "base.cordis.note": "Standard, plus Cordis runtime tools.",
        "base.all.label": "All shipped rows",
        "base.all.note": "Rows from all four shipped presets. Each row can be switched."
      }

      const TRANSLATIONS = { zh: ZH, en: EN }

      /**
       * The language the shell is showing right now.
       *
       * Read from the service SNAPSHOT at call time rather than captured once, so a language
       * switch — which re-renders every outlet — picks up the new value on the next render.
       */
      /** `error` as a readable string, without assuming it is an Error. */
      function describe(error) {
        return String((error && error.message) || error)
      }

      function activeLanguage(locale) {
        let id = ""
        try {
          const snapshot = locale !== undefined && typeof locale.getLocale === "function" ? locale.getLocale() : undefined
          if (snapshot !== undefined && typeof snapshot.active === "string") id = snapshot.active
        } catch (error) {
          /* a shell without a readable snapshot falls through */
        }
        if (id === "" && typeof navigator !== "undefined" && typeof navigator.language === "string") id = navigator.language
        const norm = id.toLowerCase()
        if (norm.startsWith("zh")) return "zh"
        if (norm.startsWith("en")) return "en"
        const primary = norm.split("-")[0]
        if (Object.prototype.hasOwnProperty.call(TRANSLATIONS, norm)) return norm
        if (primary !== "" && Object.prototype.hasOwnProperty.call(TRANSLATIONS, primary)) return primary
        // 壳目前内置 zh / en，语言包可以再注册别的 id。没有对应词典时用英文，避免整页中文或裸键。
        return "en"
      }

      /**
       * Translate one key, with this bundle's own dictionaries as a FLOOR.
       *
       * The shell's bound `t` is preferred — it is what makes a language switch re-render the
       * page and what keeps this text in the same table as every other plugin's. But the page
       * must never depend on that registration having succeeded: `locale.register()` refuses a
       * namespace it already holds (an HMR swap applies this bundle twice), and a registration
       * can be gone by the time the already-mounted page renders — both observed as a page full
       * of raw keys like `assistant.heading`. The dictionaries are inlined in this file anyway
       * (they are what test/locales.test.mjs diffs against locales.mjs), so they are used when
       * the shell cannot answer. A raw key is therefore impossible whenever it is in the table.
       */
      function translate(key, fallback, shellT) {
        if (typeof shellT === "function") {
          const value = shellT(key)
          if (typeof value === "string" && value !== "" && value !== key) return value
        }
        const dict = activeLanguage(localeRef) === "en" ? EN : ZH
        if (typeof dict[key] === "string" && dict[key] !== "") return dict[key]
        if (typeof ZH[key] === "string" && ZH[key] !== "") return ZH[key]
        return fallback === undefined ? key : fallback
      }

      /**
       * The locale service, kept for {@link translate}'s language lookup and the nav label.
       *
       * A module-scope slot rather than a parameter because the nav label is a thunk the shell
       * calls outside any component, and it must read the CURRENT language each time.
       */
      let localeRef

      /**
       * Plain-element stand-ins for the shell's atoms.
       *
       * Only reached on a shell that does not seed {@link ATOMS_MODULE}. Their prop contracts
       * match the real ones exactly (`Switch.onChange` hands over the next boolean, not an
       * event), so no component below needs to know which set it got.
       */
      function fallbackAtoms() {
        const join = (...parts) => parts.filter((part) => typeof part === "string" && part !== "").join(" ")
        const Button = (props) => {
          const { variant, size, icon, children, className, ...rest } = props
          return react.createElement(
            "button",
            {
              ...rest,
              type: rest.type === undefined ? "button" : rest.type,
              className: join(
                "cpfe-btn",
                variant === "primary" ? "cpfe-btn-primary" : variant === "ghost" ? "cpfe-btn-ghost" : "cpfe-btn-outline",
                size === "sm" ? "cpfe-btn-sm" : "",
                className,
              ),
            },
            icon === undefined || icon === null ? null : icon,
            children,
          )
        }
        const Input = (props) => {
          const { icon, className, ...rest } = props
          return react.createElement("input", { ...rest, className: join("cpfe-input", className) })
        }
        const Switch = (props) => {
          const { checked, onChange, label, disabled, title, className } = props
          // Like the real atom: `label` is the accessible name ONLY, never visible text —
          // the caller draws the row title, so both paths look the same.
          return react.createElement(
            "label",
            { className: join("cpfe-switch", className), title },
            react.createElement("input", {
              type: "checkbox",
              checked,
              disabled,
              "aria-label": label,
              onChange: (event) => onChange(event.target.checked),
            }),
          )
        }
        const Tag = (props) =>
          react.createElement(
            "span",
            { className: join("cpfe-tag", props.tone === undefined ? "" : "cpfe-tag-" + props.tone, props.className) },
            props.children,
          )
        const Pill = (props) => {
          const { active, className, children, ...rest } = props
          return react.createElement(
            "button",
            {
              ...rest,
              type: "button",
              "aria-pressed": active === true,
              className: join("cpfe-pill", active === true ? "cpfe-pill-on" : "", className),
            },
            children,
          )
        }
        return { Button, Input, Switch, Tag, Pill }
      }

      /**
       * Whether an atom can be handed to `createElement`.
       *
       * ★ **This is where the page's native look was lost.** The probe used to be
       * `typeof atoms.Button === "function"`, but the shell's components are `forwardRef(...)` /
       * `memo(...)` **objects** (`Button = forwardRef(function Button(){…})`, measured), so the check
       * failed and *every* control silently fell back to the plain stand-ins — hand-painted buttons,
       * `input[type=checkbox]` instead of the shell's switch, a native `window.confirm` instead of its
       * risk dialog. Measured on 0.1.7-rc.2 (2026-09-25): `require(ATOMS_MODULE)` returns **279 exports**
       * including `Button`, `RiskConfirmation`, `Modal`, `Menu`, `SegmentedControl` and the icon set —
       * the module was in the platform seed table the whole time.
       * React renders any object carrying `$$typeof`, so that is what "usable" means here.
       */
      const renderable = (atom) =>
        typeof atom === "function" || (atom !== null && typeof atom === "object" && atom.$$typeof !== undefined)

      /**
       * The page asks for icons under short names (`IconPlusOutline16`); the shell exports
       * `IconPlusOutlineRegular`. Without this map the calls were `undefined` and **no icon ever
       * rendered** — which is part of why the header row looked hand-made.
       */
      const ICON_ALIAS = {
        IconChevronDownOutline14: "IconChevronDownOutlineRegular",
        IconChevronUpOutline14: "IconChevronUpOutlineRegular",
        IconChevronLeftOutline14: "IconChevronLeftOutlineRegular",
        IconChevronRightOutline14: "IconChevronRightOutlineRegular",
        IconPlusOutline16: "IconPlusOutlineRegular",
        IconCheckOutline14: "IconCheckOutlineRegular",
        IconCopyOutline16: "IconCopyOutlineRegular",
        IconDownloadOutline16: "IconDownloadOutlineRegular",
        IconRefreshOutline16: "IconRefreshOutlineRegular",
        IconTrashOutline16: "IconTrashOutlineRegular",
        IconWarningOutline14: "IconWarningOutlineRegular",
      }

      function withIconAliases(atoms) {
        const out = Object.assign({}, atoms)
        for (const short of Object.keys(ICON_ALIAS)) {
          if (out[short] === undefined && renderable(atoms[ICON_ALIAS[short]])) out[short] = atoms[ICON_ALIAS[short]]
        }
        return out
      }

      /** Resolve the atom set once, at factory time. */
      function loadAtoms() {
        try {
          const atoms = require(ATOMS_MODULE)
          if (atoms !== null && typeof atoms === "object" && renderable(atoms.Button)) return withIconAliases(atoms)
          console.info(
            "dsh-custom-mode: 壳提供了 " +
              ATOMS_MODULE +
              "，但里面没有可用的 Button，改用内置的朴素控件（功能一致，外观更简）。",
          )
        } catch (error) {
          console.info(
            "dsh-custom-mode: 当前壳没有在种子表里提供 " +
              ATOMS_MODULE +
              "，改用内置的朴素控件（功能一致，外观更简）。",
          )
        }
        return fallbackAtoms()
      }

      const A = loadAtoms()

      /**
       * Stylesheet: LAYOUT ONLY.
       *
       * Controls are the shell's, so nothing here restyles a button, input or switch while the
       * atoms are available — that is what keeps this page consistent with every other one. The
       * `cpfe-btn*` / `cpfe-input` / `cpfe-switch*` / `cpfe-tag*` / `cpfe-pill*` rules exist for
       * the fallback path above, and `--dsw-*` tokens are used for the few own surfaces (cards,
       * grids, the status line).
       */
      const CSS = [
        ".cpfe{--g:8px;display:flex;flex-direction:column;gap:16px;width:100%;max-width:900px;box-sizing:border-box;padding-bottom:16px;font:inherit;color:inherit}",
        ".cpfe > section{padding-top:16px;border-top:1px solid var(--dsw-alias-border-l1)}",
        ".cpfe > section:first-child{padding-top:0;border-top:0}",
        // 分节标题：度量抄官方设置面板里的同类文本。
        // 实测（**0.1.7-rc.2 与 0.2.0-rc.2 各量一次，结论相同**）：官方 `_title`（设置行标题）
        // 与 `groupTitle`（插件页分组标题）都是 14px/22px **w400**；官方唯一的 14px w500 是插件
        // 卡片标题（`cardTitle`，行高 20px，形态不同），页级标题则是 16px/24px w500。
        // 本页是设置面板里的分节，取 w400。
        // ⚠️ 本仓长期记作「官方区块标题 14px/22px w500」—— 那一条在 2026-10-02 的复量中被证伪。
        ".cpfe-h{margin:0 0 2px;font-size:14px;line-height:22px;font-weight:400;color:var(--dsw-alias-label-primary)}",
        ".cpfe-sub{margin:0 0 8px;font-size:12px;line-height:18px;color:var(--dsw-alias-label-tertiary)}",
        // 区块引言：官方是标题下的一行 12px 灰字 —— 没有折叠按钮，也没有那个蓝色圆点。
        ".cpfe-hint{display:flex;flex-direction:column;gap:2px;margin:0 0 6px}",
        ".cpfe-hint-line{font-size:12px;line-height:18px;color:var(--dsw-alias-label-tertiary)}",
        ".cpfe-hint-detail{margin:0;font-size:12px;line-height:18px;color:var(--dsw-alias-label-tertiary)}",
        // 描述：多行、自适应高度（没有多行输入组件，所以用 textarea + 同一批语义变量）
        ".cpfe-desc{box-sizing:border-box;min-height:40px;max-height:120px;resize:vertical;padding:8px 12px;border-radius:var(--dsw-radius-md);border:.5px solid var(--dsw-alias-border-l2);background:var(--dsw-alias-bg-module-platform);color:var(--dsw-alias-label-primary);font:inherit;font-size:13px;line-height:20px;margin-bottom:0}",
        ".cpfe-base-pending{color:var(--dsw-alias-state-warn-primary)}",
        ".cpfe-note{display:block;margin:8px 0 0;font-size:12px;line-height:18px;color:var(--dsw-alias-label-tertiary)}",
        ".cpfe-mono{display:block;margin:0;font-family:var(--ds-font-family-code, ui-monospace, SFMono-Regular, Menlo, monospace);font-size:12px;line-height:18px;color:var(--dsw-alias-label-secondary);overflow-wrap:anywhere}",
        ".cpfe-pills{display:flex;flex-wrap:wrap;gap:6px;align-items:center}",
        ".cpfe-pills > *{flex:0 0 auto;white-space:nowrap}",
        ".cpfe-pills button{white-space:nowrap}",
        ".cpfe-picker{display:flex;align-items:center;gap:8px;margin:2px 0 4px}",
        // 壳的菜单浮层靠一个独立的 backing 元素上色（实测在 0.1.7-rc.2 + headless 下那块是透明的，
        // 文字会"压"在下面的输入框上）。用官方 token 显式补一层底色，真实浏览器与 headless 一致。
        ".cpfe-menu{background:var(--dsw-menu-surface-fill,var(--dsw-alias-bg-layer-3));border-radius:var(--dsw-radius-lg);box-shadow:var(--dsw-elevation-soft)}",
        ".cpfe-history{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:10px}",
        ".cpfe-history-label{font-size:12px;line-height:18px;color:var(--dsw-alias-label-tertiary)}",
        ".cpfe-history-hint{font-size:12px;line-height:18px;color:var(--dsw-alias-label-tertiary)}",
        ".cpfe-pill-wrap{display:inline-flex;align-items:center;gap:4px}",
        ".cpfe-actions{display:flex;flex-wrap:wrap;gap:var(--g);align-items:center;margin-top:4px}",
        ".cpfe-newrow{display:flex;gap:var(--g);align-items:center;flex-wrap:wrap;margin-top:10px}",
        ".cpfe-postures{display:flex;flex-direction:column;gap:6px}",
        ".cpfe-create{display:flex;flex-direction:column;gap:6px;margin-top:8px}",
        ".cpfe-field{display:flex;width:100%;margin-bottom:8px}",
        ".cpfe-row-head{font-size:14px;line-height:22px;color:var(--dsw-alias-label-primary)}",
        ".cpfe-row-switch{flex:0 0 auto}",
        ".cpfe-row-line{display:flex;align-items:center;gap:8px;flex-wrap:wrap;min-width:0}",
        ".cpfe-row-note{font-size:12px;line-height:18px;color:var(--dsw-alias-label-tertiary);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
        ".cpfe-row-toggle{flex:0 0 auto;display:inline-flex;align-items:center;justify-content:center;width:24px;height:24px;padding:0;border:0;border-radius:var(--dsw-radius-sm);background:transparent;color:var(--dsw-alias-label-secondary);cursor:pointer}",
        ".cpfe-row-toggle:hover{background:var(--dsw-alias-bg-layer-2)}",
        ".cpfe-row-open{border-color:var(--dsw-alias-border-l2)}",
        ".cpfe-row-detail{display:flex;flex-direction:column;gap:4px;margin:0 0 12px;padding:0 0 0 16px;border:0;font-size:12px;line-height:18px;color:var(--dsw-alias-label-tertiary)}",
        ".cpfe-detail-line{display:flex;gap:8px;min-width:0}",
        ".cpfe-detail-key{flex:0 0 9.5em;color:var(--dsw-alias-label-secondary)}",
        ".cpfe-detail-value{min-width:0;overflow-wrap:anywhere}",
        ".cpfe-sec-head{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:8px}",
        ".cpfe-sec-head .cpfe-actions{margin-top:0}",
        ".cpfe-newinput{flex:1;min-width:200px}",
        // 单列、行高统一（对齐官方插件页的形态）；之前的自适应多列网格会让行高参差不齐。
        // ★ 对齐官方设置行（实测 0.1.7-rc.2 的行：padding 16px 0、无背景、无圆角，行间只有一条 1px
        //   分隔线）。原来是"圆角卡片 + 描边 + 灰底" —— 那正是廉价感最重的部分。
        ".cpfe-rows{display:flex;flex-direction:column;gap:0;margin-top:0}",
        ".cpfe-line{display:flex;flex-direction:column;min-width:0;border-bottom:1px solid var(--dsw-alias-border-l1)}",
        ".cpfe-line:last-child{border-bottom:0}",
        ".cpfe-group{display:flex;flex-direction:column;min-width:0}",
        ".cpfe-kids{display:flex;flex-direction:column;margin-left:0;padding-left:16px}",
        ".cpfe-row-meta{display:flex;flex-direction:column;gap:2px;min-width:0;flex:1}",
        ".cpfe-row-badges{display:flex;align-items:center;gap:4px;flex:0 1 auto;min-width:0}",
        ".cpfe-row{display:flex;gap:12px;align-items:center;box-sizing:border-box;min-height:0;padding:16px 0;border:0;border-radius:0;background:none}",
        ".cpfe-editor{box-sizing:border-box;width:100%;min-height:120px;resize:vertical;padding:12px;border-radius:var(--dsw-radius-md);border:.5px solid var(--dsw-alias-border-l2);background:var(--dsw-alias-bg-module-platform);color:var(--dsw-alias-label-primary);font-family:var(--ds-font-family-code, ui-monospace, SFMono-Regular, Menlo, monospace);font-size:13px;line-height:20px}",
        ".cpfe-bar{display:flex;align-items:center;gap:var(--g);flex-wrap:wrap;margin-top:8px}",
        ".cpfe-disclosure{appearance:none;display:inline-flex;align-items:center;gap:6px;margin:8px 0 0;padding:0;border:0;background:transparent;color:var(--dsw-alias-label-primary);font:inherit;font-size:14px;line-height:22px;font-weight:400;cursor:pointer}",
        ".cpfe-filter{box-sizing:border-box;width:100%;height:36px;margin:0 0 8px;padding:0 12px;border-radius:var(--dsw-radius-md);border:.5px solid var(--dsw-alias-border-l2);background:var(--dsw-alias-bg-module-platform);color:var(--dsw-alias-label-primary);font:inherit;font-size:13px}",
        ".cpfe .cpfe-danger{margin-left:auto}",
        ".cpfe-status{font-size:13px;line-height:20px}",
        ".cpfe-ok{color:var(--dsw-alias-label-secondary)}",
        ".cpfe-err{color:var(--dsw-alias-state-error-primary)}",
        ".cpfe-dirty{color:var(--dsw-alias-state-warn-primary)}",
        ".cpfe-danger{color:var(--dsw-alias-state-error-primary)}",
        ".cpfe-meta-line{display:flex;flex-wrap:wrap;align-items:baseline;gap:8px;min-width:0}",
        ".cpfe-version{font-size:12px;line-height:18px;color:var(--dsw-alias-label-secondary);white-space:nowrap}",
        ".cpfe-path{font-family:var(--ds-font-family-code, ui-monospace, SFMono-Regular, Menlo, monospace);font-size:12px;line-height:18px;color:var(--dsw-alias-label-secondary);overflow-wrap:anywhere}",
        // ── fallback-path controls (unused when the shell provides the atoms) ──
        ".cpfe-btn{appearance:none;cursor:pointer;padding:0 14px;height:36px;border-radius:var(--dsw-radius-md);border:.5px solid var(--dsw-alias-border-l2);background:var(--dsw-alias-bg-base);color:var(--dsw-alias-label-primary);font:inherit;font-size:14px}",
        ".cpfe-btn:disabled{opacity:.4;cursor:not-allowed}",
        ".cpfe-btn-sm{height:28px;padding:0 10px;font-size:12px;line-height:18px;border-radius:var(--dsw-radius-sm)}",
        ".cpfe-btn-primary{background:var(--dsw-alias-label-primary);color:var(--dsw-alias-bg-base);border-color:transparent;font-weight:500}",
        ".cpfe-btn-ghost{border-color:transparent;background:transparent}",
        ".cpfe-input{box-sizing:border-box;width:100%;height:36px;padding:0 12px;border-radius:var(--dsw-radius-md);border:.5px solid var(--dsw-alias-border-l2);background:var(--dsw-alias-bg-base);color:var(--dsw-alias-label-primary);font:inherit;font-size:13px}",
        ".cpfe-switch{display:inline-flex;align-items:center;gap:8px;cursor:pointer;font-size:13px;line-height:19px;color:var(--dsw-alias-label-primary)}",
        // 忙碌期间禁掉草稿类控件的交互（issue #6）。
        // 状态层已经拦住了改动（`update()` 在 busy 时直接返回），但对**受控组件**来说，
        // 光拦状态还不够：DOM 会先显示用户敲进去的字/翻过去的开关，等下一次重渲染才被拉回来 ——
        // 那就是"我明明改了，它自己弹回去了"。`pointer-events` 让点击根本到不了控件，
        // 视觉上也给一个明确的"此刻不能动"。
        ".cpfe-busy .cpfe-switch,.cpfe-busy .cpfe-row-switch,.cpfe-busy .cpfe-row-toggle,.cpfe-busy .cpfe-input,.cpfe-busy .cpfe-field,.cpfe-busy .cpfe-editor,.cpfe-busy .cpfe-pill,.cpfe-busy .cpfe-selector{pointer-events:none;opacity:.65}",
        ".cpfe-busy .cpfe-editor{cursor:progress}",
        ".cpfe-tag{font-size:12px;line-height:18px;padding:0 5px;border-radius:4px;border:.5px solid var(--dsw-alias-border-l1);color:var(--dsw-alias-label-secondary)}",
        ".cpfe-tag-success,.cpfe-tag-info{color:var(--dsw-alias-state-success-primary)}",
        ".cpfe-tag-warning{color:var(--dsw-alias-state-warn-primary)}",
        ".cpfe-tag-danger{color:var(--dsw-alias-state-error-primary)}",
        ".cpfe-pill{appearance:none;cursor:pointer;height:28px;padding:0 12px;border-radius:999px;border:.5px solid var(--dsw-alias-border-l1);background:var(--dsw-alias-bg-base);color:var(--dsw-alias-label-secondary);font:inherit;font-size:13px}",
        ".cpfe-pill-on{border-color:var(--dsw-alias-label-primary);color:var(--dsw-alias-label-primary)}",
        // ── 官方的「设置项下拉」形态 ────────────────────────────────────────────
        // 实测（**0.1.7-rc.2 与 0.2.0-rc.2 各量一次，结论相同**）：官方的 `_selector` 是
        //   height 36px · border-radius var(--dsw-radius-md) · background var(--dsw-alias-bg-module-platform)
        //   · padding 0 14px · font-size 14px/line-height 22px · gap 12px，hover 换
        //   var(--dsw-alias-interactive-bg-hover)。
        // 壳的 Button 只有 primary / ghost / outline / toolbar 四种变体，没有"灰底选择器"这一种 ——
        // 官方各设置页也是**各自定义** `_selector`（oY77xG / hVGvvW / _2XZxNq / T1PP_q 各一份、
        // 度量完全一致），所以这里照同一套度量自绘，做法与官方一致。
        // 双类选择器（`.cpfe .cpfe-selector`）用来压过壳 Button 的单类变体规则，
        // 免得 `_ghost_` / `_sm_` 把灰底和高度覆盖掉。
        ".cpfe .cpfe-selector{height:36px;padding:0 14px;gap:12px;border-radius:var(--dsw-radius-md);background:var(--dsw-alias-bg-module-platform);color:var(--dsw-alias-label-primary);font-size:14px;line-height:22px;font-weight:400}",
        ".cpfe .cpfe-selector:hover:not(:disabled){background:var(--dsw-alias-interactive-bg-hover)}",
        // ── 告警区 ──────────────────────────────────────────────────────────────
        // 这一段此前**一条 CSS 都没有**（2026-10-02 逐表核对：样式表里 `cpfe-warn` 零规则），
        // 于是出现告警时它是一串没有排版的裸 `<p>`：没有行距、图标与文字不对齐、
        // 与页面其余部分（12px/18px）的字号也对不上。度量沿用页面其余部分。
        ".cpfe-warns{display:flex;flex-direction:column;gap:12px}",
        ".cpfe-warn-row{display:flex;flex-direction:column;gap:6px;align-items:flex-start}",
        ".cpfe-warn{display:flex;gap:6px;align-items:flex-start;margin:0;font-size:12px;line-height:18px;color:var(--dsw-alias-state-warn-primary)}",
        ".cpfe-warn svg{flex:0 0 auto;margin-top:2px}",
      ].join("")

      /** Apply the page stylesheet once, keyed so a re-mount never duplicates it. */
      function ensureStyles() {
        if (typeof document === "undefined") return
        const tagId = "dsh-custom-mode/system-prompt"
        if (document.querySelector('style[data-plugin-css="' + tagId + '"]') !== null) return
        const tag = document.createElement("style")
        tag.dataset.plugin = "dsh-custom-mode"
        tag.dataset.pluginCss = tagId
        tag.textContent = CSS
        document.head.appendChild(tag)
      }

      const asJson = (response) => response.json()

      /** The endpoints, all under the one prefix route the host half registers. */
      const ROUTES = {
        list: ROUTE,
        state: ROUTE + "/state",
        history: ROUTE + "/history",
        create: ROUTE + "/create",
        delete: ROUTE + "/delete",
        reorder: ROUTE + "/reorder",
        repair: ROUTE + "/repair",
      }

      /** Every assistant this feature manages. */
      async function fetchList() {
        return asJson(await fetch(ROUTES.list, { method: "GET", headers: { accept: "application/json" } }))
      }

      /** One assistant: base mode, row tree, switches, prompt, display metadata. */
      async function fetchState(id) {
        return asJson(
          await fetch(ROUTES.state + "?id=" + encodeURIComponent(id), {
            method: "GET",
            headers: { accept: "application/json" },
          }),
        )
      }

      /** POST a JSON body to one endpoint and read the JSON verdict. */
      async function postJson(url, payload) {
        return asJson(
          await fetch(url, {
            method: "POST",
            headers: { "content-type": "application/json", accept: "application/json" },
            body: JSON.stringify(payload),
          }),
        )
      }

      /** A safe download name: no separators or characters a filesystem rejects. */
      function fileNameFor(name) {
        const cleaned = String(name === undefined || name === null ? "" : name)
          .replace(/[\\/:*?"<>|\u0000-\u001f]+/g, "-")
          .replace(/^-+|-+$/g, "")
          .trim()
        return cleaned === "" ? "prompt" : cleaned.slice(0, 60)
      }

      /** The editable draft for one assistant, derived from its loaded state. */
      /** 时间戳给人看：转本地时间；解析不了就原样显示（历史文件是纯文本，什么都有可能）。 */
      function formatWhen(at) {
        const parsed = new Date(at)
        if (Number.isNaN(parsed.getTime())) return String(at).slice(0, 16)
        return parsed.toLocaleString()
      }

      function draftOf(state) {
        return {
          id: state.id,
          mode: state.mode,
          // 出厂组成取不到时的降级标记（服务端给，只读）。它不是用户可改的字段，sameDraft 不比较它。
          baseUnavailable:
            state.baseUnavailable === undefined || state.baseUnavailable === null ? null : state.baseUnavailable,
          overrides: { ...state.overrides },
          prompt: state.prompt,
          name: typeof state.name === "string" ? state.name : "",
          description: typeof state.description === "string" ? state.description : "",
          // 「恢复出厂提示词」要用它。它不是草稿的一部分，`sameDraft` 不比较它 ——
          // 漏掉这一行按钮会一直置灰（实测：真点了没反应，浏览器验收抓到）。
          factoryPrompt: typeof state.factoryPrompt === "string" ? state.factoryPrompt : null,
          // 改动历史：列表来自 state（只有元数据），正文点「载入这一版」时按需取。
          history: Array.isArray(state.history) ? state.history : [],
          historyPick: "",
          broken: typeof state.broken === "string" && state.broken !== "" ? state.broken : null,
          // 「配置了却不生效」的告警码；文案按当前语言渲染。
          warnings: Array.isArray(state.warnings) ? state.warnings : [],
        }
      }

      /** Whether a draft still differs from the state it was loaded from. */
      function sameDraft(left, right) {
        if (left === undefined || right === undefined || left === null || right === null) return left === right
        return (
          left.mode === right.mode &&
          left.prompt === right.prompt &&
          left.name === right.name &&
          left.description === right.description &&
          JSON.stringify(left.overrides) === JSON.stringify(right.overrides)
        )
      }

      /** `「名称」副本`-style copy name, from a locale template. */
      function copyName(template, name) {
        return String(template).replace("{name}", name)
      }

      /**
       * A section's one-line introduction, sitting under its heading.
       *
       * It takes ONE string and renders it as one line — the shape the official settings pages use:
       * a single 12px/18px tertiary line under the title, with no disclosure control and no bullet.
       *
       * It used to take `hint` + `detail` and pick `detail` when present, for a collapsible
       * introduction that no longer exists. Every caller passed both, so the short form was
       * **never rendered** — five dictionary entries (`assistant.short`, `name.short`, `mode.short`,
       * `rows.short`, `prompt.short`) that looked alive in a grep but reached no pixel. Both are gone.
       */
      function SectionHint(props) {
        return react.createElement(
          "div",
          { className: "cpfe-hint" },
          react.createElement("span", { className: "cpfe-hint-line" }, props.text),
        )
      }

      /**
       * One row of the plugin switch list.
       *
       * Laid out like the shell's own plugin page: **one compact line per row** — title, status tags, a
       * one-line truncated description and the switch on the right — with everything else (the row id, the
       * full note, the tri-state detail, the platform condition) behind a per-row disclosure. Measured before
       * this change: 33 rows at 274px wide and 84–117px tall with a bare `tool-bash` line each, i.e. uneven
       * heights and a lot of vertical noise for information most users never read.
       */
      function filterRows(rows, query, translateRow) {
        const needle = String(query || "").trim().toLowerCase()
        if (needle === "") return rows
        const out = []
        for (const row of rows) {
          const title = String(translateRow("row." + row.id + ".label", row.label)).toLowerCase()
          const self = title.includes(needle) || row.id.toLowerCase().includes(needle)
          const children = Array.isArray(row.children) ? row.children : []
          const kids = self ? children : filterRows(children, query, translateRow)
          if (self || kids.length > 0) out.push(self ? row : { ...row, children: kids })
        }
        return out
      }

      function RowList(props) {
        const { rows, overrides, onToggle, expanded, onToggleExpand, depth, t } = props
        return react.createElement(
          "div",
          { className: depth === 0 ? "cpfe-rows" : "cpfe-kids" },
          rows.map((row) => {
            // A row is "on" unless its explicit override says off; without an
            // override the shipped state decides (which is what "跟随默认" means).
            const effective = overrides[row.id] !== undefined ? overrides[row.id] : !row.disabled
            const changed = overrides[row.id] !== undefined
            const rowTitle = t("row." + row.id + ".label", row.label)
            const note = row.note === null || row.note === undefined ? null : t("row." + row.id + ".note", row.note)
            const open = expanded !== undefined && expanded[row.id] === true
            return react.createElement(
              "div",
              { key: row.id, className: row.children.length > 0 ? "cpfe-group" : "cpfe-line" },
              react.createElement(
                "div",
                { className: "cpfe-row" + (open ? " cpfe-row-open" : "") },
                react.createElement(
                  "div",
                  { className: "cpfe-row-meta" },
                  react.createElement(
                    "div",
                    { className: "cpfe-row-line" },
                    react.createElement("span", { className: "cpfe-row-head" }, rowTitle),
                    react.createElement(
                      "div",
                      { className: "cpfe-row-badges" },
                      row.essential ? react.createElement(A.Tag, { tone: "warning" }, t("tag.essential")) : null,
                      row.disabledExpression !== null && row.disabledExpression !== undefined
                        ? react.createElement(A.Tag, { tone: "outline" }, t("tag.followPlatform"))
                        : null,
                      changed ? react.createElement(A.Tag, { tone: "info" }, t("status.changed")) : null,
                    ),
                  ),
                  note
                    ? react.createElement("span", { className: "cpfe-row-note", title: note }, note)
                    : null,
                ),
                react.createElement(
                  "button",
                  {
                    type: "button",
                    className: "cpfe-row-toggle",
                    "aria-expanded": open,
                    "aria-label": t(open ? "aria.collapse" : "aria.expand"),
                    onClick: () => onToggleExpand(row.id),
                  },
                  // 用壳自己的图标，不再手绘 "▸"/"▾" 字符：字符的字形、基线与粗细都随字体走，
                  // 与官方那一排 14px 线性图标放在一起就是两种东西。壳没提供时退回原字符。
                  (() => {
                    const Icon = open ? A.IconChevronDownOutline14 : A.IconChevronRightOutline14
                    return renderable(Icon) ? react.createElement(Icon, { size: 14 }) : open ? "▾" : "▸"
                  })(),
                ),
                react.createElement(A.Switch, {
                  checked: effective,
                  disabled: props.disabled === true,
                  onChange: (next) => onToggle(row.id, next),
                  // `Switch` renders NO text: its `label` is the accessible name only, so the
                  // visible title is drawn next to it (an unlabelled toggle is unusable).
                  label: rowTitle,
                  className: "cpfe-row-switch",
                }),
              ),
              open
                ? react.createElement(
                    "div",
                    { className: "cpfe-row-detail" },
                    react.createElement(
                      "div",
                      { className: "cpfe-detail-line" },
                      react.createElement("span", { className: "cpfe-detail-key" }, t("detail.id")),
                      react.createElement("span", { className: "cpfe-mono" }, row.id),
                    ),
                    note === null
                      ? null
                      : react.createElement(
                          "div",
                          { className: "cpfe-detail-line" },
                          react.createElement("span", { className: "cpfe-detail-key" }, t("detail.note")),
                          react.createElement("span", { className: "cpfe-detail-value" }, note),
                        ),
                    react.createElement(
                      "div",
                      { className: "cpfe-detail-line" },
                      react.createElement("span", { className: "cpfe-detail-key" }, t("detail.shipped")),
                      // 出厂状态来自**本机实际文件**（row.disabled），不是写死的文案：同一个行在两条 dsh 线上
                      // 的出厂状态可能不同（实测：Ralph 在稳定线出厂是启用的，在预览线是关闭的）。
                      react.createElement(
                        "span",
                        { className: "cpfe-detail-value" },
                        row.disabled ? t("detail.shippedOff") : t("detail.shippedOn"),
                      ),
                    ),
                    react.createElement(
                      "div",
                      { className: "cpfe-detail-line" },
                      react.createElement("span", { className: "cpfe-detail-key" }, t("detail.state")),
                      react.createElement(
                        "span",
                        { className: "cpfe-detail-value" },
                        changed
                          ? effective
                            ? t("detail.explicitOn")
                            : t("detail.explicitOff")
                          : t("detail.untouched"),
                      ),
                    ),
                    row.disabledExpression !== null && row.disabledExpression !== undefined
                      ? react.createElement(
                          "div",
                          { className: "cpfe-detail-line" },
                          react.createElement("span", { className: "cpfe-detail-key" }, t("detail.platform")),
                          react.createElement("span", { className: "cpfe-mono" }, String(row.disabledExpression)),
                        )
                      : null,
                  )
                : null,
              row.children.length > 0
                ? react.createElement(RowList, {
                    rows: row.children,
                    overrides: overrides,
                    onToggle: onToggle,
                    expanded: expanded,
                    onToggleExpand: onToggleExpand,
                    depth: depth + 1,
                    t: t,
                    disabled: props.disabled === true,
                  })
                : null,
            )
          }),
        )
      }

      function CustomModeSection(props) {
        // Compatibility: a shell that does not hand us a bound `t` (older or
        // changed settings contract) must still render real copy. Falling back to
        // the Chinese dictionary means the worst case is "Chinese text", never a
        // page full of raw keys like `name.heading`.
        const shellT =
          props !== null && typeof props === "object" && typeof props.t === "function" ? props.t : undefined
        /**
         * Translate a key. {@link translate} prefers the shell's bound `t` and falls back to
         * this bundle's own dictionaries, so an untranslated key degrades to Chinese (or to the
         * caller's fallback) and never to a bare key like `name.heading`.
         */
        const t = (key, fallback) => translate(key, fallback, shellT)

        /** 把 `{name}` 这类占位符换成 params 里的值（缺失就原样留着，便于发现漏参）。 */
        const fillPlaceholders = (template, params) =>
          template.replace(/\{(\w+)\}/g, (match, key) =>
            params !== null && typeof params === "object" && params[key] !== undefined ? String(params[key]) : match,
          )

        /**
         * 服务端结果的**本地化渲染**。
         *
         * 宿主仍然回中文的 `note`/`error`（那是 HTTP API 的兼容面），但页面优先用自己的词典按 `code`
         * 渲染 —— 否则英文界面会在出错那一刻掉回中文（外部审阅点名的"硬伤"）。服务端若带来了页面无从
         * 知道的细节（路径、底层错误），放在 `params` 里填进模板。没有 `code` 时退回宿主文案，兼容旧宿主。
         */
        const apiText = (result, fallbackKey) => {
          const code = result !== null && typeof result === "object" && typeof result.code === "string" ? result.code : ""
          if (code !== "") {
            const template = translate("api." + code, "", shellT)
            if (template !== "") {
              /**
               * `{mode}` 是**基础模式**，宿主只能交出机器名（`standard` / `ptc` / `minimal` /
               * `cordis` / `all`）。直接印出来就是「基础模式 all」—— 中文读者看不懂，
               * 而且和壳自己的叫法（标准模式 / PTC 模式 / 极简模式 / 创造模式 / 自定义模式）对不上，
               * 一条原生质感的提示不该长这样。页面手上有 `base.<id>.label`，用它；
               * 词典里没有这个 id 时（旧宿主 / 未来新增）退回机器名，宁可难看也不要空。
               */
              const params = result.params
              let shown = params
              if (params !== null && typeof params === "object") {
                shown = { ...params }
                if (typeof params.mode === "string") {
                  shown.mode = translate("base." + params.mode + ".label", params.mode, shellT)
                }
                if (typeof params.rows === "string") {
                  shown.rows = params.rows
                    .split(",")
                    .map((id) => translate("row." + id.trim() + ".label", id.trim(), shellT))
                    .join(", ")
                }
              }
              return fillPlaceholders(template, shown)
            }
          }
          if (result !== null && typeof result === "object") {
            if (typeof result.error === "string" && result.error !== "") return result.error
            if (typeof result.note === "string" && result.note !== "") return result.note
          }
          return t(fallbackKey)
        }

        const [list, setList] = react.useState(null)
        const [selected, setSelected] = react.useState("")
        /** id → { payload, saved, value }: loaded state, baseline and live draft. */
        const [entries, setEntries] = react.useState({})
        const [status, setStatus] = react.useState("")
        const [failed, setFailed] = react.useState(false)
        const [busy, setBusy] = react.useState(false)
        const [newName, setNewName] = react.useState("")
        const [postureChoices, setPostureChoices] = react.useState([])
        const [posture, setPosture] = react.useState("develop")
        const [deleteOpen, setDeleteOpen] = react.useState(false)
        const [acknowledged, setAcknowledged] = react.useState(false)
        /**
         * Which rows have their detail open, by row id.
         *
         * Per-row and local to the section: the list is long (33 rows measured) and the interesting detail
         * differs per user, so nothing is expanded by default and nothing is persisted.
         */
        const [expandedRows, setExpandedRows] = react.useState({})
        // 两个官方风格的下拉（壳的 `Menu`）：助手切换与改动历史。壳不提供 Menu 时退回原生控件。
        const [assistantOpen, setAssistantOpen] = react.useState(false)
        const [historyOpen, setHistoryOpen] = react.useState(false)
        const [listFailed, setListFailed] = react.useState(false)
        const [capOpen, setCapOpen] = react.useState(false)
        const [rowQuery, setRowQuery] = react.useState("")
        /** 本机这条线上有无法解析的行时，一键把它们关掉（服务端复用保存同一条排版手术）。 */
        const repairRowsNow = async () => {
          if (draft === null) return
          setBusy(true)
          setFailed(false)
          try {
            const result = await postJson(ROUTES.repair, { id: selected })
            if (result !== null && result.ok === true) {
              setStatus(apiText(result, "msg.repaired"))
              // ★ 把修复折进**草稿**（issue #8）。
              //
              // 修复在磁盘上是生效的，但草稿里那几行仍然是"启用"。而 `reload()` 对脏草稿是
              // **保留**的（`open()` 的 `!sameDraft(...)` 保护），所以草稿不会自己更新；
              // 于是下一次保存会按草稿重渲染，把刚修好的行原样打开 —— 模式再次从所有选择器里消失。
              // 实测（2026-09-21）：修复后磁盘上 ghost-row 已关闭，保存一次就又被打开了。
              const repairedIds = Array.isArray(result.params?.repairedIds) ? result.params.repairedIds : []
              if (repairedIds.length > 0 && draft !== null) {
                // 草稿里的 `overrides` 是"显式**启用**"语义，所以关闭 = false。
                const nextOverrides = { ...draft.overrides }
                for (const rowId of repairedIds) nextOverrides[rowId] = false
                update({ overrides: nextOverrides }, { force: true })
              }
              await reload()
            } else {
              setFailed(true)
              setStatus(apiText(result, "msg.saveFailed"))
            }
          } catch {
            setFailed(true)
            setStatus(t("msg.saveFailed"))
          } finally {
            setBusy(false)
          }
        }

        const toggleRowExpanded = (id) =>
          setExpandedRows((previous) => {
            const next = { ...previous }
            if (next[id] === true) delete next[id]
            else next[id] = true
            return next
          })
        /** Hidden `<input type="file">` behind the 「导入」 button. */
        const fileInput = react.useRef(null)

        const entry = selected === "" ? undefined : entries[selected]
        const draft = entry === undefined ? null : entry.value

        /** 描述框：随内容长高，避免双语描述被单行截断。draft 在未选中助手时是 null，依赖项要先取出来。 */
        const descriptionRef = react.useRef(null)
        const draftDescription = draft === null ? "" : draft.description
        // 已保存的底子：用来提示"改了但还没保存"—— 行列表是按它渲染的。
        const savedMode =
          entry === undefined || entry === null || entry.saved === null || entry.saved === undefined
            ? null
            : entry.saved.mode
        react.useEffect(() => {
          const el = descriptionRef.current
          if (el === null || el === undefined) return
          el.style.height = "auto"
          el.style.height = String(Math.min(el.scrollHeight, 200)) + "px"
        }, [draftDescription, selected])

        const payload = entry === undefined ? null : entry.payload
        const editorReady = draft !== null && payload !== null
        const dirty = editorReady && !sameDraft(draft, entry.saved)

        /** Which assistants hold unsaved edits — the list marks them. */
        const dirtyIds = new Set()
        for (const item of list === null ? [] : list) {
          const held = entries[item.id]
          if (held !== undefined && !sameDraft(held.value, held.saved)) dirtyIds.add(item.id)
        }

        const describeError = (error) => String((error && error.message) || error)

        /**
         * Fetch one assistant into `entries`, keeping any unsaved draft it already has.
         *
         * `discard` is the explicit "throw my edits away" path (the reload button), and
         * `force` is "re-read even if this entry is already in memory". Neither is set by
         * ordinary switching: moving between assistants must never race a half-typed prompt.
         */
        const open = async (id, options) => {
          if (typeof id !== "string" || id === "") return
          const force = options !== null && typeof options === "object" && options.force === true
          const discard = options !== null && typeof options === "object" && options.discard === true
          const held = entries[id]
          if (force !== true && held !== undefined) return
          const result = await fetchState(id)
          if (result === null || result === undefined || result.ok !== true) {
            setFailed(true)
            setStatus(apiText(result, "msg.readFailed"))
            return
          }
          const fresh = draftOf(result)
          setEntries((previous) => {
            const before = previous[id]
            const keep = discard !== true && before !== undefined && !sameDraft(before.value, before.saved) ? before.value : fresh
            return { ...previous, [id]: { payload: result, saved: fresh, value: keep } }
          })
          setFailed(false)
        }

        /** Re-read the assistant list and force-reload the one it settles on. */
        const reload = async (preferId, options) => {
          const result = await fetchList()
          if (result === null || result === undefined || result.ok !== true) {
            setFailed(true)
            setListFailed(true)
            setStatus(apiText(result, "msg.readFailed"))
            return
          }
          const assistants = Array.isArray(result.assistants) ? result.assistants : []
          const choices = Array.isArray(result.postures) ? result.postures.filter((item) => item !== null && typeof item === "object" && typeof item.id === "string") : []
          setListFailed(false)
          setList(assistants)
          setPostureChoices(choices)
          setPosture((current) => (choices.some((item) => item.id === current) ? current : choices.length > 0 ? choices[0].id : "develop"))
          const wanted =
            typeof preferId === "string" && preferId !== ""
              ? preferId
              : assistants.some((item) => item.id === selected)
                ? selected
                : assistants.length > 0
                  ? assistants[0].id
                  : ""
          setSelected(wanted)
          if (wanted === "") {
            setEntries({})
            return
          }
          await open(wanted, { force: true, discard: options !== null && typeof options === "object" && options.discard === true })
        }

        /**
         * Refresh the display names only.
         *
         * A save can rename an assistant but never changes ids, so the list needs a new
         * fetch while the selection, the payloads and every draft stay exactly as they are.
         */
        const refreshNames = async () => {
          const result = await fetchList()
          if (result !== null && result !== undefined && result.ok === true) {
            setList(Array.isArray(result.assistants) ? result.assistants : [])
          }
        }

        /**
         * Re-render when the shell's active language changes.
         *
         * `settings.section` no longer receives a namespace-bound `t` (the `locale:`
         * registration option was removed in 0.1.6-alpha.2), so this page translates from its
         * own dictionaries — and must therefore notice a language switch itself. The revision
         * counter is not read in render: bumping it is what re-runs {@link activeLanguage}.
         */
        const [, setLocaleRevision] = react.useState(0)
        react.useEffect(() => {
          const service = localeRef
          if (service === undefined || typeof service.subscribe !== "function") return undefined
          return service.subscribe(() => setLocaleRevision((value) => value + 1))
        }, [])

        react.useEffect(() => {
          let alive = true
          ;(async () => {
            setBusy(true)
            try {
              await reload("")
            } catch (error) {
              if (alive) {
                setFailed(true)
                setStatus(fillPlaceholders(t("status.detail"), { message: t("msg.readFailed"), detail: describeError(error) }))
              }
            } finally {
              if (alive) setBusy(false)
            }
          })()
          return () => {
            alive = false
          }
        }, [])

        /** Change one field of the selected assistant's draft. */
        const update = (patch, options) => {
          // ★ 忙碌期间**不接受**对草稿的修改（issue #6）。
          //
          // 为什么必须在这里拦：保存成功后（下面 `save()` 里）会用服务器归一化的结果
          // `value: normalized` **无条件**覆盖草稿。于是往返期间敲进去的字、翻过的行开关都会
          // 凭空消失，而页面还显示 "Saved" —— 用户丢的是手写的系统提示词。
          //
          // 拦在这一层而不是逐个控件加 `disabled`，是因为它一处覆盖**所有**草稿字段
          // （name / description / prompt / overrides），漏掉任何一个都会重新打开那条丢失路径。
          // 视觉上控件仍可点，但点了不会改状态，所以不会出现"开关翻过去又弹回来"这种骗人的反馈。
          //
          // `{ force: true }` 是给**内部**调用用的（例如「按本线修复」把结果折进草稿）：
          // 那不是用户输入，不受这条守护约束。
          if (busy === true && options?.force !== true) return
          setEntries((previous) => {
            const current = previous[selected]
            if (current === undefined) return previous
            return { ...previous, [selected]: { ...current, value: { ...current.value, ...patch } } }
          })
          setStatus("")
        }

        const pick = async (id) => {
          if (id === selected) {
            setBusy(true)
            try {
              await open(id, { force: true })
            } catch (error) {
              setFailed(true)
              setStatus(fillPlaceholders(t("status.detail"), { message: t("msg.readFailed"), detail: describeError(error) }))
            } finally {
              setBusy(false)
            }
            return
          }
          setSelected(id)
          setDeleteOpen(false)
          setAcknowledged(false)
          setStatus("")
          setBusy(true)
          try {
            await open(id)
          } catch (error) {
            setFailed(true)
            setStatus(fillPlaceholders(t("status.detail"), { message: t("msg.readFailed"), detail: describeError(error) }))
          } finally {
            setBusy(false)
          }
        }

        /** Create a new assistant, or duplicate the selected one. */
        const create = async (options) => {
          if (busy === true) return
          const from = options !== null && typeof options === "object" ? options.from : undefined
          const name =
            options !== null && typeof options === "object" && typeof options.name === "string"
              ? options.name.trim()
              : newName.trim()
          if (name === "") {
            setFailed(true)
            setStatus(t("msg.nameRequired"))
            return
          }
          setBusy(true)
          try {
            const body = { name, locale: activeLanguage(localeRef) }
            if (from !== undefined) body.from = from
            else if (postureChoices.some((item) => item.id === posture)) body.posture = posture
            const result = await postJson(ROUTES.create, body)
            if (result !== null && result !== undefined && result.ok === true) {
              setNewName("")
              setFailed(false)
              setStatus(apiText(result, from === undefined ? "msg.created" : "msg.duplicated"))
              await reload(result.id)
            } else {
              setFailed(true)
              setStatus(apiText(result, "msg.createFailed"))
            }
          } catch (error) {
            setFailed(true)
            setStatus(fillPlaceholders(t("status.detail"), { message: t("msg.createFailed"), detail: describeError(error) }))
          } finally {
            setBusy(false)
          }
        }

        const duplicate = () => create({ from: draft.id, name: copyName(t("assistant.copyName"), draft.name || draft.id) })

        /**
         * Move the selected assistant one slot in the picker order.
         *
         * Reordering must not cost the user their edits, so this reloads with `discard: false`
         * (the default) and only the roster order changes.
         */
        const move = async (direction) => {
          setBusy(true)
          try {
            const result = await postJson(ROUTES.reorder, { id: draft.id, direction })
            if (result !== null && result !== undefined && result.ok === true) {
              setFailed(false)
              setStatus(apiText(result, "msg.reordered"))
              await reload(draft.id)
            } else {
              setFailed(true)
              setStatus(apiText(result, "msg.reorderFailed"))
            }
          } catch (error) {
            setFailed(true)
            setStatus(fillPlaceholders(t("status.detail"), { message: t("msg.reorderFailed"), detail: describe(error) }))
          } finally {
            setBusy(false)
          }
        }

        /** Download the prompt being edited (client-side only — the host is not involved). */
        /**
         * 把编辑器内容换回出厂提示词。
         *
         * 只改**草稿**，不写盘：这样"一键回到官方"既立刻可见，又可被「重新读取」撤销 ——
         * 与页面其余部分一样，一切改动都要点保存才落盘。
         */
        const resetPrompt = () => {
          const factory = typeof draft.factoryPrompt === "string" ? draft.factoryPrompt : ""
          if (factory === "") return
          update({ prompt: factory })
        }

        /** 把某个历史版本载入编辑器。与「恢复出厂」同一条纪律：只改草稿，保存前不落盘。 */
        const loadVersion = async () => {
          const picked = typeof draft.historyPick === "string" ? draft.historyPick : ""
          if (picked === "" || draft.id === undefined) return
          try {
            // 版本键是序号（时间戳会在同一毫秒内撞车 —— 单测就是那样抓到它的）。
            const response = await fetch(ROUTES.history + "?id=" + encodeURIComponent(draft.id) + "&n=" + encodeURIComponent(picked))
            const payload = await asJson(response)
            if (payload === null || payload.ok !== true) {
              setStatus(t("msg.loadFailed"))
              return
            }
            update({ prompt: payload.text })
          } catch (error) {
            setStatus(fillPlaceholders(t("status.detail"), { message: t("msg.loadFailed"), detail: describeError(error) }))
          }
        }

        const exportPrompt = () => {
          try {
            const blob = new Blob([draft.prompt], { type: "text/markdown;charset=utf-8" })
            const url = URL.createObjectURL(blob)
            const link = document.createElement("a")
            link.href = url
            link.download = fileNameFor(draft.name || draft.id) + ".prompt.md"
            document.body.appendChild(link)
            link.click()
            link.remove()
            setTimeout(() => URL.revokeObjectURL(url), 0)
            setFailed(false)
            setStatus(t("msg.exported"))
          } catch (error) {
            setFailed(true)
            setStatus(fillPlaceholders(t("status.detail"), { message: t("msg.exportFailed"), detail: describe(error) }))
          }
        }

        /**
         * Load a prompt file into the EDITOR, not onto disk.
         *
         * The imported text lands in the draft, so it goes through the same review-and-save
         * path (and the same `{{…}}` validation) as anything typed by hand — an import can
         * never silently publish a prompt the renderer would refuse.
         */
        const importPrompt = (event) => {
          const input = event !== null && event !== undefined ? event.target : undefined
          const file = input !== null && input !== undefined && input.files !== undefined ? input.files[0] : undefined
          // Reset so picking the same file twice in a row fires `change` again.
          if (input !== null && input !== undefined) input.value = ""
          if (file === undefined || file === null) return
          if (file.size > 1_000_000) {
            setFailed(true)
            setStatus(t("msg.importTooLarge"))
            return
          }
          const reader = new FileReader()
          reader.onload = () => {
            const text = typeof reader.result === "string" ? reader.result : ""
            if (text.trim() === "") {
              setFailed(true)
              setStatus(t("msg.importEmpty"))
              return
            }
            update({ prompt: text })
            setFailed(false)
            setStatus(t("msg.imported"))
          }
          reader.onerror = () => {
            setFailed(true)
            setStatus(t("msg.importFailed"))
          }
          reader.readAsText(file, "utf-8")
        }

        const remove = async (id) => {
          setBusy(true)
          try {
            const result = await postJson(ROUTES.delete, { id })
            if (result !== null && result !== undefined && result.ok === true) {
              setDeleteOpen(false)
              setAcknowledged(false)
              setFailed(false)
              setStatus(apiText(result, "msg.deleted"))
              setEntries((previous) => {
                const next = { ...previous }
                delete next[id]
                return next
              })
              await reload("")
            } else {
              setFailed(true)
              setStatus(apiText(result, "msg.deleteFailed"))
            }
          } catch (error) {
            setFailed(true)
            setStatus(fillPlaceholders(t("status.detail"), { message: t("msg.deleteFailed"), detail: describeError(error) }))
          } finally {
            setBusy(false)
          }
        }

        /**
         * Ask before deleting.
         *
         * With the shell's `RiskConfirmation` the user must tick an acknowledgement — it is an
         * irreversible loss of their own text. A shell without that atom gets the plain native
         * confirmation instead of losing the guardrail entirely.
         */
        const askDelete = () => {
          if (renderable(A.RiskConfirmation)) {
            setAcknowledged(false)
            setDeleteOpen(true)
            return
          }
          const label = draft.name || draft.id
          if (typeof window !== "undefined" && window.confirm(copyName(t("delete.plainConfirm"), label))) remove(draft.id)
        }

        const pickMode = (mode) => {
          // Switching base mode keeps explicit row overrides: rows that exist in
          // the new mode keep their state, ids absent from it are ignored.
          update({ mode })
        }

        const save = async () => {
          setBusy(true)
          try {
            const result = await postJson(ROUTES.state, {
              id: draft.id,
              mode: draft.mode,
              overrides: draft.overrides,
              prompt: draft.prompt,
              name: draft.name,
              description: draft.description,
            })
            if (result !== null && result !== undefined && result.ok === true) {
              setFailed(false)
              const compositionChanged =
                entry !== undefined &&
                (draft.mode !== entry.saved.mode ||
                  JSON.stringify(draft.overrides) !== JSON.stringify(entry.saved.overrides))
              const spoken =
                result.code === "saved" && compositionChanged !== true ? { ...result, code: "savedPrompt" } : result
              setStatus(apiText(spoken, "msg.saved"))
              // The server normalises what it stores (trimmed name, recomputed overrides),
              // so the just-saved draft is replaced by what it actually wrote. Without this
              // the page would show 「已保存」 and 「未保存」 at the same time.
              const fresh = await fetchState(draft.id)
              if (fresh !== null && fresh !== undefined && fresh.ok === true) {
                const normalized = draftOf(fresh)
                setEntries((previous) => ({
                  ...previous,
                  [draft.id]: { payload: fresh, saved: normalized, value: normalized },
                }))
                await refreshNames()
              } else {
                await reload(draft.id, { discard: true })
              }
            } else {
              setFailed(true)
              setStatus(apiText(result, "msg.saveFailed"))
            }
          } catch (error) {
            setFailed(true)
            setStatus(fillPlaceholders(t("status.detail"), { message: t("msg.saveFailed"), detail: describeError(error) }))
          } finally {
            setBusy(false)
          }
        }

        const assistants = list === null ? [] : list
        const position = assistants.findIndex((item) => item.id === selected)
        const statusClass = failed
          ? "cpfe-status cpfe-err"
          : dirty
            ? "cpfe-status cpfe-dirty"
            : "cpfe-status cpfe-ok"
        const shown = dirty && status === "" ? t("msg.unsaved") : status

        const createGroup = react.createElement(
          "section",
          { className: "cpfe-create" },
          react.createElement("h2", { className: "cpfe-h" }, t("create.heading")),
          react.createElement(SectionHint, { text: t("posture.hint") }),
          postureChoices.length === 0
            ? null
            : react.createElement(
                "div",
                null,
                react.createElement(
                  "div",
                  { className: "cpfe-pills", role: "radiogroup", "aria-label": t("posture.hint") },
                  postureChoices.map((item) =>
                    react.createElement(
                      A.Pill,
                      {
                        key: item.id,
                        active: item.id === posture,
                        disabled: busy,
                        "aria-pressed": item.id === posture,
                        onClick: () => setPosture(item.id),
                      },
                      t("posture." + item.id + ".label", item.id),
                    ),
                  ),
                ),
                react.createElement("p", { className: "cpfe-note" }, t("posture." + posture + ".note", "")),
              ),
          react.createElement(
            "div",
            { className: "cpfe-newrow" },
            react.createElement(A.Input, {
              className: "cpfe-newinput",
              value: newName,
              placeholder: t("assistant.newPlaceholder"),
              "aria-label": t("assistant.newPlaceholder"),
              onKeyDown: (event) => {
                if (event.key === "Enter" && busy !== true) create()
              },
              onChange: (event) => {
                setNewName(event.target.value)
                setStatus("")
              },
            }),
            react.createElement(
              A.Button,
              {
                variant: "outline",
                icon: A.IconPlusOutline16 === undefined ? null : react.createElement(A.IconPlusOutline16),
                disabled: busy,
                onClick: () => create(),
              },
              busy ? t("btn.creating") : t("btn.create"),
            ),
          ),
        )

        // ── assistant list ────────────────────────────────────────────────────
        const assistantList = react.createElement(
          "section",
          null,
          react.createElement("h2", { className: "cpfe-h" }, t("assistant.heading")),
                react.createElement(SectionHint, { text: t("assistant.hint") }),
          list === null
            ? react.createElement(
                "div",
                null,
                react.createElement("p", { className: "cpfe-sub" }, listFailed ? t("msg.readFailed") : t("assistant.loadingList")),
                listFailed
                  ? react.createElement(A.Button, { disabled: busy, onClick: () => reload("") }, t("btn.reload"))
                  : null,
              )
            : assistants.length === 0
              ? react.createElement("p", { className: "cpfe-sub" }, t("assistant.empty"))
              : react.createElement(
                  // 官方的"选择"就是壳的 `Menu`：一个灰底按钮 + chevron，浮层里是选项
                  // （通用设置页的「权限 / 语言 / 工作步骤展示」都是这个形态）。原来是一排 pill
                  // 药丸 + 描边，是页面上最"不像官方"的一块。
                  "div",
                  { className: "cpfe-picker" },
                  renderable(A.Menu)
                    ? react.createElement(A.Menu, {
                        open: assistantOpen,
                        // ★ portal：不把浮层渲染在我们的容器里。设置面板有裁剪与叠层，非 portal 的
                        //   菜单会被裁掉表面，只剩文字"压"在下面的输入框上（实测截图如此）。
                        portal: true,
                        listClassName: "cpfe-menu",
                        align: "start",
                        side: "bottom",
                        selectedId: selected,
                        anchor: react.createElement(
                          A.Button,
                          {
                            variant: "ghost",
                            className: "cpfe-selector",
                            disabled: busy,
                            onClick: () => setAssistantOpen((open) => open !== true),
                          },
                          (assistants.find((item) => item.id === selected) || {}).name || selected || t("assistant.heading"),
                          // 官方设置项的下拉是「文字在左、chevron 在右」（实测「通用设置」页的
                          // 「工作区内修改 / 中文 / 详细」都是这个形态）⇒ 图标放在 children 末尾，
                          // 不走 `icon` prop —— 那个会渲染到文字**前面**，成为页面上唯一一个反向的下拉。
                          renderable(A.IconChevronDownOutline14)
                            ? react.createElement(A.IconChevronDownOutline14, { size: 14 })
                            : null,
                        ),
                        items: assistants.map((item) => ({
                          id: item.id,
                          label:
                            (item.name || item.id) +
                            (dirtyIds.has(item.id) ? " · " + t("msg.unsaved") : "") +
                            (typeof item.broken === "string" && item.broken !== "" ? " · " + t("assistant.broken") : ""),
                        })),
                        onSelect: (id) => {
                          setAssistantOpen(false)
                          pick(id)
                        },
                        onClose: () => setAssistantOpen(false),
                      })
                    : react.createElement(
                        "div",
                        { className: "cpfe-pills" },
                        assistants.map((item) =>
                          react.createElement(
                            "span",
                            { key: item.id, className: "cpfe-pill-wrap" },
                            react.createElement(
                              A.Pill,
                              { active: item.id === selected, disabled: busy, title: item.id, onClick: () => pick(item.id) },
                              item.name || item.id,
                            ),
                            dirtyIds.has(item.id) ? react.createElement(A.Tag, { tone: "warning" }, t("msg.unsaved")) : null,
                            typeof item.broken === "string" && item.broken !== ""
                              ? react.createElement(
                                  A.Tag,
                                  { tone: "danger", title: t("assistant.brokenHint") },
                                  Array.isArray(item.brokenRows) && item.brokenRows.length > 0
                                    ? fillPlaceholders(t("assistant.brokenRows"), { count: item.brokenRows.length })
                                    : t("assistant.broken"),
                                )
                              : null,
                          ),
                        ),
                      ),
                  dirtyIds.has(selected) ? react.createElement(A.Tag, { tone: "warning" }, t("msg.unsaved")) : null,
                  typeof (assistants.find((item) => item.id === selected) || {}).broken === "string"
                    ? react.createElement(
                        A.Tag,
                        { tone: "danger", title: t("assistant.brokenHint") },
                        (() => {
                          const current = assistants.find((item) => item.id === selected) || {}
                          return Array.isArray(current.brokenRows) && current.brokenRows.length > 0
                            ? fillPlaceholders(t("assistant.brokenRows"), { count: current.brokenRows.length })
                            : t("assistant.broken")
                        })(),
                      )
                    : null,
                ),
          editorReady
            ? react.createElement(
                "div",
                { className: "cpfe-actions" },
                react.createElement(
                  A.Button,
                  {
                    variant: "outline",
                    size: "sm",
                    icon: A.IconChevronUpOutline14 === undefined ? null : react.createElement(A.IconChevronUpOutline14),
                    disabled: busy || position <= 0,
                    onClick: () => move("up"),
                  },
                  t("btn.moveUp"),
                ),
                react.createElement(
                  A.Button,
                  {
                    variant: "outline",
                    size: "sm",
                    icon: A.IconChevronDownOutline14 === undefined ? null : react.createElement(A.IconChevronDownOutline14),
                    disabled: busy || position === -1 || position >= assistants.length - 1,
                    onClick: () => move("down"),
                  },
                  t("btn.moveDown"),
                ),
                react.createElement(
                  A.Button,
                  {
                    variant: "outline",
                    size: "sm",
                    icon: A.IconCopyOutline16 === undefined ? null : react.createElement(A.IconCopyOutline16),
                    disabled: busy,
                    onClick: duplicate,
                  },
                  t("btn.duplicate"),
                ),
                react.createElement(
                  A.Button,
                  {
                    variant: "ghost",
                    size: "sm",
                    className: "cpfe-danger",
                    icon: A.IconTrashOutline16 === undefined ? null : react.createElement(A.IconTrashOutline16),
                    disabled: busy,
                    onClick: askDelete,
                  },
                  t("btn.delete"),
                ),
              )
            : null,
          createGroup,
          typeof (assistants.find((item) => item.id === selected) || {}).broken === "string"
            ? react.createElement("p", { className: "cpfe-note" }, t("assistant.brokenHint"))
            : null,
          null,
        )

        // ── editor ────────────────────────────────────────────────────────────
        const editorSections = editorReady
          ? [
              react.createElement(
                "section",
                { key: "name" },
                react.createElement("h2", { className: "cpfe-h" }, t("name.heading")),
                react.createElement(SectionHint, { text: t("name.hint") }),
                react.createElement(A.Input, {
                  className: "cpfe-field",
                  value: draft.name,
                  placeholder: t("name.placeholder"),
                  "aria-label": t("name.heading"),
                  onChange: (event) => update({ name: event.target.value }),
                }),
                // 描述用 textarea 而不是单行 Input：shell 的组件里没有多行输入，而双语描述在单行框里
                // 会被截断（实测界面上只看到 "… / Ful"）。高度随内容自适应，样式沿用同一批语义变量。
                react.createElement("textarea", {
                  ref: descriptionRef,
                  className: "cpfe-field cpfe-desc",
                  value: draft.description,
                  rows: 2,
                  // 忙碌期间只读（issue #6）。**描述框在 DOM 里排在提示词编辑器前面**，
                  // 所以只给 `.cpfe-editor` 加 readOnly 是不够的 —— 实测就是这么漏掉的。
                  readOnly: busy === true,
                  placeholder: t("name.descriptionPlaceholder"),
                  "aria-label": t("name.descriptionPlaceholder"),
                  onChange: (event) => update({ description: event.target.value }),
                }),
              ),
              react.createElement(
                "section",
                { key: "mode" },
                react.createElement("h2", { className: "cpfe-h" }, t("mode.heading")),
                react.createElement(SectionHint, { text: t("mode.hint") }),
                // 降级态（本机这条线没有可读的出厂组成）：不给可点的模式药丸，改说一句为什么 ——
                // 点了也只会拿到一个服务端错误，那比置灰更糟。
                draft.baseUnavailable !== null
                  ? react.createElement("p", { className: "cpfe-note" }, t("mode.unavailable"))
                  : react.createElement(
                      "div",
                      { className: "cpfe-pills" },
                      payload.modes.map((mode) =>
                        react.createElement(
                          A.Pill,
                          {
                            key: mode.id,
                            active: draft.mode === mode.id,
                            disabled: busy,
                            "aria-pressed": draft.mode === mode.id,
                            onClick: () => pickMode(mode.id),
                          },
                          t("base." + mode.id + ".label", mode.label),
                        ),
                      ),
                    ),
                draft.baseUnavailable !== null
                  ? null
                  : react.createElement(
                      "p",
                      { className: "cpfe-note" },
                      payload.modes.reduce((note, mode) => (mode.id === draft.mode ? t("base." + mode.id + ".note", mode.note) : note), ""),
                    ),
                // 底子改过但还没保存时明说一句：行列表是按**已保存**的组成渲染的，审阅把它记成了"点了没反应"。
                draft.mode !== savedMode
                  ? react.createElement(
                      "p",
                      { className: "cpfe-note cpfe-base-pending" },
                      fillPlaceholders(t("mode.pendingRows"), { mode: t("base." + draft.mode + ".label", draft.mode) }),
                    )
                  : null,
              ),
              react.createElement(
                "section",
                { key: "rows" },
                draft.baseUnavailable !== null
                  ? react.createElement("p", { className: "cpfe-note" }, t("rows.unavailable"))
                  : react.createElement(
                      "div",
                      null,
                      react.createElement("input", {
                        className: "cpfe-filter",
                        value: rowQuery,
                        placeholder: t("rows.filterPlaceholder"),
                        "aria-label": t("rows.filterPlaceholder"),
                        onChange: (event) => setRowQuery(event.target.value),
                      }),
                      react.createElement(RowList, {
                        expanded: expandedRows,
                        onToggleExpand: toggleRowExpanded,
                        rows: filterRows(payload.rows, rowQuery, t),
                        overrides: draft.overrides,
                        onToggle: (id, next) => {
                          if (draft.mode !== savedMode) return
                          update({ overrides: { ...draft.overrides, [id]: next } })
                        },
                        depth: 0,
                        t: t,
                        disabled: busy === true || draft.mode !== savedMode,
                      }),
                    ),
              ),
              react.createElement(
                "section",
                { key: "prompt" },
                react.createElement(
                  "div",
                  { className: "cpfe-sec-head" },
                  react.createElement("h2", { className: "cpfe-h" }, t("prompt.heading")),
                  react.createElement(
                    "div",
                    { className: "cpfe-actions" },
                    react.createElement(
                      A.Button,
                      {
                        variant: "outline",
                        size: "sm",
                        icon:
                          A.IconDownloadOutline16 === undefined ? null : react.createElement(A.IconDownloadOutline16),
                        disabled: busy || draft.prompt === "",
                        onClick: exportPrompt,
                      },
                      t("btn.export"),
                    ),
                    react.createElement(
                      A.Button,
                      {
                        variant: "outline",
                        size: "sm",
                        disabled: busy,
                        onClick: () => {
                          const node = fileInput.current
                          if (node !== null && node !== undefined) node.click()
                        },
                      },
                      t("btn.import"),
                    ),
                    react.createElement(
                      A.Button,
                      {
                        variant: "ghost",
                        size: "sm",
                        disabled:
                          busy ||
                          typeof draft.factoryPrompt !== "string" ||
                          draft.factoryPrompt === "" ||
                          draft.prompt === draft.factoryPrompt,
                        onClick: resetPrompt,
                        title: t("btn.resetHint"),
                      },
                      t("btn.reset"),
                    ),
                    // The picker itself is invisible; the button above is the affordance. The
                    // file is read locally and lands in the editor, never straight on disk.
                    react.createElement("input", {
                      ref: fileInput,
                      type: "file",
                      accept: ".md,.markdown,.txt,text/*",
                      style: { display: "none" },
                      onChange: importPrompt,
                      "aria-hidden": "true",
                      tabIndex: -1,
                    }),
                  ),
                ),
                react.createElement(SectionHint, { text: t("prompt.hint") }),
                react.createElement("textarea", {
                  className: "cpfe-editor",
                  value: draft.prompt,
                  spellCheck: false,
                  // 忙碌期间只读（issue #6）：`update()` 那一层已经会丢弃改动，这里再给一个
                  // **看得见**的信号 —— 否则用户会以为自己在编辑，而字根本没进去。
                  // 用 readOnly 而不是 disabled：仍然可以选中/复制，只是改不了。
                  readOnly: busy === true,
                  "aria-busy": busy === true,
                  "aria-label": t("prompt.heading"),
                  onChange: (event) => update({ prompt: event.target.value }),
                }),
                // 改动历史：谁在什么时候改过。之前这里只有"当前文本"，所以会话内的工具
                // （或手工编辑）改掉提示词时，用户既看不见也回不去。
                draft.history.length === 0
                  ? null
                  : react.createElement(
                      "div",
                      { className: "cpfe-history" },
                      react.createElement("span", { className: "cpfe-history-label" }, t("history.label")),
                      // 与助手切换同一个形态：官方的下拉是壳的 Menu（灰底按钮 + chevron + 浮层），
                      // 而不是浏览器原生 `<select>`（后者的外观由浏览器决定，与官方界面完全不同）。
                      renderable(A.Menu)
                        ? react.createElement(A.Menu, {
                            open: historyOpen,
                            portal: true,
                            listClassName: "cpfe-menu",
                            align: "start",
                            side: "bottom",
                            selectedId: draft.historyPick,
                            anchor: react.createElement(
                              A.Button,
                              {
                                variant: "ghost",
                                className: "cpfe-selector",
                                disabled: busy,
                                onClick: () => setHistoryOpen((open) => open !== true),
                              },
                              draft.historyPick === ""
                                ? t("history.pick")
                                : (() => {
                                    const entry = draft.history.find((item) => String(item.n) === draft.historyPick)
                                    return entry === undefined
                                      ? t("history.pick")
                                      : formatWhen(entry.at) + " · " + t("history.by." + entry.by)
                                  })(),
                              renderable(A.IconChevronDownOutline14)
                                ? react.createElement(A.IconChevronDownOutline14, { size: 14 })
                                : null,
                            ),
                            items: draft.history.map((entry) => ({
                              id: String(entry.n),
                              label: formatWhen(entry.at) + " · " + t("history.by." + entry.by) + " · " + String(entry.bytes) + " B",
                            })),
                            onSelect: (id) => {
                              setHistoryOpen(false)
                              update({ historyPick: id })
                            },
                            onClose: () => setHistoryOpen(false),
                          })
                        : react.createElement(
                            "select",
                            {
                              value: draft.historyPick,
                              "aria-label": t("history.label"),
                              onChange: (event) => update({ historyPick: event.target.value }),
                            },
                            react.createElement("option", { value: "" }, t("history.pick")),
                            ...draft.history.map((entry) =>
                              react.createElement(
                                "option",
                                { key: String(entry.n), value: String(entry.n) },
                                formatWhen(entry.at) + " · " + t("history.by." + entry.by) + " · " + String(entry.bytes) + " B",
                              ),
                            ),
                          ),
                      react.createElement(
                        A.Button,
                        {
                          variant: "outline",
                          size: "sm",
                          disabled: busy || draft.historyPick === "",
                          title: t("history.hint"),
                          onClick: loadVersion,
                        },
                        t("history.load"),
                      ),
                    ),
              ),
            ]
          : []

        return react.createElement(
          "div",
          // `cpfe-busy` 是 issue #6 的兜底：面板里**所有**会改草稿的控件在忙碌期间一律不可交互。
          // 逐个控件加 `disabled` 会漏（本轮就漏了行开关与描述框），一个根类 + 一条 CSS 反而漏不掉。
          { className: busy === true ? "cpfe cpfe-busy" : "cpfe" },
          assistantList,
          null,
          // 配了却不生效的项：主动点名，而不是让用户对着"我明明写了"发呆。
          // `draft` 在没选中任何助手时是 null（列表还没加载完 / 一个都没有）—— 这里必须先守卫，
          // 否则整块设置页崩掉（实测：浏览器验收当场报 Cannot read properties of null）。
          draft === null || draft.warnings === undefined || draft.warnings.length === 0
            ? null
            : react.createElement(
                "div",
                { className: "cpfe-warns" },
                ...draft.warnings.map((code) =>
                  react.createElement(
                    "div",
                    { key: code, className: "cpfe-warn-row" },
                    react.createElement(
                      "p",
                      { className: "cpfe-warn" },
                      // 图标与文本分成两个节点：文本单独包一层 span，`align-items:flex-start`
                      // 才能把图标钉在**第一行**，而不是让它在一段多行告警里垂直居中。
                      // 壳没提供图标时退回原来的 "⚠" 字符。
                      renderable(A.IconWarningOutline14)
                        ? react.createElement(A.IconWarningOutline14, { size: 14 })
                        : "⚠",
                      react.createElement("span", null, t("warn." + code)),
                    ),
                    // 平台自己给的那句话（`state.broken`，形如
                    // `tool-workflow (@deepseek-ai/dsh-tool-workflow): waiting for workflowEngine`）。
                    //
                    // ★ 以前它只在 API 载荷里，**页面上根本不渲染** —— 而 `warn.presetBroken` 的文案却写着
                    //   「宿主给的原因见「详情」」，那个「详情」里从来没有它。文本在说谎，用户按提示去找也找不到。
                    //   现在把它原样印在告警下面：那是平台的原始信息、不是我们的行文，所以用等宽体单独成行。
                    code === "presetBroken" && typeof draft.broken === "string" && draft.broken !== ""
                      ? react.createElement(
                          "p",
                          { className: "cpfe-note cpfe-mono", title: draft.broken },
                          t("detail.hostReason") + draft.broken,
                        )
                      : null,
                    // 本线起不来的行可以一键修（只关掉那几行；用户创建它的那个版本可能早于播种改为派生的版本）。
                    //
                    // ★ 两个 code 都要给按钮：`presetBroken` 说的是"平台判它 broken，而『无法解析』解释不了"
                    //   —— 那正是**修复按钮唯一还帮得上的时候**。原先只认 `unresolvableRows`，于是
                    //   第一轮修复之后 `unresolvable` 变空、按钮消失，而告警文案还在叫用户"点右侧的
                    //   按本线修复"。实测（2026-10-01，桌面端）就是卡在这一步：能看见病，没有药。
                    code === "unresolvableRows" || code === "presetBroken"
                      ? react.createElement(
                          A.Button,
                          { disabled: busy, onClick: repairRowsNow },
                          t("btn.repair"),
                        )
                      : null,
                  ),
                ),
              ),
          ...(editorReady ? editorSections.filter((section) => section.key === "prompt") : []),
          ...(editorReady ? editorSections.filter((section) => section.key === "name") : []),
          react.createElement(
            "div",
            { className: "cpfe-bar" },
            react.createElement(
              A.Button,
              {
                variant: "primary",
                icon: A.IconCheckOutline14 === undefined ? null : react.createElement(A.IconCheckOutline14),
                disabled: busy || editorReady === false || dirty === false,
                onClick: save,
              },
              busy ? t("btn.saving") : t("btn.save"),
            ),
            react.createElement(
              A.Button,
              {
                variant: "outline",
                icon: A.IconRefreshOutline16 === undefined ? null : react.createElement(A.IconRefreshOutline16),
                disabled: busy,
                // Explicitly destructive of the local draft, so it says so while there is
                // one: switching assistants never discards edits, and this button is the
                // one place that does.
                onClick: () => {
                  if (dirty && typeof window !== "undefined" && window.confirm(t("btn.reloadConfirm")) !== true) return
                  reload(selected, { discard: true })
                },
              },
              dirty ? t("btn.reloadDiscard") : t("btn.reload"),
            ),
            react.createElement("span", { className: statusClass }, shown),
          ),
          editorReady
            ? react.createElement(
                "p",
                {
                  className: "cpfe-version",
                  title: String(payload.compositionPath ?? "") + "\n" + t("meta.versionHint"),
                },
                "v" + String(payload.version ?? "?"),
              )
            : null,
          ...(editorReady ? editorSections.filter((section) => section.key === "mode") : []),
          editorReady
            ? react.createElement(
                "section",
                null,
                react.createElement(
                  "button",
                  {
                    type: "button",
                    "data-cap": "toggle",
                    className: "cpfe-disclosure",
                    "aria-expanded": capOpen,
                    "aria-label": capOpen ? t("cap.collapse") : t("cap.expand"),
                    onClick: () => setCapOpen((open) => open !== true),
                  },
                  renderable(capOpen ? A.IconChevronDownOutline14 : A.IconChevronRightOutline14)
                    ? react.createElement(capOpen ? A.IconChevronDownOutline14 : A.IconChevronRightOutline14, { size: 14 })
                    : capOpen
                      ? "▾"
                      : "▸",
                  t("rows.heading"),
                ),
                capOpen
                  ? react.createElement(
                      "div",
                      null,
                      react.createElement(SectionHint, { text: t("cap.hint") }),
                      editorSections.filter((section) => section.key === "rows"),
                    )
                  : null,
              )
            : null,
          // The confirmation is a portal: rendering it here keeps every piece of this page's
          // state in one component.
          renderable(A.RiskConfirmation) && editorReady
            ? react.createElement(A.RiskConfirmation, {
                open: deleteOpen,
                title: fillPlaceholders(t("delete.title"), { name: draft.name || draft.id }),
                description: fillPlaceholders(t("delete.description"), { id: draft.id, name: draft.name || draft.id }),
                acknowledgeLabel: t("delete.acknowledge"),
                cancelLabel: t("btn.cancel"),
                closeLabel: t("delete.close"),
                confirmLabel: t("delete.confirm"),
                acknowledged,
                disabled: busy,
                onAcknowledgedChange: setAcknowledged,
                onCancel: () => {
                  setDeleteOpen(false)
                  setAcknowledged(false)
                },
                onConfirm: () => {
                  if (acknowledged) remove(draft.id)
                },
              })
            : null,
        )
      }

      function apply(ctx) {
        try {
          ensureStyles()
          const locale = ctx.get("locale")
          localeRef = locale
          // Bound once so the nav label thunk can translate at projection time.
          const navT = locale === undefined ? null : locale.bind(NS)
          if (locale !== undefined) {
            ctx.effect(() => {
              try {
                return locale.register(NS, TRANSLATIONS)
              } catch (error) {
                // The service keeps ONE dictionary per (namespace, locale), so a second apply
                // of this bundle — which is what an HMR swap looks like — is refused. Aborting
                // apply() here would cost the page its slot registration, and the dictionaries
                // it wanted are inlined in this file anyway: report and carry on.
                console.warn("dsh-custom-mode: 词典注册被拒，改用内置词典：" + describe(error))
                return () => {}
              }
            }, "custom-mode: dictionaries")
            // Self-check. A namespace this bundle just registered must answer its own key; if
            // the shell cannot answer, say so in the console instead of leaving a page of raw
            // keys (`assistant.heading`) for the user to discover. translate() falls back to
            // the inlined copy, so the page renders correctly either way.
            if (navT !== null && navT("nav") === "nav") {
              console.warn("dsh-custom-mode: 词典注册后宿主仍查不到 " + NS + "，页面已改用内置词典。")
            }
          }
          const slots = ctx.get("slots")
          if (slots === undefined) {
            console.warn("dsh-custom-mode: slots service unavailable; settings page not registered")
            return
          }
          slots.inject("settings.section", () => {
            // `locale: NS` is the shipped contract: the shell then hands the
            // component a bound `t` in props.
            //
            // The nav entry keeps the localized default rather than following the
            // selected assistant's name: with several assistants there is no single
            // name an entry called 「自定义模式」 could honestly show.
            return slots.register(
              {
                name: "settings.section",
                id: "custom-system-prompt",
                order: 21,
                label: () => translate("nav", undefined, navT === null ? undefined : navT),
                locale: NS,
              },
              CustomModeSection,
            )
          })
        } catch (error) {
          console.error("dsh-custom-mode: apply() failed", error)
        }
      }

      // Declared like every shipped settings-section plugin: the registry guards
      // service reads, and the settings shell owns the target slot.
      const inject = ["slots", "locale"]

      exports.apply = apply
      exports.inject = inject
      return module.exports
    },
  })
} catch (error) {
  // Never take the page down with us. The settings page simply will not appear.
  try {
    console.error("dsh-custom-mode: browser half failed to register", error)
  } catch {
    /* logging is best-effort */
  }
}
