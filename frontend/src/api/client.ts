import createClient from 'openapi-fetch'
import type { paths } from './schema'
import { ApiError, toApiError } from './problem'
import { session } from './session'

/**
 * The typed API client, generated from the backend's OpenAPI document (docs/design/12-frontend.md section 3).
 * Every request carries the access token and the UI language (the server localizes messages and labels by
 * Accept-Language). A 401 triggers one refresh of the session, then the request is repeated once.
 */
let language = 'zh'

export function setApiLanguage(lang: string) {
  language = lang
}

const SESSION_PATHS = ['/api/auth/login', '/api/auth/refresh', '/api/auth/logout']

function decorate(request: Request): Request {
  request.headers.set('Accept-Language', language)
  const token = session.accessToken()
  if (token && !request.headers.has('Authorization')) {
    request.headers.set('Authorization', `Bearer ${token}`)
  }
  return request
}

let refreshing: Promise<boolean> | null = null

async function refresh(): Promise<boolean> {
  const refreshToken = session.refreshToken()
  if (!refreshToken) return false
  try {
    const response = await globalThis.fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept-Language': language },
      body: JSON.stringify({ refreshToken }),
    })
    if (!response.ok) {
      session.clear()
      return false
    }
    const tokens = (await response.json()) as { accessToken: string; refreshToken: string }
    session.store(tokens.accessToken, tokens.refreshToken)
    return true
  } catch {
    return false
  }
}

/** Refreshes the session; concurrent callers share one refresh (a refresh token can be used only once). */
export function refreshSession(): Promise<boolean> {
  refreshing ??= refresh().finally(() => {
    refreshing = null
  })
  return refreshing
}

async function authFetch(request: Request): Promise<Response> {
  const path = new URL(request.url).pathname
  const retry = request.clone()
  const response = await globalThis.fetch(decorate(request))
  if (response.status === 401 && !SESSION_PATHS.includes(path) && session.refreshToken()) {
    // A refused refresh has already ended the session; a failed one (network) keeps it for the next attempt.
    if (await refreshSession()) {
      retry.headers.delete('Authorization')
      return globalThis.fetch(decorate(retry))
    }
  }
  return response
}

/**
 * fetch with the session for calls outside the typed client (uploads, file contents): the same headers and the same
 * single refresh on 401. The body is passed as given, so a FormData upload is streamed by the browser.
 */
export async function sessionFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const send = () => {
    const headers = new Headers(init.headers)
    headers.set('Accept-Language', language)
    const token = session.accessToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    return globalThis.fetch(path, { ...init, headers })
  }
  const response = await send()
  if (response.status === 401 && session.refreshToken() && (await refreshSession())) {
    return send()
  }
  return response
}

export const api = createClient<paths>({
  baseUrl: typeof window === 'undefined' ? 'http://localhost' : window.location.origin,
  fetch: authFetch,
})

/** The data of a successful call; otherwise throws an {@link ApiError} with the server's violations. */
export async function unwrap<T>(call: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  const { data, error, response } = await call
  if (!response.ok || error !== undefined) {
    throw await toApiError(response, error)
  }
  return data as T
}

export { ApiError }
