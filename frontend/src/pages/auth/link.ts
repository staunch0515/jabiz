import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { api, unwrap } from '../../api/client'
import { ApiError } from '../../api/problem'

/** Names of sign-in entries (docs/design/10-security.md section 15.1); anything else in a link is ignored. */
const ENTRY = /^[a-z][a-z0-9-]{0,39}$/

/**
 * The sign-in entry and token of a mailed link ({@code /auth/<page>?entry=<entry>&token=<token>}, decision D36
 * implementation note 13), and the path of the entry's pages: where the page leads once done.
 */
export function useLink() {
  const [params] = useSearchParams()
  const raw = params.get('entry')
  const entry = raw && ENTRY.test(raw) ? raw : 'admin'
  const token = params.get('token') || null
  const info = useQuery({
    queryKey: ['auth', 'entry', entry],
    queryFn: () => unwrap(api.GET('/api/auth/entries/{name}', { params: { path: { name: entry } } })),
    staleTime: Infinity,
    retry: false,
  })
  // Only a path of this site (the server checks app-path too); anything else is the root.
  const configured = info.data?.appPath
  const appPath = configured?.startsWith('/') && !configured.startsWith('//') ? configured : '/'
  return { entry, token, appPath }
}

/** Whether a failure was the link's own (invalid, expired, used, superseded): 422 TOKEN_INVALID. */
export function invalidLink(error: unknown): boolean {
  return error instanceof ApiError && error.status === 422 && error.violations.some((v) => v.ruleCode === 'TOKEN_INVALID')
}

/** The message of a failure, or the general one. */
export function failure(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.violations.length > 0 ? error.display : fallback
}
