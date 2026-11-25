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
    expect(main.className).toContain('pb-[calc(var(--bottom-bar-offset)')
    expect(main.className).toContain('md:pb-4')
  })

  it('puts the page in the middle column of a wide screen, 600 px between two dividers (Q113 §9.1 "宽屏")', async () => {
    frameServer()
    window.history.replaceState(null, '', '/')
    render(<App />)
    await screen.findByTestId('home')
    expect(screen.getByRole('main')).toHaveClass('pt-0')
    expect(screen.getByTestId('x-column')).toHaveClass('md:w-(--x-column)', 'md:border-x')
    // The left column (icons alone, names from xl) and the right one (from xl): drawn at once, shown by the width.
    expect(screen.getByTestId('side-rail')).toHaveClass('md:flex', 'w-(--x-rail-compact)', 'xl:w-(--x-rail)')
    expect(screen.getByTestId('side-aside')).toHaveClass('hidden', 'xl:flex', 'w-(--x-aside)', 'ml-(--x-aside-gap)')
  })
})

describe('the navigation (Q113 §9.2)', () => {
  it('has a bottom menu of four icons, their names for screen readers, the current one marked', async () => {
    frameServer()
    window.history.replaceState(null, '', '/history')
    render(<App />)
    await screen.findByTestId('history')
    const bar = document.querySelector('[data-slot="bottom-bar"]')!
    expect(bar).toHaveAttribute('data-variant', 'icons')
    const entries = [...bar.querySelectorAll('a')]
    expect(entries.map((a) => a.textContent)).toEqual(['Home', 'Search', 'Answered', 'Notifications'])
    expect(screen.getByTestId('bottom-bar-history')).toHaveAttribute('aria-current', 'page')
    for (const gone of ['bottom-bar-scan', 'bottom-bar-me']) expect(screen.queryByTestId(gone)).toBeNull()
  })

  it('scans from the floating button of a first-level page, and from no page under another', async () => {
    frameServer()
    window.history.replaceState(null, '', '/history')
    const { unmount } = render(<App />)
    await screen.findByTestId('history')
    const fab = screen.getByTestId('page-fab')
    expect(fab).toHaveAttribute('href', '/scan')
    expect(fab).toHaveAccessibleName('Scan')
    expect(fab).toHaveAttribute('data-variant', 'timeline')
    expect(fab).toHaveClass('md:hidden')
    unmount()
    window.history.replaceState(null, '', '/account/profile')
    render(<App />)
    await screen.findByTestId('header-back')
    expect(screen.queryByTestId('page-fab')).toBeNull()
  })

  it('opens the drawer from the avatar: who is signed in, the rows of "Me", and a row followed closes it', async () => {
    frameServer()
    window.history.replaceState(null, '', '/history')
    render(<App />)
    await screen.findByTestId('history')
    const avatar = screen.getByTestId('head-avatar')
    expect(avatar).toHaveAttribute('aria-haspopup', 'dialog')
    await userEvent.click(avatar)
    const drawer = await screen.findByTestId('drawer')
    expect(drawer).toHaveClass('w-(--x-drawer)')
    for (const row of ['me', 'kudos', 'prizes', 'certificates', 'maps', 'referrals', 'help']) {
      expect(screen.getByTestId(`drawer-${row}`)).toBeInTheDocument()
    }
    expect(screen.getByTestId('drawer-sign-out')).toBeInTheDocument()
    await expectAccessible()
    await userEvent.click(screen.getByTestId('drawer-me'))
    await screen.findByTestId('account')
    expect(window.location.pathname).toBe('/account')
    await waitFor(() => expect(screen.queryByTestId('drawer')).toBeNull())
  })

  it('searches on a page of its own: a hint first, the results under the box', async () => {
    frameServer()
    window.history.replaceState(null, '', '/history')
    render(<App />)
    await screen.findByTestId('history')
    await userEvent.click(screen.getByTestId('bottom-bar-search'))
    await screen.findByTestId('search-hint')
    expect(screen.getByTestId('bottom-bar-search')).toHaveAttribute('aria-current', 'page')
    await userEvent.type(screen.getByTestId('feed-search'), 'tea')
    await userEvent.click(screen.getByTestId('feed-search-submit'))
    await screen.findByTestId('search-results')
    expect(window.location.pathname + window.location.search).toBe('/search?q=tea')
    expect(screen.getByTestId('feed-searched')).toHaveTextContent('tea')
    expect(screen.getByTestId('page-fab')).toBeInTheDocument()
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

  it('heads the home with the avatar and the mark in the middle, the filters at the right (search is a page)', async () => {
    frameServer()
    window.history.replaceState(null, '', '/')
    render(<App />)
    await screen.findByTestId('home')
    const head = screen.getByRole('banner')
    expect(head.querySelector('[data-head="home"]')).not.toBeNull()
    expect(screen.getByTestId('head-avatar')).toHaveAccessibleName('Open the menu')
    expect(screen.getByTestId('head-mark')).toHaveTextContent('QuizBuks')
    expect(screen.getByTestId('feed-filters-open')).toBeInTheDocument()
    expect(screen.queryByTestId('search-open')).toBeNull()
  })

  it('names the spec\'s lengths once, as the frame\'s variables, and fades the bottom menu with the scrolling', async () => {
    frameServer()
    window.history.replaceState(null, '', '/history')
    render(<App />)
    await screen.findByTestId('history')
    const frame = document.querySelector('[data-slot="x-frame"]') as HTMLElement
    expect(frame.style.getPropertyValue('--x-header')).toBe('53px')
    expect(frame.style.getPropertyValue('--x-back')).toBe('36px')
    expect(frame.style.getPropertyValue('--x-column')).toBe('600px')
    expect(frame.style.getPropertyValue('--x-drawer')).toBe('280px')
    // At the top, the bar is all there; it takes the platform's fading (0.17 s).
    const bar = document.querySelector('[data-slot="bottom-bar"]')!
    expect(bar).toHaveStyle({ opacity: '1' })
    expect(bar).toHaveClass('transition-opacity')
  })
})
