import { configureClient, session } from '@jabiz/client'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { nameFromEmail } from '../pages/ProfileForm'
import { forgetShareNext, rememberShareNext, shareNext } from './share'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device', emailLink: true }

const SHARED = { publicationId: 'pub1', name: 'Spring quiz', quizTitle: 'Capitals', kind: 'QUIZ',
  startAt: '2026-01-01T00:00:00Z', endAt: '2099-01-01T00:00:00Z', timeLimitSec: 300, questionCount: 10,
  fullScore: 100, passScore: 60, maxReward: '500', sponsorCompany: 'Acme' }

interface Server {
  /** The public template's row; none when the publication is not live. */
  shared?: Record<string, unknown> | null
  /** Signed in: who (no profile yet), from a link that opened the account. */
  me?: { displayName: string | null; email: string }
}

/** The server of a share link: the public template, the mailed link's request, and for a new taker the welcome. */
function server(options: Server = {}) {
  const asked: string[] = []
  const posted: { path: string; body: unknown }[] = []
  let profile: Record<string, unknown> | null = null
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    asked.push(path + url.search)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/public/queries/qb.public.publication':
        return json(200, { items: options.shared === null ? [] : [options.shared ?? SHARED], offset: 0, limit: 1 })
      case '/api/auth/email-link':
        return new Response(null, { status: 202 })
      case '/api/auth/me':
        return options.me ? json(200, { userId: 'u9', ...options.me, roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.account.read'], emailVerified: true, idleTimeoutSeconds: 0 }) : json(401, {})
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: profile ? [{ id: 'p1', version: 1, attributes: profile }] : [], offset: 0, limit: 1 })
      case '/api/datasets/urn:jabiz:dataset:default:QbCountry/query':
        return json(200, { items: [
          { id: 'c1', attributes: { code: 'JP', name: { en: 'Japan', zh: '日本', ja: '日本' } } },
          { id: 'c2', attributes: { code: 'FR', name: { en: 'France', zh: '法国', ja: 'フランス' } } },
        ], offset: 0, limit: 300 })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:currency':
        return json(200, [{ code: 'EUR', label: 'Euro' }, { code: 'JPY', label: 'Japanese yen' }])
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/processes/QB_PROFILE_SAVE/latest':
        profile = { displayName: (body as { displayName: string }).displayName, countryCode: 'JP', city: null,
          preferredCurrency: 'JPY', revision: 1 }
        return json(200, { output: { userId: 'u9', profileId: 'p1', revision: 1, editedAt: '2026-10-14T00:00:00Z',
          created: true } })
      default:
        return json(404, {})
    }
  })
  return { asked, posted }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  forgetShareNext()
  vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['ja-JP', 'en'])
})
afterEach(() => {
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('the share link before signing in', () => {
  it('shows the quiz and asks for the address only, for a link back to it', async () => {
    const { posted, asked } = server()
    window.history.replaceState(null, '', '/p/pub1')
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Spring quiz' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/p/pub1')
    expect(screen.getByTestId('share-sponsor')).toHaveTextContent('Acme')
    expect(screen.getByTestId('share-facts')).toHaveTextContent('10')
    expect(asked.some((a) => a.startsWith('/api/public/queries/qb.public.publication?p.publicationId=pub1&p.at=')))
      .toBe(true)
    // No token is sent for the public page.
    expect(asked).not.toContain('/api/auth/me')

    await userEvent.type(screen.getByTestId('email-link-email'), 'mei@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Start with my e-mail address' }))
    expect(await screen.findByTestId('email-link-sent')).toHaveTextContent('mei@example.com')
    expect(posted).toContainEqual({ path: '/api/auth/email-link',
      body: { email: 'mei@example.com', entry: 'app', to: '/p/pub1', locale: 'en' } })
    // A password still works, and leads back here.
    expect(screen.getByTestId('share-password')).toHaveAttribute('href', '/login?next=%2Fp%2Fpub1')
  })

  it('says when the quiz is not open', async () => {
    server({ shared: null })
    window.history.replaceState(null, '', '/p/gone')
    render(<App />)
    expect(await screen.findByText('This quiz is not open now')).toBeInTheDocument()
    expect(screen.queryByTestId('email-link-form')).toBeNull()
  })

  it('opens the page of the mailed link', async () => {
    server()
    window.history.replaceState(null, '', '/auth/email-link?entry=app&token=t1&to=%2Fp%2Fpub1')
    render(<App />)
    expect(await screen.findByTestId('email-link-start')).toBeInTheDocument()
    expect(window.location.search).not.toContain('token')
  })

  it('signs in with a password back to the publication', async () => {
    server()
    window.history.replaceState(null, '', '/login?next=%2Fp%2Fpub1')
    render(<App />)
    expect(await screen.findByRole('textbox', { name: 'User name' })).toBeInTheDocument()
  })
})

describe('a new taker from a share link', () => {
  beforeEach(() => session.store('access', 'refresh'))

  it('gives only the country and goes on to the publication', async () => {
    const { posted } = server({ me: { displayName: null, email: 'mei.tanaka@example.com' } })
    window.history.replaceState(null, '', '/p/pub1')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/welcome'))
    expect(await screen.findByTestId('profile-country')).toHaveTextContent('Japan')
    expect(screen.queryByTestId('profile-name')).toBeNull()
    expect(screen.queryByTestId('profile-city')).toBeNull()
    expect(screen.queryByTestId('profile-currency')).toBeNull()
    await userEvent.click(screen.getByTestId('profile-save'))
    await waitFor(() => expect(window.location.pathname).toBe('/p/pub1'))
    expect(posted).toContainEqual({ path: '/api/processes/QB_PROFILE_SAVE/latest', body: {
      displayName: 'mei.tanaka', countryCode: 'JP', city: null, preferredCurrency: 'JPY', baseRevision: null } })
    expect(shareNext()).toBeNull()
  })
})

describe('the share helpers', () => {
  it('keep a publication page only, and name a taker after their address', () => {
    rememberShareNext('/p/abc')
    expect(shareNext()).toBe('/p/abc')
    rememberShareNext('/wallet')
    expect(shareNext()).toBeNull()
    rememberShareNext('//evil.example/p/x')
    expect(shareNext()).toBeNull()
    expect(nameFromEmail('Mei.T@example.com')).toBe('Mei.T')
    expect(nameFromEmail(null)).toBe('')
    expect(nameFromEmail(`${'a'.repeat(150)}@x`)).toHaveLength(100)
  })
})
