import { useSyncExternalStore } from 'react'
import { shellCachePrefix, spaNameOf, staleCaches } from './route'
import { SKIP_WAITING } from './worker'

/** Whether the SPA is a PWA (`jabizApp { spa(..., pwa = ...) }`, the build's `VITE_JABIZ_PWA`). */
export type PwaMode = 'on' | 'off' | 'remove'

export interface ServiceWorkerOptions {
  /** The build's `VITE_JABIZ_PWA` by default; `off` when absent. */
  mode?: PwaMode
  /** The SPA's prefix with a trailing slash: the build's base (`import.meta.env.BASE_URL`) by default. */
  base?: string
  /** `navigator.serviceWorker` by default (tests give their own). */
  container?: ServiceWorkerContainer
  /** The Cache Storage (`caches`) by default. */
  cacheStorage?: CacheStorage
  /** Loads the page again once the new version took over; `location.reload()` by default. */
  reload?: () => void
}

type Listener = () => void

let update: (() => void) | null = null
const listeners = new Set<Listener>()

function offer(next: (() => void) | null) {
  update = next
  listeners.forEach((listener) => listener())
}

function subscribe(listener: Listener) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/**
 * The new version that is waiting, as the function that applies it (the page reloads once it took over); null while
 * there is none. For `PwaUpdatePrompt` of `@jabiz/ui`, or an application's own prompt.
 */
export function useServiceWorkerUpdate(): (() => void) | null {
  return useSyncExternalStore(subscribe, () => update, () => null)
}

/** As {@link useServiceWorkerUpdate}, outside React: `listener` is called with each change; returns the unsubscribe. */
export function onServiceWorkerUpdate(listener: (apply: (() => void) | null) => void): () => void {
  return subscribe(() => listener(update))
}

function envMode(): PwaMode {
  const value = import.meta.env?.VITE_JABIZ_PWA
  return value === 'on' || value === 'remove' ? value : 'off'
}

/**
 * Registers the SPA's Service Worker (decision D43 items 6 and 10), once at start-up: `<base>sw.js` with the scope of
 * the SPA's prefix, never wider. A new version is installed in the background and then waits: it takes over only when
 * the user agrees (the function {@link useServiceWorkerUpdate} gives, which `PwaUpdatePrompt` calls), after which the
 * page reloads; nothing reloads by itself, so nobody is interrupted half-way through a form.
 *
 * With `pwa = REMOVE` the worker of this SPA is unregistered and its caches deleted (the server's `sw.js` does the same
 * for browsers that do not load the page). Nothing happens where Service Workers are not available, or for `off`.
 * Returns the registration, or null.
 */
export async function registerServiceWorker(options: ServiceWorkerOptions = {}): Promise<ServiceWorkerRegistration | null> {
  const mode = options.mode ?? envMode()
  const container = options.container
    ?? (typeof navigator !== 'undefined' && 'serviceWorker' in navigator ? navigator.serviceWorker : undefined)
  if (mode === 'off' || !container) return null
  const base = options.base ?? import.meta.env?.BASE_URL ?? '/'
  const scope = new URL(base, window.location.href).href

  if (mode === 'remove') {
    const registrations = await container.getRegistrations()
    await Promise.all(registrations.filter((r) => r.scope === scope).map((r) => r.unregister()))
    const storage = options.cacheStorage ?? (typeof caches !== 'undefined' ? caches : undefined)
    if (storage) {
      const stale = staleCaches(await storage.keys(), shellCachePrefix(spaNameOf(base)), null)
      await Promise.all(stale.map((name) => storage.delete(name)))
    }
    return null
  }

  const registration = await container.register(`${base}sw.js`, { scope: base, updateViaCache: 'none' })
  let accepted = false
  const waitFor = (worker: ServiceWorker) => {
    offer(() => {
      accepted = true
      worker.postMessage({ type: SKIP_WAITING })
    })
  }
  // A version that was installed while the page was closed, or one installing now: only an update when a worker
  // controls the page already (the first one takes over without asking).
  if (registration.waiting && container.controller) waitFor(registration.waiting)
  registration.addEventListener('updatefound', () => {
    const installing = registration.installing
    if (!installing) return
    installing.addEventListener('statechange', () => {
      if (installing.state === 'installed' && container.controller) waitFor(installing)
    })
  })
  let reloading = false
  container.addEventListener('controllerchange', () => {
    // The first worker claiming the page changes the controller too: only the version the user accepted reloads.
    if (!accepted || reloading) return
    reloading = true
    offer(null)
    ;(options.reload ?? (() => window.location.reload()))()
  })
  return registration
}
