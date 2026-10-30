import { noteNetworkFailure, noteResponse } from '@jabiz/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MaintenanceRedirect, maintenancePage } from './maintenance'

const MAINTENANCE = () => new Response('<html>maintenance</html>', { status: 503,
  headers: { 'Content-Type': 'text/html', 'Retry-After': '60' } })

function mount() {
  render(<QueryClientProvider client={new QueryClient()}><MaintenanceRedirect /></QueryClientProvider>)
}

afterEach(() => vi.restoreAllMocks())

describe('a page open when the service goes into maintenance', () => {
  it('stays while the service answers', async () => {
    const page = vi.spyOn(globalThis, 'fetch')
    const open = vi.spyOn(maintenancePage, 'open').mockImplementation(() => {})
    mount()
    await act(async () => {})
    expect(page).not.toHaveBeenCalled()
    expect(open).not.toHaveBeenCalled()
  })

  it('goes to the maintenance page, whichever page it is, when its own address answers with it', async () => {
    window.history.replaceState(null, '', '/history')
    const page = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => MAINTENANCE())
    const open = vi.spyOn(maintenancePage, 'open').mockImplementation(() => {})
    mount()
    await act(async () => noteResponse(MAINTENANCE()))
    await vi.waitFor(() => expect(open).toHaveBeenCalledTimes(1))
    expect(page).toHaveBeenCalledWith(`${window.location.origin}/history`, expect.objectContaining({ cache: 'no-store' }))
  })

  it('stays with the notice when its address does not answer with the maintenance page', async () => {
    const open = vi.spyOn(maintenancePage, 'open').mockImplementation(() => {})
    // The server cannot be reached at all: a reload would get the shell from the Service Worker and fail again.
    const page = vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    mount()
    await act(async () => noteNetworkFailure(new TypeError('Failed to fetch')))
    await vi.waitFor(() => expect(page).toHaveBeenCalled())
    // The page itself served (no proxy in front, as in development): nothing to go to.
    page.mockResolvedValue(new Response('<html>app</html>', { status: 200, headers: { 'Content-Type': 'text/html' } }))
    await act(async () => noteResponse(MAINTENANCE()))
    await vi.waitFor(() => expect(page).toHaveBeenCalledTimes(2))
    await act(async () => {})
    expect(open).not.toHaveBeenCalled()
  })
})
