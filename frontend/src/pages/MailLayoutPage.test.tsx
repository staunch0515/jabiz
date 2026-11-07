import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import i18n from '../i18n'
import { MAIL_LAYOUTS, USER_NAMES } from '../test/mailFixtures'
import MailLayoutPage from './MailLayoutPage'

const get = vi.fn()
const post = vi.fn()
const runProcess = vi.fn()
const permissions = { value: new Set<string>() }

vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  api: { GET: (...args: unknown[]) => get(...args), POST: (...args: unknown[]) => post(...args) },
  unwrap: (value: unknown) => value,
  runProcess: (...args: unknown[]) => runProcess(...args),
  useAuth: () => ({ can: (p: string) => permissions.value.has(p), userId: 'u-ann' }),
  UserName: ({ id }: { id: string }) => <span>{USER_NAMES[id] ?? id}</span>,
}))

const HISTORY = [
  {
    versionNo: 2, effectStartTime: '2026-11-03T08:00:00Z', createdTime: '2026-11-03T08:00:00Z', opTime: '2026-11-03T08:00:00Z',
    deleted: false, action: 'UPDATE', baseVersionNo: 1, changedFields: ['footer'], processSeqId: 22, actorId: 'u-bob',
    processName: 'CONTROL_CHANGE_PUBLISH', reason: null,
    attributes: { kind: 'CUSTOM', locale: 'en', displayName: 'Demo Store Ltd', logoUrl: '/api/public/files/logo', logoAlt: 'Demo Store', footer: 'Demo Store Ltd\nShanghai' },
  },
  {
    versionNo: 1, effectStartTime: '2026-11-01T08:00:00Z', createdTime: '2026-11-01T08:00:00Z', opTime: '2026-11-01T08:00:00Z',
    deleted: false, action: 'INSERT', baseVersionNo: null, changedFields: [], processSeqId: 21, actorId: 'u-bob',
    processName: 'CONTROL_CHANGE_PUBLISH', reason: null,
    attributes: { kind: 'CUSTOM', locale: 'en', displayName: 'Demo Store', logoUrl: null, logoAlt: null, footer: 'Demo Store\nShanghai' },
  },
]

let pending: unknown[] = []

function show(locale = 'en') {
  get.mockImplementation((path: string) => {
    if (path === '/api/mail/layouts') return Promise.resolve(MAIL_LAYOUTS)
    if (path === '/api/datasets/{resourceId}/entities/{id}/history') return Promise.resolve(HISTORY)
    return Promise.reject(new Error(`unexpected GET ${path}`))
  })
  post.mockImplementation((path: string, options: { params?: { path?: { resourceId?: string } }; body?: { layout?: { footer?: string } } }) => {
    if (path === '/api/mail/layouts/preview') {
      return Promise.resolve({ subject: 'New task', text: 'Demo', html: `<footer>${options.body?.layout?.footer}</footer>`, problems: [] })
    }
    if (path === '/api/datasets/{resourceId}/query') {
      if (options.params?.path?.resourceId === 'urn:jabiz:dataset:platform:SysControlChange') return Promise.resolve({ items: pending })
      return Promise.resolve({ items: [{ id: 'layout-en', attributes: {} }] })
    }
    return Promise.reject(new Error(`unexpected POST ${path}`))
  })
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[`/mail/layout?locale=${locale}`]}>
        <MailLayoutPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('MailLayoutPage', () => {
  beforeEach(async () => {
    get.mockReset()
    post.mockReset()
    runProcess.mockReset()
    pending = []
    permissions.value = new Set(['mail.text.read', 'mail.text.write', 'control.propose', 'approval.read'])
    await act(() => i18n.changeLanguage('en'))
  })

  it('changes the layout only by proposing a change, with a preview of a sample mail', async () => {
    runProcess.mockResolvedValue({ changeId: 'c-1', status: 'PROPOSED' })
    show()
    expect(await screen.findByRole('heading', { level: 1, name: 'Mail layout' })).toBeInTheDocument()
    expect((screen.getByTestId('mail-layout-displayName') as HTMLInputElement).value).toBe('Demo Store Ltd')
    expect(screen.getByTestId('mail-state-en')).toHaveTextContent('Customised')
    // No saving without approval: only proposing.
    expect(screen.queryByTestId('mail-save')).toBeNull()
    expect(screen.getByTestId('mail-protected')).toHaveTextContent('need another administrator\'s approval')

    const footer = screen.getByTestId('mail-layout-footer')
    fireEvent.change(footer, { target: { value: 'Demo Store Ltd\nPudong, Shanghai' } })
    await waitFor(() => expect(screen.getByTestId('mail-preview-frame')).toHaveAttribute(
      'srcdoc', '<footer>Demo Store Ltd\nPudong, Shanghai</footer>'))
    expect(post).toHaveBeenCalledWith('/api/mail/layouts/preview', { body: { locale: 'en', layout: {
      displayName: 'Demo Store Ltd', logoUrl: '/api/public/files/logo', logoAlt: 'Demo Store', footer: 'Demo Store Ltd\nPudong, Shanghai' } } })

    const user = userEvent.setup()
    await user.type(screen.getByTestId('mail-propose-reason'), 'new address')
    await user.click(screen.getByTestId('mail-propose-submit'))
    await waitFor(() => expect(runProcess).toHaveBeenCalledWith('CONTROL_CHANGE_PROPOSE', {
      targetEntity: 'MailLayout',
      values: { locale: 'en', displayName: 'Demo Store Ltd', logoUrl: '/api/public/files/logo', logoAlt: 'Demo Store', footer: 'Demo Store Ltd\nPudong, Shanghai' },
      reason: 'new address',
    }))
    await expectAccessible()
  })

  it('shows the pending change with its proposer, whom it lets withdraw it', async () => {
    pending = [{ id: 'c-2', attributes: { targetKey: 'en', proposedBy: 'u-ann', reason: 'rebrand', status: 'PROPOSED',
      changeValues: JSON.stringify({ locale: 'en', displayName: 'Demo Store Group', footer: 'Demo Store Group' }) } }]
    runProcess.mockResolvedValue({ changeId: 'c-2', status: 'WITHDRAWN' })
    show()
    const change = await screen.findByTestId('mail-pending-change')
    expect(change).toHaveTextContent('Proposed by Ann Lee')
    expect(screen.getByTestId('mail-state-en')).toHaveTextContent('Pending approval')
    await userEvent.setup().click(within(change).getByTestId('mail-pending-withdraw'))
    expect(runProcess).toHaveBeenCalledWith('CONTROL_CHANGE_WITHDRAW', { changeId: 'c-2' })
  })

  it('restores the default after the question, and sends a test mail', async () => {
    runProcess.mockResolvedValue({ address: 'ann@example.com' })
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('mail-test-send'))
    expect(runProcess).toHaveBeenCalledWith('MAIL_LAYOUT_TEST_SEND', {
      locale: 'en', displayName: 'Demo Store Ltd', logoUrl: '/api/public/files/logo', logoAlt: 'Demo Store', footer: 'Demo Store Ltd\nShanghai' })
    await user.click(screen.getByTestId('mail-reset'))
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('Restore the default layout?')
    await user.click(within(dialog).getByRole('button', { name: 'Restore default' }))
    await waitFor(() => expect(runProcess).toHaveBeenLastCalledWith('MAIL_LAYOUT_RESET', { locale: 'en', baseVersionNo: 2 }))
  })

  it('compares a version of the history with the layout in effect and takes it as the draft', async () => {
    show()
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('mail-version-open-1'))
    const compare = screen.getByTestId('mail-version-compare')
    expect(within(compare).getByTestId('mail-diff-displayName').querySelector('[data-kind="removed"]')).toHaveTextContent('Demo Store')
    expect(within(compare).getByTestId('mail-diff-footer').querySelector('[data-kind="added"]')).toHaveTextContent('Demo Store Ltd')
    expect(screen.getAllByText('Approved change')).toHaveLength(2)
    await expectAccessible()
    await user.click(within(compare).getByTestId('mail-version-use'))
    expect((screen.getByTestId('mail-layout-displayName') as HTMLInputElement).value).toBe('Demo Store')
    expect((screen.getByTestId('mail-layout-logoUrl') as HTMLInputElement).value).toBe('')
  })

  it('shows the default of a language without a stored layout, and nothing to restore', async () => {
    show('zh')
    expect((await screen.findByTestId('mail-layout-displayName') as HTMLInputElement).value).toBe('示范商店')
    expect(screen.queryByTestId('mail-reset')).toBeNull()
  })
})
