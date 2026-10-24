import { forgetEnvironment, i18n, setEnvironment } from '@jabiz/client'
import { act, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { MailUnsubscribe } from './index'

/** Answers every request with `status`; a pending promise while `hold` is true. */
function respond(status: number, hold = false) {
  const fetchMock = vi.fn(() => hold
    ? new Promise<Response>(() => {})
    : Promise.resolve(new Response(status === 204 ? null : '{}', {
      status,
      headers: { 'Content-Type': status === 204 ? 'text/plain' : 'application/problem+json' },
    })))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function state() {
  return screen.getByTestId('unsubscribe-state').dataset.state
}

beforeEach(async () => {
  await act(() => i18n.changeLanguage('en'))
  // The frame's environment banner (16zd) would otherwise ask the server: the counted calls are the page's alone.
  setEnvironment('test')
})

afterEach(() => {
  vi.unstubAllGlobals()
  forgetEnvironment()
})

describe('MailUnsubscribe', () => {
  it('posts the token of the link once and confirms', async () => {
    const fetchMock = respond(204)
    render(<MailUnsubscribe search="?token=abc.def.ghi" brand="Shop" />)
    await waitFor(() => expect(state()).toBe('done'))
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Shop')
    expect(screen.getByTestId('unsubscribe-state')).toHaveTextContent('no longer receive')
    expect(fetchMock).toHaveBeenCalledOnce()
    const request = (fetchMock.mock.calls[0] as unknown[])[0] as Request
    expect(request.method).toBe('POST')
    expect(new URL(request.url).pathname).toBe('/api/auth/mail/unsubscribe')
    expect(await request.json()).toEqual({ token: 'abc.def.ghi' })
    await expectAccessible()
  })

  it('says it is working while the answer is pending', async () => {
    respond(204, true)
    render(<MailUnsubscribe search="?token=t" />)
    expect(state()).toBe('working')
    expect(screen.getByTestId('unsubscribe-state')).toHaveTextContent('Turning these notifications off')
    // One live region whose content changes; no node with a role of its own is inserted.
    expect(screen.getByTestId('unsubscribe-state')).toHaveAttribute('aria-live', 'polite')
    expect(screen.queryByRole('alert')).toBeNull()
    await expectAccessible()
  })

  it('says when the link is invalid', async () => {
    respond(422)
    render(<MailUnsubscribe search="?token=forged" />)
    await waitFor(() => expect(state()).toBe('invalid'))
    expect(screen.getByTestId('unsubscribe-state')).toHaveTextContent('This unsubscribe link is invalid.')
    expect(screen.queryByRole('alert')).toBeNull()
    await expectAccessible()
  })

  it('needs a token and sends nothing without one', () => {
    const fetchMock = respond(204)
    render(<MailUnsubscribe search="" />)
    expect(state()).toBe('invalid')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('says when something else went wrong, in the language of the interface', async () => {
    respond(500)
    await act(() => i18n.changeLanguage('ja'))
    render(<MailUnsubscribe search="?token=t" />)
    await waitFor(() => expect(state()).toBe('failed'))
    expect(screen.getByTestId('unsubscribe-state')).toHaveTextContent('問題が発生しました')
    await expectAccessible()
  })

  it('reads the address bar when given no search, and takes the token out of it', async () => {
    const fetchMock = respond(204)
    window.history.replaceState(null, '', '/portal/mail/unsubscribe?lang=en&token=from-bar#top')
    render(<MailUnsubscribe />)
    expect(window.location.pathname + window.location.search + window.location.hash)
      .toBe('/portal/mail/unsubscribe?lang=en#top')
    await waitFor(() => expect(state()).toBe('done'))
    const request = (fetchMock.mock.calls[0] as unknown[])[0] as Request
    expect(await request.json()).toEqual({ token: 'from-bar' })
    window.history.replaceState(null, '', '/')
  })

  it('posts once in StrictMode, which runs effects twice', async () => {
    const fetchMock = respond(204)
    render(<StrictMode><MailUnsubscribe search="?token=strict" /></StrictMode>)
    await waitFor(() => expect(state()).toBe('done'))
    expect(fetchMock).toHaveBeenCalledOnce()
  })
})
