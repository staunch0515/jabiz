import { useSyncExternalStore } from 'react'
import { entryInfo, forgetEntryInfo } from './account'

/**
 * The system the pages run on (platform phase 16zd): `jabiz.web.environment`, as `GET /api/auth/entries/{name}` tells
 * it before anyone signs in. `production` is the real system; any other name is a test system, which every page shows
 * in a banner (`EnvironmentBanner` of `@jabiz/ui`) so that nobody mistakes it for the real one.
 */
export interface Environment {
  /** The name as configured: `production`, `test`, `staging`, … */
  name: string
  /** Whether this is the real system (`production`, whatever its case). */
  production: boolean
}

/** How long to wait before asking again after the server could not be reached: doubled each time, up to the last. */
const RETRY_MS = [5_000, 15_000, 60_000]

let current: Environment | undefined
/** What the pages show: `current` after the application's override (phase 17ze); recomputed when either changes. */
let shown: Environment | undefined
let override: EnvironmentOverride | null = null
let loading = false
let generation = 0
let failures = 0
let retry: ReturnType<typeof setTimeout> | undefined
const listeners = new Set<() => void>()

function notify() {
  for (const listener of listeners) listener()
}

/**
 * An application's say over the environment its pages show (phase 17ze), given what the server said and where the page
 * is: e.g. a public site answered for a while by a test system's application. Returning nothing (or throwing) keeps the
 * server's. The platform's own frontends set none, so the administration always shows what the server says.
 */
export type EnvironmentOverride = (server: Environment, location: { hostname: string }) => Environment | null | undefined

function applyOverride(environment: Environment | undefined): Environment | undefined {
  if (!environment || !override) return environment
  try {
    const hostname = typeof window === 'undefined' ? '' : window.location.hostname
    const chosen = override(environment, { hostname })
    return chosen ? { name: chosen.name, production: chosen.production } : environment
  } catch {
    // An application's mistake must not hide a test system: the server's word stands.
    return environment
  }
}

function setCurrent(environment: Environment | undefined) {
  current = environment
  shown = applyOverride(environment)
}

/** Sets (or, with `null`, removes) the application's override; the pages show its result at once. */
export function configureEnvironment(next: EnvironmentOverride | null) {
  override = next
  shown = applyOverride(current)
  notify()
}

/** An environment from the entry's answer; absent (an older server) means a test system is not ruled out: `test`. */
export function toEnvironment(name: string | null | undefined): Environment {
  const value = (name ?? '').trim() || 'test'
  return { name: value, production: value.toLowerCase() === 'production' }
}

function load() {
  if (current || loading || listeners.size === 0) return
  loading = true
  const asked = generation
  entryInfo()
    .then((info) => {
      if (asked !== generation) return
      failures = 0
      setCurrent(toEnvironment(info.environment))
      notify()
    })
    .catch(() => {
      // Offline or refused (a limiter, a restart): a test system must not stay unlabelled for the session, so it is
      // asked again while anyone shows it, after a growing pause. The entry's cached failure is dropped first.
      if (asked !== generation) return
      forgetEntryInfo()
      const pause = RETRY_MS[Math.min(failures, RETRY_MS.length - 1)]
      failures++
      retry = setTimeout(() => {
        retry = undefined
        load()
      }, pause)
    })
    .finally(() => {
      if (asked === generation) loading = false
    })
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  load()
  return () => {
    listeners.delete(listener)
    if (listeners.size === 0 && retry) {
      clearTimeout(retry)
      retry = undefined
    }
  }
}

/**
 * The environment, once the server has told it (undefined before, and while it cannot be reached). Asked once per
 * page, through the entry's public settings.
 */
export function useEnvironment(): Environment | undefined {
  return useSyncExternalStore(subscribe, () => shown, () => undefined)
}

/** Forgets what was read and stops any pending or in-flight request from writing it. */
function reset() {
  generation++
  loading = false
  failures = 0
  if (retry) {
    clearTimeout(retry)
    retry = undefined
  }
}

/** Sets the environment without asking the server (tests; an application that knows it from elsewhere). */
export function setEnvironment(environment: Environment | string | undefined) {
  reset()
  setCurrent(typeof environment === 'string' ? toEnvironment(environment) : environment)
  notify()
}

/** Forgets the environment read (tests, or after the configured entry changed): asked again on the next use. */
export function forgetEnvironment() {
  reset()
  setCurrent(undefined)
  notify()
}
