import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { forgetEnvironment, i18n, setEnvironment } from '@jabiz/client'
import { DatabaseIcon } from 'lucide-react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { AppShell, ShellNav, type ShellMenuGroup, type ShellMenuItem } from './app-shell'
import { PageHeader } from './page-header'
import { Button } from './ui/button'

const items: ShellMenuItem[] = [
  {
    key: 'master',
    label: 'Master data',
    children: [
      { key: 'carriers', label: 'Carriers', path: '/data/carriers' },
      { key: 'prices', label: 'Prices', path: '/data/prices' },
    ],
  },
  { key: 'tasks', label: 'Tasks', path: '/tasks', badge: 3 },
  { key: 'data', label: 'Data', path: '/data', icon: DatabaseIcon },
]

function renderShell(current: string) {
  return render(
    <AppShell
      brand={<span>jabiz</span>}
      navigation={<ShellNav label="Main" items={items} isActive={(path) => path === current} />}
      header={<span className="ml-auto">header actions</span>}
    >
      <PageHeader title="Carriers" description="Who carries the goods." actions={<Button>New</Button>} />
    </AppShell>,
  )
}

describe('AppShell and ShellNav', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    window.localStorage.clear()
  })

  it('shows the menu tree with the current page marked and groups that hold it open', async () => {
    renderShell('/data/prices')
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(within(nav).getByRole('link', { name: 'Prices' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('button', { name: 'Master data' })).toHaveAttribute('aria-expanded', 'true')
    expect(within(nav).getByRole('link', { name: 'Data' })).toHaveAttribute('href', '/data')
    expect(within(nav).getByRole('link', { name: 'Tasks' })).not.toHaveAttribute('aria-current')
    expect(screen.getByTestId('menu-tasks')).toHaveTextContent('3')
    expect(screen.getByRole('heading', { level: 1, name: 'Carriers' })).toBeInTheDocument()
    await expectAccessible()
  })

  it('opens and closes groups, also from the keyboard', async () => {
    renderShell('/tasks')
    const group = screen.getByRole('button', { name: 'Master data' })
    expect(group).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('link', { name: 'Carriers' })).toBeNull()
    await userEvent.click(group)
    expect(screen.getByRole('link', { name: 'Carriers' })).toBeInTheDocument()
    group.focus()
    await userEvent.keyboard('{Enter}')
    expect(group).toHaveAttribute('aria-expanded', 'false')
  })

  it('collapses the sidebar off the screen, out of reach, and remembers it', async () => {
    renderShell('/tasks')
    const toggle = screen.getByRole('button', { name: 'Toggle sidebar' })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await userEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(window.localStorage.getItem('jabiz.sidebar')).toBe('closed')
    // No icon strip: menu entries without icons and groups stay usable by expanding again.
    const sidebar = document.querySelector('[data-slot="sidebar"]')!
    expect(sidebar).toHaveAttribute('data-collapsible', 'offcanvas')
    expect(document.querySelector('[data-slot="sidebar-container"]')).toHaveAttribute('inert')
    await userEvent.click(toggle)
    expect(document.querySelector('[data-slot="sidebar-container"]')).not.toHaveAttribute('inert')
  })

  it('shows the menu in sections with rules between them, a headed section as a named group (phase 16zi)', async () => {
    const groups: ShellMenuGroup[] = [
      { key: 'overview', items: [{ key: 'home', label: 'Home', path: '/' }] },
      { key: 'content', label: 'Content', items: [{ key: 'data', label: 'Data', path: '/data', icon: DatabaseIcon },
        { key: 'tasks', label: 'Tasks', path: '/tasks', badge: 3 }] },
      { key: 'empty', label: 'Nothing yet', items: [] },
      { key: 'settings', label: 'Settings', items: [{ key: 'account', label: 'Account', path: '/account' }] },
    ]
    render(
      <AppShell brand={<span>jabiz</span>}
        navigation={<ShellNav label="Main" groups={groups} isActive={(path) => path === '/data'} />}>
        <p>page</p>
      </AppShell>,
    )
    const nav = screen.getByRole('navigation', { name: 'Main' })
    // An empty section is left out; a rule before every section but the first.
    expect(within(nav).queryByTestId('menu-group-empty')).toBeNull()
    expect(within(nav).getAllByTestId('menu-separator')).toHaveLength(2)
    expect(within(nav).getByRole('group', { name: 'Content' })).toContainElement(screen.getByTestId('menu-data'))
    expect(within(nav).getByRole('group', { name: 'Settings' })).toContainElement(screen.getByTestId('menu-account'))
    // The section without a heading is no group of its own.
    expect(within(nav).getAllByRole('group')).toHaveLength(2)
    expect(within(screen.getByTestId('menu-data')).getByRole('link')).toHaveAttribute('aria-current', 'page')
    await expectAccessible()
  })

  it('uses the router link it is given', () => {
    render(
      <ShellNav
        label="Main"
        items={[{ key: 'tasks', label: 'Tasks', path: '/tasks' }]}
        isActive={() => false}
        renderLink={(item, props) => <a href={`#${item.path}`} data-router="yes" {...props} />}
      />,
      { wrapper: ({ children }) => <AppShell brand="b" navigation={children}>{null}</AppShell> },
    )
    expect(screen.getByRole('link', { name: 'Tasks' })).toHaveAttribute('data-router', 'yes')
  })

  describe('on a narrow screen', () => {
    const width = window.innerWidth
    beforeEach(() => {
      Object.defineProperty(window, 'innerWidth', { configurable: true, value: 500 })
    })
    afterEach(() => {
      Object.defineProperty(window, 'innerWidth', { configurable: true, value: width })
    })

    it('closes the menu sheet once a link is followed', async () => {
      render(
        <AppShell
          brand="jabiz"
          navigation={
            <ShellNav
              label="Main"
              items={items}
              isActive={() => false}
              renderLink={(item, props) => (
                <a href={`#${item.path}`} {...props} onClick={(e) => { e.preventDefault(); props.onClick() }} />
              )}
            />
          }
        >
          page
        </AppShell>,
      )
      expect(screen.queryByRole('dialog')).toBeNull()
      await userEvent.click(screen.getByRole('button', { name: 'Toggle sidebar' }))
      const sheet = screen.getByRole('dialog')
      await userEvent.click(within(sheet).getByRole('link', { name: 'Tasks' }))
      expect(screen.queryByRole('dialog')).toBeNull()
    })
  })

  it('says on every page that this is a test system, and nothing on production (phase 16zd)', async () => {
    setEnvironment('staging')
    try {
      const { container, unmount } = renderShell('/data')
      const banner = screen.getByTestId('environment-banner')
      expect(banner).toHaveTextContent('staging')
      expect(banner).toHaveTextContent('Test system, not the production system')
      // The banner and the header bar stay on screen together.
      expect(banner.parentElement).toContainElement(container.querySelector('[data-slot="app-shell-header"]'))
      await expectAccessible(container)
      unmount()
      setEnvironment('production')
      renderShell('/data')
      expect(screen.queryByTestId('environment-banner')).not.toBeInTheDocument()
    } finally {
      forgetEnvironment()
    }
  })
})
