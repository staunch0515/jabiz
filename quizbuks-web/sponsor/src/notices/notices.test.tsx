import { configureClient, session } from '@jabiz/client'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { instantOf, localOf, rate } from './api'
import { noticesMessages } from './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const P1 = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b'

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const SENT = {
  broadcastId: 'b1', status: 'SENT', title: { en: 'Try our quiz' }, body: { en: 'It is fun' }, publicationId: P1,
  publicationName: 'Big prize', regions: 'JP', scheduledAt: null, sentAt: '2026-10-17T01:00:00Z',
  expiresAt: '2026-10-25T00:00:00Z', audienceCount: 200, reads: 50, clicks: 10, conversions: null,
  cancelReason: null, withdrawnAt: null, withdrawnByAdmin: false, withdrawReason: null, revision: 3,
  editedAt: '2026-10-17T01:00:00Z',
}
const WITHDRAWN = {
  ...SENT, broadcastId: 'b2', status: 'WITHDRAWN', title: { ja: 'お得なクイズ' }, reads: null, clicks: null,
  withdrawnAt: '2026-10-17T02:00:00Z', withdrawnByAdmin: true, withdrawReason: 'Misleading', revision: 4,
}
const DRAFT = {
  ...SENT, broadcastId: 'b3', status: 'DRAFT', title: { en: 'Draft one' }, body: { en: 'Soon' }, sentAt: null,
  audienceCount: null, reads: null, clicks: null, conversions: null, revision: 1,
}

interface Server {
  notices?: Record<string, unknown>[]
  /** The problems the next saves answer with, in turn (the last repeats). */
  problems?: Record<string, unknown>[][]
}

/** The server as the message centre sees it, for an approved sponsor. Records what the page sent. */
function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  const problems = options.problems ?? [[]]
  let saves = 0
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.broadcast.write', 'qb.sponsor.me'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:broadcast-status':
        return json(200, [{ code: 'DRAFT', label: 'Draft' }, { code: 'SCHEDULED', label: 'Scheduled' },
          { code: 'SENT', label: 'Sent' }, { code: 'WITHDRAWN', label: 'Withdrawn' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:region':
        return json(200, [{ code: 'JP', label: 'Japan' }, { code: 'ASIA', label: 'Asia' }])
      case '/api/queries/qb.sponsor.broadcasts':
        return json(200, { items: options.notices ?? [SENT, WITHDRAWN, DRAFT], offset: 0, limit: 200 })
      case '/api/queries/qb.sponsor.broadcast-publications':
        return json(200, { items: [{ publicationId: P1, name: 'Big prize', status: 'LIVE', regions: 'JP,ASIA',
          startAt: '2026-10-10T00:00:00Z', endAt: '2026-10-25T00:00:00Z', notices: 1 }], offset: 0, limit: 200 })
      case '/api/queries/qb.sponsor.inbox':
        return json(200, { items: [{ itemKind: 'BROADCAST', itemId: 'a1', type: 'SYSTEM',
          title: { en: 'New fees' }, body: { en: 'Fees **change**.' }, at: '2026-10-16T00:00:00Z',
          expiresAt: null, readAt: null, unread: true }], offset: 0, limit: 100 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/processes/QB_BROADCAST_SAVE/latest': {
        const answer = problems[Math.min(saves++, problems.length - 1)]
        return json(200, { output: { broadcastId: 'new1', status: 'DRAFT', revision: saves,
          sendsAt: '2026-10-18T00:00:00Z', expiresAt: '2026-10-25T00:00:00Z', targetCount: 0, problems: answer } })
      }
      case '/api/processes/QB_BROADCAST_SEND/latest':
        return json(200, { output: { broadcastId: 'new1', status: 'SENT', revision: saves + 1,
          sentAt: '2026-10-18T00:00:00Z', audienceCount: 120 } })
      case '/api/processes/QB_BROADCAST_WITHDRAW/latest':
      case '/api/processes/QB_BROADCAST_CANCEL/latest':
        return json(200, { output: { broadcastId: body?.broadcastId, status: 'WITHDRAWN', revision: 9 } })
      case '/api/processes/QB_MESSAGE_MARK/latest':
        return json(200, { output: { changed: true } })
      default:
        return json(404, {})
    }
  })
  const calls = (name: string) => posted.filter((p) => p.path === `/api/processes/${name}/latest`)
    .map((p) => p.body)
  return { calls }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
})

describe('the message centre helpers', () => {
  it('compute rates only from counts the server gave, and convert local times', () => {
    expect(rate(50, 200)).toBe(25)
    expect(rate(null, 200)).toBeNull()
    expect(rate(3, 0)).toBeNull()
    expect(localOf(instantOf('2026-10-20T09:30'))).toBe('2026-10-20T09:30')
    expect(instantOf('')).toBeNull()
  })

  it('have every text in every language', () => {
    const english = keyPaths(noticesMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(noticesMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
  })
})

describe('the message centre', () => {
  it('lists the notices with the statistics the server gives, fewer than five unnamed', async () => {
    server()
    window.history.replaceState(null, '', '/messages')
    render(<App />)
    const rows = await screen.findAllByTestId('notice-row')
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByTestId('notice-reached')).toHaveTextContent('200')
    expect(within(rows[0]).getByTestId('notice-reads')).toHaveTextContent('50 (25 %)')
    expect(within(rows[0]).getByTestId('notice-clicks')).toHaveTextContent('10 (20 %)')
    expect(within(rows[0]).getByTestId('notice-conversions')).toHaveTextContent('fewer than 5')
    expect(rows[0]).toHaveTextContent('Big prize')
    expect(rows[0]).toHaveTextContent('Japan')
    // Withdrawn by QuizBuks: the reason is shown.
    expect(within(rows[1]).getByTestId('notice-withdrawn')).toHaveTextContent('Withdrawn by QuizBuks: Misleading')
    expect(rows[1].querySelector('[lang="ja"]')).not.toBeNull()
    // A sent one is withdrawn, a draft edited or discarded.
    expect(within(rows[0]).getByTestId('notice-withdraw')).toBeInTheDocument()
    expect(within(rows[0]).queryByTestId('notice-edit')).toBeNull()
    expect(within(rows[2]).getByTestId('notice-edit')).toBeInTheDocument()
    expect(within(rows[2]).getByTestId('notice-discard')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Messages' })).toHaveAttribute('aria-current', 'page')
  })

  it('writes a notice, shows the server\'s problems and sends it only once there are none', async () => {
    const { calls } = server({ problems: [[{ field: 'body', code: 'QB_BROADCAST_LINK',
      message: 'A notice is plain text: leave out web and e-mail addresses.' }], []] })
    window.history.replaceState(null, '', '/messages')
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByTestId('notice-new'))
    await user.type(screen.getByTestId('notice-title'), 'Play now')
    await user.type(screen.getByTestId('notice-body'), 'See www.example.com')
    await user.click(screen.getByTestId('notice-publication'))
    await user.click(await screen.findByRole('option', { name: 'Big prize (notices: 1)' }))
    // The publication's regions to start with.
    expect(await screen.findByTestId('notice-region-JP')).toHaveAttribute('aria-checked', 'true')
    await user.click(screen.getByTestId('notice-region-ASIA'))
    expect(screen.getByTestId('notice-preview')).toHaveTextContent('See www.example.com')
    expect(screen.getByTestId('notice-send')).toBeDisabled()

    await user.click(screen.getByTestId('notice-save'))
    expect(await screen.findByTestId('notice-problems')).toHaveTextContent('leave out web and e-mail addresses')
    expect(calls('QB_BROADCAST_SAVE')[0]).toEqual({ broadcastId: null, baseRevision: null,
      title: { en: 'Play now' }, body: { en: 'See www.example.com' }, publicationId: P1, regions: ['JP'],
      scheduledAt: null })
    expect(screen.getByTestId('notice-send')).toBeDisabled()

    await user.clear(screen.getByTestId('notice-body'))
    await user.type(screen.getByTestId('notice-body'), 'Play it today')
    // Changed since the save: still not sent until saved again.
    expect(screen.getByTestId('notice-send')).toBeDisabled()
    await user.click(screen.getByTestId('notice-save'))
    expect(await screen.findByTestId('notice-ready')).toHaveTextContent('It can be sent.')
    expect(calls('QB_BROADCAST_SAVE')[1]).toMatchObject({ broadcastId: 'new1', baseRevision: 1 })
    await user.click(screen.getByTestId('notice-send'))
    await user.click(await screen.findByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(calls('QB_BROADCAST_SEND')).toEqual([{ broadcastId: 'new1', baseRevision: 2 }]))
  })

  it('schedules for a time and withdraws a sent notice after asking', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', '/messages')
    const user = userEvent.setup()
    render(<App />)
    const rows = await screen.findAllByTestId('notice-row')
    await user.click(within(rows[0]).getByTestId('notice-withdraw'))
    await user.click(await screen.findByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(calls('QB_BROADCAST_WITHDRAW')).toEqual([{ broadcastId: 'b1', baseRevision: 3 }]))

    await user.click(within(screen.getAllByTestId('notice-row')[2]).getByTestId('notice-edit'))
    expect(screen.getByTestId('notice-title')).toHaveValue('Draft one')
    await user.click(screen.getByTestId('notice-later'))
    await user.type(screen.getByTestId('notice-at'), '2026-10-20T09:30')
    await user.click(screen.getByTestId('notice-save'))
    await waitFor(() => expect(calls('QB_BROADCAST_SAVE')).toHaveLength(1))
    expect(calls('QB_BROADCAST_SAVE')[0]).toMatchObject({ broadcastId: 'b3', baseRevision: 1,
      scheduledAt: new Date('2026-10-20T09:30').toISOString() })
    expect(await screen.findByTestId('notice-send')).toHaveTextContent('Schedule')
  })

  it('shows QuizBuks\'s announcements to sponsors, read when opened', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', '/messages')
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByTestId('messages-tab-inbox'))
    const item = await screen.findByTestId('announcement')
    expect(item).toHaveAttribute('data-unread', 'true')
    await user.click(within(item).getByTestId('announcement-open'))
    expect(within(item).getByTestId('markdown-view')).toContainHTML('<strong>change</strong>')
    await waitFor(() => expect(calls('QB_MESSAGE_MARK')).toEqual([{ kind: 'BROADCAST', id: 'a1', action: 'READ' }]))
  })
})
