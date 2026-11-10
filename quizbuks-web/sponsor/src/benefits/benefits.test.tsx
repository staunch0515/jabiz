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

const DIGITAL_CONFIRMED = { ...PENDING, purchaseId: 'p2', kind: 'DIGITAL', title: 'Film pass', pickupAddress: null,
  pickupHours: null, status: 'CONFIRMED', confirmedAt: '2026-11-03T02:00:00Z', confirmedRevision: 3 }

const CONFIRMED_WITH_STATS = { ...PENDING, purchaseId: 'p3', status: 'CONFIRMED', confirmedAt: '2026-11-03T02:00:00Z',
  confirmedRevision: 3, allocated: 60, given: 10, issued: 25, collected: 20, expired: 2, voided: 1,
  suspendedAt: null, suspendReason: null, voidUnused: null }

/** The server for an approved sponsor; `held` is whether it holds the sponsor's benefit certification. */
function server({ held = true, digital = false, confirmed = false }: { held?: boolean; digital?: boolean;
  confirmed?: boolean } = {}) {
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
          { certification: 'BENEFIT_PROVIDER', open: false, treeId: 't1', nodeId: 'n1',
            nodeName: { en: 'Fair dealing', zh: '公平交易', ja: '公正な取引' } },
          { certification: 'BENEFIT_SPONSOR', open: held, treeId: null, nodeId: null, nodeName: null },
        ], offset: 0, limit: 10 })
      case '/api/queries/qb.sponsor.benefit-catalog':
        return json(200, { items: held ? [NOODLES].filter((o) => !params.offerId || o.offerId === params.offerId) : [],
          offset: 0, limit: 200 })
      case '/api/queries/qb.sponsor.benefit-offers':
        return json(200, { items: [], offset: 0, limit: 100 })
      case '/api/queries/qb.sponsor.benefit-orders':
        return json(200, { items: [confirmed ? CONFIRMED_WITH_STATS : digital ? DIGITAL_CONFIRMED : PENDING], offset: 0,
          limit: 200 })
      case '/api/queries/qb.sponsor.benefit-order-incidents':
        return json(200, { items: [{ incidentId: 'i1', purchaseId: 'p3', redemptionId: null, kind: 'NOT_FULFILLED',
          note: 'Closed on Monday', recordedAt: '2026-11-03T03:00:00Z', recordedBy: 'SPONSOR', resolution: null,
          resolvedAt: null, resolvedBy: null }], offset: 0, limit: 100 })
      case '/api/processes/QB_BENEFIT_PURCHASE_SUSPEND/latest':
        return json(200, { output: { purchaseId: 'p3', status: 'SUSPENDED', paused: 2 } })
      case '/api/processes/QB_BENEFIT_INCIDENT_RESOLVE/latest':
        return json(200, { output: { incidentId: 'i1', resolved: true } })
      case '/api/queries/qb.sponsor.benefit-publications':
        return json(200, { items: params.purchaseId === 'p2' ? [{ publicationId: 'pub1', name: 'Film quiz', status: 'DRAFT',
          rewardMethod: 'DIGITAL_PRIZE', digitalDelivery: 'POOL', maxWinners: 50, benefitPurchaseId: 'p2',
          sponsorCompany: 'Fridge A', available: 0, issued: 0 }] : [], offset: 0, limit: 200 })
      case '/api/queries/qb.sponsor.benefit-digital-pool-summary':
        return json(200, { items: [{ available: 0, issued: 0, revoked: 0 }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.benefit-digital-codes':
        return json(200, { items: [], offset: 0, limit: 20, total: 0 })
      case '/api/processes/QB_DIGITAL_CODES_LOAD/latest':
        return json(200, { output: { publicationId: 'pub1', loaded: 2, available: 2 } })
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

  it('show the catalog to a certified Sponsor, who asks the provider', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', '/benefits')
    render(<App />)
    const offer = await screen.findByTestId('benefit-offer')
    expect(offer).toHaveTextContent('Beef noodles')
    expect(offer).toHaveTextContent('Ramen B')
    // F-S2c: the roles card: the provider's one is not open yet, with the node to light in the app.
    const rows = await screen.findAllByTestId('benefit-access-row')
    expect(rows.map((row) => row.getAttribute('data-held'))).toEqual(['false', 'true'])
    expect(rows[0]).toHaveTextContent('Light the node “Fair dealing” of the knowledge map in the app')
    expect(rows[1]).toHaveTextContent('Open')
    await expectAccessible()
    await userEvent.click(within(offer).getByTestId('benefit-ask'))
    await userEvent.type(await screen.findByTestId('benefit-ask-body'), 'How much for 100?')
    await userEvent.click(screen.getByTestId('benefit-ask-send'))
    await waitFor(() => expect(calls('QB_BENEFIT_CONVERSATION_OPEN')).toEqual([
      { offerId: 'o1', body: 'How much for 100?' }]))
  })

  it('say the catalog is for Sponsors with a benefit role open when it shows nothing', async () => {
    server({ held: false })
    window.history.replaceState(null, '', '/benefits')
    render(<App />)
    expect(await screen.findByTestId('benefit-catalog-empty')).toHaveTextContent('a benefit role is open')
    // No node named for the sponsoring role: not available yet.
    const rows = await screen.findAllByTestId('benefit-access-row')
    expect(rows[1]).toHaveTextContent('Not available yet; ask your operator.')
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

  it('let the provider of a digital product load the codes of a buyer\'s publication (Q89b-2)', async () => {
    const { calls } = server({ digital: true })
    window.history.replaceState(null, '', purchasePath('p2', 'PROVIDER'))
    render(<App />)
    const row = await screen.findByTestId('benefit-provided-row')
    expect(row).toHaveTextContent('Film quiz')
    expect(row).toHaveTextContent('Fridge A')
    await userEvent.click(within(row).getByTestId('benefit-provided-codes'))
    await userEvent.type(await within(row).findByTestId('digital-pool-lines'), 'CODE-1{enter}CODE-2')
    await userEvent.click(within(row).getByTestId('digital-pool-load'))
    await waitFor(() => expect(calls('QB_DIGITAL_CODES_LOAD')).toEqual([
      { publicationId: 'pub1', pastedCodes: 'CODE-1\nCODE-2' }]))
    await expectAccessible()
  })

  it('show what became of a confirmed purchase, suspend it, and settle an incident (Q89d-1)', async () => {
    const { calls } = server({ confirmed: true })
    window.history.replaceState(null, '', purchasePath('p3', 'PROVIDER'))
    render(<App />)
    expect(await screen.findByTestId('benefit-stat-left')).toHaveTextContent('30')
    expect(screen.getByTestId('benefit-stat-collected')).toHaveTextContent('20')
    const incident = await screen.findByTestId('benefit-incident')
    expect(incident).toHaveTextContent('Closed on Monday')
    expect(incident).toHaveTextContent('By the other side')
    await expectAccessible()
    await userEvent.type(within(incident).getByTestId('benefit-incident-resolution-input'), 'Handed over Tuesday')
    await userEvent.click(within(incident).getByTestId('benefit-incident-resolve'))
    await waitFor(() => expect(calls('QB_BENEFIT_INCIDENT_RESOLVE')).toEqual([
      { incidentId: 'i1', resolution: 'Handed over Tuesday' }]))
    await userEvent.click(screen.getByTestId('benefit-suspend'))
    const dialog = await screen.findByTestId('benefit-suspend-dialog')
    await userEvent.type(within(dialog).getByTestId('benefit-suspend-reason-input'), 'Shop closed')
    await userEvent.click(within(dialog).getByTestId('benefit-suspend-void'))
    await userEvent.click(within(dialog).getByTestId('benefit-suspend-send'))
    await waitFor(() => expect(calls('QB_BENEFIT_PURCHASE_SUSPEND')).toEqual([
      { purchaseId: 'p3', reason: 'Shop closed', voidUnused: true }]))
  })
})

describe('the way to the sponsored benefits (F-F3)', () => {
  it('without a benefit role: no area in the menu, but the hub\'s overview leads to the catalog and its card', async () => {
    server({ held: false })
    window.history.replaceState(null, '', '/profile')
    render(<App />)
    const entry = await screen.findByTestId('profile-benefits-entry')
    expect(screen.queryByTestId('menu-benefits')).toBeNull()
    expect(within(entry).getByTestId('profile-benefits-link')).toHaveAttribute('href', '/benefits')
    await userEvent.click(within(entry).getByTestId('profile-benefits-link'))
    await waitFor(() => expect(window.location.pathname).toBe('/benefits'))
    // The catalog's card says which node opens each role (as before F-F3).
    expect(await screen.findByTestId('benefit-access')).toBeInTheDocument()
  })

  it('with a benefit role: the area is in the menu, and the overview needs no way there', async () => {
    server({ held: true })
    window.history.replaceState(null, '', '/profile')
    render(<App />)
    expect(await screen.findByTestId('menu-benefits')).toBeInTheDocument()
    await screen.findByTestId('profile-aside')
    expect(screen.queryByTestId('profile-benefits-entry')).toBeNull()
  })
})
