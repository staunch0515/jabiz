import { ApiError, configureClient, i18n, session, type SessionInfo, type SessionList as Sessions } from '@jabiz/client'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { effectiveMinutes, SessionList, sessionDeviceName } from './index'

const laptop: SessionInfo = {
  id: '0190a6b2-0000-7000-8000-000000000001',
  entry: 'portal',
  current: true,
  createdAt: '2026-10-01T08:00:00Z',
  lastUsedAt: '2026-10-13T09:00:00Z',
  expiresAt: '2026-11-12T09:00:00Z',
  clientIp: '203.0.113.5',
  userAgent: 'Mozilla/5.0 (Windows NT 10.0) Chrome/126.0',
  device: { browser: 'CHROME', os: 'WINDOWS', deviceType: 'DESKTOP' },
  mfa: true,
  oidc: false,
}
const phone: SessionInfo = {
  ...laptop,
  id: '0190a6b2-0000-7000-8000-000000000002',
  current: false,
  clientIp: '198.51.100.9',
  device: { browser: 'SAFARI', os: 'IOS', deviceType: 'MOBILE' },
  mfa: false,
  oidc: true,
}

function listing(sessions: SessionInfo[], truncated = false): Sessions {
  return { sessions, truncated, effectiveWithinSeconds: 900 }
}

const user = () => userEvent.setup()

beforeEach(async () => {
  configureClient({ entry: 'portal' })
  await act(() => i18n.changeLanguage('en'))
})

afterEach(() => {
  session.clear()
  configureClient({ entry: 'admin' })
  vi.unstubAllGlobals()
})

describe('SessionList', () => {
  it('lists the sessions with their device, address and times, this one marked', async () => {
    const list = vi.fn(async () => listing([laptop, phone]))
    render(<SessionList list={list} />)
    const items = await screen.findAllByTestId('session-item')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent('Chrome on Windows')
    expect(items[0]).toHaveTextContent('This device')
    expect(items[0]).toHaveTextContent('Two-step verified')
    expect(items[0]).toHaveTextContent('203.0.113.5')
    expect(within(items[0]).getByRole('button')).toHaveTextContent('Sign out here')
    expect(items[1]).toHaveTextContent('Safari on iOS')
    expect(items[1]).toHaveTextContent('Identity provider')
    expect(items[1]).not.toHaveTextContent('This device')
    expect(within(items[1]).getByRole('button')).toHaveTextContent('Sign out')
    expect(screen.getByTestId('session-revoke-others')).toBeInTheDocument()
    await expectAccessible()
  })

  it('ends another session after confirming, says when it takes effect, and lists again', async () => {
    const list = vi.fn().mockResolvedValueOnce(listing([laptop, phone])).mockResolvedValueOnce(listing([laptop]))
    const revoke = vi.fn(async () => undefined)
    render(<SessionList list={list} revoke={revoke} />)
    const items = await screen.findAllByTestId('session-item')
    await user().click(within(items[1]).getByRole('button', { name: 'Sign out' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('up to 15 minutes')
    await expectAccessible()
    await user().click(within(dialog).getByRole('button', { name: 'Sign out' }))
    expect(revoke).toHaveBeenCalledWith(phone.id, false)
    expect(await screen.findByTestId('session-list-state')).toHaveTextContent('signed out within 15 minutes')
    await waitFor(() => expect(screen.getAllByTestId('session-item')).toHaveLength(1))
    expect(screen.getByTestId('session-none')).toBeInTheDocument()
    expect(screen.queryByTestId('session-revoke-others')).toBeNull()
  })

  it('ends all the others', async () => {
    const list = vi.fn().mockResolvedValueOnce(listing([laptop, phone])).mockResolvedValueOnce(listing([laptop]))
    const revokeOthers = vi.fn(async () => ({ revoked: 1, effectiveWithinSeconds: 900 }))
    render(<SessionList list={list} revokeOthers={revokeOthers} />)
    await user().click(await screen.findByTestId('session-revoke-others'))
    const dialog = await screen.findByRole('alertdialog')
    await user().click(within(dialog).getByRole('button', { name: 'Sign out all other sessions' }))
    expect(revokeOthers).toHaveBeenCalledOnce()
    expect(await screen.findByTestId('session-list-state')).toHaveTextContent('Sessions ended: 1.')
    await waitFor(() => expect(screen.getAllByTestId('session-item')).toHaveLength(1))
  })

  it('asks to sign in again when the session cannot be told apart', async () => {
    const list = vi.fn(async () => listing([{ ...laptop, current: false }, phone]))
    const revokeOthers = vi.fn(async () => {
      throw new ApiError(422, { status: 422, violations: [{ field: null, ruleCode: 'SESSION_UNKNOWN', message: 'No.' }] })
    })
    render(<SessionList list={list} revokeOthers={revokeOthers} />)
    await user().click(await screen.findByTestId('session-revoke-others'))
    await user().click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Sign out all other sessions' }))
    expect(await screen.findByTestId('session-list-error')).toHaveTextContent('Sign in again')
  })

  it('ending this session signs out here', async () => {
    session.store('access', 'refresh')
    const fetch = vi.fn<(input: RequestInfo | URL) => Promise<Response>>(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetch)
    const onSignedOut = vi.fn()
    render(<SessionList list={async () => listing([laptop, phone])} onSignedOut={onSignedOut} />)
    const items = await screen.findAllByTestId('session-item')
    await user().click(within(items[0]).getByRole('button', { name: 'Sign out here' }))
    const dialog = await screen.findByRole('alertdialog')
    await user().click(within(dialog).getByRole('button', { name: 'Sign out here' }))
    await waitFor(() => expect(onSignedOut).toHaveBeenCalledOnce())
    const request = fetch.mock.calls[0][0] as Request
    expect(new URL(request.url).pathname).toBe(`/api/auth/account/sessions/${laptop.id}/revoke`)
    expect(request.method).toBe('POST')
    expect(session.accessToken()).toBeNull()
    expect(session.refreshToken()).toBeNull()
  })

  it('says when the list is cut and when it cannot be read', async () => {
    render(<SessionList list={async () => listing([laptop], true)} />)
    expect(await screen.findByText('Only the 1 most recently used sessions are shown.')).toBeInTheDocument()
    render(<SessionList list={async () => Promise.reject(new Error('offline'))} />)
    expect(await screen.findByTestId('session-list-error')).toHaveTextContent('could not be loaded')
  })

  it('speaks every platform language', async () => {
    await act(() => i18n.changeLanguage('zh'))
    render(<SessionList list={async () => listing([laptop])} />)
    expect(await screen.findByText('Windows 上的 Chrome')).toBeInTheDocument()
    expect(screen.getByText('本机')).toBeInTheDocument()
    await act(() => i18n.changeLanguage('ja'))
    expect(await screen.findByText('Windows の Chrome')).toBeInTheDocument()
  })
})

describe('session helpers', () => {
  const t = (key: string, options?: Record<string, unknown>) => (options ? `${key} ${JSON.stringify(options)}` : key)

  it('name what is known of the device', () => {
    expect(sessionDeviceName(t, { device: { browser: 'OTHER', os: 'OTHER' } })).toBe('sessions.unknownDevice')
    expect(sessionDeviceName(t, {})).toBe('sessions.unknownDevice')
    expect(sessionDeviceName(t, { device: { browser: 'FIREFOX', os: 'OTHER' } })).toBe('sessions.browser.FIREFOX')
    expect(sessionDeviceName(t, { device: { browser: 'OTHER', os: 'LINUX' } })).toBe('sessions.os.LINUX')
  })

  it('round the effective time up to whole minutes', () => {
    expect(effectiveMinutes(900)).toBe(15)
    expect(effectiveMinutes(61)).toBe(2)
    expect(effectiveMinutes(undefined)).toBe(1)
  })
})
