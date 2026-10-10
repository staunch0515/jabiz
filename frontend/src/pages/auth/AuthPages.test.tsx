import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../i18n'
import { expectAccessible } from '../../test/axe'
import JoinPage from './JoinPage'
import PasswordResetPage from './PasswordResetPage'
import VerifyEmailPage from './VerifyEmailPage'

type Answer = { status: number; body?: unknown }

/** Answers the account endpoints as given, by path; the entry is the portal's. Returns the posts made. */
function server(answers: Record<string, Answer | Answer[]>) {
  const posts: { path: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: Request) => {
    const path = new URL(input.url).pathname
    if (input.method === 'GET' && path.startsWith('/api/auth/entries/')) {
      return json(200, { name: 'portal', selfRegistration: true, requireVerifiedEmail: true, appPath: '/portal/' })
    }
    posts.push({ path, body: await input.json() })
    const planned = answers[path]
    const answer = Array.isArray(planned) ? planned.shift() : planned
    if (!answer) return json(500, {})
    return answer.body === undefined
      ? new Response(null, { status: answer.status, headers: { 'Content-Length': '0' } })
      : json(answer.status, answer.body)
  }))
  return posts
}

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

function problem(status: number, ruleCode: string, field?: string) {
  return { status, body: { status, violations: [{ field, ruleCode, message: `${ruleCode} message` }] } }
}

function page(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/auth/verify-email" element={<VerifyEmailPage />} />
          <Route path="/auth/join" element={<JoinPage />} />
          <Route path="/auth/password-reset" element={<PasswordResetPage />} />
          <Route path="/login" element={<div data-testid="login" />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function type(placeholder: string, value: string) {
  fireEvent.change(screen.getByPlaceholderText(placeholder), { target: { value } })
}

describe('account pages', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('VerifyEmailPage', () => {
    it('posts the token of the link once and leads to the entry', async () => {
      const posts = server({ '/api/auth/verify-email': { status: 204 } })
      const { container } = page('/auth/verify-email?entry=portal&token=tok-1')
      await waitFor(() => expect(screen.getByTestId('verify-state').textContent).toContain('is verified'))
      expect(posts).toEqual([{ path: '/api/auth/verify-email', body: { token: 'tok-1' } }])
      await waitFor(() => expect(screen.getByTestId('auth-continue').getAttribute('href')).toBe('/portal/'))
      await expectAccessible(container)
    })

    it('offers a new link for a link that is no good, answered alike for every address', async () => {
      const posts = server({
        '/api/auth/verify-email': problem(422, 'TOKEN_INVALID'),
        '/api/auth/verify-email/resend': { status: 202 },
      })
      page('/auth/verify-email?entry=portal&token=old')
      await waitFor(() => expect(screen.getByTestId('verify-state').textContent).toContain('invalid'))
      type('E-mail address', ' ann@example.com ')
      fireEvent.click(screen.getByRole('button', { name: 'Send a new link' }))
      await waitFor(() => expect(screen.getByTestId('resend-state').textContent).toContain('If the address'))
      expect(posts[1]).toEqual({ path: '/api/auth/verify-email/resend', body: { email: 'ann@example.com', entry: 'portal' } })
    })

    it('sends nothing without a token and keeps to entries of a proper name', async () => {
      const posts = server({})
      page('/auth/verify-email?entry=../evil')
      expect(screen.getByTestId('verify-state').textContent).toContain('invalid')
      type('E-mail address', 'ann@example.com')
      fireEvent.click(screen.getByRole('button', { name: 'Send a new link' }))
      await waitFor(() => expect(posts).toHaveLength(1))
      expect(posts[0].body).toEqual({ email: 'ann@example.com', entry: 'admin' })
    })
  })

  describe('JoinPage', () => {
    it('does nothing until asked, then joins an account whose address was verified', async () => {
      const posts = server({ '/api/auth/join': { status: 204 } })
      page('/auth/join?entry=portal&token=j1')
      expect(posts).toHaveLength(0)
      fireEvent.click(screen.getByTestId('join-continue'))
      await waitFor(() => expect(screen.getByTestId('join-state').textContent).toContain('Done'))
      expect(posts).toEqual([{ path: '/api/auth/join', body: { token: 'j1' } }])
    })

    it('asks for a new password when the address was never verified', async () => {
      const posts = server({
        '/api/auth/join': [problem(422, 'REQUIRED', 'password'), problem(400, 'PASSWORD_TOO_SHORT', 'password'),
          { status: 204 }],
      })
      const { container } = page('/auth/join?entry=portal&token=j2')
      fireEvent.click(screen.getByTestId('join-continue'))
      expect(await screen.findByTestId('join-password-hint')).toBeTruthy()
      await expectAccessible(container)

      type('New password', 'short')
      type('Repeat the new password', 'other')
      fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
      expect(await screen.findByText('The passwords do not match.')).toBeTruthy()
      expect(posts).toHaveLength(1)

      type('Repeat the new password', 'short')
      fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
      expect(await screen.findByText('PASSWORD_TOO_SHORT message')).toBeTruthy()

      type('New password', 'correct horse battery')
      type('Repeat the new password', 'correct horse battery')
      fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
      await waitFor(() => expect(screen.getByTestId('join-state').textContent).toContain('Done'))
      expect(posts[2].body).toEqual({ token: 'j2', password: 'correct horse battery' })
    })

    it('says when the link is no good', async () => {
      server({ '/api/auth/join': problem(422, 'TOKEN_INVALID') })
      page('/auth/join?token=used')
      fireEvent.click(screen.getByTestId('join-continue'))
      await waitFor(() => expect(screen.getByTestId('join-state').textContent).toContain('invalid'))
    })
  })

  describe('PasswordResetPage', () => {
    it('asks for the address and answers alike whatever it is', async () => {
      const posts = server({ '/api/auth/password-reset/request': { status: 202 } })
      const { container } = page('/auth/password-reset?entry=portal')
      await expectAccessible(container)
      fireEvent.click(screen.getByRole('button', { name: 'Send the link' }))
      expect(await screen.findByText('Enter your e-mail address.')).toBeTruthy()
      type('E-mail address', 'bob@example.com')
      fireEvent.click(screen.getByRole('button', { name: 'Send the link' }))
      await waitFor(() => expect(screen.getByTestId('reset-state').textContent).toContain('If the address'))
      expect(posts).toEqual([{ path: '/api/auth/password-reset/request', body: { email: 'bob@example.com', entry: 'portal' } }])
    })

    it('sets the new password with the token of the link and leads to signing in', async () => {
      const posts = server({ '/api/auth/password-reset/confirm': { status: 204 } })
      page('/auth/password-reset?entry=portal&token=r1')
      type('New password', 'brand new horse')
      type('Repeat the new password', 'brand new horse')
      fireEvent.click(screen.getByRole('button', { name: 'Set the password' }))
      await waitFor(() => expect(screen.getByTestId('reset-state').textContent).toContain('Sign in with the new password'))
      expect(posts).toEqual([{ path: '/api/auth/password-reset/confirm', body: { token: 'r1', password: 'brand new horse' } }])
      await waitFor(() => expect(screen.getByTestId('auth-continue').getAttribute('href')).toBe('/portal/'))
    })

    it('says when the link is no good and offers a new one', async () => {
      server({ '/api/auth/password-reset/confirm': problem(422, 'TOKEN_INVALID') })
      page('/auth/password-reset?entry=portal&token=old')
      type('New password', 'brand new horse')
      type('Repeat the new password', 'brand new horse')
      fireEvent.click(screen.getByRole('button', { name: 'Set the password' }))
      await waitFor(() => expect(screen.getByTestId('reset-state').textContent).toContain('invalid'))
      fireEvent.click(screen.getByRole('link', { name: 'Send the link' }))
      expect(await screen.findByRole('button', { name: 'Send the link' })).toBeTruthy()
    })

    it('speaks the language of the interface', async () => {
      server({})
      await i18n.changeLanguage('ja')
      page('/auth/password-reset')
      expect(screen.getByRole('button', { name: 'リンクを送る' })).toBeTruthy()
    })
  })
})
