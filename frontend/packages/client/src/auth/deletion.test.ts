import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/problem'
import { configureSession, session } from '../api/session'
import { json, urlOf } from '../test/locks'
import {
  cancelAccountDeletion,
  confirmAccountDeletion,
  getAccountDeletion,
  requestAccountDeletion,
  requestAccountDeletionLink,
  unacknowledgedNotices,
} from './deletion'

type Call = { method: string; path: string; body: unknown; auth: string | null }

/** Records the calls and answers each with `answer`. */
function server(answer: (call: Call) => Response) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(urlOf(input), 'http://localhost'), init)
    const text = await request.text()
    const call = {
      method: request.method,
      path: new URL(request.url).pathname,
      body: text ? JSON.parse(text) : undefined,
      auth: request.headers.get('Authorization'),
    }
    calls.push(call)
    return answer(call)
  }))
  return calls
}

describe('account deletion (decision D62)', () => {
  beforeEach(() => configureSession('portal'))
  afterEach(() => {
    session.clear()
    configureSession('admin')
    vi.unstubAllGlobals()
  })

  it('reads, asks for and withdraws the signed-in user\'s deletion in the session', async () => {
    session.store('access', 'refresh')
    const calls = server(({ method, path }) => {
      if (method === 'GET') return json(200, { available: true, pending: null, notices: [{ code: 'orders', message: 'Orders stay.' }] })
      if (path.endsWith('/cancel')) return json(200, { requestId: 'r1' })
      return json(200, { requestId: 'r1', dueTime: '2026-11-11T09:00:00Z' })
    })
    expect((await getAccountDeletion()).notices).toEqual([{ code: 'orders', message: 'Orders stay.' }])
    expect(await requestAccountDeletion({ currentPassword: 'pw', acknowledgedNotices: ['orders'] }))
      .toEqual({ requestId: 'r1', dueTime: '2026-11-11T09:00:00Z' })
    expect(await cancelAccountDeletion()).toEqual({ requestId: 'r1' })
    expect(calls.map((c) => [c.method, c.path, c.body, c.auth])).toEqual([
      ['GET', '/api/auth/account/deletion', undefined, 'Bearer access'],
      ['POST', '/api/auth/account/deletion', { currentPassword: 'pw', acknowledgedNotices: ['orders'] }, 'Bearer access'],
      ['POST', '/api/auth/account/deletion/cancel', undefined, 'Bearer access'],
    ])
  })

  it("asks for a link and confirms it without a session, at the frontend's entry unless another is named", async () => {
    const calls = server(({ path }) => path.endsWith('/confirm')
      ? json(200, { requestId: 'r2', dueTime: '2026-11-11T09:00:00Z' })
      : new Response(null, { status: 202, headers: { 'Content-Length': '0' } }))
    await requestAccountDeletionLink({ email: ' ann@example.com ' })
    await requestAccountDeletionLink({ email: 'ann@example.com', entry: 'shop' })
    expect((await confirmAccountDeletion({ token: 't1', acknowledgedNotices: [] })).requestId).toBe('r2')
    expect(calls.map((c) => [c.path, c.body, c.auth])).toEqual([
      ['/api/auth/account-deletion', { email: 'ann@example.com', entry: 'portal' }, null],
      ['/api/auth/account-deletion', { email: 'ann@example.com', entry: 'shop' }, null],
      ['/api/auth/account-deletion/confirm', { token: 't1', acknowledgedNotices: [] }, null],
    ])
  })

  it('reads the notes a refusal names as not confirmed', async () => {
    server(() => json(422, { violations: [
      { field: null, ruleCode: 'ACCOUNT_DELETION_NOTICES', message: 'Confirm the notes.' },
      { field: null, ruleCode: 'erasure.notice.orders', message: 'Orders stay.' },
      { field: null, ruleCode: 'erasure.notice.points', message: 'Points are lost.' },
    ] }))
    const refused = await confirmAccountDeletion({ token: 't1', acknowledgedNotices: [] }).catch((e: unknown) => e)
    expect(unacknowledgedNotices(refused)).toEqual([
      { code: 'orders', message: 'Orders stay.' },
      { code: 'points', message: 'Points are lost.' },
    ])
    const other = new ApiError(422, { violations: [{ field: null, ruleCode: 'erasure.notice.x', message: 'x' }] })
    expect(unacknowledgedNotices(other)).toEqual([])
    expect(unacknowledgedNotices(new Error('x'))).toEqual([])
  })
})
