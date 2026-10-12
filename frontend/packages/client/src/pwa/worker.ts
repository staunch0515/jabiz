import { staleCaches, swRoute, type SwContext } from './route'

/**
 * The Service Worker of a jabiz PWA (decision D43 items 6 and 10), written against the few parts of the worker's
 * global scope it uses so that it can be tested with fakes. `sw.ts` starts it with the configuration the
 * `@jabiz/client/vite` preset builds in.
 */

/** What the preset builds into the worker. */
export interface SwConfig {
  /** `on`: cache the shell; `remove`: unregister and delete this SPA's caches (`pwa = REMOVE`). */
  mode: 'on' | 'remove'
  /** This version's cache: `jabiz-shell-<SPA name>-<build hash>`. */
  cache: string
  /** Every version's cache name starts with it: `jabiz-shell-<SPA name>-`. */
  prefix: string
  /** The SPA's prefix with a trailing slash. */
  base: string
  /** The other SPAs' prefixes of the same jar. */
  exclude: string[]
  /** The shell's files, as paths. */
  precache: string[]
  /** The shell's page, answered to navigations when offline. */
  index: string
}

export interface ExtendableEventLike {
  waitUntil(promise: Promise<unknown>): void
}

export interface FetchEventLike extends ExtendableEventLike {
  request: Request
  respondWith(response: Response | Promise<Response>): void
}

export interface MessageEventLike extends ExtendableEventLike {
  data: unknown
}

/** The parts of `ServiceWorkerGlobalScope` the worker uses. */
export interface WorkerScope {
  location: { origin: string }
  registration: { unregister(): Promise<boolean> }
  clients: { claim(): Promise<void> }
  skipWaiting(): Promise<void>
  addEventListener(type: 'install' | 'activate', listener: (event: ExtendableEventLike) => void): void
  addEventListener(type: 'fetch', listener: (event: FetchEventLike) => void): void
  addEventListener(type: 'message', listener: (event: MessageEventLike) => void): void
}

/** The message the page sends when the user agrees to the new version (`registerServiceWorker`). */
export const SKIP_WAITING = 'SKIP_WAITING'

/** Deletes this SPA's caches other than `keep` (none: all of them); never another SPA's. */
async function cleanUp(caches: CacheStorage, prefix: string, keep: string | null): Promise<void> {
  const stale = staleCaches(await caches.keys(), prefix, keep)
  await Promise.all(stale.map((name) => caches.delete(name)))
}

/** Starts the worker in `scope`. */
export function startWorker(scope: WorkerScope, config: SwConfig, caches: CacheStorage, fetcher: typeof fetch) {
  if (config.mode === 'remove') {
    // Taking the PWA back: replace the installed worker at once, delete its caches and unregister. Its pages stay
    // as they are until they are loaded again, then without a worker. Nothing is intercepted meanwhile.
    scope.addEventListener('install', (event) => event.waitUntil(scope.skipWaiting()))
    scope.addEventListener('activate', (event) =>
      event.waitUntil(cleanUp(caches, config.prefix, null).then(() => scope.registration.unregister())),
    )
    return
  }
  const ctx: SwContext = {
    origin: scope.location.origin,
    base: config.base,
    exclude: config.exclude,
    precache: new Set(config.precache),
  }

  // A new version waits until the user agrees (PwaUpdatePrompt); the first one has nobody to wait for.
  scope.addEventListener('install', (event) =>
    event.waitUntil(caches.open(config.cache).then((cache) => cache.addAll(config.precache))),
  )
  scope.addEventListener('activate', (event) =>
    event.waitUntil(cleanUp(caches, config.prefix, config.cache).then(() => scope.clients.claim())),
  )
  scope.addEventListener('message', (event) => {
    const data = event.data as { type?: unknown } | null
    if (data && data.type === SKIP_WAITING) event.waitUntil(scope.skipWaiting())
  })
  scope.addEventListener('fetch', (event) => {
    const request = event.request
    const route = swRoute({ url: request.url, method: request.method, mode: request.mode }, ctx)
    if (route === 'precache') {
      event.respondWith(
        caches.open(config.cache)
          .then((cache) => cache.match(request))
          .then((cached) => cached ?? fetcher(request)),
      )
    } else if (route === 'navigate') {
      // The network first; the shell only when it cannot be reached. Navigations are never put into the cache.
      event.respondWith(
        fetcher(request).catch(async (error: unknown) => {
          const cache = await caches.open(config.cache)
          const shell = await cache.match(config.index)
          if (shell) return shell
          throw error
        }),
      )
    }
    // 'pass': no respondWith, the browser fetches as without a worker.
  })
}
