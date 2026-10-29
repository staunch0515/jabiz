import { useSearchParams } from 'react-router'
import { useAuth } from '@jabiz/admin'
import { PERMISSION } from './ids'
import { DIRECT, type Scope } from './prospect-api'

/**
 * Whose prospecting a page shows: headquarters (`qb.admin.prospect`) reads any owner's, `?owner=` in the address and
 * its own (`direct`) by default; an operator's member reads its operator's. The server decides whose a process acts
 * on, whatever the page shows: headquarters' own.
 */
export function useProspectScope(): Scope {
  const { can } = useAuth()
  const [search] = useSearchParams()
  const admin = can(PERMISSION.adminProspect)
  return { admin, owner: admin ? search.get('owner')?.trim() || DIRECT : '' }
}
