import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { SPONSOR } from '../messages'
import { HelpPanel, openHelp } from './HelpPanel'
import { SPONSOR_PAGE_NAMES, sponsorPageName } from './pages'

type Row = Record<string, unknown>

/** Who is signed in: a Sponsor, who may ask the help unless a test says otherwise. */
const auth = vi.hoisted(() => ({ permissions: new Set<string>(['qb.help.use', 'qb.sponsor.me']) }))

vi.mock('@jabiz/client', async (original) => ({
  ...(await original<typeof import('@jabiz/client')>()),
  useAuth: () => ({ permissions: auth.permissions }),
}))

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const T1 = '0192f0a0-0000-7000-8000-0000000000t1'

let threads: Row[]
let messages: Row[]
let refusals: Record<string, { status: number; body: Row }[]>
let posted: { name: string; body: Row }[]
let asked: string[]

beforeEach(() => {
  auth.permissions = new Set(['qb.help.use', 'qb.sponsor.me'])
  window.localStorage.setItem('qb.help.notice.sponsor', '1')
  threads = []
  messages = []
  refusals = {}
  posted = []
  asked = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = (await request.clone().json().catch(() => null)) as Row | null
    asked.push(path)
    if (path === '/api/queries/qb.me.help-settings') {
      return json(200, { items: [{ enabled: true, pollSeconds: 3, pollMaxMinutes: 3, userDaily: 20,
        questionMaxLength: 2000 }] })
    }
    if (path === '/api/queries/qb.me.help-threads') {
      const filter = (body?.filters as Row[] | undefined)?.[0]
      return json(200, { items: filter ? threads.filter((t) => t.threadId === filter.value) : threads })
    }
    if (path === '/api/queries/qb.me.help-messages') return json(200, { items: messages })
    const process = /^\/api\/processes\/([A-Z_]+)\/latest$/.exec(path)?.[1]
    if (process) {
      posted.push({ name: process, body: body ?? {} })
      const refusal = refusals[process]?.shift()
      if (refusal) return json(refusal.status, refusal.body)
      if (process === 'QB_HELP_ESCALATE') {
        threads = [{ threadId: T1, status: 'ESCALATED', escalated: true, waitingFor: 'STAFF',
          createdAt: '2026-11-09T01:00:00Z', lastActivityAt: '2026-11-09T01:00:00Z', firstQuestion: body?.text }]
        messages = [{ messageId: 'm1', kind: 'USER', body: body?.text, createdAt: '2026-11-09T01:00:00Z' }]
        return json(200, { output: { threadId: T1, messageId: 'm1', status: 'ESCALATED' } })
      }
      return json(200, { output: { threadId: T1, messageId: 'm1', status: 'OPEN', jobStatus: 'QUEUED' } })
    }
    return json(404, {})
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})

function renderPanel(path = '/quizzes/q1', search = '') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><HelpPanel path={path} search={search} /></QueryClientProvider>)
}

describe('the header\'s help panel', () => {
  it('opens at the right from "?", asks with the page\'s name, and passes axe in both appearances', async () => {
    renderPanel('/quizzes/q1')
    const button = screen.getByTestId('help-open')
    expect(button).toHaveAccessibleName('Help: ask the AI')
    await userEvent.click(button)
    const panel = await screen.findByTestId('help-panel')
    expect(within(panel).getByRole('heading', { name: 'Ask the AI' })).toBeVisible()
    await expectAccessible()
    await userEvent.type(within(panel).getByTestId('help-ai-text'), 'How do I add a question?')
    await userEvent.click(within(panel).getByTestId('help-ai-submit'))
    await waitFor(() => expect(posted.filter((p) => p.name === 'QB_HELP_ASK').map((p) => p.body)).toEqual([
      { threadId: null, entry: 'sponsor', language: 'en', page: 'quiz-edit', text: 'How do I add a question?' }]))
  })

  it('closes with Escape and gives the focus back to "?"', async () => {
    renderPanel()
    const button = screen.getByTestId('help-open')
    button.focus()
    await userEvent.keyboard('{Enter}')
    expect(await screen.findByTestId('help-panel')).toBeVisible()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByTestId('help-panel')).toBeNull())
    expect(button).toHaveFocus()
  })

  it('opens on a conversation from the inbox', async () => {
    threads = [{ threadId: T1, status: 'ESCALATED', escalated: true, waitingFor: 'USER',
      createdAt: '2026-11-09T01:00:00Z', lastActivityAt: '2026-11-09T02:00:00Z', firstQuestion: 'Invoices?' }]
    messages = [{ messageId: 'm1', kind: 'USER', body: 'Invoices?', createdAt: '2026-11-09T01:00:00Z' },
      { messageId: 'm2', kind: 'STAFF', body: 'Open Transactions.', createdAt: '2026-11-09T02:00:00Z' }]
    renderPanel('/dashboard')
    act(() => openHelp(T1))
    const panel = await screen.findByTestId('help-panel')
    expect(await within(panel).findByText('Open Transactions.')).toBeVisible()
    expect(within(panel).getByTestId('help-ai-escalated')).toHaveTextContent('The support team replied')
    await expectAccessible()
  })

  it('on 409, says the content was updated, reads again and keeps the text', async () => {
    refusals.QB_HELP_ASK = [{ status: 409, body: { detail: 'Changed meanwhile' } }]
    renderPanel()
    await userEvent.click(screen.getByTestId('help-open'))
    const panel = await screen.findByTestId('help-panel')
    await userEvent.type(within(panel).getByTestId('help-ai-text'), 'Why?')
    const before = asked.filter((p) => p === '/api/queries/qb.me.help-settings').length
    await userEvent.click(within(panel).getByTestId('help-ai-submit'))
    const problem = await within(panel).findByTestId('help-ai-problem')
    expect(problem).toHaveAttribute('data-kind', 'conflict')
    expect(problem).toHaveTextContent('The content was updated. Please try again.')
    await waitFor(() => expect(asked.filter((p) => p === '/api/queries/qb.me.help-settings').length)
      .toBeGreaterThan(before))
    expect(within(panel).getByTestId('help-ai-text')).toHaveValue('Why?')
  })

  it('at a limit, hands the question to a person (the Sponsor\'s operator)', async () => {
    refusals.QB_HELP_ASK = [{ status: 422, body: { violations: [{ field: null, ruleCode: 'QB_HELP_MONTHLY_LIMIT',
      message: 'Used up.' }] } }]
    renderPanel('/wallet')
    await userEvent.click(screen.getByTestId('help-open'))
    const panel = await screen.findByTestId('help-panel')
    await userEvent.type(within(panel).getByTestId('help-ai-text'), 'Top-up failed')
    await userEvent.click(within(panel).getByTestId('help-ai-submit'))
    await userEvent.click(await within(panel).findByTestId('help-ai-problem-escalate'))
    await waitFor(() => expect(posted.filter((p) => p.name === 'QB_HELP_ESCALATE').map((p) => p.body)).toEqual([
      { threadId: null, entry: 'sponsor', language: 'en', page: 'wallet', text: 'Top-up failed' }]))
    expect(await within(panel).findByTestId('help-ai-escalated')).toBeVisible()
  })

  it('is not offered to whoever may not ask', () => {
    auth.permissions = new Set(['qb.sponsor.me'])
    renderPanel()
    expect(screen.queryByTestId('help-open')).toBeNull()
  })
})

describe('the Sponsor pages\' names', () => {
  it('are the server\'s list (help.HelpPages), in its order', () => {
    const java = readFileSync(resolve(process.cwd(),
      '../../backend/quizbuks/src/main/java/com/jabiz/quizbuks/help/HelpPages.java'), 'utf8')
    const block = /Map<String, String> SPONSOR = ordered\(([\s\S]*?)\);/.exec(java)![1]
    expect([...block.matchAll(/^\s*"([a-z-]+)", "/gm)].map((m) => m[1])).toEqual([...SPONSOR_PAGE_NAMES])
  })

  it('name the pages by path and tab', () => {
    expect(sponsorPageName('/dashboard')).toBe('dashboard')
    expect(sponsorPageName('/quizzes')).toBe('quizzes')
    expect(sponsorPageName('/quizzes/q1')).toBe('quiz-edit')
    expect(sponsorPageName('/publications/new')).toBe('publication-edit')
    expect(sponsorPageName('/publications/p1')).toBe('publication-detail')
    expect(sponsorPageName('/publications/p1/analytics')).toBe('analytics')
    expect(sponsorPageName('/assets', '?tab=products')).toBe('products')
    expect(sponsorPageName('/assets', '?tab=media')).toBe('assets')
    expect(sponsorPageName('/messages', '?tab=conversations')).toBe('conversations')
    expect(sponsorPageName('/messages/conversations/c1')).toBe('conversations')
    expect(sponsorPageName('/messages')).toBe('notices')
    expect(sponsorPageName('/profile/page')).toBe('page')
    expect(sponsorPageName('/profile/basics')).toBe('profile')
    expect(sponsorPageName('/wallet/topups/t1')).toBe('wallet')
    expect(sponsorPageName('/cash-rewards')).toBeNull()
    expect(sponsorPageName('/preview/x')).toBeNull()
  })
})

describe('the help panel\'s texts', () => {
  it('have the same keys in every language, none empty', () => {
    const english = keyPaths(bundle(SPONSOR, 'en').helpPanel)
    expect(english).toEqual(['open', 'openThread'])
    for (const lng of LANGUAGES) {
      expect(keyPaths(bundle(SPONSOR, lng).helpPanel), lng).toEqual(english)
      expect(emptyTexts(SPONSOR, lng).filter((key) => key.startsWith('helpPanel')), lng).toEqual([])
    }
  })
})
