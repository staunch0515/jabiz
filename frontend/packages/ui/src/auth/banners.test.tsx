import { i18n, noteResponse, resetServiceAvailability } from '@jabiz/client'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { OfflineBanner } from './index'

function maintenance() {
  return new Response('<!doctype html><title>Maintenance</title>', { status: 503,
    headers: { 'Content-Type': 'text/html', 'Retry-After': '300' } })
}

beforeEach(async () => {
  resetServiceAvailability()
  await act(() => i18n.changeLanguage('en'))
})
afterEach(() => {
  resetServiceAvailability()
  vi.restoreAllMocks()
})

describe('the page while the service does not answer (17x)', () => {
  it('says so once, in front of the page, with the next check, and closes once a check finds it back', async () => {
    render(<OfflineBanner />)
    expect(screen.queryByTestId('service-unavailable')).toBeNull()
    act(() => noteResponse(maintenance()))
    const dialog = await screen.findByRole('alertdialog', { name: 'Down for maintenance' })
    expect(dialog).toHaveTextContent('carries on by itself once it is back')
    expect(screen.getByTestId('service-unavailable-next')).toHaveTextContent(/Next check in about (299|300|301) seconds/)
    await expectAccessible()
    // It cannot be dismissed: nothing behind it works.
    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()

    // Still down when checked now: it stays.
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(maintenance())
      .mockResolvedValueOnce(new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }))
    await userEvent.click(screen.getByTestId('service-unavailable-retry'))
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    // Back: it closes.
    await userEvent.click(screen.getByTestId('service-unavailable-retry'))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
  })

  it('in Chinese and Japanese too', async () => {
    render(<OfflineBanner />)
    act(() => noteResponse(maintenance()))
    await act(() => i18n.changeLanguage('zh'))
    expect(await screen.findByRole('alertdialog', { name: '系统维护中' })).toBeInTheDocument()
    expect(screen.getByTestId('service-unavailable-retry')).toHaveTextContent('立即重试')
    await act(() => i18n.changeLanguage('ja'))
    expect(await screen.findByRole('alertdialog', { name: 'メンテナンス中' })).toBeInTheDocument()
  })

  it('leaves an offline browser to the offline banner', async () => {
    const onLine = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    act(() => noteResponse(maintenance()))
    render(<OfflineBanner />)
    act(() => {
      window.dispatchEvent(new Event('offline'))
    })
    expect(screen.getByTestId('offline-banner')).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).toBeNull()
    onLine.mockRestore()
  })
})
