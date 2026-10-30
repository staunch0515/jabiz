import { QueryClientContext } from '@tanstack/react-query'
import { useContext, useEffect, useSyncExternalStore } from 'react'
import { session } from './session'

/**
 * Whether the service answers at all (ROADMAP 17x). During maintenance or a restart the reverse proxy answers every
 * call with its own page (502, 503 or 504, HTML, `Retry-After`) or the connection fails; a page already open would show
 * an error in every part that reads data. The client notes such answers here instead, the page says once that the
 * service is unavailable, and a light probe finds out when it is back. The application's own 503 (`PROCESS_BUSY`,
 * `QUERY_TIMEOUT`) is a JSON problem and counts as an answer.
 */
export interface ServiceUnavailable {
  /** When the first unanswered call was noted (the device's clock). */
  since: number
  /** When the next probe is due (the device's clock). */
  nextProbe: number
}

/** The probe's interval bounds: `Retry-After` is followed within them; a failed connection waits the shortest. */
export const PROBE_MIN_MS = 10_000
export const PROBE_MAX_MS = 300_000

let state: ServiceUnavailable | null = null
let timer: ReturnType<typeof setTimeout> | undefined
let probing: Promise<boolean> | null = null
const listeners = new Set<() => void>()
const restoredHandlers = new Set<() => void>()

function emit() {
  listeners.forEach((listener) => listener())
}

/** The proxy's own answer rather than the application's: a gateway status without a JSON body. */
export function isProxyUnavailable(response: Response): boolean {
  if (response.status !== 502 && response.status !== 503 && response.status !== 504) return false
  return !(response.headers.get('Content-Type') ?? '').toLowerCase().includes('json')
}

function retryAfterMs(response: Response | null): number {
  const header = response?.headers.get('Retry-After')
  const seconds = header && /^\d+$/.test(header.trim()) ? Number(header.trim()) : 0
  return Math.min(PROBE_MAX_MS, Math.max(PROBE_MIN_MS, seconds * 1000))
}

function schedule(delay: number) {
  clearTimeout(timer)
  timer = setTimeout(() => void probeService(), delay)
  if (state) state = { ...state, nextProbe: Date.now() + delay }
}

function markUnavailable(delay: number) {
  if (!state) state = { since: Date.now(), nextProbe: Date.now() + delay }
  schedule(delay)
  emit()
}

function markAvailable() {
  if (!state) return
  clearTimeout(timer)
  state = null
  emit()
  restoredHandlers.forEach((handler) => handler())
}

/** Notes what a call was answered: the proxy's unavailable page marks the service unavailable, any other answer back. */
export function noteResponse(response: Response) {
  if (isProxyUnavailable(response)) markUnavailable(retryAfterMs(response))
  else markAvailable()
}

/**
 * Notes a call that got no answer: while the browser is online the server is out of reach (the offline banner covers
 * an offline browser). An aborted call (a page left, a time limit) says nothing about the server.
 */
export function noteNetworkFailure(error: unknown) {
  if (error instanceof DOMException && (error.name === 'AbortError' || error.name === 'TimeoutError')) return
  if (typeof navigator !== 'undefined' && navigator.onLine === false) return
  markUnavailable(PROBE_MIN_MS)
}

/** fetch that notes whether the service answered; what it returns or throws is unchanged. */
export async function observedFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  let response: Response
  try {
    response = await globalThis.fetch(input, init)
  } catch (error) {
    noteNetworkFailure(error)
    throw error
  }
  noteResponse(response)
  return response
}

/**
 * Asks whether the service is back with the lightest public call there is (the entry's description), now; concurrent
 * callers share one probe. Back: the state clears and the restore handlers run. Not yet: the next probe is scheduled.
 */
export function probeService(): Promise<boolean> {
  probing ??= (async () => {
    try {
      const response = await globalThis.fetch(`/api/auth/entries/${encodeURIComponent(session.entry())}`, {
        headers: { Accept: 'application/json' },
        cache: 'no-store',
      })
      if (isProxyUnavailable(response)) {
        if (state) schedule(retryAfterMs(response))
        return false
      }
      markAvailable()
      return true
    } catch {
      if (state) schedule(PROBE_MIN_MS)
      return false
    }
  })().finally(() => {
    probing = null
  })
  return probing
}

/** The service's state now: null while it answers. */
export function serviceUnavailable(): ServiceUnavailable | null {
  return state
}

/** Runs `handler` each time the service is back after being unavailable; returns the unsubscribe. */
export function onServiceRestored(handler: () => void): () => void {
  restoredHandlers.add(handler)
  return () => restoredHandlers.delete(handler)
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * The service's state, following it; when it is back, what the page reads is read again (the queries of the
 * surrounding QueryClient, if any), so the parts that failed meanwhile recover without a tap.
 */
export function useServiceUnavailable(): ServiceUnavailable | null {
  const current = useSyncExternalStore(subscribe, serviceUnavailable, () => null)
  const queryClient = useContext(QueryClientContext)
  useEffect(() => onServiceRestored(() => void queryClient?.invalidateQueries()), [queryClient])
  return current
}

/** Forgets the state and the scheduled probe (tests). */
export function resetServiceAvailability() {
  clearTimeout(timer)
  state = null
  probing = null
  emit()
}
