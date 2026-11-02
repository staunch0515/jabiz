import { afterEach, describe, expect, it, vi } from 'vitest'
import { base64urlBytes, disablePush, enablePush, pushEnabledHere, pushSupported } from './push'

const KEY = 'BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4'

function json(body: unknown, status = 200) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

/** A browser with the Push API: a Service Worker registration whose push manager subscribes. */
function browser(permission: NotificationPermission, existing: PushSubscription | null = null) {
  const unsubscribe = vi.fn(async () => true)
  const subscription = {
    endpoint: 'https://fcm.googleapis.com/fcm/send/abc',
    options: { applicationServerKey: base64urlBytes(KEY).buffer },
    unsubscribe,
    toJSON: () => ({ endpoint: 'https://fcm.googleapis.com/fcm/send/abc', keys: { p256dh: 'pk', auth: 'au' } }),
  } as unknown as PushSubscription
  const pushManager = {
    getSubscription: vi.fn(async () => existing),
    subscribe: vi.fn(async () => subscription),
  }
  vi.stubGlobal('Notification', { requestPermission: vi.fn(async () => permission) })
  vi.stubGlobal('PushManager', function PushManager() {})
  Object.defineProperty(navigator, 'serviceWorker', {
    configurable: true,
    value: { ready: Promise.resolve({ pushManager }), getRegistration: vi.fn(async () => ({ pushManager })) },
  })
  return { pushManager, subscription, unsubscribe }
}

afterEach(() => {
  vi.unstubAllGlobals()
  Reflect.deleteProperty(navigator, 'serviceWorker')
})

describe('push on this device (decision D69)', () => {
  it('reads the server key as bytes', () => {
    expect(base64urlBytes(KEY)).toHaveLength(65)
    expect(base64urlBytes(KEY)[0]).toBe(4)
  })

  it('subscribes with the server key and registers the subscription for the session', async () => {
    const { pushManager } = browser('granted')
    const fetch = vi.fn<(request: Request) => Promise<Response>>(async (request) =>
      request.url.endsWith('/api/auth/account/push') ? json({ enabled: true, webPushKey: KEY, templates: [], devices: [] })
        : json({ deviceId: 'd1' }))
    vi.stubGlobal('fetch', fetch)
    expect(pushSupported()).toBe(true)
    expect(await enablePush()).toBe('enabled')
    expect(pushManager.subscribe).toHaveBeenCalledWith({ userVisibleOnly: true, applicationServerKey: base64urlBytes(KEY) })
    const register = fetch.mock.calls[1][0]
    expect(new URL(register.url).pathname).toBe('/api/auth/account/push/devices')
    expect(await register.json()).toEqual({ kind: 'webpush', endpoint: 'https://fcm.googleapis.com/fcm/send/abc',
      p256dh: 'pk', auth: 'au' })
  })

  it('stops at a refused permission or a server with push off', async () => {
    browser('denied')
    vi.stubGlobal('fetch', vi.fn(async () => json({ enabled: true, webPushKey: KEY })))
    expect(await enablePush()).toBe('denied')
    browser('granted')
    vi.stubGlobal('fetch', vi.fn(async () => json({ enabled: false })))
    expect(await enablePush()).toBe('disabled')
  })

  it('unsubscribes and removes the device by its endpoint', async () => {
    const { subscription, unsubscribe } = browser('granted')
    browser('granted', subscription)
    const fetch = vi.fn<(request: Request) => Promise<Response>>(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetch)
    await disablePush()
    expect(unsubscribe).toHaveBeenCalled()
    expect(await fetch.mock.calls[0][0].json()).toEqual({ endpoint: 'https://fcm.googleapis.com/fcm/send/abc' })
  })

  it('is on here only while the server has a device for the session in use', async () => {
    const { subscription } = browser('granted')
    browser('granted', subscription)
    const device = { id: 'd1', kind: 'webpush', entry: 'portal', createdAt: '2026-11-01T00:00:00Z' }
    // The browser kept its subscription, but the device was registered in an earlier session (or by another user).
    expect(await pushEnabledHere({ enabled: true, devices: [{ ...device, current: false }] })).toBe(false)
    expect(await pushEnabledHere({ enabled: true, devices: [{ ...device, current: true }] })).toBe(true)
    browser('granted')
    expect(await pushEnabledHere({ enabled: true, devices: [{ ...device, current: true }] })).toBe(false)
  })
})
