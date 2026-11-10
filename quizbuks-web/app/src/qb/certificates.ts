import { fileNameOf, runProcess, sessionFetch, toApiError } from '@jabiz/client'
import type { TFunction } from 'i18next'
import { certificationName, certificationNamesOf } from './certifications'
import { pickText, type I18nText } from './legal'

/*
 * The caller's certificates (F-S5b, docs/quizbuks/plans/impl/F-S5.md section 3), typed by hand after
 * `CertificateProcesses`: a Taker's in the app, a Sponsor's in the Sponsor app (the same list, the same actions, their
 * own processes). The same file in both SPAs.
 */

/** Where a certificate comes from (`CertificateProcesses.Certificate.source`): a lit node's, or Q77's certification. */
export const SOURCE_NODE = 'NODE'
export const SOURCE_CERTIFICATION = 'CERTIFICATION'

/** The processes of one audience: the list, the PDF and the sharing. */
export interface CertificateCalls {
  mine: string
  issue: string
  share: string
}

export const TAKER_CERTIFICATES: CertificateCalls = {
  mine: 'QB_MY_CERTIFICATIONS',
  issue: 'QB_CERTIFICATE_ISSUE',
  share: 'QB_CERTIFICATE_SHARE_SET',
}

export const SPONSOR_CERTIFICATES: CertificateCalls = {
  mine: 'QB_SPONSOR_MY_CERTIFICATES',
  issue: 'QB_SPONSOR_CERTIFICATE_ISSUE',
  share: 'QB_SPONSOR_CERTIFICATE_SHARE_SET',
}

/** The layout of a Q77 certificate's PDF; documents of it have the certificate as their subject. */
export const LEGACY_CERTIFICATE_LAYOUT = 'qb.certificate'

/** One certificate of the caller's (`CertificateProcesses.Certificate`). */
export interface Certificate {
  source: string
  certificateId: string
  verifyKey: string
  /** The name by language (a node's; a Sponsor's own certification's; none for the catalog's, named by the texts). */
  names: I18nText | null
  issuerName: string | null
  /** When the node was lit (Q77: when the holding began). */
  litAt: string
  /** When the lighting was revoked: the certificate is no longer valid. */
  revokedAt: string | null
  shared: boolean
  /** The PDF once issued (a node's). */
  runId: string | null
  /** Q77's end of the holding; a node's certificate has none. */
  heldUntil: string | null
  certification: string | null
}

export async function readCertificates(calls: CertificateCalls): Promise<Certificate[]> {
  const output = await runProcess<{ certificates?: Certificate[] }>(calls.mine, {})
  return (output.certificates ?? []).map((row) => ({ ...row, names: certificationNamesOf(row.names) }))
}

/** Turns the sharing of a node's certificate off or on; a certificate's key stays the same. */
export function setShared(calls: CertificateCalls, certificateId: string, shared: boolean) {
  return runProcess<{ certificateId: string; shared: boolean }>(calls.share, { certificateId, shared })
}

/** The certificate languages; another interface language issues it in English. */
export function certificateLanguage(language: string | undefined): 'en' | 'zh' | 'ja' {
  const code = (language ?? '').slice(0, 2)
  return code === 'zh' || code === 'ja' ? code : 'en'
}

/** The certificate's name in the reader's language (Q77's from the catalog's texts, a node's from its names). */
export function certificateTitle(c: Pick<Certificate, 'source' | 'certification' | 'names'>, language: string,
  tQb: TFunction): string {
  if (c.source === SOURCE_CERTIFICATION && c.certification) return certificationName(c.certification, c.names, language, tQb)
  return pickText(c.names, language).text
}

/** A certificate whose lighting was revoked can be neither shared nor issued as a PDF for the first time. */
export const isRevoked = (c: Pick<Certificate, 'revokedAt'>) => c.revokedAt != null

/**
 * Whether a download can be offered: a node's certificate is issued as a PDF on the first download, which a revoked one
 * is refused (one issued before stays downloadable); Q77's PDF is the one issued back then.
 */
export const canDownload = (c: Pick<Certificate, 'source' | 'revokedAt' | 'runId'>) =>
  c.source !== SOURCE_NODE || !isRevoked(c) || c.runId != null

/** The PDF of a certificate exactly as issued, handed to the browser; a node's is issued by the first call. */
export async function downloadCertificate(calls: CertificateCalls, c: Certificate, language: string): Promise<string | null> {
  let runId = c.runId
  let issued: string | null = null
  if (c.source === SOURCE_NODE) {
    if (!runId) {
      const output = await runProcess<{ runId: string }>(calls.issue,
        { certificateId: c.certificateId, language: certificateLanguage(language) })
      runId = output.runId
      issued = runId
    }
  } else {
    const runs = await sessionFetch(`/api/documents/runs?layout=${encodeURIComponent(LEGACY_CERTIFICATE_LAYOUT)}`
      + `&subject=${encodeURIComponent(c.certificateId)}&limit=1`)
    if (!runs.ok) throw await toApiError(runs)
    const [run] = (await runs.json()) as { runId: string }[]
    if (!run) throw new Error('No document of the certificate')
    runId = run.runId
  }
  const response = await sessionFetch(`/api/documents/runs/${encodeURIComponent(runId!)}/pdf`)
  if (!response.ok) throw await toApiError(response)
  const url = URL.createObjectURL(await response.blob())
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileNameOf(response.headers.get('Content-Disposition')) ?? `${c.verifyKey}.pdf`
    link.rel = 'noopener'
    link.click()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
  return issued
}
