/**
 * LittleWhale's dsh tools
 *
 * These run in the host process, which is the app's own Node child and shares its uid, so the
 * privileged channel is one loopback connection away: the app process serves it and holds the
 * binder to the root or shell process, which is something Node cannot do for itself
 *
 * Both the endpoint and the token arrive in environment variables the app sets when it spawns the
 * host, so the same tools on a machine that is not a phone report exactly that instead of failing
 * obscurely
 *
 * Every call that changes a screen names the screen it means. Which screen the app's preview shows
 * is the user's to change at any moment, so a tool that named nothing could act on the one the user
 * had just switched to - the coordinates a caller holds are only meaningful against the screen it
 * measured them on
 */

import { connect } from 'node:net'

import { createHash, randomUUID } from 'node:crypto'
import { createWriteStream } from 'node:fs'
import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import { Readable, Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { join, dirname } from 'node:path'

import { defineTool } from '@deepseek-ai/dsh-tools'

/** Set by the app when it starts the host; both absent everywhere else */
const ENDPOINT_VARIABLE = 'LW_CHANNEL_ENDPOINT'
const TOKEN_VARIABLE = 'LW_CHANNEL_TOKEN'

/** How long a swipe takes when the caller does not say, which is a deliberate drag */
const DEFAULT_SWIPE_MS = 300

/** 按名字点一个从像素里读出来的框时, 落点从框中间这一块里随机取, 见 scatterInside */
const SCATTER_INSET = 0.2

/**
 * What the three names for a hold mean, in milliseconds
 *
 * Android's own long press threshold is 500ms (`ViewConfiguration.getLongPressTimeout`), and that
 * is the whole of what the platform reads into a hold: everything at or past it is a long press and
 * everything past that is only "kept down longer", which matters to the few apps that count - a
 * voice message being recorded, a drag that starts by holding. So the names sit just above that
 * line rather than in the seconds: `short` is the shortest hold that is reliably a long press
 * (600ms, which leaves the main thread's own detector its margin), and the other two are the steps
 * above it. A hold nobody named - "8s", "250ms" - is written out instead, which is also how a
 * deliberate long one is asked for
 */
const HOLD_NAMES = { short: 600, medium: 1500, long: 3000 }

/** No hold lasts longer than this: the device's gesture queue is blocked for the whole of one */
const MAX_HOLD_MS = 10000

/** How many controls one lw_ui lists before it says the rest are off screen */
const MAX_UI_NODES = 200

/** How many queued sentences lw_voice prints: the tail is what a person is asking about */
const MAX_VOICE_LINES = 20

export const name = 'littlewhale-channel'

/** The tool registry has to exist before anything can be registered on it */
export const inject = ['tools', 'agents', 'sessions']

export function apply(ctx) {
  for (const tool of TOOLS) ctx.tools.register(tool)

  // Answer every approval request with a grant, so nothing on the screen waits for a person
  //
  // An answerer is not registered on a service: it is a listener on the `approval/request`
  // waterfall, and returning an outcome answers for every agent while `next()` would delegate.
  // `prepend` is what makes it decisive - this plugin is mounted from the last patch layer, so
  // without it the web GUI's own approval panel would claim a request first whenever a browser
  // has the GUI open, and the user would be asked to confirm something they cannot see
  //
  // This is a deliberate trade, recorded in AGENTS.md: a screen being driven at a distance has
  // nobody watching it, so a question is not a safety net, it is a stall. The approval log still
  // records that a decision was made and what it was
  ctx.on('approval/request', (_request, _next) => Promise.resolve('allowed-once'), { prepend: true })

  // 语音链投进来的话: 一直看着应用写的那份队列, 有新句子就送进会话 (批次 2 的投递)
  //
  // `sessionController` 走 ctx.get 而不是 inject: 它是 web 那一套里的服务, 而这个插件在别的
  // profile 里也装 (那些 profile 没有它)。inject 少了会让整个插件的 53 个工具跟着挂, 而这里
  // 真正想表达的只是"这个能力在这套 profile 里可能没有"
  //
  // 这一行是**故意的**: 队列在哪个文件、这条链起没起来, 是排查"说了话没进会话"的第一个问题,
  // 而它只靠日志才回答得了 (2026-10-05 就是在这里瞎猜了一轮)
  console.log(`littlewhale-channel: voice inbox is ${voiceInboxPath() ?? 'unavailable (no DSH_HOME)'}`)
  try {
    startVoiceInbox(ctx, (line) => voiceDeliver(ctx, line))
  } catch (error) {
    warn(ctx, `the voice inbox was not started: ${error?.message ?? error}`)
  }

  // 一轮说完就把回答念出来 (批次 2.3): 挂在 `session/event` 的 `turn/end` 上, 只有正常结束的那一轮
  // 才念 —— 理由写在 startReadAloud 上面
  try {
    startReadAloud(ctx)
  } catch (error) {
    warn(ctx, `reading replies aloud was not started: ${error?.message ?? error}`)
  }

  // 语音输入是 dsh 里可选的一套 (voice-input bundle 带的那个客户端录音按钮): 服务在才注册
  // 本机的转写 provider, 不在就什么都不做 —— 一个可选能力不该让 lw_* 那堆工具跟着挂
  try {
    if (typeof ctx.inject === 'function') {
      ctx.inject(['speechToText'], (speechCtx) => {
        registerVoiceInput(speechCtx)
      })
    }
  } catch (error) {
    warn(ctx, `the on-device speech provider was not registered: ${error.message}`)
  }
}

/**
 * 报一句警告
 *
 * **两处都写**: 控制台那一行是保证看得见的 (主机日志里带 `DshHost`), 而 cordis 的 logger 是给
 * profile 自己那一套用的。2026-10-05 踩的就是只写 logger: 投递链没起来, 而 `ctx.logger?.warn?.()`
 * 把它吞得干干净净, 日志里一个字都没有, 只能在文件系统上一点点反推
 */
function warn(ctx, message) {
  console.warn(`littlewhale-channel: ${message}`)
  if (typeof ctx.logger?.warn === 'function') ctx.logger.warn(message)
}

/** The display a call is about, which is the id `lw_screen` reports and nothing else */
const DISPLAY_ID = {
  type: 'integer',
  required: true,
  description:
    'The displayId of the screen to act on, exactly as lw_screen reports it. Display 0 is the '
    + "phone's own screen, the glass the person holding it is looking at; every other id is a "
    + 'virtual screen this host made. Prefer a virtual screen whenever the user has not named one: '
    + 'display 0 is the phone in somebody\'s hand, so anything aimed at it takes the phone away '
    + 'from them, and a call is refused outright while their finger is on the glass while a drag '
    + 'on it is cut short. Make one with lw_screen_create instead of reaching for the id that is '
    + 'already there. When a call comes back saying the screen is gone, is paused, or is in the '
    + 'user\'s hands, read the reason it gives: the user did it from the phone, or an agent closed '
    + 'it with lw_screen_release. Either way stop, say which screen it is, and ask the user what '
    + 'they want - do not retry the id, and do not move on to another screen on your own.',
}

/**
 * 屏参数的另一版: 可以省略, 省了就是"每一块屏"
 *
 * 与 [DISPLAY_ID] 的区别是刻意的: 那一版把 displayId 定成必填, 因为**动手**必须点名是哪一块屏
 * (选中的屏是用户随时能改的); 而事件订阅只是**看**, 省略就等于全看, 那才是它的默认用法
 *
 * **声明必须在 `TOOLS` 数组之前**: `const` 不提升, 放到下面就是模块求值期抛错, 而那张表是模块级的
 * —— 整包 UNSUPPORTED_SCHEMA, 会话里一个 `lw_*` 工具都不剩 (自检会报 "Cannot access ... before
 * initialization")
 */
const WATCHED_DISPLAY = {
  type: 'integer',
  description: "Only this screen's changes; leave it out to watch every screen, which is the usual "
    + 'thing here because watching is reading rather than acting',
}

/**
 * What one acting call is for, said by the caller in its own words
 *
 * Every call that changes something on a screen carries one, the way an agent framework asks for a
 * line of explanation beside a command. It is required rather than optional on purpose: saying what
 * a step is for before taking it is the difference between a transcript a person can follow and a
 * list of coordinates, and there is no human in the approval path to ask instead
 */
const NOTE = {
  type: 'string',
  required: true,
  description: 'One short sentence, in your own words, saying what this step is doing and why',
}

/**
 * 把视频模式要的那块屏与相机起来, 回一句人话
 *
 * 这是**切换模式的一部分**, 不是新能力: 用的还是 `create` / `screen` / `launch` 那三个桥调用,
 * 只是把"认屏 → 建屏 → 开相机"三步并成一次 —— 主人说一句"看看这是什么", 少等两轮往返
 *
 * 屏按固定形状建 (720x1280 / dpi 320): 相机是竖屏应用, 建一块竖屏的屏它才会铺满, 而**建屏时就把
 * 形状定对**是这里唯一要紧的事 (应用不因为屏换了形状就重排)。已经有一块叫 `video` 的屏就沿用
 */
const VIDEO_SCREEN = { name: 'video', width: 720, height: 1280, dpi: 320 }

/** 这台设备上相机可能叫什么: 先按包名试, 都不行就把判断交回给模型 */
const CAMERA_PACKAGES = ['com.android.camera', 'com.vivo.camera', 'com.android.camera2']

async function bringUpCamera() {
  const lines = []
  try {
    const screens = await call('screen')
    const all = Array.isArray(screens?.screens) ? screens.screens : []
    const mine = all.find((screen) => screen?.name === VIDEO_SCREEN.name)
    let displayId = mine?.displayId
    if (displayId === undefined) {
      const created = await call('create', VIDEO_SCREEN)
      displayId = created?.displayId
      lines.push(created?.created
        ? `virtual screen ready on displayId ${displayId} (${VIDEO_SCREEN.width}x${VIDEO_SCREEN.height})`
        : `the virtual screen did not come up: ${created?.detail ?? JSON.stringify(created)}`)
    } else {
      lines.push(`reusing the ${VIDEO_SCREEN.name} screen on displayId ${displayId}`)
    }
    if (displayId === undefined) return lines.join('\n')
    for (const camera of CAMERA_PACKAGES) {
      const launched = await call('launch', { displayId, package: camera })
      if (launched?.launched !== false) {
        lines.push(`camera ${camera} is in front on that screen`)
        return lines.join('\n')
      }
      lines.push(`${camera}: ${launched?.detail ?? 'not started'}`)
    }
    lines.push('no camera package started; find the one this device ships and use lw_launch')
  } catch (error) {
    lines.push(`bringing the camera up failed: ${error?.message ?? error}`)
  }
  return lines.join('\n')
}

const TOOLS = [
  defineTool({
    name: 'lw_probe',
    description:
      'Probe the LittleWhale privileged channel on the Android device this host runs on. '
      + 'Reports the uid the privileged helper process runs as (0 means root), its pid, and the '
      + 'touchscreen ranges that getevent -p reports for it. Use this to check that the channel '
      + 'is up before anything relies on controlling the screen.',
    parameters: {},
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute() {
      return formatProbe(await call('probe'))
    },
  }),
  defineTool({
    name: 'lw_mode',
    description:
      'Switch which mode this phone assistant is in. "phone" is the usual one: operate the phone '
      + 'through the lw_* tools. "video" points the camera at what is in front of the user and '
      + 'answers what it is, in one to three sentences. A switch replaces the assistant\'s prompt '
      + 'text, so it takes effect on the NEXT model step rather than this one: call it, say the mode '
      + 'changed, and stop there. Call it with "video" when the user asks to look at something, and '
      + 'with "phone" when the video work is over (they said to quit video mode, close the camera or '
      + 'stop looking). mode "status" reports which one is active right now.',
    parameters: {
      mode: {
        type: 'string',
        required: true,
        description: 'phone, video, or status',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const answer = await call('mode', { mode: args.mode })
      // 切到视频模式顺带把屏与相机起来: 这一步本来要模型自己走三次 (认屏 / 建屏 / 开相机),
      // 放到这里就成了一次调用 —— 主人说"看看这是什么"之后不用等三轮
      const brought = answer.switched === true && answer.mode === 'video'
        ? await bringUpCamera()
        : null
      if (answer.switched === false) return answer.detail
      if (answer.modes) return `modes: ${answer.modes}\nactive: ${answer.active}`
      if (answer.switched === true) {
        return `mode -> ${answer.mode} (${answer.name}); ${answer.detail}`
          + (brought ? `\n${brought}` : '')
      }
      return `mode: ${answer.mode} (${answer.name})\n`
        + `prompt file: ${answer.promptWritten ? 'written' : 'missing'} (${answer.promptFile})`
    },
  }),

  defineTool({
    name: 'lw_screen',
    description:
      'List the screens this device can be asked to act on: the phone\'s own screen, and every '
      + 'virtual screen the host is running. A virtual screen is a separate Android display with '
      + 'its own size, its own apps and its own picture, and it keeps running whether or not the '
      + 'phone is showing it. Call this before any other lw_ tool that acts on a screen: the '
      + 'displayId it reports is what those calls name, and the size it reports is the coordinate '
      + 'space their points are in. Display 0 is the phone\'s own screen, the one a person is '
      + 'holding - it is always there, nothing can be created or released there, and whether it can '
      + 'be driven right now is in the same answer, because a real finger on the glass stops '
      + 'everything aimed at it. Unless the user asked for that screen in so many words, work on a '
      + 'virtual one instead: it is theirs to keep using, and only a screen of ours can be given '
      + 'the shape an app wants (lw_screen_create, then lw_screen_resize or lw_screen_rotate). Two '
      + 'things here are not yours to undo: a screen you were working '
      + 'on may be missing, and a screen may say its control is paused. A paused screen says so in '
      + 'this list; a missing one does not, but a call naming its id answers with the reason - the '
      + 'user closed it from the phone, or an agent closed it with lw_screen_release, which may '
      + 'have been another session, since screens are shared. In every one of those cases stop, say '
      + 'which screen it is, and ask the user what they want instead of retrying or carrying on '
      + 'with a different screen.',
    parameters: {},
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute() {
      return formatScreens(await call('screen'))
    },
  }),

  defineTool({
    name: 'lw_screen_create',
    description:
      'Create a virtual screen on the Android device and answer with its displayId. This is the '
      + 'usual way to work: a screen of its own leaves the phone in the user\'s hands, where '
      + 'acting on display 0 would take it away from them. The screen '
      + 'starts empty: nothing is drawn on it until an app is launched onto it with lw_launch, and '
      + 'the apps you launch stay on the display you launched them on. Give it a name to tell your '
      + 'screens apart - a name that is taken gets a number appended. Width, height and dpi default '
      + "to the device's own screen, so leave them out to get a screen the size of the phone, and "
      + 'give them when you already know the shape the app wants - a game or any other '
      + 'landscape-only app wants a width larger than its height, and an app that declares an '
      + 'orientation of its own is left in a band in the middle of a screen shaped the other way '
      + 'rather than laid out to fill it. It is '
      + 'not final either way, since lw_screen_resize and lw_screen_rotate change a screen that '
      + "already exists with its apps still running. The phone's own screen is not created here: "
      + 'it is displayId 0 and it is always there.',
    parameters: {
      name: {
        type: 'string',
        description: 'What to call this screen, for example the app you mean to put on it',
      },
      width: {
        type: 'integer',
        description: 'Width in pixels; the device\'s own screen when omitted. Larger than height'
          + ' for a landscape app, smaller for a portrait one',
      },
      height: {
        type: 'integer',
        description: 'Height in pixels; the device\'s own screen when omitted',
      },
      dpi: {
        type: 'integer',
        description: 'Pixel density; the device\'s own density when omitted',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatCreated(await call('create', drop(args)))
    },
  }),

  defineTool({
    name: 'lw_screen_resize',
    description:
      'Give an existing virtual screen another size, with whatever is running on it left running. '
      + 'A display\'s size is what an app lays itself out for, so this is how a screen made '
      + 'portrait becomes the landscape one a game wants: the picture cannot be rotated into it. '
      + 'Name the width, the height, the dpi, or as many of those as you mean to change - every '
      + 'one you leave out keeps the value the screen has. The apps on the screen are not '
      + 'restarted: the new size reaches them as a configuration change, so one that follows its '
      + 'screen lays itself out again and fills it, while one that declares an orientation of its '
      + 'own - most system apps are portrait-only - keeps that layout and sits in a band in the '
      + 'middle of the screen instead. Take a fresh picture before '
      + 'reading coordinates off this screen, since every one of them has moved. The phone\'s own '
      + 'screen (displayId 0) has no size of ours to change and is refused.',
    parameters: {
      displayId: DISPLAY_ID,
      width: {
        type: 'integer',
        description: 'The screen\'s new width in pixels; it keeps its own when omitted',
      },
      height: {
        type: 'integer',
        description: 'The screen\'s new height in pixels; it keeps its own when omitted',
      },
      dpi: {
        type: 'integer',
        description: 'The screen\'s new pixel density; it keeps its own when omitted',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const width = Number(args?.width ?? 0)
      const height = Number(args?.height ?? 0)
      const dpi = Number(args?.dpi ?? 0)
      if (!(width > 0 || height > 0 || dpi > 0)) {
        throw new Error('lw_screen_resize needs at least one of width, height or dpi to change')
      }
      return formatResized(await call('resize', drop(args)))
    },
  }),

  defineTool({
    name: 'lw_screen_rotate',
    description:
      'Turn a virtual screen a quarter turn: exactly lw_screen_resize with the width and the '
      + 'height traded for each other. A screen has a shape rather than an orientation of its '
      + 'own, so the two directions are the same turn and there is no clockwise to name. Reach for '
      + 'this when an app turns out to want the other shape - and take a fresh picture afterwards, '
      + 'because what is on the screen is laid out again at the new size rather than rotated, so '
      + 'all of its coordinates move.',
    parameters: {
      displayId: DISPLAY_ID,
      dpi: {
        type: 'integer',
        description: 'The screen\'s new pixel density; it keeps its own when omitted',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatResized(await call('resize', { ...drop(args), swap: true }))
    },
  }),

  defineTool({
    name: 'lw_screen_release',
    description:
      'Give one virtual screen back, which takes everything running on it down with it. '
      + 'Irreversible: any app you launched onto that display has to be launched again on a new '
      + 'one. Name the displayId to release - there is no default, because releasing the wrong '
      + 'screen destroys work nobody asked to lose, and displayId 0 is the phone\'s own screen, '
      + 'which cannot be released at all. Screens are shared with every other session on '
      + 'this host, so a screen you did not create for this task is not yours to close: ask the '
      + 'user first. Whoever calls this is recorded as the closer, so a later call on that id is '
      + 'told an agent closed it rather than the user.',
    parameters: { displayId: DISPLAY_ID, note: NOTE },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatReleased(await call('release', drop(args, 'note')), args?.note)
    },
  }),

  defineTool({
    name: 'lw_ui',
    description:
      'Read what one screen says: every control on it that has text, a description or an '
      + 'action, each with the rectangle it occupies in the screen\'s own pixels. Works on a '
      + 'virtual screen and on the phone\'s own screen (displayId 0) alike, including what the '
      + 'person holding the phone is looking at. Use this instead '
      + 'of lw_screenshot whenever it answers: the text is exact and the coordinates are exact, '
      + 'while coordinates measured on a picture have been scaled and rounded on the way. Press '
      + 'what it lists with lw_tap(text="..."), which needs no coordinate at all. An app that '
      + 'draws its own interface - a game, Flutter, a canvas or a WebGL view - has no such tree, '
      + 'and this answers that it found nothing: fall back to lw_ocr there, which reads the text '
      + 'off the pixels.',
    parameters: { displayId: DISPLAY_ID },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatUi(await call('ui', drop(args)))
    },
  }),

  defineTool({
    name: 'lw_ocr',
    description:
      'Read the text on a screen from its pixels. This is the fallback for a screen whose controls '
      + 'cannot be named: an app that draws its own interface - a game, a Unity or Cocos canvas, a '
      + 'WebGL view - has no control tree, but its text is still on the screen. Every line comes '
      + 'back with the rectangle it occupies in the screen\'s own pixels, so lw_tap(x=..., y=...) '
      + 'can press it; the centre is given too. It reads Chinese and English, runs on the device '
      + 'with no network and no cloud, and unlike lw_screenshot it does **not** scale the picture '
      + 'down, so small text stays readable. Prefer lw_ui whenever that answers: it is exact and '
      + 'free, while this is a reading of a picture, and it can mistake an icon for a character '
      + '(those come back with a low score).',
    parameters: { displayId: DISPLAY_ID, note: NOTE },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return said(formatOcr(await call('ocr', drop(args, 'note'))), args?.note)
    },
  }),

  defineTool({
    name: 'lw_tap',
    description:
      'Press something on a screen, either by name or at a point - on a virtual screen, or on the '
      + "phone's own screen with displayId 0. By name is the reliable "
      + 'way: pass text exactly as lw_ui reported it and the device finds that control and presses '
      + 'it, reporting what it pressed. If the name reaches more than one control nothing is '
      + 'pressed and the candidates come back, so the next attempt can be more specific. A point '
      + 'is for when there is no name to use - a canvas, or a place you measured on a screenshot - '
      + 'and it is in the screen\'s own pixels, with 0,0 at its top left. Either way the call '
      + 'returns once the device has delivered the press, so a screenshot or an lw_ui taken after '
      + 'it shows the result. Pass hold to make it a long press, which is how a context menu, a '
      + 'selection or a drag handle is reached; by name that uses the control\'s own long click '
      + 'action, and a control without one is pressed with a held finger instead. On the phone\'s '
      + 'own screen the user has the last word: if their '
      + 'finger is on the glass the press is not delivered and the answer says so - stop, say what '
      + 'you were doing, and ask them, because retrying it will be refused too. A press that is '
      + 'held can also be taken away in the middle: the answer then says how long it lasted, and '
      + 'what followed is not the result of a press that finished.',
    parameters: {
      displayId: DISPLAY_ID,
      text: {
        type: 'string',
        description: 'Name of the control to press: its text if it has one, otherwise the desc= '
          + 'value lw_ui reported for it',
      },
      x: { type: 'number', description: 'Horizontal position of the point, instead of a text' },
      y: { type: 'number', description: 'Vertical position of the point, instead of a text' },
      source: {
        type: 'string',
        enum: ['auto', 'a11y', 'ocr'],
        description: 'Where to look the name up. auto (the default) asks the control tree first '
          + 'and, only if that tree has no such name, reads the screen\'s pixels with lw_ocr - '
          + 'which is what a self-drawn screen needs. a11y is the tree alone, ocr is the pixels '
          + 'alone. An ambiguous name is never followed by a guess, whichever way it was found',
      },
      hold: holdParameter(
        'A hold past a long press is a press kept down, which is what a voice message being '
        + 'recorded or a drag that has to be started by holding expects.',
      ),
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const named = args?.text !== undefined
      const placed = args?.x !== undefined && args?.y !== undefined
      if (named && placed) {
        throw new Error('lw_tap takes either a text or a point, not both')
      }
      if (!named && !placed) {
        throw new Error('lw_tap needs either a text to press by name or both x and y')
      }
      const held = holdMs(args?.hold)
      if (named) {
        const source = args.source ?? 'auto'
        const asked = { displayId: args.displayId, text: args.text, holdMs: held }
        if (source === 'ocr') {
          return said(await tapByReading(args.displayId, args.text, held), args.note)
        }
        const tree = await call('tapText', asked)
        // ambiguous 也直接交回去: 树里说有两个同名控件的时候, 读像素只会是第三个猜测
        // 这里看的是 clicked 而不是 outcome: 无障碍动作被拒之后桥会用一根按住的手指兜底,
        // 那一下 outcome 仍是 unclicked 而 clicked 已经是真, 再退到读屏就会按第二次
        if (source === 'a11y' || tree.clicked === true || tree.outcome === 'ambiguous') {
          return formatNamedTap(tree, args.note)
        }
        const reading = await tapByReading(args.displayId, args.text, held)
        return said(`${namedTapReport(tree)}\n${reading}`, args.note)
      }
      return formatGesture('tapped', await call('tap', {
        displayId: args.displayId,
        x: args.x,
        y: args.y,
        holdMs: held,
      }), args.note)
    },
  }),

  defineTool({
    name: 'lw_key',
    description:
      'Press a key on a screen: BACK, HOME, APP_SWITCH, ENTER, DEL, the volume keys, the arrows, and '
      + 'anything else Android has a keycode for. This is how a button the platform itself handles '
      + 'is pressed - BACK, HOME and the volume keys are on no screen\'s tree, so lw_ui and lw_tap '
      + 'cannot reach them - and it is the only way: the input command is refused for this app\'s '
      + 'uid, exactly like am, so a shell cannot do it either. Name the key the way Android names '
      + 'it, with or without the KEYCODE_ prefix (BACK, keycode_home, APP_SWITCH, DPAD_DOWN, '
      + 'MOVE_END), or pass its number as code. A name Android does not have, or a number it does '
      + 'not define, is refused and the answer says what to use instead - so do not guess a number, '
      + 'the wrong one is a different key rather than an error. Pass hold to keep the key down: a '
      + 'long BACK force-stops the app in front, a long HOME calls the assistant, a long press on a '
      + 'power key opens its menu. Works on a virtual screen and on the '
      + 'phone\'s own screen (displayId 0), where the user\'s own finger stops it like every other '
      + 'acting call. HOME and the power and sleep keys are refused on a virtual screen: a screen '
      + 'of this host has no launcher, so they belong on displayId 0.',
    parameters: {
      displayId: DISPLAY_ID,
      key: {
        type: 'string',
        description: 'The Android name of the key, for example BACK, HOME, APP_SWITCH, ENTER, DPAD_DOWN',
      },
      code: {
        type: 'integer',
        description: 'The keycode number instead of a name, for example 4 for BACK - exactly one of key and code',
      },
      hold: holdParameter(
        'Holding a key down does not repeat it - send the key again for that - so this is for what '
        + 'the hold itself means to the platform.',
      ),
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      if (args?.key !== undefined && args?.code !== undefined) {
        throw new Error('lw_key takes either a key name or a code, not both')
      }
      if (args?.key === undefined && args?.code === undefined) {
        throw new Error('lw_key needs the key to press, by name (key) or by number (code)')
      }
      const held = holdMs(args?.hold)
      return formatPressed(await call('key', {
        displayId: args.displayId,
        key: args.key,
        code: args.code,
        holdMs: held,
      }), args.note)
    },
  }),

  defineTool({
    name: 'lw_swipe',
    description:
      'Drag a finger across a screen, as one continuous gesture: down at the first point, '
      + 'moved across, up at the second - on a virtual screen, or on the phone\'s own screen with '
      + 'displayId 0. Use it to scroll, to dismiss, or to drag something. The '
      + 'points are in the screen\'s own pixels and the duration decides the speed, which is what '
      + 'tells a scroll apart from a fling - a short duration over a long distance flings. A drag '
      + 'on the phone\'s own screen is the one gesture that can be taken away half way through: if '
      + 'the user puts a finger on the glass the finger is lifted where it had reached and the '
      + 'answer says how far it got, so do not read the next screenshot as the result of a gesture '
      + 'that finished - stop and ask the user.',
    parameters: {
      displayId: DISPLAY_ID,
      fromX: { type: 'number', required: true, description: 'Horizontal position the finger starts at' },
      fromY: { type: 'number', required: true, description: 'Vertical position the finger starts at' },
      toX: { type: 'number', required: true, description: 'Horizontal position the finger ends at' },
      toY: { type: 'number', required: true, description: 'Vertical position the finger ends at' },
      durationMs: {
        type: 'integer',
        description: `How long the drag takes in milliseconds, ${DEFAULT_SWIPE_MS} by default`,
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatGesture('swiped', await call('swipe', drop(args, 'note')), args?.note)
    },
  }),

  defineTool({
    name: 'lw_type',
    description:
      'Type text into a screen: search, fill a form, write a message. The device finds the field '
      + 'itself - the one with focus, or the only editable thing on the screen - and writes the '
      + 'text into it, so any character works, Chinese included. By default it adds at the cursor; '
      + 'pass replace to overwrite what the field already says instead. If the screen reports no '
      + 'field at all (a game, a canvas), the text goes in as key presses, which only produces what '
      + 'a keyboard has - letters, digits, punctuation - and lands wherever focus is. The answer '
      + 'says which of the two happened and what the field reads afterwards, so read it rather than '
      + 'assuming. Give x and y to put the text into the field **at that point** - the tool presses '
      + 'it first, which is what decides where the text lands on a screen with more than one field. '
      + 'Typing does not submit anything: press the app\'s own button, or ENTER with '
      + 'lw_key, if it needs that. On the phone\'s own screen it is refused while the user\'s finger '
      + 'is on it, like every other acting call.',
    parameters: {
      displayId: DISPLAY_ID,
      text: {
        type: 'string',
        required: true,
        description: 'The text to type, exactly as it should appear in the field',
      },
      replace: {
        type: 'boolean',
        description: 'Overwrite what the field already says, instead of adding to it at the cursor',
      },
      x: { type: 'integer', description: 'Press this point first, then type into the field there' },
      y: { type: 'integer', description: 'Press this point first, then type into the field there' },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const text = typeof args?.text === 'string' ? args.text : ''
      if (text === '') throw new Error('lw_type needs the text to type')
      return formatTyped(await call('type', drop(args, 'note')), args.note)
    },
  }),

  defineTool({
    name: 'lw_apps',
    description:
      'List the apps this device can start: the name a person sees, and the package to start it '
      + 'with. Pass query to filter by either one - that is how you find a single app rather than '
      + 'reading the whole list - and pass user to ask about a user other than the person holding '
      + 'the phone. A cloned app (双开, a parallel space) is the same package in another user rather '
      + 'than a second package, so it appears in that user\'s list. This is the listing a shell '
      + 'cannot give you: pm refuses an app uid on this device, and with an explicit --user it '
      + 'answers with LittleWhale alone, so call this instead of running pm or cmd package.',
    parameters: {
      user: {
        type: 'integer',
        description: 'The Android user to list for; the person holding the phone (0) when left out.'
          + ' A cloned app lives in another user, and lw_probe lists the users this device has',
      },
      query: {
        type: 'string',
        description: 'Only the apps whose name or package contains this, for example 设置 or bili',
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatApps(await call('apps', drop(args, 'note')))
    },
  }),

  defineTool({
    name: 'lw_launch',
    description:
      'Start an app on a screen and wait until it is up: on a virtual screen, or on the phone\'s '
      + 'own screen with displayId 0, which is what puts it in front of the person holding the '
      + 'phone. Name the app by package, or by the name a person uses for it ("设置") when that '
      + 'name reaches exactly one app - its '
      + 'launcher activity is what starts, and the answer says which one that turned out to be - '
      + 'or name an exact component like com.android.settings/.Settings. An app that is cloned - '
      + '双开, a parallel space - is the same package in another Android user rather than a second '
      + 'package, so it is started by naming that user, and lw_probe lists the ones this device '
      + 'has. Call lw_apps when you do not know the package. The app stays on that '
      + 'display until the screen is released, and the phone does not have to be showing it. For a '
      + 'game or another app that only knows one orientation, give the screen that shape first - '
      + 'lw_screen_create takes a width and a height - because an app that declares an orientation '
      + 'of its own keeps it and sits in a band in the middle of a screen shaped the other way. Use '
      + 'this rather than a shell: the am command refuses an app uid on this device, so a launch '
      + 'attempted from a shell fails. Call lw_ui or lw_screenshot after it to see the result.',
    parameters: {
      displayId: DISPLAY_ID,
      package: {
        type: 'string',
        description: 'Package to start, for example com.android.settings, or the name a person'
          + ' uses for the app when it reaches exactly one - lw_apps lists both',
      },
      component: {
        type: 'string',
        description: 'Exact component to start instead of a package, for example com.android.settings/.Settings',
      },
      user: {
        type: 'integer',
        description: 'The Android user to start it for; the person holding the phone (0) when left'
          + ' out. A cloned app - 双开, a parallel space - is the same package in another user,'
          + ' with its own data, and lw_probe lists the users this device has',
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const packageName = (args?.package ?? '').trim()
      const component = (args?.component ?? '').trim()
      if ((packageName === '') === (component === '')) {
        throw new Error('lw_launch takes either a package or a component, and exactly one of them')
      }
      return formatLaunched(await call('launch', drop(args, 'note')), args?.note)
    },
  }),

  defineTool({
    name: 'lw_screenshot',
    description:
      'Take a picture of one screen and leave it as a PNG file in the workspace: a virtual screen, '
      + "or the phone's own screen with displayId 0, which is exactly what a person would be "
      + 'looking at. Works '
      + 'whether or not the phone is showing that screen, so it is how you see what is on a screen '
      + 'you are driving: read the file back with the read_image tool to look at it. Prefer lw_ui '
      + 'when it answers - it gives text and exact coordinates, and this gives neither - and use '
      + 'this for what has no readable tree: games, Flutter, canvases, or to see the picture as a '
      + 'person would. **The picture can come back much smaller than the screen**: the phone caps a '
      + 'screenshot at a pixel budget and then shrinks it further to fit a byte budget, so a '
      + '1080x2400 screen can arrive as 536x1192 - or smaller when there is a lot on it - and small '
      + 'text is then unreadable. **To read a screen, use the two reading tools rather than a '
      + 'picture of it**: lw_ui reads the accessibility tree, which gives every control\'s exact '
      + 'text and position, and lw_ocr runs the on-device OCR over the screen\'s own pixels without '
      + 'scaling them - so both are exact where a screenshot of the same screen is a small copy. '
      + 'Take this when you want to see the screen as a person would, or when neither of those two '
      + 'answers. When a scaled picture is what you have, the answer says what to multiply '
      + 'coordinates measured on it by to get coordinates for lw_tap and lw_swipe. The file is '
      + 'overwritten by the next capture of the same screen, unless you ask for more than one at a'
      + ' time.',
    parameters: {
      displayId: DISPLAY_ID,
      // 只要屏幕的一块: 文字太小看不清时, 把那一块按它自己的像素交给模型, 比整屏缩一遍有用
      x: { type: 'integer', description: 'Left edge of the part to capture, for a partial picture' },
      y: { type: 'integer', description: 'Top edge of the part to capture, for a partial picture' },
      width: { type: 'integer', description: 'Width of the part to capture, with x, y and height' },
      height: { type: 'integer', description: 'Height of the part to capture, with x, y and width' },
      count: {
        type: 'integer',
        description: 'How many pictures in a row, 1 to 12, for something that is moving. Each one'
          + ' is a separate file; leave it out for a single picture. Twelve pictures are twelve'
          + ' images in the conversation, so ask for the grid too unless you need to read each one',
      },
      intervalMs: {
        type: 'integer',
        description: 'How far apart those pictures are, in milliseconds, 50 to 5000 (120 by'
          + ' default). It is wall-clock time from one capture to the next, and one capture takes'
          + ' a couple of hundred milliseconds here, so anything faster is impossible: the answer'
          + ' says when each picture was actually taken rather than what was asked for',
      },
      sheet: {
        type: 'boolean',
        description: 'Also lay the pictures out as ONE picture - a grid in the order they were'
          + ' taken. Reading one grid costs a twelfth of reading twelve pictures, so this is how to'
          + ' see where the screen went between them. It is not for reading small text or for'
          + ' measuring positions: every cell is a small copy, and the separate files are still'
          + ' there when a cell is not enough',
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatScreenshot(await call('screenshot', drop(args)))
    },
  }),

  // ---- 1.0.2 加的那一批: 通知与震动、剪贴板、传输、设备与系统信息、输入增强 ----
  //
  // 它们的共同点是不需要特权: 应用那一侧用 Context 自己做, 走桥只是为了把结果按同一套协议送回来。
  // 所以每个工具就是一行 `simpleTool` —— 参数表照实写, 说明里说清"什么情况下它做不到"

  simpleTool(
    'lw_notify',
    'Post a notification on the phone, optionally vibrating or coming up as a banner. Use it to tell'
    + ' the person holding the device something they should see outside this app - a job finished, a'
    + ' decision is waiting. The same notification id is reused, so a second call replaces the first'
    + ' rather than stacking; tapping it brings DSH-LW to the front. Needs the notification'
    + ' permission, and says so if it is missing. **banner is a permission of its own**: since'
    + ' Android 14 a full-screen intent has to be granted by hand, so the answer says whether the'
    + ' banner will actually appear rather than assuming it. The channel is read back too - it is'
    + ' created at high importance, but only the user can change that afterwards, and a channel the'
    + ' user has turned down does not banner.',
    'notify',
    {
      title: { type: 'string', required: true, description: 'The notification title, one short line' },
      text: { type: 'string', required: true, description: 'The notification body' },
      vibrateMs: {
        type: 'integer',
        description: 'Vibrate for this many milliseconds as well (up to 3000). Needs the vibration'
          + ' permission, which the device grants on its own at install',
      },
      banner: {
        type: 'boolean',
        description: 'Also give it a full-screen intent, so it comes up over whatever is on the'
          + ' screen (and over the lock screen). For something worth interrupting a person for;'
          + ' whether it is allowed is a separate switch the user owns',
      },
    },
  ),

  simpleTool(
    'lw_notifications',
    'Read the notification shade, and dismiss what is in it. This is how the agent finds out what'
    + ' the phone has been telling its owner: a message that arrived, a download that finished, a'
    + ' two-factor code. op=list gives one line per notification, newest first, each starting with'
    + ' the key that op=cancel takes - it names the app, the importance, whether it can be cleared'
    + ' at all, and the title and text. cancelling takes one key, or a package to clear everything'
    + ' that app posted. **Needs 通知使用权**, which no app can request at runtime: the answer says'
    + ' so, and the switch for it is in DSH-LW\'s own Settings -> 通知. Ongoing notifications (a'
    + ' music player, a call) are left alone and reported as such rather than as a failure.',
    'notifications',
    {
      op: {
        type: 'string',
        required: true,
        description: 'list what is in the shade, or cancel',
        enum: ['list', 'cancel'],
      },
      key: {
        type: 'string',
        description: 'For op=cancel: the key of one notification, exactly as op=list printed it',
      },
      package: {
        type: 'string',
        description: 'For op=cancel: clear every clearable notification from this app instead of'
          + ' naming one key',
      },
    },
  ),

  simpleTool(
    'lw_vibrate',
    'Vibrate the phone for a moment, as a nudge with no notification at all.',
    'vibrate',
    { ms: { type: 'integer', description: 'How long, in milliseconds. Default 200, at most 3000' } },
  ),

  simpleTool(
    'lw_clipboard',
    'Read or write the system clipboard. Writing works at any time and is the way to hand a long'
    + ' piece of text to another app without typing it. Reading only works while DSH-LW is the'
    + ' foreground app - that is an Android 10 rule, not this app\'s - and the answer says so when'
    + ' it cannot.',
    'clipboard',
    {
      op: {
        type: 'string',
        required: true,
        description: 'get to read the clipboard, set to replace it with text',
        enum: ['get', 'set'],
      },
      text: { type: 'string', description: 'What to write, required when op is set' },
    },
  ),

  simpleTool(
    'lw_share',
    'Hand text or a file to the system share sheet, so the person picks which app takes it. This is'
    + ' how a file the agent wrote in the work area gets into a chat app or an editor. The chooser'
    + ' opening is all this reports: which app takes it is the user\'s pick.',
    'share',
    {
      text: { type: 'string', description: 'Text to share, or the message to go beside a file' },
      path: { type: 'string', description: 'A file to share, as an absolute path' },
      mime: { type: 'string', description: 'The MIME type, when the extension does not say it' },
    },
  ),

  simpleTool(
    'lw_open_file',
    'Open a file with whichever app on the device handles its type. Use it to show the user a'
    + ' picture, a PDF or a document the agent produced.',
    'openFile',
    {
      path: { type: 'string', required: true, description: 'The file to open, as an absolute path' },
      mime: { type: 'string', description: 'The MIME type, when the extension does not say it' },
    },
  ),

  simpleTool(
    'lw_download',
    'Ask the system to download a URL into the shared Downloads folder, which is where a file has'
    + ' to be for the user to find it with a file manager. The system downloads it on its own'
    + ' schedule, so this reports that it was queued rather than that it finished.',
    'download',
    {
      url: { type: 'string', required: true, description: 'The http or https URL to download' },
      to: { type: 'string', description: 'The file name to save it as, taken from the URL by default' },
    },
  ),

  simpleTool(
    'lw_device',
    'What this device is: model, Android version, ABI, screen size and density, locale, uptime, the'
    + ' privileged channel\'s state and this app\'s own version. Call it once at the start of a'
    + ' session rather than guessing.',
    'device',
  ),

  simpleTool(
    'lw_battery',
    'Battery level, whether it is charging and from what, temperature, voltage, current draw and the'
    + ' screen\'s own state. The screen state matters before anything else: a screenshot of a'
    + ' sleeping device is the last frame, not what is on it.',
    'battery',
  ),

  simpleTool(
    'lw_storage',
    'How full the storage and the memory are: data, shared storage and system partitions, plus total'
    + ' and available memory and this app\'s own heap.',
    'storage',
  ),

  simpleTool(
    'lw_running',
    'How loaded the device is: core count, the load average from the kernel, the memory situation.'
    + ' Worth checking before starting anything heavy.',
    'running',
  ),

  simpleTool(
    'lw_volume',
    'Read or set the device\'s volume: media, ring, notification, alarm and call streams, plus the'
    + ' ringer mode (normal, vibrate, silent). A write is read back, because this device has been'
    + ' seen accepting a write and keeping the old value.',
    'volume',
    {
      op: {
        type: 'string',
        required: true,
        description: 'get or set for one stream\'s level, adjust for one step, mode for the ringer',
        enum: ['get', 'set', 'adjust', 'mode'],
      },
      system: {
        type: 'string',
        description: 'Which stream: media, ring, notification, alarm or call. Default media',
        enum: ['media', 'ring', 'notification', 'alarm', 'call'],
      },
      level: { type: 'integer', description: 'The level to set, for op=set' },
      direction: {
        type: 'string',
        description: 'up, down or mute, for op=adjust',
        enum: ['up', 'down', 'mute'],
      },
      mode: {
        type: 'string',
        description: 'normal, vibrate or silent, for op=mode',
        enum: ['normal', 'vibrate', 'silent'],
      },
    },
  ),

  simpleTool(
    'lw_media',
    'Send a media transport key: play, pause, playPause, next, previous or stop. It goes to whatever'
    + ' the device considers its current media session.',
    'media',
    {
      op: {
        type: 'string',
        required: true,
        description: 'The transport control to send',
        enum: ['play', 'pause', 'playPause', 'next', 'previous', 'stop'],
      },
    },
  ),

  simpleTool(
    'lw_net',
    'Read the network state (active transport, WiFi name, IP addresses, airplane mode), or change'
    + ' one of the radios: airplane, data, wifi, bluetooth. Reading is free; changing needs the'
    + ' privileged channel and may be refused by the ROM, which the answer says.',
    'net',
    {
      op: {
        type: 'string',
        required: true,
        description: 'get to read, or airplane / data / wifi / bluetooth to turn one on or off',
        enum: ['get', 'airplane', 'data', 'wifi', 'bluetooth'],
      },
      on: { type: 'boolean', description: 'For the changing ops: true to turn it on, false to turn it off' },
    },
  ),

  simpleTool(
    'lw_system',
    'Read or write a few system settings: screen brightness, screen timeout, auto-rotate and font'
    + ' scale. Reading also covers bluetooth, NFC and airplane mode. The protected ones need the'
    + ' WRITE_SETTINGS grant, which is given from a system settings page; a write is read back and'
    + ' the answer says when the device did not keep it.',
    'system',
    {
      op: {
        type: 'string',
        required: true,
        description: 'get to read one key (or all of them), set to write one',
        enum: ['get', 'set'],
      },
      key: {
        type: 'string',
        description: 'Which setting: brightness, screen_off_timeout, accelerometer_rotation, font_scale,'
          + ' bluetooth, nfc or airplane_mode',
      },
      value: { type: 'integer', description: 'The value to write, for op=set' },
    },
  ),

  simpleTool(
    'lw_sensor',
    'List the device\'s sensors, or read one of them once. The value is a single sample, so a sensor'
    + ' that is not moving reports its resting value.',
    'sensor',
    {
      name: { type: 'string', description: 'A sensor name or type; omit it to list them all' },
      timeoutMs: { type: 'integer', description: 'How long to wait for a sample. Default 1500' },
    },
  ),

  simpleTool(
    'lw_location',
    'Where the device is, from its last known fix, or - with fresh=true - by waiting for a new one.'
    + ' Needs the location permission; indoors a fresh fix often does not arrive, and the answer'
    + ' says that rather than guessing.',
    'location',
    {
      fresh: { type: 'boolean', description: 'Wait for a new fix instead of reading the last known one' },
      timeoutMs: { type: 'integer', description: 'How long to wait for a fresh fix. Default 10000' },
    },
  ),

  simpleTool(
    'lw_permissions',
    'Which permissions this app has right now, per capability, and how each missing one is granted.'
    + ' Call it when a tool says it is missing a permission, or before relying on the camera, the'
    + ' location, notifications or the clipboard.',
    'permissions',
  ),

  simpleTool(
    'lw_power',
    'The screen and the lock: state to see, on to wake it, off to put it to sleep, lock to lock it.'
    + ' This is the way out of the sleeping device trap - a screenshot of a sleeping device is the'
    + ' last frame, and injected touches do not wake it.',
    'power',
    {
      op: {
        type: 'string',
        required: true,
        description: 'state, on, off or lock',
        enum: ['state', 'on', 'off', 'lock'],
      },
    },
  ),

  simpleTool(
    'lw_app_control',
    'Force stop, clear the data of, uninstall, install, disable or enable an app, or make one the'
    + ' home app. **forceStop, clearData, uninstall, install and disable each ask for a tap on the'
    + ' phone first**: the app puts a confirmation on screen and nothing happens unless someone taps'
    + ' it, so a call from an unattended run comes back saying nobody confirmed. disable is in that'
    + ' group because it is stickier than a force stop - the app leaves the launcher and only comes'
    + ' back if someone enables it again. enable and setHome act straight away. Use these when the'
    + ' user asked for exactly that change; it is not how a stuck app is normally dealt with.',
    'syscmd',
    {
      op: {
        type: 'string',
        required: true,
        description: 'forceStop, clearData, uninstall, install, disable, enable or setHome',
        enum: ['forceStop', 'clearData', 'uninstall', 'install', 'disable', 'enable', 'setHome'],
      },
      package: {
        type: 'string',
        required: true,
        description: 'The package to act on, or the APK path for install; for setHome, the component'
          + ' as package/class',
      },
      user: { type: 'integer', description: 'The Android user, for a cloned app. Default 0' },
    },
  ),

  // 1.0.3: 打开一个链接, 或者起一个具体的 intent
  //
  // 这两条与 lw_launch 是同一件事的两个层次: lw_launch 认应用名, 而这里认动作与地址。**只有 am 的
  // start 那一个动词进得了白名单**, 所以它能做的仍然只是"起一个 activity", 不是一条命令
  defineTool({
    name: 'lw_intent',
    description:
      'Open a link, or start an activity by its action or component, on one screen. Use openUrl for'
      + ' a plain http/https address - a browser, a shop page, a map link. Use action or component'
      + ' when the user named something more specific: a mail composer (android.intent.action.SENDTO'
      + ' with a mailto: address), a settings page of another app, a specific class. The screen is'
      + ' named the same way every other acting tool names one, and the activity lands on it.'
      + ' Nothing here is a shell: the one command this reaches is `am start`, with an action, a'
      + ' piece of data, a component and a display.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'openUrl for an http(s) address, intent for anything else',
        enum: ['openUrl', 'intent'],
      },
      url: { type: 'string', description: 'The http or https address, for openUrl' },
      action: {
        type: 'string',
        description: 'An intent action such as android.intent.action.SENDTO, for intent',
      },
      data: {
        type: 'string',
        description: 'What the action is about - a mailto: address, a content:// uri, for intent',
      },
      component: {
        type: 'string',
        description: 'A class to start, as package/class, for intent',
      },
      displayId: DISPLAY_ID,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const op = args?.op === 'openUrl' ? 'openUrl' : 'intent'
      const target = op === 'openUrl' ? args?.url : args?.component || args?.action
      if (typeof target !== 'string' || target.length === 0) {
        return op === 'openUrl'
          ? 'openUrl needs a url: give the http or https address to open'
          : 'intent needs an action or a component: on its own, a display is not something to start'
      }
      return answerOf(await call('syscmd', {
        op,
        // openUrl 把地址放在 package 上 (那一侧的 `-d` 就是它), intent 认的是 action / data / component
        package: op === 'openUrl' ? args?.url : undefined,
        component: op === 'intent' ? args?.component : undefined,
        action: args?.action,
        data: args?.data,
        displayId: args?.displayId,
        note: args?.note,
      }))
    },
  }),

  // 键与打字的补充: 一次按住几个键 (Ctrl+A 那种), 以及把文本放进指定的字段
  defineTool({
    name: 'lw_key_combo',
    description:
      'Hold two or more keys down together and let go - Ctrl+A, Shift+Tab, Alt+Tab, Ctrl+Shift+T.'
      + ' Use it for the shortcuts a single key cannot express: select all, the app switcher, a new'
      + ' tab. Send the keys in the order they should be held, modifiers first. Names are the'
      + ' Android ones without the KEYCODE_ prefix (CTRL_LEFT, SHIFT_LEFT, ALT_LEFT, A, TAB, ...),'
      + ' and a name Android does not have is refused instead of being pressed as some other key.',
    parameters: {
      keys: {
        type: 'array',
        required: true,
        description: 'The keys to hold, modifiers first, at least two, at most four',
        items: { type: 'string' },
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const keys = Array.isArray(args?.keys) ? args.keys : []
      return answerOf(await call('syscmd', { op: 'keyCombo', keys, note: args?.note }))
    },
  }),

  // 让设备别睡
  simpleTool(
    'lw_keep_awake',
    'Hold a wake lock so the device does not sleep while something takes a while: a long download,'
    + ' a batch of screenshots, a step that must not be interrupted. **The screen may still go off**'
    + ' - this keeps the CPU awake, not the display - and a screen that has gone off hands back its'
    + ' last frame without saying so, which is why lw_screenshot and lw_ocr report the screen state'
    + ' too. **It does not let go on its own**: nothing here times out, so call it again with off'
    + ' when the work is done, or the device stays awake until something else stops it. Ask with op'
    + ' status to see whether it is held and for how long.',
    'keepAwake',
    {
      op: {
        type: 'string',
        description: 'on to hold it, off to let go, status to ask. Default on',
        enum: ['on', 'off', 'status'],
      },
      note: NOTE,
    },
  ),

  simpleTool(
    'lw_wait_for',
    'Wait until a screen shows something by name, then answer where it is. Use it after an action'
    + ' that makes a screen change: reading the tree immediately afterwards reads the old screen.'
    + ' The match is the same one lw_tap uses, so a name this finds is a name that can be pressed.',
    'waitFor',
    {
      displayId: DISPLAY_ID,
      text: { type: 'string', required: true, description: 'The text or description to wait for' },
      timeoutMs: {
        type: 'integer',
        description: 'How long to wait before giving up. Default 15000, at most 90000',
      },
    },
  ),

  simpleTool(
    'lw_ui_dump',
    'Write the whole view hierarchy of a screen to a file in the work area, layout containers'
    + ' included, and answer with the first lines of it. lw_ui keeps only the controls worth naming;'
    + ' this is for when the question is why the screen is laid out the way it is.',
    'uiDump',
    {
      displayId: DISPLAY_ID,
      to: { type: 'string', description: 'Where to write it; the work area by default' },
      limit: { type: 'integer', description: 'At most this many nodes. Default 4000' },
    },
  ),

  simpleTool(
    'lw_gesture',
    'Draw a multi-finger gesture: one path per finger, all moving together. Use it for the shapes'
    + ' neither a tap nor a straight swipe can make - two fingers at once, a curve, a drag that has'
    + ' to go round something. A point per path is not a gesture: paths need at least two.',
    'gesture',
    {
      displayId: DISPLAY_ID,
      paths: {
        type: 'array',
        required: true,
        description: 'One object per finger: { "points": [ { "x": 1, "y": 2 }, ... ] }, in the'
          + ' screen\'s own pixels',
        items: {
          type: 'object',
          // 对象节点必须自己声明开闭: dsh 的作者侧 schema 把 additionalProperties 定成必填, 少一个
          // 就是模块求值期抛错, 整包 UNSUPPORTED_SCHEMA, 会话里一个 lw_ 工具都不剩
          additionalProperties: false,
          properties: {
            points: {
              type: 'array',
              items: {
                type: 'object',
                additionalProperties: false,
                properties: { x: { type: 'number' }, y: { type: 'number' } },
              },
            },
          },
        },
      },
      durationMs: { type: 'integer', description: 'How long the whole gesture takes. Default 300' },
    },
  ),

  simpleTool(
    'lw_pinch',
    'Pinch in or out around a point: two fingers starting either side of it and moving together or'
    + ' apart. scale above 1 zooms in (the fingers spread), below 1 zooms out.',
    'pinch',
    {
      displayId: DISPLAY_ID,
      x: { type: 'number', required: true, description: 'The centre of the pinch, in screen pixels' },
      y: { type: 'number', required: true, description: 'The centre of the pinch, in screen pixels' },
      scale: { type: 'number', description: 'Above 1 spreads the fingers (zoom in), below 1 closes them' },
      durationMs: { type: 'integer', description: 'How long the pinch takes. Default 300' },
    },
  ),
  simpleTool(
    'lw_scroll',
    'Scroll a screen by a screenful, through the scrollable control\'s own accessibility action'
    + ' rather than an injected drag. Use it wherever a list or a form is longer than the screen:'
    + ' a control that has not been laid out yet cannot be pressed by name, and on this device an'
    + ' injected drag does not move a list at all. It scrolls the largest scrollable control on the'
    + ' screen, or the container around the control that a name points at. Read the screen again'
    + ' afterwards - every row has moved, and the rectangles with them.',
    'scroll',
    {
      displayId: DISPLAY_ID,
      direction: {
        type: 'string',
        description: 'forward reads on (the content moves up), backward goes back. Default forward',
        enum: ['forward', 'backward'],
      },
      text: {
        type: 'string',
        description: 'Name of a control whose scrollable container should be scrolled, as lw_ui'
          + ' reported it. Leave it out to scroll the largest scrollable control on the screen',
      },
      times: { type: 'integer', description: 'How many screenfuls, 1 to 10. Default 1' },
    },
  ),

  // ---- 1.0.3 批次 4: 工作区里的文件、媒体库、拍照 ----
  //
  // 这三条与 1.0.2 那一批同形 (app 进程里用 Context 做, 走桥只为把结果按同一套协议送回来), 但
  // `lw_files` 的说明要多说一句它**不**是什么: 这个会话自己就带着 read / write / glob, 而工作区
  // 就是那套工具的家, 所以模型不该为了"在工作区里读写一个文件"来这里

  simpleTool(
    'lw_files',
    'Look at the work area the way the phone does: list a directory, read a text file, write one.'
    + ' Reach for this when the question is about what the *device* makes of a file - whether the'
    + ' media library lists it (that is what the gallery, the music player and a file manager read),'
    + ' what type the system calls it, how big a picture is - or when a file has to be written and'
    + ' the phone has to see it in the same step. Writing scans the file afterwards, so a picture'
    + ' lands in the gallery without a second call. **Paths are resolved against the work area**'
    + ' (a relative one starts there, and anything outside it is refused), which is the one anchor'
    + ' that does not drift with the session\'s own directory. This is not a replacement for this'
    + ' session\'s own read / write / glob tools: those work anywhere the session is allowed to, and'
    + ' they are the ones to use inside the work area for anything else.',
    'files',
    {
      op: {
        type: 'string',
        required: true,
        description: 'list a directory, read a text file, or write one',
        enum: ['list', 'read', 'write'],
      },
      path: {
        type: 'string',
        description: 'The file or directory, relative to the work area or absolute inside it.'
          + ' Required for read and write; list defaults to the work area itself',
      },
      text: {
        type: 'string',
        description: 'What to write, required when op is write. Text only - a binary file has to'
          + ' be built by something else',
      },
    },
  ),

  simpleTool(
    'lw_media_scan',
    'Ask the Android media library to index a file or a directory, and answer with what it made'
    + ' of each file. Use it after something wrote a picture, a video or a piece of audio that the'
    + ' gallery still cannot see: a file that lands in shared storage is not in the library until'
    + ' something scans it, and that is the difference between "the file is there" and "the person'
    + ' can find it". A relative path starts at the work area and an absolute one is taken as it is,'
    + ' so a file inside the work area works either way and one outside it (Downloads, for instance)'
    + ' has to be named in full - this only asks the system to index it and hands back the library\'s'
    + ' own answer, so it discloses no content. A file the scanner does not add (a `.nomedia`'
    + ' directory, a type it does not index) is reported as exactly that rather than as a failure.',
    'mediaScan',
    { path: { type: 'string', required: true, description: 'The file or directory to index' } },
  ),

  simpleTool(
    'lw_take_photo',
    'Take a photo with the device\'s own camera app, and leave it in the work area. This is for'
    + ' "show me what is in front of the phone" or "photograph this": it opens the system camera on'
    + ' the phone\'s own screen and waits (up to waitMs) for a picture to be written. **A person has'
    + ' to press the shutter** - the shutter is on the phone\'s screen, not on a virtual screen of'
    + ' ours, so this only works with someone holding the device. It answers whether the camera'
    + ' really came up, and whether a photo landed; when nothing did, the empty file it made is'
    + ' removed rather than left behind. Read the result back with read_image to see it, or hand it'
    + ' to the person with lw_open_file.',
    'takePhoto',
    {
      waitMs: {
        type: 'integer',
        description: 'How long to wait for the photo, in milliseconds. Default 20000, at most'
          + ' 120000 - a person has to take it in that time',
      },
      note: NOTE,
    },
  ),

  // ---- 1.0.3 批次 6: 事件订阅 ----
  //
  // 这一批要改的是模型的**读法**: 按一下之后不要马上反复 lw_ui, 而是先说清"我在等什么", 再等它发生。
  // 事件由系统送进服务那条有界队列 (掩码里就四个类型), 这两条工具读的就是那根游标

  simpleTool(
    'lw_events_subscribe',
    'Start watching a screen for changes, so a later call can be told what happened instead of'
    + ' asking again and again. Use it right after an action that makes something change: the'
    + ' answer lists what had just happened in the same scope (so you are not blind to the moments'
    + ' before you started) and hands back an id. Then lw_events_wait with that id collects what'
    + ' happens next. **This is how to stop polling**: lw_ui reads a snapshot and costs a round'
    + ' trip, while a subscription costs nothing until something actually changes. A subscription'
    + ' lives for lifeMs (ten minutes by default) and then forgets itself; op=stop ends one early,'
    + ' op=list shows the live ones. The four kinds are window (a different window or activity came'
    + ' up), content (the inside of a window changed), focus (a field was focused) and scroll.',
    'eventsSubscribe',
    {
      op: {
        type: 'string',
        description: 'start watching (the default), stop one, or list the live ones',
        enum: ['start', 'stop', 'list'],
      },
      id: { type: 'string', description: 'For op=stop: the id the subscription was given' },
      displayId: WATCHED_DISPLAY,
      kind: {
        type: 'string',
        description: 'Only this kind of change; leave it out for all four',
        enum: ['window', 'content', 'focus', 'scroll'],
      },
      package: { type: 'string', description: 'Only changes from this app, by package name' },
      lifeMs: {
        type: 'integer',
        description: 'How long the subscription lives, in milliseconds. Default 600000 (ten'
          + ' minutes), at most 1800000',
      },
    },
  ),

  simpleTool(
    'lw_events_wait',
    'Wait until something changes on a screen, then answer with what happened. Give it the id of a'
    + ' subscription from lw_events_subscribe to carry on from where that one got to, or pass the'
    + ' filters instead and it watches just for this one call. It answers with the changes in order,'
    + ' with how long after the wait started each one landed, and says so plainly when nothing'
    + ' changed in that time - which is itself a useful answer, and cheaper than reading the screen'
    + ' twice. Events arrive throttled by the system (about ten a second) and identical ones in a'
    + ' row are collapsed into one line with a count, so a scroll shows up as a line rather than as'
    + ' hundreds.',
    'eventsWait',
    {
      displayId: WATCHED_DISPLAY,
      id: {
        type: 'string',
        description: 'The subscription to carry on from; leave it out to watch just for this call'
          + ' using the filters below',
      },
      kind: {
        type: 'string',
        description: 'Only this kind of change; leave it out for all four',
        enum: ['window', 'content', 'focus', 'scroll'],
      },
      package: { type: 'string', description: 'Only changes from this app, by package name' },
      timeoutMs: {
        type: 'integer',
        description: 'How long to wait before answering that nothing happened. Default 15000, at'
          + ' most 90000',
      },
      limit: { type: 'integer', description: 'At most this many events to print. Default 20' },
    },
  ),

  defineTool({
    name: 'lw_speech',
    description:
      'Transcribe speech on this phone with no network and no API key: the app links sherpa-onnx '
      + 'and runs SenseVoice (Chinese, English, Cantonese, Japanese and Korean, with punctuation) '
      + 'in its own process, so the audio never leaves the device. op=status reports the engine and '
      + 'whether the model is on disk; op=prepare downloads it once, about 240 MB from the '
      + 'hf-mirror copy of the model (huggingface.co itself is unreachable from this phone), plus '
      + 'the 1.8 MB silero voice-activity model that the always-listening chain cuts segments '
      + 'with; op=transcribe turns one 16 kHz mono PCM16 WAV file into text. This is the same '
      + "engine the GUI's own voice input button uses, so prepare is what makes that button usable.",
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'status, prepare or transcribe',
      },
      wav: {
        type: 'string',
        description:
          'Absolute path of a 16 kHz mono PCM16 WAV file, required for op=transcribe',
      },
      language: {
        type: 'string',
        description:
          'auto, zh, en, yue, ja or ko (default auto, which lets the model decide)',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      if (args.op === 'status') {
        const info = await speechInspect()
        return [
          `engine ${info.engine} ${info.sherpa} (onnxruntime ${info.onnxruntime})`,
          `model ${info.model} in ${info.directory}`,
          info.present
            ? `downloaded: ${info.modelBytes} bytes of weights, ${info.tokensBytes} bytes of tokens`
            : 'the model is not downloaded yet, so op=prepare is what comes first',
          info.vad
            ? `silero VAD downloaded: ${info.vadBytes} bytes at ${info.vadPath}`
            : `the silero VAD is missing (${info.vadBytes} bytes at ${info.vadPath}): the wake word`
              + ' still listens, but nothing gets cut into segments until op=prepare fetches it',
          `loaded in memory: ${info.loaded ? 'yes' : 'no'}`,
          `languages: ${info.languages}`,
        ].join('\n')
      }
      if (args.op === 'prepare') {
        const info = await speechPrepare()
        return `the model is ready in ${info.directory}; the GUI voice input button (and`
          + ' op=transcribe) can use it now'
      }
      if (args.op === 'transcribe') {
        if (!args.wav) throw new Error('op=transcribe names the recording with wav=<a 16 kHz mono WAV>')
        const answer = await speechTranscribe(args.wav, args.language)
        return `${answer.text}\n\n(${answer.seconds.toFixed(1)}s of audio, ${answer.language},`
          + ` ${answer.elapsedMs} ms of inference)`
      }
      throw new Error(`op has to be status, prepare or transcribe, not "${args.op}"`)
    },
  }),
  defineTool({
    name: 'lw_speak',
    description:
      'Speak a line out loud on this phone through its own text-to-speech engine: nothing goes over '
      + 'the network and no API key is involved. op=status reports the engine, how many voices it '
      + 'has and whether Chinese is usable; op=speak says the text and waits until the engine '
      + 'reports it finished; op=stop cuts off whatever is being said; op=release lets the engine go '
      + '(bringing it up costs a few hundred milliseconds, so it is kept while in use). Use it to '
      + 'read a result back to the person holding the phone, for instance one line when a task is '
      + 'done. Whether sound actually came out is theirs to confirm: the answer only reports what '
      + 'the engine said.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'status, speak, stop or release',
      },
      text: {
        type: 'string',
        description: 'The line to speak, required for op=speak. Keep it short: this is read aloud.',
      },
      interrupt: {
        type: 'boolean',
        description: 'Cut off whatever is being said before this line (default true)',
      },
      rate: {
        type: 'number',
        description: 'Speech rate from 0.5 to 2.0 (default 1.0)',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const request = { op: args.op }
      if (args.text !== undefined) request.text = args.text
      if (args.interrupt !== undefined) request.interrupt = args.interrupt
      if (args.rate !== undefined) request.rate = args.rate
      const answer = await call('speak', request)
      if (args.op === 'status') {
        const onDevice = answer.readingWith === 'on-device'
        return [
          `reading with: ${onDevice
            ? `the on-device voice ${answer.onDeviceModel}`
            : 'the system engine'}`,
          `rate: ${answer.rateFollowsSystem ? 'whatever the system says' : `${answer.rate} x`}`
            + `, voice: ${answer.selectedVoice}`,
          answer.readAloud === false
            ? 'read-aloud: off (a finished reply is not read on its own; a line asked for by name still is)'
            : 'read-aloud: on (every finished reply is read out loud)',
          `system engine ${answer.engine}`,
          `voices ${answer.voices}, Chinese: ${answer.chinese}`,
          answer.chineseVoices
            ? `Chinese voices: ${answer.chineseVoices}`
            : 'no Chinese voice is listed by the engine',
          answer.onDeviceVoices
            ? `on-device voices: ${answer.onDeviceVoices}`
            : `on-device voices: none imported under ${answer.voicesDirectory}`,
          `speaking right now: ${answer.speaking ? 'yes' : 'no'} (${answer.utterances} utterances so far)`,
        ].join('\n')
      }
      if (args.op === 'speak') {
        if (!answer.spoken) return `the engine did not report finishing: ${answer.detail}`
        // 自带那条要报出用的是哪个音色目录, 系统那条没有这一项
        const withVoice = answer.readingWith === 'on-device' ? ` with the on-device voice ${answer.voice}` : ''
        return `the engine took ${answer.characters} characters` + withVoice
          + (answer.pieces > 1 ? ` in ${answer.pieces} pieces` : '')
          + ' and reported it finished'
      }
      if (args.op === 'stop') {
        return answer.stopped
          ? `stopped: ${answer.detail}`
          : `nothing was stopped: ${answer.detail}`
      }
      return answer.released
        ? 'the engine was let go; the next call brings it up again'
        : answer.detail
    },
  }),
  defineTool({
    name: 'lw_voice',
    description:
      'The always-listening voice chain on this phone: the app keeps one microphone open, cuts it '
      + 'into sentences with a silero VAD (3 s of silence ends one, 15 s at most each) and '
      + 'transcribes each sentence on-device, then drops it into a queue the host sends into the '
      + 'conversation as a `voice`-sourced message (steering into a running turn when there is one, '
      + 'creating a session when there is none). op=inbox reports that queue, where the reader has '
      + 'got to and how the last few deliveries landed; op=clean shows what a piece of markdown '
      + 'would sound like when read aloud (code blocks, tables and links are stripped); op=read '
      + 'speaks a line right now through the same cleaning and the same engine the automatic reading '
      + 'uses; op=say delivers a line by hand, exactly as if it had been spoken. The chain itself is '
      + 'started and stopped with lw_wakeword, whose status reports it.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'inbox, clean, read or say',
      },
      text: {
        type: 'string',
        description: 'The text op=clean, op=read and op=say work on',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      if (args.op === 'say') {
        if (!args.text) throw new Error('op=say needs text=<the line to deliver>')
        const outcome = await voiceDeliver(ctx, { seq: 0, text: args.text, source: 'voice', at: Date.now() })
        return `delivered to ${String(voiceDelivery.sessionId)}: `
          + (outcome.running ? 'steered into the running turn' : 'queued for the next turn')
      }
      if (args.op === 'clean' || args.op === 'read') {
        if (!args.text) throw new Error(`op=${args.op} needs text=<what to say>`)
        const spoken = readAloudText(args.text)
        if (!spoken) {
          return 'nothing in that text is worth reading aloud: it is all code, tables, links or'
            + ' blank lines, so the cleaner left an empty line'
        }
        if (args.op === 'clean') return spoken
        const answer = await call('speak', { op: 'speak', text: spoken })
        // 自带那条引擎会回报用的是哪个音色目录; 系统那条没有这一项
        const via = answer.readingWith === 'on-device' ? ` with the on-device voice ${answer.voice}` : ''
        return answer.spoken
          ? `said ${answer.characters} characters${via} (from ${args.text.length} of markdown)`
          : `the engine did not report finishing: ${answer.detail}`
      }
      if (args.op === 'inbox') {
        const state = await voiceInboxState()
        if (!state.inbox) return 'the voice inbox has no place to live: DSH_HOME is not set'
        const lines = state.queued.slice(-MAX_VOICE_LINES)
        return withJson(
          [
            `inbox ${state.inbox}`,
            `${state.queued.length} sentence(s) in the file, reader has delivered up to #`
              + `${state.cursor === null ? 'nothing yet' : state.cursor}`,
            `${voiceDelivery.lines} delivered`
              + ` (${voiceDelivery.steered} steered, ${voiceDelivery.queued} queued)`
              + `${voiceDelivery.sessionId ? `, last to ${voiceDelivery.sessionId}` : ''}`,
            `${voiceReading.count} reply(ies) read aloud`
              + `${voiceReading.skipped ? `, ${voiceReading.skipped} skipped because read-aloud is off` : ''}`
              + `${voiceReading.error ? `, last problem: ${voiceReading.error}` : ''}`,
            ...lines.map((line) => `  #${line.seq} ${line.text}`),
          ],
          {
            inbox: state.inbox,
            cursor: state.cursor,
            queued: lines,
            delivery: { ...voiceDelivery },
            reading: { ...voiceReading },
          },
        )
      }
      throw new Error(`op has to be inbox, clean, read or say, not "${args.op}"`)
    },
  }),
  defineTool({
    name: 'lw_overlay',
    description:
      'Float the dsh GUI over other apps as a system overlay window, which is what makes its input '
      + 'box reachable without leaving whatever app the person is in. The window is the same GUI as '
      + 'a second client of the same host - same sessions, same cookies - so the composer, the '
      + 'voice input button and the read-aloud button all work inside it. It has a title bar that '
      + 'drags it, a 收起 button that leaves only that bar, an 应用 button that brings the full app '
      + 'forward and a × that closes it. op=show puts it up (default: full width, 45% of the screen, '
      + 'near the bottom, all of which width/height/x/y can override in pixels); op=hide closes it; '
      + 'op=state says whether the overlay permission is granted, whether a window is up and what '
      + 'page it is on. Because the window takes focus so a keyboard can type into it, touches '
      + 'outside it no longer pass through - keep it small.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'show, hide or state',
      },
      width: { type: 'number', description: 'Window width in pixels (op=show)' },
      height: { type: 'number', description: 'Window height in pixels (op=show)' },
      x: { type: 'number', description: 'Distance from the left edge in pixels (op=show)' },
      y: { type: 'number', description: 'Distance from the top edge in pixels (op=show)' },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const request = { op: args.op }
      for (const key of ['width', 'height', 'x', 'y']) {
        if (args[key] !== undefined) request[key] = args[key]
      }
      const answer = await call('overlay', request)
      if (args.op === 'show') {
        return `the window is up at ${answer.x},${answer.y} sized ${answer.width}x${answer.height},`
          + ` showing ${answer.url}`
      }
      if (args.op === 'hide') return answer.detail
      return [
        `overlay permission: ${answer.permission ? 'granted' : 'not granted'}`,
        `window: ${answer.showing ? `up on ${answer.url}` : 'not up'}`,
        `host: ${answer.host}`,
        answer.page ? `last problem: ${answer.page}` : '',
      ].filter(Boolean).join('\n')
    },
  }),
  defineTool({
    name: 'lw_wakeword',
    description:
      'Listen for a wake word on this phone, so the agent can be called by voice instead of by '
      + 'typing. The listening runs in the app itself with sherpa-onnx keyword spotting - a 3.3M '
      + 'parameter zipformer, 16 kHz mono, nothing leaves the device and no API key is involved. '
      + 'op=status reports the model, the words being watched for, whether the microphone '
      + 'permission is granted, whether the listener is up and how many times it has fired; '
      + 'op=prepare downloads the model once (about 5.5 MB) and writes the default word 素云; '
      + 'op=keywords replaces the word table (each word is given as 词=拼音, for example '
      + '素云=su4 yun2 - the pinyin is what the model needs, see docs/wake-word.md); op=start '
      + 'starts the foreground listener, which keeps a notification with a 停止 button; op=stop '
      + 'ends it. What happens on a hit is onWake: app brings the app forward (default, and the '
      + 'one that works without the overlay permission), overlay floats the GUI window over '
      + 'whatever is on the screen. Two things to say plainly: the microphone is really on the '
      + 'whole time while it listens, and a two syllable word like 素云 does get false hits, '
      + 'so threshold is worth tuning on the real device.',
    parameters: {
      op: {
        type: 'string',
        required: true,
        description: 'status, prepare, keywords, start or stop',
      },
      words: {
        type: 'array',
        items: { type: 'string' },
        description:
          'For op=keywords (and for op=prepare, to override the default word): one "词=拼音" entry'
          + ' per word, for example "素云=su4 yun2" or "小爱同学=xiao3 ai4 tong2 xue2". Tone numbers'
          + ' are what the model wants; pinyin already carrying tone marks is accepted as it is',
      },
      lines: {
        type: 'array',
        items: { type: 'string' },
        description:
          'For op=keywords: raw keyword lines in the model\'s own token form, for example'
          + ' "s ù y ún @素云". Use this only when the pinyin route cannot say what you mean',
      },
      threshold: {
        type: 'number',
        description: 'For op=start: how sure the spotter has to be before it fires (default 0.25)',
      },
      score: {
        type: 'number',
        description: 'For op=start: how strongly the keyword is boosted (default 1.5)',
      },
      onWake: {
        type: 'string',
        description:
          'For op=start: "app" brings the app forward, "overlay" floats the GUI window (default app)',
      },
      vibrateMs: {
        type: 'integer',
        description: 'For op=start: how long to buzz on a hit (default 200, 0 for silent)',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      if (args.op === 'status') {
        const info = await wakeWordInspect()
        return [
          `engine sherpa-onnx keyword spotting, model ${info.model}`,
          `model in ${info.directory}: ${info.present ? 'downloaded' : 'not downloaded yet'}`
            + (info.modelFiles ? ` (${info.modelFiles})` : ''),
          `watching for: ${info.keywordNames || 'nothing yet'}`,
          `microphone permission: ${info.permission ? 'granted' : `not granted - ${info.permission}`}`,
          `listener: ${info.listening ? 'up' : 'not running'}, ${info.hits} hit(s)`
            + (info.lastKeyword ? `, last was ${info.lastKeyword} at ${new Date(info.lastHitAt).toLocaleString()}` : ''),
          info.unknownTokens
            ? `these tokens are not in the model's table, so their lines would be dropped silently:`
              + ` ${info.unknownTokens}`
            : '',
          info.lastError ? `last problem: ${info.lastError}` : '',
        ].filter(Boolean).join('\n')
      }
      if (args.op === 'prepare') {
        const info = await wakeWordPrepare(args.words)
        return `the model is in ${info.directory} (${WAKEWORD_FILES.length} files, sha256 checked)`
          + ` and the table holds: ${info.keywordNames || 'nothing'}. op=start is what begins listening`
      }
      if (args.op === 'keywords') {
        const lines = await wakeWordLinesFor(args)
        const answer = await call('wakeword', { op: 'keywords', lines })
        return answer.written
          ? `the table now holds ${answer.keywords}`
          : `the table was not changed: ${answer.keywords || 'it is empty'}`
      }
      if (args.op === 'start') {
        const request = { op: 'start' }
        for (const key of ['threshold', 'score', 'onWake', 'vibrateMs']) {
          if (args[key] !== undefined) request[key] = args[key]
        }
        const answer = await call('wakeword', request)
        return `listening for ${answer.keywords}; the microphone is on until op=stop, and a hit`
          + ' shows up in op=status'
      }
      if (args.op === 'stop') {
        const answer = await call('wakeword', { op: 'stop' })
        return `${answer.detail} (${answer.hits} hit(s) this run)`
      }
      throw new Error(`op has to be status, prepare, keywords, start or stop, not "${args.op}"`)
    },
  }),
]

/** One request, one response: the app answers a single line and closes the connection */
async function call(method, params = {}) {
  const endpoint = process.env[ENDPOINT_VARIABLE]
  const token = process.env[TOKEN_VARIABLE]
  if (!endpoint || !token) {
    throw new Error(
      `no privileged channel: ${ENDPOINT_VARIABLE} and ${TOKEN_VARIABLE} are unset,`
      + ' which means this host was not started by LittleWhale',
    )
  }
  const answer = await request(endpoint, JSON.stringify({ method, token, ...params }))
  let parsed
  try {
    parsed = JSON.parse(answer)
  } catch (error) {
    throw new Error(`the app answered with something that is not JSON: ${answer.slice(0, 200)}`)
  }
  if (parsed.ok !== true) throw new Error(parsed.error ?? 'the app reported no reason')
  return parsed.result
}

/** Send one line and resolve with the first line that comes back */
function request(endpoint, line) {
  const split = endpoint.lastIndexOf(':')
  const host = endpoint.slice(0, split)
  const port = Number(endpoint.slice(split + 1))
  return new Promise((resolve, reject) => {
    const socket = connect({ host, port })
    let buffer = ''
    let settled = false
    socket.setEncoding('utf8')
    socket.on('connect', () => socket.write(`${line}\n`))
    socket.on('data', (chunk) => {
      buffer += chunk
      const end = buffer.indexOf('\n')
      if (end < 0) return
      settled = true
      socket.end()
      resolve(buffer.slice(0, end))
    })
    socket.on('error', (error) => {
      settled = true
      reject(new Error(`could not reach the app's privileged channel at ${endpoint}: ${error.message}`))
    })
    socket.on('close', () => {
      if (settled) return
      settled = true
      reject(new Error(`the app closed the channel at ${endpoint} without answering`))
    })
  })
}

/** A tool's arguments as a request body, without the ones the caller left out or that are ours */
function drop(args, ...ours) {
  const skipped = new Set(ours)
  return Object.fromEntries(
    Object.entries(args ?? {}).filter(([key, value]) => value !== undefined && !skipped.has(key)),
  )
}

/**
 * How long something is to be held, as a number of milliseconds
 *
 * A string rather than a number because a bare number does not say what unit it is in: "1s",
 * "500ms", "1.5s" all work, and so do the three names above. A number that arrives anyway is read
 * as seconds, which is what "1" nearly always means
 *
 * @param value what the caller passed as hold, absent when it left it out
 * @param fallback what to answer when it did, which is "a plain press" for both tools
 */
function holdMs(value, fallback = 0) {
  if (value === undefined || value === null || value === '') return fallback
  const text = String(value).trim().toLowerCase()
  const names = Object.keys(HOLD_NAMES).join(' / ')
  if (Object.hasOwn(HOLD_NAMES, text)) return HOLD_NAMES[text]
  const parsed = /^(\d+(?:\.\d+)?)\s*(ms|s)?$/.exec(text)
  if (!parsed) {
    throw new Error(`hold has to be a duration like "1s" or "500ms", or one of ${names}`
      + ` - not ${JSON.stringify(value)}`)
  }
  const ms = Math.round(Number(parsed[1]) * (parsed[2] === 'ms' ? 1 : 1000))
  if (ms > MAX_HOLD_MS) {
    throw new Error(`hold of ${ms}ms is longer than this tool allows (${MAX_HOLD_MS}ms): the screen`
      + ' is held for the whole of it and nothing else can be done meanwhile')
  }
  return ms
}

/**
 * 一个"做一件事, 把答案原样念出来"的工具
 *
 * 1.0.2 加的这批能力里大部分都是这个样子: 名字、一段给模型的说明、几个参数, 然后一次桥调用。说明在
 * 这里统一生成, 免得二十个工具各写一遍同样的几句
 *
 * 答案由应用那一侧写好放在 `result.text` 里 —— **它才是唯一知道真的发生了什么的那一边** (有没有
 * 权限、设备认不认、命令的退出码), 插件不该自己编一句
 *
 * @param name 工具名, `lw_` 开头
 * @param description 模型看到的说明
 * @param method 桥上的方法名
 * @param parameters 参数表, 照 `defineTool` 的形状
 */
function simpleTool(name, description, method, parameters = {}) {
  return defineTool({
    name,
    description,
    parameters,
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return answerOf(await call(method, drop(args)))
    },
  })
}

/** 应用那一侧写好的答案, 兜底是让人能读的 JSON —— 正常不该走到那一条 */
function answerOf(result) {
  if (typeof result === 'string') return result
  if (result && typeof result.text === 'string' && result.text.length > 0) return result.text
  return JSON.stringify(result)
}

/**
 * The `hold` parameter of an acting tool, said once and shared by both of them
 */
function holdParameter(tail) {
  const names = Object.entries(HOLD_NAMES).map(([name, ms]) => `${name} (${ms}ms)`).join(', ')
  return {
    type: 'string',
    description: 'How long to hold it down: a duration like "1s" or "500ms", or one of '
      + `${names}. Android reads anything held 500ms or more as a long press, which is why the`
      + ' names start just above that line - short is the ordinary long press. Leave it out for a'
      + ` plain, instant press. ${tail}`,
  }
}

/** A duration as shortly as it can be said back: 600ms, 1.5s, 8s */
function formatMs(ms) {
  return ms >= 1000 ? `${Number((ms / 1000).toFixed(2))}s` : `${ms}ms`
}

/** How long a press was held, as a clause, or an empty string when it was not held */
function holdSaid(result) {
  const ms = result.holdMs ?? 0
  if (!(ms > 0)) return ''
  return `, held for ${formatMs(ms)}`
}

/**
 * The caller's own words about a step, put on the first line of its report
 *
 * It goes into the tool result rather than only into a log, because the result is what the session
 * keeps: a person reading the conversation afterwards sees why each press happened, right where it
 * happened
 */
function said(report, note) {
  const text = (note ?? '').trim()
  if (text === '') return report
  const end = report.indexOf('\n')
  return end < 0
    ? `${report} - "${text}"`
    : `${report.slice(0, end)} - "${text}"${report.slice(end)}`
}

/** The channel's own report, which says whether the device is reachable at all */
function formatProbe(result) {
  const channel = result.channel ?? {}
  const lines = []
  if (channel.connected === true) {
    const identity = channel.uid === 0 ? 'root' : `uid ${channel.uid}`
    lines.push(
      `privileged channel: ${channel.backend} as ${identity} (pid ${channel.pid}, service v${channel.version})`,
    )
  } else {
    lines.push(`privileged channel: not connected${channel.error ? ` - ${channel.error}` : ''}`)
  }

  const input = result.input ?? {}
  if (input.available !== true) {
    lines.push(`input devices: unreadable (${input.reason ?? 'no reason reported'})`)
  } else {
    lines.push(`input devices: ${input.deviceCount}`)
    const touch = input.touchscreen
    if (touch && typeof touch === 'object') {
      lines.push(
        `touchscreen: ${touch.path} "${touch.name}"`
        + ` x=[${touch.x[0]},${touch.x[1]}] y=[${touch.y[0]},${touch.y[1]}]`
        + ` protocolB=${touch.protocolB} direct=${touch.direct} btnTouch=${touch.touchKey}`,
      )
    } else {
      lines.push('touchscreen: none recognised')
    }
  }
  lines.push(brakeLine(result.touch))
  lines.push(...usersLines(result.users))
  return withJson(lines, result)
}

/**
 * The device's Android users, which is only worth saying when there is more than one
 *
 * One user is every phone as it comes, and saying so in every probe would be noise. A second one is
 * what a cloned app is - the same package installed again with its own data - so that is the case
 * the answer exists for: it is the number lw_launch has to be given to start the clone rather than
 * the app it was cloned from
 */
function usersLines(users) {
  const found = Array.isArray(users) ? users : []
  if (found.length < 2) return []
  const named = found.map(
    (user) => `${user.id} (${user.name}${user.running === false ? ', not running' : ''})`,
  )
  return [
    `users: ${named.join(', ')} - an app installed for two of these is two copies with their own`
    + ' data, so name the second one as lw_launch\'s user to start the copy rather than the original',
  ]
}

/** What the brake is doing, which is a fact about the whole device rather than about a screen
 *
 * It is read from the phone's own glass and it is the only thing standing between the model and
 * the screen in somebody's hand, so both the probe and the screen list say what it is up to
 */
function brakeLine(touch) {
  if (!touch || touch.available !== true) {
    const reason = touch && touch.error ? ` - ${touch.error}` : ''
    return `touch watch: not running${reason}, so nothing may act on displayId 0`
  }
  const state = touch.userTouching === true
    ? (touch.down === true
      ? 'the user is on the phone right now, a finger is down'
      : `the user was on the phone ${touch.msSinceLastTouch} ms ago`)
    : 'the phone is free right now'
  return `touch watch: ${touch.path}, ${touch.events} real touches seen, ${state}`
}

/** The screens that exist, which is the list every other call's displayId comes from */
function formatScreens(result) {
  const screens = result.screens ?? []
  const lines = []
  const primary = result.primary
  if (primary && typeof primary.displayId === 'number') {
    lines.push(
      `- displayId ${primary.displayId} "${primary.label}" `
      + `${primary.width}x${primary.height} at ${primary.dpi}dpi, the phone's own screen: always `
      + 'there, no preview, and the person holding the phone is the brake on it',
    )
  }
  if (screens.length === 0) {
    lines.push('no virtual screen exists yet, create one with lw_screen_create')
  } else {
    lines.push(`${screens.length} virtual screen${screens.length === 1 ? '' : 's'}`)
    for (const screen of screens) {
      const shown = screen.displayId === result.selected ? ', shown in the preview on the phone' : ''
      const paused = screen.acceptsControl === false ? ', control paused by the user' : ''
      lines.push(
        `- displayId ${screen.displayId} "${screen.label}" `
        + `${screen.width}x${screen.height} at ${screen.dpi}dpi${shown}${paused}`,
      )
    }
  }
  lines.push(brakeLine(result.touch))
  // 通道不通才说那句话: `lastError` 是**上一次**出错留下的, 通道已经连上时它还在, 念出来就是一句
  // 已经不成立的话 (2026-10-04 在模拟器上就是这么误导的)
  const channel = result.channel ?? {}
  if (channel.connected !== true) {
    lines.push(
      'the screen side is not connected right now: '
      + `${channel.error || result.lastError || 'no reason reported'}`,
    )
  }
  return withJson(lines, result)
}

/** What was just made */
function formatCreated(result) {
  if (result.created !== true) {
    return `the device refused to create a screen: ${result.error || 'no reason reported'}`
  }
  return withJson(
    [`created "${result.label}" as displayId ${result.displayId}, and the phone is showing it`],
    result,
  )
}

/** What a screen is now, after being given another shape */
function formatResized(result) {
  if (result.resized !== true) {
    return `nothing was resized: ${result.error || `no screen with displayId ${result.displayId}`}`
  }
  const how = result.swapped === true ? 'turned a quarter turn' : 'resized'
  return withJson(
    [
      `displayId ${result.displayId} "${result.label}" is now ${result.width}x${result.height}`
      + ` at ${result.dpi}dpi (${how}): anything on it that follows its screen has been laid out`
      + ' again, so take a new picture rather than reusing coordinates from the old one',
    ],
    result,
  )
}

/** What was just given back */
function formatReleased(result, note) {
  const lines = result.released === true
    ? [`released displayId ${result.displayId}`]
    : [`nothing was released: ${result.error || `no screen with displayId ${result.displayId}`}`]
  return said(withJson(lines, result), note)
}

/** One key press, said the way Android names it and the way the caller asked for it */
function formatPressed(result, note) {
  const how = result.longPress === true ? 'long-pressed' : 'pressed'
  return said(
    withJson(
      [
        `${how} ${result.key} (keycode ${result.code}) on displayId ${result.displayId}`
        + ` "${result.label}"${holdSaid(result)}`,
      ],
      result,
    ),
    note,
  )
}

/** What typing did, and what the field says now */
function formatTyped(result, note) {
  const where = result.via === 'keys'
    ? ' as key presses, because that screen reports no text field, so it went wherever focus is'
    : ` into ${fieldName(result.field)}`
  const count = `${result.written} character${result.written === 1 ? '' : 's'}`
  const lines = [
    `typed ${count}${where} on displayId ${result.displayId} "${result.label}"`,
  ]
  if (result.password === true) {
    lines.push('the field hides what is in it, so it is not read back')
  } else if (typeof result.text === 'string' && result.text !== '') {
    lines.push(`the field now reads ${JSON.stringify(result.text)}`)
  }
  return said(withJson(lines, result), note)
}

/** A field, named the way it says itself and placed where it is */
function fieldName(field) {
  if (!field || !field.className) return 'the field'
  const said = field.text || field.description
  return `${field.className}${said ? ` "${said}"` : ''} at ${formatRect(field.bounds)}`
}

/** What a gesture was delivered as, which is all a caller can know without looking */
function formatGesture(verb, result, note) {
  if (result.tapped === true || result.swiped === true) {
    const lines = result.swiped === true
      ? [`${verb} displayId ${result.displayId} over ${result.durationMs}ms`]
      : [`${verb} displayId ${result.displayId} at ${result.x},${result.y}${holdSaid(result)}`]
    // 熄屏时注入是**静默失效**的: 平台收下了事件, 界面没有任何反应, 所以这句话必须说在明面上
    if (result.screen && result.screen !== 'on') {
      lines.push(`careful: that screen is ${result.screen}, and a sleeping screen does not take`
        + ' injected presses - the gesture was delivered to a display that is not listening.'
        + ' Send WAKEUP with lw_key first if something was supposed to happen.')
    }
    return said(withJson(lines, result), note)
  }
  return said(withJson([`the device did not report the gesture as delivered`], result), note)
}

/** 这张图是哪一块, 以及图上的点怎么换算回屏幕上的点 */
function pictureNote(picture) {
  const left = picture.left ?? 0
  const top = picture.top ?? 0
  const scale = picture.scale ?? 1
  const partial = left !== 0 || top !== 0
  const where = partial ? `the part of the screen at ${left},${top}` : 'the whole screen'
  let how
  if (scale > 1 && partial) {
    how = `multiply a coordinate on the picture by ${scale.toFixed(2)}, then add ${left},${top}`
  } else if (scale > 1) {
    how = `multiply a coordinate on the picture by ${scale.toFixed(2)} to get the screen coordinate`
  } else if (partial) {
    how = `add ${left},${top} to a coordinate on the picture to get the screen coordinate`
  } else {
    how = 'coordinates on it are screen coordinates already'
  }
  return { where, how }
}

/** Where the picture went, and how to read coordinates off it */
function formatScreenshot(result) {
  if (!result.path) {
    // 没有图的原因现在分两种, 而它们要做的下一步不一样: 屏没了 (再建一块) 与屏在而还没画出来
    // (在它上面起个应用再拍) —— 理由由设备那一侧写, 这里原样念
    return `no picture was written: ${result.error || 'no reason reported'}`
  }
  const shots = Array.isArray(result.shots) ? result.shots : []
  if (shots.length > 1) {
    const span = ((result.spanMs ?? 0) / 1000).toFixed(1)
    // 要说的是"量到的间隔", 不是"要的间隔": 一次截图本身要两三百毫秒, 所以比它小的那个数做不到。
    // 判据拿**真实跨度**与**要的跨度**比, 而且要的是"没有比要的更长" —— 反着写永远成立 (设备慢下来
    // 只会让跨度更大, 那正是"做不到"的样子)
    const asked = result.intervalMs ?? 0
    const kept = (result.spanMs ?? 0) <= asked * (shots.length - 1) * 1.1
    const first = pictureNote(shots[0].picture ?? {})
    const lines = [
      `took ${shots.length} pictures of displayId ${result.displayId} "${result.label}", one every`
      + ` ${asked} ms as far as this device keeps up - ${span} s from the first to the last.`
      + ` Each is ${first.where}, and on each one ${first.how}: separate files, nothing`
      + ' overwritten, so read them in order - the screen moved between them, which is the whole'
      + ' point of taking more than one.',
    ]
    if (!kept) {
      lines.push(
        'They are further apart than that in practice, because one capture takes a couple of'
        + ' hundred milliseconds here: the times below are what happened, not what was asked for.',
      )
    }
    shots.forEach((shot, index) => {
      const note = pictureNote(shot.picture ?? {})
      const at = ((result.offsets?.[index] ?? 0) / 1000).toFixed(1)
      // 一张自己那一句只在它与第一张不同时才写: 同一块屏同一个预算, 重复十二遍是纯噪音
      const own = note.how === first.how ? '' : `, on it ${note.how}`
      lines.push(
        `  ${index + 1}. at +${at}s ${shot.path} - ${(shot.bytes / 1024).toFixed(1)} KB${own}`,
      )
    })
    if (result.sheet?.path) {
      const twin = result.sheet.fullPath && result.sheet.fullPath !== result.sheet.path
        ? `; its full-size version is ${result.sheet.fullPath}`
        : ''
      lines.push(
        `All ${shots.length} are also laid out in one picture, ${result.sheet.path}`
        + ` (${result.sheet.columns}x${result.sheet.rows} cells, in the order they were taken,`
        + ` ${(result.sheet.bytes / 1024).toFixed(1)} KB${twin}). Read that for where the screen`
        + ' went; open a numbered file above when you need to read what is written on it.',
      )
    }
    if (result.sheetError) lines.push(`no grid was made: ${result.sheetError}`)
    const twin = shots[0].fullPath
    if (twin) {
      lines.push(
        `Each one also left its full-size twin beside it (the first is ${twin}) - the two belong`
        + ' together, so deleting a picture means deleting both.',
      )
    }
    return withJson(lines, result)
  }
  const kilobytes = (result.bytes / 1024).toFixed(1)
  const picture = result.picture ?? {}
  const note = pictureNote(picture)
  const scaled = (picture.scale ?? 1) > 1
  const size = scaled
    ? `${picture.width}x${picture.height} px, so ${note.how}`
    : `the same size as the screen (${note.where})`
  // 一次截图落两个文件: 全尺寸那份给人看, 缩过的那份给模型。哪一份叫什么由设备那一侧说 (它给
  // fullPath), 这里不猜文件名 —— `.model` 那个后缀猜错过一次。**只要了一块时另外说**: 那时
  // 全尺寸那份是整屏, 不是这张图的"孪生兄弟", 把它说成一对会让人以为删一张就够
  const partial = (picture.left ?? 0) !== 0 || (picture.top ?? 0) !== 0
  const other = result.fullPath && result.fullPath !== result.path ? result.fullPath : ''
  const pair = other
    ? partial
      ? `; the whole screen it was cut from is ${other}, so that file is a different picture`
      : `; its full-size twin is ${other} - the two belong together, so deleting the screenshot`
        + ' means deleting both'
    : ''
  const lines = [
    `displayId ${result.displayId} "${result.label}" captured to ${result.path}`
    + ` (screen ${result.width}x${result.height}, picture ${size}; ${kilobytes} KB, overwritten`
    + ` by the next capture of this screen${pair})`,
  ]
  // 要了网格而只拍了一张 (或只拍了这一张): 理由必须念出来, 否则答案里少了一件它问过的事
  if (result.sheetError) lines.push(`no grid was made: ${result.sheetError}`)
  return withJson(lines, result)
}

/** What a screen says about itself, which is the answer that saves measuring anything */
function formatUi(result) {
  if (result.enabled !== true) {
    return 'the phone has the accessibility service off, so it will not report what is on a'
      + ' screen. Turn it on in LittleWhale\'s settings, under 无障碍. Until then use lw_screenshot'
      + ' and read the picture.'
  }
  if (result.error) {
    return `displayId ${result.displayId}: ${result.error}`
  }
  const nodes = result.nodes ?? []
  // A screen that draws its own picture does not report nothing: it reports one full-screen
  // surface with no text and nothing to press, which reads like a control to a caller that does
  // not look closely. What matters is whether anything here is worth acting on
  const usable = nodes.filter((node) => node.clickable === true
    || node.editable === true
    || node.checkable === true
    || node.scrollable === true
    || (node.text ?? '') !== '')
  if (usable.length === 0) {
    const surfaces = nodes
      .map((node) => `${node.className || 'control'}${node.description ? ` (${node.description})` : ''}`)
      .join(', ')
    return `displayId ${result.displayId} "${result.label}" draws its own picture: the device`
      + ` reports ${nodes.length} control${nodes.length === 1 ? '' : 's'} with no text and nothing`
      + ` to press${surfaces ? ` - ${surfaces}` : ''}. There is no name to press here, so use`
      + ' lw_screenshot and coordinates on this screen.'
  }
  const lines = [
    `displayId ${result.displayId} "${result.label}" ${result.width}x${result.height}, `
    + `${result.package || 'unknown package'}, ${usable.length} readable controls.`
    + ' The rectangles are in the screen\'s own pixels, the same ones lw_tap and lw_swipe use.',
  ]
  const shown = usable.slice(0, MAX_UI_NODES)
  for (const node of shown) lines.push(describeNode(node))
  if (usable.length > shown.length) {
    lines.push(
      `… ${usable.length - shown.length} more controls are not listed, they are off screen or far`
      + ' down the list; scroll the screen to bring what you need into view',
    )
  }
  lines.push('Press one of these by name with lw_tap(text="..."), or by a point from its rectangle.')
  return lines.join('\n')
}

/** One control, in one line: what it is, what to call it, and where it is */
function describeNode(node) {
  const indent = '  '.repeat(Math.min(node.depth ?? 0, 8))
  const parts = [node.className || 'control']
  if (node.text) parts.push(JSON.stringify(node.text))
  if (node.description) parts.push(`desc=${JSON.stringify(node.description)}`)
  if (node.viewId) parts.push(`id=${node.viewId}`)
  const flags = []
  if (node.clickable) flags.push('clickable')
  if (node.scrollable) flags.push('scrollable')
  if (node.editable) flags.push('editable')
  if (node.checkable) flags.push(node.checked ? 'checked' : 'checkable')
  if (flags.length) parts.push(`[${flags.join(' ')}]`)
  // A control that is not clickable is usually a label inside a row that is, so the row's
  // rectangle is what a point would have to land in
  if (node.target && !sameRect(node.target, node.bounds)) {
    parts.push(`at ${formatRect(node.bounds)} press row ${formatRect(node.target)}`)
  } else {
    parts.push(`at ${formatRect(node.bounds)}`)
  }
  return indent + parts.join(' ')
}

/** What a launch turned out to be, with the command's own words kept */
function formatLaunched(result, note) {
  const target = result.package || result.component
  const whose = result.user > 0 ? ` as user ${result.user}` : ''
  const from = result.asked ? ` (from ${JSON.stringify(result.asked)})` : ''
  const lines = result.started === true
    ? [`launched ${target}${from}${whose} on displayId ${result.displayId} "${result.label}"`]
    : [
      `the launch of ${target}${from}${whose} on displayId ${result.displayId} did not report`
      + ` success (am exited ${result.code}); what it said follows`,
    ]
  const output = (result.output ?? '').trim()
  if (output) lines.push(output)
  // 起完的自查: `am start` 报成功不等于窗口落在这块屏上, 而"没有窗口"这件事以前只有模型自己去发现
  // (windowOnDisplay 为 null 表示这次查不了: 无障碍没开, 窗口列表读不到)
  if (result.windowOnDisplay === false || result.windowOnDisplay === null) {
    lines.push(result.windowNote
      || `displayId ${result.displayId} has no window on it after the launch`)
    lines.push('Read the screen back with lw_ui before acting on it.')
  } else {
    lines.push('Call lw_ui to see what the screen says now.')
  }
  return said(lines.join('\n'), note)
}

/** What the launcher's apps are, as one line per app */
function formatApps(result) {
  const where = `user ${result.user}`
  if (result.error) return `could not list the apps for ${where}: ${result.error}`
  const apps = result.apps ?? []
  const wanted = result.query ? ` matching ${JSON.stringify(result.query)}` : ''
  if (apps.length === 0) {
    return result.query
      ? `no app for ${where} matches ${JSON.stringify(result.query)} (of ${result.launchable}` +
        ' that can be started) - try a shorter query, or no query at all to see them'
      : `nothing installed for ${where} has a launcher entry, so there is nothing here to start`
  }
  const lines = [
    `${apps.length} app${apps.length === 1 ? '' : 's'}${wanted} for ${where}` +
      (result.truncated ? ' (the list was cut short, narrow it with query)' : '') + ':',
  ]
  for (const app of apps) lines.push(`  ${app.label} - ${app.package}`)
  lines.push('Start one with lw_launch(package="..."), which takes the package or that name.')
  return lines.join('\n')
}

/** What pressing by name did, which is the one call that can be ambiguous */
function formatNamedTap(result, note) {
  return said(namedTapReport(result), note)
}

/** What reading a screen's pixels found */
function formatOcr(result) {
  const where = `displayId ${result.displayId}`
  if (result.error) return `could not read ${where}: ${result.error}`
  const lines = result.lines ?? []
  const cost = `${result.captureMs ?? 0}ms to capture, ${result.detMs ?? 0}ms to find the text, `
    + `${result.recMs ?? 0}ms per line, on ${result.backend}`
  const asleep = asleepLine(result)
  if (lines.length === 0) {
    return `no text was found on ${where} (${cost}). The screen may be blank or hold only`
      + ' pictures, and lw_screenshot shows it as it is.'
  }
  const report = [
    `${lines.length} line${lines.length === 1 ? '' : 's'} read on ${where} (${cost}). The`
    + ' rectangles are in the screen\'s own pixels, the same ones lw_tap and lw_swipe use: press a'
    + ' line with lw_tap(x=..., y=...) at its centre.',
  ]
  if (asleep) report.unshift(asleep)
  for (const line of lines) {
    const box = line.box ?? []
    report.push(`  [${box.join(',')}] ${JSON.stringify(line.text)}`
      + ` score ${(line.score ?? 0).toFixed(2)} centre ${(line.center ?? []).join(',')}`)
  }
  report.push('A low score means it was probably not text - an icon, a picture or a stray mark.')
  report.push('Press one of these by name with lw_tap(text="...") and no coordinate at all, or by'
    + ' a point from its rectangle; pressing by name lands on a random point inside the rectangle'
    + ' rather than its exact centre.')
  return report.join('\n')
}

/**
 * The warning for a screen that is not awake, or null when it is
 *
 * A sleeping display still hands out the last frame it drew and still swallows injected touches,
 * both without saying so: what comes back reads like a live screen that ignores presses
 */
function asleepLine(result) {
  const state = result.screen
  if (!state || state === 'on') return null
  return `Careful: this screen is ${state}, and a sleeping screen goes on showing its last frame`
    + ' while ignoring injected presses. What is listed below may no longer be on the screen, and'
    + ' none of it can be pressed. Wake it first with lw_key(key="WAKEUP") and read it again.'
}

/**
 * Press a name that exists only as pixels
 *
 * Reading is a guess, so it never guesses twice: an exact match wins, otherwise a line that
 * contains the name, and more than one candidate presses nothing at all
 */
async function tapByReading(displayId, text, hold = 0) {
  const result = await call('ocr', { displayId })
  if (result.error) return `the screen could not be read: ${result.error}`
  if (result.screen && result.screen !== 'on') {
    return `the screen is ${result.screen}, so nothing was pressed: a sleeping screen keeps showing`
      + ' its last frame but does not take a press. Send WAKEUP with lw_key first, then try again.'
  }
  const lines = result.lines ?? []
  const needle = String(text).trim()
  const exact = lines.filter((line) => String(line.text ?? '').trim() === needle)
  const candidates = exact.length > 0
    ? exact
    : lines.filter((line) => String(line.text ?? '').includes(needle))
  if (candidates.length === 0) {
    return lines.length === 0
      ? 'reading the screen found no text at all'
      : `reading the screen found no line saying ${JSON.stringify(text)} among the ${lines.length}`
        + ' lines it could read; call lw_ocr to see them'
  }
  if (candidates.length > 1) {
    return `${candidates.length} lines on the screen say that, so nothing was pressed: `
      + candidates.map((line) => `${JSON.stringify(line.text)} at [${(line.box ?? []).join(',')}]`)
        .join(', ')
      + '. Press the one you meant with lw_tap(x=..., y=...) at its centre.'
  }
  const only = candidates[0]
  const point = scatterInside(only.box ?? [])
  const pressed = await call('tap', { displayId, x: point.x, y: point.y, holdMs: hold })
  return `pressed the line ${JSON.stringify(only.text)} (read off the screen with score`
    + ` ${(only.score ?? 0).toFixed(2)}) at ${point.x},${point.y} on displayId`
    + ` ${pressed.displayId}, a point drawn inside its rectangle [${(only.box ?? []).join(',')}]`
    + ` rather than its exact centre${hold > 0 ? `, holding it for ${formatMs(hold)}` : ''}`
}

/**
 * A point inside a rectangle, drawn at random rather than being the centre
 *
 * Two reasons. The centre of a wide row is often the label inside it rather than the part that
 * takes the press, while anywhere in the row works. And a tool that lands on the same coordinate
 * every single time is a pattern: a game that watches its own input can tell it apart from a
 * person, and a person never taps the same pixel twice
 *
 * The draw is confined to the middle of the rectangle - a fifth of the way in from each edge - so
 * it stays clear of the border and of whatever sits next to the row
 */
function scatterInside(box) {
  const [left, top, right, bottom] = box
  const insetX = (right - left) * SCATTER_INSET
  const insetY = (bottom - top) * SCATTER_INSET
  const x = Math.round(left + insetX + Math.random() * Math.max(0, right - left - 2 * insetX))
  const y = Math.round(top + insetY + Math.random() * Math.max(0, bottom - top - 2 * insetY))
  return { x, y }
}

/** The report itself, before the caller's own note is put on it */
function namedTapReport(result) {
  const matches = result.matches ?? []
  // clicked 而不是 outcome: 无障碍动作被拒之后那一根按住的手指算数, 见 lw_tap 里的注释
  if (result.clicked === true) {
    const hit = matches[0] ?? {}
    const how = result.via === 'finger'
      ? 'a touch at its centre, because it does not take an accessibility action'
      : result.long === true ? 'its own long-click action' : 'its own click action'
    return `pressed ${nameOf(hit)} on displayId ${result.displayId} by ${how}${holdSaid(result)}`
  }
  if (result.outcome === 'ambiguous') {
    return [
      `${matches.length} controls on displayId ${result.displayId} say that, so nothing was pressed:`
      + ' this tool does not guess which one you meant. Pick one from this list and press it by a'
      + ' point inside its rectangle with lw_tap(x=..., y=...), or call lw_ui and name a control'
      + ' that only appears once.',
      ...matches.map((node) => `- ${nameOf(node)} at ${formatRect(node.bounds)}`
        + (node.target && !sameRect(node.target, node.bounds)
          ? `, the row that takes the press is ${formatRect(node.target)}`
          : '')),
    ].join('\n')
  }
  if (result.outcome === 'none') {
    return `nothing matched: ${result.error || 'no reason reported'}. Call lw_ui to see what the`
      + ' screen actually says.'
  }
  if (result.outcome === 'unavailable') {
    return result.error || 'the accessibility service is off, so nothing can be pressed by name'
  }
  return `the press did not go through: ${result.error || result.outcome}`
}

/** How a control is named back to a caller, preferring the text it was found by */
function nameOf(node) {
  const label = node.text || node.description
  const id = node.viewId ? ` id=${node.viewId}` : ''
  return `${node.className || 'control'}${id} ${label ? JSON.stringify(label) : '(no text)'}`
}

/** A rectangle as it is easiest to read, which is also how a point is picked out of it */
function formatRect(rect) {
  if (!Array.isArray(rect) || rect.length < 4) return '[?]'
  return `[${rect[0]},${rect[1]},${rect[2]},${rect[3]}]`
}

function sameRect(a, b) {
  return Array.isArray(a) && Array.isArray(b) && a.every((value, index) => value === b[index])
}

/** A report a model can read, with the structured answer kept underneath it */
function withJson(lines, result) {
  return [...lines, '', JSON.stringify(result, null, 2)].join('\n')
}

/* ------------------------------------------------------------------ on-device speech */

/**
 * dsh's voice input records in the page (getUserMedia + MediaRecorder) and hands the host a
 * canonical 16 kHz mono PCM16 WAV to transcribe. The official local provider wants
 * sherpa-onnx-node, and that package ships native addons for darwin / linux / win only - there is
 * no android-arm64 build to load - so this host registers a provider that hands the audio back to
 * the app: the APK links sherpa-onnx statically and answers the `speech` channel method.
 *
 * The model is not in the APK (SenseVoice int8 is around 240 MB) and is not in this repository:
 * it is downloaded into the app's private directory on demand. huggingface.co cannot be reached
 * from this phone, so the hf-mirror copy is tried first. Both files are checked against the sha256
 * the official voice-input bundle publishes, so a mirror cannot quietly hand over something else.
 */
const SPEECH_PROVIDER_ID = 'lw-native'

const SPEECH_LANGUAGES = ['auto', 'zh', 'en', 'yue', 'ja', 'ko']

const SPEECH_SOURCES = [
  'https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main',
  'https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main',
]

const SPEECH_FILES = [
  {
    name: 'model.int8.onnx',
    bytes: 239233841,
    sha256: 'c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51',
  },
  {
    name: 'tokens.txt',
    bytes: 315894,
    sha256: 'f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc',
  },
]

/**
 * The silero voice-activity model the always-listening chain cuts segments with
 *
 * Same file and same sha256 as the one dsh's own `speech-to-text-sensevoice` package pins, so the
 * segmentation behaves the way that package's does. It is 1.8 MB - small enough that the app hashes
 * it on every start instead of trusting the download - and it is *not* part of the voice input
 * button's needs, so it is tracked separately from `present`: a phone that already downloaded the
 * 240 MB recogniser must not be told to download it again just because the VAD arrived later
 */
const SPEECH_VAD = {
  name: 'silero_vad.onnx',
  bytes: 1807522,
  sha256: 'a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28',
}

/** Same repo layout as the recogniser: the hf-mirror copy first, huggingface.co as the fallback */
const SPEECH_VAD_SOURCES = [
  'https://hf-mirror.com/csukuangfj/vad/resolve/main',
  'https://huggingface.co/csukuangfj/vad/resolve/main',
]

/** How far the model is, as the page's voice input reads it */
const speechState = { phase: 'checking', detail: 'looking for the model' }
const speechListeners = new Set()

function speechAnnounce(phase, detail) {
  speechState.phase = phase
  speechState.detail = detail
  for (const listener of speechListeners) {
    try {
      listener()
    } catch {
      // one observer throwing must not stop the others
    }
  }
}

async function speechSize(path) {
  try {
    return (await stat(path)).size
  } catch {
    return -1
  }
}

/** What the engine and the model say right now, without downloading anything */
async function speechInspect() {
  const info = await call('speech', { op: 'status' })
  const sizes = await Promise.all(
    SPEECH_FILES.map((file) => speechSize(join(info.directory, file.name))),
  )
  const present = SPEECH_FILES.every((file, index) => sizes[index] === file.bytes)
  const vad = await speechSize(info.vadPath) === SPEECH_VAD.bytes
  speechAnnounce(
    present && vad ? 'ready' : 'unprepared',
    present && vad
      ? `sherpa-onnx ${info.sherpa}`
      : present ? 'the silero VAD is not downloaded yet' : 'the model is not downloaded yet',
  )
  return { ...info, present, vad }
}

/** Fetch the model once, from whichever mirror answers */
async function speechPrepare() {
  const info = await speechInspect()
  await mkdir(info.directory, { recursive: true })
  if (!info.present) {
    for (const file of SPEECH_FILES) {
      const target = join(info.directory, file.name)
      if (await speechSize(target) === file.bytes) continue
      await speechDownload(file, target)
    }
  }
  if (!info.vad) {
    await mkdir(dirname(info.vadPath), { recursive: true })
    await speechDownload(SPEECH_VAD, info.vadPath, SPEECH_VAD_SOURCES)
  }
  speechAnnounce('ready', `sherpa-onnx ${info.sherpa}`)
  return { ...info, present: true, vad: true }
}

async function speechDownload(file, target, sources = SPEECH_SOURCES) {
  let failure = null
  for (const base of sources) {
    const partial = `${target}.part`
    try {
      speechAnnounce('downloading', `${file.name} from ${new URL(base).host}`)
      const response = await fetch(`${base}/${file.name}`)
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const digest = createHash('sha256')
      let received = 0
      const meter = new Transform({
        transform(chunk, _encoding, callback) {
          digest.update(chunk)
          received += chunk.length
          speechState.bytes = received
          speechState.total = file.bytes
          callback(null, chunk)
        },
      })
      await pipeline(Readable.fromWeb(response.body), meter, createWriteStream(partial))
      const actual = digest.digest('hex')
      if (actual !== file.sha256) throw new Error(`sha256 ${actual} is not ${file.sha256}`)
      await rename(partial, target)
      return
    } catch (error) {
      failure = error
      await rm(partial, { force: true })
    }
  }
  speechAnnounce('failed', String(failure?.message ?? failure))
  throw new Error(`could not download ${file.name}: ${failure?.message ?? failure}`)
}

/** One recording through the app's own engine; a missing model is an error, not a 240 MB surprise */
async function speechTranscribe(wav, language) {
  const info = await speechInspect()
  if (!info.present) {
    throw new Error('the speech model is not downloaded yet: run lw_speech op=prepare once')
  }
  return await call('speech', { op: 'transcribe', wav, language: language ?? 'auto' })
}

/* ── 唤醒词 ─────────────────────────────────────────────────────────────────
 * 引擎在 app 那一侧 (同一个 sherpa-onnx, 换成了 KeywordSpotter), 这一侧只做两件事: 把模型取到
 * 手机上、把词表写成 sherpa-onnx 认的那一行。
 *
 * 词表为什么不能写中文原文: keywords 文件每一行是**模型的 token 序列**加 `@显示名`, 而
 * EncodeKeywords 只认模型 tokens.txt 里的符号 —— 中文原文一个都不在表里, 它不报错, 只是把那
 * 一行静默丢掉, 结果是"照做了但永远不触发"。所以这里把拼音拆成声母 + 带调韵母, 逐个对一遍
 * 符号表, 对不上就当面报错
 */

/** 按文件取的那一份: ModelScope 上同名的中文 zipformer KWS (3.3M 参数) */
const WAKEWORD_SOURCES = [
  'https://modelscope.cn/api/v1/models/pkufool/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01/repo?Revision=master&FilePath=',
]

/** 字节数与 sha256 都是本机实测过的 (2026-10-04): 换包一定会被发现 */
const WAKEWORD_FILES = [
  {
    name: 'encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
    bytes: 4807159,
    sha256: '017af32f2c0138f931d05fbc009ee864295e910aff304f77d2f563815fc834fb',
  },
  {
    name: 'decoder-epoch-12-avg-2-chunk-16-left-64.onnx',
    bytes: 675349,
    sha256: 'bb3d8640cc6a495088707173bc1707a8a4ffe014594fc9adcba8389e27d0339f',
  },
  {
    name: 'joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
    bytes: 65208,
    sha256: '431de10b554f134ef8af320feea2db337e641290449a3d3f6cb6e5f5fd2c9c3d',
  },
  {
    name: 'tokens.txt',
    bytes: 1627,
    sha256: '72316508d9119696145abc6f1f8cdc46287535c34e5ce7e595f845cb1499cf2e',
  },
]

/** 开门那一句; 改词表就是改这一行 (见 op=keywords) */
const WAKEWORD_DEFAULT_WORDS = ['素云=su4 yun2']

/** 声母: 长在前, 免得 zh 被拆成 z + h */
const PINYIN_INITIALS = [
  'zh', 'ch', 'sh', 'b', 'p', 'm', 'f', 'd', 't', 'n', 'l',
  'g', 'k', 'h', 'j', 'q', 'x', 'r', 'z', 'c', 's', 'y', 'w',
]

/** 声调往哪个元音上标 */
const TONE_MARKS = {
  a: 'āáǎà',
  o: 'ōóǒò',
  e: 'ēéěè',
  i: 'īíǐì',
  u: 'ūúǔù',
  'ü': 'ǖǘǚǜ',
}

/** su4 -> sù; 已经带调号的 (sù) 原样回来; 轻声 (第 5 声) 不带调号 */
function markedSyllable(raw) {
  let syllable = raw.trim().toLowerCase().replace(/u:/g, 'ü').replace(/v/g, 'ü')
  const tone = syllable.match(/([1-5])$/)
  if (!tone) return syllable
  syllable = syllable.slice(0, -1)
  if (tone[1] === '5') return syllable
  const at = toneIndex(syllable)
  const marks = at >= 0 ? TONE_MARKS[syllable[at]] : null
  if (!marks) return syllable
  return syllable.slice(0, at) + marks[Number(tone[1]) - 1] + syllable.slice(at + 1)
}

/** 一个音节里调号落在哪个字符上: a / o / e 优先, iu 落在 u, ui 落在 i, 其余落在最后一个元音 */
function toneIndex(syllable) {
  for (const vowel of ['a', 'o', 'e']) {
    const at = syllable.indexOf(vowel)
    if (at >= 0) return at
  }
  if (syllable.endsWith('iu') || syllable.endsWith('ui')) return syllable.length - 1
  for (let at = syllable.length - 1; at >= 0; at -= 1) {
    if ('iuü'.includes(syllable[at])) return at
  }
  return -1
}

/** 模型认得的那张符号表: tokens.txt 每行第一个字段 */
async function wakeWordSymbols(directory) {
  const text = await readFile(join(directory, 'tokens.txt'), 'utf8')
  const symbols = new Set()
  for (const line of text.split('\n')) {
    const token = line.split(' ')[0].trim()
    if (token) symbols.add(token)
  }
  return symbols
}

/** 一个词 + 它的拼音 -> sherpa-onnx 要的那一行; 有符号表里没有的 token 就直接报错 */
function wakeWordLine(word, pinyin, symbols) {
  const tokens = []
  for (const raw of String(pinyin).trim().split(/\s+/)) {
    const syllable = markedSyllable(raw)
    if (!syllable) throw new Error(`"${pinyin}" has an empty syllable`)
    const initial = PINYIN_INITIALS.find(
      (one) => syllable.startsWith(one) && syllable.length > one.length,
    )
    const parts = initial ? [initial, syllable.slice(initial.length)] : [syllable]
    for (const token of parts) {
      if (!symbols.has(token)) {
        throw new Error(
          `the model's tokens.txt has no "${token}" (from ${raw} of "${word}"), so sherpa-onnx`
          + ' would drop the whole line without saying so',
        )
      }
    }
    tokens.push(...parts)
  }
  return `${tokens.join(' ')} @${word}`
}

/** "素云=su4 yun2" -> 一行; 没写等号就当成拼音与词同名的一对, 报错让人补上 */
async function wakeWordLinesFor(args, directory) {
  const raw = args?.lines?.length ? args.lines : null
  if (raw) return raw.map((line) => String(line).trim()).filter(Boolean)
  const pairs = args?.words?.length ? args.words : null
  if (!pairs) throw new Error('op=keywords names the words: words=["素云=su4 yun2", ...]')
  const folder = directory ?? (await call('wakeword', { op: 'status' })).directory
  const symbols = await wakeWordSymbols(folder)
  return pairs.map((pair) => {
    const at = String(pair).indexOf('=')
    if (at <= 0) throw new Error(`"${pair}" has to be 词=拼音, for example 素云=su4 yun2`)
    return wakeWordLine(String(pair).slice(0, at).trim(), String(pair).slice(at + 1), symbols)
  })
}

/** 现在什么样: 引擎报的 + 磁盘上那几个文件的大小对不对 */
async function wakeWordInspect() {
  const info = await call('wakeword', { op: 'status' })
  const sizes = await Promise.all(
    WAKEWORD_FILES.map((file) => speechSize(join(info.directory, file.name))),
  )
  const present = WAKEWORD_FILES.every((file, index) => sizes[index] === file.bytes)
  return { ...info, present }
}

/** 取一次模型, 顺便把缺省词表写上; 已经有了就不动它 */
async function wakeWordPrepare(words) {
  const before = await wakeWordInspect()
  await mkdir(before.directory, { recursive: true })
  for (const file of WAKEWORD_FILES) {
    const target = join(before.directory, file.name)
    if (await speechSize(target) === file.bytes) continue
    await wakeWordDownload(file, target)
  }
  if (words?.length) {
    const lines = await wakeWordLinesFor({ words }, before.directory)
    await call('wakeword', { op: 'keywords', lines })
  } else if (!before.keywords) {
    const lines = await wakeWordLinesFor({ words: WAKEWORD_DEFAULT_WORDS }, before.directory)
    await call('wakeword', { op: 'keywords', lines })
  }
  return await wakeWordInspect()
}

/** 与 speech 那一套同一个写法: 换镜像、对 sha256、失败不留半条文件 */
async function wakeWordDownload(file, target) {
  let failure = null
  for (const base of WAKEWORD_SOURCES) {
    const partial = `${target}.part`
    try {
      const response = await fetch(`${base}${encodeURIComponent(file.name)}`)
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const digest = createHash('sha256')
      const meter = new Transform({
        transform(chunk, _encoding, callback) {
          digest.update(chunk)
          callback(null, chunk)
        },
      })
      await pipeline(Readable.fromWeb(response.body), meter, createWriteStream(partial))
      const actual = digest.digest('hex')
      if (actual !== file.sha256) throw new Error(`sha256 ${actual} is not ${file.sha256}`)
      await rename(partial, target)
      return
    } catch (error) {
      failure = error
      await rm(partial, { force: true })
    }
  }
  throw new Error(`could not download ${file.name}: ${failure?.message ?? failure}`)
}

/* ------------------------------------------------------------------ reading aloud */

/**
 * What of a reply is worth saying out loud
 *
 * A reply is markdown written for the eye. Read aloud, the parts that only make sense on a screen
 * turn into noise: "```" and "|" and "](https://...)" are not words. So this strips exactly those
 * and returns whatever prose is left, and the answer says so when nothing is left - a reply that is
 * all code reads as an empty line, and "nothing worth reading" is a truer thing to say than silence
 *
 * The rules live here and only here (a decision D15 pinned): fenced code goes, inline code keeps its
 * contents, images go, links keep their text, bare URLs go, table rows go, heading/list/quote
 * markers go, emphasis markers go, rules go, then whitespace collapses
 */
function readAloudText(markdown) {
  let text = String(markdown ?? '')
  text = text.replace(/```[\s\S]*?```/g, ' ')
  text = text.replace(/`([^`]*)`/g, '$1')
  text = text.replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
  text = text.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
  text = text.replace(/https?:\/\/\S+/g, ' ')
  text = text.replace(/^[^\n]*\|[^\n]*$/gm, '')
  text = text.replace(/^\s{0,3}(#{1,6}|>|[-*+]|\d+[.)])\s+/gm, '')
  text = text.replace(/(\*\*|__)([\s\S]*?)\1/g, '$2')
  text = text.replace(/(?<![A-Za-z0-9])(\*|_)(?=\S)([^*_\n]*?)(?<=\S)\1(?![A-Za-z0-9])/g, '$2')
  text = text.replace(/^\s*([-*_]\s*){3,}$/gm, ' ')
  text = text.replace(/^[ \t]+$/gm, '')
  return text.replace(/[ \t]+/g, ' ').replace(/\n{2,}/g, '\n').replace(/^ | $/gm, '').trim()
}

/* ── 语音投递 ───────────────────────────────────────────────────────────────
 * 应用那一侧把认出来的话追加进 `$DSH_HOME/voice/inbox.jsonl` (一行一句, 带自增 `seq`), 这一侧
 * 读出来送进会话,
 *
 * 为什么是文件而不是让应用连过来: 现有的回环桥是"宿主问、应用答", 反过来的话要么让宿主开一个
 * 监听口 (新的攻击面, 应用还得知道那个口在哪), 要么让应用去猜 dsh 的内部 HTTP 接口,文件这条路
 * 三个好处一次拿到 —— 宿主重启不丢、应用先写宿主后起也投得出去、`$DSH_HOME` 是真文件系统所以
 * 轮询的开销可以忽略,
 *
 * 去重靠 `seq` 不靠字节游标: 文件满了会从尾部留若干行重写, 那一刻游标会指到新文件之外, 只靠游标
 * 就会重放,游标记的是**已投递的最大 seq**, 所以重写与截断都不会让一句话被说两遍,
 */

/** 投递队列的位置, 与应用那一侧同一个约定 */
function voiceInboxPath() {
  const home = process.env.DSH_HOME
  return home ? join(home, 'voice', 'inbox.jsonl') : null
}

/** 轮询那个队列的间隔: 半秒对说话这件事足够快, 而它只是一次 stat */
const VOICE_POLL_MS = 500

function voiceCursorPath(inbox) {
  return join(dirname(inbox), 'inbox.cursor')
}

/** 已投递到哪 (最大 seq) 与最近一次读到的文件指纹 */
const voiceCursor = { path: null, seq: null, fingerprint: null }

/** 尾部那行的 seq, 认不出来就是 0 */
function voiceLastSeq(raw) {
  const lines = String(raw).split('\n')
  for (let at = lines.length - 1; at >= 0; at -= 1) {
    const line = lines[at].trim()
    if (!line) continue
    try {
      const seq = Number(JSON.parse(line)?.seq)
      if (Number.isFinite(seq)) return seq
    } catch {
      // 末尾可能留着半行 (进程被杀), 那一行就是没有序号, 继续往回找
    }
  }
  return 0
}

/**
 * 读到哪了
 *
 * 游标文件不在时**从当前的尾部开始**, 不把装之前说的那些话一次性倒进会话 —— 一个刚装上的功能
 * 不该拿一堆积压的句子打断主人
 */
async function voiceCursorLoad(inbox, raw) {
  if (voiceCursor.path !== inbox || voiceCursor.seq === null) {
    voiceCursor.path = inbox
    const stored = await readFile(voiceCursorPath(inbox), 'utf8').catch(() => null)
    const parsed = stored === null ? Number.NaN : Number.parseInt(stored.trim(), 10)
    voiceCursor.seq = Number.isFinite(parsed) ? parsed : voiceLastSeq(raw)
  }
  return voiceCursor.seq
}

/** 投递成功之后才前移: 投失败的那一句留在下游, 下一次接着投, 不静默丢掉 */
async function voiceCursorStore(inbox, seq) {
  voiceCursor.seq = seq
  await mkdir(dirname(inbox), { recursive: true })
  await writeFile(voiceCursorPath(inbox), `${seq}\n`)
}

/**
 * 文件没变就别解析: 500 ms 一次的轮询里绝大多数时候什么都没发生
 *
 * 但**"上次读出来却还没确认投出去"时不许跳过** —— 那正是投递失败之后要重试的那一刻, 而文件当然
 * 没变。少了 `unread` 这一个条件, 一句投失败的话就会一直躺在文件里, 直到主人再说一句才被顺带带
 * 出去 (实测出来的: tools/check-voice-inbox.mjs 第 3 条判据)
 */
async function voiceUnchanged(inbox) {
  if (voiceCursor.unread) return false
  const info = await stat(inbox).catch(() => null)
  if (info === null) return true
  const fingerprint = `${info.size}:${info.mtimeMs}`
  if (voiceCursor.fingerprint === fingerprint) return true
  voiceCursor.fingerprint = fingerprint
  return false
}

/** 还没投递过的那几行, 按说的顺序 */
async function voiceReadNew(inbox) {
  if (await voiceUnchanged(inbox)) return []
  const raw = await readFile(inbox, 'utf8').catch(() => null)
  if (raw === null) return []
  const since = await voiceCursorLoad(inbox, raw)
  const fresh = []
  for (const line of raw.split('\n')) {
    const text = line.trim()
    if (!text) continue
    let record = null
    try {
      record = JSON.parse(text)
    } catch {
      continue
    }
    const seq = Number(record?.seq)
    const said = typeof record?.text === 'string' ? record.text.trim() : ''
    if (!Number.isFinite(seq) || seq <= since || !said) continue
    fresh.push({ seq, text: said, source: record.source ?? 'voice', at: Number(record.at) || 0 })
  }
  // 留一条"还没确认投出去"的记号, 见 voiceUnchanged: 它让失败的那句话下一次还被读出来
  voiceCursor.unread = fresh.length > 0
  return fresh
}

/**
 * 一直看着那个队列, 有新句子就送进会话
 *
 * 一句一句地送, 送成功才前移游标: 顺序就是主人说话的顺序, 而中途失败不会让后面的话插到前面去
 */
function startVoiceInbox(ctx, deliver) {
  const inbox = voiceInboxPath()
  if (!inbox) {
    warn(ctx, 'the voice inbox has no place to live: DSH_HOME is not set')
    return
  }
  let running = false
  const tick = async () => {
    if (running) return
    running = true
    try {
      for (const line of await voiceReadNew(inbox)) {
        console.log(`littlewhale-channel: voice line #${line.seq} picked up from the queue`)
        await deliver(line)
        await voiceCursorStore(inbox, line.seq)
      }
    } catch (error) {
      warn(ctx, `the voice inbox could not be read: ${error?.message ?? error}`)
    } finally {
      running = false
    }
  }
  // 定时器注册失败也必须把第一次读跑了: 一条"没起来"的警告比一个不吭声的死功能好得多, 而且
  // 第一次读本身就能把"路径对不对、游标读到哪"这两件事说清楚
  try {
    ctx.effect(
      () => {
        const timer = setInterval(() => void tick(), VOICE_POLL_MS)
        return () => clearInterval(timer)
      },
      'littlewhale-channel: voice inbox',
    )
  } catch (error) {
    warn(ctx, `the voice inbox timer could not be registered: ${error?.message ?? error}`)
  }
  void tick()
}

/** 队列现在什么样, 给 lw_voice 用 */
async function voiceInboxState() {
  const inbox = voiceInboxPath()
  if (!inbox) return { inbox: null, queued: [], cursor: null }
  const raw = await readFile(inbox, 'utf8').catch(() => null)
  const queued = []
  if (raw !== null) {
    for (const line of raw.split('\n')) {
      const text = line.trim()
      if (!text) continue
      try {
        const record = JSON.parse(text)
        if (typeof record?.text === 'string' && record.text.trim()) {
          queued.push({ seq: Number(record.seq), at: Number(record.at) || 0, text: record.text.trim() })
        }
      } catch {
        // 半行, 不当它是队列里的一句
      }
    }
  }
  // 游标还没建立时报的是**读者这次会从哪开始** (即文件尾部), 不是"什么都没有": 第一次跑本来
  // 就不会把历史倒进会话, 而"什么都不会投"与"都会投"这两句话在故障排查时是反的
  const cursor = voiceCursor.path === inbox ? voiceCursor.seq : voiceLastSeq(raw ?? '')
  return { inbox, queued, cursor }
}

/* ── 语音投递: 一句话怎么变成会话里的一条消息 ─────────────────────────────── */

/** 投递的去向与结果, 给 lw_voice 看: 投了几条、投给谁、是插进去的还是排上的、最近一次为什么失败 */
const voiceDelivery = { lines: 0, sessionId: null, steered: 0, queued: 0, last: null, error: null }

/** `@deepseek-ai/dsh-llm` 的模块命名空间, 按需加载一次 */
let messageFactory = null

/**
 * 造一条用户消息
 *
 * 为什么按需 import 而不是写在文件头上: 这个插件的**模块级依赖只有一个** (工具 schema 在模块求值
 * 时就编译完了), 而多一个静态 import 就多一个"整包加载失败"的理由 —— `tools/check-host-plugin.mjs`
 * 那种只准备了 `dsh-tools` 的环境会在 import 那一步就死, 53 个工具跟着一起没有,按需加载把"投递
 * 这条路缺东西"与"所有工具都没有"分开: 拿不到工厂时投递如实报错, 工具照旧
 *
 * `source` 那个写法是这批里最容易写错的一处, 理由见 [voiceDeliver]
 */
async function createVoiceMessage(text) {
  if (messageFactory === null) messageFactory = await import('@deepseek-ai/dsh-llm')
  return messageFactory.createUserMessage({
    content: [{ type: 'text', text }],
    source: { kind: 'user', via: 'voice' },
  })
}

/**
 * 目标会话: 正在跑的那一轮优先, 否则最近动过的那个**根**会话
 *
 * 两个判据分工不同: `ctx.agents.list()` 是活着的 agent, 其中 `status === 'running'` 的那个就是
 * 主人此刻正在进行的这一轮 —— 话说给它是"插进去"而不是"排到下一轮",没有正在跑的, 才去看会话
 * 列表里最近动过的那个根会话 (子代理与 fork 出来的不算: 那不是主人在看的那个)
 */
async function voiceTargetSession(ctx, controller, signal) {
  const busy = ctx.agents
    .list()
    .find((agent) => agent.status === 'running' && agent.meta?.origin !== 'subagent')
  if (busy !== undefined) return { sessionId: busy.sessionId, running: true }
  const listed = await controller.list({}, signal)
  const roots = listed.items.filter((item) => item.origin !== 'subagent' && !item.parentSessionId)
  if (roots.length === 0) return null
  const newest = roots.reduce((newest, item) => (item.updatedAt > newest.updatedAt ? item : newest))
  return { sessionId: newest.sessionId, running: newest.running }
}

/**
 * 把一句话送进会话
 *
 * D6/D7 (主人 2026-10-05 定): **有正在跑的轮就 `steer` 插进去; 没有会话就新建一个再发**,
 * 会话不活着也能投: `resolveAgent` 会把它恢复起来 (与 dsh 自己的 schedule 那条路同一个做法), 所以
 * "应用在后台说了一句话"不会因为界面没开着而丢掉
 *
 * **来源标记怎么写是这批里最容易写错的一处** (批次 2.2)。两种写法都"有来源", 但只有一个是对的:
 *
 * - `{ kind: 'user', via: 'voice' }` —— 对了,`MessageSourceMap` 是合并扩展的, 各生产者声明自己
 *   的键, 而 `user-rpc` 就是这么干的 (`{ kind: 'user', rpcId … }`, GUI 自己发的每一条都是它)
 * - `{ kind: 'voice' }` —— 错了,会话界面按 `source.kind !== 'user'` 分流: 认不出的一律画成
 *   **注入的上下文行**, 而不是主人自己的那个气泡; 会话标题、活动、steering 历史那一整套也都以
 *   `kind === 'user'` 为准,所以新造一个 kind 等于把"主人说的话"降级成"系统塞进来的东西"
 *
 * 也就是说: kind 必须留 `user` (它决定这条消息是不是"主人说的"), `via: 'voice'` 才是那个标记
 *
 * **flush 之后才算投出去**: 游标只在投递成功之后前移, 中途崩了下次会重投, 而不是静默丢掉
 */
async function voiceDeliver(ctx, line) {
  const controller = ctx.get('sessionController')
  if (!controller) {
    throw new Error('this profile has no session controller, so a spoken line has nowhere to go')
  }
  const signal = new AbortController().signal
  const target = await voiceTargetSession(ctx, controller, signal)
  const sessionId = target === null ? (await controller.create({})).sessionId : target.sessionId
  const outcome = await ctx.agents.withoutInitiator(async () => {
    const resolved = await controller.resolveAgent(sessionId)
    if ('error' in resolved) throw resolved.error
    const { agent } = resolved
    const running = agent.status === 'running'
    const message = await createVoiceMessage(line.text)
    // 正在跑就插进当前轮 (D6), 否则排上并唤醒它
    if (running) agent.steer(message)
    else agent.followup(message)
    const flushed = await ctx.sessions.flush(agent.session)
    return { running, flushed }
  })
  voiceDelivery.lines += 1
  voiceDelivery.sessionId = String(sessionId)
  if (outcome.running) voiceDelivery.steered += 1
  else voiceDelivery.queued += 1
  voiceDelivery.last = { at: Date.now(), text: line.text, sessionId: String(sessionId), steered: outcome.running }
  voiceDelivery.error = null
  ctx.logger?.info?.(
    `voice line #${line.seq} ${outcome.running ? 'steered into' : 'queued on'} ${String(sessionId)}`,
  )
  return outcome
}

/* ── 回答念出来 ───────────────────────────────────────────────────────────── */

/** 最近一次朗读的去向, 给 lw_voice 看 */
const voiceReading = { count: 0, skipped: 0, last: null, error: null }

/**
 * 一轮说完才念 (批次 2.3)
 *
 * 触发点是 `session/event` 里的 `turn/end`, 而且**只有 `reason.kind === 'completed'` 才念**: 一个
 * 轮次里每一步都有一条 `assistant/message` (中间还夹着工具调用), 而只有"这一轮正常结束"才说明
 * 模型不再欠回复 —— 被打断的、出错的、撞上 max-tokens 的那一轮念出来是半句话, 那不叫念回答,
 * 所以不按消息念, 也不在 `agent/turn-stopping` 里念 (那是 serial 钩子, 模型会等我们念完)
 *
 * 读的是这一轮最后那条助手消息的正文, 先过 [readAloudText] 洗一遍 (批次 2.4), 洗空了就什么都不念
 *
 * 半双工在应用那一侧 (批次 2.5): `LwSpeak` 出声时把麦克风那条链关上, 所以念出来的字不会被录回去,
 * 顺带也不会把唤醒词自己叫醒
 *
 * 任何一轮结束都会念, 包括主人在界面里打字问的那些 —— "只在语音问的时候才念"是一个开关的事,
 * 那属于浮标与设置那一批 (D14)
 */
function startReadAloud(ctx) {
  // sessionId -> 这一轮最后那条助手正文,一轮里会有好几条, 后一条顶掉前一条
  const pending = new Map()
  ctx.on('session/event', (session, event) => {
    if (session.meta?.origin === 'subagent') return
    if (event.type === 'assistant/message') {
      // 被打断的那条不作数: 它后面还可能跟着真正的回答
      if (event.data.interrupted === true) {
        pending.delete(session.id)
        return
      }
      const content = Array.isArray(event.data.message?.content) ? event.data.message.content : []
      pending.set(
        session.id,
        content
          .filter((block) => block.type === 'text' && typeof block.text === 'string')
          .map((block) => block.text)
          .join('\n'),
      )
      return
    }
    if (event.type !== 'turn/end') return
    const text = pending.get(session.id)
    pending.delete(session.id)
    if (text === undefined || event.data.reason?.kind !== 'completed') return
    const spoken = readAloudText(text)
    if (!spoken) return
    // 设置页那个「自动朗读回答」关掉之后就不念 —— 开关在应用那一侧 (它与音色/语速放在一起),
    // 所以这里先问一句。**读不到设定时照念**: 桥出错不该让这个功能静默消失, 只记一行
    void call('speak', { op: 'status' })
      .then((state) => {
        if (state?.readAloud === false) {
          voiceReading.skipped += 1
          voiceReading.error = null
          return null
        }
        voiceReading.count += 1
        voiceReading.last = { at: Date.now(), characters: spoken.length, text: spoken }
        voiceReading.error = null
        // 不 await: 这是 emit 钩子, 念多久都不该把会话的事挡在后面
        return call('speak', { op: 'speak', text: spoken })
      })
      .then((answer) => {
        if (!answer || answer.spoken) return
        voiceReading.error = `the engine did not report finishing: ${answer.detail}`
        ctx.logger?.warn?.(voiceReading.error)
      })
      .catch((error) => {
        voiceReading.error = `speaking failed: ${error?.message ?? error}`
        ctx.logger?.warn?.(voiceReading.error)
      })
  })
}

/** Register the provider the page's voice input button resolves to */
function registerVoiceInput(ctx) {
  const speech = ctx.speechToText
  if (!speech || typeof speech.register !== 'function') return
  speech.register({
    info: {
      id: SPEECH_PROVIDER_ID,
      name: 'On-device SenseVoice (sherpa-onnx)',
      location: 'host-local',
      languages: SPEECH_LANGUAGES,
      downloadSources: SPEECH_SOURCES,
      setupEstimate: {
        recommendedDiskBytes: 260 * 1024 * 1024,
        expectedMemoryBytes: 700 * 1024 * 1024,
        minimumMinutes: 1,
        maximumMinutes: 30,
      },
    },
    preparation: {
      snapshot: () => ({ ...speechState }),
      prepare: async () => {
        await speechPrepare()
      },
      cancel: async () => {
        speechAnnounce('ready', 'cancelled')
      },
      subscribe: (listener) => {
        speechListeners.add(listener)
        return () => speechListeners.delete(listener)
      },
    },
    async transcribe({ audio, language }) {
      const info = await speechInspect()
      if (!info.present) {
        throw new Error('the speech model is not downloaded yet: run lw_speech op=prepare once')
      }
      const wav = join(info.directory, `recording-${randomUUID()}.wav`)
      await writeFile(wav, Buffer.from(audio))
      try {
        const answer = await speechTranscribe(wav, language)
        return { text: answer.text }
      } finally {
        await rm(wav, { force: true })
      }
    },
  })
  // Look once at load so the page knows whether this provider is usable, without downloading
  void speechInspect().catch((error) => speechAnnounce('failed', String(error?.message ?? error)))
}
