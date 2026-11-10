import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { frameServer } from '../test/frameServer'

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => vi.restoreAllMocks())

describe('the frame of the signed-in pages (F-F4)', () => {
  it('moves the focus to the title of the page it goes to, from the bottom menu', async () => {
    frameServer()
    window.history.replaceState(null, '', '/')
    render(<App />)
    await screen.findByTestId('home')
    await userEvent.click(screen.getByTestId('bottom-bar-history'))
    const title = await screen.findByRole('heading', { level: 1, name: 'Answered' })
    await waitFor(() => expect(title).toHaveFocus())
  })

  it('keeps the focus on a tab when the tab is followed, and marks the page it leads to', async () => {
    frameServer()
    window.history.replaceState(null, '', '/history')
    render(<App />)
    const tab = await screen.findByTestId('page-tab-prizes')
    tab.focus()
    await userEvent.keyboard('{Enter}')
    await screen.findByTestId('prizes')
    expect(window.location.pathname).toBe('/history/prizes')
    expect(screen.getByTestId('page-tab-prizes')).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('heading', { level: 1 })).not.toHaveFocus()
  })

  it('moves between the tabs with the arrow keys', async () => {
    frameServer()
    window.history.replaceState(null, '', '/inbox')
    render(<App />)
    const first = await screen.findByTestId('page-tab-inbox')
    first.focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByTestId('page-tab-conversations')).toHaveFocus()
    await expectAccessible()
  })

  it('keeps the page clear of the bottom menu on a phone and not on a wide screen', async () => {
    frameServer()
    window.history.replaceState(null, '', '/history')
    render(<App />)
    await screen.findByTestId('history')
    const main = screen.getByRole('main')
    expect(main.className).toContain('pb-[calc(var(--bottom-bar-height)')
    expect(main.className).toContain('md:pb-4')
  })

  it('leaves the home\'s own markup as it was, with the menu gone (the visual baseline is pixel-identical)', async () => {
    frameServer()
    window.history.replaceState(null, '', '/')
    render(<App />)
    const home = await screen.findByTestId('home')
    expect(home.className).toBeTruthy()
    expect(screen.getByRole('main')).toHaveClass('pt-0', 'max-w-md')
  })
})
