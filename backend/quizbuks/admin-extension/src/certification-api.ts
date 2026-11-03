import { api, runProcess, runQuery, unwrap } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { CERTIFICATION_PROCESS, DATASET, TEMPLATE } from './ids'

/*
 * Certifications (backend `certification.CertificationProcesses`, docs/quizbuks/plans/Q69-certifications.md), typed by
 * hand after the records. An operator (or headquarters) makes publications of its official publisher certification
 * rounds, closes them, and grants or revokes a certification by hand with a reason; headquarters names the official
 * publishers. Whether someone holds a certification is the server's alone: the page only shows the counts it gives.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const bool = (value: unknown): boolean => value === true || value === 'true'

/** A UUID as typed (a publication's id). */
export const isUuid = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)

/** `RoundRow`: a round with the passes recorded on it (`holders`); a closed one counts only those before closing. */
export interface Round {
  roundId: string
  publicationId: string
  publicationName: string | null
  certification: string
  certificationVersion: number
  addedAt: string | null
  closedAt: string | null
  holders: number
}

/** `HandRow`: a grant or a revocation made by hand, with its reason. */
export interface HandRecord {
  recordId: string
  userId: string
  certification: string
  kind: 'GRANTED' | 'REVOKED'
  reason: string | null
  actor: string | null
  owner: string | null
  at: string | null
  validUntil: string | null
}

/** `Overview`: the caller's (operator's, or headquarters') rounds and records by hand, newest first. */
export interface CertificationOverview {
  owner: string
  rounds: Round[]
  byHand: HandRecord[]
}

/** `RoundOutput`. */
export interface RoundOutput {
  roundId: string
  publicationId: string
  certification: string
  certificationVersion: number
  closedAt: string | null
}

/** `GrantOutput`. */
export interface GrantOutput {
  recordId: string
  kind: 'GRANTED' | 'REVOKED'
  validUntil: string | null
}

export const CERTIFICATIONS_KEY = ['qb-ext', 'certifications'] as const

export const readOverview = () => runProcess<CertificationOverview>(CERTIFICATION_PROCESS.overview, {})

export const addRound = (publicationId: string, certification: string) =>
  runProcess<RoundOutput>(CERTIFICATION_PROCESS.roundAdd, { publicationId, certification })

export const closeRound = (roundId: string) => runProcess<RoundOutput>(CERTIFICATION_PROCESS.roundClose, { roundId })

/** A grant or revocation by hand; the reason is required (at most 500 characters). */
export const recordByHand = (kind: 'grant' | 'revoke',
  input: { userId: string; certification: string; reason: string }) =>
  runProcess<GrantOutput>(kind === 'grant' ? CERTIFICATION_PROCESS.grant : CERTIFICATION_PROCESS.revoke, { ...input })

/** A row of the official publishers' dataset (`qb.certification.admin`); `owner` is an operator or `direct`. */
export interface OfficialPublisher {
  userId: string
  owner: string
  active: boolean
  setBy: string | null
  setAt: string | null
}

export const OFFICIAL_KEY = [...CERTIFICATIONS_KEY, 'official'] as const

/** A page of the official publishers. */
export async function readOfficialPublishers(offset: number, limit: number): Promise<Page<OfficialPublisher>> {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: DATASET.officialPublisher } },
    body: { offset, limit, filters: [] },
  }))
  return {
    items: (answer.items ?? []).map((item) => {
      const row = (item.attributes ?? {}) as Row
      return { userId: String(row.userId ?? ''), owner: String(row.owner ?? ''), active: bool(row.active),
        setBy: str(row.setBy), setAt: str(row.setAt) }
    }),
  }
}

/** `OfficialInput`: an empty operator is headquarters' own (direct) publisher. */
export const setOfficialPublisher = (input: { userId: string; operatorId: string | null; active: boolean }) =>
  runProcess<{ userId: string; owner: string; active: boolean }>(CERTIFICATION_PROCESS.officialSet, { ...input })

/** Q88a: a row of `qb.admin.sponsor-certifications`: a sponsor's own certification, whose, how many rounds and holders. */
export interface SponsorCertificationRow {
  certificationId: string
  key: string
  sponsorId: string
  company: string | null
  /** The name by language, as the sponsor wrote it. */
  name: Record<string, string>
  validityDays: number
  certificationVersion: number
  status: string
  createdAt: string
  rounds: number
  holders: number
}

export const SPONSOR_CERTIFICATION_SORTS: Readonly<Record<string, string>> = { createdAt: 'createdAt', company: 'company' }

function namesOf(value: unknown): Record<string, string> {
  let raw: unknown = value
  if (typeof raw === 'string') {
    try {
      raw = JSON.parse(raw)
    } catch {
      return {}
    }
  }
  if (!raw || typeof raw !== 'object') return {}
  return Object.fromEntries(Object.entries(raw as Record<string, unknown>)
    .filter(([, text]) => typeof text === 'string' && text.trim()) as [string, string][])
}

/** The sponsors' certifications, a page at a time; `q` narrows to sponsors whose company name contains it. */
export async function readSponsorCertifications(q: string, offset: number, limit: number,
  sorts?: SortSpec[]): Promise<Page<SponsorCertificationRow>> {
  const page = await runQuery<Row>(TEMPLATE.sponsorCertifications,
    { params: { at: new Date().toISOString(), ...(q ? { q } : {}) }, offset, limit, sorts })
  return {
    items: page.items.map((row): SponsorCertificationRow => ({
      certificationId: String(row.certificationId),
      key: String(row.key ?? ''),
      sponsorId: String(row.sponsorId ?? ''),
      company: str(row.company),
      name: namesOf(row.name),
      validityDays: Number(row.validityDays ?? 0),
      certificationVersion: Number(row.certificationVersion ?? 0),
      status: String(row.status ?? ''),
      createdAt: String(row.createdAt ?? ''),
      rounds: Number(row.rounds ?? 0),
      holders: Number(row.holders ?? 0),
    })),
    total: page.total,
  }
}
