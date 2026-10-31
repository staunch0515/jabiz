import { api, runProcess, unwrap } from '@jabiz/client'
import type { ContentLanguage } from '../qb/api'
import { SPONSOR_PAGE } from './ids'

/*
 * The sponsor's page as the console edits it (Q60a): types after `SponsorPageProcesses`'s records, and its calls. No
 * logo and no story since Q65b: the page shows the onboarding's avatar and introduction.
 */

export type SponsorPageStatus = 'DRAFT' | 'PENDING' | 'PUBLISHED' | 'REJECTED' | 'TAKEN_DOWN'

/** The link name: 3 to 30 lowercase letters, digits or inner hyphens (`QbSponsorPages.HANDLE_PATTERN`). */
export const HANDLE_PATTERN = /^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$/
export const HANDLE_LENGTH = 30
export const HEADLINE_LENGTH = 120
export const WEBSITE_LENGTH = 300

/** The sponsor's own page as stored. */
export interface MySponsorPage {
  handle: string
  language: ContentLanguage
  headline: string
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
