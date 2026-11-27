import { api, runProcess, unwrap } from '@jabiz/client'
import { AVATAR } from './ids'
import presets from './presets.json'

/*
 * A taker's own avatar (Q114a, `avatar.AvatarProcesses`, docs/quizbuks/plans/impl/Q114.md): read from the caller's
 * own dataset (no row: the initial), chosen with `QB_AVATAR_SET`. Only the taker sees it.
 */
/** The query key of the caller's avatar, which every avatar of the frame reads. */
export const AVATAR_QUERY = ['qb', 'avatar'] as const

/** The preset drawings, in the order the picker shows them (the same list as the server's `AvatarPresets`). */
export const PRESETS: readonly string[] = presets.codes

/** What the avatar is (typed by hand after `AvatarProcesses.SetInput` / `AvatarOutput`). */
export type MyAvatar = { kind: 'INITIAL' } | { kind: 'PRESET'; preset: string }

export const INITIAL: MyAvatar = { kind: 'INITIAL' }

function avatarOf(row: Record<string, unknown> | null | undefined): MyAvatar {
  const preset = row?.preset
  return row?.kind === 'PRESET' && typeof preset === 'string' && PRESETS.includes(preset)
    ? { kind: 'PRESET', preset } : INITIAL
}

/** The caller's avatar; the initial when they never chose one. */
export async function readMyAvatar(): Promise<MyAvatar> {
  const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: AVATAR.mine } },
    body: { limit: 1 },
  }))
  const first = (page.items ?? [])[0]
  return avatarOf(first ? (first.attributes ?? {}) as Record<string, unknown> : null)
}

/** Chooses the caller's avatar; answers it as saved. */
export async function setMyAvatar(avatar: MyAvatar): Promise<MyAvatar> {
  const output = await runProcess<Record<string, unknown>>(AVATAR.set,
    avatar.kind === 'PRESET' ? { kind: 'PRESET', preset: avatar.preset } : { kind: 'INITIAL' })
  return avatarOf(output)
}

/** The drawing of a preset: a file of the brand profile (`brands/<brand>/public/avatars/`), served by the site. */
export function presetUrl(preset: string): string {
  return `${import.meta.env.BASE_URL}avatars/${preset}.svg`
}
