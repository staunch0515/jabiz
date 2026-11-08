import { runProcess, runQuery } from '@jabiz/client'
import type { PublicationOutput } from '../publishing/api'

/*
 * F-S12 (docs/quizbuks/plans/impl/F-S12.md): the videos Sponsors share with each other, typed by hand after
 * `sharedvideo.SharedVideoProcesses`, `publishing.PublicationSharedVideos` and the templates `qb.sponsor.shared-videos`
 * (the catalog) and `qb.sponsor.my-shared-videos` (the provider's own). The provider shares a video link of their
 * library; their operator reviews it once; other Sponsors pick it for a draft publication.
 */

export const SHARED_VIDEO_PROCESS = {
  share: 'QB_SHARED_VIDEO_SHARE',
  withdraw: 'QB_SHARED_VIDEO_WITHDRAW',
  set: 'QB_PUBLICATION_SHARED_VIDEO_SET',
} as const

export const SHARED_VIDEO_TEMPLATE = {
  catalog: 'qb.sponsor.shared-videos',
  mine: 'qb.sponsor.my-shared-videos',
} as const

export const CATALOG_QUERY = ['qb', 'shared-videos', 'catalog'] as const
export const MY_SHARED_QUERY = ['qb', 'shared-videos', 'mine'] as const
export const sharedVideoQuery = (sharedVideoId: string) => ['qb', 'shared-videos', 'one', sharedVideoId] as const

/** A shared video's states (`QbSharedVideos.STATUSES`). */
export type SharedVideoStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'TAKEN_DOWN'
export const SHARED_VIDEO_STATUSES: readonly SharedVideoStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN',
  'TAKEN_DOWN']

/** A video of the catalog (or the one a draft picked, whatever became of it). */
export interface SharedVideo {
  sharedVideoId: string
  providerId: string
  providerCompany: string | null
  /** The title in each language of the platform. */
  titles: Record<string, string>
  url: string
  durationSec: number
  vertical: boolean
  topics: string[]
  status: SharedVideoStatus
  approvedAt: string | null
}

/** One of the provider's own shares. */
export interface MySharedVideo extends Omit<SharedVideo, 'providerId' | 'providerCompany'> {
  assetId: string
  sharedAt: string | null
  rejectReason: string | null
  withdrawnAt: string | null
  takenDownAt: string | null
  takedownReason: string | null
  /** How many publications (any Sponsor's) use it now. */
  usesRunning: number
}

export interface ShareInput {
  assetId: string
  titleZh: string
  titleJa: string
  titleEn: string
  topics: string[]
  vertical: boolean
}

export interface ShareOutput {
  sharedVideoId: string
  status: SharedVideoStatus
  approval: string | null
  approvalRequestId: string | null
}

type Row = Record<string, unknown>
const text = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const bool = (value: unknown): boolean => value === true || value === 'true'

/** The titles as the template gives them (the JSON text of the column). */
export function titlesOf(value: unknown): Record<string, string> {
  if (value && typeof value === 'object') return value as Record<string, string>
  if (typeof value !== 'string' || value === '') return {}
  try {
    const parsed: unknown = JSON.parse(value)
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, string>) : {}
  } catch {
    return {}
  }
}

/** The title in the reader's language, else English, else any. */
export function titleIn(titles: Record<string, string>, language: string): string {
  const short = language.split('-')[0]
  return titles[short] || titles.en || Object.values(titles).find((title) => !!title) || ''
}

const topicsOf = (value: unknown): string[] =>
  typeof value === 'string' && value !== '' ? value.split(',').filter((code) => code !== '') : []

function videoOf(row: Row): SharedVideo {
  return {
    sharedVideoId: String(row.sharedVideoId ?? ''),
    providerId: String(row.providerId ?? ''),
    providerCompany: text(row.providerCompany),
    titles: titlesOf(row.title),
    url: String(row.url ?? ''),
    durationSec: Number(row.durationSec ?? 0),
    vertical: bool(row.vertical),
    topics: topicsOf(row.topics),
    status: (text(row.status) ?? 'APPROVED') as SharedVideoStatus,
    approvedAt: text(row.approvedAt),
  }
}

export interface CatalogFilter {
  q?: string
  topic?: string | null
  vertical?: boolean | null
}

/** The catalog: approved videos, newest first, searched by title or provider, by topic and orientation. */
export async function readCatalog(filter: CatalogFilter, limit = 50): Promise<SharedVideo[]> {
  const params: Record<string, unknown> = {}
  if (filter.q && filter.q.trim() !== '') params.q = filter.q.trim()
  if (filter.topic) params.topic = filter.topic
  if (filter.vertical != null) params.vertical = filter.vertical
  const page = await runQuery<Row>(SHARED_VIDEO_TEMPLATE.catalog, { params, limit })
  return page.items.map(videoOf)
}

/** The video a draft picked, whatever its state now; null when it is gone. */
export async function readSharedVideo(sharedVideoId: string): Promise<SharedVideo | null> {
  const page = await runQuery<Row>(SHARED_VIDEO_TEMPLATE.catalog, { params: { sharedVideoId }, limit: 1 })
  return page.items.length === 0 ? null : videoOf(page.items[0])
}

/** The provider's own shares, newest first. */
export async function readMySharedVideos(): Promise<MySharedVideo[]> {
  const page = await runQuery<Row>(SHARED_VIDEO_TEMPLATE.mine, { limit: 200 })
  return page.items.map((row) => ({
    ...videoOf(row),
    assetId: String(row.assetId ?? ''),
    sharedAt: text(row.sharedAt),
    rejectReason: text(row.rejectReason),
    withdrawnAt: text(row.withdrawnAt),
    takenDownAt: text(row.takenDownAt),
    takedownReason: text(row.takedownReason),
    usesRunning: Number(row.usesRunning ?? 0),
  }))
}

export const shareVideo = (input: ShareInput) => runProcess<ShareOutput>(SHARED_VIDEO_PROCESS.share, { ...input })

export const withdrawSharedVideo = (sharedVideoId: string) =>
  runProcess<ShareOutput>(SHARED_VIDEO_PROCESS.withdraw, { sharedVideoId })

/** A draft's pick (none: no shared video), at the revision the page edited. */
export const setPublicationSharedVideo = (publicationId: string, baseRevision: number | null,
  sharedVideoId: string | null) =>
  runProcess<PublicationOutput>(SHARED_VIDEO_PROCESS.set, { publicationId, baseRevision, sharedVideoId })

/** Whether a share still stands (in review or shared): the asset is not shared again until it is withdrawn. */
export const standing = (status: SharedVideoStatus) => status === 'PENDING' || status === 'APPROVED'
