import { sessionFetch } from './client'
import { saveResponse } from './reports'
import type { components } from './schema'

/** Retention, legal holds and the open-format export (docs/design/21-audit-retention.md sections 3 and 4). */
export type RetentionReport = components['schemas']['RetentionReportResult']
export type RetentionPolicyStatus = components['schemas']['RetentionPolicyStatus']

export interface ExportRequest {
  datasets: string[]
  asOf?: string
  reports?: boolean
  reportsFrom?: string
  reportsTo?: string
}

/** Downloads the export (POST /api/exports/data) as the ZIP the server names. */
export async function exportData(request: ExportRequest): Promise<void> {
  const response = await sessionFetch('/api/exports/data', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  await saveResponse(response, 'jabiz-export.zip')
}

/** The platform's legal hold dataset and processes (the pages are generated from them). */
export const LEGAL_HOLD_DATASET = 'urn:jabiz:dataset:platform:SysLegalHold'
export const LEGAL_HOLD_PLACE = 'LEGAL_HOLD_PLACE'
export const LEGAL_HOLD_RELEASE = 'LEGAL_HOLD_RELEASE'
