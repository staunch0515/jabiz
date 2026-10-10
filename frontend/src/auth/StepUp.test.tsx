import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { sessionFetch } from '../api/client'
import { session } from '../api/session'
import i18n from '../i18n'
import { StepUpProvider } from './StepUp'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderProvider() {
  render(
    <MemoryRouter>
      <StepUpProvider>
        <div />
      </StepUpProvider>
    </MemoryRouter>,
  )
}

describe('StepUpProvider', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
    session.store('old', 'refresh')
  })
  afterEach(() => {
    session.clear()
    vi.unstubAllGlobals()
  })

  it('asks for the code, retries with the new token, and shows a wrong code', async () => {
    let attempts = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (url.endsWith('/api/auth/step-up')) {
        attempts += 1
        return attempts === 1
          ? json(422, { violations: [{ ruleCode: 'MFA_CODE_INVALID', message: 'The code is not valid.' }] })
          : json(200, { accessToken: 'stepped' })
      }
      const auth = new Headers(init?.headers).get('Authorization')
      return auth === 'Bearer stepped' ? json(200, {}) : json(403, { violations: [{ ruleCode: 'MFA_REQUIRED' }] })
    }))
    renderProvider()

    const response = sessionFetch('/api/imports/x/commit', { method: 'POST' })
    fireEvent.change(await screen.findByTestId('step-up-code'), { target: { value: '000000' } })
    fireEvent.click(screen.getByRole('button', { name: /Verify/ }))
    expect((await screen.findByTestId('step-up-error')).textContent).toContain('The code is not valid.')
    fireEvent.change(screen.getByTestId('step-up-code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: /Verify/ }))
    await waitFor(async () => expect((await response).status).toBe(200))
  })

  it('points users without two-step verification to set it up', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      return url.endsWith('/api/auth/step-up')
        ? json(422, { violations: [{ ruleCode: 'MFA_NOT_ENROLLED', message: 'Set up first.' }] })
        : json(403, { violations: [{ ruleCode: 'MFA_REQUIRED' }] })
    }))
    renderProvider()
    const response = sessionFetch('/api/x', { method: 'POST' })
    fireEvent.change(await screen.findByTestId('step-up-code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: /Verify/ }))
    expect(await screen.findByText(/Set it up under Security first/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Cancel/ }))
    expect((await response).status).toBe(403)
  })
})
