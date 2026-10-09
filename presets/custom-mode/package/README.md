# dsh-custom-mode

English | [中文](README.zh.md)

**DeepSeek Harness (dsh) custom mode plugin** — edit a mode's system prompt, base mode and plugin switches in
the settings page, and keep several assistants side by side (multi-mode / multi-persona, coding, chat or
role-play). · **中文**：DeepSeek Harness（dsh）自定义模式插件 —— 在设置页编辑系统提示词、选择基础模式、逐行
开关插件，可并存多个助手（多模式 / 多角色扮演 RP）。

![dsh-custom-mode — a settings page for dsh agent modes](https://raw.githubusercontent.com/BOWLUNA/dsh-custom-mode/main/docs/images/header.png)

[![Star this repo](https://img.shields.io/badge/Star-this%20repo-1f2430?style=flat-square&logo=github&logoColor=white&labelColor=1f2430)](https://github.com/BOWLUNA/dsh-custom-mode/stargazers) [![npm](https://img.shields.io/npm/v/dsh-custom-mode?label=npm&style=flat-square&logo=npm&logoColor=white&labelColor=1f2430)](https://www.npmjs.com/package/dsh-custom-mode) [![CI](https://img.shields.io/github/actions/workflow/status/BOWLUNA/dsh-custom-mode/test.yml?label=CI&style=flat-square&logo=githubactions&logoColor=white&labelColor=1f2430)](https://github.com/BOWLUNA/dsh-custom-mode/actions/workflows/test.yml) [![license](https://img.shields.io/badge/license-MIT-97ca00?style=flat-square&logo=opensourceinitiative&logoColor=white&labelColor=1f2430)](LICENSE) [![dsh](https://img.shields.io/badge/dsh-%E2%89%A50.1.5--rc.2-4d6bfe?style=flat-square&logo=deepseek&logoColor=white&labelColor=1f2430)](https://github.com/BOWLUNA/dsh-custom-mode#readme)

[![bilibili](https://img.shields.io/badge/bilibili-videos-%2300A1D6?style=flat-square&logo=bilibili&logoColor=white&labelColor=1f2430)](https://b23.tv/qJ4Ev0W) [![Douyin](https://img.shields.io/badge/Douyin-shorts-%23FE2C55?style=flat-square&logo=tiktok&logoColor=white&labelColor=1f2430)](https://v.douyin.com/VWh0M03Fa4Y/) [![RedNote](https://img.shields.io/badge/RedNote-notes-%23FF2442?style=flat-square&logo=xiaohongshu&logoColor=white&labelColor=1f2430)](https://xhslink.cn/o/A7QtXmePBBF) [![Discord](https://img.shields.io/badge/Discord-chat-%235865F2?style=flat-square&logo=discord&logoColor=white&labelColor=1f2430)](https://discord.gg/pz97SfAfSy) [![GitHub](https://img.shields.io/github/discussions/BOWLUNA/dsh-custom-mode?label=GitHub&style=flat-square&logo=github&logoColor=white&labelColor=1f2430)](https://github.com/BOWLUNA/dsh-custom-mode/discussions)

A custom mode for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (dsh). Its
system prompt is a plain file you can edit on the Web settings page, and an edit takes effect on the
**next model step** — no restart, no new session.

In one line: **a settings page for dsh agent modes** — choose a mode's base composition, toggle the plugin
rows it mounts, and edit its system prompt, which the agent loop re-reads before every model step. Several
modes ("assistants") can live side by side, each with its own prompt.

Also searched for as: custom mode · custom prompt · system-prompt editor · multi-mode / several assistants ·
multi-agent · roleplay (RP) / chat personas.

**Unit tests cover three lines. The render gate runs only on `0.2.0-rc.2`.** npm's `latest` and `next` have both pointed at `0.2.0-rc.2` since 2026-09-29:

| dsh | role | status |
| --- | --- | --- |
| `0.2.0-rc.2` | **newest line** — npm's `latest` *and* `next`, and the line the official desktop app ships (it is version-locked to dsh) | ✅ CI (ubuntu node 20/24 + **windows**) + a real instance: install, composition and the seeded preset verified on this line (the full render gate runs here) |
| `0.2.1-alpha.1` | preview line — npm `alpha`. Covered by unit tests; the render gate does not run here | ✅ CI |
| `0.1.7-rc.2` | previous stable — still inside the declared range, and what most existing installs run | ✅ CI. On this line the page falls back where the shell does not provide atoms; the render gate does not run here |

Older builds (`0.1.5-rc.3`, `0.1.6-alpha.*`) use the same mechanisms and remain inside the declared peer
range, but they no longer get a CI leg of their own.

The four official modes (`standard` / `ptc` / `minimal` / `cordis`) are untouched.

<p align="center">
<img src="https://raw.githubusercontent.com/BOWLUNA/dsh-custom-mode/main/docs/images/01-mode-switch.png" width="360" alt="Base mode and plugin switches">
<img src="https://raw.githubusercontent.com/BOWLUNA/dsh-custom-mode/main/docs/images/02-plugin-switches.png" width="360" alt="Plugin switches"><br>
<img src="https://raw.githubusercontent.com/BOWLUNA/dsh-custom-mode/main/docs/images/03-system-prompt.png" width="360" alt="System prompt">
<img src="https://raw.githubusercontent.com/BOWLUNA/dsh-custom-mode/main/docs/images/04-preset-picker.png" width="360" alt="Mode picker">
</p>

<p align="center">
<img src="https://raw.githubusercontent.com/BOWLUNA/dsh-custom-mode/main/docs/images/05-assistant-manager.png" width="360" alt="Assistants">
</p>

The four views above are one 2×2 set. The assistant manager sits on its own, centered. Every file is English and exactly 800×800, shot on a throwaway `DSH_HOME` with no sessions.

## Install

One command installs everything — the settings-page plugin, and the preset it seeds on first
activation:

```sh
dsh plugin --profile web add dsh-custom-mode@2.0.1   # pin the version to get this one for sure
# A bare `add dsh-custom-mode` is subject to pnpm's release cooldown (`minimumReleaseAge`, 1 day by
# default): for hours after a release it can silently install an OLDER version — measured: a bare
# install 38 minutes after 1.3.0 shipped landed on 1.0.3. Check what you got with `npm ls
# dsh-custom-mode` inside the profile, or pin the version as above.
```

Restart dsh afterwards, then choose「自定义模式」for a new session. The preset is written to
`$DSH_HOME/.agent-presets/custom/`; anything already there is left alone, so a `prompt.md` you wrote
yourself is never overwritten.

**Platforms**: the suite runs on every push under Ubuntu (Node 20 and 24) **and Windows (Node 24)** — the
Windows job exists because that platform has its own failure modes (MSYS paths in `install.sh`, `rename`
locking under concurrent saves, platform expressions evaluating the other way). Both shell scripts work in
any POSIX shell, Git Bash included.

### Installing from the interface (no terminal)

From dsh `0.1.6-alpha.2` there is a Plugins page: **sidebar → Plugins → Add plugin**. It takes three
kinds of input, and all three work here:

| Input | What to paste |
| --- | --- |
| **Package name** | `dsh-custom-mode` |
| **GitHub repository URL** | `https://github.com/BOWLUNA/dsh-custom-mode` (the repository root) |
| **Local plugin directory** | `<your clone>` — the repository root, which *is* the package |

There is also an **in-app market** for browsing the whole ecosystem: install `dshmarket`
(`dsh plugin --profile web add dshmarket`), open **Settings → Plugin Market** and search
`dsh-custom-mode` — its cards read the `engines.dsh` range this plugin declares, and the curated shots
listed in the repository's `screenshots.json` (five images; they are the same `docs/images/*.png`
files the README shows, so each picture exists once).

All three work because the repository's **root `package.json` is the published package**: `dsh.bundle`,
`main` and `exports["./client"]` are declared there, so an npm install and a GitHub-URL install fetch
the same files. Before 1.10.0 this repository carried two manifests — a `private: true` wrapper at the
root plus `editor/package.json` — and that is what made third-party catalogues render the plugin as
`dsh-custom-mode#editor` and what made dshfind-derived cards read the wrapper's `private: true` and
report "not published to npm". One manifest at the root, and `test/manifests.test.mjs` keeps it that way.

### On the desktop app

The desktop application is part of dsh itself (`apps/desktop`), version-locked to it — Electron and
`@deepseek-ai/dsh` always carry the same exact version — and it is **the complete Web application in an
Electron shell**, so this plugin's page renders there unchanged. One install path, and it is the one above:

- **Install from inside the app**: sidebar → **Plugins** → add plugin → search `dsh-custom-mode`.
  The app runs the shared plugin manager with its own bundled pnpm.
- **Do not try to use a standalone CLI for it — dsh itself refuses** (measured on 0.1.7-rc.2, and again on
  0.2.0-rc.2):
  ```
  $ dsh plugin --profile desktop add .
  error: profile "desktop" is managed exclusively by the Electron application
  ```
  The desktop owns `$DSH_HOME/profiles/desktop`: package operations there hold a profile transaction lock
  and startup recovery renames `cordis.patch.yml`. `install.sh` therefore **refuses** `--profile desktop`
  too and points you at the app, so the two agree instead of one silently working around the other.
  (A lab simulation of the profile shape can override it with `DSH_ALLOW_DESKTOP_PROFILE=1`.)
  **The app's own bundled CLI is the exception** — `<install>\resources\runtime\cli\bin\dsh.cmd` is allowed
  to operate on that profile (an older note claimed a pin installed a different version; that note is withdrawn).
  It is a launcher, not a dsh feature: it starts Electron with `ELECTRON_RUN_AS_NODE=1` and runs the
  desktop-host CLI, so it is the app talking to its own profile rather than something driving from outside.
- **Nothing else to do on the preset side**: `$DSH_HOME/.agent-presets/` is product data shared by the
  desktop app and the CLI, and the preset is seeded by the plugin on first activation.
- **If the mode does not show up in the picker, open Settings → Custom mode and read the warning.** A preset
  whose rows cannot start on that machine is marked *broken* by the platform and dropped from every picker;
  the settings page names the rows and offers **Fix for this line**. On the desktop line that can take one
  click and then a **restart of the app** — the picker re-reads the registry only at boot (measured on
  1.11.1, see `docs/MEASUREMENTS.md` section 34).
- If a third-party bundle ever keeps the app from starting, the native recovery dialog offers
  **Disable third-party plugins**; installed packages and the plugin's own data stay on disk.

**Compatibility is declared, not assumed.** dsh checks every plugin's
`peerDependencies["@deepseek-ai/dsh"]` against the running runtime (prereleases included), and since
0.2.0 that check is an **installation gate**, not a warning: a range that does not cover the runtime
means `dsh plugin add` refuses the package outright
(`installation rejected: Plugin … is incompatible with dsh 0.2.0-rc.2`). The declared range therefore
*is* the support statement, which is why it moves only after the new line has been installed and tested
— a range that "probably" works is exactly the kind of claim that turns into a support ticket.

To keep the sources around as well — or to install without npm — clone and run the script, which does
the same two things explicitly:

```sh
git clone https://github.com/BOWLUNA/dsh-custom-mode
cd dsh-custom-mode
./install.sh            # copies preset/ into $DSH_HOME/.agent-presets/custom/ and installs the plugin
./uninstall.sh          # removes the plugin; keeps your prompt unless you pass --purge
```

The mode needs a profile that ships `agent-presets` — the `web` profile does, `tui` and `headless` do
not.

### Trying it without installing anything

Every release carries a **`dsh-custom-mode.dshpreset`** beside the source
([latest](https://github.com/BOWLUNA/dsh-custom-mode/releases/latest/download/dsh-custom-mode.dshpreset)).
It is a zip holding one ready-to-use assistant: `manifest.json` plus `preset/` — the composition, a
`prompt.md`, the reader that re-reads it before every model step, and a model-facing tool that reads and
rewrites that prompt. Import it in the desktop app and you have a working「自定义模式」**without
installing a package**:

| | |
| --- | --- |
| **What you get** | one assistant whose system prompt is a plain file. Edit it in any editor — the agent loop re-reads it before every model step, so the change applies on the next step, with no restart. Or just ask the model to rewrite it: the preset ships exactly that tool. |
| **What you don't get** | the settings page. That is what the plugin adds — the page itself, plus managing **several** assistants side by side and picking one from the new-session menu. |

So the preset is the 30-second way to see what this is, and the plugin is the way to live with it.
Importing the preset does not conflict with installing the plugin afterwards: the plugin seeds its own
preset under `$DSH_HOME/.agent-presets/` and never overwrites a `prompt.md` you wrote.

## Usage

The settings page (Settings → "Custom mode") is an **assistant manager**: the top of the page lists
every custom mode you have — create, switch, delete — and the four blocks below (name, base mode,
plugin switches, system prompt) edit **whichever one is selected**.

- **Ordering** — "Move up / Move down" writes the order into each assistant's `preset.yml` (`order`,
  the roster's own sort key), so it survives a restart and the new-session picker follows it.
- **Import / export a prompt** — "Export prompt" saves the current text as a `.md`; "Import prompt"
  reads a file into the **editor** (nothing is written until you save), so an import goes through the
  same `{{…}}` validation as anything typed.
- **Asking the agent to change its own prompt requires your approval.** The in-session `custom_prompt`
  tool goes through the platform's approval seam (`tools/pre-execute` returning `ask`), so the request waits
  for an explicit「允许一次」and shows what would be written and where. Measured: with the `ask` approval
  policy the panel appears and approving really writes; with `never` (full access) nothing prompts and the
  call is **denied**. The worst case is therefore "the change does not happen", never "it happened quietly".
- **配置了却不生效会被点名** — the page warns when a setting cannot take effect: the「身份（系统提示词）」
  row is off while `prompt.md` still has content (so your prompt is silently ignored), the `custom_prompt`
  tool row is off, the assistant has no name or no description (the picker shows the bare id /「暂无描述」).
- **The agent can change its own prompt, with your approval** — the in-session `custom_prompt` tool reads the
  prompt, replaces it, or **appends** to it. Appending matters because it never has to *reproduce* the whole
  prompt: an agent that wants to remember one rule cannot lose existing content on the way (measured: it may
  still read first to see what it is adding to). Both writing actions go through the platform's approval panel
  first.
- **Change history** — every save, *and* any change made outside this page (the in-session
  `custom_prompt` tool, a hand edit of `prompt.md`), leaves a version in the history list under the
  prompt box, labelled with when and where it came from. Loading one only edits the draft: nothing is
  written until you save, so browsing old versions cannot destroy the current one. Before this, a prompt
  changed from inside a session was invisible — the page only ever showed "the current text".
- **Reset to the factory prompt** — one click puts the **shipped template** (the text a new assistant
  starts from) back into the editor. It is draft-only like every other edit: nothing is written until
  you save, and Reload discards it. Before this existed, a prompt you had edited into a corner could
  only be recovered by deleting the assistant and creating it again.
- **New assistant** — type a name and click "New assistant". It is seeded from the packaged template:
  the full Standard row set plus a starter prompt, selectable in a new session as soon as you save it —
  **in an already-open page the picker's list is a load-time snapshot, so refresh (F5) once to see a new
  assistant there** (measured; the roster itself is up to date).
- **Duplicate** — copies the selected assistant's prompt, base mode and row switches into a new one;
  the two are independent afterwards.
- **Each assistant is independent** — its prompt, base mode and row switches are its own; changing one
  leaves the others alone.
- **Base mode is the row set, not the prompt** — it decides which rows exist and which tools the mode has;
  this plugin always replaces the base's `persona` row with its own reader (`complete: false`), so the
  base's *prompt* semantics are **not** inherited. Minimal is the visible case: you get minimal's tool set,
  not minimal's prompt.
- **A fifth base mode: Custom — the union of every shipped row.** The first four are dsh's own; the fifth is
  **synthesised by this plugin**. Its row set is the union of those four, and a row declared by more than one
  mode keeps the text and shipped default of whichever mode comes first in `standard → ptc → minimal →
  cordis` order — so standard's shipped state wins where there is a conflict.
  Why it has to exist: a switch can only rewrite a row the *base text already has*, so on Standard the rows
  only PTC declares (`tool-presentation`), only Minimal declares (the persistent-terminal group) and only
  Cordis declares (`tool-cordis`) were **unreachable** — not hidden in the UI, just absent from the composer.
  Measured on `0.2.0-rc.2`: standard 32 rows, PTC 33, Cordis 33, Minimal 7, and the union **40**. Groups
  travel whole with their `isolate` realms and `!!js` conditions intact, so the union cannot invent a row
  that `dsh-agent-presets` would refuse to mount.
- **The two shells cannot both be on, and the plugin moves the switch for you.** The union contains one
  mutually exclusive pair: Minimal's "persistent terminal" set and standard's `tool-bash` / `tool-pwsh` are
  two implementations of the same thing — both register a tool called `bash`. Enabling both makes
  `dsh-agent-presets` mark the whole mode **broken**, and a broken mode is silently dropped from **every**
  picker while the settings page stays green. So in Custom mode, turning one side on turns the other off,
  and the save message says which row moved. A conflicting combination written outside the page (hand edit,
  another tool) is named by a warning instead of failing silently.
- **Switching assistants never discards drafts** — each keeps its own unsaved edits, marked
  "Unsaved" in the list; the only path that throws edits away is the reload button, which renames
  itself to say so.
- **Delete** — a confirmation, then the plugin does the removal itself. Which confirmation depends on the
  line: when the shell hands a third-party client plugin its `RiskConfirmation` atom you get the shell's own
  dialog with a tick-box; on `0.1.7-rc.2` the client seed table only exposes `Button/Input/Switch/Tag/Pill`,
  so it degrades to a native `confirm` (same guardrail, one prompt instead of a dialog). Removal works on both
  lines: `0.1.6` and older have `agentPresets.remove()`, while `0.1.7`+ has no such call at all — there the
  plugin disposes its own registration and removes the directory. It only removes the mode directory from disk:
  **sessions already using it keep running** (their composition was read when they started), and new sessions
  no longer offer it.
- **Ask the agent** — every assistant ships a `custom_prompt` tool, so a session can read or rewrite
  **its own** prompt.
- **Edit the file** — `$DSH_HOME/.agent-presets/<assistant id>/prompt.md` is that assistant's single
  source of truth.

Only `{{model}}`, `{{cwd}}` and `{{provider}}` are interpolated. An unknown `{{…}}` is rejected when
saved: the renderer throws on it, which would fail every request in that mode.

Every control (buttons, inputs, switches, tags, the confirmation dialog, icons) comes from the
shell's own `@deepseek-ai/dsh-client-ui-primitives`, so theme, light/dark and future restyling reach
this page automatically; a shell that does not provide those atoms falls back to built-in plain
controls with the same behaviour.

An "assistant" is **one directory** under the user preset root (default `$DSH_HOME/.agent-presets/`),
and its directory name is its internal id. The page manages only **presets this tool created** — the
test is that the directory carries `prompt.md` and that its composition injects identity through
`prompt-reader.mjs`. Any other hand-authored preset is neither listed nor touched: the page
regenerates a composition from a base mode, and doing that to a hand-written one would destroy it.

### How this differs from the built-in Plugins page

From dsh `0.1.6-alpha.2` the harness ships a Plugins page that can enable and disable plugins live.
It and this mode's per-row switches act at **different levels**:

| | Built-in Plugins page | This mode's per-row switches |
| --- | --- | --- |
| Scope | **The whole profile** — what this machine has installed | **One agent mode** — which rows its composition mounts |
| Typical use | Turn a plugin off globally | Keep Standard fully loaded and trim this mode to what it needs |

They coexist: the harness decides what the machine has, this mode decides which of it the mode uses.

The boundary is **drawn by upstream**: the Plugins page documents that it manages "the profile's bundles
and their uniquely addressable rows", and states plainly that **agent-preset rows remain read-only**.
The preset layer is therefore out of its reach — and that is exactly the layer this mode covers.

A demonstrable example: `tool-plugin-manager` (the agent-facing install/toggle tool) ships **off in
Standard and PTC** — only Creator enables it. The official modes give you no way to change that; here
you flip one switch.

## How it works

dsh normally takes the system prompt from a preset's YAML, and `@deepseek-ai/dsh-persona` resolves its
`prefix` once at mount. This preset registers the same `deployment:persona-prefix` section but makes
its `text` a **function**, which the agent loop calls before every model step.

Two different things therefore decide when a change lands:

- **Prompt text** is re-read per step, so an edit applies to the **running** session immediately.
- **Switches and base mode** rewrite the composition file. `agent-presets` remounts a preset when that
  file's `mtimeMs` and `size` change, so a **new session** picks them up; a running session keeps the
  configuration it started with, which is deliberate — swapping a tool set mid-conversation would be wrong.

Row switches are tri-state. A row you never touched stays byte-identical to the shipped one, including
its `!!js` platform condition and its shipped `disabled` state; an explicit on/off replaces that
condition with a boolean. Platform expressions are evaluated on the host, so the page shows the state
actually in force on this machine rather than whether a key exists.

Several assistants need no new mechanism: `dsh-agent-presets` already scans **every** directory under
the user preset root, and re-reads those roots on each roster call, so a directory created just now is
selectable the next time a session is started. Each assistant's `prompt-reader.mjs` / `prompt-tool.mjs`
resolves `prompt.md` relative to **its own module location**, so N copies are N independent prompts.
Creation seeds the packaged template; deletion goes through the platform's `agentPresets.remove()` where it
exists and through the plugin's own dispose-and-remove path where it does not (0.1.7+),
which refuses a shipped preset and re-checks that the directory really lives under the writable root.

## Versioning

The package version is its **own line** — `1.0.0`, then `1.0.1`, … It does not mirror the DSH release.
What this plugin supports is declared in `engines.dsh` and the `@deepseek-ai/dsh` peer range in
`package.json`, and `tools/verify-version-consistency.mjs` (run in CI) asserts that the DSH
version CI installs and tests falls inside those ranges.

**Three lines are supported: the newest stable line (`0.2.0-rc.2`, which npm's `latest` and `next` both
point at since 2026-09-29, and the line the desktop app ships), the newest preview line
(`0.2.1-alpha.1`, which npm's `alpha` points at) and the previous stable (`0.1.7-rc.2` — still inside
the declared range, and what most existing installs run)** — declared as
`>=0.1.5-rc.2 <0.2.0-0 || >=0.1.6-alpha.1 <0.2.0-0 || >=0.1.7-alpha.1 <0.2.0-0 || >=0.2.0-0 <0.3.0-0 || >=0.2.1-alpha.1 <0.3.0-0`.
The official one-click installer reads `peerDependencies` and **lets prereleases match** (`semver.satisfies(version, range, { includePrerelease: true })` in `dsh-app-boot`). The current stable line `0.2.0-rc.2` and the preview line `0.2.1-alpha.1` both install. The range still names those prereleases because npm and pnpm's default comparison is stricter, so a marketplace using that comparison does not reject the preview line either. CI installs all three lines and runs the full suite on each. The render gate runs only on `0.2.0-rc.2`, where the shell's UI changes land first. Older `0.1.5` / `0.1.6` builds stay inside the peer range and no longer have their own CI leg.
**The official desktop app (DeepSeek Harness Desktop) is covered too**: it is version-locked to dsh and now
ships `0.2.0-rc.2`, i.e. the same combination this matrix pins. Install it from inside the app (sidebar →
Plugins); the CLI is refused for that profile by dsh itself. On `0.2.0-rc.2` measured (2026-09-30, clean
throwaway `DSH_HOME`): `dsh plugin --profile web add dsh-custom-mode` resolves the package in 640 ms, the
composition tree carries the row, boot seeds all five preset files, the declarative registry syncs the
assistant, and the `agentPresets` capability set is unchanged (`list, register, inventory, select,
document`).

Two reasons for the split. A bare `x.y.z` is what directories and markets require before they will
auto-install a package — several resolve npm `latest` and reject anything carrying a prerelease tag.
And a version string was never a checkable claim anyway: the declared range is, and it is the thing
that goes stale when upstream moves. What decides compatibility in practice is still whether the APIs
below exist, which is what the ranges are for.

<details>
<summary>Coupling points (check these when upgrading dsh)</summary>

| Dependency | Failure if it changes |
| --- | --- |
| `ctx.systemPrompt.section()` with a **function** `text` | the prompt stops hot-reloading — the point of the project |
| `agentPresets` remounts on composition `mtimeMs`+`size` | switches need a process restart to apply |
| `ctx.tools.register()` | loses the `custom_prompt` tool |
| `ctx.connection.fetch.register({ path, methods, requestBody, fetch })` | settings page 404s — nothing is registered |
| `kind: 'prefix'` matching both `path` and `path/…` | only the list opens; `/state`, `/create`, `/delete` all 404 |
| `agentPresets.list()` rows carrying `id` / `trust` / `path`, with `preset.yml` supplying `name` / `description` | the assistant list is empty or unrecognisable |
| `agentPresets.remove(id)` (legacy only), refusing `trust: 'system'` | 0.1.7+ deletion no longer needs it — the declarative backend disposes the registration and deletes the directory instead |
| the `/api` channel's fence (Host/Origin + browser auth) | the page cannot authenticate at all; do **not** "fix" it by moving the route to the raw `webServer` table |
| `ctx.inject(deps, cb)` (scoped wait) | the row parks in `pending` in profiles without a web server |
| `dsh.client` + `exports["./client"]`, client bundle id == package name | the browser half is not discovered |
| `settings.section` slot (`id` / `order` / `label`) | page placement and label |
| **`settings.section` no longer takes `locale:`** (since 0.1.6-alpha.2) | the shell does not hand over a `t` bound to this namespace; the page carries its own dictionaries as a floor — see ARCHITECTURE §15 |
| `preset.yml`'s `order` participating in the roster sort | move up/down stops working |
| `ctx.locale.register/bind` | falls back to Chinese |
| **where the base composition comes from** — `agentPresets.readDocument(<mode>).content` on 0.1.7+, the `@deepseek-ai/dsh-agent-presets` files before that | the base mode and the plugin switches become read-only (the prompt still saves); see `base-composition.mjs` |
| shipped layout `<presets>/<id>/agent.cordis.yml` and row text shape | base-mode switching breaks |
| `!!js` platform expressions | platform rows display the wrong state |

</details>

## Documentation

- [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) — failures reproduced on a real machine, with symptoms, cause and a way out.
- [`docs/MEASUREMENTS.md`](docs/MEASUREMENTS.md) — the commands and raw output behind each claim.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — why this is two artifacts, and which host APIs it depends on.
- [`docs/PUBLISHING.md`](docs/PUBLISHING.md) — how the npm package is published.
- [`AGENTS.md`](AGENTS.md) — agent-facing notes, including the full recipe for verifying the UI in a
  real browser on the lab (`tools/browser-verify.mjs`).
- [`CHANGELOG.md`](CHANGELOG.md) · [`SECURITY.md`](SECURITY.md) · [`CONTRIBUTING.md`](CONTRIBUTING.md)

## Development

```sh
node test/run.mjs        # 15 suites; resolves the shipped presets itself (0.1.7+ derives them from the host)
```

Edits to `client.js` are hot-swapped by `@deepseek-ai/dsh-client-hmr` about a second later; the
host half (`index.mjs`, `composition.mjs`, `meta.mjs`, `paths.mjs`) needs a restart. See
[`test/README.md`](test/README.md) for what each suite protects, and [`CONTRIBUTING.md`](CONTRIBUTING.md)
before changing behaviour.

## If it helps

A ⭐ on GitHub is what makes a plugin findable in a catalogue of thousands — it costs you nothing and it is
the whole reason this page keeps getting maintenance. If it is **not** working on your dsh line, open an issue
with the output of `dsh --version`: that is the fastest path to a fix, and the compatibility table above is
what came out of the last one.

## License

MIT

## Updating

The plugin lives in the profile's `node_modules` and the platform owns that installation, so updating means
installing again — your data is not touched:

```sh
# the pinned form: what you ask for is what you get
dsh plugin --profile web add dsh-custom-mode@2.0.1
# then restart the DSH process that serves the web profile
```

**Do not install by bare name, and do not use `@latest`.** pnpm applies a release cooldown
(`minimumReleaseAge`, 24 hours by default). A bare `dsh plugin add dsh-custom-mode` resolves to *the newest
version older than 24 hours*. Measured: while npm `latest` was 1.12.2 and six minutes old, `@latest` installed
1.11.6. Pin the exact version.

Each exact pin appends a `minimumReleaseAgeExclude` line in the profile's `pnpm-workspace.yaml`. pnpm 11.7.0
honours only the first line that matches the package name ([pnpm#12463](https://github.com/pnpm/pnpm/issues/12463)).
Keep one line, `dsh-custom-mode`, not a list of versions. This plugin does not edit that file. If install fails
with `ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION`, merge the list to that one line and run the install again:
`node_modules` may already show the new version while `package.json` still names the old one.

**How to know the upgrade worked**: restart dsh, then read the version at the bottom of this settings page.

**What an update does not touch**: `$DSH_HOME/.agent-presets/<your assistants>/` — `prompt.md`, `preset.yml` and
your row switches are yours. Seeding only fills in *missing* files, so a prompt you wrote is never overwritten.

**If the base mode and the plugin switches are greyed out with a one-line warning**: this dsh line exposes no
shipped composition to this plugin (the resolver tried the host's `readDocument()`, the legacy presets package
and the packaged `dsh-web-app` patch — the host log names each attempt). The system prompt still saves on its
own; nothing else about the mode is touched. On 0.1.7 that was a hard 500 until 1.9.13.

**If a mode stops appearing in the picker after an update**: an assistant created by an older version keeps its
old composition file, and if that file enables a plugin row this dsh line does not ship, the platform marks the
whole preset broken and silently drops it while the settings page keeps working. Open the settings page: it says
so and offers **「Fix for this line」**, which turns exactly those rows off and leaves everything else alone.
