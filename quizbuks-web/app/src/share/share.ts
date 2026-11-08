import { minuteNow, publicQuery } from '../qb/legal'
import type { QuizKind } from '../qb/play'

/** The public template of a share link's page (`queries/qb/public/publication.sql`; Q15). */
export const SHARE_TEMPLATE = 'qb.public.publication'

/** A live publication as anybody sees it before signing in: a whitelist of its card and the sponsor's company. */
export interface SharedPublication {
  publicationId: string
  name: string
  quizTitle: string
  kind: QuizKind
  startAt: string
  endAt: string
  timeLimitSec: number
  questionCount: number
  /** Q81: the questions drawn per taker; null (or absent) asks all of them. */
  pickCount?: number | null
  fullScore: number
  passScore: number
  maxReward: unknown
  sponsorCompany: string | null
  /** Q60a: the sponsor's published page, which the company links to. */
  sponsorHandle?: string | null
  /** F-S11: asked from mainland China, it has a video that cannot be played there (and no exception). */
  regionBlocked?: boolean
}

/** The publication if it is live, else null (scheduled, paused, over, unknown: the page says it is not open). */
export async function readSharedPublication(publicationId: string, mainlandChina = false):
  Promise<SharedPublication | null> {
  const rows = await publicQuery<SharedPublication>(SHARE_TEMPLATE, { publicationId,
    ...(mainlandChina ? { mainlandChina: 'true', at: minuteNow() } : {}) }, 1)
  return rows[0] ?? null
}

/** The path of a publication's page, which its share link opens (`/p/<id>`). */
export function sharePath(publicationId: string): string {
  return `/p/${encodeURIComponent(publicationId)}`
}

const NEXT_KEY = 'qb.share.next'

/**
 * Keeps the publication page a share link led to while the welcome step comes first (a new taker has no country yet):
 * the step is then the short one, and leads there once saved. In this tab only.
 */
export function rememberShareNext(path: string) {
  try {
    window.sessionStorage.setItem(NEXT_KEY, path)
  } catch {
    // A convenience only: the welcome step then leads home.
  }
}

/** The page kept by {@link rememberShareNext}, if it is a publication's. */
export function shareNext(): string | null {
  try {
    const path = window.sessionStorage.getItem(NEXT_KEY)
    return path && /^\/p\/[^/?#]+$/.test(path) ? path : null
  } catch {
    return null
  }
}

export function forgetShareNext() {
  try {
    window.sessionStorage.removeItem(NEXT_KEY)
  } catch {
    // Nothing kept.
  }
}
