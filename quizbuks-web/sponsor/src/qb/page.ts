import { api, runProcess, unwrap } from '@jabiz/client'
import { publicQuery } from './legal'
import type { ContentLanguage } from './api'
import type { QuizKind, RewardMethod } from './play'

/*
 * A sponsor's page (docs/quizbuks/plans/Q60-sponsor-page.md): the console edits it (`page.SponsorPageProcesses`),
 * the app shows it to anybody at `/s/<handle>` once published (templates `queries/qb/public/sponsor-page*.sql`).
 */

export const SPONSOR_PAGE = {
  save: 'QB_SPONSOR_PAGE_SAVE',
  submit: 'QB_SPONSOR_PAGE_SUBMIT',
  unpublish: 'QB_SPONSOR_PAGE_UNPUBLISH',
  /** The sponsor's own page, read only. */
  myDataset: 'urn:jabiz:dataset:me:QbSponsorPage',
  /** The page's logo and cover: public once the page is. */
  imagePolicy: 'qb.sponsor-page-image',
  publicTemplate: 'qb.public.sponsor-page',
  publicationsTemplate: 'qb.public.sponsor-page-publications',
} as const

export type SponsorPageStatus = 'DRAFT' | 'PENDING' | 'PUBLISHED' | 'REJECTED' | 'TAKEN_DOWN'

/** The link name: 3 to 30 lowercase letters, digits or inner hyphens (`QbSponsorPages.HANDLE_PATTERN`). */
export const HANDLE_PATTERN = /^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$/
export const HANDLE_LENGTH = 30
export const HEADLINE_LENGTH = 120
export const STORY_LENGTH = 5_000
export const WEBSITE_LENGTH = 300

/** The sponsor's own page as stored. */
export interface MySponsorPage {
  handle: string
  language: ContentLanguage
  headline: string
  story: string | null
  logo: string | null
  cover: string | null
  website: string | null
  status: SponsorPageStatus
  rejectReason: string | null
  takedownReason: string | null
  takenDownAt: string | null
  publishedAt: string | null
  /** Taken down and not published since: the next publication waits for a person's review. */
  reviewRequired: boolean
  revision: number
}

/** `SponsorPageProcesses.SaveInput`. */
export interface SponsorPageInput {
  handle: string
  language: ContentLanguage
  headline: string
  story: string | null
  logo: string | null
  cover: string | null
  website: string | null
  baseRevision: number | null
}

/** `SponsorPageProcesses.PageOutput`. */
export interface SponsorPageOutput {
  userId: string
  handle: string
  status: SponsorPageStatus
  revision: number
  /** `PENDING`, `APPROVED` or `NOT_REQUIRED` when the process asked for a review. */
  approval: string | null
  approvalRequestId: string | null
}

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

const text = (value: unknown) => (value == null || value === '' ? null : String(value))

/** The caller's page, or null before the first save. */
export async function readMySponsorPage(): Promise<MySponsorPage | null> {
  const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: SPONSOR_PAGE.myDataset } },
    body: { limit: 1 },
  }))
  const row = (page.items ?? [])[0]?.attributes as Record<string, unknown> | undefined
  if (!row) return null
  return {
    handle: String(row.handle ?? ''),
    language: row.language as ContentLanguage,
    headline: String(row.headline ?? ''),
    story: text(row.story),
    logo: text(row.logo),
    cover: text(row.cover),
    website: text(row.website),
    status: row.status as SponsorPageStatus,
    rejectReason: text(row.rejectReason),
    takedownReason: text(row.takedownReason),
    takenDownAt: text(row.takenDownAt),
    publishedAt: text(row.publishedAt),
    reviewRequired: row.reviewRequired === true,
    revision: Number(row.revision) || 0,
  }
}

export const saveSponsorPage = (input: SponsorPageInput) =>
  runProcess<SponsorPageOutput>(SPONSOR_PAGE.save, { ...input })

export const submitSponsorPage = (baseRevision: number | null) =>
  runProcess<SponsorPageOutput>(SPONSOR_PAGE.submit, { baseRevision })

export const unpublishSponsorPage = (baseRevision: number | null) =>
  runProcess<SponsorPageOutput>(SPONSOR_PAGE.unpublish, { baseRevision })

/** A published page by its handle (typed in any case); null when there is none. */
export async function readPublicSponsorPage(handle: string): Promise<PublicSponsorPage | null> {
  const rows = await publicQuery<PublicSponsorPage>(SPONSOR_PAGE.publicTemplate, { handle: handle.toLowerCase() }, 1)
  return rows[0] ?? null
}

/** The live public publications of the page with the handle, ending soonest first. */
export function readPagePublications(handle: string): Promise<PagePublication[]> {
  return publicQuery<PagePublication>(SPONSOR_PAGE.publicationsTemplate, { handle: handle.toLowerCase() }, 50)
}

/** The app's path of a sponsor's page. */
export function sponsorPagePath(handle: string): string {
  return `/s/${encodeURIComponent(handle)}`
}

/** The link to a sponsor's page anybody can open: the app's page, under this site's /taker/ (Q30). */
export function sponsorPageLink(handle: string, origin: string = window.location.origin): string {
  return `${origin}/taker${sponsorPagePath(handle)}`
}
