import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { expectAccessible } from '../test/axe'
import AccountSecurityPage, { ACCOUNT_SECTIONS } from './AccountSecurityPage'

const get = vi.fn()
const listSessions = vi.fn()
const listIdentities = vi.fn()

// The building blocks call the server through @jabiz/client.
vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  api: { GET: (...args: unknown[]) => get(...args), POST: vi.fn() },
  unwrap: (value: unknown) => value,
  listSessions: () => listSessions(),
  listIdentities: () => listIdentities(),
}))

/** Answers the MFA status and the providers offered at the administration's entry. */
function answers(mfa: unknown, providers: unknown[] = []) {
  get.mockImplementation(async (path: string) => (path === '/api/auth/oidc/providers' ? providers : mfa))
}

function show() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <AccountSecurityPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AccountSecurityPage', () => {
  beforeEach(async () => {
    get.mockReset()
    listSessions.mockReset().mockResolvedValue({ sessions: [], truncated: false, effectiveWithinSeconds: 900 })
    listIdentities.mockReset().mockResolvedValue([])
    await i18n.changeLanguage('en')
  })

  it('is made of its sections, in the order ACCOUNT_SECTIONS declares, each named by its heading', async () => {
    answers({ enrolled: true, recoveryCodesLeft: 4 }, [{ id: 'google', label: 'Google', kind: 'google' }])
    show()
    expect(await screen.findByTestId('account-section-identities')).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1, name: 'Security' })).toBeTruthy()
    const sections = screen.getAllByRole('region')
    expect(sections.map((s) => s.getAttribute('data-testid'))).toEqual(
      ACCOUNT_SECTIONS.map((s) => `account-section-${s.name}`),
    )
    expect(ACCOUNT_SECTIONS.map((s) => s.name)).toEqual(['mfa', 'password', 'sessions', 'identities'])
    expect(screen.getByRole('region', { name: 'Two-step verification' })).toBe(sections[0])

    const mfa = within(screen.getByTestId('account-section-mfa'))
    expect(await mfa.findByTestId('mfa-status')).toHaveTextContent('4 recovery codes left.')
    expect(get).toHaveBeenCalledWith('/api/auth/mfa')
    await waitFor(() => expect(listSessions).toHaveBeenCalled())
    const identities = within(screen.getByTestId('account-section-identities'))
    expect(screen.getByRole('region', { name: 'Sign-in with other accounts' })).toBe(sections[3])
    expect(await identities.findByTestId('link-google')).toHaveTextContent('Link Google')
    await expectAccessible()
  })

  it('has no section for linked accounts where no identity provider is offered', async () => {
    answers({ enrolled: true, recoveryCodesLeft: 4 })
    show()
    await waitFor(() => expect(get).toHaveBeenCalledWith('/api/auth/oidc/providers'))
    await waitFor(() => expect(listSessions).toHaveBeenCalled())
    expect(screen.queryByTestId('account-section-identities')).toBeNull()
    expect(screen.getAllByRole('region').map((s) => s.getAttribute('data-testid'))).toEqual(
      ['account-section-mfa', 'account-section-password', 'account-section-sessions'],
    )
  })

  it('offers setting up two-step verification while it is off', async () => {
    answers({ enrolled: false })
    show()
    const mfa = within(screen.getByTestId('account-section-mfa'))
    expect(await mfa.findByTestId('mfa-status')).toHaveTextContent('Two-step verification is off.')
    expect(mfa.getByTestId('mfa-start')).toBeTruthy()
  })
})
