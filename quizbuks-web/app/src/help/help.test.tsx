import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { helpMessages } from '../qb/helpMessages'
import { HelpChat } from '../qb/HelpChat'
import { helpProblem, plainAnswer, siteLinks } from '../qb/help'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { QB } from '../qb/messages'
import { targetOf } from '../inbox/api'
import { APP_PAGE_NAMES, appPageName, askedFrom, forgetPage, helpThreadPath, notePage } from './pages'

type Row = Record<string, unknown>

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const T1 = '0192f0a0-0000-7000-8000-0000000000t1'

/** The server as the help sees it: its settings, the caller's conversations and messages; what the page posted. */
interface Server {
  settings: Row
  threads: Row[]
  messages: Record<string, Row[]>
  /** The answer to the next process call of a name, once; else the default. */
  refusals: Record<string, { status: number; body: Row }[]>
  posted: { name: string; body: Row }[]
  asked: string[]
}

let server: Server

function thread(over: Row = {}): Row {
  return { threadId: T1, status: 'OPEN', escalated: false, waitingFor: null, resolved: null, closeReason: null,
    createdAt: '2026-11-09T01:00:00Z', lastActivityAt: '2026-11-09T01:00:00Z', firstQuestion: 'How do I withdraw?',
    ...over }
}

function message(id: string, kind: string, over: Row = {}): Row {
  return { messageId: id, kind, body: null, links: null, jobStatus: null, failureCode: null, rating: null,
    createdAt: '2026-11-09T01:00:00Z', ...over }
}

beforeEach(() => {
  window.localStorage.clear()
  server = { settings: { enabled: true, pollSeconds: 1, pollMaxMinutes: 1, userDaily: 20, questionMaxLength: 200 },
    threads: [], messages: {}, refusals: {}, posted: [], asked: [] }
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = (await request.clone().json().catch(() => null)) as Row | null
    server.asked.push(path)
    const query = /^\/api\/queries\/(.+)$/.exec(path)?.[1]
    if (query === 'qb.me.help-settings') return json(200, { items: [server.settings], offset: 0, limit: 1 })
    if (query === 'qb.me.help-threads') {
      const filter = (body?.filters as Row[] | undefined)?.[0]
      const items = filter ? server.threads.filter((t) => t.threadId === filter.value) : server.threads
      return json(200, { items, offset: 0, limit: 50 })
    }
    if (query === 'qb.me.help-messages') {
      const id = String((body?.params as Row).threadId)
      return json(200, { items: server.messages[id] ?? [], offset: 0, limit: 500 })
    }
    const process = /^\/api\/processes\/([A-Z_]+)\/latest$/.exec(path)?.[1]
    if (process) {
      server.posted.push({ name: process, body: body ?? {} })
      const refusal = server.refusals[process]?.shift()
      if (refusal) return json(refusal.status, refusal.body)
      return json(200, { output: answer(process, body ?? {}) })
    }
    return json(404, {})
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
  forgetPage()
})

/** What the processes write: a question waits for its answer; a hand-over makes the conversation a person's. */
function answer(process: string, body: Row): Row {
  const id = (body.threadId as string | null) ?? T1
  if (!server.threads.some((t) => t.threadId === id)) server.threads.push(thread({ threadId: id }))
  const row = server.threads.find((t) => t.threadId === id)!
  const list = (server.messages[id] ??= [])
  if (process === 'QB_HELP_ASK') {
    const toPerson = row.status === 'ESCALATED'
    list.push(message(`m${list.length + 1}`, toPerson ? 'USER' : 'QUESTION', { body: body.text,
      jobStatus: toPerson ? null : 'QUEUED' }))
    return { threadId: id, messageId: `m${list.length}`, status: row.status, jobStatus: toPerson ? null : 'QUEUED' }
  }
  if (process === 'QB_HELP_ESCALATE') {
    Object.assign(row, { status: 'ESCALATED', escalated: true, waitingFor: 'STAFF' })
    if (body.text) list.push(message(`m${list.length + 1}`, 'USER', { body: body.text }))
    return { threadId: id, messageId: null, status: 'ESCALATED' }
  }
  if (process === 'QB_HELP_RATE') {
    const rated = list.find((m) => m.messageId === body.messageId)!
    rated.rating = body.rating
    if (body.rating === 'SOLVED' && row.status === 'ESCALATED') row.status = 'CLOSED'
    return { threadId: id, messageId: body.messageId, status: row.status }
  }
  if (process === 'QB_HELP_CLOSE') {
    row.status = 'CLOSED'
    return { threadId: id, messageId: null, status: 'CLOSED' }
  }
  return {}
}

/** The AI's answer arrives: the question answered, the answer after it. */
function answerArrives(threadId = T1, over: Row = {}) {
  const list = server.messages[threadId]
  list.filter((m) => m.kind === 'QUESTION' && m.jobStatus === 'QUEUED').forEach((m) => { m.jobStatus = 'ANSWERED' })
  list.push(message(`m${list.length + 1}`, 'ANSWER', { body: 'Open [My Kudos](/wallet) and choose **Withdraw**.',
    links: '/wallet\nhttps://evil.example/x\n//evil.example/y\n/help', ...over }))
}

function Harness({ initial = null, page = 'wallet' }: { initial?: string | null; page?: string | null }) {
  const [threadId, setThreadId] = useState<string | null>(initial)
  return <HelpChat entry="app" page={page} threadId={threadId} onThreadChange={setThreadId} />
}

function renderChat(props: { initial?: string | null; page?: string | null; noticeRead?: boolean } = {}) {
  if (props.noticeRead !== false) window.localStorage.setItem('qb.help.notice.app', '1')
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><Harness initial={props.initial} page={props.page} />
  </QueryClientProvider>)
}

const posted = (name: string) => server.posted.filter((p) => p.name === name).map((p) => p.body)

describe('the AI help', () => {
  it('shows the privacy notice once, before the first question, and passes axe in both appearances', async () => {
    renderChat({ noticeRead: false })
    const notice = await screen.findByTestId('help-ai-notice')
    expect(notice).toHaveTextContent('privacy policy')
    expect(screen.queryByTestId('help-ai-form')).toBeNull()
    await expectAccessible()
    await userEvent.click(within(notice).getByTestId('help-ai-notice-ok'))
    expect(await screen.findByTestId('help-ai-form')).toBeVisible()
    expect(window.localStorage.getItem('qb.help.notice.app')).toBe('1')
  })

  it('asks with the page\'s name, waits for the answer, shows it as plain text with the site\'s links and reads it out',
    async () => {
      renderChat()
      const box = await screen.findByTestId('help-ai-text')
      await userEvent.type(box, 'How do I withdraw?')
      await userEvent.click(screen.getByTestId('help-ai-submit'))
      expect(await screen.findByTestId('help-ai-waiting')).toHaveTextContent('The AI is writing an answer')
      expect(posted('QB_HELP_ASK')).toEqual([{ threadId: null, entry: 'app', language: 'en', page: 'wallet',
        text: 'How do I withdraw?' }])
      expect(screen.getByTestId('help-ai-text')).toHaveValue('')
      await expectAccessible()
      // The page asks again every qb.help.poll-seconds until the answer is there.
      answerArrives()
      const answered = await screen.findByTestId('help-ai-rate', {}, { timeout: 4000 })
      const item = answered.closest('li')!
      expect(within(item).getByTestId('help-ai-body')).toHaveTextContent('Open My Kudos and choose Withdraw.')
      // Never markup: the text stays text.
      expect(item.querySelector('strong, b')).toBeNull()
      // Only the site's own paths: another site (or a protocol-relative address) is never a link.
      expect(within(item).getAllByTestId('help-ai-link').map((a) => a.getAttribute('href'))).toEqual(['/wallet',
        '/help'])
      expect(screen.queryByTestId('help-ai-waiting')).toBeNull()
      expect(screen.getByTestId('help-ai-live')).toHaveAttribute('aria-live', 'polite')
      expect(screen.getByTestId('help-ai-live')).toHaveTextContent('The AI answered: Open My Kudos')
      await expectAccessible()
    })

  it('sends a page name only when there is one (none from an answering page)', async () => {
    renderChat({ page: null })
    await userEvent.type(await screen.findByTestId('help-ai-text'), 'Hi')
    await userEvent.click(screen.getByTestId('help-ai-submit'))
    await waitFor(() => expect(posted('QB_HELP_ASK')[0]).toMatchObject({ page: null }))
  })

  it('says the answer takes longer than usual after qb.help.poll-max-minutes, and asks again on request', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    server.threads.push(thread())
    server.messages[T1] = [message('m1', 'QUESTION', { body: 'Slow?', jobStatus: 'QUEUED' })]
    renderChat({ initial: T1 })
    expect(await screen.findByTestId('help-ai-waiting')).toBeVisible()
    await act(async () => { await vi.advanceTimersByTimeAsync(61_000) })
    const slow = await screen.findByTestId('help-ai-slow')
    expect(slow).toHaveTextContent('taking longer than usual')
    // It stopped asking: no more reads of the messages while it waits for the Taker.
    const reads = server.asked.filter((p) => p.endsWith('qb.me.help-messages')).length
    await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })
    expect(server.asked.filter((p) => p.endsWith('qb.me.help-messages')).length).toBe(reads)
    expect(within(slow).getByTestId('help-ai-slow-escalate')).toBeVisible()
    answerArrives()
    await userEvent.click(within(slow).getByTestId('help-ai-check-again'))
    expect(await screen.findByTestId('help-ai-rate')).toBeVisible()
    expect(screen.queryByTestId('help-ai-slow')).toBeNull()
  })

  it('rates an answer; "not solved" offers a person, who takes the conversation', async () => {
    server.threads.push(thread())
    server.messages[T1] = [message('m1', 'QUESTION', { body: 'Q?', jobStatus: 'ANSWERED' }),
      message('m2', 'ANSWER', { body: 'A.', links: '/help' })]
    renderChat({ initial: T1 })
    await userEvent.click(await screen.findByTestId('help-ai-unsolved-rate'))
    expect(posted('QB_HELP_RATE')).toEqual([{ threadId: T1, messageId: 'm2', rating: 'UNSOLVED' }])
    const hint = await screen.findByTestId('help-ai-unsolved')
    expect(await screen.findByTestId('help-ai-rated')).toHaveTextContent('not solved')
    await userEvent.type(screen.getByTestId('help-ai-text'), 'Still stuck')
    await userEvent.click(within(hint).getByTestId('help-ai-unsolved-escalate'))
    await waitFor(() => expect(posted('QB_HELP_ESCALATE')).toEqual([{ threadId: T1, entry: 'app', language: 'en',
      page: 'wallet', text: 'Still stuck' }]))
    // Handed over: the box writes to the person now, and nothing waits for the AI.
    expect(await screen.findByTestId('help-ai-escalated')).toHaveTextContent('A person has this conversation')
    expect(screen.getByLabelText('Your message to the support team')).toBeVisible()
    await userEvent.type(screen.getByTestId('help-ai-text'), 'More details')
    await userEvent.click(screen.getByTestId('help-ai-submit'))
    await waitFor(() => expect(screen.getAllByTestId('help-ai-message').at(-1)).toHaveAttribute('data-kind', 'USER'))
    expect(screen.queryByTestId('help-ai-waiting')).toBeNull()
    await expectAccessible()
  })

  it('marks a reply as solved, which the server answers by closing the conversation', async () => {
    server.threads.push(thread({ status: 'ESCALATED', escalated: true, waitingFor: 'USER' }))
    server.messages[T1] = [message('m1', 'USER', { body: 'Help' }), message('m2', 'STAFF', { body: 'Done.' })]
    renderChat({ initial: T1 })
    expect(await screen.findByTestId('help-ai-escalated')).toHaveTextContent('The support team replied')
    await userEvent.click(screen.getByTestId('help-ai-solved'))
    expect(await screen.findByTestId('help-ai-closed')).toHaveTextContent('ended')
    expect(screen.queryByTestId('help-ai-form')).toBeNull()
  })

  it('when the AI is off, says so and hands the typed question to a person directly', async () => {
    server.settings.enabled = false
    renderChat()
    expect(await screen.findByTestId('help-ai-off')).toHaveTextContent('switched off')
    await expectAccessible()
    await userEvent.type(screen.getByTestId('help-ai-text'), 'How do I change my name?')
    expect(screen.getByTestId('help-ai-submit')).toHaveTextContent('Ask a person')
    await userEvent.click(screen.getByTestId('help-ai-submit'))
    await waitFor(() => expect(posted('QB_HELP_ESCALATE')).toEqual([{ threadId: null, entry: 'app', language: 'en',
      page: 'wallet', text: 'How do I change my name?' }]))
    expect(posted('QB_HELP_ASK')).toEqual([])
    expect(await screen.findByTestId('help-ai-escalated')).toBeVisible()
  })

  it('at a limit, says so, keeps the question and offers a person', async () => {
    server.refusals.QB_HELP_ASK = [{ status: 422, body: { detail: 'Refused', violations: [{ field: null,
      ruleCode: 'QB_HELP_DAILY_LIMIT', message: 'You asked 20 questions today.' }] } }]
    renderChat()
    await userEvent.type(await screen.findByTestId('help-ai-text'), 'One more?')
    await userEvent.click(screen.getByTestId('help-ai-submit'))
    const problem = await screen.findByTestId('help-ai-problem')
    expect(problem).toHaveAttribute('data-kind', 'limit')
    expect(problem).toHaveTextContent('limit of questions')
    expect(problem).toHaveTextContent('You asked 20 questions today.')
    expect(screen.getByTestId('help-ai-text')).toHaveValue('One more?')
    await expectAccessible()
    await userEvent.click(within(problem).getByTestId('help-ai-problem-escalate'))
    await waitFor(() => expect(posted('QB_HELP_ESCALATE')).toMatchObject([{ threadId: null, text: 'One more?' }]))
  })

  it('when switched off meanwhile, says so and offers a person', async () => {
    server.refusals.QB_HELP_ASK = [{ status: 422, body: { violations: [{ field: null, ruleCode: 'QB_HELP_DISABLED',
      message: 'Off.' }] } }]
    renderChat()
    await userEvent.type(await screen.findByTestId('help-ai-text'), 'Anyone?')
    await userEvent.click(screen.getByTestId('help-ai-submit'))
    const problem = await screen.findByTestId('help-ai-problem')
    expect(problem).toHaveAttribute('data-kind', 'off')
    expect(within(problem).getByTestId('help-ai-problem-escalate')).toBeVisible()
  })

  it('on a failure of the server, offers a person', async () => {
    server.refusals.QB_HELP_ASK = [{ status: 500, body: {} }]
    renderChat()
    await userEvent.type(await screen.findByTestId('help-ai-text'), 'Hello?')
    await userEvent.click(screen.getByTestId('help-ai-submit'))
    const problem = await screen.findByTestId('help-ai-problem')
    expect(problem).toHaveAttribute('data-kind', 'other')
    expect(within(problem).getByTestId('help-ai-problem-escalate')).toBeVisible()
  })

  it('on 409, says the content was updated, reads the conversation again and keeps what was typed', async () => {
    server.threads.push(thread())
    server.messages[T1] = [message('m1', 'QUESTION', { body: 'Q?', jobStatus: 'ANSWERED' }),
      message('m2', 'ANSWER', { body: 'A.' })]
    server.refusals.QB_HELP_ASK = [{ status: 409, body: { title: 'Conflict', detail: 'Changed meanwhile' } }]
    renderChat({ initial: T1 })
    await userEvent.type(await screen.findByTestId('help-ai-text'), 'My follow-up')
    const reads = () => server.asked.filter((p) => p.endsWith('qb.me.help-messages')).length
    const before = reads()
    // Someone (another tab) wrote meanwhile: the read again shows it.
    server.messages[T1].push(message('m3', 'QUESTION', { body: 'From another tab', jobStatus: 'ANSWERED' }))
    await userEvent.click(screen.getByTestId('help-ai-submit'))
    const problem = await screen.findByTestId('help-ai-problem')
    expect(problem).toHaveAttribute('data-kind', 'conflict')
    expect(problem).toHaveTextContent('The content was updated. Please try again.')
    await waitFor(() => expect(reads()).toBeGreaterThan(before))
    expect(await screen.findByText('From another tab')).toBeVisible()
    expect(screen.getByTestId('help-ai-text')).toHaveValue('My follow-up')
    // Trying again sends the same words.
    await userEvent.click(screen.getByTestId('help-ai-submit'))
    await waitFor(() => expect(posted('QB_HELP_ASK').map((b) => b.text)).toEqual(['My follow-up', 'My follow-up']))
  })

  it('on 409 when rating, reads again too', async () => {
    server.threads.push(thread())
    server.messages[T1] = [message('m1', 'QUESTION', { body: 'Q?', jobStatus: 'ANSWERED' }),
      message('m2', 'ANSWER', { body: 'A.' })]
    server.refusals.QB_HELP_RATE = [{ status: 409, body: { detail: 'Changed' } }]
    renderChat({ initial: T1 })
    await userEvent.click(await screen.findByTestId('help-ai-solved'))
    expect(await screen.findByTestId('help-ai-problem')).toHaveAttribute('data-kind', 'conflict')
  })

  it('says why a question failed: a quiz is not answered; and offers a person', async () => {
    server.threads.push(thread())
    server.messages[T1] = [message('m1', 'QUESTION', { body: 'Which option is right?', jobStatus: 'FAILED',
      failureCode: 'QB_HELP_QUIZ_REFUSED' })]
    renderChat({ initial: T1 })
    expect(await screen.findByTestId('help-ai-failure')).toHaveTextContent('does not help with quiz questions')
    expect(screen.getByTestId('help-ai-escalate')).toBeEnabled()
  })

  it('ends a conversation after asking, and starts a new one', async () => {
    server.threads.push(thread())
    server.messages[T1] = [message('m1', 'QUESTION', { body: 'Q?', jobStatus: 'ANSWERED' })]
    renderChat({ initial: T1 })
    await userEvent.click(await screen.findByTestId('help-ai-close'))
    await userEvent.click(await screen.findByRole('button', { name: 'End it' }))
    expect(posted('QB_HELP_CLOSE')).toEqual([{ threadId: T1 }])
    expect(await screen.findByTestId('help-ai-closed')).toBeVisible()
    await userEvent.click(screen.getByTestId('help-ai-new'))
    expect(await screen.findByTestId('help-ai-form')).toBeVisible()
    expect(screen.getByTestId('help-ai')).toHaveAttribute('data-thread', '')
  })

  it('lists the Taker\'s own conversations and opens one; what it held is not read out again', async () => {
    server.threads.push(thread({ threadId: 'old', status: 'CLOSED', firstQuestion: 'An old question' }),
      thread({ threadId: T1, status: 'ESCALATED', firstQuestion: 'Handed over' }))
    server.messages.old = [message('o1', 'QUESTION', { body: 'An old question', jobStatus: 'ANSWERED' }),
      message('o2', 'ANSWER', { body: 'An old answer.' })]
    renderChat()
    await userEvent.click(await screen.findByTestId('help-ai-history'))
    const rows = await screen.findAllByTestId('help-ai-thread')
    expect(rows.map((r) => r.textContent)).toEqual([expect.stringContaining('Ended'),
      expect.stringContaining('With a person')])
    await expectAccessible()
    await userEvent.click(rows[0])
    expect(await screen.findByText('An old answer.')).toBeVisible()
    expect(screen.getByTestId('help-ai-closed')).toBeVisible()
    expect(screen.getByTestId('help-ai-live')).toHaveTextContent('')
  })

  it('reads out a reply that comes while the conversation is open', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    server.threads.push(thread())
    server.messages[T1] = [message('m1', 'QUESTION', { body: 'Q?', jobStatus: 'QUEUED' })]
    renderChat({ initial: T1 })
    await screen.findByTestId('help-ai-waiting')
    server.messages[T1][0].jobStatus = 'FAILED'
    await act(async () => { await vi.advanceTimersByTimeAsync(1_500) })
    await waitFor(() => expect(screen.getByTestId('help-ai-live')).toHaveTextContent('could not answer'))
  })
})

describe('the help\'s rules of display', () => {
  it('keeps only the site\'s own paths, within the app or elsewhere on the site', () => {
    expect(siteLinks('/taker/wallet\n/sponsor/help\nhttps://x.example\n//x.example\n/\\x\njavascript:alert(1)\n'
      + '/taker/wallet\n/taker', '/taker/')).toEqual([
      { href: '/taker/wallet', inApp: '/wallet' },
      { href: '/sponsor/help', inApp: null },
      { href: '/taker', inApp: '/' },
    ])
    expect(siteLinks(null, '/')).toEqual([])
  })

  it('shows Markdown\'s links and emphasis as words', () => {
    expect(plainAnswer('See [the help](/help) and **this**.\n## Next')).toBe('See the help and this.\nNext')
    expect(plainAnswer(null)).toBe('')
  })

  it('tells the problems apart', () => {
    expect(helpProblem(new Error('x'))).toBe('other')
  })

  it('leads an inbox reply to the conversation', () => {
    expect(targetOf({ itemKind: 'MESSAGE', action: 'HELP', actionId: T1, publicationId: null }))
      .toBe(helpThreadPath(T1))
    expect(helpThreadPath('a b')).toBe('/help?thread=a%20b')
  })
})

describe('the help\'s page names', () => {
  it('are the server\'s list (help.HelpPages), in its order', () => {
    const java = readFileSync(resolve(process.cwd(),
      '../../backend/quizbuks/src/main/java/com/jabiz/quizbuks/help/HelpPages.java'), 'utf8')
    const block = /Map<String, String> APP = ordered\(([\s\S]*?)\);/.exec(java)![1]
    const names = [...block.matchAll(/^\s*"([a-z-]+)", "/gm)].map((m) => m[1])
    expect(names).toEqual([...APP_PAGE_NAMES])
  })

  it('name the app\'s pages, and none of the answering pages', () => {
    expect(appPageName('/')).toBe('home')
    expect(appPageName('/', '?shorts=p1')).toBe('shorts')
    expect(appPageName('/p/abc')).toBe('publication')
    expect(appPageName('/play/abc')).toBeNull()
    expect(appPageName('/result/abc')).toBeNull()
    expect(appPageName('/wallet/payout')).toBe('wallet')
    expect(appPageName('/account/profile')).toBe('profile')
    expect(appPageName('/account/security')).toBe('account')
    expect(appPageName('/scan')).toBeNull()
    for (const path of ['/', '/history', '/inbox', '/conversations/x', '/referrals', '/my-sponsor', '/help',
      '/redeem/k', '/account/certificates']) {
      expect(APP_PAGE_NAMES as readonly string[], path).toContain(appPageName(path))
    }
  })

  it('send the page the Taker came from: none from an answering page, the help itself when opened directly', () => {
    expect(askedFrom()).toBe('help')
    notePage('/wallet')
    notePage('/help')
    expect(askedFrom()).toBe('wallet')
    notePage('/play/abc')
    expect(askedFrom()).toBeNull()
  })
})

describe('the help\'s texts', () => {
  it('have the same keys in every language, none empty, and use the product\'s words', () => {
    const english = keyPaths(bundle(QB, 'en').helpAi)
    expect(english.length).toBeGreaterThan(20)
    for (const lng of LANGUAGES) {
      expect(keyPaths(bundle(QB, lng).helpAi), lng).toEqual(english)
      expect(emptyTexts(QB, lng), lng).toEqual([])
    }
    expect(helpMessages.zh.helpAi.conflict).toBe('内容已更新，请重试')
    expect(helpMessages.ja.helpAi.conflict).toBe('内容が更新されました。もう一度お試しください。')
    expect(helpMessages.en.helpAi.conflict).toBe('The content was updated. Please try again.')
    expect(JSON.stringify(helpMessages)).not.toMatch(/后台|管理画面|console/i)
  })
})
