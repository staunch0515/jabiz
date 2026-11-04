import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Material, Quiz } from '../content/api'
import { EditingProvider, type Editing } from '../content/editing'
import '../messages'
import { resetServerClock } from '../qb/legal'
import { AiCoverPanel } from './AiCoverPanel'
import { AiQuestionsPanel } from './AiQuestionsPanel'
import { AiUsageMetric } from './AiUsageMetric'
import { materialBytes, parseCandidates, parseCoverParams, parseCovers, stillPolling, toAiJob } from './api'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const QUIZ: Quiz = {
  quizId: 'q1', kind: 'QUIZ', language: 'en', title: 'Paris', intro: 'About Paris', cover: null, timeLimitSec: null, status: 'DRAFT',
  latestVersionNo: 0, versionLabel: null, revision: 7, editedAt: '2026-10-18T00:00:00Z', changedSinceVersion: false,
  questionCount: 0, materialCount: 2, clonedFrom: null, translationLanguages: [],
}

const material = (id: string, kind: Material['kind'], body: string | null): Material => ({
  materialId: id, kind, title: `Material ${id}`, description: 'About it', body, url: null, pdf: null, audio: null,
  images: [], watchToAnswer: false, durationSec: null, variantOf: null, language: null,
})

const MATERIALS = [material('m1', 'ARTICLE', 'Paris is the capital of France.'), material('m2', 'VIDEO_LINK', null)]

const USAGE = { month: '2026-10', enabled: true, jobs: 3, failedJobs: 1, inputTokens: 12000, outputTokens: 3400,
  monthlyTokens: 2000000, reservedTokens: 0, remainingTokens: 1984600, dailyJobs: 20, jobsToday: 2,
  monthEnds: '2026-10-31T15:00:00Z', dayEnds: '2026-10-18T15:00:00Z' }

const CANDIDATES = JSON.stringify({ schema: 1, questions: [
  { no: 1, stem: 'What is the capital of France?', points: 10, materials: [1],
    options: [{ text: 'Paris', correct: true }, { text: 'Lyon', correct: false }] },
  { no: 2, stem: '<b>Bold</b> question?', points: 5, materials: [],
    options: [{ text: 'Yes', correct: true }, { text: 'No', correct: false }] },
] })

const job = (status: string, extra: Record<string, unknown> = {}) => ({
  aiJobId: 'j1', externalJobId: 'x1', quizId: 'q1', status, params: '{"count":5}', candidates: null,
  candidateCount: null, invalidCount: null, acceptedCount: null, failureCode: null,
  requestedAt: new Date().toISOString(), finishedAt: null, expiresAt: null, ...extra,
})

interface Server {
  usage?: Record<string, unknown>
  /** The usage rows in turn (the last repeats), in place of `usage`. */
  usages?: Record<string, unknown>[]
  jobs?: Record<string, unknown>[][]
  /** The cover jobs (Q8b), read with `kind: COVER`. */
  coverJobs?: Record<string, unknown>[][]
  mustAccept?: boolean
}

/** The server as the AI panel sees it; records what was asked. */
function server(options: Server = {}) {
  const asked: { path: string; body: Record<string, unknown> }[] = []
  let jobCalls = 0
  let coverCalls = 0
  let usageCalls = 0
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    asked.push({ path, body })
    const page = (items: unknown[]) => json(200, { items, offset: 0, limit: items.length })
    switch (path) {
      case '/api/queries/qb.sponsor.ai-usage':
        return page([options.usages ? options.usages[Math.min(usageCalls++, options.usages.length - 1)]
          : options.usage ?? USAGE])
      case '/api/queries/qb.sponsor.ai-jobs': {
        if ((body as { params?: { kind?: string } } | null)?.params?.kind === 'COVER') {
          const covers = options.coverJobs ?? [[]]
          return page(covers[Math.min(coverCalls++, covers.length - 1)])
        }
        const pages = options.jobs ?? [[]]
        return page(pages[Math.min(jobCalls++, pages.length - 1)])
      }
      case '/api/processes/QB_AI_COVER_REQUEST/latest':
        return json(200, { output: { aiJobId: 'c1', externalJobId: 'x2', status: 'QUEUED', estimatedTokens: 9000 } })
      case '/api/processes/QB_AI_COVER_ACCEPT/latest':
        return json(200, { output: { quizId: 'q1', revision: 8, editedAt: '2026-10-18T00:00:00Z', itemId: null,
          partIds: [] } })
      case '/api/queries/qb.me.consents':
        return page([{ kind: 'AI_NOTICE', versionId: 'v', versionNo: 2, title: { en: 'AI notice' },
          changeSummary: null, requiresConsent: true, effectiveAt: '2026-10-01T00:00:00Z', toAccept: 2,
          accepted: options.mustAccept === false ? 2 : null, mustAccept: options.mustAccept ?? true }])
      case '/api/public/queries/qb.public.legal':
        return page([{ kind: 'AI_NOTICE', versionNo: 2, title: { en: 'AI notice' },
          body: { en: '## What is sent\n\nYour quiz content.' }, changeSummary: null, requiresConsent: true,
          effectiveAt: '2026-10-01T00:00:00Z', upcomingVersionNo: null, upcomingEffectiveAt: null }])
      case '/api/processes/QB_AI_QUESTIONS_REQUEST/latest':
        return json(200, { output: { aiJobId: 'j1', externalJobId: 'x1', status: 'QUEUED', estimatedTokens: 18000 } })
      case '/api/processes/QB_AI_ACCEPT/latest':
        return json(200, { output: { quizId: 'q1', revision: 8, aiJobId: 'j1', questionIds: ['n1'] } })
      case '/api/processes/QB_AI_DISCARD/latest':
      case '/api/processes/QB_AI_CANCEL/latest':
        return json(200, { output: { aiJobId: 'j1', status: 'DISCARDED' } })
      default:
        return json(404, {})
    }
  })
  return {
    of: (p: string) => asked.filter((a) => a.path === p).map((a) => a.body),
    jobCalls: () => jobCalls,
  }
}

function editing(overrides: Partial<Editing> = {}): Editing {
  return { quizId: 'q1', revision: () => 7, edited: vi.fn(async () => {}), failed: vi.fn(), stale: false, ...overrides }
}

function wrap(node: ReactNode, edit: Editing = editing()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><EditingProvider value={edit}>{node}</EditingProvider>
  </QueryClientProvider>)
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
  resetServerClock()
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('the AI questions panel', () => {
  it('asks with the AI notice accepted, the chosen materials and the request', async () => {
    const calls = server({ jobs: [[], [job('QUEUED')]] })
    const user = userEvent.setup()
    wrap(<AiQuestionsPanel quiz={QUIZ} materials={MATERIALS} />)
    expect(await screen.findByTestId('ai-remaining')).toHaveTextContent('1,984,600')
    // Q78: nothing going on: one line with the button, no card.
    expect(screen.getByTestId('ai-panel')).toHaveAttribute('data-collapsed', 'true')
    expect(screen.queryByText('AI questions')).toBeNull()
    await user.click(screen.getByTestId('ai-generate'))
    const dialog = await screen.findByTestId('ai-dialog')
    // A non-article is sent by its title and description only.
    const items = within(dialog).getAllByTestId('ai-material')
    expect(items).toHaveLength(2)
    expect(items[1]).toHaveTextContent('Only its title and description are sent')
    expect(within(dialog).getByTestId('ai-sent')).toHaveTextContent('Never your account details')
    const notice = await within(dialog).findByTestId('ai-notice')
    expect(notice).toHaveTextContent('What is sent')
    // Not without the notice agreed.
    await user.click(within(dialog).getByTestId('ai-submit'))
    expect(await within(dialog).findByTestId('ai-local-problem')).toHaveTextContent('Agree to the AI notice')
    expect(calls.of('/api/processes/QB_AI_QUESTIONS_REQUEST/latest')).toEqual([])
    await user.click(within(dialog).getByTestId('ai-notice-agree'))
    // The article is left out: only the video's title and description go.
    await user.click(within(dialog).getAllByTestId('ai-material-choose')[0])
    await user.click(within(dialog).getByTestId('ai-submit'))
    await waitFor(() => expect(calls.of('/api/processes/QB_AI_QUESTIONS_REQUEST/latest')).toHaveLength(1))
    expect(calls.of('/api/processes/QB_AI_QUESTIONS_REQUEST/latest')[0]).toEqual({ quizId: 'q1',
      materialIds: ['m2'], count: 5, difficulty: 'MEDIUM', language: 'en', avoidExisting: true, aiNoticeVersionNo: 2 })
    expect(await screen.findByTestId('ai-running')).toHaveTextContent('writing 5 questions')
    expect(screen.getByTestId('ai-panel')).not.toHaveAttribute('data-collapsed')
  })

  it('does not ask for more than one request can hold', async () => {
    server({ mustAccept: false })
    const user = userEvent.setup()
    // Five long articles: about 300 KB, more than the 250 KB a request holds (Q8b).
    wrap(<AiQuestionsPanel quiz={QUIZ} materials={['m1', 'm2', 'm3', 'm4', 'm5'].map((id) =>
      material(id, 'ARTICLE', 'あ'.repeat(20_000)))} />)
    await user.click(await screen.findByTestId('ai-generate'))
    const dialog = await screen.findByTestId('ai-dialog')
    expect(within(dialog).getByTestId('ai-size')).toHaveAttribute('data-too-large', 'true')
    expect(within(dialog).getByTestId('ai-submit')).toBeDisabled()
    // Four of them fit.
    await user.click(within(dialog).getAllByTestId('ai-material-choose')[4])
    expect(within(dialog).getByTestId('ai-size')).not.toHaveAttribute('data-too-large')
  })

  it('sends a PDF material as its file, not counted in the request\'s text', async () => {
    const calls = server({ mustAccept: false })
    const user = userEvent.setup()
    const pdf = { ...material('m3', 'PDF', null), pdf: 'f-pdf' }
    wrap(<AiQuestionsPanel quiz={QUIZ} materials={[...MATERIALS, pdf]} />)
    await user.click(await screen.findByTestId('ai-generate'))
    const dialog = await screen.findByTestId('ai-dialog')
    const items = within(dialog).getAllByTestId('ai-material')
    expect(items[2]).toHaveTextContent('The PDF itself is sent (at most 10 MB; all PDFs together at most 100 pages)')
    expect(items[1]).toHaveTextContent('Only its title and description are sent')
    expect(materialBytes(pdf)).toBeLessThan(200)
    await user.click(within(dialog).getByTestId('ai-submit'))
    await waitFor(() => expect(calls.of('/api/processes/QB_AI_QUESTIONS_REQUEST/latest')).toHaveLength(1))
    expect(calls.of('/api/processes/QB_AI_QUESTIONS_REQUEST/latest')[0]).toMatchObject({
      materialIds: ['m1', 'm2', 'm3'] })
  })

  it('asks for no more questions than the effort in force allows', async () => {
    const calls = server({ mustAccept: false, usage: { ...USAGE, maxCount: 10 } })
    const user = userEvent.setup()
    wrap(<AiQuestionsPanel quiz={QUIZ} materials={MATERIALS} />)
    await user.click(await screen.findByTestId('ai-generate'))
    const dialog = await screen.findByTestId('ai-dialog')
    const count = within(dialog).getByTestId('ai-count')
    expect(count).toHaveAttribute('max', '10')
    await user.clear(count)
    await user.type(count, '11')
    await user.click(within(dialog).getByTestId('ai-submit'))
    expect(await within(dialog).findByTestId('ai-local-problem')).toHaveTextContent('10')
    expect(calls.of('/api/processes/QB_AI_QUESTIONS_REQUEST/latest')).toEqual([])
  })

  it('looks at a running job every five seconds, for five minutes at most', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const calls = server({ jobs: [[job('QUEUED', { requestedAt: new Date().toISOString() })]] })
    wrap(<AiQuestionsPanel quiz={QUIZ} materials={MATERIALS} />)
    await screen.findByTestId('ai-running')
    const first = calls.jobCalls()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_100)
    })
    await waitFor(() => expect(calls.jobCalls()).toBeGreaterThan(first))
    expect(stillPolling(toAiJob(job('QUEUED', { requestedAt: new Date(Date.now() - 6 * 60_000).toISOString() }))))
      .toBe(false)
    expect(stillPolling(toAiJob(job('READY')))).toBe(false)
  })

  it('adds only the reviewed candidates, as the sponsor left them', async () => {
    const calls = server({ jobs: [[job('READY', { candidates: CANDIDATES, candidateCount: 2, invalidCount: 1,
      expiresAt: '2026-10-25T00:00:00Z' })]] })
    const edit = editing()
    const user = userEvent.setup()
    wrap(<AiQuestionsPanel quiz={QUIZ} materials={MATERIALS} />, edit)
    const review = await screen.findByTestId('ai-review')
    const cards = within(review).getAllByTestId('ai-candidate')
    expect(cards).toHaveLength(2)
    expect(within(review).getByTestId('ai-invalid')).toHaveTextContent('1 of the AI')
    expect(cards[0]).toHaveTextContent('Based on: material 1')
    // Plain text: the markup of a candidate is shown, never rendered.
    expect(within(cards[1]).getByTestId('ai-candidate-stem')).toHaveValue('<b>Bold</b> question?')
    expect(review.querySelector('b')).toBeNull()
    expect(within(review).getByTestId('ai-accept')).toBeDisabled()
    await expectAccessible()
    const stem = within(cards[0]).getByTestId('ai-candidate-stem')
    await user.clear(stem)
    await user.type(stem, 'Which city is the capital of France?')
    await user.click(within(cards[0]).getByTestId('ai-candidate-reviewed'))
    expect(within(review).getByTestId('ai-accept')).toHaveTextContent('Add 1 reviewed questions')
    await user.click(within(review).getByTestId('ai-accept'))
    await waitFor(() => expect(calls.of('/api/processes/QB_AI_ACCEPT/latest')).toHaveLength(1))
    expect(calls.of('/api/processes/QB_AI_ACCEPT/latest')[0]).toEqual({ aiJobId: 'j1', quizId: 'q1',
      baseRevision: 7, questions: [{ candidateNo: 1, stem: 'Which city is the capital of France?', points: 10,
        options: [{ text: 'Paris', correct: true }, { text: 'Lyon', correct: false }], reviewed: true }] })
    await waitFor(() => expect(edit.edited).toHaveBeenCalledWith(expect.objectContaining({ revision: 8 })))
  })

  it('discards the candidates after asking', async () => {
    const calls = server({ jobs: [[job('READY', { candidates: CANDIDATES, candidateCount: 2 })]] })
    const user = userEvent.setup()
    wrap(<AiQuestionsPanel quiz={QUIZ} materials={MATERIALS} />)
    await user.click(await screen.findByTestId('ai-discard'))
    await user.click(await screen.findByRole('button', { name: 'Discard all' }))
    await waitFor(() => expect(calls.of('/api/processes/QB_AI_DISCARD/latest')).toEqual([{ aiJobId: 'j1' }]))
  })

  it('says why a job failed or expired, and when AI is off', async () => {
    server({ jobs: [[job('FAILED', { failureCode: 'QB_AI_REFUSED' })]] })
    const { unmount } = wrap(<AiQuestionsPanel quiz={QUIZ} materials={MATERIALS} />)
    expect(await screen.findByTestId('ai-failed')).toHaveTextContent('declined')
    unmount()
    vi.restoreAllMocks()
    server({ jobs: [[job('FAILED', { failureCode: 'QB_AI_PROVIDER_UNAVAILABLE' })]] })
    const second = wrap(<AiQuestionsPanel quiz={QUIZ} materials={MATERIALS} />)
    expect(await screen.findByTestId('ai-failed')).toHaveTextContent('temporarily unavailable')
    second.unmount()
    vi.restoreAllMocks()
    server({ jobs: [[job('EXPIRED')]], usage: { ...USAGE, enabled: false } })
    wrap(<AiQuestionsPanel quiz={QUIZ} materials={MATERIALS} />)
    expect(await screen.findByTestId('ai-expired')).toHaveTextContent('not reviewed in time')
    expect(await screen.findByTestId('ai-off')).toBeInTheDocument()
    expect(screen.queryByTestId('ai-generate')).toBeNull()
    await expectAccessible()
  })

  it('says that a queued job waits while AI is paused, and can still be cancelled', async () => {
    server({ jobs: [[job('QUEUED', { requestedAt: new Date().toISOString() })]], usage: { ...USAGE, enabled: false } })
    wrap(<AiQuestionsPanel quiz={QUIZ} materials={MATERIALS} />)
    expect(await screen.findByTestId('ai-running')).toHaveTextContent('AI is paused')
    expect(screen.getByTestId('ai-cancel')).toBeEnabled()
    await expectAccessible()
  })

  it('looks at whether AI is paused again while the job waits', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    server({ jobs: [[job('QUEUED', { requestedAt: new Date().toISOString() })]],
      usages: [{ ...USAGE, enabled: false }, USAGE] })
    wrap(<AiQuestionsPanel quiz={QUIZ} materials={MATERIALS} />)
    expect(await screen.findByTestId('ai-running')).toHaveTextContent('AI is paused')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_100)
    })
    await waitFor(() => expect(screen.getByTestId('ai-running')).toHaveTextContent('writing'))
  })

  it('reads the stored candidates defensively', () => {
    expect(parseCandidates(CANDIDATES)).toHaveLength(2)
    expect(parseCandidates('not json')).toEqual([])
    expect(parseCandidates(null)).toEqual([])
    expect(parseCandidates('{"questions":[{"stem":"x","options":[{"text":"a","correct":"yes"}]}]}')[0])
      .toEqual({ no: 1, stem: 'x', points: 10, materials: [], options: [{ text: 'a', correct: false }] })
  })
})

const COVERS = JSON.stringify({ schema: 2, style: 'ILLUSTRATION', covers: [
  { no: 1, style: 'ILLUSTRATION', text: 'Rivers <b>1</b>', placement: 'bottom', fileId: 'f1' },
] })
const OLD_COVERS = JSON.stringify({ schema: 1, covers: [
  { no: 1, palette: 'ocean', layout: 'center', motif: 'circles', text: 'Rivers 1', fileId: 'f1' },
  { no: 2, palette: 'sunset', layout: 'band', motif: 'waves', text: 'Rivers 2', fileId: 'f2' },
] })

const coverJob = (status: string, extra: Record<string, unknown> = {}) => job(status, { aiJobId: 'c1',
  kind: 'COVER', params: JSON.stringify({ count: 1, language: 'ja', style: 'ILLUSTRATION',
    requirements: 'A river at dawn', titleOverlay: true, titleText: null }), ...extra })

describe('the AI cover panel', () => {
  it('asks for an illustration with requirements, the AI notice accepted', async () => {
    const calls = server({ coverJobs: [[], [coverJob('QUEUED')]] })
    const user = userEvent.setup()
    wrap(<AiCoverPanel quiz={QUIZ} />)
    await user.click(await screen.findByTestId('ai-cover-generate'))
    const dialog = await screen.findByTestId('ai-cover-dialog')
    expect(within(dialog).getByTestId('ai-cover-sent')).toHaveTextContent('what you write here')
    expect(within(dialog).getByTestId('ai-cover-style-ILLUSTRATION')).toBeChecked()
    expect(within(dialog).queryByTestId('ai-cover-replacing')).toBeNull()
    await within(dialog).findByTestId('ai-notice')
    await expectAccessible()
    await user.click(within(dialog).getByTestId('ai-cover-submit'))
    expect(await within(dialog).findByTestId('ai-cover-local-problem')).toHaveTextContent('Agree to the AI notice')
    expect(calls.of('/api/processes/QB_AI_COVER_REQUEST/latest')).toEqual([])
    await user.type(within(dialog).getByTestId('ai-cover-requirements'), 'Blue tones, a river')
    expect(dialog).toHaveTextContent('19 / 500')
    await user.type(within(dialog).getByTestId('ai-cover-text'), 'Rivers!')
    await user.click(within(dialog).getByTestId('ai-notice-agree'))
    await user.click(within(dialog).getByTestId('ai-cover-submit'))
    await waitFor(() => expect(calls.of('/api/processes/QB_AI_COVER_REQUEST/latest')).toEqual([
      { quizId: 'q1', language: 'en', aiNoticeVersionNo: 2, style: 'ILLUSTRATION', requirements: 'Blue tones, a river',
        titleOverlay: true, titleText: 'Rivers!' }]))
    expect(await screen.findByTestId('ai-cover-running')).toHaveTextContent('making the cover')
    // The cover jobs are read apart from the questions'.
    expect(calls.of('/api/queries/qb.sponsor.ai-jobs')).toEqual(expect.arrayContaining([
      expect.objectContaining({ params: { quizId: 'q1', kind: 'COVER' } })]))
  })

  it('asks for a simple graphic, or an illustration without text', async () => {
    const calls = server({ coverJobs: [[], [coverJob('QUEUED')]], mustAccept: false })
    const user = userEvent.setup()
    wrap(<AiCoverPanel quiz={QUIZ} />)
    await user.click(await screen.findByTestId('ai-cover-generate'))
    const dialog = await screen.findByTestId('ai-cover-dialog')
    // No title: no text and no language to ask for.
    await user.click(within(dialog).getByTestId('ai-cover-overlay'))
    expect(within(dialog).queryByTestId('ai-cover-text')).toBeNull()
    // A simple graphic always shows its text.
    await user.click(within(dialog).getByTestId('ai-cover-style-GRAPHIC'))
    expect(within(dialog).queryByTestId('ai-cover-overlay')).toBeNull()
    expect(within(dialog).getByTestId('ai-cover-text')).toBeInTheDocument()
    await user.click(within(dialog).getByTestId('ai-cover-submit'))
    await waitFor(() => expect(calls.of('/api/processes/QB_AI_COVER_REQUEST/latest')).toEqual([
      { quizId: 'q1', language: 'en', aiNoticeVersionNo: null, style: 'GRAPHIC', requirements: null,
        titleOverlay: true, titleText: null }]))
  })

  it('uses the cover as a quiz edit', async () => {
    const calls = server({ coverJobs: [[coverJob('READY', { candidates: COVERS, candidateCount: 1,
      expiresAt: '2026-10-25T00:00:00Z' })]] })
    const edit = editing()
    const applied = vi.fn()
    const user = userEvent.setup()
    wrap(<AiCoverPanel quiz={QUIZ} onApplied={applied} />, edit)
    const choice = await screen.findByTestId('ai-cover-choice')
    expect(within(choice).getAllByTestId('ai-cover-candidate')).toHaveLength(1)
    // The text in the picture's description, as plain text; what was asked for.
    expect(within(choice).getByTestId('ai-cover-shown-text')).toHaveTextContent('Text on the cover: Rivers <b>1</b>')
    expect(choice.querySelector('b')).toBeNull()
    expect(within(choice).getByTestId('ai-cover-asked')).toHaveTextContent('A river at dawn')
    await expectAccessible()
    await user.click(within(choice).getByTestId('ai-cover-apply'))
    await waitFor(() => expect(calls.of('/api/processes/QB_AI_COVER_ACCEPT/latest')).toEqual([
      { aiJobId: 'c1', quizId: 'q1', candidateNo: 1, baseRevision: 7 }]))
    await waitFor(() => expect(edit.edited).toHaveBeenCalledWith(expect.objectContaining({ revision: 8 })))
    await waitFor(() => expect(applied).toHaveBeenCalled())
  })

  it('asks again from the cover\'s own requirements, which the new one replaces', async () => {
    const calls = server({ coverJobs: [[coverJob('READY', { candidates: COVERS, candidateCount: 1 })],
      [coverJob('QUEUED')]], mustAccept: false })
    const user = userEvent.setup()
    wrap(<AiCoverPanel quiz={QUIZ} />)
    await user.click(await screen.findByTestId('ai-cover-again'))
    const dialog = await screen.findByTestId('ai-cover-dialog')
    expect(within(dialog).getByTestId('ai-cover-requirements')).toHaveValue('A river at dawn')
    expect(within(dialog).getByTestId('ai-cover-replacing')).toHaveTextContent('replaced by the new one')
    await expectAccessible()
    await user.clear(within(dialog).getByTestId('ai-cover-requirements'))
    await user.type(within(dialog).getByTestId('ai-cover-requirements'), 'Warmer colours')
    await user.click(within(dialog).getByTestId('ai-cover-submit'))
    await waitFor(() => expect(calls.of('/api/processes/QB_AI_COVER_REQUEST/latest')).toEqual([
      { quizId: 'q1', language: 'ja', aiNoticeVersionNo: null, style: 'ILLUSTRATION', requirements: 'Warmer colours',
        titleOverlay: true, titleText: null }]))
  })

  it('says that a queued cover job waits while AI is paused', async () => {
    server({ coverJobs: [[coverJob('QUEUED', { requestedAt: new Date().toISOString() })]],
      usage: { ...USAGE, enabled: false } })
    wrap(<AiCoverPanel quiz={QUIZ} />)
    expect(await screen.findByTestId('ai-cover-running')).toHaveTextContent('AI is paused')
    expect(screen.getByTestId('ai-cover-cancel')).toBeEnabled()
    await expectAccessible()
  })

  it('says why a cover job failed or expired, and discards', async () => {
    server({ coverJobs: [[coverJob('FAILED', { failureCode: 'QB_AI_NO_CANDIDATES' })]] })
    const first = wrap(<AiCoverPanel quiz={QUIZ} />)
    expect(await screen.findByTestId('ai-cover-failed')).toHaveTextContent('could not be drawn')
    first.unmount()
    vi.restoreAllMocks()
    server({ coverJobs: [[coverJob('FAILED', { failureCode: 'QB_AI_COVER_FONT' })]] })
    const second = wrap(<AiCoverPanel quiz={QUIZ} />)
    expect(await screen.findByTestId('ai-cover-failed')).toHaveTextContent('cannot show')
    second.unmount()
    vi.restoreAllMocks()
    server({ coverJobs: [[coverJob('EXPIRED')]] })
    const third = wrap(<AiCoverPanel quiz={QUIZ} />)
    expect(await screen.findByTestId('ai-cover-expired')).toHaveTextContent('not used in time')
    third.unmount()
    vi.restoreAllMocks()
    const calls = server({ coverJobs: [[coverJob('READY', { candidates: COVERS, candidateCount: 1 })]] })
    const user = userEvent.setup()
    wrap(<AiCoverPanel quiz={QUIZ} />)
    await user.click(await screen.findByTestId('ai-cover-discard'))
    await user.click(await screen.findByRole('button', { name: 'Discard' }))
    await waitFor(() => expect(calls.of('/api/processes/QB_AI_DISCARD/latest')).toEqual([{ aiJobId: 'c1' }]))
  })

  it('reads the stored covers and what they were asked with defensively', () => {
    expect(parseCovers(COVERS)).toEqual([{ no: 1, style: 'ILLUSTRATION', text: 'Rivers <b>1</b>', fileId: 'f1' }])
    // A job of before Q96: catalogue designs.
    expect(parseCovers(OLD_COVERS)).toHaveLength(2)
    expect(parseCovers(OLD_COVERS)[0]).toMatchObject({ style: 'GRAPHIC', text: 'Rivers 1' })
    expect(parseCovers('{"covers":[{"no":1,"text":"no file"}]}')).toEqual([])
    expect(parseCovers('not json')).toEqual([])
    expect(toAiJob(coverJob('READY', { candidates: COVERS }))).toMatchObject({ kind: 'COVER', candidates: [],
      coverParams: { style: 'ILLUSTRATION', requirements: 'A river at dawn', titleOverlay: true, titleText: '',
        language: 'ja' } })
    expect(parseCoverParams({ count: 3, language: 'en' })).toEqual({ style: 'GRAPHIC', requirements: '',
      titleOverlay: true, titleText: '', language: 'en' })
    expect(toAiJob(job('READY', { candidates: CANDIDATES })).covers).toEqual([])
    expect(toAiJob(job('READY', { candidates: CANDIDATES })).coverParams).toBeNull()
  })
})

describe('the dashboard\'s AI usage', () => {
  it('shows the month\'s tokens, requests and what is left', async () => {
    server()
    wrap(<AiUsageMetric zone="Asia/Tokyo" />)
    const metric = await screen.findByTestId('metric-ai')
    expect(metric).toHaveTextContent('15,400 tokens')
    expect(screen.getByTestId('metric-ai-detail')).toHaveTextContent('12,000 in, 3,400 out · 3 requests')
    expect(metric).toHaveTextContent('1,984,600 left until')
    await expectAccessible()
  })

  it('says when AI is off', async () => {
    server({ usage: { ...USAGE, enabled: false } })
    wrap(<AiUsageMetric zone="Asia/Tokyo" />)
    expect(await screen.findByTestId('metric-ai')).toHaveTextContent('AI questions are off')
  })
})
