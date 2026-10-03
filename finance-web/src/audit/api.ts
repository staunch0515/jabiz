import { ApiError, runProcess, sessionFetch } from '@jabiz/admin'

/** The finance backend's names (backend/finance, audit/AuditProcesses). */
export const PROCESSES = { package: 'FIN_AUDIT_PACKAGE' } as const

export const QUERIES = { manualEntries: 'finance.audit.manual_entries' } as const

export const PERMISSIONS = {
  package: 'fin.audit.package',
  /** The export of the package: the export itself and the issued reports (the datasets' reads the server checks). */
  export: ['data.export', 'report.archive.read'],
  manualEntries: 'fin.journal.read',
} as const

/** What the auditor asked for; the empty fields are left out. */
export interface PackageInput {
  request: string
  from: string
  to: string
  minAmount: string
  accessReviewAsOf?: string
  bankCode?: string
  statementDate?: string
}

export interface PackageReport {
  templateId: string
  runId: string
  contentHash: string
  rows: number
}

/** The body of POST /api/exports/data that packs exactly the package's reports. */
export interface ExportRequest {
  datasets: string[]
  reports: boolean
  reportsFrom: string
  reportsTo: string
}

export interface PackageOutput {
  request: string
  issuedTime: string
  reports: PackageReport[]
  export: ExportRequest
}

/** The package downloaded: its file name and the SHA-256 the exporter hands over outside the system. */
export interface Downloaded {
  fileName: string
  bytes: number
  sha256: string
}

/** Issues the request's reports in one operation (FIN_AUDIT_PACKAGE). */
export async function issuePackage(input: PackageInput, key: string): Promise<PackageOutput> {
  const given = Object.fromEntries(Object.entries(input).filter(([, v]) => v !== undefined && v !== ''))
  return runProcess<PackageOutput>(PROCESSES.package, given, { idempotencyKey: key })
}

export async function sha256(content: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', content)
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
}

/** Exports the package through the platform (POST /api/exports/data), saves it and answers its SHA-256. */
export async function downloadPackage(request: ExportRequest): Promise<Downloaded> {
  const response = await sessionFetch('/api/exports/data', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  if (!response.ok) {
    const problem = (await response.json().catch(() => undefined)) as Record<string, unknown> | undefined
    throw new ApiError(response.status, problem ?? { title: response.statusText })
  }
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const named = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1] ?? /filename="?([^";]+)"?/i.exec(disposition)?.[1]
  const fileName = named ? decodeURIComponent(named.trim()) : 'audit-package.zip'
  const blob = await response.blob()
  save(blob, fileName)
  return { fileName, bytes: blob.size, sha256: await sha256(await blob.arrayBuffer()) }
}

/** Saves the process's answer, which the offline check reads (verify-package.py --expect). */
export function saveAnswer(answer: PackageOutput) {
  save(new Blob([JSON.stringify(answer, null, 2)], { type: 'application/json' }), 'package.json')
}

function save(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.rel = 'noopener'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
