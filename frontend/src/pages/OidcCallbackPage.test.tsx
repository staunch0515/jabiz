import { render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/problem'
import i18n from '../i18n'
import OidcCallbackPage from './OidcCallbackPage'

const signInWithProvider = vi.fn()

vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ signInWithProvider }) }))
vi.mock('../auth/oidc', () => ({ takeReturnPath: () => '/tasks' }))

function LoginProbe() {
  const location = useLocation()
  return <div data-testid="login">{JSON.stringify(location.state)}</div>
}

function page(search: string) {
  render(
    <App>
      <MemoryRouter initialEntries={[`/login/oidc${search}`]}>
        <Routes>
          <Route path="/login/oidc" element={<OidcCallbackPage />} />
          <Route path="/login" element={<LoginProbe />} />
          <Route path="/tasks" element={<div data-testid="tasks" />} />
        </Routes>
      </MemoryRouter>
    </App>,
  )
}

describe('OidcCallbackPage', () => {
  beforeEach(async () => {
    signInWithProvider.mockReset()
    await i18n.changeLanguage('en')
  })

  it('hands state and code over once and goes where the sign-in started', async () => {
    signInWithProvider.mockResolvedValue({ status: 'SIGNED_IN' })
    page('?state=s1&code=c1')
    expect(await screen.findByTestId('tasks')).toBeTruthy()
    expect(signInWithProvider).toHaveBeenCalledOnce()
    expect(signInWithProvider).toHaveBeenCalledWith('s1', 'c1')
  })

  it('continues on the sign-in page when a second factor comes next', async () => {
    signInWithProvider.mockResolvedValue({ status: 'MFA_REQUIRED', challenge: 'ch' })
    page('?state=s1&code=c1')
    expect((await screen.findByTestId('login')).textContent).toContain('"challenge":"ch"')
  })

  it('shows what the provider or the server refused', async () => {
    page('?error=access_denied&error_description=The+user+cancelled')
    expect((await screen.findByTestId('oidc-error')).textContent).toBe('The user cancelled')
    expect(signInWithProvider).not.toHaveBeenCalled()
  })

  it('shows a refused sign-in', async () => {
    signInWithProvider.mockRejectedValue(new ApiError(401, { violations: [{ ruleCode: 'LOGIN_FAILED', message: 'No.' }] }))
    page('?state=s1&code=c1')
    await waitFor(() => expect(screen.getByTestId('oidc-error').textContent).toBe('No.'))
  })
})
