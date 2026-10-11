import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useSearchParams } from 'react-router'
import { api, unwrap } from '../../api/client'
import { ApiError } from '../../api/problem'

/** Names of sign-in entries (docs/design/10-security.md section 15.1); anything else in a link is ignored. */
const ENTRY = /^[a-z][a-z0-9-]{0,39}$/

/**
 * The sign-in entry and token of a mailed link ({@code /auth/<page>?entry=<entry>&token=<token>}, decision D36
 * implementation note 13), and the path of the entry's pages: where the page leads once done.
 *
 * The token is read once and then taken out of the address bar (`history.replaceState`), so that it stays out of the
 * history, bookmarks and screenshots; the page sends no referrer while it is open ({@link useNoReferrer}).
 */
export function useLink() {
  const [params] = useSearchParams()
  const raw = params.get('entry')
  const entry = raw && ENTRY.test(raw) ? raw : 'admin'
  // From the router's location, which the address bar's clean-up below does not change.
  const token = params.get('token') || null
  useNoReferrer()
  useEffect(() => {
    const url = new URL(window.location.href)
    if (url.searchParams.has('token')) {
      url.searchParams.delete('token')
      window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash)
    }
  }, [])
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

/** No referrer while the page is open: its address held a token, and its links lead elsewhere. */
export function useNoReferrer() {
  useEffect(() => {
    const meta = document.createElement('meta')
    meta.name = 'referrer'
    meta.content = 'no-referrer'
    document.head.appendChild(meta)
    return () => meta.remove()
  }, [])
}

/** Whether a failure was the link's own (invalid, expired, used, superseded): 422 TOKEN_INVALID. */
export function invalidLink(error: unknown): boolean {
  return error instanceof ApiError && error.status === 422 && error.violations.some((v) => v.ruleCode === 'TOKEN_INVALID')
}

/** The violations of a failure that concern the password fields; empty for anything else. */
export function passwordProblems(error: unknown): string[] {
  return error instanceof ApiError
    ? error.violations.filter((v) => v.field === 'password' || v.field === 'newPassword').map((v) => v.message)
    : []
}

/** Whether the server asks for a password it was not given (422 REQUIRED for `password`). */
export function passwordRequired(error: unknown): boolean {
  return error instanceof ApiError && error.status === 422
    && error.forField('password').some((v) => v.ruleCode === 'REQUIRED')
}

/** The message of a failure, or the general one. */
export function failure(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.violations.length > 0 ? error.display : fallback
}
