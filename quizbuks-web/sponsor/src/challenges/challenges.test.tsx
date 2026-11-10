import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { challengePath, challengeResultPath, challengeRouteOf, challengesOf, CHALLENGES_PATH } from './api'
import { challengesMessages } from './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }
const TREE = 'c1'
const PUB = 'p1'
const ATTEMPT = 'a1'
const pass = { op: 'ALL', items: [{ passed: true }] }

/** One node of the operator's copy, as `qb.sponsor.challenges` gives it. */
function row(over: Record<string, unknown> = {}) {
  return {
    approved: true, treeId: TREE, templateTreeId: 'tmpl', treeName: JSON.stringify({ en: 'Sponsor skills' }),
    nodeId: 'start', name: JSON.stringify({ en: 'Getting started' }), description: null,
    condition: JSON.stringify(pass), seq: 1, lit: false, litAt: null, unmet: '[]', refs: '[]',
    gates: 'qb.tree.gate.digital-prize-node', grantAmount: 200,
    publications: JSON.stringify([{ publicationId: PUB, name: 'First steps', translations: null,
      endAt: '2026-12-01T00:00:00Z', attemptId: null, status: null, deadline: null, startable: true }]),
    ...over,
  }
}

function started() {
  const now = new Date()
  return {
    attemptId: ATTEMPT, publicationId: PUB, status: 'IN_PROGRESS', startedAt: now.toISOString(),
    deadline: new Date(now.getTime() + 300_000).toISOString(), serverTime: now.toISOString(), timeLimitSec: 300,
    currentIndex: 0, resumed: false, answers: [], kind: 'QUIZ',
    questions: [{ no: 1, stem: 'What lights a node?', image: null, points: 5, selection: 'SINGLE',
      options: [{ no: 1, text: 'A pass', image: null }, { no: 2, text: 'A payment', image: null }] }],
  }
}

function finished(passed: boolean) {
  const base = started()
  return { attemptId: ATTEMPT, publicationId: PUB, name: 'First steps', status: 'SUBMITTED', kind: 'QUIZ',
    deadline: base.deadline, serverTime: new Date().toISOString(), score: passed ? 5 : 0, fullScore: 5, passScore: 5,
    passed, reward: 0, rewardStatus: 'NONE', rewardMethod: 'PLATFORM', cash: null, redeem: null, digital: null,
    questions: base.questions, result: [{ no: 1, selected: [passed ? 1 : 2], correct: [1], points: 5,
      earned: passed ? 5 : 0 }] }
}

/** The server as the challenges see it: an approved Sponsor of an operator with one copy and one challenge. */
function server(options: { rows?: () => unknown[]; passed?: boolean } = {}) {
  const sent: { name: string; input: Record<string, unknown> }[] = []
  const rows = options.rows ?? (() => [row()])
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const process = /^\/api\/processes\/([^/]+)\//.exec(path)
    if (process && request.method === 'POST') {
      const body = await request.clone().json() as Record<string, unknown>
      sent.push({ name: process[1], input: body })
      switch (process[1]) {
        case 'QB_SPONSOR_ATTEMPT_START': return json(200, { output: started() })
        case 'QB_SPONSOR_ATTEMPT_SAVE': return json(200, { output: { attemptId: ATTEMPT, savedAt: new Date().toISOString() } })
        case 'QB_SPONSOR_ATTEMPT_SUBMIT': return json(200, { output: { attemptId: ATTEMPT, status: 'SUBMITTED' } })
        case 'QB_SPONSOR_ATTEMPT_RESULT': return json(200, { output: finished(options.passed ?? true) })
        default: return json(404, {})
      }
    }
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.sponsor.challenge'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/queries/qb.sponsor.challenges':
        return json(200, { items: rows(), offset: 0, limit: 1000 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.sponsor.conversations-unread':
        return json(200, { items: [{ unread: 0 }], offset: 0, limit: 1 })
      default:
        return json(404, {})
    }
  })
  return { sent }
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

describe('the Sponsor\'s challenges (F-S4c)', () => {
  it('has every text in every language, and its addresses', () => {
    const english = keyPaths(challengesMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(challengesMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
    expect(challengeRouteOf(challengePath('p 1'))).toEqual({ publicationId: 'p 1' })
    expect(challengeRouteOf(challengeResultPath('a1'))).toEqual({ attemptId: 'a1' })
    expect(challengeRouteOf(CHALLENGES_PATH)).toBeNull()
    expect(challengeRouteOf(`${CHALLENGES_PATH}/a/b`)).toBeNull()
  })

  it('reads the nodes as maps, with their grants and challenges', () => {
    const { approved, maps } = challengesOf([row(), row({ nodeId: 'next', seq: 2, grantAmount: null,
      publications: '[]' })])
    expect(approved).toBe(true)
    expect(maps).toHaveLength(1)
    expect(maps[0].graph.nodes.map((n) => n.nodeId)).toEqual(['start', 'next'])
    expect([...maps[0].grants]).toEqual([['start', 200]])
    expect(maps[0].publications.get('start')?.[0]).toMatchObject({ publicationId: PUB, startable: true })
  })

  it('lists the operator\'s copy with a challenge to answer, what the node opens and grants', async () => {
    server()
    window.history.replaceState(null, '', CHALLENGES_PATH)
    render(<App />)
    const map = await screen.findByTestId('challenge-map')
    expect(map).toHaveAttribute('data-tree', TREE)
    await userEvent.click(within(map).getAllByTestId('knowledge-map-node')[0])
    expect(within(map).getByTestId('knowledge-map-grant')).toHaveTextContent('200')
    expect(within(map).getByTestId('knowledge-map-gives')).toHaveTextContent('digital prizes')
    expect(within(map).getByTestId('challenge-start')).toHaveAttribute('href', expect.stringContaining(challengePath(PUB)))
    await expectAccessible()
  })

  it('says when there is none, and when the Sponsor is not approved yet', async () => {
    server({ rows: () => [row({ approved: false, publications: '[]' })] })
    window.history.replaceState(null, '', CHALLENGES_PATH)
    render(<App />)
    expect(await screen.findByTestId('challenges-not-approved')).toBeInTheDocument()
  })

  it('answers a challenge with the shared player and shows the node lit with what it gave', async () => {
    let lit = false
    const { sent } = server({ rows: () => [row({ lit, publications: JSON.stringify([{ publicationId: PUB,
      name: 'First steps', translations: null, endAt: null, attemptId: ATTEMPT, status: lit ? 'SUBMITTED' : 'IN_PROGRESS',
      deadline: null, startable: !lit }]) })] })
    window.history.replaceState(null, '', challengePath(PUB))
    render(<App />)
    expect(await screen.findByTestId('play-question')).toHaveTextContent('What lights a node?')
    await userEvent.click(screen.getAllByTestId('play-option')[0])
    lit = true
    await userEvent.click(screen.getByTestId('play-submit'))
    await userEvent.click(await screen.findByRole('button', { name: 'Submit answers' }))
    const result = await screen.findByTestId('challenge-result')
    expect(result).toHaveAttribute('data-passed', 'true')
    expect(await within(result).findByTestId('challenge-grant')).toHaveTextContent('200')
    expect(within(result).getByTestId('challenge-gate')).toHaveTextContent('digital prizes')
    expect(within(result).getAllByTestId('result-question')).toHaveLength(1)
    expect(sent.map((s) => s.name)).toEqual(expect.arrayContaining(['QB_SPONSOR_ATTEMPT_START',
      'QB_SPONSOR_ATTEMPT_SUBMIT', 'QB_SPONSOR_ATTEMPT_RESULT']))
    expect(sent.find((s) => s.name === 'QB_SPONSOR_ATTEMPT_SUBMIT')?.input).toMatchObject({ attemptId: ATTEMPT,
      publicationId: PUB })
    await expectAccessible()
  })

  it('says to wait for the next challenge when not passed', async () => {
    server({ passed: false })
    window.history.replaceState(null, '', challengeResultPath(ATTEMPT))
    render(<App />)
    const result = await screen.findByTestId('challenge-result')
    expect(result).toHaveAttribute('data-passed', 'false')
    expect(within(result).getByTestId('challenge-next')).toHaveTextContent('next challenge')
    await waitFor(() => expect(within(result).queryByTestId('challenge-grant')).toBeNull())
  })
})
