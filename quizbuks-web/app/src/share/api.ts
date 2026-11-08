import { runQuery } from '@jabiz/client'
import { minuteNow, publicQuery } from '../qb/legal'
import type { ContentLanguage } from '../qb/api'
import type { QuizKind, RewardMethod } from '../qb/play'
import type { PublicProduct, PublicationProduct } from '../qb/products'
import { PLAY } from '../qb/ids'
import { certificationNamesOf } from '../qb/certifications'
import type { I18nText } from '../qb/legal'
import { edgesOf, readAllRows, treeStateOf, type TreeEdge, type TreeState } from '../qb/tree'
import { SPONSOR_PAGE_TEMPLATE } from './ids'

/* A sponsor's page as anybody reads it in the app (Q60a, Q60b): from the public templates only. */

/** A published page as anybody reads it. */
export interface PublicSponsorPage {
  handle: string
  language: ContentLanguage
  headline: string
  story: string | null
  logo: string | null
  cover: string | null
  website: string | null
  company: string
  /** Q73c: the languages it offers (`en,zh`, the main one first) and the one it is shown in. */
  languages?: string | null
  displayLanguage?: string | null
}

/** A live public publication the page lists. */
export interface PagePublication {
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
  maxReward: unknown
  rewardMethod: RewardMethod
  /** Q76: the listed publications it requires first, as JSON (`PublicationPrerequisite` without names); none: `[]`. */
  prerequisites?: string | null
}

/** A published page by its handle (typed in any case); null when there is none. */
export async function readPublicSponsorPage(handle: string, languages: readonly string[] = []):
  Promise<PublicSponsorPage | null> {
  const rows = await publicQuery<PublicSponsorPage>(SPONSOR_PAGE_TEMPLATE.page, { handle: handle.toLowerCase(),
    ...(languages.length > 0 ? { languages: languages.join(',') } : {}) }, 1)
  return rows[0] ?? null
}

/** The live public publications of the page with the handle, ending soonest first. */
export function readPagePublications(handle: string, mainlandChina = false): Promise<PagePublication[]> {
  // F-S11: from mainland China (as the platform said), without what has a video that cannot be played there; the
  // template answers by these parameters only (the time to the minute), so the shared cache serves it.
  return publicQuery<PagePublication>(SPONSOR_PAGE_TEMPLATE.publications, { handle: handle.toLowerCase(),
    ...(mainlandChina ? { mainlandChina: 'true', at: minuteNow() } : {}) }, 50)
}

/** A published page's listed products, in the sponsor's order. */
export function readPageProducts(handle: string, languages: readonly string[] = []): Promise<PublicProduct[]> {
  return publicQuery<PublicProduct>(SPONSOR_PAGE_TEMPLATE.products, { handle: handle.toLowerCase(),
    ...(languages.length > 0 ? { languages: languages.join(',') } : {}) }, 50)
}

/** A live publication's products, by question number (0: the quiz's) and in the sponsor's order. */
export async function readPublicationProducts(publicationId: string): Promise<PublicationProduct[]> {
  const rows = await publicQuery<PublicationProduct>(SPONSOR_PAGE_TEMPLATE.publicationProducts, { publicationId }, 200)
  return [...rows].sort((a, b) => Number(a.questionNo) - Number(b.questionNo) || Number(a.seq) - Number(b.seq))
}

/** A node of `qb.app.sponsor-tree` (Q85): one of the sponsor's publications as the caller stands on it. */
export interface SponsorTreeNode {
  id: string
  name: string
  kind: QuizKind
  endAt: string
  maxReward: unknown
  live: boolean
  state: TreeState
  requires: TreeEdge[]
  /** The required publications' names, by their id (from the `prerequisites` JSON). */
  requiredNames: Record<string, string>
  /** Q86: the certifications the node requires, whether the caller holds each and its current round. */
  certifications: { key: string; held: boolean; roundPublicationId: string | null; names: I18nText | null }[]
}

/**
 * The sponsor's tree for the signed-in caller: their live public publications and those the caller answered, each
 * with the caller's state. None when the page is not published.
 */
export async function readSponsorTree(handle: string, at: string, lang: string): Promise<SponsorTreeNode[]> {
  const rows = await readAllRows((offset, limit) => runQuery<Record<string, unknown>>(PLAY.sponsorTree,
    { params: { handle: handle.toLowerCase(), at, lang }, offset, limit }))
  return rows.flatMap((row): SponsorTreeNode[] => {
    const state = treeStateOf(row.state)
    if (typeof row.publicationId !== 'string' || !state) return []
    const requiredNames: Record<string, string> = {}
    const certifications: SponsorTreeNode['certifications'] = []
    let raw: unknown = row.prerequisites
    if (typeof raw === 'string') {
      try {
        raw = JSON.parse(raw)
      } catch {
        raw = []
      }
    }
    if (Array.isArray(raw)) {
      for (const item of raw) {
        if (!item || typeof item !== 'object') continue
        const p = item as Record<string, unknown>
        if (typeof p.certification === 'string' && p.certification) {
          certifications.push({ key: p.certification, held: p.held === true || p.held === 'true',
            roundPublicationId: typeof p.roundPublicationId === 'string' ? p.roundPublicationId : null,
            names: certificationNamesOf(p.certificationNames) })
        } else if (typeof p.requiredPublicationId === 'string' && typeof p.name === 'string') {
          requiredNames[p.requiredPublicationId] = p.name
        }
      }
    }
    return [{
      id: row.publicationId,
      name: String(row.name ?? ''),
      kind: (row.kind as QuizKind) ?? 'QUIZ',
      endAt: String(row.endAt ?? ''),
      maxReward: row.maxReward,
      live: row.live === true || row.live === 'true',
      state,
      requires: edgesOf(row.prerequisites),
      requiredNames,
      certifications,
    }]
  })
}
