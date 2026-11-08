import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import i18next from 'i18next'
import App from '../App'
import '../messages'
import { LikedTopics } from './LikedTopics'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }

const TOPICS = [
  { topicId: 'travel', name: JSON.stringify({ zh: '旅行', ja: '旅行', en: 'Travel' }), active: true, mergedInto: null },
  { topicId: 'art', name: JSON.stringify({ zh: '艺术', ja: 'アート', en: 'Art' }), active: true, mergedInto: null },
  { topicId: 'retired', name: JSON.stringify({ zh: '旧词', ja: '旧語', en: 'Retired' }), active: false,
    mergedInto: null },
  { topicId: 'hiking', name: JSON.stringify({ zh: '徒步', ja: 'ハイキング', en: 'Hiking' }), active: false,
    mergedInto: 'travel' },
]

/** The server for a verified taker: the word list, their settings (saved as the server would), the home. */
function server(initial: Record<string, unknown> = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  let settings: Record<string, unknown> = { soundOff: false, likedTopics: null, strictTopics: false, ...initial }
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u1', displayName: 'Kai', email: 'kai@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.account.read', 'qb.play'], emailVerified: true, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Kai', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/datasets/urn:jabiz:dataset:me:QbUserSettings/query':
        return json(200, { items: [{ id: 'u1', version: 1, attributes: settings }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.topics':
        return json(200, { items: TOPICS, offset: 0, limit: 500 })
      case '/api/queries/qb.app.inbox-unread':
        return json(200, { items: [{ messages: 0, broadcasts: 0 }], offset: 0, limit: 1 })
      case '/api/processes/QB_SETTINGS_SAVE/latest': {
        const given = body as { likedTopics: string[] | null; strictTopics: boolean | null }
        settings = { ...settings, likedTopics: given.likedTopics ?? settings.likedTopics,
          strictTopics: given.strictTopics ?? settings.strictTopics }
        const liked = Array.isArray(settings.likedTopics) ? settings.likedTopics
          : typeof settings.likedTopics === 'string' ? settings.likedTopics.split(',') : []
        return json(200, { output: { soundOn: true, contentLanguages: [], publicHandle: null, publicPage: false,
          likedTopics: liked, strictTopics: settings.strictTopics } })
      }
      case '/api/queries/qb.app.feed':
        return json(200, { items: [], offset: 0, limit: 20 })
      default:
        return json(404, {})
    }
  })
  const calls = (name: string) => posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body)
  return { calls }
}

function renderTopics() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <LikedTopics />
    </QueryClientProvider>,
  )
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(async () => {
  session.clear()
  session.store('access', 'refresh')
  configureClient({ entry: 'app' })
  await i18next.changeLanguage('en')
})
afterEach(() => {
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('liked topics and strict mode (F-S9)', () => {
  it('offers the words of the list in the reader\'s language and saves each choice at once', async () => {
    const { calls } = server()
    const { container } = renderTopics()
    expect(await screen.findByLabelText('Travel')).toBeInTheDocument()
    expect(screen.getByLabelText('Art')).toBeInTheDocument()
    // Deactivated and merged words are not offered anew.
    expect(screen.queryByLabelText(/Retired/)).toBeNull()
    expect(screen.queryByLabelText(/Hiking/)).toBeNull()
    // Strict mode needs a liked topic first.
    expect(screen.getByTestId('strict-topics-switch')).toBeDisabled()
    expect(screen.getByTestId('strict-topics-needs')).toBeInTheDocument()
    await userEvent.click(screen.getByTestId('liked-topic-art'))
    await waitFor(() => expect(calls('QB_SETTINGS_SAVE')).toContainEqual(
      expect.objectContaining({ likedTopics: ['art'] })))
    await waitFor(() => expect(screen.getByTestId('strict-topics-switch')).toBeEnabled())
    await userEvent.click(screen.getByTestId('strict-topics-switch'))
    await waitFor(() => expect(calls('QB_SETTINGS_SAVE')).toContainEqual(
      expect.objectContaining({ strictTopics: true })))
    await expectAccessible(container)
  })

  it('keeps a word liked before that is no longer offered, and turns strict mode off any time', async () => {
    const { calls } = server({ likedTopics: 'retired,hiking', strictTopics: true })
    renderTopics()
    expect(await screen.findByTestId('liked-topic-retired')).toBeChecked()
    expect(screen.getByTestId('liked-topic-retired').closest('li')).toHaveTextContent('Retired No longer offered')
    // A merged word is shown by the word it counts as.
    expect(screen.getByTestId('liked-topic-hiking').closest('li')).toHaveTextContent('Travel')
    await waitFor(() => expect(screen.getByTestId('strict-topics-switch')).toBeChecked())
    await userEvent.click(screen.getByTestId('strict-topics-switch'))
    await waitFor(() => expect(calls('QB_SETTINGS_SAVE')).toContainEqual(
      expect.objectContaining({ strictTopics: false })))
  })

  it('shows them in the account\'s "about me" and says on the home that only liked topics show', async () => {
    server({ likedTopics: 'travel', strictTopics: true })
    window.history.replaceState(null, '', '/account/details')
    const { container } = render(<App />)
    expect(await screen.findByTestId('account-topics')).toHaveTextContent('Topics you like')
    expect(await screen.findByTestId('liked-topic-travel')).toBeChecked()
    await expectAccessible(container)
    window.history.replaceState(null, '', '/')
    render(<App />)
    expect(await screen.findByTestId('home-strict-topics')).toHaveTextContent('Showing only the topics you like.')
    expect(screen.getByTestId('home-strict-topics-change')).toHaveAttribute('href', expect.stringContaining(
      '/account/details'))
  })
})
