import { fileNameOf, runProcess, sessionFetch, toApiError } from '@jabiz/client'
import { certificationNamesOf } from '../qb/certifications'
import { publicQuery, serverNow, type I18nText } from '../qb/legal'
import { hrefOf } from '../qb/router'
import { CERTIFICATE_LAYOUT, CERTIFICATE_PROCESS, CERTIFICATE_TEMPLATE } from './ids'

/*
 * The certificates' calls (Q77), typed by hand after `CertificateProcesses` and the public templates; the end-to-end
 * test `certificates.spec.ts` runs them against the server.
 */

/** A certificate of the caller's still valid (`CertificateProcesses.Held`; F-S2c2b: no certification is held any more). */
export interface HeldCertification {
  certification: string
  heldFrom: string
  heldUntil: string
  certificateId: string
  verifyKey: string
  /** Q88a: a sponsor's own certification's names by language and the sponsor's name; null for the catalog's. */
  name?: I18nText | null
  sponsorName?: string | null
}

export async function readMyCertifications(): Promise<HeldCertification[]> {
  const output = await runProcess<{ held?: HeldCertification[] }>(CERTIFICATE_PROCESS.mine, {})
  return output.held ?? []
}

export interface IssuedCertificate {
  certificateId: string
  verifyKey: string
  heldUntil: string
  issued: boolean
}

/** The certificate languages; another interface language issues it in English. */
export function certificateLanguage(language: string | undefined): 'en' | 'zh' | 'ja' {
  const code = (language ?? '').slice(0, 2)
  return code === 'zh' || code === 'ja' ? code : 'en'
}

/** The PDF of a certificate exactly as issued, handed to the browser. */
export async function downloadCertificate(certificateId: string, verifyKey: string) {
  const runs = await sessionFetch(`/api/documents/runs?layout=${encodeURIComponent(CERTIFICATE_LAYOUT)}`
    + `&subject=${encodeURIComponent(certificateId)}&limit=1`)
  if (!runs.ok) throw await toApiError(runs)
  const [run] = (await runs.json()) as { runId: string }[]
  if (!run) throw new Error('No document of the certificate')
  const response = await sessionFetch(`/api/documents/runs/${encodeURIComponent(run.runId)}/pdf`)
  if (!response.ok) throw await toApiError(response)
  const url = URL.createObjectURL(await response.blob())
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileNameOf(response.headers.get('Content-Disposition')) ?? `${verifyKey}.pdf`
    link.rel = 'noopener'
    link.click()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
}

/** The app's page that verifies a certificate (the address printed on it, below the app's root). */
export function verifyPath(key: string): string {
  return `/verify/${encodeURIComponent(key)}`
}

/** A taker's public certification page. */
export function takerPagePath(handle: string): string {
  return `/u/${encodeURIComponent(handle)}`
}

/** A certificate as its verify page shows it. */
export interface VerifiedCertificate {
  certification: string
  certificationVersion: number
  /** Q88a: a sponsor's own certification's names and the sponsor as the issuer; null for the catalog's. */
  certificationNames: I18nText | null
  issuerName: string | null
  holderName: string
  heldFrom: string
  validUntil: string
  issuedAt: string
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
  certification: string
  certificationNames: I18nText | null
  issuerName: string | null
  holderName: string
  heldFrom: string
  validUntil: string
  verifyKey: string
}

/** The issued, unrevoked certificates of a page that is on (none when it is off or unknown), still valid at `now`. */
export async function readTakerPage(handle: string, now: number = serverNow()): Promise<PublicCertificate[]> {
  const rows = await publicQuery<PublicCertificate>(CERTIFICATE_TEMPLATE.takerPage, { handle: handle.toLowerCase() },
    20)
  return rows.filter((row) => Date.parse(row.validUntil) > now).map(withNames)
}

/** The names column as the template gives it (a JSON text) read into an object. */
function withNames<T extends { certificationNames: I18nText | null }>(row: T): T {
  return { ...row, certificationNames: certificationNamesOf(row.certificationNames) }
}

export type CertificateState = 'VALID' | 'EXPIRED' | 'REVOKED'

/** A certificate's state: revoked after its holding began, else valid until its end (by the server's clock as known). */
export function stateOf(certificate: VerifiedCertificate, now: number = serverNow()): CertificateState {
  if (certificate.revokedAt) return 'REVOKED'
  return Date.parse(certificate.validUntil) > now ? 'VALID' : 'EXPIRED'
}

/** The full address of an app page (for copying), below the app's root. */
export function appUrl(path: string, origin: string = window.location.origin): string {
  return `${origin}${hrefOf(path)}`
}
