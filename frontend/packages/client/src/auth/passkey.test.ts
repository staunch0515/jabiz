import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { session } from '../api/session'
import { configureClient } from '../configure'
import { configureNativeShell } from '../native'
import {
  PasskeyCancelledError, addPasskey, credentialJson, creationOptions, fromBase64url, passkeySignIn, passkeySupport,
  requestOptions, stepUpWithPasskey, toBase64url,
} from './passkey'

const CHROME = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 '
  + 'Mobile Safari/537.36'
const WECHAT = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) '
  + 'Mobile/15E148 MicroMessenger/8.0.47(0x18002f2c) NetType/WIFI Language/zh_CN'
const WEB_VIEW = 'Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 '
  + 'Chrome/130.0.0.0 Mobile Safari/537.36; wv)'

function json(body: unknown, status = 200) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

const bytes = (...values: number[]) => new Uint8Array(values).buffer

/** A credential as a browser without `toJSON` returns it. */
function assertion(): PublicKeyCredential {
  return {
    id: 'AQID', rawId: bytes(1, 2, 3), type: 'public-key', authenticatorAttachment: 'platform',
    response: {
      clientDataJSON: bytes(123, 125), authenticatorData: bytes(9), signature: bytes(8, 7), userHandle: bytes(255, 254),
    },
    getClientExtensionResults: () => ({}),
  } as unknown as PublicKeyCredential
}

beforeEach(() => {
  session.clear()
})

afterEach(() => {
  configureClient({ entry: 'admin' })
  configureNativeShell(null)
  vi.unstubAllGlobals()
  Reflect.deleteProperty(navigator, 'credentials')
})

describe('passkeys in the browser (decision D71)', () => {
  it('are offered where WebAuthn is, never inside another app but the own shell', () => {
    expect(passkeySupport({ userAgent: CHROME, webAuthn: true, nativeShell: false })).toBe('available')
    expect(passkeySupport({ userAgent: CHROME, webAuthn: false, nativeShell: false })).toBe('unsupported')
    expect(passkeySupport({ userAgent: WECHAT, webAuthn: true, nativeShell: false })).toBe('in-app-browser')
    expect(passkeySupport({ userAgent: WEB_VIEW, webAuthn: true, nativeShell: false })).toBe('in-app-browser')
    expect(passkeySupport({ userAgent: WEB_VIEW, webAuthn: true, nativeShell: true })).toBe('available')
  })

  it('turns Base64url into bytes and back, padding or not', () => {
    expect(toBase64url(fromBase64url('-_8'))).toBe('-_8')
    expect(new Uint8Array(fromBase64url('AQID'))).toEqual(new Uint8Array([1, 2, 3]))
    expect(toBase64url(new Uint8Array([251, 255]))).toBe('-_8')
    expect(toBase64url(new Uint8Array([0, 1, 2, 3]).subarray(1))).toBe('AQID')
  })

  it('turns the options into what navigator.credentials takes', () => {
    const create = creationOptions({
      challenge: 'AQID', rp: { id: 'example.com', name: 'Ex' }, user: { id: 'BAU', name: 'a@b', displayName: 'A' },
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }], excludeCredentials: [{ type: 'public-key', id: 'Bg',
        transports: ['internal'] }], authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
    })
    expect(new Uint8Array(create.challenge as ArrayBuffer)).toEqual(new Uint8Array([1, 2, 3]))
    expect(new Uint8Array(create.user.id as ArrayBuffer)).toEqual(new Uint8Array([4, 5]))
    expect(create.user.name).toBe('a@b')
    expect(create.excludeCredentials).toEqual([{ type: 'public-key', id: fromBase64url('Bg'), transports: ['internal'] }])
    expect(create.rp).toEqual({ id: 'example.com', name: 'Ex' })
    const get = requestOptions({ challenge: 'AQID', rpId: 'example.com', userVerification: 'required' })
    expect(get.allowCredentials).toEqual([])
    expect(get.rpId).toBe('example.com')
  })

  it('writes the answer as WebAuthn JSON where the browser has no toJSON', () => {
    expect(credentialJson(assertion())).toEqual({
      id: 'AQID', rawId: 'AQID', type: 'public-key', authenticatorAttachment: 'platform', clientExtensionResults: {},
      response: { clientDataJSON: 'e30', authenticatorData: 'CQ', signature: 'CAc', userHandle: '__4' },
    })
    const native = { ...assertion(), toJSON: () => ({ id: 'native' }) } as unknown as PublicKeyCredential
    expect(credentialJson(native)).toEqual({ id: 'native' })
  })

  it('signs in at the frontend\'s entry with the browser\'s answer', async () => {
    configureClient({ entry: 'portal' })
    const get = vi.fn(async () => assertion())
    Object.defineProperty(navigator, 'credentials', { configurable: true, value: { get } })
    const fetch = vi.fn<(request: Request) => Promise<Response>>(async (request) =>
      request.url.endsWith('/options') ? json({ publicKey: { challenge: 'AQID', rpId: 'localhost', allowCredentials: [] } })
        : json({ status: 'SIGNED_IN', accessToken: 'a', refreshToken: 'r' }))
    vi.stubGlobal('fetch', fetch)
    const tokens = await passkeySignIn({ autofill: true })
    expect(tokens.accessToken).toBe('a')
    expect(await fetch.mock.calls[0][0].json()).toEqual({ entry: 'portal' })
    expect(get).toHaveBeenCalledWith(expect.objectContaining({ mediation: 'conditional' }))
    const answer = await fetch.mock.calls[1][0].json()
    expect(answer.entry).toBe('portal')
    expect(answer.credential.id).toBe('AQID')
  })

  it('stops quietly when the dialog is closed: nothing is sent', async () => {
    Object.defineProperty(navigator, 'credentials', { configurable: true, value: {
      create: vi.fn(async () => { throw new DOMException('closed', 'NotAllowedError') }),
    } })
    const fetch = vi.fn<(request: Request) => Promise<Response>>(async () => json({ publicKey: {
      challenge: 'AQID', rp: { id: 'localhost', name: 'x' }, user: { id: 'AQ', name: 'a', displayName: 'a' },
      excludeCredentials: [],
    } }))
    vi.stubGlobal('fetch', fetch)
    await expect(addPasskey({ currentPassword: 'pw' }, 'Phone')).rejects.toBeInstanceOf(PasskeyCancelledError)
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(await fetch.mock.calls[0][0].json()).toEqual({ currentPassword: 'pw' })
  })

  it('steps up with a passkey: the new access token is the session\'s', async () => {
    session.store('old', 'refresh')
    Object.defineProperty(navigator, 'credentials', { configurable: true, value: { get: vi.fn(async () => assertion()) } })
    vi.stubGlobal('fetch', vi.fn<(request: Request) => Promise<Response>>(async (request) =>
      request.url.endsWith('/options') ? json({ publicKey: { challenge: 'AQID', allowCredentials: [] } })
        : json({ accessToken: 'stepped', mfaAt: '2026-01-01T00:00:00Z' })))
    await stepUpWithPasskey()
    expect(session.accessToken()).toBe('stepped')
  })
})
