import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { conversationOf, conversationPath } from './api'
import { conversationsMessages } from './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }
const ID = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4c01'

const WAITING = { conversationId: ID, publicationId: 'p1', publicationName: 'Capitals', takerName: 'Mei',
  status: 'OPEN', closedBy: null, openedAt: '2026-11-01T00:00:00Z', lastMessageAt: '2026-11-02T00:00:00Z',
  lastSender: 'TAKER', lastBody: 'Where is my prize?', unread: true, waitingReply: true }
const ANSWERED = { ...WAITING, conversationId: 'c2', takerName: 'Bo', lastSender: 'SPONSOR', unread: false,
  waitingReply: false, lastMessageAt: '2026-11-02T05:00:00Z', lastBody: 'Thanks' }

/** The server as the message centre sees it, for an approved sponsor with two conversations. */
function server() {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  let conversations = [ANSWERED, WAITING]
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
          permissions: ['qb.publication.write', 'qb.conversation.use'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.sponsor.conversations': {
        const items = conversations.filter((c) => (!params.conversationId || c.conversationId === params.conversationId)
          && (!params.waiting || c.waitingReply))
        return json(200, { items, offset: 0, limit: 200 })
      }
      case '/api/queries/qb.sponsor.conversations-unread':
        return json(200, { items: [{ unread: conversations.filter((c) => c.unread).length }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.conversation-messages':
        return json(200, { items: [{ messageId: 'm1', sender: 'TAKER', body: 'Where is my prize?',
          createdAt: '2026-11-02T00:00:00Z' }], offset: 0, limit: 500 })
      case '/api/queries/qb.sponsor.conversation-rewards':
        return json(200, { items: [{ kind: 'REDEEM', recordId: 'r1', sourceKind: 'ATTEMPT', code: 'QB-7K2',
          hint: null, amount: 500, status: 'DUE' }], offset: 0, limit: 50 })
      case '/api/processes/QB_CONVERSATION_READ/latest':
        conversations = conversations.map((c) => ({ ...c, unread: false }))
        return json(200, { output: { conversationId: ID, status: 'OPEN' } })
      case '/api/processes/QB_CONVERSATION_SEND/latest':
        return json(200, { output: { conversationId: ID, messageId: 'm2', created: false } })
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
  cleanup()
  window.history.replaceState(null, '', '/')
})

describe('the Takers\' conversations (Q82)', () => {
  it('have every text in every language, and read their paths', () => {
    const english = keyPaths(conversationsMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(conversationsMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
    expect(conversationOf(conversationPath(ID))).toBe(ID)
    expect(conversationOf('/messages')).toBeNull()
  })

  it('list those waiting for a reply first, with the menu\'s badge', async () => {
    server()
    window.history.replaceState(null, '', '/messages?tab=conversations')
    render(<App />)
    const rows = await screen.findAllByTestId('conversation-row')
    expect(rows.map((row) => row.getAttribute('data-id'))).toEqual([ID, 'c2'])
    expect(rows[0]).toHaveTextContent('Mei')
    expect(within(rows[0]).getByTestId('conversation-waiting')).toHaveTextContent('New')
    expect(await screen.findByTestId('messages-tab-conversations-unread')).toHaveTextContent('1')
    await expectAccessible()
  })

  it('show a conversation with the Taker\'s rewards, mark it read and send a reply', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', conversationPath(ID))
    render(<App />)
    expect(await screen.findByTestId('conversation-taker')).toHaveTextContent('Mei')
    expect(await screen.findByTestId('conversation-reward')).toHaveTextContent('QB-7K2')
    await waitFor(() => expect(calls('QB_CONVERSATION_READ')).toEqual([{ conversationId: ID }]))
    await userEvent.type(screen.getByTestId('conversation-body'), 'At the counter.')
    await userEvent.click(screen.getByTestId('conversation-send'))
    await waitFor(() => expect(calls('QB_CONVERSATION_SEND')).toEqual([
      { conversationId: ID, body: 'At the counter.' }]))
    await expectAccessible()
  })
})
