import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { expectAccessible } from '@jabiz/ui/testing'
import SessionsPage from './SessionsPage'

const get = vi.fn()
const post = vi.fn()
const runProcess = vi.fn()
const permissions = { value: new Set<string>() }

vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  api: { GET: (...args: unknown[]) => get(...args), POST: (...args: unknown[]) => post(...args) },
  unwrap: (value: unknown) => value,
  runProcess: (...args: unknown[]) => runProcess(...args),
  useAuth: () => ({ can: (p: string) => permissions.value.has(p) }),
}))

const USER = '0190a6b2-0000-7000-8000-0000000000aa'
const SESSION = {
  id: '0190a6b2-0000-7000-8000-000000000001',
  entry: 'portal',
  current: false,
  createdAt: '2026-10-01T08:00:00Z',
  lastUsedAt: '2026-10-13T09:00:00Z',
  expiresAt: '2026-11-12T09:00:00Z',
  clientIp: '203.0.113.5',
  userAgent: 'Mozilla/5.0 (Windows NT 10.0) Chrome/126.0',
  device: { browser: 'CHROME', os: 'WINDOWS', deviceType: 'DESKTOP' },
  mfa: false,
  oidc: false,
}

function show() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <SessionsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const user = () => userEvent.setup()

/** Picks the user through the search of user names. */
async function pickUser() {
  await user().click(screen.getByTestId('session-user-select'))
  await user().keyboard('an')
  // User names that contain what was typed, as a LIKE pattern, once typing pauses.
  await waitFor(() => expect(post).toHaveBeenCalledWith('/api/datasets/{resourceId}/query', expect.objectContaining({
    body: expect.objectContaining({ filters: [{ field: 'userName', op: 'LIKE', value: '%an%' }] }),
  })))
  await user().click(await screen.findByRole('option', { name: /ann/ }))
}

describe('SessionsPage', () => {
  beforeEach(async () => {
    get.mockReset()
    post.mockReset()
    post.mockResolvedValue({ items: [{ id: USER, attributes: { userName: 'ann', displayName: 'Ann Lee' } }] })
    runProcess.mockReset()
    permissions.value = new Set(['security.user.session.read', 'security.user.session.revoke'])
    await act(() => i18n.changeLanguage('en'))
  })

  it("lists a user's sessions at every entry", async () => {
    get.mockResolvedValue({ sessions: [SESSION, { ...SESSION, id: 's2', entry: 'admin', clientIp: null }], truncated: false, effectiveWithinSeconds: 900 })
    show()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Sessions')
    await pickUser()
    expect(screen.getByTestId('session-user-select')).toHaveTextContent('ann')
    const rows = await screen.findAllByTestId('admin-session-revoke')
    expect(rows).toHaveLength(2)
    expect(get).toHaveBeenLastCalledWith('/api/security/sessions', { params: { query: { userId: USER } } })
    const table = screen.getByRole('table')
    expect(table).toHaveTextContent('Chrome on Windows')
    expect(table).toHaveTextContent('portal')
    expect(table).toHaveTextContent('203.0.113.5')
    await expectAccessible()
  })

  it('ends one session with a reason, or all of them', async () => {
    get.mockResolvedValue({ sessions: [SESSION], truncated: false, effectiveWithinSeconds: 900 })
    runProcess.mockResolvedValue({ revoked: 1 })
    show()
    await pickUser()
    await user().click(await screen.findByTestId('admin-session-revoke'))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('up to 15 minutes')
    // Not without a reason.
    await user().click(within(dialog).getByTestId('admin-session-confirm'))
    expect(await within(dialog).findByText('Give a reason.')).toBeInTheDocument()
    expect(runProcess).not.toHaveBeenCalled()
    await expectAccessible()
    await user().type(within(dialog).getByTestId('admin-session-reason'), 'phone lost')
    await user().click(within(dialog).getByTestId('admin-session-confirm'))
    await waitFor(() => expect(runProcess).toHaveBeenCalledWith('SEC_USER_SESSIONS_REVOKE',
      { userId: USER, sessionId: SESSION.id, reason: 'phone lost' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    await user().click(screen.getByTestId('admin-session-revoke-all'))
    const all = await screen.findByRole('dialog')
    expect(all).toHaveTextContent('End every session of this user?')
    await user().type(within(all).getByTestId('admin-session-reason'), 'account compromised')
    await user().click(within(all).getByTestId('admin-session-confirm'))
    await waitFor(() => expect(runProcess).toHaveBeenLastCalledWith('SEC_USER_SESSIONS_REVOKE',
      { userId: USER, reason: 'account compromised' }))
  })

  it('offers no ending to those who may only read', async () => {
    permissions.value = new Set(['security.user.session.read'])
    get.mockResolvedValue({ sessions: [SESSION], truncated: false, effectiveWithinSeconds: 900 })
    show()
    await pickUser()
    expect(await screen.findByText('Chrome on Windows')).toBeInTheDocument()
    expect(screen.queryByTestId('admin-session-revoke')).toBeNull()
    expect(screen.queryByTestId('admin-session-revoke-all')).toBeNull()
  })
})
