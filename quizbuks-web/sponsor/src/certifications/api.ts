import { runProcess, runQuery } from '@jabiz/client'
import { certificationNamesOf } from '../qb/certifications'
import type { I18nText } from '../qb/legal'
import type { PublicationOutput } from '../publishing/api'
import { CERTIFICATION_PROCESS, CERTIFICATION_TEMPLATE } from './ids'

/*
 * The sponsor's own certifications (Q88a), typed by hand after `SponsorCertificationProcesses` and the templates. A
 * publication of the sponsor's becomes a round of one in the wizard (a draft only), which passing it then gives; the
 * end-to-end test `sponsor-certification.spec.ts` runs these against the server.
 */

export const CERTIFICATIONS_PATH = '/certifications'
export const CERTIFICATIONS_QUERY = ['qb', 'sponsor-certifications'] as const
export const roundQuery = (publicationId: string | null) => ['qb', 'publication-round', publicationId] as const

export type CertificationStatus = 'ACTIVE' | 'RETIRED'

/** A row of `qb.sponsor.certifications`. */
export interface SponsorCertification {
  certificationId: string
  /** What rounds and prerequisites name it by (`sp:<id>`). */
  key: string
  name: I18nText
  description: I18nText | null
  validityDays: number
  certificationVersion: number
  status: CertificationStatus
  createdAt: string
  updatedAt: string
  rounds: number
  openRounds: number
  holders: number
}

type Row = Record<string, unknown>
const num = (value: unknown) => (typeof value === 'number' ? value : Number(value ?? 0)) || 0
const str = (value: unknown) => (value == null || value === '' ? null : String(value))

export async function readMyCertifications(at: string = new Date().toISOString()): Promise<SponsorCertification[]> {
  const page = await runQuery<Row>(CERTIFICATION_TEMPLATE.mine, { params: { at }, limit: 50 })
  return page.items.map((row): SponsorCertification => ({
    certificationId: String(row.certificationId),
    key: String(row.key ?? ''),
    name: certificationNamesOf(row.name) ?? {},
    description: certificationNamesOf(row.description),
    validityDays: num(row.validityDays),
    certificationVersion: num(row.certificationVersion),
    status: row.status === 'RETIRED' ? 'RETIRED' : 'ACTIVE',
    createdAt: String(row.createdAt ?? ''),
    updatedAt: String(row.updatedAt ?? ''),
    rounds: num(row.rounds),
    openRounds: num(row.openRounds),
    holders: num(row.holders),
  }))
}

/** `CreateInput` / `UpdateInput`: the texts by language (blank ones are dropped by the server). */
export interface CertificationInput {
  certificationId?: string
  name: I18nText
  description: I18nText
  validityDays: number
  /** Editing only: the rules changed, holders must pass again. */
  raiseVersion?: boolean
}

/** `SponsorCertificationProcesses.Output`. */
export interface CertificationOutput {
  certificationId: string
  key: string
  name: I18nText
  description: I18nText | null
  validityDays: number
  certificationVersion: number
  status: CertificationStatus
  updatedAt: string
}

export function saveCertification(input: CertificationInput): Promise<CertificationOutput> {
  const { certificationId, raiseVersion, ...rest } = input
  return certificationId
    ? runProcess<CertificationOutput>(CERTIFICATION_PROCESS.update, { certificationId, ...rest,
      raiseVersion: raiseVersion === true })
    : runProcess<CertificationOutput>(CERTIFICATION_PROCESS.create, rest)
}

export const retireCertification = (certificationId: string) =>
  runProcess<CertificationOutput>(CERTIFICATION_PROCESS.retire, { certificationId })

/** A row of `qb.sponsor.publication-round`: the round a publication is, with the sponsor's certification when it is one. */
export interface PublicationRound {
  roundId: string
  certification: string
  certificationVersion: number
  addedAt: string
  closedAt: string | null
  /** The sponsor's own certification the key names; null for the catalog's (an official publisher's round, Q69). */
  certificationId: string | null
  name: I18nText | null
  status: CertificationStatus | null
  currentVersion: number | null
}

export async function readPublicationRound(publicationId: string): Promise<PublicationRound | null> {
  const page = await runQuery<Row>(CERTIFICATION_TEMPLATE.round, { params: { publicationId }, limit: 1 })
  const row = page.items[0]
  if (!row) return null
  return {
    roundId: String(row.roundId),
    certification: String(row.certification ?? ''),
    certificationVersion: num(row.certificationVersion),
    addedAt: String(row.addedAt ?? ''),
    closedAt: str(row.closedAt),
    certificationId: str(row.certificationId),
    name: certificationNamesOf(row.name),
    status: row.status === 'RETIRED' ? 'RETIRED' : row.status === 'ACTIVE' ? 'ACTIVE' : null,
    currentVersion: row.currentVersion == null ? null : num(row.currentVersion),
  }
}

/** Makes a draft a round of the sponsor's certification (null: no round); the draft's next revision comes back. */
export const setPublicationRound = (publicationId: string, baseRevision: number | null, certificationId: string | null) =>
  runProcess<PublicationOutput>(CERTIFICATION_PROCESS.roundSet, { publicationId, baseRevision, certificationId })

/** Closes the round a submitted publication is: passes from then on do not count. */
export const closePublicationRound = (publicationId: string) =>
  runProcess<{ roundId: string; closedAt: string }>(CERTIFICATION_PROCESS.roundClose, { publicationId })
