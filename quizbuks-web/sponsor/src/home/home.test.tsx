import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { placeOf } from '../shell/areas'
import { pageOf } from '../shell/routes'
import { NO_TODO, TODO_COLUMNS, type TodoCounts } from './api'
import { homeMessages } from './messages'
import { prizesBadge, TODO_ORDER, TODO_PATH, todoItems } from './todo'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const ALL: TodoCounts = { claimsToConfirm: 2, digitalToSend: 3, fundsShort: 1, rewardsWaiting: 4, invoicesOverdue: 1,
  publicationsInReview: 2, pageInReview: 1, sharedVideosInReview: 1, videoUsesToConfirm: 5, creditsExpiring: 1 }

interface Options {
  status?: string
  counts?: TodoCounts | 'fail'
  unread?: number
  /** Every part of the hub's checklist that is needed, set up. */
  complete?: boolean
}

/** The server of an approved (or not) sponsor; what is not named answers "not found". Records the todo asks. */
function server({ status = 'APPROVED', counts = NO_TODO, unread = 0, complete = true }: Options = {}) {
  const asked: Record<string, unknown>[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    const page = (items: unknown[]) => json(200, { items, offset: 0, limit: items.length })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.publication.write'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: status, company: 'Acme',
          contactName: 'Sara' } }] })
      case '/api/queries/qb.sponsor.todo':
        asked.push((body?.params ?? {}) as Record<string, unknown>)
        return counts === 'fail' ? json(403, { title: 'Forbidden' }) : page([counts])
      case '/api/queries/qb.sponsor.conversations-unread':
        return page([{ unread }])
      case '/api/datasets/urn:jabiz:dataset:me:QbSponsorPage/query':
        return json(200, { items: complete ? [{ id: 'p', version: 1, attributes: { status: 'PUBLISHED',
          handle: 'acme' } }] : [] })
      default:
        // What the checklist reads (products, staff, the mail server): none set up.
        if (path.startsWith('/api/datasets/')) return json(200, { items: [] })
        if (path.startsWith('/api/queries/')) return page([])
        return json(404, {})
    }
  })
  return { asked }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
})

describe('the to-do items (F-F3b)', () => {
  it('leave out what has nothing to do, in the list\'s order', () => {
    const none = { counts: NO_TODO, unreadConversations: 0, onboardingInReview: false, profileMissing: 0 }
    expect(todoItems(none)).toEqual([])
    expect(todoItems({ ...none, counts: undefined })).toEqual([])
    expect(todoItems({ ...none, counts: { ...NO_TODO, invoicesOverdue: 2, claimsToConfirm: 1 }, unreadConversations: 7,
      onboardingInReview: true, profileMissing: 2 }).map((item) => [item.key, item.count])).toEqual([
      ['claimsToConfirm', 1], ['unreadConversations', 7], ['invoicesOverdue', 2], ['onboardingInReview', 1],
      ['profileMissing', 2]])
  })

  it('have a place for every column and every one of them leads to a page of an area', () => {
    for (const column of TODO_COLUMNS) expect(TODO_ORDER, column).toContain(column)
    expect(new Set(TODO_ORDER).size).toBe(TODO_ORDER.length)
    for (const key of TODO_ORDER) {
      const path = TODO_PATH[key]
      const url = new URL(path, 'https://quizbuks.test')
      expect(pageOf(url.pathname, url.search, { kudos: true, benefits: true }), path).not.toBeNull()
      expect(placeOf(url.pathname), path).not.toBeNull()
    }
  })

  it('count the prizes badge as claims and digital prizes together', () => {
    expect(prizesBadge(undefined)).toBe(0)
    expect(prizesBadge({ ...NO_TODO, claimsToConfirm: 2, digitalToSend: 3, fundsShort: 9 })).toBe(5)
  })

  it('are worded in every language, with no retired names', () => {
    const english = keyPaths(homeMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(homeMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
      expect(JSON.stringify(homeMessages[lng])).not.toMatch(/后台|管理画面|コンソール|console|back office/i)
    }
    for (const key of TODO_ORDER) expect(homeMessages.en.home.todo.items[key], key).toBeTruthy()
  })
})

describe('the home\'s to-do list (F-F3b)', () => {
  it('shows every item with its count, leading straight to the page that handles it', async () => {
    const { asked } = server({ counts: ALL, unread: 6, complete: false })
    window.history.replaceState(null, '', '/home')
    render(<App />)
    const todo = await screen.findByTestId('todo')
    await waitFor(() => expect(within(todo).getAllByRole('link').length).toBe(TODO_ORDER.length - 1))
    const expected: [string, string, string][] = [
      ['claimsToConfirm', '2', '/prizes/redeem'],
      ['digitalToSend', '3', '/prizes/digital'],
      ['unreadConversations', '6', '/messages/conversations'],
      ['fundsShort', '1', '/publications?status=FUNDS_SHORT'],
      ['rewardsWaiting', '4', '/money/wallet'],
      ['invoicesOverdue', '1', '/money/bills'],
      ['creditsExpiring', '1', '/money/account'],
      ['publicationsInReview', '2', '/publications?status=PENDING_REVIEW'],
      ['pageInReview', '1', '/profile/page'],
      ['sharedVideosInReview', '1', '/assets/media'],
      ['videoUsesToConfirm', '5', '/assets/media'],
      ['profileMissing', '1', '/profile'],
    ]
    expect(within(todo).getAllByRole('link').map((link) => link.getAttribute('data-testid')))
      .toEqual(expected.map(([key]) => `todo-${key}`))
    for (const [key, count, path] of expected) {
      const link = within(todo).getByTestId(`todo-${key}`)
      expect(link, key).toHaveAttribute('data-count', count)
      expect(link, key).toHaveAttribute('href', expect.stringContaining(path))
      expect(link, key).toHaveTextContent(count)
    }
    expect(within(todo).getByTestId('todo-claimsToConfirm')).toHaveTextContent('Prize claims waiting for you to confirm')
    // The server is asked the time of the client's minute (the credit's notice runs from it).
    expect(asked[0]).toEqual({ at: expect.stringMatching(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00\.000Z$/) })
    // The area of the prizes counts the claims and the digital prizes in its entry.
    await waitFor(() => expect(screen.getByTestId('menu-prizes')).toHaveTextContent('5'))
    await expectAccessible()
  })

  it('follows a link to the page it names', async () => {
    server({ counts: { ...NO_TODO, claimsToConfirm: 1 } })
    window.history.replaceState(null, '', '/home')
    render(<App />)
    await userEvent.click(await screen.findByTestId('todo-claimsToConfirm'))
    await waitFor(() => expect(window.location.pathname).toBe('/prizes/redeem'))
  })

  it('shows nothing for a count of zero, and says all is done when nothing is left', async () => {
    server({ counts: { ...NO_TODO, invoicesOverdue: 1 } })
    window.history.replaceState(null, '', '/home')
    render(<App />)
    const todo = await screen.findByTestId('todo')
    expect(await within(todo).findAllByRole('link')).toHaveLength(1)
    expect(within(todo).getByTestId('todo-invoicesOverdue')).toHaveAttribute('href', expect.stringContaining('/money/bills'))
    expect(within(todo).queryByTestId('todo-claimsToConfirm')).toBeNull()
    expect(within(todo).queryByTestId('todo-empty')).toBeNull()
    // The menu has no badge on the prizes when there is nothing for them.
    expect(screen.getByTestId('menu-prizes')).not.toHaveTextContent(/\d/)
  })

  it('says all is done once everything is read and nothing is left', async () => {
    server()
    window.history.replaceState(null, '', '/home')
    render(<App />)
    expect(await screen.findByTestId('todo-empty')).toHaveTextContent('All done')
    await expectAccessible()
  })

  it('does not say all is done when the counts could not be read', async () => {
    server({ counts: 'fail' })
    window.history.replaceState(null, '', '/home')
    render(<App />)
    await screen.findByTestId('dashboard-hero')
    await new Promise((resolve) => setTimeout(resolve, 200))
    expect(screen.queryByTestId('todo-empty')).toBeNull()
  })

  it('before the approval lists only the application and the profile, and asks the server for no counts', async () => {
    const { asked } = server({ status: 'PENDING', complete: false })
    window.history.replaceState(null, '', '/home')
    render(<App />)
    const todo = await screen.findByTestId('todo')
    await waitFor(() => expect(within(todo).getAllByRole('link').length).toBeGreaterThan(0))
    expect(within(todo).getAllByRole('link').map((link) => link.getAttribute('data-testid')))
      .toEqual(['todo-onboardingInReview', 'todo-profileMissing'])
    expect(asked).toEqual([])
    expect(screen.queryByTestId('metric-answering')).toBeNull()
  })
})

