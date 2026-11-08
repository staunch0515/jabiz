import { runQuery } from '@jabiz/client'
import { useQuery } from '@tanstack/react-query'
import { readMySponsor } from '../qb/api'
import { PROFILE_TEMPLATE } from './ids'

/** The query of the sponsor's onboarding, which the routes, the dashboard, the preview and the hub read. */
export const SPONSOR_QUERY = ['qb', 'sponsor'] as const

/** The sponsor's onboarding, or null before applying. */
export function useMySponsor() {
  return useQuery({ queryKey: SPONSOR_QUERY, queryFn: readMySponsor })
}

/** The operator that looks after the Sponsor (F-S1b, `qb.sponsor.operator`). */
export interface YourOperator {
  operatorId: string
  name: string
  language: string | null
  contactName: string
  contactEmail: string
  own: boolean
  assignedAt: string | null
}

export const YOUR_OPERATOR_QUERY = ['qb', 'sponsor', 'operator'] as const

/** The Sponsor's operator, or null while none looks after them. */
export async function readYourOperator(): Promise<YourOperator | null> {
  const page = await runQuery<Record<string, unknown>>(PROFILE_TEMPLATE.operator, { limit: 1 })
  const row = page.items[0]
  if (!row) return null
  return {
    operatorId: String(row.operatorId), name: String(row.name ?? ''),
    language: row.language == null ? null : String(row.language),
    contactName: String(row.contactName ?? ''), contactEmail: String(row.contactEmail ?? ''),
    own: row.own === true || row.own === 'true', assignedAt: (row.assignedAt as string | null) ?? null,
  }
}
