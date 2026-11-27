import { api, runProcess, unwrap, uploadFile } from '@jabiz/client'
import { AVATAR } from './ids'
import presets from './presets.json'

/*
 * A taker's own avatar (Q114, `avatar.AvatarProcesses`, docs/quizbuks/plans/impl/Q114.md): read with the template
 * `qb.app.avatar` (no row of the caller's: the initial), chosen with `QB_AVATAR_SET`. Only the taker sees it.
 */
/** The query key of the caller's avatar, which every avatar of the frame reads. */
export const AVATAR_QUERY = ['qb', 'avatar'] as const

/** The preset drawings, in the order the picker shows them (the same list as the server's `AvatarPresets`). */
export const PRESETS: readonly string[] = presets.codes

/** What the avatar is (typed by hand after `AvatarProcesses.SetInput` / `AvatarOutput`). */
export type MyAvatar = { kind: 'INITIAL' } | { kind: 'PRESET'; preset: string } | { kind: 'PHOTO'; photo: string }

/** The avatar, and whether a photo can be newly chosen now (`qb.avatar.photo.enabled`; the server decides). */
export interface AvatarState {
  avatar: MyAvatar
  photoEnabled: boolean
}

export const INITIAL: MyAvatar = { kind: 'INITIAL' }

function avatarOf(row: Record<string, unknown> | null | undefined): MyAvatar {
  const preset = row?.preset
  const photo = row?.photo
  if (row?.kind === 'PRESET' && typeof preset === 'string' && PRESETS.includes(preset)) {
    return { kind: 'PRESET', preset }
  }
  if (row?.kind === 'PHOTO' && typeof photo === 'string' && photo) return { kind: 'PHOTO', photo }
  return INITIAL
}

/** The caller's avatar (the initial when they never chose one) and whether a photo can be chosen. */
export async function readMyAvatar(): Promise<AvatarState> {
  const page = await unwrap(api.POST('/api/queries/{queryId}', {
    params: { path: { queryId: AVATAR.mine } },
    body: { params: {} },
  }))
  const row = ((page.items ?? [])[0] ?? null) as Record<string, unknown> | null
  return { avatar: avatarOf(row), photoEnabled: row?.photoEnabled === true }
}

/** Chooses the caller's avatar; answers it as saved. */
export async function setMyAvatar(avatar: MyAvatar): Promise<MyAvatar> {
  const input = avatar.kind === 'PRESET' ? { kind: 'PRESET', preset: avatar.preset }
    : avatar.kind === 'PHOTO' ? { kind: 'PHOTO', photo: avatar.photo } : { kind: 'INITIAL' }
  const output = await runProcess<Record<string, unknown>>(AVATAR.set, input)
  return avatarOf(output)
}

/**
 * Uploads a photo already made square (`squarePhoto`) under the photos' policy and answers its id. The name is neutral
 * (root CLAUDE.md, files: the original name stays the uploader's anyway, `privateMetadata()`).
 */
export async function uploadPhoto(photo: Blob): Promise<string> {
  const uploaded = await uploadFile(AVATAR.photoPolicy, photo, 'avatar.jpg')
  if (!uploaded.fileId) throw new Error('The upload answered no file')
  return uploaded.fileId
}

/** The drawing of a preset: a file of the brand profile (`brands/<brand>/public/avatars/`), served by the site. */
export function presetUrl(preset: string): string {
  return `${import.meta.env.BASE_URL}avatars/${preset}.svg`
}
