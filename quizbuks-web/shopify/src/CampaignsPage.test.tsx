import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { CampaignsPage } from './CampaignsPage'
import type { Campaign, Connection } from './api'
import './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function campaign(overrides: Partial<Campaign> = {}): Campaign {
  return {
    campaignId: 'c1', status: 'DRAFT', name: 'Spring quiz', video: null, timeLimitSec: 120, passScore: 1,
    percent: 15, revision: 3, publicationId: null, createdAt: '2026-10-27T00:00:00Z',
    editedAt: '2026-10-27T00:00:00Z', publishedAt: null, endedAt: null,
    questions: [{ stem: 'Which?', options: [{ text: 'A', correct: false }, { text: 'B', correct: true }] }],
    ...overrides,
  }
}

const POOL = { available: 180, issuedToday: 12, issuedWeek: 70 }

interface Server {
  campaigns: Campaign[]
  /** The answer of a process other than the list, by name: status and body (200 with the campaign by default). */
  answers?: Record<string, { status: number; body: unknown }>
}

/** The server as the page sees it; records what was posted. */
function server(options: Server) {
  const posted: { process: string; body: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const process = /^\/api\/processes\/([A-Z_]+)\/latest$/.exec(path)?.[1]
    if (!process) return json(404, {})
    const body = await request.clone().json().catch(() => ({}))
    if (process === 'QBS_CAMPAIGN_LIST') return json(200, { output: { campaigns: options.campaigns, pool: POOL } })
    posted.push({ process, body })
    const answer = options.answers?.[process]
    return answer ? json(answer.status, answer.body) : json(200, { output: campaign({ ...body }) })
  })
  return posted
}

function page(connection: Connection = 'CONNECTED') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <CampaignsPage connection={connection} />
    </QueryClientProvider>,
  )
}

describe('the quiz campaigns (S2b)', () => {
  beforeAll(() => configureClient({ entry: 'shopify' }))
  beforeEach(() => {
    session.clear()
    session.store('a1', 'r1')
  })
  afterEach(() => vi.restoreAllMocks())

  it('shows the pool and the running campaign first, with what each may do', async () => {
    server({ campaigns: [campaign({ campaignId: 'd', name: 'Draft one' }),
      campaign({ campaignId: 'e', name: 'Old one', status: 'ENDED' }),
      campaign({ campaignId: 'l', name: 'Live one', status: 'LIVE' })] })
    page()
    const cards = await screen.findAllByTestId('campaign')
    expect(cards.map((card) => card.textContent)).toEqual([expect.stringContaining('Live one'),
      expect.stringContaining('Draft one'), expect.stringContaining('Old one')])
    expect(within(screen.getByTestId('pool')).getByText('180')).toBeInTheDocument()
    expect(within(screen.getByTestId('pool')).getByText('12')).toBeInTheDocument()
    expect(within(cards[0]).getByRole('button', { name: 'Pause' })).toBeInTheDocument()
    expect(within(cards[0]).getByRole('button', { name: 'End' })).toBeInTheDocument()
    expect(within(cards[1]).getByRole('button', { name: 'Publish' })).toBeEnabled()
    expect(within(cards[2]).getByRole('button', { name: 'Copy into a new draft' })).toBeInTheDocument()
    expect(within(cards[2]).queryByRole('button', { name: 'End' })).not.toBeInTheDocument()
    await expectAccessible()
  })

  it('asks to connect first and offers no publishing until then', async () => {
    server({ campaigns: [campaign()] })
    page('NONE')
    expect(await screen.findByText(/Connect the store to Shopify/)).toBeInTheDocument()
    expect(within(await screen.findByTestId('campaign')).getByRole('button', { name: 'Publish' })).toBeDisabled()
  })

  it('creates a draft with its questions and the correct options chosen', async () => {
    const posted = server({ campaigns: [] })
    const user = userEvent.setup()
    page()
    await user.click(await screen.findByRole('button', { name: 'New campaign' }))
    await user.type(screen.getByLabelText('Name'), 'Autumn quiz')
    await user.type(screen.getByLabelText('Video to watch first (optional)'), 'https://youtu.be/abc')
    await user.clear(screen.getByLabelText('Discount (% off the order)'))
    await user.type(screen.getByLabelText('Discount (% off the order)'), '20')
    await user.type(screen.getByLabelText('Question'), 'First?')
    await user.type(screen.getByLabelText('Option 1'), 'No')
    await user.type(screen.getByLabelText('Option 2'), 'Yes')
    await user.click(screen.getByRole('radio', { name: 'Option 2 is correct' }))
    // A second question, a third option on it.
    await user.click(screen.getByRole('button', { name: 'Add a question' }))
    const second = screen.getAllByTestId('question')[1]
    await user.type(within(second).getByLabelText('Question'), 'Second?')
    await user.click(within(second).getByRole('button', { name: 'Add an option' }))
    await user.type(within(second).getByLabelText('Option 1'), 'Red')
    await user.type(within(second).getByLabelText('Option 2'), 'Green')
    await user.type(within(second).getByLabelText('Option 3'), 'Blue')
    await user.click(within(second).getByRole('radio', { name: 'Option 3 is correct' }))
    await user.clear(screen.getByLabelText('Correct answers to pass'))
    await user.type(screen.getByLabelText('Correct answers to pass'), '2')
    // The preview follows what is typed.
    expect(within(screen.getByTestId('preview')).getByText('Answer a quiz, get 20% off')).toBeInTheDocument()
    expect(within(screen.getByTestId('preview')).getByText('Blue')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Save the draft' }))
    await waitFor(() => expect(posted).toHaveLength(1))
    expect(posted[0]).toEqual({ process: 'QBS_CAMPAIGN_SAVE', body: {
      name: 'Autumn quiz', video: 'https://youtu.be/abc', timeLimitSec: 120, passScore: 2, percent: 20,
      questions: [
        { stem: 'First?', options: [{ text: 'No', correct: false }, { text: 'Yes', correct: true }] },
        { stem: 'Second?', options: [{ text: 'Red', correct: false }, { text: 'Green', correct: false },
          { text: 'Blue', correct: true }] },
      ] } })
    expect(await screen.findByText('Saved: Autumn quiz.')).toBeInTheDocument()
  })

  it('says what is missing before sending anything', async () => {
    const posted = server({ campaigns: [] })
    const user = userEvent.setup()
    page()
    await user.click(await screen.findByRole('button', { name: 'New campaign' }))
    await user.clear(screen.getByLabelText('Correct answers to pass'))
    await user.type(screen.getByLabelText('Correct answers to pass'), '3')
    await user.click(screen.getByRole('button', { name: 'Save the draft' }))
    expect(await screen.findByText('From 1 to 1 (the number of questions).')).toBeInTheDocument()
    expect(screen.getAllByText('Required.').length).toBeGreaterThan(0)
    expect(posted).toEqual([])
  })

  it('edits a draft with its revision, and says when someone changed it meanwhile', async () => {
    const posted = server({ campaigns: [campaign()], answers: { QBS_CAMPAIGN_SAVE: { status: 409,
      body: { status: 409, title: 'Conflict', violations: [{ field: 'baseRevision', ruleCode: 'QBS_CAMPAIGN_CHANGED',
        message: 'The draft was changed meanwhile: reload it.' }] } } } })
    const user = userEvent.setup()
    page()
    await user.click(await screen.findByRole('button', { name: 'Edit' }))
    expect(screen.getByLabelText('Name')).toHaveValue('Spring quiz')
    expect(screen.getByRole('radio', { name: 'Option 2 is correct' })).toBeChecked()
    await user.click(screen.getByRole('button', { name: 'Save the draft' }))
    expect(await screen.findByText(/Someone changed this draft meanwhile/)).toBeInTheDocument()
    expect(posted[0].body).toMatchObject({ campaignId: 'c1', baseRevision: 3 })
  })

  it('publishes after asking, and pauses, resumes and ends a running one', async () => {
    const posted = server({ campaigns: [campaign(), campaign({ campaignId: 'l', status: 'LIVE' }),
      campaign({ campaignId: 'p', status: 'PAUSED' })] })
    const user = userEvent.setup()
    page()
    await user.click(await screen.findByRole('button', { name: 'Publish' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('15% discount code')
    await user.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(await screen.findByText('Published: the campaign is live.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Pause' }))
    await user.click(await screen.findByRole('button', { name: 'Resume' }))
    await user.click(screen.getAllByRole('button', { name: 'End' })[0])
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'End' }))
    await waitFor(() => expect(posted.map((p) => [p.process, p.body.to ?? null])).toEqual([
      ['QBS_CAMPAIGN_PUBLISH', null], ['QBS_CAMPAIGN_MOVE', 'PAUSED'], ['QBS_CAMPAIGN_MOVE', 'LIVE'],
      ['QBS_CAMPAIGN_MOVE', 'ENDED']]))
  })

  it('copies an ended campaign into a new draft', async () => {
    const posted = server({ campaigns: [campaign({ status: 'ENDED' })] })
    const user = userEvent.setup()
    page()
    await user.click(await screen.findByRole('button', { name: 'Copy into a new draft' }))
    expect(screen.getByRole('heading', { name: 'New campaign' })).toBeInTheDocument()
    expect(screen.getByLabelText('Name')).toHaveValue('Spring quiz')
    await user.click(screen.getByRole('button', { name: 'Save the draft' }))
    await waitFor(() => expect(posted).toHaveLength(1))
    expect(posted[0].body).not.toHaveProperty('campaignId')
    expect(posted[0].body).toMatchObject({ name: 'Spring quiz', percent: 15 })
  })

  it('shows what the server refused', async () => {
    server({ campaigns: [campaign()], answers: { QBS_CAMPAIGN_PUBLISH: { status: 422, body: { status: 422,
      title: 'Unprocessable', violations: [{ field: 'campaignId', ruleCode: 'QBS_CAMPAIGN_RUNNING',
        message: 'Another campaign is running: end it first.' }] } } } })
    const user = userEvent.setup()
    page()
    await user.click(await screen.findByRole('button', { name: 'Publish' }))
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Publish' }))
    expect(await screen.findByText('Another campaign is running: end it first.')).toBeInTheDocument()
  })
})
