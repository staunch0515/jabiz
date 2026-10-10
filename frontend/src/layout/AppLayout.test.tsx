import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AppearanceProvider } from '@jabiz/ui'
import { Boxes } from 'lucide-react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { axe } from 'vitest-axe'
import i18n from '../i18n'
import AppLayout from './AppLayout'

const auth = {
  userId: 'u-1',
  displayName: 'Ann Lee',
  signOut: vi.fn(async () => {}),
  can: (p: string) => p !== 'audit.read',
  idleTimeoutSeconds: 900 as number | null,
  dataPeriod: null as { from: string | null; to: string | null } | null,
}
vi.mock('../auth/AuthContext', () => ({ useAuth: () => auth }))

const idle = vi.hoisted(() => ({ onIdle: null as null | (() => void), seconds: null as number | null }))
vi.mock('../auth/useIdleLock', () => ({
  useIdleLock: (seconds: number | null, onIdle: () => void) => {
    idle.seconds = seconds
    idle.onIdle = onIdle
  },
}))

vi.mock('../meta/hooks', () => ({
  useMenus: () => ({
    data: [
      {
        code: 'master',
        label: 'Master data',
        children: [{ code: 'carriers', label: 'Carriers', path: '/data/carriers' }],
      },
    ],
  }),
  useMyTasks: () => ({ data: { total: 3 } }),
  useQueryCatalog: () => ({ data: [{ id: 'r', report: true }] }),
  useImportCatalog: () => ({ data: [] }),
}))

vi.mock('../extension', () => ({
  extension: {
    menu: [
      { key: 'stock', label: 'menu.stock', path: '/commerce/stock', icon: Boxes, permission: 'commerce.stock.read' },
      { key: 'secret', label: 'menu.secret', path: '/secret', permission: 'audit.read' },
    ],
  },
}))

function renderLayout(path = '/data/carriers') {
  return render(
    <AppearanceProvider>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="*" element={<p>page</p>} />
          </Route>
          <Route path="/login" element={<p>sign-in page</p>} />
        </Routes>
      </MemoryRouter>
    </AppearanceProvider>,
  )
}

describe('AppLayout (the shell, decision D34)', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    i18n.addResourceBundle('en', 'app', { menu: { stock: 'Stock', secret: 'Secret' } }, true, true)
    auth.dataPeriod = null
    auth.signOut.mockClear()
    window.localStorage.clear()
  })

  it('lists the server menus, the extension entries and the fixed pages the user may use', () => {
    renderLayout()
    const nav = screen.getByRole('navigation', { name: 'Navigation' })
    expect(within(nav).getByRole('button', { name: 'Master data' })).toHaveAttribute('aria-expanded', 'true')
    expect(within(nav).getByRole('link', { name: 'Carriers' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Stock' })).toHaveAttribute('href', '/commerce/stock')
    expect(within(nav).queryByRole('link', { name: 'Secret' })).toBeNull()
    expect(within(nav).getByRole('link', { name: 'Reports' })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: 'Imports' })).toBeNull()
    expect(within(nav).queryByRole('link', { name: 'Audit' })).toBeNull()
    expect(within(nav).getByRole('link', { name: 'Data' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Processes' })).toBeInTheDocument()
  })

  it('counts the open tasks in the header and the menu', () => {
    renderLayout()
    expect(screen.getByRole('link', { name: '3 open tasks' })).toHaveAttribute('href', '/tasks')
    expect(screen.getByTestId('menu-tasks')).toHaveTextContent('3')
  })

  it('switches the language from the header', async () => {
    renderLayout()
    await userEvent.click(screen.getByTestId('language-switch'))
    await userEvent.click(screen.getByRole('menuitemradio', { name: '日本語' }))
    await waitFor(() => expect(i18n.language).toBe('ja'))
    expect(screen.getByRole('link', { name: 'データ' })).toBeInTheDocument()
    await act(() => i18n.changeLanguage('en'))
  })

  it('switches the appearance from the header', async () => {
    renderLayout()
    await userEvent.click(screen.getByRole('button', { name: /Appearance/ }))
    await userEvent.click(screen.getByRole('menuitemradio', { name: 'Dark' }))
    expect(document.documentElement).toHaveClass('dark')
    // The Ant Design pages keep the light tokens whatever the choice.
    expect(screen.getByText('page').closest('.light')).not.toBeNull()
    await userEvent.click(screen.getByRole('button', { name: /Appearance/ }))
    await userEvent.click(screen.getByRole('menuitemradio', { name: 'Light' }))
  })

  it('shows a session limited to a period', () => {
    auth.dataPeriod = { from: '2025-01-01T00:00:00Z', to: null }
    renderLayout()
    expect(screen.getByTestId('data-period')).toHaveTextContent('…')
  })

  it('signs out from the account menu, and when idle', async () => {
    renderLayout()
    await userEvent.click(screen.getByRole('button', { name: 'Ann Lee' }))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Sign out' }))
    expect(auth.signOut).toHaveBeenCalledTimes(1)
    expect(await screen.findByText('sign-in page')).toBeInTheDocument()

    renderLayout()
    expect(idle.seconds).toBe(900)
    act(() => idle.onIdle?.())
    await waitFor(() => expect(auth.signOut).toHaveBeenCalledTimes(2))
  })

  it('has no axe violations in the shell, light and dark', async () => {
    renderLayout()
    for (const dark of [false, true]) {
      document.documentElement.classList.toggle('dark', dark)
      const results = await axe(document.body, {
        runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
        rules: { 'color-contrast': { enabled: false } },
      })
      expect(results.violations.map((v) => v.id)).toEqual([])
    }
    document.documentElement.classList.remove('dark')
  })
})
