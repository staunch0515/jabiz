import { ApiError, configureClient, i18n, session, type LinkedIdentity } from '@jabiz/client'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '../test/axe'
import { LinkedIdentities, type OidcProvider } from './index'

const google: OidcProvider = { id: 'google', label: 'Google', kind: 'google' }
const corp: OidcProvider = { id: 'corp', label: 'Corporate', kind: 'generic' }
const linkedGoogle: LinkedIdentity = {
  id: '0190a6b2-0000-7000-8000-0000000000aa',
  provider: 'google',
  label: 'Google',
  kind: 'google',
  email: 'mei@gmail.com',
  linkedAt: '2026-10-13T09:00:00Z',
}

function refused(status: number, ruleCode: string, field: string | null = null) {
  return new ApiError(status, { status, violations: [{ field, ruleCode, message: `${ruleCode} message` }] })
}

const user = () => userEvent.setup()

beforeEach(async () => {
  configureClient({ entry: 'portal' })
  await act(() => i18n.changeLanguage('en'))
})

afterEach(() => {
  session.clear()
  configureClient({ entry: 'admin' })
  vi.unstubAllGlobals()
})

describe('LinkedIdentities', () => {
  it('lists the linked accounts and offers the providers not linked yet', async () => {
    render(<LinkedIdentities returnTo="/account" providers={[google, corp]} list={async () => [linkedGoogle]}
      linked={{ status: 'LINKED', provider: 'google' }} />)
    const items = await screen.findAllByTestId('linked-identity')
    expect(items).toHaveLength(1)
    expect(items[0]).toHaveTextContent('Google')
    expect(items[0]).toHaveTextContent('mei@gmail.com')
    expect(screen.getByTestId('linked-identities-linked')).toHaveTextContent('Google is linked.')
    expect(screen.queryByTestId('link-google')).toBeNull()
    expect(screen.getByTestId('link-corp')).toHaveTextContent('Link Corporate')
    await expectAccessible()
  })

  it('asks for the current password only when the server wants it, and shows a wrong one', async () => {
    const start = vi.fn()
      .mockRejectedValueOnce(refused(422, 'REAUTHENTICATION_REQUIRED', 'currentPassword'))
      .mockRejectedValueOnce(refused(422, 'PASSWORD_INCORRECT', 'currentPassword'))
      .mockResolvedValueOnce(undefined)
    render(<LinkedIdentities returnTo="/account" providers={[corp]} list={async () => []} start={start} />)
    expect(await screen.findByTestId('linked-identities-none')).toBeInTheDocument()
    await user().click(screen.getByTestId('link-corp'))
    expect(start).toHaveBeenNthCalledWith(1, 'corp', { currentPassword: undefined }, '/account')
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('Enter your current password to link Corporate')
    await expectAccessible()
    await user().type(within(dialog).getByLabelText('Current password'), 'wrong{Enter}')
    expect(await screen.findByTestId('link-password-error')).toHaveTextContent('PASSWORD_INCORRECT message')
    await user().clear(within(dialog).getByLabelText('Current password'))
    await user().type(within(dialog).getByLabelText('Current password'), 'right')
    await user().click(within(dialog).getByTestId('link-continue'))
    await waitFor(() => expect(start).toHaveBeenNthCalledWith(3, 'corp', { currentPassword: 'right' }, '/account'))
  })

  it('removes a link after confirming and lists again; the server says when it is the last way in', async () => {
    const list = vi.fn().mockResolvedValueOnce([linkedGoogle]).mockResolvedValueOnce([])
    const unlink = vi.fn().mockRejectedValueOnce(refused(422, 'LAST_SIGN_IN_METHOD')).mockResolvedValueOnce(undefined)
    render(<LinkedIdentities returnTo="/account" providers={[google]} list={list} unlink={unlink} />)
    const item = await screen.findByTestId('linked-identity')
    await user().click(within(item).getByTestId('linked-identity-remove'))
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('Remove Google?')
    await user().click(within(dialog).getByRole('button', { name: 'Remove' }))
    expect(await screen.findByTestId('linked-identities-error')).toHaveTextContent('LAST_SIGN_IN_METHOD message')

    // The dialog stays open after a refusal; this time it goes.
    await user().click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Remove' }))
    expect(unlink).toHaveBeenLastCalledWith(linkedGoogle.id)
    expect(await screen.findByTestId('linked-identities-state')).toHaveTextContent('Google has been removed.')
    await waitFor(() => expect(screen.getByTestId('linked-identities-none')).toBeInTheDocument())
    expect(screen.getByTestId('link-google')).toBeInTheDocument()
  })

  it('shows nothing when no provider is offered and none is linked', async () => {
    const list = vi.fn(async () => [])
    const { container } = render(<LinkedIdentities returnTo="/account" providers={[]} list={list} />)
    await waitFor(() => expect(list).toHaveBeenCalled())
    await waitFor(() => expect(container.querySelector('[data-testid="linked-identities"]')).toBeNull())
  })

  it('speaks every platform language', async () => {
    await act(() => i18n.changeLanguage('zh'))
    render(<LinkedIdentities returnTo="/account" providers={[corp]} list={async () => []} />)
    expect(await screen.findByText('关联 Corporate')).toBeInTheDocument()
    await act(() => i18n.changeLanguage('ja'))
    expect(await screen.findByText('Corporate を連携')).toBeInTheDocument()
  })
})
