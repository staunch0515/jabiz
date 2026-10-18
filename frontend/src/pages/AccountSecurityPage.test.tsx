import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { expectAccessible } from '../test/axe'
import AccountSecurityPage, { ACCOUNT_SECTIONS } from './AccountSecurityPage'

const get = vi.fn()
const listSessions = vi.fn()
const listIdentities = vi.fn()
const getMailPreferences = vi.fn()
const getMailLanguage = vi.fn()
const setMailPreference = vi.fn()

// The building blocks call the server through @jabiz/client.
vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  api: { GET: (...args: unknown[]) => get(...args), POST: vi.fn() },
  unwrap: (value: unknown) => value,
  listSessions: () => listSessions(),
  listIdentities: () => listIdentities(),
  getMailPreferences: () => getMailPreferences(),
  getMailLanguage: () => getMailLanguage(),
  setMailPreference: (template: string, subscribed: boolean) => setMailPreference(template, subscribed),
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
    getMailPreferences.mockReset().mockResolvedValue([
      { template: 'it.news', subscribed: true, displayName: 'News' },
    ])
    getMailLanguage.mockReset().mockResolvedValue({ effective: 'en', languages: ['zh', 'ja', 'en'], defaultLocale: 'en' })
    setMailPreference.mockReset().mockResolvedValue(undefined)
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
    expect(ACCOUNT_SECTIONS.map((s) => s.name)).toEqual(['mfa', 'password', 'sessions', 'identities', 'mail'])
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
      ['account-section-mfa', 'account-section-password', 'account-section-sessions', 'account-section-mail'],
    )
  })

  it('offers setting up two-step verification while it is off', async () => {
    answers({ enrolled: false })
    show()
    const mfa = within(screen.getByTestId('account-section-mfa'))
    expect(await mfa.findByTestId('mfa-status')).toHaveTextContent('Two-step verification is off.')
    expect(mfa.getByTestId('mfa-start')).toBeTruthy()
  })

  it("has the user's mail: the notifications of the administration and the language (phase 16s)", async () => {
    answers({ enrolled: true, recoveryCodesLeft: 4 })
    show()
    const mail = within(await screen.findByTestId('account-section-mail'))
    expect(screen.getByRole('region', { name: 'E-mail' })).toBe(screen.getByTestId('account-section-mail'))
    const news = await mail.findByRole('switch', { name: 'News' })
    expect(news).toBeChecked()
    expect(mail.getByRole('combobox', { name: 'Language of your mail' })).toHaveTextContent('Default (English)')
    await userEvent.click(news)
    expect(setMailPreference).toHaveBeenCalledWith('it.news', false)
    expect(await mail.findByTestId('mail-preferences-state')).toHaveTextContent('News: off.')
    await waitFor(() => expect(listSessions).toHaveBeenCalled())
    await expectAccessible()
  })
})
