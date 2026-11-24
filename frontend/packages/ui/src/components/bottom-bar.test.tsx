import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BellIcon, BellRingIcon, HomeIcon, MenuIcon, SearchIcon } from 'lucide-react'
import { describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { BottomBar, type BottomBarItem } from './bottom-bar'

/*
 * Phase 17zz30 (12 §13.1): the bottom menu of icons alone, its solid current entry and its fading as the page scrolls;
 * the default bar is unchanged (app-frame.test.tsx).
 */

const items: BottomBarItem[] = [
  { key: 'home', label: 'Home', path: '/', icon: HomeIcon, activeIcon: MenuIcon },
  { key: 'search', label: 'Search', path: '/search', icon: SearchIcon },
  { key: 'inbox', label: 'Notifications, 3 unread', path: '/inbox', icon: BellIcon, activeIcon: BellRingIcon, badge: 3 },
]

const at = (current: string) => (path: string) => path === current

describe('the bottom menu of icons alone', () => {
  it('shows only the icons, names each entry for screen readers and marks the current one', async () => {
    const { container } = render(<BottomBar label="Areas" items={items} isActive={at('/')} variant="icons" />)
    const nav = screen.getByRole('navigation', { name: 'Areas' })
    expect(nav).toHaveAttribute('data-variant', 'icons')
    expect(nav.querySelector('ul')).toHaveClass('h-(--bottom-bar-icons-height)')
    const home = within(nav).getByRole('link', { name: 'Home' })
    expect(home).toHaveAttribute('aria-current', 'page')
    // The name is there for screen readers only: nothing but the icon shows.
    expect(within(home).getByText('Home')).toHaveClass('sr-only')
    expect(within(nav).getByRole('link', { name: /Notifications, 3 unread/ })).not.toHaveAttribute('aria-current')
    await expectAccessible(container)
  })

  it('draws the current entry with its solid icon, the others with their own', () => {
    render(<BottomBar label="Areas" items={items} isActive={at('/')} variant="icons" />)
    const home = screen.getByRole('link', { name: 'Home' })
    expect(home.querySelector('svg')).toHaveClass('lucide-menu')
    expect(home.querySelector('svg')).toHaveAttribute('stroke-width', '2.6')
    const search = screen.getByRole('link', { name: 'Search' })
    expect(search.querySelector('svg')).toHaveClass('lucide-search')
    expect(search.querySelector('svg')).toHaveAttribute('stroke-width', '1.9')
    // An entry not shown keeps its own icon even when it has a solid one.
    expect(screen.getByRole('link', { name: /Notifications/ }).querySelector('svg')).toHaveClass('lucide-bell')
  })

  it('fades as a whole when told to, and can still be used', async () => {
    const follow = vi.fn((event: Event) => event.preventDefault())
    render(<BottomBar label="Areas" items={items} isActive={at('/')} variant="icons" dim={0.3}
      renderLink={(item, props) => <a href={item.path} onClick={(e) => follow(e.nativeEvent)} {...props} />} />)
    const nav = screen.getByRole('navigation', { name: 'Areas' })
    expect(nav).toHaveStyle({ opacity: '0.3' })
    expect(nav).toHaveClass('transition-opacity')
    await userEvent.click(screen.getByRole('link', { name: 'Search' }))
    expect(follow).toHaveBeenCalledOnce()
  })

  it('keeps "More" as an icon named for screen readers', async () => {
    render(<BottomBar label="Areas" items={items} isActive={at('/')} variant="icons" more={<a href="/all">All</a>} />)
    const more = screen.getByRole('button', { name: 'More' })
    expect(within(more).getByText('More')).toHaveClass('sr-only')
    await userEvent.click(more)
    expect(await screen.findByTestId('bottom-bar-sheet')).toBeInTheDocument()
  })

  it('leaves the bar as it was without the new options', () => {
    render(<BottomBar label="Areas" items={items} isActive={at('/')} />)
    const nav = screen.getByRole('navigation', { name: 'Areas' })
    expect(nav).toHaveAttribute('data-variant', 'labels')
    expect(nav).not.toHaveAttribute('style')
    expect(within(nav).getByText('Home')).not.toHaveClass('sr-only')
    expect(screen.getByRole('link', { name: 'Home' }).querySelector('svg')).toHaveClass('lucide-house')
  })
})
