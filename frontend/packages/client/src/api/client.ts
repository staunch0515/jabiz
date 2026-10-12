import createClient from 'openapi-fetch'
import type { paths } from './schema'
import { ApiError, toApiError } from './problem'
import { hasWebLocks, session } from './session'

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
/** The step-up itself never asks for a step-up (but is refreshed on 401 like any other call). */
const STEP_UP_PATH = '/api/auth/step-up'

/**
 * Asks the user for a second factor when an operation requires a recent one (403 MFA_REQUIRED, docs/design/
 * 10-security.md section 10); resolves true once a new access token is stored. Set by the StepUpProvider.
 */
type StepUpHandler = () => Promise<boolean>
let stepUpHandler: StepUpHandler | null = null

export function setStepUpHandler(handler: StepUpHandler | null) {
  stepUpHandler = handler
}

let steppingUp: Promise<boolean> | null = null

/** Whether a response asks for a step-up: concurrent requests share one prompt. */
async function stepUpFor(response: Response): Promise<boolean> {
  if (response.status !== 403 || !stepUpHandler) return false
  try {
    const problem = (await response.clone().json()) as { violations?: { ruleCode?: string }[] }
    if (!problem.violations?.some((v) => v.ruleCode === 'MFA_REQUIRED')) return false
  } catch {
    return false
  }
  const handler = stepUpHandler
  steppingUp ??= handler().finally(() => {
    steppingUp = null
  })
  return steppingUp
}

function decorate(request: Request): Request {
  request.headers.set('Accept-Language', language)
  const token = session.accessToken()
  if (token && !request.headers.has('Authorization')) {
    request.headers.set('Authorization', `Bearer ${token}`)
  }
  return request
}

let refreshing: Promise<boolean> | null = null

/** The refresh itself, with the token read only now: under the lock, after any other tab's turn. */
async function exchange(): Promise<boolean> {
  const refreshToken = session.refreshToken()
  if (!refreshToken) return false
  try {
    const response = await globalThis.fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept-Language': language },
      body: JSON.stringify({ refreshToken, entry: session.entry() }),
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

/**
 * Tabs sharing a refresh token kept on the device (decision D43 item 2) take turns: each reads the token only once it
 * holds the entry's lock, so a token another tab has just rotated is never sent again (the server would take that
 * for a stolen token and end the session everywhere). Without Web Locks no token is shared (see session.ts).
 */
function refresh(): Promise<boolean> {
  if (!session.refreshToken()) return Promise.resolve(false)
  if (!hasWebLocks()) return exchange()
  return navigator.locks.request(`jabiz.refresh.${session.entry()}`, () => exchange())
}

/** Options of {@link refreshSession}. */
export interface RefreshOptions {
  /**
   * A refresh of its own rather than joining one under way: after something changed what new tokens say (an address
   * just verified, decision D36), a refresh that started before it does not count.
   */
  force?: boolean
}

/** Refreshes the session; concurrent callers share one refresh (a refresh token can be used only once). */
export function refreshSession(options: RefreshOptions = {}): Promise<boolean> {
  if (options.force && refreshing) {
    return refreshing.then(() => refreshSession(options))
  }
  refreshing ??= refresh().finally(() => {
    refreshing = null
  })
  return refreshing
}

async function authFetch(request: Request): Promise<Response> {
  const path = new URL(request.url).pathname
  const retry = request.clone()
  const again = request.clone()
  let response = await globalThis.fetch(decorate(request))
  if (response.status === 401 && !SESSION_PATHS.includes(path) && session.refreshToken()) {
    // A refused refresh has already ended the session; a failed one (network) keeps it for the next attempt.
    if (await refreshSession()) {
      retry.headers.delete('Authorization')
      response = await globalThis.fetch(decorate(retry))
    }
  }
  if (!SESSION_PATHS.includes(path) && path !== STEP_UP_PATH && (await stepUpFor(response))) {
    again.headers.delete('Authorization')
    return globalThis.fetch(decorate(again))
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
  let response = await send()
  if (response.status === 401 && session.refreshToken() && (await refreshSession())) {
    response = await send()
  }
  if (await stepUpFor(response)) {
    return send()
  }
  return response
}

/**
 * Confirms the signed-in user with a second factor: stores the new access token. Throws an {@link ApiError} with the
 * server's violations (MFA_CODE_INVALID, MFA_NOT_ENROLLED) when refused.
 */
export async function stepUp(code: string): Promise<void> {
  const answer = await unwrap(api.POST('/api/auth/step-up', { body: { code } }))
  session.storeAccess(answer.accessToken!)
}

/**
 * A typed client of other paths with the platform's session (decision D43; docs/design/12-frontend.md section 12):
 * an application's own single-page application passes the types generated from its own OpenAPI snapshot. Every call
 * carries the access token and the language, is refreshed once on 401 and stepped up on MFA_REQUIRED, as with
 * {@link api}; processes and templates still go through runProcess / runQuery.
 */
export function createApiClient<Paths extends object>() {
  return createClient<Paths>({
    baseUrl: typeof window === 'undefined' ? 'http://localhost' : window.location.origin,
    fetch: authFetch,
  })
}

export const api = createApiClient<paths>()

/** The data of a successful call; otherwise throws an {@link ApiError} with the server's violations. */
export async function unwrap<T>(call: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  const { data, error, response } = await call
  if (!response.ok || error !== undefined) {
    throw await toApiError(response, error)
  }
  return data as T
}

export { ApiError }
