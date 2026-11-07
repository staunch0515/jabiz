import { useSearchParams } from 'react-router'
import { useAuth } from '@jabiz/admin'
import { PERMISSION } from './ids'
import type { Scope } from './prospect-api'

/**
 * Whose prospecting a page shows: the platform (`qb.admin.prospect`) reads any operator's, the one in the address
 * (`?owner=`, none until one is chosen), and runs none of it (F-S1b); an operator's member reads and runs its
 * operator's. The server decides whose a process acts on: the caller's operator.
 */
export function useProspectScope(): Scope {
  const { can } = useAuth()
  const [search] = useSearchParams()
  const admin = can(PERMISSION.adminProspect)
  return { admin, owner: admin ? search.get('owner')?.trim() ?? '' : '' }
}

/** Whether the page has whose prospecting to read: an operator's member always, the platform once it chose one. */
export const chosen = (scope: Scope) => !scope.admin || scope.owner !== ''
