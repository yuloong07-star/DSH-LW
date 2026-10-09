/**
 * Filesystem locations shared by the settings page's host half and the
 * composition compiler.
 *
 * The prompt file and the composition file must live in the SAME preset
 * directory — that is the whole contract between the preset and this plugin. So
 * the composition path is derived from the prompt path rather than configured
 * separately: one override cannot silently desynchronise them.
 */

import { join, dirname } from 'node:path'
import { homedir } from 'node:os'

/** DSH home, matching the deployment's own default resolution. */
export function dshHome() {
  return process.env.DSH_HOME && process.env.DSH_HOME !== '' ? process.env.DSH_HOME : join(homedir(), '.dsh')
}

/**
 * Absolute path of the prompt file the page edits.
 *
 * Honours `DSH_CUSTOM_PROMPT_PATH` so a deployment can point the editor at a
 * preset directory with another id; the default matches the `custom` preset
 * installed under DSH home.
 */
export const PROMPT_PATH =
  process.env.DSH_CUSTOM_PROMPT_PATH && process.env.DSH_CUSTOM_PROMPT_PATH !== ''
    ? process.env.DSH_CUSTOM_PROMPT_PATH
    : join(dshHome(), '.agent-presets', 'custom', 'prompt.md')

/** The preset directory both files live in. */
export const PRESET_DIR = dirname(PROMPT_PATH)

/** The preset's composition file, rewritten by the base-mode and switch settings. */
export const COMPOSITION_PATH = join(PRESET_DIR, 'agent.cordis.yml')

/** This feature's own private HTTP route. Browser half and host half must agree. */
export const ROUTE_PATH = '/custom-mode'
