import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { onServiceWorkerUpdate, registerServiceWorker, useServiceWorkerUpdate } from './register'

/** A Service Worker in a state, with the listeners the page adds. */
class FakeWorker extends EventTarget {
  state: string
  postMessage = vi.fn()
  constructor(state: string) {
    super()
    this.state = state
  }
  become(state: string) {
    this.state = state
    this.dispatchEvent(new Event('statechange'))
  }
}

class FakeRegistration extends EventTarget {
  scope: string
  waiting: FakeWorker | null = null
  installing: FakeWorker | null = null
  unregister = vi.fn(async () => true)
  constructor(scope: string) {
    super()
    this.scope = scope
  }
}

class FakeContainer extends EventTarget {
  controller: FakeWorker | null
  registration: FakeRegistration
  others: FakeRegistration[] = []
  register = vi.fn(async () => this.registration)
  getRegistrations = vi.fn(async () => [this.registration, ...this.others])
  constructor(controlled: boolean, scope = `${window.location.origin}/portal/`) {
    super()
    this.controller = controlled ? new FakeWorker('activated') : null
    this.registration = new FakeRegistration(scope)
  }
}

const unsubscribes: (() => void)[] = []
afterEach(() => unsubscribes.splice(0).forEach((u) => u()))

function latestUpdate() {
  let latest: (() => void) | null = null
  unsubscribes.push(onServiceWorkerUpdate((apply) => {
    latest = apply
  }))
  return () => latest
}

describe('registerServiceWorker', () => {
  it('does nothing when the SPA is no PWA or the browser has no Service Workers', async () => {
    const container = new FakeContainer(false)
    expect(await registerServiceWorker({ mode: 'off', container: container as never })).toBeNull()
    expect(container.register).not.toHaveBeenCalled()
    // The build's VITE_JABIZ_PWA is not set in the tests: off.
    expect(await registerServiceWorker({ container: container as never })).toBeNull()
    expect(await registerServiceWorker({ mode: 'on', base: '/portal/' })).toBeNull()
  })

  it('registers the SPA\'s worker with its prefix as the scope, revalidating the script', async () => {
    const container = new FakeContainer(false)
    await registerServiceWorker({ mode: 'on', base: '/portal/', container: container as never })
    expect(container.register).toHaveBeenCalledWith('/portal/sw.js', { scope: '/portal/', updateViaCache: 'none' })
  })

  it('offers a new version only once installed while another controls the page, and reloads only when accepted',
    async () => {
      const container = new FakeContainer(true)
      const reload = vi.fn()
      const update = latestUpdate()
      const { result } = renderHook(() => useServiceWorkerUpdate())
      await registerServiceWorker({ mode: 'on', base: '/portal/', container: container as never, reload })
      expect(update()).toBeNull()

      const next = new FakeWorker('installing')
      container.registration.installing = next
      container.registration.dispatchEvent(new Event('updatefound'))
      expect(update()).toBeNull()
      act(() => next.become('installed'))
      expect(update()).toBeTypeOf('function')
      expect(result.current).toBe(update())
      // Nothing reloads by itself, nor when another worker happens to take over.
      container.dispatchEvent(new Event('controllerchange'))
      expect(reload).not.toHaveBeenCalled()
      expect(next.postMessage).not.toHaveBeenCalled()

      act(() => update()!())
      expect(next.postMessage).toHaveBeenCalledWith({ type: 'SKIP_WAITING' })
      act(() => container.dispatchEvent(new Event('controllerchange')))
      expect(reload).toHaveBeenCalledOnce()
      expect(update()).toBeNull()
      expect(result.current).toBeNull()
      container.dispatchEvent(new Event('controllerchange'))
      expect(reload).toHaveBeenCalledOnce()
    })

  it('offers a version that is waiting already, and none on the first install', async () => {
    const waiting = new FakeContainer(true)
    waiting.registration.waiting = new FakeWorker('installed')
    const update = latestUpdate()
    await registerServiceWorker({ mode: 'on', base: '/portal/', container: waiting as never, reload: vi.fn() })
    expect(update()).toBeTypeOf('function')
    act(() => update()!())
    expect(waiting.registration.waiting.postMessage).toHaveBeenCalledWith({ type: 'SKIP_WAITING' })

    // The first worker of a page that had none: it takes over without asking, nothing reloads.
    const first = new FakeContainer(false)
    const reload = vi.fn()
    await registerServiceWorker({ mode: 'on', base: '/portal/', container: first as never, reload })
    const worker = new FakeWorker('installing')
    first.registration.installing = worker
    first.registration.dispatchEvent(new Event('updatefound'))
    worker.become('installed')
    first.dispatchEvent(new Event('controllerchange'))
    expect(reload).not.toHaveBeenCalled()
  })

  it('takes the PWA back: unregisters this SPA\'s worker only and deletes its caches only', async () => {
    const container = new FakeContainer(true)
    const other = new FakeRegistration(`${window.location.origin}/`)
    container.others.push(other)
    const names = ['jabiz-shell-portal-a', 'jabiz-shell-root-b', 'mine']
    const storage = { keys: vi.fn(async () => names), delete: vi.fn(async () => true) }

    expect(await registerServiceWorker({ mode: 'remove', base: '/portal/', container: container as never,
      cacheStorage: storage as never })).toBeNull()
    expect(container.registration.unregister).toHaveBeenCalled()
    expect(other.unregister).not.toHaveBeenCalled()
    expect(storage.delete.mock.calls).toEqual([['jabiz-shell-portal-a']])
    expect(container.register).not.toHaveBeenCalled()
  })
})
