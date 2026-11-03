import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { conversationPath, idOf, newPurchasePath, PURCHASES_PATH, purchasePath, sideOf } from './api'
import { benefitsMessages } from './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const NOODLES = { offerId: 'o1', kind: 'GOODS', name: 'Beef noodles', description: 'One bowl', image1: null,
  unitValue: 30, pickupAddress: 'Ramen B, Shibuya', pickupHours: '11:00-21:00', terms: null,
  editedAt: '2026-11-03T00:00:00Z', providerCompany: 'Ramen B', conversationId: null }

const PENDING = { purchaseId: 'p1', offerId: 'o1', otherCompany: 'Fridge A', kind: 'GOODS', title: 'Beef noodles',
  unitValue: 30, pickupAddress: 'Ramen B, Shibuya', pickupHours: '11:00-21:00', terms: null, quantity: 100,
  paidAmount: 3000, paidOn: '2026-11-03', proof1: 'f1', note: 'Paid by transfer', status: 'PENDING_PROVIDER',
  submittedAt: '2026-11-03T01:00:00Z', confirmedAt: null, confirmedRevision: null, declineReason: null,
  editedAt: '2026-11-03T01:00:00Z', revision: 3 }

/** The server for an approved sponsor; `held` is whether it holds the sponsor's benefit certification. */
function server({ held = true }: { held?: boolean } = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    const params = (body?.params ?? {}) as Record<string, unknown>
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.publication.write', 'qb.benefit.use'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.sponsor.benefit-access':
        return json(200, { items: [
          { certification: 'BENEFIT_PROVIDER', heldUntil: null, roundPublicationId: 'r1' },
          { certification: 'BENEFIT_SPONSOR', heldUntil: held ? '2027-11-03T00:00:00Z' : null, roundPublicationId: null },
        ], offset: 0, limit: 10 })
      case '/api/queries/qb.sponsor.benefit-catalog':
        return json(200, { items: held ? [NOODLES].filter((o) => !params.offerId || o.offerId === params.offerId) : [],
          offset: 0, limit: 200 })
      case '/api/queries/qb.sponsor.benefit-offers':
        return json(200, { items: [], offset: 0, limit: 100 })
      case '/api/queries/qb.sponsor.benefit-orders':
        return json(200, { items: [PENDING], offset: 0, limit: 200 })
      case '/api/queries/qb.sponsor.benefit-purchases':
        return json(200, { items: [], offset: 0, limit: 200 })
      case '/api/processes/QB_BENEFIT_CONVERSATION_OPEN/latest':
        return json(200, { output: { conversationId: 'c1', messageId: 'm1', created: true } })
      case '/api/processes/QB_BENEFIT_PURCHASE_SAVE/latest':
        return json(200, { output: { purchaseId: 'p9', status: 'DRAFT', revision: 1 } })
      case '/api/processes/QB_BENEFIT_PURCHASE_CONFIRM/latest':
        return json(200, { output: { purchaseId: 'p1', status: 'CONFIRMED', revision: 4 } })
      default:
        return json(404, {})
    }
  })
  const calls = (name: string) => posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body)
  return { calls }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
  cleanup()
  window.history.replaceState(null, '', '/')
})

describe('sponsored benefits (Q89a)', () => {
  it('have every text in every language, and read their paths', () => {
    const english = keyPaths(benefitsMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(benefitsMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
    expect(idOf(purchasePath('p 1', 'PROVIDER').split('?')[0], PURCHASES_PATH)).toBe('p 1')
    expect(idOf('/benefits/purchases', PURCHASES_PATH)).toBeNull()
    expect(sideOf('?as=provider')).toBe('PROVIDER')
    expect(sideOf('')).toBe('SPONSOR')
    expect(conversationPath('c1', 'SPONSOR')).toBe('/benefits/conversations/c1')
    expect(newPurchasePath('o1')).toBe('/benefits/purchases/new?offer=o1')
  })

  it('show the catalog to a certified sponsor, who asks the provider', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', '/benefits')
    render(<App />)
    const offer = await screen.findByTestId('benefit-offer')
    expect(offer).toHaveTextContent('Beef noodles')
    expect(offer).toHaveTextContent('Ramen B')
    // The certification card: the provider's one is missing, with the quiz to take in the app.
    const rows = await screen.findAllByTestId('benefit-access-row')
    expect(rows.map((row) => row.getAttribute('data-held'))).toEqual(['false', 'true'])
    expect(within(rows[0]).getByRole('link')).toHaveAttribute('href', '/taker/p/r1')
    await expectAccessible()
    await userEvent.click(within(offer).getByTestId('benefit-ask'))
    await userEvent.type(await screen.findByTestId('benefit-ask-body'), 'How much for 100?')
    await userEvent.click(screen.getByTestId('benefit-ask-send'))
    await waitFor(() => expect(calls('QB_BENEFIT_CONVERSATION_OPEN')).toEqual([
      { offerId: 'o1', body: 'How much for 100?' }]))
  })

  it('say the catalog is for certified sponsors when it shows nothing', async () => {
    server({ held: false })
    window.history.replaceState(null, '', '/benefits')
    render(<App />)
    expect(await screen.findByTestId('benefit-catalog-empty')).toHaveTextContent('benefit certification')
  })

  it('record a new purchase of a catalog benefit', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', newPurchasePath('o1'))
    render(<App />)
    await userEvent.type(await screen.findByTestId('benefit-purchase-quantity'), '100')
    await userEvent.type(screen.getByTestId('benefit-purchase-paid'), '3000')
    await userEvent.type(screen.getByTestId('benefit-purchase-paid-on'), '2026-11-03')
    await userEvent.click(screen.getByTestId('benefit-purchase-save'))
    await waitFor(() => expect(calls('QB_BENEFIT_PURCHASE_SAVE')).toEqual([{ offerId: 'o1', quantity: '100',
      paidAmount: '3000', paidOn: '2026-11-03', proofs: [], note: null }]))
    await waitFor(() => expect(window.location.pathname).toBe('/benefits/purchases/p9'))
  })

  it('let the provider confirm the revision it reads', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', purchasePath('p1', 'PROVIDER'))
    render(<App />)
    expect(await screen.findByTestId('benefit-purchase-state')).toHaveTextContent('Waiting for the provider')
    expect(screen.getByTestId('benefit-purchase-answer')).toHaveTextContent('Paid by transfer')
    await expectAccessible()
    await userEvent.click(screen.getByTestId('benefit-purchase-confirm'))
    const dialog = await screen.findByRole('alertdialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm: received and will provide' }))
    await waitFor(() => expect(calls('QB_BENEFIT_PURCHASE_CONFIRM')).toEqual([{ purchaseId: 'p1', baseRevision: 3 }]))
  })
})
