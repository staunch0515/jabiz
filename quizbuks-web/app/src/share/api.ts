import { publicQuery } from '../qb/legal'
import type { ContentLanguage } from '../qb/api'
import type { QuizKind, RewardMethod } from '../qb/play'
import type { PublicProduct, PublicationProduct } from '../qb/products'
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
  maxReward: unknown
  rewardMethod: RewardMethod
}

/** A published page by its handle (typed in any case); null when there is none. */
export async function readPublicSponsorPage(handle: string): Promise<PublicSponsorPage | null> {
  const rows = await publicQuery<PublicSponsorPage>(SPONSOR_PAGE_TEMPLATE.page, { handle: handle.toLowerCase() }, 1)
  return rows[0] ?? null
}

/** The live public publications of the page with the handle, ending soonest first. */
export function readPagePublications(handle: string): Promise<PagePublication[]> {
  return publicQuery<PagePublication>(SPONSOR_PAGE_TEMPLATE.publications, { handle: handle.toLowerCase() }, 50)
}

/** A published page's listed products, in the sponsor's order. */
export function readPageProducts(handle: string): Promise<PublicProduct[]> {
  return publicQuery<PublicProduct>(SPONSOR_PAGE_TEMPLATE.products, { handle: handle.toLowerCase() }, 50)
}

/** A live publication's products, by question number (0: the quiz's) and in the sponsor's order. */
export async function readPublicationProducts(publicationId: string): Promise<PublicationProduct[]> {
  const rows = await publicQuery<PublicationProduct>(SPONSOR_PAGE_TEMPLATE.publicationProducts, { publicationId }, 200)
  return [...rows].sort((a, b) => Number(a.questionNo) - Number(b.questionNo) || Number(a.seq) - Number(b.seq))
}
