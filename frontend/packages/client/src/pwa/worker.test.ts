import { describe, expect, it, vi } from 'vitest'
import { SKIP_WAITING, startWorker, type SwConfig, type WorkerScope } from './worker'

const ORIGIN = 'https://shop.example.com'

const CONFIG: SwConfig = {
  mode: 'on',
  cache: 'jabiz-shell-portal-b2',
  prefix: 'jabiz-shell-portal-',
  base: '/portal/',
  exclude: ['/'],
  precache: ['/portal/index.html', '/portal/assets/index-abc.js'],
  index: '/portal/index.html',
}

/** A Cache Storage in memory, shared by every SPA of the origin (as the browser's). */
function cacheStorage(initial: Record<string, Record<string, string>> = {}) {
  const stores = new Map<string, Map<string, string>>(
    Object.entries(initial).map(([name, entries]) => [name, new Map(Object.entries(entries))]))
  const cacheOf = (name: string) => {
    if (!stores.has(name)) stores.set(name, new Map())
    const store = stores.get(name)!
    return {
      addAll: vi.fn(async (urls: string[]) => urls.forEach((url) => store.set(url, `body of ${url}`))),
      put: vi.fn(),
      match: vi.fn(async (request: Request | string) => {
        const path = new URL(typeof request === 'string' ? request : request.url, ORIGIN).pathname
        const body = store.get(path)
        return body === undefined ? undefined : new Response(body)
      }),
    }
  }
  const storage = {
    open: vi.fn(async (name: string) => cacheOf(name)),
    keys: vi.fn(async () => [...stores.keys()]),
    delete: vi.fn(async (name: string) => stores.delete(name)),
  }
  return { storage: storage as unknown as CacheStorage, stores, cacheOf }
}

function scope() {
  const listeners = new Map<string, (event: never) => void>()
  const worker = {
    location: { origin: ORIGIN },
    registration: { unregister: vi.fn(async () => true) },
    clients: { claim: vi.fn(async () => undefined) },
    skipWaiting: vi.fn(async () => undefined),
    addEventListener: vi.fn((type: string, listener: (event: never) => void) => listeners.set(type, listener)),
  }
  /** Dispatches an event; resolves once everything it waits for and responds with is done. */
  const dispatch = async (type: string, event: Record<string, unknown> = {}) => {
    const waits: Promise<unknown>[] = []
    let response: Promise<Response> | undefined
    listeners.get(type)!({
      ...event,
      waitUntil: (promise: Promise<unknown>) => waits.push(promise),
      respondWith: (r: Response | Promise<Response>) => {
        response = Promise.resolve(r)
      },
    } as never)
    await Promise.all(waits)
    return response
  }
  return { worker, dispatch, listens: (type: string) => listeners.has(type) }
}

function request(path: string, init: { method?: string; mode?: string } = {}): Request {
  return { url: ORIGIN + path, method: init.method ?? 'GET', mode: init.mode ?? 'cors' } as unknown as Request
}

describe('the worker', () => {
  it('precaches the shell when installed and waits: it does not skip waiting by itself', async () => {
    const { worker, dispatch } = scope()
    const { storage, stores } = cacheStorage()
    startWorker(worker as unknown as WorkerScope, CONFIG, storage, vi.fn())

    await dispatch('install')
    expect([...stores.get('jabiz-shell-portal-b2')!.keys()]).toEqual(CONFIG.precache)
    expect(worker.skipWaiting).not.toHaveBeenCalled()

    await dispatch('message', { data: { type: 'something else' } })
    expect(worker.skipWaiting).not.toHaveBeenCalled()
    await dispatch('message', { data: { type: SKIP_WAITING } })
    expect(worker.skipWaiting).toHaveBeenCalledOnce()
  })

  it('deletes only its own older shells when activated: the Cache Storage is shared', async () => {
    const { worker, dispatch } = scope()
    const { storage, stores } = cacheStorage({
      'jabiz-shell-portal-a1': {}, 'jabiz-shell-portal-b2': {}, 'jabiz-shell-root-x': {}, 'app-data': {},
    })
    startWorker(worker as unknown as WorkerScope, CONFIG, storage, vi.fn())

    await dispatch('activate')
    expect([...stores.keys()].sort()).toEqual(['app-data', 'jabiz-shell-portal-b2', 'jabiz-shell-root-x'])
    expect(worker.clients.claim).toHaveBeenCalled()
  })

  it('answers the shell from the cache, pages from the network, and lets everything else through', async () => {
    const { worker, dispatch } = scope()
    const { storage, cacheOf } = cacheStorage({ 'jabiz-shell-portal-b2': {
      '/portal/index.html': 'cached shell', '/portal/assets/index-abc.js': 'cached js' } })
    const fetcher = vi.fn(async () => new Response('from the network'))
    startWorker(worker as unknown as WorkerScope, CONFIG, storage, fetcher)

    expect(await (await dispatch('fetch', { request: request('/portal/assets/index-abc.js') }))!.text())
      .toBe('cached js')
    expect(fetcher).not.toHaveBeenCalled()

    const page = await dispatch('fetch', { request: request('/portal/auth/verify-email?token=t', { mode: 'navigate' }) })
    expect(await page!.text()).toBe('from the network')
    // A page with a token is never put into a cache.
    expect(cacheOf('jabiz-shell-portal-b2').put).not.toHaveBeenCalled()

    expect(await dispatch('fetch', { request: request('/api/auth/me') })).toBeUndefined()
    expect(await dispatch('fetch', { request: request('/api/orders', { method: 'POST' }) })).toBeUndefined()
    expect(await dispatch('fetch', { request: request('/data', { mode: 'navigate' }) })).toBeUndefined()
  })

  it('answers a page with the cached shell when offline', async () => {
    const { worker, dispatch } = scope()
    const { storage } = cacheStorage({ 'jabiz-shell-portal-b2': { '/portal/index.html': 'cached shell' } })
    startWorker(worker as unknown as WorkerScope, CONFIG, storage, vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    }))

    const page = await dispatch('fetch', { request: request('/portal/orders', { mode: 'navigate' }) })
    expect(await page!.text()).toBe('cached shell')
  })

  it('takes the PWA back: replaces itself at once, deletes its caches, unregisters and intercepts nothing', async () => {
    const { worker, dispatch, listens } = scope()
    const { storage, stores } = cacheStorage({ 'jabiz-shell-portal-a1': {}, 'jabiz-shell-root-x': {} })
    startWorker(worker as unknown as WorkerScope, { ...CONFIG, mode: 'remove' }, storage, vi.fn())

    await dispatch('install')
    expect(worker.skipWaiting).toHaveBeenCalled()
    await dispatch('activate')
    expect([...stores.keys()]).toEqual(['jabiz-shell-root-x'])
    expect(worker.registration.unregister).toHaveBeenCalled()
    expect(listens('fetch')).toBe(false)
  })
})
