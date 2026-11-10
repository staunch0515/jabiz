import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { frameServer, prize } from '../test/frameServer'

// jsdom has no canvas: the QR code is drawn as text.
vi.mock('qrcode', () => ({ default: { toString: vi.fn(async (text: string) => `<svg>${text}</svg>`) } }))

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => vi.restoreAllMocks())

const THREE = [
  prize(),
  prize({ kind: 'CASH', prizeId: 'c1', attemptId: 'a2', code: 'C-ABCD1234', description: null, status: 'MARKED_PAID',
    expiresAt: null, publicationName: 'Cash week' }),
  prize({ kind: 'DIGITAL', prizeId: 'd1', attemptId: 'a3', code: null, status: 'DELIVERED', expiresAt: null,
    publicationName: 'Premium week', description: 'A month of Premium' }),
  prize({ prizeId: 'r2', attemptId: 'a4', status: 'REDEEMED', publicationName: 'Old week' }),
]

describe('the tab "To collect" (F-F4)', () => {
  it('lists the three kinds, each leading to its result, with the pass of a prize to collect', async () => {
    frameServer({ prizes: THREE })
    window.history.replaceState(null, '', '/history/prizes')
    render(<App />)
    const rows = await screen.findAllByTestId('prize-row')
    expect(rows.map((row) => row.dataset.kind)).toEqual(['REDEEM', 'CASH', 'DIGITAL'])
    expect(within(rows[0]).getByTestId('prize-status')).toHaveTextContent('A prize worth 100 Kudos to collect')
    expect(within(rows[1]).getByTestId('prize-status')).toHaveTextContent('The Sponsor paid 100 Kudos: confirm it')
    expect(within(rows[2]).getByTestId('prize-status')).toHaveTextContent('Digital prize worth 100 Kudos is ready')
    expect(within(rows[0]).getByTestId('prize-open')).toHaveAttribute('href', '/result/a1')
    expect(within(rows[1]).getByTestId('prize-open')).toHaveAttribute('href', '/result/a2')
    // The pass is for the prize to collect at the Sponsor's only.
    expect(within(rows[0]).getByTestId('pass-show')).toBeInTheDocument()
    expect(within(rows[1]).queryByTestId('pass-show')).toBeNull()
    expect(within(rows[2]).queryByTestId('pass-show')).toBeNull()
    expect(screen.getByTestId('page-tab-prizes')).toHaveAttribute('aria-current', 'page')
    expect(screen.getByTestId('bottom-bar-history')).toHaveAttribute('aria-current', 'page')
    await expectAccessible()
  })

  it('leads a prize a friend earned to the referral\'s page', async () => {
    frameServer({ prizes: [prize({ sourceKind: 'REFERRAL', attemptId: 'ref-9' })] })
    window.history.replaceState(null, '', '/history/prizes')
    render(<App />)
    expect(await screen.findByTestId('prize-open')).toHaveAttribute('href', '/referral/ref-9')
  })

  it('shows all of them, the settled too, on request, and asks the server for the open ones otherwise', async () => {
    const { queries } = frameServer({ prizes: THREE })
    window.history.replaceState(null, '', '/history/prizes')
    render(<App />)
    await screen.findAllByTestId('prize-row')
    expect(queries('qb.app.prizes').some((call) => (call.body as { params: { open: boolean } }).params.open === true))
      .toBe(true)
    await userEvent.click(screen.getByRole('radio', { name: 'All' }))
    await waitFor(() => expect(screen.getAllByTestId('prize-row')).toHaveLength(4))
    expect(screen.getAllByTestId('prize-row')[3]).toHaveAttribute('data-status', 'REDEEMED')
  })

  it('counts the open ones on the tab and on the bottom menu\'s Answered, once', async () => {
    const { queries } = frameServer({ prizes: THREE })
    window.history.replaceState(null, '', '/history')
    render(<App />)
    await waitFor(() => expect(screen.getByTestId('page-tab-prizes')).toHaveTextContent('3'))
    expect(screen.getByTestId('bottom-bar-history')).toHaveAccessibleName('Answered, 3 to collect')
    expect(screen.getByTestId('side-rail-history')).toHaveAccessibleName('Answered, 3 to collect')
    // One request for the count, whoever shows it.
    const counts = queries('qb.app.prizes').filter((call) => (call.body as { count?: boolean }).count)
    expect(counts).toHaveLength(1)
  })

  it('says there is nothing to collect, with the way to a quiz', async () => {
    frameServer({ prizes: [] })
    window.history.replaceState(null, '', '/history/prizes')
    render(<App />)
    expect(await screen.findByTestId('prizes-empty')).toHaveTextContent('Nothing to collect')
    expect(screen.getByTestId('bottom-bar-history')).toHaveAccessibleName('Answered')
    await expectAccessible()
  })

  it('says when the prizes cannot be read, and tries again', async () => {
    frameServer({ prizesFail: true })
    window.history.replaceState(null, '', '/history/prizes')
    render(<App />)
    expect(await screen.findByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})
