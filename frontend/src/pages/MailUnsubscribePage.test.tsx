import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import MailUnsubscribePage from './MailUnsubscribePage'

function page(search: string) {
  render(
    <MemoryRouter initialEntries={[`/mail/unsubscribe${search}`]}>
      <Routes>
        <Route path="/mail/unsubscribe" element={<MailUnsubscribePage />} />
      </Routes>
    </MemoryRouter>,
  )
}

function respond(status: number) {
  const fetchMock = vi.fn(async () => new Response(status === 204 ? null : '{}', {
    status,
    headers: { 'Content-Type': status === 204 ? 'text/plain' : 'application/problem+json' },
  }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('MailUnsubscribePage', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts the token of the link once and confirms', async () => {
    const fetchMock = respond(204)
    page('?token=abc.def.ghi')
    await waitFor(() => expect(screen.getByTestId('unsubscribe-state').dataset.state).toBe('done'))
    expect(screen.getByTestId('unsubscribe-state').textContent).toContain('no longer receive')
    expect(fetchMock).toHaveBeenCalledOnce()
    const request = (fetchMock.mock.calls[0] as unknown[])[0] as Request
    expect(request.url).toContain('/api/auth/mail/unsubscribe')
    expect(await request.json()).toEqual({ token: 'abc.def.ghi' })
  })

  it('says when the link is invalid', async () => {
    respond(422)
    page('?token=forged')
    await waitFor(() => expect(screen.getByTestId('unsubscribe-state').dataset.state).toBe('invalid'))
  })

  it('needs a token and sends nothing without one', () => {
    const fetchMock = respond(204)
    page('')
    expect(screen.getByTestId('unsubscribe-state').dataset.state).toBe('invalid')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('speaks the language of the interface', async () => {
    respond(500)
    await i18n.changeLanguage('ja')
    page('?token=t')
    await waitFor(() => expect(screen.getByTestId('unsubscribe-state').textContent).toContain('問題が発生しました'))
  })
})
