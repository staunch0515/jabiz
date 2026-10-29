import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expectAccessible } from '@jabiz/ui/testing'
import { afterEach, describe, expect, it, vi } from 'vitest'
import '../messages'
import TrialPage, { TRIAL_TEMPLATE, trialKeyOf } from './TrialPage'

const KEY = 'ab'.repeat(24)

const trial = {
  title: 'Know your sencha',
  intro: 'Five questions.',
  questions: [1, 2, 3, 4, 5].map((n) => ({ stem: `Question ${n}?`, options: [
    { text: `Right ${n}`, correct: true }, { text: `Wrong ${n}a`, correct: false },
    { text: `Wrong ${n}b`, correct: false }, { text: `Wrong ${n}c`, correct: false }] })),
}

function serve(items: unknown[]) {
  const asked: string[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    asked.push(String(input))
    return new Response(JSON.stringify({ items }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  })
  return asked
}

function renderTrial() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <TrialPage trialKey={KEY} />
    </QueryClientProvider>,
  )
}

afterEach(() => vi.restoreAllMocks())

describe('the trial page', () => {
  it('knows its address by the 48-character key only', () => {
    expect(trialKeyOf(`/try/${KEY}`)).toBe(KEY)
    expect(trialKeyOf('/try/abc')).toBeNull()
    expect(trialKeyOf('/dashboard')).toBeNull()
  })

  it('reads the trial anonymously, checks the answers in the page and invites to sign up with the key', async () => {
    const asked = serve([{ trialKey: KEY, company: 'Green Leaf Tea', language: 'ja', trial: JSON.stringify(trial) }])
    const { container } = renderTrial()
    const questions = await screen.findAllByTestId('trial-question')
    expect(questions).toHaveLength(5)
    expect(asked[0]).toContain(`/api/public/queries/${TRIAL_TEMPLATE}?p.key=${KEY}`)
    expect(screen.getByText(/Green Leaf Tea/)).toBeInTheDocument()
    await expectAccessible(container)
    const user = userEvent.setup()
    expect(screen.getByTestId('trial-check')).toBeDisabled()
    for (const [i, question] of questions.entries()) {
      await user.click(within(question).getByLabelText(i === 0 ? 'Wrong 1a' : `Right ${i + 1}`))
    }
    await user.click(screen.getByTestId('trial-check'))
    expect(screen.getByTestId('trial-score')).toHaveTextContent('4 of 5')
    expect(within(questions[0]).getByTestId('trial-feedback')).toHaveTextContent('Right 1')
    expect(screen.getByTestId('trial-signup')).toHaveAttribute('href', expect.stringContaining(`signup?src=${KEY}`))
  })

  it('says when the trial is gone', async () => {
    serve([])
    renderTrial()
    expect(await screen.findByTestId('trial-missing')).toBeInTheDocument()
  })
})
