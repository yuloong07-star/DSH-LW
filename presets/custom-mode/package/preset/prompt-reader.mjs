/**
 * Persona row for the `custom` agent preset: the system prompt comes from a
 * plain text file the user owns and edits, not from this composition.
 *
 * The file is re-read on EVERY prompt assembly (the agent loop assembles the
 * system prompt before each model step), so an edit takes effect on the next
 * step of the current session — no restart, no new session. Each read is
 * guarded by an mtime/size check, so a steady file costs one `stat` per step
 * and no re-read.
 *
 * This row registers `deployment:persona-prefix` and `deployment:persona-suffix`
 * in the agent's own scope, shadowing the deployment-wide persona the same way
 * `@deepseek-ai/dsh-persona` does. It is deliberately NOT that package: its
 * `prefix` is a static string resolved at mount, which cannot reflect an edit.
 *
 * Config:
 *   path      - absolute path of the prompt file (default: prompt.md beside this module)
 *   suffix    - persona suffix template (default: the working-directory line)
 *   complete  - true makes this text the ONLY system prompt section, dropping
 *               harness identity, tool guidance and the suffix. Default false:
 *               the file replaces the persona paragraph while the coding
 *               agent's tool guidance stays.
 *   fallback  - text used when the file is missing or unreadable (default: '')
 */

import { readFileSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/** Section name the prompt registry uses for the persona prefix. */
const PERSONA_PREFIX_SECTION = 'deployment:persona-prefix'
/** Section name the prompt registry uses for the persona suffix. */
const PERSONA_SUFFIX_SECTION = 'deployment:persona-suffix'
/** Sort order of the persona prefix (matches the registry's own placement). */
const PERSONA_PREFIX_ORDER = 0
/** Sort order of the persona suffix, after all first-party guidance. */
const PERSONA_SUFFIX_ORDER = 10200

const DEFAULT_SUFFIX = 'Your working directory is {{cwd}}.'
const DEFAULT_FALLBACK =
  'You are a coding agent powered by the {{model}} model.\n\n' +
  '(No custom system prompt is set yet. Open Settings -> 自定义模式 to write one.)'

/**
 * Owned, content-keyed cache of one prompt file.
 *
 * A cached entry is reused only while the file's mtime and size are unchanged,
 * so an edit written by any tool — this harness, an editor, a `cp` — is picked
 * up on the next assembly. On any read error the last good text is served
 * instead of an empty prompt, so a transient failure never blanks the agent.
 */
class PromptFile {
  constructor(path, fallback) {
    this.path = path
    this.fallback = fallback
    this.text = undefined
    this.mtimeMs = -1
    this.size = -1
  }

  /** Current prompt text; re-reads the file when it changed since the last call. */
  read() {
    let info
    try {
      info = statSync(this.path)
    } catch {
      // Missing file: keep the last good text, else the fallback.
      return this.text === undefined ? this.fallback : this.text
    }
    if (this.text !== undefined && info.mtimeMs === this.mtimeMs && info.size === this.size) {
      return this.text
    }
    try {
      const next = readFileSync(this.path, 'utf8')
      if (next.trim() !== '') this.text = next
      this.mtimeMs = info.mtimeMs
      this.size = info.size
      return this.text === undefined ? this.fallback : this.text
    } catch {
      return this.text === undefined ? this.fallback : this.text
    }
  }
}

export const name = 'custom-prompt-file'

/** The prompt registry is a hard dependency: without it there is no identity. */
export const inject = ['systemPrompt']

export function apply(ctx, config = {}) {
  const path = config.path ?? fileURLToPath(new URL('./prompt.md', import.meta.url))
  const suffix = config.suffix ?? DEFAULT_SUFFIX
  const fallback = config.fallback ?? DEFAULT_FALLBACK
  const file = new PromptFile(path, fallback)

  ctx.effect(
    () =>
      ctx.systemPrompt.section({
        name: PERSONA_PREFIX_SECTION,
        order: PERSONA_PREFIX_ORDER,
        // A provider, not a string: evaluated at every assembly.
        text: () => file.read(),
        complete: config.complete === true,
      }),
    'custom-prompt.prefix',
  )

  // `complete: true` restores the prefix as the sole section, so a suffix would
  // be discarded anyway; skipping its registration keeps that explicit.
  if (config.complete !== true) {
    ctx.effect(
      () =>
        ctx.systemPrompt.section({
          name: PERSONA_SUFFIX_SECTION,
          order: PERSONA_SUFFIX_ORDER,
          text: suffix,
        }),
      'custom-prompt.suffix',
    )
  }
}
