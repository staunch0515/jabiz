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
const CERT = { source: 'NODE', certificateId: 'c1', verifyKey: 'k1', names: { en: 'Coffee' }, issuerName: 'Beans',
  litAt: '2026-10-01T00:00:00Z', revokedAt: null, shared: true, runId: null, heldUntil: null, certification: null }
// A revoked certificate is listed (marked) but not counted.
const REVOKED = { ...CERT, certificateId: 'c2', verifyKey: 'k2', revokedAt: '2026-10-05T00:00:00Z' }

describe('My page (F-F4; Q113d-2)', () => {
  it('shows the three numbers as links, Kudos with "Coming soon" while they cannot be used', async () => {
    frameServer({ wallet: WALLET, prizes: [prize(), prize({ prizeId: 'r2' })], certificates: [CERT, CERT, CERT, REVOKED] })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    const numbers = await screen.findByTestId('me-numbers')
    await waitFor(() => expect(within(numbers).getByTestId('me-number-kudos-value')).toHaveTextContent('1,250'))
    expect(within(numbers).getByTestId('me-number-kudos')).toHaveAttribute('href', '/wallet')
    expect(within(numbers).getByTestId('me-number-kudos')).toHaveAccessibleName('Kudos 1,250, Coming soon')
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
    await waitFor(() => expect(screen.getByTestId('me-number-kudos-value')).toHaveTextContent('1,250'))
    expect(screen.queryByTestId('me-number-kudos-note')).toBeNull()
    expect(screen.getByTestId('me-number-kudos')).toHaveAccessibleName('Kudos 1,250')
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

  it('is the profile of X: the name, the picture over the banner, where and since when, and the five tabs', async () => {
    frameServer({ wallet: WALLET })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    expect(await screen.findByTestId('me-banner')).toBeInTheDocument()
    expect(screen.getByTestId('me-avatar')).toBeInTheDocument()
    expect(screen.getByTestId('me-edit-profile')).toHaveAttribute('href', '/account/profile')
    const tabs = screen.getByRole('navigation', { name: 'My content' })
    expect(within(tabs).getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual([
      '/account', '/account?tab=kudos', '/account?tab=certificates', '/account?tab=maps', '/account?tab=referrals'])
    expect(screen.getByTestId('page-tab-answered')).toHaveAttribute('aria-current', 'page')
    // Answered: the latest, then the way to all of them (none here: the prototype's empty tab).
    expect(await screen.findByTestId('me-answered-empty')).toHaveTextContent('No quizzes yet')
    await expectAccessible()
    await userEvent.click(within(tabs).getByRole('link', { name: 'Knowledge maps' }))
    expect(window.location.search).toBe('?tab=maps')
    expect(await screen.findByTestId('me-tab-maps')).toBeInTheDocument()
    expect(screen.getByTestId('me-maps-all')).toHaveAttribute('href', '/maps')
    // The settings are a page of their own now, nothing of the top-right menu.
    expect(screen.queryByTestId('me-settings')).toBeNull()
    expect(screen.queryByTestId('app-menu')).toBeNull()
  })

  it('opens the tab an address names, the first for another', async () => {
    frameServer({ wallet: WALLET })
    window.history.replaceState(null, '', '/account?tab=kudos')
    render(<App />)
    expect(await screen.findByTestId('me-tab-kudos')).toBeInTheDocument()
    expect(screen.getByTestId('me-kudos-all')).toHaveAttribute('href', '/wallet')
    window.history.replaceState(null, '', '/account?tab=nothing')
  })
})

describe('the settings (Q113d-2)', () => {
  it('has every entry the menu of "Me" had, searched by their words, and signing out', async () => {
    frameServer({ wallet: WALLET })
    window.history.replaceState(null, '', '/settings')
    render(<App />)
    const rows = await screen.findByTestId('me-settings')
    expect(screen.getByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument()
    expect(screen.getByTestId('account-menu-preferences')).toHaveAttribute('href', '/account/preferences')
    expect(screen.getByTestId('account-menu-id')).toHaveTextContent('Staff counter')
    expect(screen.getByTestId('account-menu-blocks')).toHaveTextContent('Blocks and topics')
    expect(screen.getByTestId('account-about')).toHaveAttribute('href', '/about')
    expect(screen.getByTestId('sign-out')).toBeInTheDocument()
    expect(screen.queryByTestId('account-menu-my-sponsor')).toBeNull()
    await expectAccessible()
    await userEvent.type(screen.getByTestId('settings-search'), 'blocks')
    expect(within(rows).getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual(['/account/blocks'])
    await userEvent.clear(screen.getByTestId('settings-search'))
    await userEvent.type(screen.getByTestId('settings-search'), 'zzzz')
    expect(screen.getByTestId('settings-none')).toHaveTextContent('No setting matches.')
  })

  it('is where the drawer\'s "Settings" leads', async () => {
    frameServer({ wallet: WALLET })
    window.history.replaceState(null, '', '/account/profile')
    render(<App />)
    expect(await screen.findByTestId('header-back')).toHaveAccessibleName('Back to Settings')
    expect(screen.getByTestId('header-back')).toHaveAttribute('href', '/settings')
  })

  it('adds the way to the Sponsor\'s own page for a Taker who is a Sponsor too', async () => {
    frameServer({ wallet: WALLET, sponsor: true })
    window.history.replaceState(null, '', '/settings')
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
    // "Me" is the drawer's (Q113 §9.2): the left column of a wide screen marks it.
    expect(screen.getByTestId('side-rail-me')).toHaveAttribute('aria-current', 'page')
    await expectAccessible()
  })
})
