/**
 * The preset's display metadata: `preset.yml` next to the composition.
 *
 * This file is what every picker shows — the session's mode chooser and the
 * settings nav. `agent-presets` re-reads the preset roots on each roster read, so
 * a change here appears without a process restart; only a NEW session picks it up,
 * exactly like a composition change.
 *
 * The directory is a parameter because this feature manages SEVERAL assistants:
 * each one owns its own `preset.yml`, and one shared constant would make every save
 * rename the same assistant.
 *
 * Only two scalar keys are handled, so a full YAML parser is unnecessary. Writing
 * always emits double-quoted, escaped scalars: a user-supplied colon, leading
 * dash, or newline would otherwise restructure the document, and a malformed
 * `preset.yml` makes the mode vanish from the pickers.
 */

import { readFileSync, writeFileSync } from 'node:fs'
import { writeAtomic } from './atomic.mjs'
import { join } from 'node:path'
import { PRESET_DIR } from './paths.mjs'

/**
 * Absolute path of one preset directory's metadata file.
 *
 * @param {string} [directory] - the preset directory (defaults to the legacy `custom`).
 * @returns {string} absolute path.
 */
export function presetMetaPath(directory = PRESET_DIR) {
  return join(directory, 'preset.yml')
}

/** The legacy single-preset metadata path, kept for callers that address it directly. */
export const PRESET_META_PATH = presetMetaPath()

/**
 * YAML double-quoted scalars use C-style escapes — a superset of JSON's, so `JSON.parse` is not enough
 * (`\e`, `\N`, `\_`, `\L`, `\P`, `\xNN`, `\UNNNNNNNN` have no JSON equivalent).
 *
 * Measured (`probes/probe-io.mjs` G): the previous implementation was a plain backslash-stripper, so
 * `"tab\there"` read back as `tabthere` — the name lost a character every round trip. Four control
 * characters were checked and all four were wrong, which means a name the page shows and the name the
 * platform stores drift apart permanently.
 */
const DOUBLE_QUOTED_ESCAPES = {
  0: '\0',
  a: '\x07',
  b: '\b',
  t: '\t',
  n: '\n',
  v: '\v',
  f: '\f',
  r: '\r',
  e: '\x1b',
  ' ': ' ',
  '"': '"',
  '/': '/',
  '\\': '\\',
  N: '\u0085',
  _: '\u00a0',
  L: '\u2028',
  P: '\u2029',
}

/** Decode the inside of a YAML double-quoted scalar. */
function decodeDoubleQuoted(inner) {
  let out = ''
  for (let i = 0; i < inner.length; i += 1) {
    const ch = inner[i]
    if (ch !== '\\') {
      out += ch
      continue
    }
    const next = inner[i + 1]
    if (next === undefined) {
      out += '\\'
      break
    }
    if (next === 'x' || next === 'u' || next === 'U') {
      const width = next === 'x' ? 2 : next === 'u' ? 4 : 8
      const digits = inner.slice(i + 2, i + 2 + width)
      const code = Number.parseInt(digits, 16)
      if (digits.length === width && Number.isNaN(code) === false) {
        out += String.fromCodePoint(code)
        i += 1 + width
        continue
      }
      out += next
      i += 1
      continue
    }
    if (Object.prototype.hasOwnProperty.call(DOUBLE_QUOTED_ESCAPES, next)) {
      out += DOUBLE_QUOTED_ESCAPES[next]
      i += 1
      continue
    }
    // An unknown escape is a YAML error; taking the character itself keeps the round trip lossless
    // rather than silently dropping the backslash and the character after it.
    out += next
    i += 1
  }
  return out
}

/**
 * Strip one layer of matching quotes and undo the escaping that the quoting style actually implies.
 *
 * The two styles are **not** interchangeable, which the previous version did not model:
 *  - **double quotes** carry C-style escapes (see above);
 *  - **single quotes** carry exactly one escape, `''` → `'`; a backslash inside them is a literal.
 */
function unquote(value) {
  const quoted =
    value.length >= 2 &&
    ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))
  if (!quoted) return value
  const inner = value.slice(1, -1)
  if (value.startsWith('"')) return decodeDoubleQuoted(inner)
  return inner.replace(/''/g, "'")
}

/**
 * Read one preset directory's display name and description.
 *
 * @param {string} [directory] - the preset directory (defaults to the legacy `custom`).
 * @returns {{name: string, description: string}} both empty when the file is absent.
 */
export function readPresetMeta(directory = PRESET_DIR) {
  let text = ''
  try {
    text = readFileSync(presetMetaPath(directory), 'utf8')
  } catch {
    return { name: '', description: '' }
  }
  /** Read one scalar key. */
  const read = (key) => {
    const lines = text.split('\n')
    for (const line of lines) {
      if (!line.startsWith(key + ':')) continue
      return unquote(line.slice(key.length + 1).trim())
    }
    return ''
  }
  // `order` is the roster's own sort key (`dsh-agent-presets` orders by
  // `order ?? Infinity`, then id), which is what makes it the right home for
  // "which assistant comes first".
  const rawOrder = Number(read('order'))
  const order = read('order') !== '' && Number.isFinite(rawOrder) ? rawOrder : undefined
  return { name: read('name'), description: read('description'), ...order === undefined ? {} : { order } }
}

/** Quote and flatten a value into a single-line YAML scalar. */
function yamlScalar(value) {
  const flat = String(value).replace(/\r?\n/g, ' ').trim()
  // `JSON.stringify` 恰好就是 YAML 双引号标量需要的转义：除了 `"` 与 `\`，它还会把控制字符写成
  // `\u0007` 这类转义序列。此前手工只转前两个，于是名字里一个 BEL / VT / NUL 就会写出平台
  // js-yaml 解析不了的 preset.yml —— 这个模式在**所有选择器**里退化成裸目录 id，description 与
  // order 一起丢，而本插件自己的页面还显示着那个名字（外部评审实测）。
  // 对普通名字（含中文）输出与旧实现逐字节相同，所以这次替换不会动任何既有文件。
  return JSON.stringify(flat)
}

/**
 * Render the exact bytes `writePresetMeta` would write, without writing them.
 *
 * Why it is exported: the settings page writes the composition, the prompt **and** this file, and a review
 * found the third one landing separately — so a failure while writing `preset.yml` left a new prompt with
 * the old name on disk while the page reported "save failed" (issue #5). All three now go through one
 * staged atomic write; that requires the text, not a second writer.
 *
 * @param {string} name - display name (required).
 * @param {string|undefined} description - optional description.
 * @param {string} directory - the preset directory (for the existing `order`).
 * @param {{order?: number}} [options] - explicit roster position.
 * @returns {{ok: true, text: string, name: string} | {ok: false, code: string, error: string}} the bytes and the
 *   normalized name, or why there are none.
 */
export function presetMetaText(name, description, directory = PRESET_DIR, options = {}) {
  const cleanName = typeof name === 'string' ? name.replace(/\r?\n/g, ' ').trim() : ''
  if (cleanName === '') return { ok: false, code: 'nameRequired', error: '模式名称不能为空。' }
  const lines = ['name: ' + yamlScalar(cleanName)]
  if (typeof description === 'string' && description.trim() !== '') {
    lines.push('description: ' + yamlScalar(description))
  }
  const requested = options !== null && typeof options === 'object' ? options.order : undefined
  const order =
    typeof requested === 'number' && Number.isFinite(requested) ? Math.trunc(requested) : readPresetMeta(directory).order
  if (order !== undefined) lines.push('order: ' + String(order))
  return { ok: true, text: lines.join('\n') + '\n', name: cleanName }
}

/**
 * Write `preset.yml` with a display name and optional description.
 *
 * The name is required: an empty one would render the mode as its bare directory
 * id everywhere.
 *
 * @param {string} name - display name.
 * @param {string|undefined} description - optional description.
 * @param {string} [directory] - the preset directory (defaults to the legacy `custom`).
 * @param {{order?: number}} [options] - roster position. Omitted means "keep whatever is on
 *   disk": a rename must never reshuffle the pickers.
 * @returns {{ok: true, name: string} | {ok: false, error: string}} the outcome.
 */
export function writePresetMeta(name, description, directory = PRESET_DIR, options = {}) {
  const rendered = presetMetaText(name, description, directory, options)
  if (rendered.ok !== true) return rendered
  try {
    // **非原子写的最坏后果在这里**：preset.yml 写坏 = 这个模式从所有选择器里消失（见本文件头注释）。
    // 实测审阅指出这里原先用的是裸 writeFileSync，与设置页的纪律不一致；现在共用同一份实现。
    writeAtomic(presetMetaPath(directory), rendered.text)
  } catch (error) {
    // 带 `code`：这条是保存路径上最后一个没有 code 的用户可见失败，英文界面此前会在这里显示中文。
    // 它发生得尤其难受 —— 组成文件与提示词**已经写成功**了，只有 preset.yml 没写成，所以页面
    // 说"失败"而磁盘上是新提示词 + 旧名字（外部评审复现）。
    const detail = String((error && error.message) || error)
    return { ok: false, code: 'metaWriteFailed', params: { detail }, error: '写入 preset.yml 失败：' + detail }
  }
  return { ok: true, name: rendered.name }
}
