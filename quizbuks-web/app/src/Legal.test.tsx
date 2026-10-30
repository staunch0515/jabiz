import { changeLanguage, configureClient, session } from '@jabiz/client'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expectAccessible } from '@jabiz/ui/testing'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import './messages'
import { APP_VERSION } from './pages/AboutPage'

/*
 * Terms, privacy and help in the app (docs/quizbuks/plans/Q7-messages-legal.md, Q7b): the help centre and the texts
 * without signing in, the texts in the reader's language with Japanese governing, the agreement on the welcome step,
 * the prompt for whoever must accept again, and the About page.
 */

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }

const TERMS_V1 = { versionNo: 1, title: { en: 'Terms of use', ja: '利用規約' },
  body: { en: '# Terms\n\nPlay fair. <script>alert(1)</script>', ja: '# 規約\n\n公正に。' }, changeSummary: null,
  requiresConsent: true, effectiveAt: '2026-09-01T00:00:00Z' }
const TERMS_V2 = { versionNo: 2, title: { en: 'Terms of use', ja: '利用規約' },
  body: { en: 'Version two.', ja: '第 2 版。' }, changeSummary: { en: 'Rewards are clearer.', ja: '報酬を明確に。' },
  requiresConsent: true, effectiveAt: '2099-01-01T00:00:00Z' }

const consent = (kind: string, versionNo: number, mustAccept: boolean) => ({ kind, versionId: `${kind}-${versionNo}`,
  versionNo, title: { en: kind }, changeSummary: { en: `${kind} changed.` }, requiresConsent: true,
  effectiveAt: '2026-10-01T00:00:00Z', toAccept: versionNo, accepted: mustAccept ? null : versionNo, mustAccept })

interface Me {
  emailVerified?: boolean
  profile?: Record<string, unknown> | null
  consents?: Record<string, unknown>[]
  support?: string | null
  /** The first acceptance finds a newer version in effect (it took effect meanwhile): refused, then version 4. */
  outdatedOnce?: boolean
}

/** A version withdrawn before it took effect (review M1). */
const TERMS_V3_WITHDRAWN = { versionNo: 3, title: { en: 'Terms of use', ja: '利用規約' }, body: { en: 'Never.' },
  changeSummary: { en: 'A mistake.' }, requiresConsent: true, effectiveAt: '2099-06-01T00:00:00Z', withdrawn: true }

/**
 * The server as the app sees it; `me` absent: nobody is signed in. Records what was asked and posted. With
 * `withdrawn`, the history has a withdrawn version 3.
 */
function server(me?: Me, withdrawn = false) {
  const asked: string[] = []
  const posted: { path: string; body: Record<string, unknown> }[] = []
  let consents = me?.consents ?? []
  let profile = me?.profile ?? null
  let outdated = me?.outdatedOnce ?? false
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
      case '/api/auth/me':
        return me ? json(200, { userId: 'u1', displayName: 'Mei', email: 'mei@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.account.read'], emailVerified: me.emailVerified ?? true,
          idleTimeoutSeconds: 0 }) : json(401, {})
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: profile ? [{ id: 'p1', version: 1, attributes: profile }] : [], offset: 0,
          limit: 1 })
      case '/api/datasets/urn:jabiz:dataset:default:QbCountry/query':
        return json(200, { items: [{ id: 'c1', attributes: { code: 'JP', name: { en: 'Japan' } } }], offset: 0,
          limit: 300 })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:currency':
        return json(200, [{ code: 'JPY', label: 'Japanese yen' }])
      case '/api/queries/qb.me.consents':
        return json(200, { items: consents, offset: 0, limit: 20 })
      case '/api/queries/qb.me.support':
        return json(200, { items: [{ email: me?.support ?? null }], offset: 0, limit: 20 })
      case '/api/queries/qb.app.feed':
        return json(200, { items: [], offset: 0, limit: 20, total: 0 })
      case '/api/processes/QB_CONSENT_ACCEPT/latest':
        if (outdated) {
          outdated = false
          consents = [consent('TERMS_APP', 4, true)]
          return json(422, { status: 422, code: 'QB_TERMS_OUTDATED', detail: 'Refused', violations: [{
            field: 'items', ruleCode: 'QB_TERMS_OUTDATED', message: 'Version 3 of TERMS_APP is not the one in effect' }] })
        }
        consents = consents.map((row) => ({ ...row, mustAccept: false }))
        return json(200, { output: { recorded: ['TERMS_APP'] } })
      case '/api/processes/QB_PROFILE_SAVE/latest':
        profile = { displayName: 'Mei', countryCode: 'JP', city: null, preferredCurrency: 'JPY', revision: 1 }
        return json(200, { output: { userId: 'u1', profileId: 'p1', revision: 1, editedAt: '2026-10-14T00:00:00Z',
          created: true } })
      case '/api/public/queries/qb.public.faq': {
        const q = url.searchParams.get('p.q')
        const rows = [
          { faqId: 'f1', category: 'REWARDS', question: { en: 'When are rewards paid?', ja: '報酬はいつ？' },
            answer: { en: 'At once. <img src=x onerror=alert(1)>' }, seq: 2 },
          { faqId: 'f2', category: 'ACCOUNT', question: { en: 'How do I sign up?' }, answer: { en: 'Use **Sign up**.' },
            seq: 1 },
        ]
        return json(200, { items: q ? rows.filter((r) => r.question.en.includes(q)) : rows, offset: 0, limit: 100 })
      }
      case '/api/public/queries/qb.public.legal':
        return json(200, { items: [{ kind: url.searchParams.get('p.kind'), ...TERMS_V1, upcomingVersionNo: 2,
          upcomingEffectiveAt: TERMS_V2.effectiveAt }], offset: 0, limit: 100 })
      case '/api/public/queries/qb.public.legal-version':
        return json(200, { items: [{ kind: 'TERMS_APP', ...(url.searchParams.get('p.versionNo') === '3'
          ? TERMS_V3_WITHDRAWN : { ...TERMS_V2, withdrawn: false }) }], offset: 0, limit: 100 })
      case '/api/public/queries/qb.public.legal-history':
        return json(200, { items: [...(withdrawn ? [TERMS_V3_WITHDRAWN] : []), { ...TERMS_V2, withdrawn: false },
          { ...TERMS_V1, withdrawn: false }], offset: 0, limit: 100 })
      default:
        return json(404, {})
    }
  })
  return { asked, posted }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  window.history.replaceState(null, '', '/')
  vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['ja-JP', 'en'])
})
afterEach(async () => {
  vi.restoreAllMocks()
  await changeLanguage('en')
})

describe('a visitor', () => {
  it('reads the help by category and searches it, without the contact', async () => {
    const { asked } = server()
    window.history.replaceState(null, '', '/help')
    render(<App />)
    const account = await screen.findByTestId('help-category-ACCOUNT')
    const rewards = screen.getByTestId('help-category-REWARDS')
    // In the categories' order, whatever the questions' order.
    expect(account.compareDocumentPosition(rewards) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(within(rewards).getByTestId('faq-answer').innerHTML).not.toContain('<img')
    expect(screen.getByTestId('help-contact')).toHaveTextContent('Sign in to contact us.')
    expect(screen.queryByTestId('help-mail')).toBeNull()
    await userEvent.type(screen.getByTestId('help-search'), 'sign')
    // The search is asked once the typing settles; until its answer the old results give way to the loading state.
    await waitFor(() =>
      expect(asked).toContain('/api/public/queries/qb.public.faq?p.audience=APP&p.q=sign&limit=100&count=false'))
    expect(await screen.findByTestId('help-category-ACCOUNT')).toBeInTheDocument()
    expect(screen.queryByTestId('help-category-REWARDS')).toBeNull()
    expect(screen.getByTestId('public-back')).toHaveAttribute('href', '/login')
  })

  it('reads the terms in effect with the announced version and every version', async () => {
    server()
    window.history.replaceState(null, '', '/legal/terms')
    render(<App />)
    const text = await screen.findByTestId('legal-text')
    expect(text).toHaveAttribute('data-version', '1')
    // Raw HTML in the text is never rendered.
    expect(text.querySelector('script')).toBeNull()
    expect(screen.getByTestId('legal-governing')).toHaveTextContent('The Japanese text governs')
    expect(screen.getByTestId('legal-upcoming')).toHaveTextContent('version 2')
    expect(screen.getByTestId('legal-history-2')).toHaveTextContent('Announced')
    expect(screen.getByTestId('legal-history-1')).toHaveTextContent('In effect')
    await userEvent.click(within(screen.getByTestId('legal-upcoming')).getByRole('link'))
    await waitFor(() => expect(screen.getByTestId('legal-text')).toHaveAttribute('data-version', '2'))
    expect(window.location.search).toBe('?v=2')
  })

  it('sees a withdrawn version marked in the history and on its page (review M1)', async () => {
    server(undefined, true)
    window.history.replaceState(null, '', '/legal/terms')
    render(<App />)
    const row = await screen.findByTestId('legal-history-3')
    expect(within(row).getByTestId('legal-withdrawn')).toBeInTheDocument()
    expect(within(screen.getByTestId('legal-history-2')).queryByTestId('legal-withdrawn')).toBeNull()
    await userEvent.click(within(row).getByRole('link'))
    expect(await screen.findByTestId('legal-text')).toHaveAttribute('data-version', '3')
    expect(screen.getByTestId('legal-withdrawn-note')).toBeInTheDocument()
  })

  it('in Chinese, reads the English text, told so, and may show the Japanese one', async () => {
    server()
    await changeLanguage('zh')
    window.history.replaceState(null, '', '/legal/terms')
    render(<App />)
    const body = await screen.findByTestId('legal-body')
    expect(screen.getByTestId('legal-in-english')).toBeInTheDocument()
    expect(within(body).getByTestId('markdown-view')).toHaveAttribute('lang', 'en')
    await userEvent.click(screen.getByTestId('legal-original'))
    expect(within(body).getByTestId('markdown-view')).toHaveAttribute('lang', 'ja')
    expect(within(body).getByTestId('markdown-view')).toHaveTextContent('公正に。')
  })

  it('in Japanese, reads the governing text without a note', async () => {
    server()
    await changeLanguage('ja')
    window.history.replaceState(null, '', '/legal/terms')
    render(<App />)
    expect(await screen.findByTestId('legal-body')).toHaveTextContent('公正に。')
    expect(screen.queryByTestId('legal-governing')).toBeNull()
  })

  it('reads about the app: its version and the texts', async () => {
    server()
    window.history.replaceState(null, '', '/about')
    render(<App />)
    expect(await screen.findByTestId('about-version')).toHaveTextContent(APP_VERSION)
    expect(screen.getByTestId('about-built')).not.toHaveTextContent('')
    expect(screen.getByTestId('legal-links').querySelector('a[href="/legal/privacy"]')).not.toBeNull()
    expect(screen.queryByTestId('about-sign-out')).toBeNull()
  })

  it('sees where to read the terms when signing up', async () => {
    server()
    window.history.replaceState(null, '', '/signup')
    render(<App />)
    const link = await screen.findByTestId('legal-link-TERMS_APP')
    expect(link).toHaveAttribute('href', '/legal/terms')
    // No new window: in an installed app it has no way back but closing it.
    expect(link).not.toHaveAttribute('target')
    await userEvent.click(link)
    const sheet = await screen.findByTestId('legal-sheet')
    expect(await within(sheet).findByTestId('legal-text')).toHaveAttribute('data-version', '1')
    expect(window.location.pathname).toBe('/signup')
    await expectAccessible()
    // Another version shows in the sheet, the address stays.
    await userEvent.click(within(within(sheet).getByTestId('legal-upcoming')).getByRole('button'))
    await waitFor(() => expect(within(sheet).getByTestId('legal-text')).toHaveAttribute('data-version', '2'))
    expect(window.location.search).toBe('')
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByTestId('legal-sheet')).toBeNull())
    expect(screen.getByTestId('legal-link-PRIVACY')).not.toHaveAttribute('target')
  })
})

describe('a signed-in taker', () => {
  beforeEach(() => session.store('access', 'refresh'))

  it('accepts the texts in effect with the welcome step', async () => {
    const { posted } = server({ profile: null, consents: [consent('TERMS_APP', 3, true), consent('PRIVACY', 1, true)] })
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/welcome'))
    // No prompt before the profile: the welcome step accepts.
    expect(screen.queryByTestId('consent-gate')).toBeNull()
    expect(await screen.findByTestId('legal-link-TERMS_APP')).toHaveAttribute('aria-haspopup', 'dialog')
    expect(screen.getByTestId('profile-save')).toBeDisabled()
    await userEvent.click(screen.getByTestId('profile-terms'))
    await userEvent.click(screen.getByTestId('profile-save'))
    await waitFor(() => expect(window.location.pathname).toBe('/'))
    expect(posted.find((p) => p.path === '/api/processes/QB_PROFILE_SAVE/latest')?.body).toMatchObject({
      acceptTerms: true, termsVersionNo: 3, privacyVersionNo: 1 })
  })

  it('who must accept again sees only the prompt, then the app', async () => {
    const { posted } = server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null,
      preferredCurrency: 'JPY', revision: 1 }, consents: [consent('TERMS_APP', 3, true), consent('PRIVACY', 1, false)] })
    render(<App />)
    expect(await screen.findByTestId('consent-gate')).toHaveTextContent('TERMS_APP changed.')
    expect(screen.queryByRole('navigation', { name: 'App' })).toBeNull()
    await userEvent.click(screen.getByTestId('consent-agree'))
    await userEvent.click(screen.getByTestId('consent-accept'))
    await waitFor(() => expect(screen.queryByTestId('consent-gate')).toBeNull())
    expect(screen.getByRole('navigation', { name: 'App' })).toBeInTheDocument()
    expect(posted.find((p) => p.path === '/api/processes/QB_CONSENT_ACCEPT/latest')?.body).toEqual({
      items: [{ kind: 'TERMS_APP', versionNo: 3 }] })
  })

  it('reads the very version the prompt names (review L6)', async () => {
    server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null, preferredCurrency: 'JPY', revision: 1 },
      consents: [consent('TERMS_APP', 3, true)] })
    render(<App />)
    const item = within(await screen.findByTestId('consent-item-TERMS_APP'))
    expect(item.getByTestId('legal-link-TERMS_APP')).toHaveAttribute('href', '/legal/terms?v=3')
  })

  it('told a newer version took effect, shows it and accepts that one, once agreed again (review M2)', async () => {
    const { posted } = server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null,
      preferredCurrency: 'JPY', revision: 1 }, consents: [consent('TERMS_APP', 3, true)], outdatedOnce: true })
    render(<App />)
    expect(await screen.findByTestId('consent-item-TERMS_APP')).toHaveTextContent(/(Version 3|第 3 版)/)
    await userEvent.click(screen.getByTestId('consent-agree'))
    await userEvent.click(screen.getByTestId('consent-accept'))
    await waitFor(() => expect(screen.getByTestId('consent-item-TERMS_APP')).toHaveTextContent(/(Version 4|第 4 版)/))
    expect(screen.getByTestId('consent-error')).toBeInTheDocument()
    // What the user agreed to was version 3: they agree again before version 4 is accepted.
    expect(screen.getByTestId('consent-accept')).toBeDisabled()
    await userEvent.click(screen.getByTestId('consent-agree'))
    await userEvent.click(screen.getByTestId('consent-accept'))
    await waitFor(() => expect(screen.queryByTestId('consent-gate')).toBeNull())
    expect(posted.filter((p) => p.path === '/api/processes/QB_CONSENT_ACCEPT/latest').map((p) => p.body)).toEqual([
      { items: [{ kind: 'TERMS_APP', versionNo: 3 }] }, { items: [{ kind: 'TERMS_APP', versionNo: 4 }] }])
  })

  it('whose address is not verified is told, not stopped (nothing they do writes)', async () => {
    server({ emailVerified: false, profile: { displayName: 'Mei', countryCode: 'JP', city: null,
      preferredCurrency: 'JPY', revision: 1 }, consents: [consent('TERMS_APP', 3, true)] })
    render(<App />)
    expect(await screen.findByTestId('consent-unverified')).toBeInTheDocument()
    expect(screen.queryByTestId('consent-gate')).toBeNull()
    expect(screen.getByRole('navigation', { name: 'App' })).toBeInTheDocument()
  })

  it('finds the help, the texts and the contact address from the account', async () => {
    server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null, preferredCurrency: 'JPY', revision: 1 },
      support: 'help@quizbuks.example' })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    await userEvent.click(await screen.findByTestId('account-about'))
    expect(await screen.findByTestId('about-sign-out')).toBeInTheDocument()
    window.history.replaceState(null, '', '/help')
    window.dispatchEvent(new PopStateEvent('popstate'))
    expect(await screen.findByTestId('help-mail')).toHaveAttribute('href', 'mailto:help@quizbuks.example')
    expect(screen.getByTestId('public-back')).toHaveAttribute('href', '/')
  })
})
