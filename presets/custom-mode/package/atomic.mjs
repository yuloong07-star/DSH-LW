/**
 * Atomic file writes — one implementation for every writer in the host half.
 *
 * **Why this is a module and not a helper inside `index.mjs`**: the settings page, the preset meta writer and
 * the seeder all write user-visible files, and "we are careful here but not there" is how a project gets a
 * half-written `preset.yml` (whose failure mode is the whole mode disappearing from every picker). A review
 * found exactly that split: `index.mjs` had the retrying atomic write while `meta.mjs` used a bare
 * `writeFileSync` under a comment promising the opposite. Sharing the function makes the discipline structural.
 *
 * The two things it buys:
 *  - **temporary name + rename**, so a reader never observes a half-written file (the prompt reader caches by
 *    mtime+size, and `preset.yml` is parsed by the platform);
 *  - **retry on Windows**, where two concurrent renames onto the same target fail with `EPERM`/`EBUSY`; a
 *    random temporary name does not help with *that* collision, only with temp-vs-temp ones.
 */
import { randomBytes } from 'node:crypto'
import { existsSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

/** Synchronous backoff: these write paths are synchronous, so waiting is simpler than async plumbing. */
function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms)
}

/**
 * `rename` with retries.
 *
 * @param {string} from - the temporary file that already holds the content.
 * @param {string} to - the destination.
 * @param {{rename?: Function, sleep?: Function, attempts?: number}} [options] - injectable for tests, which is
 *   how the Windows-only retry behaviour is pinned on Linux.
 * @returns {void}
 */
export function renameWithRetry(from, to, options = {}) {
  const rename = typeof options.rename === 'function' ? options.rename : renameSync
  const sleep = typeof options.sleep === 'function' ? options.sleep : sleepSync
  const attempts = Number.isInteger(options.attempts) ? options.attempts : 8
  for (let attempt = 1; ; attempt += 1) {
    try {
      rename(from, to)
      return
    } catch (error) {
      const code = error !== null && typeof error === 'object' ? error.code : undefined
      const retryable = code === 'EPERM' || code === 'EBUSY' || code === 'EACCES'
      if (retryable !== true || attempt >= attempts) throw error
      // 8 attempts with a growing backoff (20, 40, … 160 ms) — measured: a review still saw 1 failure in 10
      // concurrent saves with the previous 5×20 ms, so the window is wider than that on Windows.
      sleep(20 * attempt)
    }
  }
}

/**
 * Stage content into a temporary file next to its destination, without touching the destination yet.
 *
 * Used when several files must change together (the settings page writes the composition and the prompt):
 * staging both first means a failure while *preparing* one leaves the other untouched, which is the window a
 * review pointed at ("`saveState` is not transactional; the second write failing leaves a mixed state").
 *
 * @param {string} file - destination path.
 * @param {string} text - content to write into the temporary.
 * @returns {{file: string, temporary: string}} handle for {@link commitStaged} / {@link discardStaged}.
 */
export function stageAtomic(file, text) {
  const temporary = `${file}.tmp-${String(process.pid)}-${randomBytes(4).toString('hex')}`
  writeFileSync(temporary, text, 'utf8')
  return { file, temporary }
}

/** Rename a staged temporary onto its destination (with the Windows retry). */
export function commitStaged(staged) {
  renameWithRetry(staged.temporary, staged.file)
}

/** Remove a staged temporary without touching the destination. */
export function discardStaged(staged) {
  try {
    rmSync(staged.temporary, { force: true })
  } catch {
    /* best effort */
  }
}

/**
 * Write several files together, as close to a transaction as a filesystem will give us.
 *
 * **Why this is not the old two-phase version.** The previous implementation staged everything and then
 * renamed them one by one, and said so honestly in a comment ("not a true transaction — a rename can still
 * fail between two commits"). But the callers' contracts do not say that: `savePromptOnly` promises
 * "提示词与 preset.yml 一起换名，失败则两个都不动", and `saveState` says the three files are written
 * together (issue #5). A caller that returns `ok:false` while the disk holds new-prompt + old-name is
 * exactly the state those promises rule out — the user is told nothing was saved and half of it was.
 *
 * Two defects were measured by `probes/probe-io.mjs`:
 *
 *   L   staging happened **outside** the try, so a staging failure left its `.tmp-…` behind — and the
 *       module's own comment says an orphan temporary in an assistant directory is not just litter,
 *       because the roster scans that directory;
 *   K   committing renamed them in order and gave up on the first failure — the earlier targets were
 *       already replaced, and the caller saw a plain failure.
 *
 * **How it is transactional now**: stage everything, then move every existing target aside to a backup,
 * then put the new files in place. Any failure anywhere in that sequence restores from the backups and
 * removes what it had already installed, so the caller's "nothing changed" is true. The backups are
 * removed only after every destination is in place.
 *
 * @param {Array<[string, string]>} entries - `[file, text]` pairs.
 * @returns {void}
 */
export function writeAtomicPair(entries) {
  const stamp = `${String(process.pid)}-${randomBytes(4).toString('hex')}`

  // ── phase 0: refuse to touch a destination that is a directory. ──
  //
  // Measured: `probes/probe-io.mjs` K injects the failure by making the second target a directory.
  // A naive "move the old target aside, then install" transaction **succeeds** on that input — it renames
  // the user's directory to `.bak-…` and leaves a file where it was. That is worse than failing: it obeys
  // the letter of "transactional" while quietly relocating a directory nobody asked it to touch.
  // Being transactional must not mean "willing to move anything out of the way". A directory at a path that
  // should hold a file is not a target to replace — it is a reason to refuse before anything moves.
  for (const [file] of entries) {
    if (existsSync(file) && statSync(file).isDirectory()) {
      throw new Error(`拒绝写入：目标已存在且是目录 — ${file}（未改动任何文件）`)
    }
  }

  // ── phase 1: stage everything. A failure here must not leave a temporary behind. ──
  const staged = []
  try {
    for (const [file, text] of entries) staged.push(stageAtomic(file, text))
  } catch (error) {
    for (const one of staged) discardStaged(one)
    throw error
  }

  // ── phase 2: move the current targets aside. `null` = the target did not exist. ──
  const backups = []
  const installed = []
  try {
    for (const one of staged) {
      if (existsSync(one.file)) {
        const backup = `${one.file}.bak-${stamp}`
        renameWithRetry(one.file, backup)
        backups.push([one.file, backup])
      } else {
        backups.push([one.file, null])
      }
    }
    // ── phase 3: install. ──
    for (const one of staged) {
      commitStaged(one)
      installed.push(one)
    }
  } catch (error) {
    // Undo in reverse: remove what we installed, then put the backups back.
    for (const one of installed) {
      try {
        rmSync(one.file, { force: true })
      } catch {
        /* best effort — the restore below is what matters */
      }
    }
    for (const [file, backup] of backups) {
      if (backup === null) continue
      try {
        renameWithRetry(backup, file)
      } catch {
        /* best effort; the thrown error still tells the caller nothing is consistent */
      }
    }
    for (const one of staged) discardStaged(one)
    throw error
  }

  // ── success: drop the backups ──
  for (const [, backup] of backups) {
    if (backup === null) continue
    try {
      rmSync(backup, { force: true })
    } catch {
      /* a leftover .bak-… is inert; failing the whole write over it would be worse */
    }
  }
}

/**
 * Write a file so that readers see either the old content or the new one, never a mix.
 *
 * The temporary name is unique per call (pid + random suffix). On failure the temporary file is removed —
 * an orphan `.tmp-…` inside an assistant directory is not just litter: the roster scans that directory.
 * **The staging write itself is inside the try** for the same reason: a `writeFileSync` that fails part way
 * (a full disk, an EACCES) leaves a partial temporary, and that is exactly the litter this promises not to
 * leave. Measured by `probes/probe-io.mjs` — the same defect the pair version had.
 *
 * @param {string} file - destination path.
 * @param {string} text - content to write.
 * @returns {void}
 */
/**
 * 掉电落在「旧文件已改名为 .bak、新文件还没装上」时，把唯一的备份移回原名。
 *
 * 多于一份备份时不动：无法判断哪一份是最后的好内容。
 *
 * @param {string} directory - one assistant directory.
 * @returns {void}
 */
export function restoreBackups(directory) {
  const names = ['prompt.md', 'agent.cordis.yml', 'preset.yml']
  let entries
  try {
    entries = readdirSync(directory)
  } catch {
    return
  }
  for (const name of names) {
    const file = join(directory, name)
    if (existsSync(file)) continue
    const backups = entries.filter((entry) => entry.startsWith(name + '.bak-'))
    if (backups.length !== 1) continue
    try {
      renameSync(join(directory, backups[0]), file)
    } catch {
      /* 恢复失败就留着备份，下一次读取再试 */
    }
  }
}

export function writeAtomic(file, text) {
  const temporary = `${file}.tmp-${String(process.pid)}-${randomBytes(4).toString('hex')}`
  try {
    writeFileSync(temporary, text, 'utf8')
    renameWithRetry(temporary, file)
  } catch (error) {
    try {
      rmSync(temporary, { force: true })
    } catch {
      /* cleaning up must not mask the original error */
    }
    throw error
  }
}
