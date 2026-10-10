import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import LoginPage from './LoginPage'
import { ApiError } from '../api/problem'
import { expectAccessible } from '../test/axe'

const signIn = vi.fn()
const verify = vi.fn()
const providers = vi.fn()
const startProviderSignIn = vi.fn()

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: { GET: (...args: unknown[]) => providers(...args) },
  unwrap: (value: unknown) => value,
}))
vi.mock('../auth/oidc', () => ({ startProviderSignIn: (...args: unknown[]) => startProviderSignIn(...args) }))

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ signIn, verify, signedIn: false, ready: true }),
  lastUserName: () => 'amy',
}))
let home: string | undefined
vi.mock('../extension', () => ({
  get extension() {
    return { home }
  },
}))
vi.mock('qrcode', () => ({ default: { toCanvas: vi.fn(async () => undefined) } }))

function page(state?: object) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[{ pathname: '/login', state }]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/data" element={<div data-testid="home" />} />
          <Route path="/finance/journals" element={<div data-testid="extension-home" />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function submitPassword() {
  fireEvent.change(screen.getByPlaceholderText('User name'), { target: { value: 'amy' } })
  fireEvent.change(screen.getByPlaceholderText('Password'), { target: { value: 'secret password' } })
  fireEvent.click(screen.getByRole('button', { name: /Sign in/ }))
}

describe('LoginPage', () => {
  beforeEach(async () => {
    signIn.mockReset()
    verify.mockReset()
    providers.mockReset()
    providers.mockResolvedValue([])
    startProviderSignIn.mockReset()
    home = undefined
    await i18n.changeLanguage('en')
  })

  it('asks for the code after the password when two-step verification is on', async () => {
    signIn.mockResolvedValue({ status: 'MFA_REQUIRED', challenge: 'ch' })
    verify.mockResolvedValue(undefined)
    page()
    await submitPassword()

    fireEvent.change(await screen.findByPlaceholderText('Code'), { target: { value: ' 123456 ' } })
    fireEvent.click(screen.getByRole('button', { name: /Verify/ }))
    await waitFor(() => expect(verify).toHaveBeenCalledWith('ch', '123456'))
    expect(await screen.findByTestId('home')).toBeTruthy()
  })

  it("lands on the application's home when it declares one and there is no page to return to", async () => {
    home = '/finance/journals'
    signIn.mockResolvedValue({ status: 'SIGNED_IN' })
    page()
    await submitPassword()
    expect(await screen.findByTestId('extension-home')).toBeTruthy()
  })

  it('returns to the page it came from rather than the home', async () => {
    home = '/finance/journals'
    signIn.mockResolvedValue({ status: 'SIGNED_IN' })
    page({ from: '/data' })
    await submitPassword()
    expect(await screen.findByTestId('home')).toBeTruthy()
  })

  it('sets up two-step verification first when a role requires it, then asks to sign in again', async () => {
    signIn.mockResolvedValue({ status: 'MFA_ENROLLMENT_REQUIRED', challenge: 'enrol' })
    page()
    await submitPassword()
    expect(await screen.findByTestId('mfa-enroll')).toBeTruthy()
    expect(screen.getByText(/Your role requires two-step verification/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Back to sign in/ }))
    expect(await screen.findByPlaceholderText('Password')).toBeTruthy()
  })

  it('says why after an idle lock and fills in the user name', async () => {
    page({ idle: true })
    expect(screen.getByTestId('idle-locked')).toBeTruthy()
    expect((screen.getByPlaceholderText('User name') as HTMLInputElement).value).toBe('amy')
  })

  it('offers the identity providers and starts a sign-in through one', async () => {
    providers.mockResolvedValue([{ id: 'corp', label: 'Corporate directory' }])
    startProviderSignIn.mockResolvedValue(undefined)
    page({ from: '/tasks' })
    fireEvent.click(await screen.findByTestId('oidc-corp'))
    expect(providers).toHaveBeenCalledWith('/api/auth/oidc/providers')
    await waitFor(() => expect(startProviderSignIn).toHaveBeenCalledWith('corp', '/tasks'))
  })

  it('continues with the code step when the provider sign-in needs a second factor', async () => {
    page({ step: { status: 'MFA_REQUIRED', challenge: 'from-oidc' } })
    fireEvent.change(await screen.findByPlaceholderText('Code'), { target: { value: '654321' } })
    fireEvent.click(screen.getByRole('button', { name: /Verify/ }))
    await waitFor(() => expect(verify).toHaveBeenCalledWith('from-oidc', '654321'))
  })

  it('asks for what is missing before sending, and shows the server refusal', async () => {
    page()
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Enter your user name.')).toBeInTheDocument()
    expect(screen.getByText('Enter your password.')).toBeInTheDocument()
    expect(screen.getByLabelText('User name')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('User name')).toHaveAccessibleDescription('Enter your user name.')
    expect(signIn).not.toHaveBeenCalled()

    signIn.mockRejectedValue(new ApiError(401, { violations: [{ ruleCode: 'LOGIN_FAILED', message: 'Wrong.' }] }))
    await submitPassword()
    expect((await screen.findByTestId('login-error')).textContent).toBe('Wrong.')
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password')
    expect(screen.getByLabelText('User name')).toHaveAttribute('autocomplete', 'username')
  })

  it('names its fields and is accessible, light and dark', async () => {
    providers.mockResolvedValue([{ id: 'corp', label: 'Corporate directory' }])
    page({ idle: true })
    expect(await screen.findByTestId('oidc-corp')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('jabiz')
    expect(screen.getByRole('combobox', { name: 'Language' })).toBeInTheDocument()
    await expectAccessible()
  })

  it('verifies the code only once one is entered, and the code field is for one-time codes', async () => {
    page({ step: { status: 'MFA_REQUIRED', challenge: 'c' } })
    const code = await screen.findByPlaceholderText('Code')
    expect(code).toHaveAttribute('autocomplete', 'one-time-code')
    expect(code).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByText('Enter the code.')).toBeInTheDocument()
    expect(verify).not.toHaveBeenCalled()
    await expectAccessible()
  })
})
