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

describe('the heads of the pages (Q113 §9.1, P4)', () => {
  it('heads a first-level page with the avatar and the title, its tabs in the same sticky band', async () => {
    frameServer()
    window.history.replaceState(null, '', '/history')
    render(<App />)
    const title = await screen.findByRole('heading', { level: 1, name: 'Answered' })
    const head = title.closest('[data-slot="x-head"]') as HTMLElement
    expect(head.tagName).toBe('HEADER')
    expect(head.querySelector('[data-head="top"]')).not.toBeNull()
    expect(head.querySelector('[data-slot="page-tabs"]')).toHaveAttribute('data-indicator', 'label')
    expect(head).toHaveClass('sticky', 'top-0')
    // The frame's own head stands back: one head on the page.
    expect(document.querySelectorAll('[data-slot="x-head"]')).toHaveLength(1)
    await expectAccessible()
  })

  it('heads a page under another with the way back to the page above it', async () => {
    frameServer()
    window.history.replaceState(null, '', '/account/profile')
    render(<App />)
    const back = await screen.findByTestId('header-back')
    expect(back.closest('[data-head="sub"]')).not.toBeNull()
    expect(back).toHaveAttribute('href', '/account')
    expect(back).toHaveAccessibleName('Back to Me')
    expect(document.querySelectorAll('[data-slot="x-head"]')).toHaveLength(1)
  })

  it('heads the home with the avatar and the mark in the middle, the search at the right', async () => {
    frameServer()
    window.history.replaceState(null, '', '/')
    render(<App />)
    await screen.findByTestId('home')
    const head = screen.getByRole('banner')
    expect(head.querySelector('[data-head="home"]')).not.toBeNull()
    expect(screen.getByTestId('head-avatar')).toHaveAttribute('href', '/account')
    expect(screen.getByTestId('head-mark')).toHaveTextContent('QuizBuks')
    expect(screen.getByTestId('search-open')).toBeInTheDocument()
  })

  it('names the spec\'s lengths once, as the frame\'s variables, and fades the bottom menu with the scrolling', async () => {
    frameServer()
    window.history.replaceState(null, '', '/history')
    render(<App />)
    await screen.findByTestId('history')
    const frame = document.querySelector('[data-slot="x-frame"]') as HTMLElement
    expect(frame.style.getPropertyValue('--x-header')).toBe('53px')
    expect(frame.style.getPropertyValue('--x-back')).toBe('36px')
    // At the top, the bar is all there; it takes the platform's fading (0.17 s).
    const bar = document.querySelector('[data-slot="bottom-bar"]')!
    expect(bar).toHaveStyle({ opacity: '1' })
    expect(bar).toHaveClass('transition-opacity')
  })
})
