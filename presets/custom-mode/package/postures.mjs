/**
 * 新建助手时的姿态。
 *
 * 姿态不是第五套基础模式。每一条的 `base` 必须是官方四套（或它们的并集 `all`）之一，
 * 建完之后用户仍用设置页上的基础模式药丸改底子。`hands: 'shipped'` 表示开关一字不改，
 * 和今天「只填名字就新建」相同。`hands: 'allow'` 是一张允许名单：出厂是开、又不在名单里的行
 * 会被显式关掉。上游以后新增的行因此默认关闭，而不是悄悄出现在「聊天」里。
 *
 * 提示词草稿放在 `preset/postures/<id>.<zh|en>.md`，不写进代码，也从不复制进用户的预设目录。
 * 只有用户点了新建，才会把对应草稿写进那一个新助手的 `prompt.md`。`develop` 不读这些文件，
 * 它用的就是 `preset/prompt.md`，这样老的创建路径字节级不变。
 *
 * 能进这张表的姿态，提示词里要有一条可以指给人看的约定，开关要是这条约定的直接后果。
 * 占位句（「把提示词写在这里」）不够格：它会占一个助手，也会占模型的上下文。
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const DIR = join(dirname(fileURLToPath(import.meta.url)), 'preset', 'postures')

/** 所有姿态都留下的行：身份（否则提示词不生效）和会话内改提示词的工具。 */
export const POSTURE_ALWAYS = ['persona', 'custom-prompt-tool']

export const POSTURES = [
  { id: 'develop', base: 'standard', hands: 'shipped', allow: [] },
  {
    id: 'write',
    base: 'standard',
    hands: 'allow',
    allow: ['persona', 'agent-instructions', 'tool-fs', 'tool-fs-search', 'tool-todo', 'time-context', 'custom-prompt-tool'],
  },
  {
    id: 'chat',
    base: 'standard',
    hands: 'allow',
    allow: ['persona', 'tool-ask-user', 'time-context', 'custom-prompt-tool'],
  },
]

/** @param {string} id */
export function postureById(id) {
  return POSTURES.find((item) => item.id === id) ?? null
}

/** 列表接口交给页面的公开形状：没有允许名单，页面不必理解开关。 */
export function publicPostures() {
  return POSTURES.map((item) => ({ id: item.id, base: item.base }))
}

/**
 * @param {unknown} value - 请求里的 locale。只有明确的中文走中文草稿，其余走英文。
 * @returns {'zh' | 'en'}
 */
export function postureLocale(value) {
  return typeof value === 'string' && value.toLowerCase().startsWith('zh') ? 'zh' : 'en'
}

/**
 * 某个姿态的起步提示词。`develop` 返回 null，调用方继续用出厂 `prompt.md`。
 *
 * @param {string} id
 * @param {'zh' | 'en'} locale
 * @returns {string | null}
 */
export function posturePrompt(id, locale) {
  if (id === 'develop') return null
  const lang = locale === 'zh' ? 'zh' : 'en'
  try {
    return readFileSync(join(DIR, id + '.' + lang + '.md'), 'utf8')
  } catch {
    return readFileSync(join(DIR, id + '.' + (lang === 'zh' ? 'en' : 'zh') + '.md'), 'utf8')
  }
}
