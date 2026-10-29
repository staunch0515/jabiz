import { ApiError, configureClient, i18n, session, type AccountDeletionStatus } from '@jabiz/client'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { AccountDeletion, MailLinkPage } from './index'

const DUE = '2026-11-11T09:00:00Z'
const notices = [
  { code: 'orders', message: 'Your orders are kept for ten years.' },
  { code: 'points', message: 'Your loyalty points are lost.' },
]
const available: AccountDeletionStatus = { available: true, pending: undefined, notices }
const pending: AccountDeletionStatus = {
  available: true,
  pending: { requestId: 'r1', requestedTime: '2026-10-28T09:00:00Z', dueTime: DUE, channel: 'ACCOUNT' },
  notices: [],
}

function refused(status: number, ruleCode: string, field: string | null = null) {
  return new ApiError(status, { status, violations: [{ field, ruleCode, message: `${ruleCode} message` }] })
}

function noticesRefused(named: { code: string; message: string }[]) {
  return {
    status: 422,
    violations: [
      { field: null, ruleCode: 'ACCOUNT_DELETION_NOTICES', message: 'Please read and confirm the notes.' },
      ...named.map((n) => ({ field: null, ruleCode: `erasure.notice.${n.code}`, message: n.message })),
    ],
  }
}

type Answer = { status: number; body?: unknown }

/** Answers posts as given, by path, and the entry's settings. Returns the posts made. */
function server(answers: Record<string, Answer | Answer[]> = {}) {
  const posts: { path: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), 'http://localhost'), init)
    const path = new URL(request.url).pathname
    if (request.method === 'GET' && path.startsWith('/api/auth/entries/')) {
      return Response.json({ name: path.split('/').pop(), appPath: '/portal/' })
    }
    const text = await request.text()
    posts.push({ path, body: text ? JSON.parse(text) : undefined })
    const planned = answers[path]
    const answer = Array.isArray(planned) ? planned.shift() : planned
    if (!answer) return Response.json({}, { status: 500 })
    return answer.body === undefined
      ? new Response(null, { status: answer.status, headers: { 'Content-Length': '0' } })
      : new Response(JSON.stringify(answer.body), {
        status: answer.status,
        headers: { 'Content-Type': answer.status >= 400 ? 'application/problem+json' : 'application/json' },
      })
  }))
  return posts
}

const user = () => userEvent.setup()

beforeEach(async () => {
  configureClient({ entry: 'portal' })
  await act(() => i18n.changeLanguage('en'))
})

afterEach(() => {
  session.clear()
  configureClient({ entry: 'admin' })
  window.history.replaceState(null, '', '/')
  vi.unstubAllGlobals()
})

describe('AccountDeletion', () => {
  it('says when a pending request deletes the account, and withdraws it', async () => {
    const load = vi.fn().mockResolvedValueOnce(pending).mockResolvedValueOnce(available)
    const cancel = vi.fn().mockResolvedValueOnce({ requestId: 'r1' })
    render(<AccountDeletion load={load} cancel={cancel} />)
    expect(await screen.findByTestId('account-deletion-pending'))
      .toHaveTextContent('Your account will be deleted on November 11, 2026.')
    await expectAccessible()
    await user().click(screen.getByRole('button', { name: 'Withdraw the request' }))
    expect(cancel).toHaveBeenCalledTimes(1)
    expect(await screen.findByTestId('account-deletion-state')).toHaveTextContent('Your request has been withdrawn.')
    await waitFor(() => expect(screen.queryByTestId('account-deletion-pending')).toBeNull())
    expect(screen.getByTestId('account-deletion-start')).toBeInTheDocument()
  })

  it('needs every note ticked, asks for the current password only when the server wants it, and shows a wrong one',
    async () => {
      const request = vi.fn()
        .mockRejectedValueOnce(refused(422, 'REAUTHENTICATION_REQUIRED', 'currentPassword'))
        .mockRejectedValueOnce(refused(422, 'PASSWORD_INCORRECT', 'currentPassword'))
        .mockResolvedValueOnce({ requestId: 'r1', dueTime: DUE })
      const onRequested = vi.fn()
      render(<AccountDeletion load={async () => available} request={request} onRequested={onRequested} />)
      expect(await screen.findByText(/deleted at the end of a waiting period/)).toBeInTheDocument()
      await expectAccessible()

      await user().click(screen.getByTestId('account-deletion-start'))
      expect(screen.getByTestId('account-deletion-notices-error')).toHaveTextContent('Confirm each note')
      expect(screen.getByRole('checkbox', { name: notices[0].message })).toHaveAttribute('aria-invalid', 'true')
      expect(screen.queryByRole('dialog')).toBeNull()
      await user().click(screen.getByRole('checkbox', { name: notices[0].message }))
      await user().click(screen.getByRole('checkbox', { name: notices[1].message }))
      expect(screen.queryByTestId('account-deletion-notices-error')).toBeNull()

      await user().click(screen.getByTestId('account-deletion-start'))
      const dialog = await screen.findByRole('dialog')
      expect(dialog).toHaveTextContent('Delete your account?')
      expect(within(dialog).queryByLabelText('Current password')).toBeNull()
      await user().click(within(dialog).getByTestId('account-deletion-confirm'))
      expect(request).toHaveBeenNthCalledWith(1, { currentPassword: undefined, acknowledgedNotices: ['orders', 'points'] })
      expect(await within(dialog).findByTestId('account-deletion-password-hint')).toHaveTextContent('current password')
      // The password can be shown (phase 17p).
      await user().click(within(dialog).getByRole('button', { name: 'Show typed characters' }))
      expect(within(dialog).getByLabelText('Current password')).toHaveAttribute('type', 'text')
      await expectAccessible()

      await user().type(within(dialog).getByLabelText('Current password'), 'wrong{Enter}')
      expect(await screen.findByTestId('account-deletion-password-error')).toHaveTextContent('PASSWORD_INCORRECT message')
      await user().clear(within(dialog).getByLabelText('Current password'))
      await user().type(within(dialog).getByLabelText('Current password'), 'right')
      await user().click(within(dialog).getByTestId('account-deletion-confirm'))
      await waitFor(() => expect(request).toHaveBeenNthCalledWith(3,
        { currentPassword: 'right', acknowledgedNotices: ['orders', 'points'] }))
      expect(await screen.findByTestId('account-deletion-pending')).toHaveTextContent('November 11, 2026')
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
      expect(onRequested).toHaveBeenCalledWith({ requestId: 'r1', dueTime: DUE })
    })

  it.each([
    ['REAUTHENTICATION_UNAVAILABLE', 'set up two-step verification'],
    ['ACCOUNT_DELETION_PROTECTED', 'holds administrative permissions'],
    ['ACCOUNT_DELETION_PENDING', 'has been requested already'],
    ['ACCOUNT_DELETION_UNAVAILABLE', 'cannot be deleted here'],
    ['SOMETHING_ELSE', 'SOMETHING_ELSE message'],
  ])('says what %s means', async (code, text) => {
    const request = vi.fn().mockRejectedValueOnce(refused(422, code))
    render(<AccountDeletion load={async () => ({ available: true, notices: [] })} request={request} />)
    await user().click(await screen.findByTestId('account-deletion-start'))
    await user().click(within(await screen.findByRole('dialog')).getByTestId('account-deletion-confirm'))
    expect(await screen.findByTestId('account-deletion-error')).toHaveTextContent(text)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('shows the notes again when they changed since they were read', async () => {
    const changed = { code: 'points', message: 'Your loyalty points and vouchers are lost.' }
    const load = vi.fn().mockResolvedValueOnce(available)
      .mockResolvedValueOnce({ available: true, notices: [notices[0], changed] })
    const body = noticesRefused([changed])
    const request = vi.fn()
      .mockRejectedValueOnce(new ApiError(422, body))
      .mockResolvedValueOnce({ requestId: 'r1', dueTime: DUE })
    render(<AccountDeletion load={load} request={request} />)
    for (const n of notices) await user().click(await screen.findByRole('checkbox', { name: n.message }))
    await user().click(screen.getByTestId('account-deletion-start'))
    await user().click(within(await screen.findByRole('dialog')).getByTestId('account-deletion-confirm'))
    expect(await screen.findByTestId('account-deletion-error')).toHaveTextContent('The notes have changed')
    expect(await screen.findByRole('checkbox', { name: changed.message })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: notices[0].message })).toBeChecked()

    await user().click(screen.getByRole('checkbox', { name: changed.message }))
    await user().click(screen.getByTestId('account-deletion-start'))
    await user().click(within(await screen.findByRole('dialog')).getByTestId('account-deletion-confirm'))
    await waitFor(() => expect(request).toHaveBeenLastCalledWith(
      { currentPassword: undefined, acknowledgedNotices: ['orders', 'points'] }))
    expect(await screen.findByTestId('account-deletion-pending')).toBeInTheDocument()
  })

  it('says only a short note where accounts cannot be deleted', async () => {
    render(<AccountDeletion load={async () => ({ available: false, notices: [] })} />)
    expect(await screen.findByTestId('account-deletion-unavailable')).toHaveTextContent('cannot be deleted here')
    expect(screen.queryByTestId('account-deletion-start')).toBeNull()
  })

  it('says when nothing was pending any more', async () => {
    const load = vi.fn().mockResolvedValueOnce(pending).mockResolvedValueOnce(available)
    render(<AccountDeletion load={load} cancel={vi.fn().mockRejectedValueOnce(refused(422, 'ACCOUNT_DELETION_NONE'))} />)
    await user().click(await screen.findByTestId('account-deletion-withdraw'))
    expect(await screen.findByTestId('account-deletion-error')).toHaveTextContent('No deletion of your account is pending.')
    expect(await screen.findByTestId('account-deletion-start')).toBeInTheDocument()
  })

  it('works through the session with the client by default', async () => {
    session.store('access', 'refresh')
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(pending)))
    render(<AccountDeletion />)
    expect(await screen.findByTestId('account-deletion-pending')).toBeInTheDocument()
    const [request] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [Request]
    expect(new URL(request.url).pathname).toBe('/api/auth/account/deletion')
  })

  it('speaks every platform language', async () => {
    await act(() => i18n.changeLanguage('zh'))
    render(<AccountDeletion load={async () => pending} />)
    expect(await screen.findByTestId('account-deletion-pending')).toHaveTextContent('2026年11月11日')
    expect(screen.getByRole('button', { name: '撤回申请' })).toBeInTheDocument()
    await act(() => i18n.changeLanguage('ja'))
    expect(await screen.findByRole('button', { name: '申請を取り下げる' })).toBeInTheDocument()
  })
})

describe('MailLinkPage account-deletion', () => {
  it('asks for a link without a token, with the same answer for every address', async () => {
    window.history.replaceState(null, '', '/portal/auth/account-deletion?entry=portal')
    const posts = server({ '/api/auth/account-deletion': { status: 202 } })
    render(<MailLinkPage brand="Portal" />)
    await waitFor(() => expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/portal/'))
    await expectAccessible()
    await user().type(screen.getByRole('textbox', { name: 'E-mail address' }), 'bob@example.com{Enter}')
    expect(await screen.findByTestId('account-deletion-request-state'))
      .toHaveTextContent('If an account with this address can be deleted here')
    expect(posts).toEqual([{ path: '/api/auth/account-deletion', body: { email: 'bob@example.com', entry: 'portal' } }])
  })

  it('says so where the entry does not let accounts be deleted', async () => {
    window.history.replaceState(null, '', '/portal/auth/account-deletion?entry=portal')
    server({ '/api/auth/account-deletion': { status: 422, body: { status: 422, violations: [
      { field: null, ruleCode: 'ACCOUNT_DELETION_UNAVAILABLE', message: 'No.' }] } } })
    render(<MailLinkPage brand="Portal" />)
    await user().type(screen.getByRole('textbox', { name: 'E-mail address' }), 'bob@example.com{Enter}')
    expect(await screen.findByText('Accounts cannot be deleted here.')).toBeInTheDocument()
    expect(screen.queryByTestId('account-deletion-request-state')).toBeNull()
  })

  it('confirms with the token after the notes are ticked, and says when the account goes', async () => {
    window.history.replaceState(null, '', '/portal/auth/account-deletion?entry=portal&token=t1')
    const posts = server({
      '/api/auth/account-deletion/confirm': [
        { status: 422, body: noticesRefused(notices) },
        { status: 200, body: { requestId: 'r2', dueTime: DUE } },
      ],
    })
    render(<MailLinkPage brand="Portal" />)
    expect(window.location.search).toBe('?entry=portal')
    expect(posts).toEqual([])
    await user().click(screen.getByTestId('account-deletion-start'))
    expect(await screen.findByTestId('account-deletion-notices')).toBeInTheDocument()
    expect(screen.queryByTestId('account-deletion-error')).toBeNull()
    await expectAccessible()

    await user().click(screen.getByTestId('account-deletion-start'))
    expect(screen.getByTestId('account-deletion-notices-error')).toBeInTheDocument()
    for (const n of notices) await user().click(screen.getByRole('checkbox', { name: n.message }))
    await user().click(screen.getByTestId('account-deletion-start'))
    expect(await screen.findByTestId('account-deletion-state')).toHaveTextContent('will be deleted on November 11, 2026')
    expect(posts.map((p) => p.body)).toEqual([
      { token: 't1', acknowledgedNotices: [] },
      { token: 't1', acknowledgedNotices: ['orders', 'points'] },
    ])
    await waitFor(() => expect(screen.getByTestId('auth-continue')).toHaveAttribute('href', '/portal/'))
    await expectAccessible()
  })

  it('leads from a link that is no good to asking for a new one', async () => {
    window.history.replaceState(null, '', '/portal/auth/account-deletion?entry=portal&token=old')
    server({ '/api/auth/account-deletion/confirm': { status: 422, body: { status: 422, violations: [
      { field: null, ruleCode: 'TOKEN_INVALID', message: 'No.' }] } } })
    render(<MailLinkPage brand="Portal" />)
    await user().click(screen.getByTestId('account-deletion-start'))
    expect(await screen.findByTestId('account-deletion-state')).toHaveTextContent('invalid')
    expect(screen.getByRole('link', { name: 'Ask for a new link' }))
      .toHaveAttribute('href', '/portal/auth/account-deletion?entry=portal')
    await expectAccessible()
  })

  it('says why an account with administrative permissions is not deleted', async () => {
    window.history.replaceState(null, '', '/portal/auth/account-deletion?entry=portal&token=t3')
    server({ '/api/auth/account-deletion/confirm': { status: 422, body: { status: 422, violations: [
      { field: null, ruleCode: 'ACCOUNT_DELETION_PROTECTED', message: 'No.' }] } } })
    render(<MailLinkPage brand="Portal" />)
    await user().click(screen.getByTestId('account-deletion-start'))
    expect(await screen.findByTestId('account-deletion-error')).toHaveTextContent('holds administrative permissions')
  })
})
