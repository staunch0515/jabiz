import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { treePathOf } from './api'
import { treeMessages } from './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }
const BASICS = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b01'

/** The server as the tree page sees it: an approved sponsor; the page asks for nothing of its own. */
function server() {
  const asked: string[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    asked.push(path)
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.publication.write'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.sponsor.conversations-unread':
        return json(200, { items: [{ unread: 0 }], offset: 0, limit: 1 })
      default:
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
  cleanup()
  window.history.replaceState(null, '', '/')
})

describe('the Sponsor\'s tree (Q85, being updated since F-S2c2)', () => {
  it('has every text in every language, and its address', () => {
    const english = keyPaths(treeMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(treeMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
    expect(treePathOf(BASICS)).toBe(`/tree?node=${BASICS}`)
  })

  it('stays at its address and says it is coming soon, reading no tree', async () => {
    const { asked } = server()
    window.history.replaceState(null, '', treePathOf(BASICS))
    render(<App />)
    const page = await screen.findByTestId('tree-page')
    expect(await screen.findByTestId('tree-soon')).toHaveTextContent('Coming soon')
    expect(page).toBeInTheDocument()
    expect(asked.filter((path) => path.startsWith('/api/queries/qb.sponsor.tree'))).toEqual([])
    await expectAccessible()
  })
})
