import type { components } from './schema'

/** The audit trail (docs/design/21-audit-retention.md section 1). */
export type AuditRecord = components['schemas']['AuditRecordEntry']
export type AuditRecordPage = components['schemas']['AuditRecordPage']
export type AuditFieldChange = components['schemas']['AuditFieldChange']

/** The filters of GET /api/audit/records that the page takes from its URL. */
export const AUDIT_FILTERS = ['entityType', 'entityId', 'actorId', 'processName', 'field'] as const
export type AuditFilter = (typeof AUDIT_FILTERS)[number]
