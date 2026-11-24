import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { PageTabs, type PageTab } from './page-tabs'

/*
 * Phase 17zz30 (12 §13.1): the tabs whose current one has a bar under its words, as wide as they are; the default row
 * is unchanged (app-frame.test.tsx).
 */

const tabs: PageTab[] = [
  { key: 'all', label: 'All', path: '/history' },
  { key: 'prizes', label: 'To collect', path: '/history/prizes', badge: 1 },
  { key: 'open', label: 'Unfinished', path: '/history/open' },
]

describe('tabs marked under their words', () => {
  it('marks the current tab with a bar inside its words, 56 px at least, and only that one', async () => {
    const { container } = render(<PageTabs label="Answered" tabs={tabs} current="prizes" indicator="label" />)
    const nav = screen.getByRole('navigation', { name: 'Answered' })
    expect(nav).toHaveAttribute('data-indicator', 'label')
    const current = screen.getByRole('link', { name: /To collect/ })
    expect(current).toHaveAttribute('aria-current', 'page')
    expect(current).toHaveClass('font-bold', 'h-[3.3125rem]', 'text-[0.9375rem]')
    // The bar lies inside the words' box: its width is theirs, whatever the tab's width.
    const words = current.querySelector('[data-slot="page-tab-label"]')!
    expect(words).toHaveClass('min-w-14', 'relative')
    const bar = words.querySelector('[data-slot="page-tab-indicator"]')!
    expect(bar).toHaveClass('absolute', 'inset-x-0', 'bottom-0', 'h-1', 'rounded-full', 'bg-primary')
    expect(nav.querySelectorAll('[data-slot="page-tab-indicator"]')).toHaveLength(1)
    expect(screen.getByRole('link', { name: 'All' })).toHaveClass('font-medium', 'text-muted-foreground')
    expect(within(current).getByText('1')).toBeInTheDocument()
    await expectAccessible(container)
  })

  it('shares the row among a few tabs with fill, else sizes each to its words and hides the scrollbar', () => {
    const { rerender } = render(<PageTabs label="Answered" tabs={tabs} current="all" indicator="label" fill />)
    expect(screen.getByRole('link', { name: 'All' }).closest('li')).toHaveClass('flex-1')
    rerender(<PageTabs label="Answered" tabs={tabs} current="all" indicator="label" />)
    expect(screen.getByRole('link', { name: 'All' }).closest('li')).not.toHaveClass('flex-1')
    expect(screen.getByRole('link', { name: 'All' })).toHaveClass('px-4')
    expect(screen.getByRole('navigation', { name: 'Answered' })).toHaveClass('[scrollbar-width:none]')
  })

  it('moves between the tabs with the arrow keys, Home and End', async () => {
    render(<PageTabs label="Answered" tabs={tabs} current="all" indicator="label" />)
    screen.getByRole('link', { name: 'All' }).focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('link', { name: /To collect/ })).toHaveFocus()
    await userEvent.keyboard('{End}')
    expect(screen.getByRole('link', { name: 'Unfinished' })).toHaveFocus()
    await userEvent.keyboard('{Home}')
    expect(screen.getByRole('link', { name: 'All' })).toHaveFocus()
  })

  it('keeps the underline the row always had without the option', () => {
    render(<PageTabs label="Answered" tabs={tabs} current="all" />)
    expect(screen.getByRole('navigation', { name: 'Answered' })).not.toHaveAttribute('data-indicator')
    expect(screen.getByRole('link', { name: 'All' })).toHaveClass('border-b-2', 'border-primary')
  })
})
