import { api, unwrap } from './api/client'
import type { components } from './api/schema'

/**
 * Push notifications on this device (decision D69): the browser's Web Push subscription, registered for the signed-in
 * session; the server pushes the notifications the user has not turned off to it, and the platform's Service Worker
 * shows them. Needs the PWA's Service Worker (`Pwa.ON`).
 */

export type PushSettings = components['schemas']['PushSettings']

/** What enabling came to. */
export type PushOutcome = 'enabled' | 'denied' | 'unsupported' | 'disabled'

/** Whether this browser can receive Web Push at all (a Service Worker, the Push API and notifications). */
export function pushSupported(): boolean {
  return typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window
    && 'Notification' in window
}

/** Whether push is on for the system, its key, the templates with the user's choice, the user's devices. */
export async function pushSettings(): Promise<PushSettings> {
  return unwrap(api.GET('/api/auth/account/push'))
}

/**
 * Asks the user's permission, subscribes this browser with the server's key and registers it for the session:
 * `denied` when the user refused, `unsupported` without the Push API, `disabled` when push is off on the server.
 */
export async function enablePush(): Promise<PushOutcome> {
  if (!pushSupported()) return 'unsupported'
  const settings = await pushSettings()
  if (!settings.enabled || !settings.webPushKey) return 'disabled'
  const permission = await Notification.requestPermission()
  if (permission !== 'granted') return 'denied'
  const registration = await navigator.serviceWorker.ready
  const key = base64urlBytes(settings.webPushKey)
  let subscription = await registration.pushManager.getSubscription()
  if (subscription && !sameKey(subscription.options.applicationServerKey, key)) {
    // Subscribed with an earlier key of the server: that subscription no longer works.
    await subscription.unsubscribe()
    subscription = null
  }
  subscription ??= await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key })
  const json = subscription.toJSON()
  await unwrap(api.POST('/api/auth/account/push/devices', {
    body: { kind: 'webpush', endpoint: json.endpoint, p256dh: json.keys?.p256dh, auth: json.keys?.auth },
  }))
  return 'enabled'
}

/** Unsubscribes this browser and removes its device; nothing when it was not subscribed. */
export async function disablePush(): Promise<void> {
  if (!pushSupported()) return
  const registration = await navigator.serviceWorker.ready
  const subscription = await registration.pushManager.getSubscription()
  if (!subscription) return
  const endpoint = subscription.endpoint
  await subscription.unsubscribe()
  await unwrap(api.POST('/api/auth/account/push/devices/remove', { body: { endpoint } })).catch(() => undefined)
}

/**
 * Whether push is on here: this browser is subscribed and the server has a device for the session in use. A device
 * belongs to the session it was registered in, so after signing in again (or as another user on this browser) the
 * subscription the browser kept is not on until it is registered again (`enablePush`).
 */
export async function pushEnabledHere(settings?: PushSettings): Promise<boolean> {
  if (!pushSupported()) return false
  const current = settings ?? await pushSettings()
  if (!current.devices?.some((device) => device.current)) return false
  const registration = await navigator.serviceWorker.getRegistration()
  return !!registration && !!(await registration.pushManager.getSubscription())
}

/** Turns a template's push notifications off or on for the user. */
export async function setPushPreference(template: string, subscribed: boolean): Promise<void> {
  await unwrap(api.POST('/api/auth/account/push/preferences', { body: { template, subscribed } }))
}

/** Base64url (as VAPID keys are written) to bytes. */
export function base64urlBytes(text: string): Uint8Array<ArrayBuffer> {
  const base64 = text.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(text.length / 4) * 4, '=')
  const binary = atob(base64)
  const bytes = new Uint8Array(new ArrayBuffer(binary.length))
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

function sameKey(current: ArrayBuffer | null, key: Uint8Array): boolean {
  if (!current) return true
  const bytes = new Uint8Array(current)
  return bytes.length === key.length && bytes.every((byte, i) => byte === key[i])
}
