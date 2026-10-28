import { expectAccessible } from '@jabiz/ui/testing'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MESSAGES, textsOf } from './messages'
import './quiz'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const CAMPAIGN = { live: true, publicationId: 'p1', name: 'Spring quiz', video: 'https://www.youtube-nocookie.com/embed/abc',
  questionCount: 2, timeLimitSec: 60, passScore: 2, percent: 15, codesToday: true, badge: false }

const STARTED = {
  attemptId: 'a1', publicationId: 'p1', startedAt: '2026-10-27T00:00:00Z', deadline: '2026-10-27T00:01:00Z',
  serverTime: '2026-10-27T00:00:00Z', timeLimitSec: 60, currentIndex: 0, resumed: false, answers: [],
  questions: [
    { no: 1, stem: 'First?', points: 1, options: [{ no: 1, text: 'Yes' }, { no: 2, text: 'No' }] },
    { no: 2, stem: 'Second?', points: 1, options: [{ no: 1, text: 'Red' }, { no: 2, text: 'Blue' }] },
  ],
}

const PASSED = { attemptId: 'a1', score: 2, fullScore: 2, passScore: 2, passed: true, outcome: 'ISSUED',
  discountCode: 'QB-ABCDEFGHJK', percent: 15, expiresAt: '2026-11-10T00:00:00Z' }

type Answer = { status: number; body: unknown }

/** The app proxy as the block sees it, by process; records what was sent. */
function proxy(answers: Record<string, Answer | Answer[]>) {
  const sent: { process: string; query: string; body: Record<string, unknown> | null }[] = []
  const calls: Record<string, number> = {}
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), window.location.origin)
    const process = url.pathname.split('/').pop()!
    sent.push({ process, query: url.search, body: init?.body ? JSON.parse(String(init.body)) : null })
    const listed = answers[process]
    const n = calls[process] = (calls[process] ?? 0) + 1
    const answer = Array.isArray(listed) ? listed[Math.min(n - 1, listed.length - 1)] : listed
    return answer ? json(answer.status, answer.body) : json(404, {})
  })
  return sent
}

async function mount(attributes: Record<string, string> = {}) {
  const element = document.createElement('quizbuks-quiz')
  element.setAttribute('proxy', '/apps/quizbuks')
  for (const [name, value] of Object.entries(attributes)) element.setAttribute(name, value)
  document.body.append(element)
  const root = element.shadowRoot!
  await vi.waitFor(() => expect(root.querySelector('.open') ?? root.childElementCount === 0).toBeTruthy())
  return { element, root }
}

function button(root: ShadowRoot, name: string): HTMLButtonElement {
  const found = [...root.querySelectorAll('button')].find((b) => b.textContent === name || b.ariaLabel === name)
  if (!found) throw new Error(`No button ${name}: ${[...root.querySelectorAll('button')].map((b) => b.textContent)}`)
  return found
}

function choose(root: ShadowRoot, text: string) {
  const label = [...root.querySelectorAll('label')].find((l) => l.textContent === text)!
  const input = label.querySelector('input')!
  input.checked = true
  input.dispatchEvent(new Event('change'))
}

describe('the storefront quiz block (S2b)', () => {
  beforeEach(() => {
    window.localStorage.clear()
    document.body.replaceChildren()
    vi.useFakeTimers({ shouldAdvanceTime: true, now: new Date('2026-10-27T00:00:00Z') })
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('shows nothing without a live campaign, or with no codes left today', async () => {
    proxy({ QBS_SF_CAMPAIGN: { status: 200, body: { ...CAMPAIGN, live: false } } })
    const none = await mount()
    await vi.waitFor(() => expect(globalThis.fetch).toHaveBeenCalled())
    expect(none.root.querySelector('.open')).toBeNull()
    vi.restoreAllMocks()
    proxy({ QBS_SF_CAMPAIGN: { status: 200, body: { ...CAMPAIGN, codesToday: false } } })
    const soldOut = await mount()
    await vi.waitFor(() => expect(globalThis.fetch).toHaveBeenCalled())
    expect(soldOut.root.querySelector('.open')).toBeNull()
  })

  it('plays the video only when asked, then answers, passes and shows the code', async () => {
    const sent = proxy({ QBS_SF_CAMPAIGN: { status: 200, body: CAMPAIGN }, QBS_SF_START: { status: 200, body: STARTED },
      QBS_SF_SUBMIT: { status: 200, body: PASSED } })
    const { root } = await mount({ root: '/', locale: 'en' })
    await vi.waitFor(() => expect(root.querySelector('.open')).not.toBeNull())
    expect(root.querySelector('.open')!.textContent).toBe('Answer a quiz, get 15% off')
    button(root, 'Answer a quiz, get 15% off').click()
    expect(root.querySelector('h2')!.textContent).toBe('Spring quiz')
    expect(root.querySelector('iframe')).toBeNull()
    await expectAccessible(root.querySelector('dialog')!)
    button(root, 'Watch the video').click()
    expect(root.querySelector('iframe')!.getAttribute('src')).toBe('https://www.youtube-nocookie.com/embed/abc?autoplay=1')

    button(root, 'Start the quiz').click()
    await vi.waitFor(() => expect(root.querySelector('h2')!.textContent).toBe('Question 1 of 2'))
    expect(button(root, 'Next').disabled).toBe(true)
    choose(root, 'Yes')
    button(root, 'Next').click()
    expect(root.querySelector('legend')!.textContent).toBe('Second?')
    choose(root, 'Blue')
    await expectAccessible(root.querySelector('dialog')!)
    vi.advanceTimersByTime(5_000)
    button(root, 'Submit').click()
    await vi.waitFor(() => expect(root.querySelector('.code')?.textContent).toBe('QB-ABCDEFGHJK'))
    expect(root.querySelector('a.primary')!.getAttribute('href'))
      .toBe('/discount/QB-ABCDEFGHJK?redirect=%2F')
    expect(root.querySelector('.open')!.textContent).toBe('Your 15% discount code')

    // The visitor's key in the query and the body; the answers by the attempt's numbers.
    const start = sent.find((s) => s.process === 'QBS_SF_START')!
    const visitor = start.body!.visitor as string
    expect(visitor).toMatch(/^[A-Za-z0-9_-]{16,64}$/)
    expect(start.query).toBe(`?visitor=${visitor}`)
    expect(sent.find((s) => s.process === 'QBS_SF_SUBMIT')!.body).toEqual({ publicationId: 'p1', attemptId: 'a1',
      visitor, answers: [{ questionNo: 1, selected: [1] }, { questionNo: 2, selected: [2] }] })
    await expectAccessible(root.querySelector('dialog')!)
  })

  it('shows the code it got again, even when today\'s codes are gone', async () => {
    window.localStorage.setItem('quizbuks:code:p1:anon', JSON.stringify({ code: 'QB-KEPT00000', percent: 15,
      expiresAt: null }))
    const sent = proxy({ QBS_SF_CAMPAIGN: { status: 200, body: { ...CAMPAIGN, codesToday: false } } })
    const { root } = await mount()
    await vi.waitFor(() => expect(root.querySelector('.open')).not.toBeNull())
    button(root, 'Your 15% discount code').click()
    expect(root.querySelector('.code')!.textContent).toBe('QB-KEPT00000')
    expect(sent.map((s) => s.process)).toEqual(['QBS_SF_CAMPAIGN'])
  })

  it('keeps each signed-in customer\'s code apart', async () => {
    window.localStorage.setItem('quizbuks:code:p1:c7', JSON.stringify({ code: 'QB-SEVEN0000', percent: 15,
      expiresAt: null }))
    proxy({ QBS_SF_CAMPAIGN: { status: 200, body: CAMPAIGN } })
    const { root } = await mount({ customer: '8' })
    await vi.waitFor(() => expect(root.querySelector('.open')).not.toBeNull())
    expect(button(root, 'Answer a quiz, get 15% off')).toBeTruthy()
    document.body.replaceChildren()
    const seven = await mount({ customer: '7' })
    await vi.waitFor(() => expect(seven.root.querySelector('.open')).not.toBeNull())
    button(seven.root, 'Your 15% discount code').click()
    expect(seven.root.querySelector('.code')!.textContent).toBe('QB-SEVEN0000')
  })

  it('says it was answered when the attempt this browser started has ended without a code', async () => {
    window.localStorage.setItem('quizbuks:attempt:p1:anon', 'a1')
    proxy({ QBS_SF_CAMPAIGN: { status: 200, body: CAMPAIGN },
      QBS_SF_START: { status: 422, body: { violations: [{ ruleCode: 'QB_ATTEMPT_ALREADY_TAKEN' }] } },
      QBS_SF_SUBMIT: { status: 422, body: { violations: [{ ruleCode: 'QB_ATTEMPT_FINISHED' }] } } })
    const { root } = await mount()
    await vi.waitFor(() => expect(root.querySelector('.open')).not.toBeNull())
    button(root, 'Answer a quiz, get 15% off').click()
    button(root, 'Start the quiz').click()
    await vi.waitFor(() => expect(root.textContent).toContain(MESSAGES.en.already))
  })

  it('goes back to the question, its buttons as they were, when a submission fails', async () => {
    proxy({ QBS_SF_CAMPAIGN: { status: 200, body: CAMPAIGN }, QBS_SF_START: { status: 200, body: STARTED },
      QBS_SF_SUBMIT: { status: 500, body: {} } })
    const { root } = await mount()
    await vi.waitFor(() => expect(root.querySelector('.open')).not.toBeNull())
    button(root, 'Answer a quiz, get 15% off').click()
    button(root, 'Start the quiz').click()
    await vi.waitFor(() => expect(root.querySelector('legend')).not.toBeNull())
    vi.advanceTimersByTime(61_000)
    await vi.waitFor(() => expect(root.querySelector('.status')!.textContent).toBe(MESSAGES.en.error))
    expect(root.querySelector('legend')!.textContent).toBe('First?')
    expect(button(root, 'Back').disabled).toBe(true)
  })

  it('asks again for the code of an attempt this browser started, when the server says it was answered', async () => {
    window.localStorage.setItem('quizbuks:attempt:p1:anon', 'a1')
    const sent = proxy({ QBS_SF_CAMPAIGN: { status: 200, body: CAMPAIGN },
      QBS_SF_START: { status: 422, body: { violations: [{ ruleCode: 'QB_ATTEMPT_ALREADY_TAKEN' }] } },
      QBS_SF_SUBMIT: { status: 200, body: { ...PASSED, outcome: 'AGAIN', score: null, fullScore: null } } })
    const { root } = await mount()
    await vi.waitFor(() => expect(root.querySelector('.open')).not.toBeNull())
    button(root, 'Answer a quiz, get 15% off').click()
    button(root, 'Start the quiz').click()
    await vi.waitFor(() => expect(root.querySelector('.code')?.textContent).toBe('QB-ABCDEFGHJK'))
    expect(sent.find((s) => s.process === 'QBS_SF_SUBMIT')!.body).not.toHaveProperty('answers')
  })

  it('says why there is no code: not passed, gone for today, answered too fast', async () => {
    proxy({ QBS_SF_CAMPAIGN: { status: 200, body: CAMPAIGN }, QBS_SF_START: { status: 200, body: STARTED },
      QBS_SF_SUBMIT: [
        { status: 422, body: { violations: [{ ruleCode: 'QB_ATTEMPT_TOO_FAST' }] } },
        { status: 200, body: { ...PASSED, outcome: 'DAY_FULL', discountCode: null, percent: null, expiresAt: null } },
      ] })
    const { root } = await mount()
    await vi.waitFor(() => expect(root.querySelector('.open')).not.toBeNull())
    button(root, 'Answer a quiz, get 15% off').click()
    button(root, 'Start the quiz').click()
    await vi.waitFor(() => expect(root.querySelector('legend')).not.toBeNull())
    choose(root, 'Yes')
    button(root, 'Next').click()
    choose(root, 'Red')
    button(root, 'Submit').click()
    await vi.waitFor(() => expect(root.querySelector('.status')!.textContent)
      .toBe('That was quick: read the questions, then submit again.'))
    button(root, 'Submit').click()
    await vi.waitFor(() => expect(root.textContent).toContain('today\'s discount codes have all been handed out'))
    expect(root.querySelector('.code')).toBeNull()
  })

  it('submits what is answered when the time runs out', async () => {
    const sent = proxy({ QBS_SF_CAMPAIGN: { status: 200, body: CAMPAIGN }, QBS_SF_START: { status: 200, body: STARTED },
      QBS_SF_SUBMIT: { status: 200, body: { ...PASSED, passed: false, score: 1, outcome: 'NOT_PASSED',
        discountCode: null, percent: null, expiresAt: null } } })
    const { root } = await mount()
    await vi.waitFor(() => expect(root.querySelector('.open')).not.toBeNull())
    button(root, 'Answer a quiz, get 15% off').click()
    button(root, 'Start the quiz').click()
    await vi.waitFor(() => expect(root.querySelector('legend')).not.toBeNull())
    choose(root, 'Yes')
    vi.advanceTimersByTime(51_000)
    expect(root.querySelector('[data-clock]')!.textContent).toBe('10 seconds left')
    vi.advanceTimersByTime(10_000)
    await vi.waitFor(() => expect(root.textContent).toContain('1 of 2 correct.'))
    expect(root.textContent).toContain('Not this time.')
    expect(sent.find((s) => s.process === 'QBS_SF_SUBMIT')!.body!.answers).toEqual([{ questionNo: 1, selected: [1] }])
  })

  it('says it is QuizBuks\'s at the end on the free plan only', async () => {
    for (const badge of [true, false]) {
      document.body.replaceChildren()
      vi.restoreAllMocks()
      proxy({ QBS_SF_CAMPAIGN: { status: 200, body: { ...CAMPAIGN, badge } }, QBS_SF_START: { status: 200, body: STARTED },
        QBS_SF_SUBMIT: { status: 200, body: { ...PASSED, passed: false, score: 0, outcome: 'NOT_PASSED',
          discountCode: null, percent: null, expiresAt: null } } })
      const { root } = await mount()
      await vi.waitFor(() => expect(root.querySelector('.open')).not.toBeNull())
      button(root, 'Answer a quiz, get 15% off').click()
      expect(root.textContent).not.toContain('Powered by QuizBuks')
      button(root, 'Start the quiz').click()
      await vi.waitFor(() => expect(root.querySelector('legend')).not.toBeNull())
      expect(root.textContent).not.toContain('Powered by QuizBuks')
      vi.advanceTimersByTime(61_000)
      await vi.waitFor(() => expect(root.textContent).toContain('Not this time.'))
      expect(root.querySelector('.badge')?.textContent ?? null).toBe(badge ? 'Powered by QuizBuks' : null)
      if (badge) {
        const link = root.querySelector<HTMLAnchorElement>('.badge a')!
        expect(link.getAttribute('href')).toBe('https://quizbuks.com/shopify-app/')
        expect(link.target).toBe('_blank')
        expect(link.rel).toBe('noopener')
      }
      if (badge) await expectAccessible(root.querySelector('dialog')!)
    }
  })

  it('speaks the storefront\'s language', async () => {
    proxy({ QBS_SF_CAMPAIGN: { status: 200, body: CAMPAIGN } })
    const { root } = await mount({ locale: 'zh-CN' })
    await vi.waitFor(() => expect(root.querySelector('.open')!.textContent).toBe('答题赢 15% 折扣'))
    expect(textsOf('ja').open).toContain('オフ')
    expect(textsOf('fr')).toBe(MESSAGES.en)
  })

  it('has every text in every language', () => {
    const keys = Object.keys(MESSAGES.en).sort()
    for (const [language, texts] of Object.entries(MESSAGES)) {
      expect(Object.keys(texts).sort(), language).toEqual(keys)
      for (const [key, text] of Object.entries(texts)) {
        expect(text.trim(), `${language}.${key}`).not.toBe('')
        // The same placeholders as English.
        expect(text.match(/\{\w+\}/g)?.sort() ?? [], `${language}.${key}`)
          .toEqual((MESSAGES.en as Record<string, string>)[key].match(/\{\w+\}/g)?.sort() ?? [])
      }
    }
  })
})
