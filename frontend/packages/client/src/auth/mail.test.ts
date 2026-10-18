import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/problem'
import { configureSession, session } from '../api/session'
import { json, urlOf } from '../test/locks'
import {
  getMailLanguage,
  getMailPreferences,
  mailLanguageOffer,
  onMailLanguageChange,
  setMailLanguage,
  setMailPreference,
} from './mail'

type Call = { method: string; path: string; body: unknown; auth: string | null; language: string | null }

/** Records the calls and answers them from `answers` by method and path (204 when not given). */
function server(answers: Record<string, () => Response> = {}) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : null
    const method = request?.method ?? init?.method ?? 'GET'
    const path = new URL(urlOf(input), 'http://localhost').pathname
    const text = request ? await request.text() : ''
    const headers = new Headers(request?.headers ?? init?.headers)
    calls.push({ method, path, body: text ? JSON.parse(text) : undefined, auth: headers.get('Authorization'),
      language: headers.get('Accept-Language') })
    return answers[`${method} ${path}`]?.() ?? new Response(null, { status: 204 })
  }))
  return calls
}

const language = (effective: string, locale: string | null = effective) => () =>
  json(200, { locale, effective, languages: ['zh', 'ja', 'en'] })

describe('mail settings (phase 16s)', () => {
  beforeEach(() => {
    configureSession('portal')
    session.store('access', 'refresh-1')
  })
  afterEach(() => {
    session.clear()
    configureSession('admin')
    vi.unstubAllGlobals()
  })

  it("read and set the signed-in user's notifications", async () => {
    const calls = server({
      'GET /api/auth/mail/preferences': () =>
        json(200, { templates: [{ template: 'shop.news', subscribed: true, displayName: 'News' }] }),
      'POST /api/auth/mail/preferences': () => json(200, { template: 'shop.news', subscribed: false }),
    })
    expect(await getMailPreferences()).toEqual([{ template: 'shop.news', subscribed: true, displayName: 'News' }])
    await setMailPreference('shop.news', false)
    expect(calls.map(({ method, path, body, auth }) => ({ method, path, body, auth }))).toEqual([
      { method: 'GET', path: '/api/auth/mail/preferences', body: undefined, auth: 'Bearer access' },
      { method: 'POST', path: '/api/auth/mail/preferences', body: { template: 'shop.news', subscribed: false },
        auth: 'Bearer access' },
    ])
    // Named in the interface language, which every call carries.
    expect(calls[0].language).toBeTruthy()
  })

  it('an answer without templates is an empty list', async () => {
    server({ 'GET /api/auth/mail/preferences': () => json(200, {}) })
    expect(await getMailPreferences()).toEqual([])
  })

  it('read and set the language of the mail; none is the default', async () => {
    const calls = server({ 'GET /api/auth/account/locale': language('en', null),
      'POST /api/auth/account/locale': () => json(200, { userId: 'u' }) })
    expect(await getMailLanguage()).toEqual({ locale: null, effective: 'en', languages: ['zh', 'ja', 'en'] })
    await setMailLanguage('ja')
    await setMailLanguage(null)
    expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([{ locale: 'ja' }, { locale: '' }])
  })

  it('tells the listeners of a mail language set on this page, after the server took it', async () => {
    server({ 'POST /api/auth/account/locale': () => json(200, { userId: 'u' }) })
    const heard: (string | null)[] = []
    const stop = onMailLanguageChange((locale) => heard.push(locale))
    await setMailLanguage('ja')
    await setMailLanguage(null)
    stop()
    await setMailLanguage('en')
    expect(heard).toEqual(['ja', null])
    server({ 'POST /api/auth/account/locale': () => json(422, { status: 422, violations: [] }) })
    const refused: unknown[] = []
    const stopRefused = onMailLanguageChange((locale) => refused.push(locale))
    await expect(setMailLanguage('zh')).rejects.toBeInstanceOf(ApiError)
    stopRefused()
    expect(refused).toEqual([])
  })

  it('a refusal is thrown as an ApiError', async () => {
    server({ 'POST /api/auth/mail/preferences': () =>
      json(422, { status: 422, violations: [{ field: 'template', ruleCode: 'INVALID_VALUE', message: 'no' }] }) })
    await expect(setMailPreference('shop.other', false)).rejects.toBeInstanceOf(ApiError)
  })

  describe('mailLanguageOffer', () => {
    it('offers the new interface language when mail goes in another one, and changes nothing', async () => {
      const calls = server({ 'GET /api/auth/account/locale': language('en') })
      expect(await mailLanguageOffer('ja')).toBe('ja')
      expect(calls.map((c) => c.method)).toEqual(['GET'])
    })

    it('offers nothing when mail goes in that language already, also by default', async () => {
      server({ 'GET /api/auth/account/locale': language('en', null) })
      expect(await mailLanguageOffer('en')).toBeNull()
    })

    it('offers nothing for a language mail cannot be in', async () => {
      server({ 'GET /api/auth/account/locale': () => json(200, { locale: null, effective: 'en', languages: ['en'] }) })
      expect(await mailLanguageOffer('zh')).toBeNull()
    })

    it('offers nothing without a session or when it cannot tell', async () => {
      const calls = server({ 'GET /api/auth/account/locale': () => json(403, { status: 403, violations: [] }) })
      expect(await mailLanguageOffer('ja')).toBeNull()
      session.clear()
      expect(await mailLanguageOffer('ja')).toBeNull()
      expect(calls).toHaveLength(1)
    })
  })
})
