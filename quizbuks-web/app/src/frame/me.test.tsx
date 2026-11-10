import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { frameServer, prize } from '../test/frameServer'

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => vi.restoreAllMocks())

const WALLET = { available: '1250', totalEarned: '1500', pendingCount: 0, pendingAmount: '0', inTransfer: '0',
  held: '0', heldOpen: '0', heldUntil: null, usable: false }
const CERT = { certification: 'REFERRER', heldFrom: '2026-10-01T00:00:00Z', heldUntil: '2027-10-01T00:00:00Z',
  certificateId: 'c1', verifyKey: 'k1' }

describe('"Me" (F-F4)', () => {
  it('shows the three numbers as links, Kudos with "Coming soon" while they cannot be used', async () => {
    frameServer({ wallet: WALLET, prizes: [prize(), prize({ prizeId: 'r2' })], certificates: [CERT, CERT, CERT] })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    const numbers = await screen.findByTestId('me-numbers')
    await waitFor(() => expect(within(numbers).getByTestId('me-number-kudos-value')).toHaveTextContent('1,250 Kudos'))
    expect(within(numbers).getByTestId('me-number-kudos')).toHaveAttribute('href', '/wallet')
    expect(within(numbers).getByTestId('me-number-kudos')).toHaveAccessibleName(/^Kudos 1,250\s+Kudos, Coming soon$/)
    expect(within(numbers).getByTestId('me-number-prizes')).toHaveAttribute('href', '/history/prizes')
    await waitFor(() => expect(within(numbers).getByTestId('me-number-prizes-value')).toHaveTextContent('2'))
    expect(within(numbers).getByTestId('me-number-prizes')).toHaveAccessibleName('Prizes to collect 2')
    expect(within(numbers).getByTestId('me-number-certificates')).toHaveAttribute('href', '/account/certificates')
    await waitFor(() => expect(within(numbers).getByTestId('me-number-certificates-value')).toHaveTextContent('3'))
    await expectAccessible()
  })

  it('takes "Coming soon" away once the switches are on, without a change of the app', async () => {
    frameServer({ wallet: { ...WALLET, usable: true } })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    await waitFor(() => expect(screen.getByTestId('me-number-kudos-value')).toHaveTextContent('1,250 Kudos'))
    expect(screen.queryByTestId('me-number-kudos-note')).toBeNull()
    expect(screen.getByTestId('me-number-kudos')).toHaveAccessibleName(/^Kudos 1,250\s+Kudos$/)
  })

  it('shows a dash where a number cannot be read, and the link still works', async () => {
    frameServer({ prizesFail: true })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    const numbers = await screen.findByTestId('me-numbers')
    await waitFor(() => expect(within(numbers).getByTestId('me-number-kudos-value')).toHaveTextContent('–'))
    expect(within(numbers).getByTestId('me-number-prizes-value')).toHaveTextContent('–')
    expect(within(numbers).getByTestId('me-number-prizes')).toHaveAccessibleName('Prizes to collect –')
    await userEvent.click(within(numbers).getByTestId('me-number-prizes'))
    expect(window.location.pathname).toBe('/history/prizes')
  })

  it('has the two groups with every old entry in them, and nothing of the top-right menu', async () => {
    frameServer({ wallet: WALLET })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    const owned = await screen.findByRole('navigation', { name: 'What I have' })
    expect(within(owned).getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual([
      '/history/prizes', '/account/certificates', '/maps', '/referrals', '/wallet'])
    // The Referrals of the old menu are a row here; scanning is a bottom entry, the appearance a section of the
    // settings, signing out is the button at the bottom.
    expect(screen.getByTestId('account-menu-referrals')).toHaveAttribute('href', '/referrals')
    expect(screen.getByTestId('account-menu-preferences')).toHaveAttribute('href', '/account/preferences')
    expect(screen.getByTestId('account-menu-id')).toHaveTextContent('Staff counter')
    expect(screen.getByTestId('account-menu-blocks')).toHaveTextContent('Blocks and topics')
    expect(screen.getByTestId('sign-out')).toBeInTheDocument()
    expect(screen.queryByTestId('app-menu')).toBeNull()
    expect(screen.queryByTestId('account-menu-my-sponsor')).toBeNull()
  })

  it('adds the way to the Sponsor\'s own page for a Taker who is a Sponsor too', async () => {
    frameServer({ wallet: WALLET, sponsor: true })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    expect(await screen.findByTestId('account-menu-my-sponsor')).toHaveAttribute('href', '/my-sponsor')
  })

  it('has a page of its own for blocks and topics, under "Me"', async () => {
    frameServer({ wallet: WALLET })
    window.history.replaceState(null, '', '/account/blocks')
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Blocks and topics' })).toBeInTheDocument()
    expect(screen.getByTestId('account-blocks')).toBeInTheDocument()
    expect(screen.getByTestId('account-topics')).toBeInTheDocument()
    expect(screen.getByTestId('bottom-bar-me')).toHaveAttribute('aria-current', 'page')
    await expectAccessible()
  })
})
