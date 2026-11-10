import { i18n } from '@jabiz/client'
import type { TFunction } from 'i18next'
import { certificationName, certificationNamesOf } from '../qb/certifications'
import { SOURCE_NODE } from '../qb/certificates'
import { pickText, publicQuery, serverNow, type I18nText } from '../qb/legal'
import { hrefOf } from '../qb/router'
import { CERTIFICATE_TEMPLATE } from './ids'

/*
 * The public certificate pages' calls (Q77, F-S5b), typed by hand after the public templates; the end-to-end test
 * `certificates.spec.ts` runs them against the server. The caller's own list is `qb/certificates.ts`.
 */

/** The app's page that verifies a certificate (the address printed on it, below the app's root). */
export function verifyPath(key: string): string {
  return `/verify/${encodeURIComponent(key)}`
}

/** A taker's public certification page. */
export function takerPagePath(handle: string): string {
  return `/u/${encodeURIComponent(handle)}`
}

/**
 * A certificate as its verify page shows it (`qb.public.certificate`): a lit node's (names `certificateNames`, no end), or
 * Q77's (the certification and its holding). One its holder stopped sharing has nothing but `shared: false`.
 */
export interface VerifiedCertificate {
  source: string
  shared: boolean
  certification: string | null
  certificationVersion: number | null
  /** Q88a: a sponsor's own certification's names; null for the catalog's. */
  certificationNames: I18nText | null
  /** A lit node's certificate's names by language. */
  certificateNames: I18nText | null
  /** The issuer as frozen on a node's certificate; for Q77's the Sponsor whose certification it is. */
  issuerName: string | null
  holderName: string | null
  heldFrom: string | null
  /** Q77's end of the holding; a node's certificate has none. */
  validUntil: string | null
  issuedAt: string | null
  revokedAt: string | null
}

/** The certificate of a key (as printed, in any case); null when there is none. */
export async function readVerifiedCertificate(key: string): Promise<VerifiedCertificate | null> {
  const rows = await publicQuery<VerifiedCertificate>(CERTIFICATE_TEMPLATE.verify, { key: key.toLowerCase() }, 1)
  return rows[0] ? withNames(rows[0]) : null
}

/** A certificate on a public certification page. */
export interface PublicCertificate {
  handle: string
  source: string
  certification: string | null
  certificationNames: I18nText | null
  certificateNames: I18nText | null
  issuerName: string | null
  holderName: string
  heldFrom: string
  validUntil: string | null
  verifyKey: string
}

/**
 * The shared, unrevoked certificates of a page that is on (none when it is off or unknown): a node's, and Q77's still
 * valid at `now` (a node's has no end).
 */
export async function readTakerPage(handle: string, now: number = serverNow()): Promise<PublicCertificate[]> {
  const rows = await publicQuery<PublicCertificate>(CERTIFICATE_TEMPLATE.takerPage, { handle: handle.toLowerCase() },
    20)
  return rows.filter((row) => row.validUntil == null || Date.parse(row.validUntil) > now).map(withNames)
}

/** The names columns as the template gives them (JSON texts) read into objects. */
function withNames<T extends { certificationNames: I18nText | null; certificateNames: I18nText | null }>(row: T): T {
  return { ...row, certificationNames: certificationNamesOf(row.certificationNames),
    certificateNames: certificationNamesOf(row.certificateNames) }
}

/** What a certificate is called, in the reader's language: a node's by its names, Q77's by its certification. */
export function nameOf(c: Pick<VerifiedCertificate, 'source' | 'certification' | 'certificationNames' | 'certificateNames'>,
  tQb: TFunction, language: string = i18n.language): string {
  if (c.source === SOURCE_NODE || !c.certification) return pickText(c.certificateNames, language).text
  return certificationName(c.certification, c.certificationNames, language, tQb)
}

export type CertificateState = 'VALID' | 'EXPIRED' | 'REVOKED' | 'DISABLED'

/**
 * A certificate's state: its holder turned the link off (nothing else is known then), else revoked, else valid
 * (a node's certificate has no end) or, for Q77's, until its end (by the server's clock as known).
 */
export function stateOf(certificate: VerifiedCertificate, now: number = serverNow()): CertificateState {
  if (!certificate.shared) return 'DISABLED'
  if (certificate.revokedAt) return 'REVOKED'
  if (certificate.validUntil == null) return 'VALID'
  return Date.parse(certificate.validUntil) > now ? 'VALID' : 'EXPIRED'
}

/** The full address of an app page (for copying), below the app's root. */
export function appUrl(path: string, origin: string = window.location.origin): string {
  return `${origin}${hrefOf(path)}`
}
