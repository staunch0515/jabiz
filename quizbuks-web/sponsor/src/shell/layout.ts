import { runQuery } from '@jabiz/client'
import defaults from './bottom-bar.default.json'
import { SHELL_TEMPLATE } from './ids'

/**
 * The bottom menu's areas until the parameter is read, and when it has no value yet (before `QB_SETUP`): the same as
 * the parameter's declared value (`QbParams`; the backend's `SponsorLayoutParamsTest` reads this file and compares),
 * so the menu does not change once the answer is in.
 */
export const DEFAULT_BOTTOM_BAR: string = defaults.bottomBar

export const LAYOUT_QUERY = ['qb', 'sponsor-layout'] as const

/** The frame's parameters (`qb.sponsor.layout`): the bottom menu's area keys, comma-separated. */
export async function readLayout(): Promise<{ bottomBar: string }> {
  const page = await runQuery<Record<string, unknown>>(SHELL_TEMPLATE.layout, { limit: 1 })
  const value = page.items[0]?.bottomBar
  return { bottomBar: typeof value === 'string' && value.trim() !== '' ? value : DEFAULT_BOTTOM_BAR }
}
