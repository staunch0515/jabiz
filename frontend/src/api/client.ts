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
