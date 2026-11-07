import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { i18n } from '@jabiz/client'
import { HomeIcon, InboxIcon, PlusIcon, ScanLineIcon, SendIcon } from 'lucide-react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { AppShell, ShellNav, type ShellMenuItem } from './app-shell'
import { BottomBar, type BottomBarItem } from './bottom-bar'
import { GlobalCreate } from './global-create'
import { PageHeader } from './page-header'
import { PageTabs, type PageTab } from './page-tabs'
import { DetailPage, FormPage, ListPage } from './page-templates'

/*
 * The application frame and the page templates of phase 17zj: the main action beside the title (the floating button
 * on a phone), the page's tabs, the create menu, the icon strip, the bottom menu, the focus on a new page, and the
 * list, detail and form templates. Each passes axe in both appearances.
 */

const narrow = () => Object.defineProperty(window, 'innerWidth', { configurable: true, value: 360 })
const wide = () => Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1920 })

const menu: ShellMenuItem[] = [
  { key: 'home', label: 'Home', path: '/', icon: HomeIcon },
  { key: 'publications', label: 'Publications', path: '/publications', icon: SendIcon, badge: 2 },
  { key: 'inbox', label: 'Messages', path: '/inbox', icon: InboxIcon },
]

const bottom: BottomBarItem[] = [
  { key: 'home', label: 'Home', path: '/', icon: HomeIcon },
  { key: 'publications', label: 'Publications', path: '/publications', icon: SendIcon },
  { key: 'scan', label: 'Scan', path: '/scan', icon: ScanLineIcon, raised: true },
  { key: 'inbox', label: 'Messages', path: '/inbox', icon: InboxIcon, badge: 4 },
]

const tabs: PageTab[] = [
  { key: 'list', label: 'List', path: '/publications' },
  { key: 'tree', label: 'Structure', path: '/publications/tree' },
  { key: 'stats', label: 'Statistics', path: '/publications/stats', badge: 1 },
]

function Shell({ current, children, onCreate }: { current: string; children: React.ReactNode; onCreate?: () => void }) {
  const isActive = (path: string) => path === current
  const nav = <ShellNav label="Main" items={menu} isActive={isActive} />
  return (
    <AppShell
      brand={<span>Sponsor</span>}
      collapsible="icon"
      create={<GlobalCreate items={[
        { key: 'quiz', label: 'New quiz', icon: PlusIcon, path: '/quizzes/new' },
        { key: 'notice', label: 'Send a notice', onSelect: onCreate },
      ]} />}
      navigation={nav}
      bottomBar={<BottomBar label="Areas" items={bottom} isActive={isActive} more={nav} />}
      routeKey={current}
    >
      {children}
    </AppShell>
  )
}

describe('the application frame', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    window.localStorage.clear()
    wide()
  })

  afterEach(() => {
    wide()
  })

  it('puts the main action right after the title on a wide screen, with the area icon and tabs', async () => {
    const create = vi.fn()
    render(
      <PageHeader
        title="Publications"
        icon={SendIcon}
        description="What you published."
        primaryAction={{ label: 'New publication', icon: PlusIcon, onClick: create }}
        tabs={<PageTabs label="Publications" tabs={tabs} current="tree" />}
      />,
    )
    const title = screen.getByRole('heading', { level: 1, name: 'Publications' })
    const button = screen.getByRole('button', { name: 'New publication' })
    // In the title's own row, right after it, not at the far end of the header.
    expect(title.parentElement).toBe(button.parentElement)
    expect(title.nextElementSibling).toBe(button)
    expect(screen.queryByTestId('page-fab')).toBeNull()
    await userEvent.click(button)
    expect(create).toHaveBeenCalledOnce()
    expect(screen.getByRole('link', { name: 'Structure' })).toHaveAttribute('aria-current', 'page')
    await expectAccessible()
  })

  it('makes the main action a floating button on a phone', async () => {
    narrow()
    const create = vi.fn()
    render(<PageHeader title="Publications" primaryAction={{ label: 'New publication', onClick: create }} />)
    expect(screen.queryByTestId('page-primary-action')).toBeNull()
    const fab = screen.getByRole('button', { name: 'New publication' })
    expect(fab).toHaveAttribute('data-testid', 'page-fab')
    await userEvent.click(fab)
    expect(create).toHaveBeenCalledOnce()
    await expectAccessible()
  })

  it('renders a link action through the router', () => {
    render(<PageHeader title="Quizzes" primaryAction={{
      label: 'New quiz',
      renderLink: (props) => <a href="/quizzes/new" {...props} />,
    }} />)
    expect(screen.getByRole('link', { name: 'New quiz' })).toHaveAttribute('href', '/quizzes/new')
  })

  it('keeps the head it always had without the new parts', () => {
    render(<PageHeader title="Carriers" actions={<button type="button">Export</button>} />)
    const title = screen.getByTestId('page-title')
    expect(title).toHaveTextContent('Carriers')
    expect(title.querySelector('svg')).toBeNull()
    expect(screen.queryByTestId('page-primary-action')).toBeNull()
  })

  it('moves between the tabs with the arrow keys, Home and End, and shows counts', async () => {
    render(<PageTabs label="Publications" tabs={tabs} current="list" />)
    const nav = screen.getByRole('navigation', { name: 'Publications' })
    const [list, tree, stats] = within(nav).getAllByRole('link')
    expect(list).toHaveAttribute('aria-current', 'page')
    expect(tree).not.toHaveAttribute('aria-current')
    expect(stats).toHaveTextContent('1')
    list.focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(tree).toHaveFocus()
    await userEvent.keyboard('{End}')
    expect(stats).toHaveFocus()
    await userEvent.keyboard('{ArrowRight}')
    expect(list).toHaveFocus()
    await userEvent.keyboard('{ArrowLeft}')
    expect(stats).toHaveFocus()
    await userEvent.keyboard('{Home}')
    expect(list).toHaveFocus()
    await expectAccessible()
  })

  it('offers what can be created from the sidebar, and nothing when there is nothing', async () => {
    const notice = vi.fn()
    render(<Shell current="/" onCreate={notice}><PageHeader title="Home" /></Shell>)
    await userEvent.click(screen.getByRole('button', { name: 'New' }))
    const items = screen.getAllByRole('menuitem')
    expect(items.map((item) => item.textContent)).toEqual(['New quiz', 'Send a notice'])
    expect(items[0]).toHaveAttribute('href', '/quizzes/new')
    await expectAccessible()
    await userEvent.click(items[1])
    expect(notice).toHaveBeenCalledOnce()

    const empty = render(<AppShell brand="x" navigation={null} create={<GlobalCreate items={[]} />}>y</AppShell>)
    expect(empty.container.querySelector('[data-slot="global-create"]')).toBeNull()
  })

  it('collapses the sidebar to icons, named by tooltips, and remembers it on this device', async () => {
    render(<Shell current="/inbox"><PageHeader title="Messages" /></Shell>)
    const sidebar = document.querySelector('[data-slot="sidebar"]')
    expect(sidebar).toHaveAttribute('data-state', 'expanded')
    await userEvent.click(screen.getByRole('button', { name: 'Toggle sidebar' }))
    expect(sidebar).toHaveAttribute('data-state', 'collapsed')
    expect(sidebar).toHaveAttribute('data-collapsible', 'icon')
    expect(window.localStorage.getItem('jabiz.sidebar')).toBe('closed')
    // Still the menu: the links keep their names (the icon strip hides only the text).
    expect(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Messages' }))
      .toHaveAttribute('aria-current', 'page')
  })

  it('shows the bottom menu on a phone only, keeps the floating button above it, and opens the whole menu', async () => {
    const { unmount } = render(<Shell current="/publications"><PageHeader title="Publications" /></Shell>)
    expect(screen.queryByRole('navigation', { name: 'Areas' })).toBeNull()
    unmount()

    narrow()
    render(
      <Shell current="/publications">
        <PageHeader title="Publications" primaryAction={{ label: 'New publication', onClick: () => {} }} />
      </Shell>,
    )
    const bar = screen.getByRole('navigation', { name: 'Areas' })
    expect(within(bar).getByRole('link', { name: 'Publications' })).toHaveAttribute('aria-current', 'page')
    expect(within(bar).getByRole('link', { name: /Messages/ })).toHaveTextContent('4')
    expect(within(bar).getByRole('link', { name: 'Scan' })).not.toHaveAttribute('aria-current')
    // The page and the floating button keep clear of the bar.
    const provider = document.querySelector('[data-slot="sidebar-wrapper"]') as HTMLElement
    expect(provider.style.getPropertyValue('--bottom-bar-offset')).toBe('var(--bottom-bar-height)')
    expect(screen.getByTestId('page-fab')).toBeInTheDocument()
    await expectAccessible()

    await userEvent.click(within(bar).getByRole('button', { name: 'More' }))
    const sheet = screen.getByRole('dialog', { name: 'More' })
    expect(within(sheet).getByRole('link', { name: 'Home' })).toBeInTheDocument()
    await expectAccessible()
    await userEvent.click(within(sheet).getByRole('link', { name: 'Home' }))
    expect(screen.queryByRole('dialog', { name: 'More' })).toBeNull()
  })

  it('moves the focus to the new page\'s title when the route changes, not on the first page', () => {
    const { rerender } = render(<Shell current="/"><PageHeader title="Home" /></Shell>)
    expect(screen.getByRole('heading', { name: 'Home' })).not.toHaveFocus()
    rerender(<Shell current="/inbox"><PageHeader title="Messages" /></Shell>)
    expect(screen.getByRole('heading', { name: 'Messages' })).toHaveFocus()
  })
})

describe('the page templates', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    wide()
  })

  it('a list page: head, search, status chips and the list', async () => {
    function Page() {
      const [query, setQuery] = useState('')
      const [state, setState] = useState<'all' | 'open'>('all')
      return (
        <ListPage
          title="Publications"
          icon={SendIcon}
          primaryAction={{ label: 'New publication', onClick: () => {} }}
          search={{ value: query, onChange: setQuery }}
          chips={{ label: 'Status', value: state, onChange: setState, options: [
            { value: 'all', label: 'All' },
            { value: 'open', label: 'Open' },
          ] }}
          toolbar={<button type="button">Export</button>}
        >
          <p data-testid="list-body">{query}|{state}</p>
        </ListPage>
      )
    }
    const { container } = render(<Page />)
    expect(container.querySelector('[data-slot="page-content"]')).toHaveClass('max-w-(--content-max)')
    await userEvent.type(screen.getByRole('searchbox', { name: 'Search' }), 'spring')
    await userEvent.click(within(screen.getByRole('radiogroup', { name: 'Status' })).getByRole('radio', { name: 'Open' }))
    expect(screen.getByTestId('list-body')).toHaveTextContent('spring|open')
    await expectAccessible()
  })

  it('a detail page: the status and next steps come before the content, in a named region', async () => {
    const { container } = render(
      <DetailPage title="Spring quiz" aside={<p>Live until 30 April</p>} asideLabel="Status and next steps">
        <p>The questions</p>
      </DetailPage>,
    )
    const aside = screen.getByRole('complementary', { name: 'Status and next steps' })
    const content = container.querySelector('[data-slot="detail-content"]') as HTMLElement
    expect(aside.compareDocumentPosition(content) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(aside).toHaveClass('lg:sticky', 'lg:order-2')
    await expectAccessible()
  })

  it('a form page: one narrow column, a bar that says whether there are unsaved changes, save on Enter', async () => {
    const save = vi.fn()
    const discard = vi.fn()
    function Page() {
      const [name, setName] = useState('Shop')
      const dirty = name !== 'Shop'
      return (
        <FormPage title="Shop profile" dirty={dirty} onSave={save} onDiscard={() => { discard(); setName('Shop') }}>
          <label>
            Name
            <input value={name} onChange={(event) => setName(event.target.value)} />
          </label>
        </FormPage>
      )
    }
    const added = vi.spyOn(window, 'addEventListener')
    const { container } = render(<Page />)
    expect(container.querySelector('[data-slot="page-content"]')).toHaveClass('max-w-(--content-form-max)')
    expect(screen.getByRole('status')).toHaveTextContent('No unsaved changes')
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Discard' })).toBeDisabled()
    expect(added.mock.calls.some(([type]) => type === 'beforeunload')).toBe(false)

    await userEvent.type(screen.getByRole('textbox', { name: 'Name' }), ' 2')
    expect(screen.getByRole('status')).toHaveTextContent('Unsaved changes')
    // Leaving the page now asks first.
    const leaving = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leaving)
    expect(leaving.defaultPrevented).toBe(true)
    await expectAccessible()

    fireEvent.submit(screen.getByRole('textbox', { name: 'Name' }).closest('form')!)
    expect(save).toHaveBeenCalledOnce()
    await userEvent.click(screen.getByRole('button', { name: 'Discard' }))
    expect(discard).toHaveBeenCalledOnce()
    expect(screen.getByRole('status')).toHaveTextContent('No unsaved changes')
    added.mockRestore()
  })
})
