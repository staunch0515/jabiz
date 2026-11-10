import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { frameServer, prize } from '../test/frameServer'
import { createDecoder } from '../scan/decoder'

vi.mock('qrcode', () => ({ default: { toString: vi.fn(async (text: string) => `<svg>${text}</svg>`) } }))
vi.mock('../scan/decoder', () => ({ createDecoder: vi.fn() }))

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
  vi.mocked(createDecoder).mockResolvedValue({ decode: vi.fn(async () => null) })
  const stream = { getTracks: () => [{ stop: vi.fn() }] } as unknown as MediaStream
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true,
    value: { getUserMedia: vi.fn(async () => stream) } })
})
afterEach(() => {
  vi.restoreAllMocks()
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: undefined })
})

const DUE = prize()
const SECOND = prize({ prizeId: 'r2', attemptId: 'a2', publicationName: 'Tea week', description: 'A pot of tea' })
const OTHERS = [
  prize({ kind: 'CASH', prizeId: 'c1', status: 'DUE' }),
  prize({ prizeId: 'r3', status: 'CLAIMED' }),
]

describe('showing the pass from the scanner (F-F4)', () => {
  it('opens the one pass of the one prize to collect with one tap on the scanner\'s button', async () => {
    const { posted } = frameServer({ prizes: [DUE, ...OTHERS] })
    window.history.replaceState(null, '', '/scan')
    render(<App />)
    const button = await screen.findByTestId('pass-show')
    expect(button).toHaveTextContent('Show my pass (1)')
    await userEvent.click(button)
    expect(await screen.findByTestId('pass-dialog')).toBeInTheDocument()
    expect(await screen.findByTestId('pass-digits')).toHaveTextContent('123 456')
    expect(posted.filter((p) => p.path === '/api/processes/QB_REDEMPTION_PASS_ISSUE/latest').map((p) => p.body))
      .toEqual([{ redemptionId: 'r1' }])
    await expectAccessible()
  })

  it('leads to the tab that lists them when there are several', async () => {
    frameServer({ prizes: [DUE, SECOND] })
    window.history.replaceState(null, '', '/scan')
    render(<App />)
    const link = await screen.findByTestId('pass-shortcut')
    expect(link).toHaveTextContent('Show my pass (2)')
    expect(link).toHaveAttribute('href', '/scan/pass')
    await userEvent.click(link)
    expect(window.location.pathname).toBe('/scan/pass')
  })

  it('offers nothing on the scanner when no prize waits at a Sponsor\'s place', async () => {
    frameServer({ prizes: OTHERS.filter((p) => p.kind === 'CASH') })
    window.history.replaceState(null, '', '/scan')
    render(<App />)
    await screen.findByTestId('scan-page')
    await waitFor(() => expect(screen.queryByTestId('pass-show')).toBeNull())
    expect(screen.queryByTestId('pass-shortcut')).toBeNull()
  })
})

describe('the tab "Show my pass" (F-F4)', () => {
  it('lists the prizes to collect, each with its pass, and does not start the camera', async () => {
    frameServer({ prizes: [DUE, SECOND, ...OTHERS] })
    window.history.replaceState(null, '', '/scan/pass')
    render(<App />)
    const passes = await screen.findAllByTestId('my-pass')
    expect(passes).toHaveLength(2)
    expect(passes[0]).toHaveTextContent('A bowl of noodles')
    expect(passes[0]).toHaveTextContent('Capitals week · Ramen B')
    expect(within(passes[1]).getByTestId('pass-show')).toBeInTheDocument()
    expect(screen.getByTestId('page-tab-pass')).toHaveAttribute('aria-current', 'page')
    expect(screen.getByTestId('bottom-bar-scan')).toHaveAttribute('aria-current', 'page')
    expect(navigator.mediaDevices.getUserMedia).not.toHaveBeenCalled()
    await expectAccessible()
    await userEvent.click(within(passes[0]).getByTestId('pass-show'))
    expect(await screen.findByTestId('pass-qr')).toBeInTheDocument()
  })

  it('has an empty state that leads to what is waiting', async () => {
    frameServer({ prizes: [] })
    window.history.replaceState(null, '', '/scan/pass')
    render(<App />)
    expect(await screen.findByTestId('my-passes-empty')).toHaveTextContent('No pass to show')
    await userEvent.click(screen.getByRole('link', { name: 'See what is waiting' }))
    expect(window.location.pathname).toBe('/history/prizes')
    await expectAccessible()
  })

  it('goes back to the camera on the first tab', async () => {
    frameServer({ prizes: [] })
    window.history.replaceState(null, '', '/scan/pass')
    render(<App />)
    await screen.findByTestId('my-passes-empty')
    await userEvent.click(screen.getByTestId('page-tab-scan'))
    expect(await screen.findByTestId('scan-page')).toBeInTheDocument()
    await waitFor(() => expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalled())
  })
})
