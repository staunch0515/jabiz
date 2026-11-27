/** The names of a taker's avatar on the server (Q114; `avatar.AvatarProcesses`, `avatar.QbAvatars`). */
export const AVATAR = {
  set: 'QB_AVATAR_SET',
  /** The caller's avatar and whether a photo can be newly chosen now (Q114b; always one row). */
  mine: 'qb.app.avatar',
  /** The photos' file policy (Q114b). */
  photoPolicy: 'qb.taker-avatar',
} as const
