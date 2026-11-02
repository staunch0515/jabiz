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

/** A push message: its data as the server sent it (decision D69). */
export interface PushEventLike extends ExtendableEventLike {
  data: { json(): unknown } | null
}

/** A tap on a notification this worker showed. */
export interface NotificationEventLike extends ExtendableEventLike {
  notification: { data: unknown; close(): void }
}

/** A window of the origin, as `clients.matchAll` gives it. */
export interface WindowClientLike {
  url: string
  focus(): Promise<unknown>
  navigate(url: string): Promise<unknown>
}

/** The parts of `ServiceWorkerGlobalScope` the worker uses. */
export interface WorkerScope {
  location: { origin: string }
  registration: {
    unregister(): Promise<boolean>
    showNotification(title: string, options: { body?: string; tag?: string; data?: unknown }): Promise<void>
  }
  clients: {
    claim(): Promise<void>
    matchAll(options: { type: 'window'; includeUncontrolled: boolean }): Promise<readonly WindowClientLike[]>
    openWindow(url: string): Promise<unknown>
  }
  skipWaiting(): Promise<void>
  addEventListener(type: 'install' | 'activate', listener: (event: ExtendableEventLike) => void): void
  addEventListener(type: 'fetch', listener: (event: FetchEventLike) => void): void
  addEventListener(type: 'message', listener: (event: MessageEventLike) => void): void
  addEventListener(type: 'push', listener: (event: PushEventLike) => void): void
  addEventListener(type: 'notificationclick', listener: (event: NotificationEventLike) => void): void
}

/** A push notification as the platform sends it (`{title, body, url, tag}`); null for anything else. */
export function pushMessage(data: PushEventLike['data']): { title: string; body: string; url: string; tag?: string }
  | null {
  if (!data) return null
  let value: unknown
  try {
    value = data.json()
  } catch {
    return null
  }
  if (!value || typeof value !== 'object') return null
  const message = value as Record<string, unknown>
  if (typeof message.title !== 'string' || message.title === '') return null
  return {
    title: message.title,
    body: typeof message.body === 'string' ? message.body : '',
    url: typeof message.url === 'string' ? message.url : '',
    tag: typeof message.tag === 'string' ? message.tag : undefined,
  }
}

/** The page a notification opens: a path of this origin, else the SPA's home; never another site. */
export function notificationTarget(data: unknown, origin: string, base: string): string {
  const url = data && typeof data === 'object' ? (data as { url?: unknown }).url : undefined
  if (typeof url === 'string' && url.startsWith('/') && !url.startsWith('//')) {
    try {
      const target = new URL(url, origin)
      if (target.origin === origin) return target.href
    } catch {
      // Not a URL: the home.
    }
  }
  return new URL(base, origin).href
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
  // Push notifications (decision D69): shown as the server sent them; a tap opens their page in a window of this SPA
  // when there is one, else in a new one.
  scope.addEventListener('push', (event) => {
    const message = pushMessage(event.data)
    if (!message) return
    event.waitUntil(scope.registration.showNotification(message.title, {
      body: message.body, tag: message.tag, data: { url: message.url },
    }))
  })
  scope.addEventListener('notificationclick', (event) => {
    event.notification.close()
    const target = notificationTarget(event.notification.data, ctx.origin, config.base)
    const home = new URL(config.base, ctx.origin).href
    event.waitUntil(scope.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      const own = windows.find((window) => window.url.startsWith(home))
      if (!own) return scope.clients.openWindow(target)
      // navigate() rejects for a window this worker does not control yet (opened before it took over): then a new one.
      return own.focus().then(() => own.navigate(target)).catch(() => scope.clients.openWindow(target))
    }))
  })
}
