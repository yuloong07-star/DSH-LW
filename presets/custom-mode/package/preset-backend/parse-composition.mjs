/**
 * A deliberately small YAML reader, sized to `agent.cordis.yml`.
 *
 * ## Why this exists
 *
 * The declarative backend (dsh ≥ 0.1.7) needs the composition as **JS row objects** to hand to
 * `agentPresets.register()`; there is no file for the platform to scan any more. Three routes were
 * ruled out by measurement before writing this:
 *
 *  1. **Borrow the host's parser** — `createRequire(import.meta.url).resolve('@deepseek-ai/cordis-plugin-loader')`
 *     fails from inside a profile-installed plugin (measured), so the loader's YAML entry is unreachable.
 *  2. **Reconstruct the rows from `compositionInventory()`** — it returns a **flattened** row list
 *     (`entryId` / `moduleName` / `enabled`), so the `isolate` groups that service rows must sit inside
 *     are lost. The composition's own comments say a service row outside such a group collides with
 *     other presets at mount, so flattening is not an option.
 *  3. **Skip the text and re-render** — the composer works by *text surgery* (it preserves comments and
 *     `!!js` predicates byte-for-byte), so it has no structured row model to hand out.
 *
 * ## What it supports, and what it refuses
 *
 * Only what this feature's own files contain: block sequences, nested block mappings, block scalars
 * (`|`, `|-`, `>`, `>-`), quoted and plain scalars, comments, and the `!!js` tag. Anything else is
 * refused with a located error rather than guessed — a mis-read row silently changes what the model
 * sees, which is the failure this repository least tolerates.
 *
 * `!!js` values are returned as `{ __js: "<source>" }` so callers decide how to evaluate them; this
 * module never runs anything.
 */

/** Thrown with the offending line number so a broken composition is diagnosable, not mysterious. */
export class CompositionParseError extends Error {
  constructor(message, line) {
    super(`${message}（第 ${line} 行）`)
    this.name = 'CompositionParseError'
    this.line = line
  }
}

const isBlank = (line) => line.trim() === '' || line.trim().startsWith('#')
const indentOf = (line) => line.length - line.trimStart().length

/**
 * Parse a composition document into plain JS.
 *
 * @param {string} text - the composition file's contents.
 * @returns {any} the document (a block sequence for a composition file).
 */
export function parseComposition(text) {
  const lines = String(text).split('\n')
  const state = { lines, index: 0 }
  skipBlank(state)
  if (state.index >= lines.length) return null
  return parseNode(state, indentOf(lines[state.index]))
}

function skipBlank(state) {
  while (state.index < state.lines.length && isBlank(state.lines[state.index])) state.index += 1
}

/** The next content line, or undefined. */
function peek(state) {
  skipBlank(state)
  return state.lines[state.index]
}

/**
 * Parse the block starting at the current line, whose entries are indented at least `indent`.
 * A line beginning with `- ` opens a sequence; anything else opens a mapping.
 */
function parseNode(state, indent) {
  const line = peek(state)
  if (line === undefined) return null
  return /^\s*-(\s|$)/.test(line) ? parseSequence(state, indent) : parseMapping(state, indent)
}

function parseSequence(state, indent) {
  const out = []
  for (;;) {
    const line = peek(state)
    if (line === undefined || indentOf(line) < indent || !/^\s*-(\s|$)/.test(line)) break
    const lineNo = state.index + 1
    const rest = line.trimStart().slice(1)
    // `- ` on its own, or `- key: value` inline.
    const inline = rest.trimStart()
    if (inline === '') {
      state.index += 1
      out.push(parseNode(state, indent + 1))
    } else if (/^[^:]+:(\s|$)/.test(inline)) {
      // Inline mapping start: rewrite it as a mapping at its own column and re-parse.
      state.lines[state.index] = ' '.repeat(indent + 2) + inline
      out.push(parseMapping(state, indent + 2))
    } else {
      state.index += 1
      out.push(parseScalar(inline, lineNo))
    }
  }
  return out
}

function parseMapping(state, indent) {
  const out = {}
  for (;;) {
    const line = peek(state)
    if (line === undefined || indentOf(line) < indent) break
    if (/^\s*-(\s|$)/.test(line)) break
    const lineNo = state.index + 1
    const match = /^([A-Za-z_][\w.-]*)\s*:(.*)$/.exec(line.trim())
    if (match === null) throw new CompositionParseError(`无法把这一行读成映射项：${JSON.stringify(line.trim())}`, lineNo)
    const key = match[1]
    const tail = match[2]
    state.index += 1
    if (tail.trim() === '') {
      // Value is a nested block (or null when nothing is indented under it).
      const next = peek(state)
      if (next === undefined || indentOf(next) <= indent) out[key] = null
      else out[key] = parseNode(state, indentOf(next))
    } else if (/^[|>][-+]?\d*\s*$/.test(tail.trim())) {
      out[key] = parseBlockScalar(state, indent, tail.trim())
    } else {
      out[key] = parseScalar(tail.trim(), lineNo)
    }
  }
  return out
}

/** A `|`/`>` block scalar: consume every more-indented line, keeping its text. */
function parseBlockScalar(state, indent, header) {
  const fold = header.startsWith('>')
  const stripFinal = header.includes('-')
  const body = []
  let blockIndent = -1
  while (state.index < state.lines.length) {
    const line = state.lines[state.index]
    if (line.trim() !== '' && indentOf(line) <= indent) break
    body.push(line)
    state.index += 1
  }
  while (body.length > 0 && body[0].trim() === '') body.shift()
  while (body.length > 0 && body[body.length - 1].trim() === '') body.pop()
  if (blockIndent === -1) {
    blockIndent = body.reduce((min, l) => (l.trim() === '' ? min : Math.min(min, indentOf(l))), Number.MAX_SAFE_INTEGER)
    if (blockIndent === Number.MAX_SAFE_INTEGER) blockIndent = indent + 2
  }
  const text = body.map((l) => l.slice(blockIndent)).join('\n')
  const value = fold ? text.replace(/\n(?!\n)/g, ' ') : text
  return stripFinal ? value : value + '\n'
}

/**
 * A scalar: `!!js` tagged, quoted, or plain.
 *
 * Plain scalars stay strings — the composer only ever writes strings, booleans and numbers, and a
 * bare word like `standard` must not become a date or a number.
 */
export function parseScalar(raw, lineNo = 0) {
  const value = raw.trim()
  if (value === '') return null
  if (value.startsWith('!!js')) {
    const source = value.slice(4).trim()
    if (source === '') throw new CompositionParseError('!!js 后面没有表达式', lineNo)
    return { __js: source }
  }
  if (value === 'true') return true
  if (value === 'false') return false
  if (value === 'null' || value === '~') return null
  if (/^-?\d+$/.test(value)) return Number(value)
  if (/^"(?:[^"\\]|\\.)*"$/.test(value)) {
    try {
      return JSON.parse(value)
    } catch {
      throw new CompositionParseError(`双引号标量无法转义：${value}`, lineNo)
    }
  }
  if (/^'(?:[^']|'')*'$/.test(value)) return value.slice(1, -1).replace(/''/g, "'")
  if (/^[[{]/.test(value)) throw new CompositionParseError(`不支持流式集合：${value}`, lineNo)
  return value
}
