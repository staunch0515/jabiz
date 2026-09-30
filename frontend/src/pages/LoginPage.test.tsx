import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import LoginPage from './LoginPage'

const signIn = vi.fn()
const verify = vi.fn()

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ signIn, verify, signedIn: false, ready: true }),
  lastUserName: () => 'amy',
}))
vi.mock('qrcode', () => ({ default: { toCanvas: vi.fn(async () => undefined) } }))

function page(state?: object) {
  render(
    <App>
      <MemoryRouter initialEntries={[{ pathname: '/login', state }]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/data" element={<div data-testid="home" />} />
        </Routes>
      </MemoryRouter>
    </App>,
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
})
