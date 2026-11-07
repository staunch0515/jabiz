import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { targetOf } from '../inbox/api'
import { conversationOf, conversationPath } from './api'
import { ContactSponsor } from './ConversationPages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device', emailLink: true }
const USER = '01a0f4fe-4660-7000-8000-0000000000dd'
const PUBLICATION = '01a0f4fe-4660-7000-8000-0000000000p1'
const ID = '01a0f4fe-4660-7000-8000-0000000000c1'

const CONVERSATION = { conversationId: ID, publicationId: PUBLICATION, publicationName: 'Capitals',
  sponsorCompany: 'Acme Tea', status: 'OPEN', closedBy: null, openedAt: '2026-11-01T00:00:00Z',
  lastMessageAt: '2026-11-02T00:00:00Z', lastSender: 'SPONSOR', lastBody: 'At the counter', unread: true }

const MESSAGES = [
  { messageId: 'm1', sender: 'TAKER', body: 'Where is my prize?', createdAt: '2026-11-01T00:00:00Z' },
  { messageId: 'm2', sender: 'SPONSOR', body: 'At the counter', createdAt: '2026-11-02T00:00:00Z' },
]

interface Server {
  conversations?: Record<string, unknown>[]
  blocks?: Record<string, unknown>[]
  refusal?: Record<string, unknown>
}

/** The server as the app sees it: a verified taker, their conversations and the conversation processes. */
function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  let conversations = options.conversations ?? [CONVERSATION]
  let blocks = options.blocks ?? []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    const params = (body?.params ?? {}) as Record<string, unknown>
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: USER, displayName: 'Mei', email: 'mei@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.conversation.use'], emailVerified: true, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Mei', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/datasets/urn:jabiz:dataset:me:QbUserSettings/query':
        return json(200, { items: [], offset: 0, limit: 1 })
      case '/api/queries/qb.app.inbox-unread':
        return json(200, { items: [{ messages: 0, broadcasts: 0 }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.conversations': {
        const items = conversations.filter((c) => (!params.conversationId || c.conversationId === params.conversationId)
          && (!params.publicationId || c.publicationId === params.publicationId))
        return json(200, { items, offset: 0, limit: 100 })
      }
      case '/api/queries/qb.app.conversation-messages':
        return json(200, { items: MESSAGES, offset: 0, limit: 500 })
      case '/api/queries/qb.app.conversation-blocks':
        return json(200, { items: blocks, offset: 0, limit: 100 })
      case '/api/queries/qb.app.conversations-unread':
        return json(200, { items: [{ unread: conversations.filter((c) => c.unread).length }], offset: 0, limit: 1 })
      case '/api/processes/QB_CONVERSATION_READ/latest':
        conversations = conversations.map((c) => ({ ...c, unread: false }))
        return json(200, { output: { conversationId: ID, status: 'OPEN' } })
      case '/api/processes/QB_CONVERSATION_SEND/latest':
        if (options.refusal) return json(422, options.refusal)
        return json(200, { output: { conversationId: ID, messageId: 'm3', created: false } })
      case '/api/processes/QB_CONVERSATION_OPEN/latest':
        conversations = [{ ...CONVERSATION, unread: false }]
        return json(200, { output: { conversationId: ID, messageId: 'm1', created: true } })
      case '/api/processes/QB_CONVERSATION_BLOCK/latest':
        conversations = conversations.map((c) => ({ ...c, status: 'CLOSED', closedBy: 'TAKER' }))
        blocks = [{ blockId: 'b1', sponsorCompany: 'Acme Tea', createdAt: '2026-11-02T00:00:00Z' }]
        return json(200, { output: { blockId: 'b1', closed: 1 } })
      case '/api/processes/QB_CONVERSATION_UNBLOCK/latest':
        blocks = []
        return json(200, { output: { blockId: null, closed: 0 } })
      default:
        return json(404, {})
    }
  })
  const processCalls = (name: string) =>
    posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body)
  return { processCalls }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  configureClient({ entry: 'app' })
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
  cleanup()
  window.history.replaceState(null, '', '/')
})

describe('conversations (Q82)', () => {
  it('reads the paths and leads a reply message to its conversation', () => {
    expect(conversationOf(conversationPath(ID))).toBe(ID)
    expect(conversationOf('/conversations')).toBeNull()
    expect(conversationOf('/conversations/a/b')).toBeNull()
    expect(conversationOf('/conversations/%E0%A4%A')).toBeNull()
    expect(targetOf({ itemKind: 'MESSAGE', action: 'CONVERSATION', actionId: ID, publicationId: PUBLICATION }))
      .toBe(`/conversations/${ID}`)
  })

  it('lists the conversations, new replies first marked', async () => {
    server()
    window.history.replaceState(null, '', '/conversations')
    render(<App />)
    const row = await screen.findByTestId('conversation-row')
    expect(row).toHaveTextContent('Acme Tea')
    expect(row).toHaveTextContent('Capitals')
    expect(row).toHaveAttribute('data-unread', 'true')
    await expectAccessible()
  })

  it('shows a conversation, marks it read and sends a message', async () => {
    const { processCalls } = server()
    window.history.replaceState(null, '', conversationPath(ID))
    render(<App />)
    const messages = await screen.findAllByTestId('conversation-message')
    expect(messages.map((m) => m.getAttribute('data-sender'))).toEqual(['TAKER', 'SPONSOR'])
    expect(screen.getByTestId('conversation-sponsor')).toHaveTextContent('Acme Tea')
    await waitFor(() => expect(processCalls('QB_CONVERSATION_READ')).toEqual([{ conversationId: ID }]))
    await userEvent.type(screen.getByTestId('conversation-body'), 'Thank you!')
    await userEvent.click(screen.getByTestId('conversation-send'))
    await waitFor(() => expect(processCalls('QB_CONVERSATION_SEND')).toEqual([
      { conversationId: ID, body: 'Thank you!' }]))
    await waitFor(() => expect(screen.getByTestId('conversation-body')).toHaveValue(''))
    await expectAccessible()
  })

  it('shows the server\'s reason when a message is refused', async () => {
    server({ refusal: { status: 422, title: 'Unprocessable', detail: 'Rejected', violations: [{ field: 'body',
      code: 'QB_CONVERSATION_ADDRESS', message: 'Messages are plain text: please leave out web or e-mail addresses.' }] } })
    window.history.replaceState(null, '', conversationPath(ID))
    render(<App />)
    await userEvent.type(await screen.findByTestId('conversation-body'), 'me@example.com')
    await userEvent.click(screen.getByTestId('conversation-send'))
    expect(await screen.findByTestId('conversation-error')).toHaveTextContent('plain text')
  })

  it('blocks the Sponsor, then unblocks them from the list', async () => {
    const { processCalls } = server()
    window.history.replaceState(null, '', conversationPath(ID))
    render(<App />)
    await userEvent.click(await screen.findByTestId('conversation-block-open'))
    await userEvent.click(await screen.findByTestId('conversation-block-confirm'))
    await waitFor(() => expect(processCalls('QB_CONVERSATION_BLOCK')).toEqual([{ conversationId: ID }]))
    expect(await screen.findByTestId('conversation-closed')).toHaveTextContent('You closed')
    cleanup()

    window.history.replaceState(null, '', '/conversations')
    render(<App />)
    const block = await screen.findByTestId('conversation-block')
    expect(block).toHaveTextContent('Acme Tea')
    await userEvent.click(within(block).getByTestId('conversation-unblock'))
    await waitFor(() => expect(processCalls('QB_CONVERSATION_UNBLOCK')).toEqual([{ blockId: 'b1' }]))
  })

  it('contacts the Sponsor from a result, then offers the conversation', async () => {
    const { processCalls } = server({ conversations: [] })
    render(<QueryClientProvider client={new QueryClient()}>
      <ContactSponsor publicationId={PUBLICATION} company="Acme Tea" />
    </QueryClientProvider>)
    await userEvent.click(await screen.findByTestId('result-contact-sponsor'))
    const dialog = await screen.findByTestId('contact-sponsor-dialog')
    expect(dialog).toHaveTextContent('Write to Acme Tea')
    await expectAccessible()
    await userEvent.type(within(dialog).getByTestId('contact-sponsor-body'), 'Where is my prize?')
    await userEvent.click(within(dialog).getByTestId('contact-sponsor-send'))
    await waitFor(() => expect(processCalls('QB_CONVERSATION_OPEN')).toEqual([
      { publicationId: PUBLICATION, body: 'Where is my prize?' }]))
    await waitFor(() => expect(window.location.pathname).toBe(conversationPath(ID)))
    expect(await screen.findByTestId('result-contact-sponsor')).toHaveAttribute('data-existing', 'true')
  })
})
