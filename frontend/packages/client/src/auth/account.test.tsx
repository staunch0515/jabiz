import { act, render, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/problem'
import { configureSession, session } from '../api/session'
import { json, urlOf } from '../test/locks'
import {
  changePassword,
  confirmPasswordReset,
  entryInfo,
  forgetEntryInfo,
  isProblem,
  register,
  requestPasswordReset,
  resendVerification,
  useOnline,
} from './account'
import { parseMailLink, readMailLink, useMailLink } from './mailLink'

/** Records what is posted and answers 202, or as given. */
function server(answer: () => Response = () => new Response(null, { status: 202, headers: { 'Content-Length': '0' } })) {
  const posts: { path: string; body: unknown; auth: string | null }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(urlOf(input), 'http://localhost')
    const request = input instanceof Request ? input : null
    if ((request?.method ?? init?.method) === 'POST') {
      const text = request ? await request.text() : String(init?.body ?? '')
      const headers = new Headers(request?.headers ?? init?.headers)
      posts.push({ path: url.pathname, body: JSON.parse(text), auth: headers.get('Authorization') })
      return answer()
    }
    if (url.pathname.startsWith('/api/auth/entries/')) return json(200, { name: 'portal', appPath: '/portal/' })
    return json(404, {})
  }))
  return posts
}

describe('account functions (decision D43)', () => {
  beforeEach(() => configureSession('portal'))
  afterEach(() => {
    session.clear()
    configureSession('admin')
    forgetEntryInfo()
    vi.unstubAllGlobals()
  })

  it("act for the frontend's entry unless another is named", async () => {
    const posts = server()
    await register({ email: 'ann@example.com', password: 'correct horse', displayName: 'Ann', locale: 'en' })
    await requestPasswordReset(' ann@example.com ')
    await resendVerification('ann@example.com', 'shop')
    expect(posts.map((p) => [p.path, p.body])).toEqual([
      ['/api/auth/register', { email: 'ann@example.com', password: 'correct horse', displayName: 'Ann', locale: 'en', entry: 'portal' }],
      ['/api/auth/password-reset/request', { email: 'ann@example.com', entry: 'portal' }],
      ['/api/auth/verify-email/resend', { email: 'ann@example.com', entry: 'shop' }],
    ])
  })

  it('throw the refusal with its rule code', async () => {
    server(() => json(422, { violations: [{ field: null, ruleCode: 'TOKEN_INVALID', message: 'No.' }] }))
    const refused = await confirmPasswordReset('old', 'new password').catch((e: unknown) => e)
    expect(isProblem(refused, 'TOKEN_INVALID')).toBe(true)
    expect(isProblem(refused, 'TOKEN_INVALID', 422)).toBe(true)
    expect(isProblem(refused, 'TOKEN_INVALID', 400)).toBe(false)
    expect(isProblem(new Error('x'), 'TOKEN_INVALID')).toBe(false)
  })

  it('change the own password with the current one, keeping this session', async () => {
    session.store('access', 'refresh-1')
    const posts = server(() => new Response(null, { status: 204 }))
    await changePassword('old one', 'new one')
    expect(posts).toEqual([{
      path: '/api/auth/account/password',
      body: { currentPassword: 'old one', newPassword: 'new one', refreshToken: 'refresh-1' },
      auth: 'Bearer access',
    }])

    server(() => json(422, { violations: [{ field: 'currentPassword', ruleCode: 'PASSWORD_INCORRECT', message: 'Wrong.' }] }))
    const refused = await changePassword('wrong', 'new one').catch((e: unknown) => e)
    expect(refused).toBeInstanceOf(ApiError)
    expect(isProblem(refused, 'PASSWORD_INCORRECT')).toBe(true)
  })

  it("read an entry's settings once", async () => {
    server()
    const fetch = globalThis.fetch as ReturnType<typeof vi.fn>
    expect((await entryInfo()).appPath).toBe('/portal/')
    await entryInfo('portal')
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('follow whether the browser is online', () => {
    const { result } = renderHook(() => useOnline())
    expect(result.current).toBe(true)
    const onLine = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    act(() => {
      window.dispatchEvent(new Event('offline'))
    })
    expect(result.current).toBe(false)
    onLine.mockReturnValue(true)
    act(() => {
      window.dispatchEvent(new Event('online'))
    })
    expect(result.current).toBe(true)
    onLine.mockRestore()
  })
})

describe('mailed links (decision D43 item 4)', () => {
  afterEach(() => {
    window.history.replaceState(null, '', '/')
    forgetEntryInfo()
    vi.unstubAllGlobals()
  })

  it('read the entry and token, and take the token out of the address bar', () => {
    window.history.replaceState(null, '', '/portal/auth/verify-email?entry=portal&token=tok-1#x')
    expect(readMailLink()).toEqual({ entry: 'portal', token: 'tok-1', page: 'verify-email' })
    expect(window.location.pathname + window.location.search + window.location.hash).toBe('/portal/auth/verify-email?entry=portal#x')
    expect(readMailLink().token).toBeNull()
  })

  it("keep to entry names, else the frontend's entry", () => {
    window.history.replaceState(null, '', '/auth/join?entry=../evil&token=t')
    expect(parseMailLink()).toEqual({ entry: 'admin', token: 't', page: 'join' })
    expect(parseMailLink('?entry=shop')).toEqual({ entry: 'shop', token: null, page: 'join' })
  })

  it('send no referrer while the page is open, and lead to the entry once done', async () => {
    server()
    window.history.replaceState(null, '', '/portal/auth/password-reset?entry=portal&token=r1')
    function Probe() {
      const link = useMailLink()
      return <a data-testid="probe" data-token={link.token ?? ''} href={link.appPath} />
    }
    const { getByTestId, unmount } = render(<Probe />)
    expect(getByTestId('probe').dataset.token).toBe('r1')
    expect(window.location.search).toBe('?entry=portal')
    expect(document.querySelector('meta[name="referrer"]')?.getAttribute('content')).toBe('no-referrer')
    await waitFor(() => expect(getByTestId('probe').getAttribute('href')).toBe('/portal/'))
    unmount()
    expect(document.querySelector('meta[name="referrer"]')).toBeNull()
  })
})
