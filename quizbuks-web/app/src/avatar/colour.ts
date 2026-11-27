/*
 * The initial's background (Q114a, docs/quizbuks/plans/impl/Q114.md section 4.1): one of the brand's eight
 * `--qb-avatar-*` colours, taken from the taker's id, so that the same person has the same colour on every device and
 * two takers with the same initial mostly differ. Not stored: only the taker sees it.
 */

/** The backgrounds as Tailwind classes, written out so that Tailwind finds them. */
export const AVATAR_BACKGROUNDS = [
  'bg-qb-avatar-1',
  'bg-qb-avatar-2',
  'bg-qb-avatar-3',
  'bg-qb-avatar-4',
  'bg-qb-avatar-5',
  'bg-qb-avatar-6',
  'bg-qb-avatar-7',
  'bg-qb-avatar-8',
] as const

/** FNV-1a over the id's UTF-16 code units: small, stable and spread well enough for eight buckets. */
function hash(text: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

/** The index (0–7) of the background for a taker; no id yet (still loading): the first, the brand's own. */
export function initialColour(userId: string | null | undefined): number {
  return userId ? hash(userId) % AVATAR_BACKGROUNDS.length : 0
}

/** The background class for a taker. */
export function initialBackground(userId: string | null | undefined): string {
  return AVATAR_BACKGROUNDS[initialColour(userId)]
}
