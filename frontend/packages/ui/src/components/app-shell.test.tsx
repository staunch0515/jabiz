import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { i18n } from '@jabiz/client'
import { DatabaseIcon } from 'lucide-react'
import { beforeEach, describe, expect, it } from 'vitest'
import { expectAccessible } from '../test/axe'
import { AppShell, ShellNav, type ShellMenuItem } from './app-shell'
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

  it('collapses the sidebar with its named toggle and remembers it', async () => {
    renderShell('/tasks')
    const toggle = screen.getByRole('button', { name: 'Toggle sidebar' })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await userEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(window.localStorage.getItem('jabiz.sidebar')).toBe('closed')
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
})
