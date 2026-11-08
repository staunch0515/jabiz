import { runProcess } from '@jabiz/admin'
import { CERTIFICATION_PROCESS } from './ids'

/*
 * Certifications (backend `certification.CertificationProcesses`, docs/quizbuks/plans/Q69-certifications.md), typed by
 * hand after the records. An operator (or headquarters) makes publications of its official publisher certification
 * rounds, closes them, and grants or revokes a certification by hand with a reason; headquarters names the official
 * publishers. Whether someone holds a certification is the server's alone: the page only shows the counts it gives.
 */

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

/** F-S1b: an operator's overview, as the platform reads it (read only). */
export const readOverviewOf = (operatorId: string) =>
  runProcess<CertificationOverview>(CERTIFICATION_PROCESS.overviewOf, { operatorId })

export const addRound = (publicationId: string, certification: string) =>
  runProcess<RoundOutput>(CERTIFICATION_PROCESS.roundAdd, { publicationId, certification })

export const closeRound = (roundId: string) => runProcess<RoundOutput>(CERTIFICATION_PROCESS.roundClose, { roundId })

/** A grant or revocation by hand; the reason is required (at most 500 characters). */
export const recordByHand = (kind: 'grant' | 'revoke',
  input: { userId: string; certification: string; reason: string }) =>
  runProcess<GrantOutput>(kind === 'grant' ? CERTIFICATION_PROCESS.grant : CERTIFICATION_PROCESS.revoke, { ...input })

